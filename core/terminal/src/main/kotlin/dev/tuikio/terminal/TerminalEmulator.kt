package dev.tuikio.terminal

/**
 * An xterm-compatible terminal state machine: UTF-8 decoding, a VT500-style escape
 * sequence parser (after Paul Williams' DEC parser), and the screen operations that
 * ncurses issues for TERM=xterm-256color.
 *
 * Not thread-safe: callers serialize [feed], [resize] and rendering on [lock].
 * Damage is tracked per row; renderers call [Screen.collectDamage] on [screen].
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    scrollback: Int = 1000,
    /** Bytes the terminal answers with (cursor position reports, device attributes). */
    private val reply: (ByteArray) -> Unit = {},
) {
    interface Listener {
        fun onTitleChanged(title: String) {}
        fun onBell() {}
    }

    enum class MouseTracking { OFF, X10, NORMAL, BUTTON_EVENT, ANY_EVENT }
    enum class MouseEncoding { DEFAULT, UTF8, SGR, URXVT }
    enum class CursorShape { BLOCK, UNDERLINE, BAR }

    val lock = Any()
    var listener: Listener? = null

    var cols = cols
        private set
    var rows = rows
        private set

    private val mainScreen = Screen(cols, rows, scrollback)
    private val altScreen = Screen(cols, rows, 0)
    var screen: Screen = mainScreen
        private set
    val isAltScreen: Boolean get() = screen === altScreen

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    private var pendingWrap = false
    var cursorVisible = true
        private set
    var cursorShape = CursorShape.BLOCK
        private set
    var cursorBlink = true
        private set

    private var pen = CellStyle.DEFAULT
    private var top = 0
    private var bottom = rows - 1

    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var autoWrap = true
        private set
    var originMode = false
        private set
    var insertMode = false
        private set
    var newLineMode = false
        private set
    var reverseVideo = false
        private set
    var bracketedPaste = false
        private set
    var focusReporting = false
        private set
    var mouseTracking = MouseTracking.OFF
        private set
    var mouseEncoding = MouseEncoding.DEFAULT
        private set

    var title = ""
        private set

    /** Increments on every state change, so a renderer can skip idle frames cheaply. */
    var generation = 0L
        private set

    private val charsets = intArrayOf(CS_ASCII, CS_ASCII, CS_ASCII, CS_ASCII)
    private var gl = 0
    private var tabs = BooleanArray(cols) { it % 8 == 0 && it != 0 }

    private class Saved(
        val row: Int, val col: Int, val pen: Long, val gl: Int, val charsets: IntArray,
        val origin: Boolean, val wrap: Boolean, val pendingWrap: Boolean,
    )

    private var savedMain: Saved? = null
    private var savedAlt: Saved? = null

    private var lastPrinted = -1
    private var lastRow = -1
    private var lastCol = -1

    // ---------------------------------------------------------------- parser state
    private var state = GROUND
    private val params = IntArray(MAX_PARAMS)
    private val subParam = BooleanArray(MAX_PARAMS)
    private var nParams = 0
    private var privateMarker = 0
    private var intermediates = 0
    private var nIntermediates = 0
    private val osc = StringBuilder()

    private var utf8Need = 0
    private var utf8Cp = 0
    private var utf8Min = 0

    // ---------------------------------------------------------------- input

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            i++
            if (utf8Need == 0) {
                when {
                    b < 0x80 -> onCodePoint(b)
                    b and 0xE0 == 0xC0 -> { utf8Need = 1; utf8Cp = b and 0x1F; utf8Min = 0x80 }
                    b and 0xF0 == 0xE0 -> { utf8Need = 2; utf8Cp = b and 0x0F; utf8Min = 0x800 }
                    b and 0xF8 == 0xF0 -> { utf8Need = 3; utf8Cp = b and 0x07; utf8Min = 0x10000 }
                    else -> onCodePoint(REPLACEMENT)
                }
            } else if (b and 0xC0 == 0x80) {
                utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                utf8Need--
                if (utf8Need == 0) {
                    val cp = utf8Cp
                    val bad = cp < utf8Min || cp > 0x10FFFF || cp in 0xD800..0xDFFF
                    onCodePoint(if (bad) REPLACEMENT else cp)
                }
            } else {
                // Truncated sequence: emit a replacement and reprocess this byte.
                utf8Need = 0
                onCodePoint(REPLACEMENT)
                i--
            }
        }
        generation++
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun onCodePoint(cp: Int) {
        // C0 controls act in (almost) every state.
        if (cp < 0x20 || cp == 0x7F) {
            when {
                cp == 0x1B -> {
                    if (state == OSC_STRING) dispatchOsc()
                    clearSequence()
                    state = ESCAPE
                }
                cp == 0x18 || cp == 0x1A -> state = GROUND
                state == OSC_STRING -> if (cp == 0x07) { dispatchOsc(); state = GROUND }
                state == IGNORE_STRING -> Unit
                cp == 0x7F -> Unit
                else -> execute(cp)
            }
            return
        }
        when (state) {
            GROUND -> print(cp)
            ESCAPE -> when {
                cp in 0x20..0x2F -> { collectIntermediate(cp); state = ESCAPE_INTERMEDIATE }
                cp == '['.code -> { clearSequence(); state = CSI_ENTRY }
                cp == ']'.code -> { osc.setLength(0); state = OSC_STRING }
                cp == 'P'.code || cp == 'X'.code || cp == '^'.code || cp == '_'.code -> state = IGNORE_STRING
                cp in 0x30..0x7E -> { escDispatch(cp); state = GROUND }
                else -> state = GROUND
            }
            ESCAPE_INTERMEDIATE -> when (cp) {
                in 0x20..0x2F -> collectIntermediate(cp)
                in 0x30..0x7E -> { escDispatch(cp); state = GROUND }
                else -> state = GROUND
            }
            CSI_ENTRY, CSI_PARAM -> when (cp) {
                in '0'.code..'9'.code -> {
                    if (nParams == 0) nParams = 1
                    val idx = nParams - 1
                    val cur = if (params[idx] < 0) 0 else params[idx]
                    params[idx] = (cur * 10 + (cp - '0'.code)).coerceAtMost(65535)
                    state = CSI_PARAM
                }
                ';'.code, ':'.code -> {
                    if (nParams == 0) nParams = 1
                    if (nParams < MAX_PARAMS) {
                        params[nParams] = -1
                        subParam[nParams] = cp == ':'.code
                        nParams++
                    }
                    state = CSI_PARAM
                }
                in 0x3C..0x3F -> if (state == CSI_ENTRY) { privateMarker = cp; state = CSI_PARAM } else state = CSI_IGNORE
                in 0x20..0x2F -> { collectIntermediate(cp); state = CSI_INTERMEDIATE }
                in 0x40..0x7E -> { csiDispatch(cp); state = GROUND }
                else -> state = CSI_IGNORE
            }
            CSI_INTERMEDIATE -> when (cp) {
                in 0x20..0x2F -> collectIntermediate(cp)
                in 0x30..0x3F -> state = CSI_IGNORE
                in 0x40..0x7E -> { csiDispatch(cp); state = GROUND }
                else -> state = CSI_IGNORE
            }
            CSI_IGNORE -> if (cp in 0x40..0x7E) state = GROUND
            OSC_STRING -> if (osc.length < MAX_OSC) osc.appendCodePoint(cp)
            IGNORE_STRING -> Unit
        }
    }

    private fun clearSequence() {
        nParams = 0
        params.fill(-1)
        subParam.fill(false)
        privateMarker = 0
        intermediates = 0
        nIntermediates = 0
    }

    private fun collectIntermediate(cp: Int) {
        if (nIntermediates < 2) intermediates = (intermediates shl 8) or cp
        nIntermediates++
    }

    private fun param(i: Int, default: Int): Int {
        if (i >= nParams) return default
        val v = params[i]
        return if (v < 0) default else v
    }

    /** Parameter treated as a count: missing or 0 both mean 1. */
    private fun count(i: Int): Int = param(i, 1).coerceAtLeast(1)

    // ---------------------------------------------------------------- C0 controls

    private fun execute(cp: Int) {
        when (cp) {
            0x07 -> listener?.onBell()
            0x08 -> {
                if (cursorCol > 0) cursorCol--
                pendingWrap = false
            }
            0x09 -> tab(1)
            0x0A, 0x0B, 0x0C -> {
                lineFeed()
                if (newLineMode) cursorCol = 0
            }
            0x0D -> { cursorCol = 0; pendingWrap = false }
            0x0E -> gl = 1
            0x0F -> gl = 0
        }
    }

    // ---------------------------------------------------------------- printing

    private fun print(cp0: Int) {
        var cp = cp0
        val set = charsets[gl]
        if (set == CS_DEC_SPECIAL && cp in 0x5F..0x7E) {
            cp = DEC_SPECIAL[cp - 0x5F]
        } else if (set == CS_UK && cp == '#'.code) {
            cp = 0xA3
        }
        val w = UnicodeWidth.of(cp)
        if (w == 0) {
            if (lastRow in 0 until rows && lastCol in 0 until cols) {
                screen[lastRow].addCombining(lastCol, cp)
                screen.markDirty(lastRow)
            }
            return
        }
        if (pendingWrap && autoWrap) {
            screen[cursorRow].wrapped = true
            cursorCol = 0
            lineFeed()
        }
        pendingWrap = false
        if (w == 2 && cursorCol == cols - 1) {
            if (autoWrap) {
                putCell(cursorRow, cursorCol, 0, pen and CellStyle.CELL_FLAGS.inv())
                screen[cursorRow].wrapped = true
                cursorCol = 0
                lineFeed()
            } else {
                cursorCol = (cols - 2).coerceAtLeast(0)
            }
        }
        if (insertMode) insertBlanks(w)
        val style = pen and CellStyle.CELL_FLAGS.inv()
        if (w == 2 && cols >= 2) {
            putCell(cursorRow, cursorCol, cp, style or CellStyle.WIDE)
            putCell(cursorRow, cursorCol + 1, 0, style or CellStyle.WIDE_TAIL)
        } else {
            putCell(cursorRow, cursorCol, cp, style)
        }
        lastPrinted = cp
        lastRow = cursorRow
        lastCol = cursorCol
        if (cursorCol + w >= cols) {
            cursorCol = cols - 1
            pendingWrap = autoWrap
        } else {
            cursorCol += w
        }
    }

    /** Writes one cell, repairing any wide character it partially overwrites. */
    private fun putCell(row: Int, col: Int, cp: Int, style: Long) {
        if (col !in 0 until cols) return
        val line = screen[row]
        val old = line.styles[col]
        if (CellStyle.has(old, CellStyle.WIDE_TAIL) && col > 0 && !CellStyle.has(style, CellStyle.WIDE_TAIL)) {
            line.chars[col - 1] = 0
            line.styles[col - 1] = line.styles[col - 1] and CellStyle.CELL_FLAGS.inv()
        }
        if (CellStyle.has(old, CellStyle.WIDE) && col + 1 < cols && !CellStyle.has(style, CellStyle.WIDE)) {
            line.chars[col + 1] = 0
            line.styles[col + 1] = line.styles[col + 1] and CellStyle.CELL_FLAGS.inv()
        }
        line.chars[col] = cp
        line.styles[col] = style
        line.combining?.remove(col)
        screen.markDirty(row)
    }

    private fun lineFeed() {
        if (cursorRow == bottom) {
            screen.scrollUp(top, bottom, 1, CellStyle.erased(pen))
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
        pendingWrap = false
    }

    private fun reverseIndex() {
        if (cursorRow == top) {
            screen.scrollDown(top, bottom, 1, CellStyle.erased(pen))
        } else if (cursorRow > 0) {
            cursorRow--
        }
        pendingWrap = false
    }

    private fun tab(n: Int) {
        repeat(n) {
            var c = cursorCol + 1
            while (c < cols - 1 && !tabs[c]) c++
            cursorCol = c.coerceAtMost(cols - 1)
        }
        pendingWrap = false
    }

    private fun backTab(n: Int) {
        repeat(n) {
            var c = cursorCol - 1
            while (c > 0 && !tabs[c]) c--
            cursorCol = c.coerceAtLeast(0)
        }
        pendingWrap = false
    }

    // ---------------------------------------------------------------- cursor

    private fun moveTo(row: Int, col: Int) {
        val minRow = if (originMode) top else 0
        val maxRow = if (originMode) bottom else rows - 1
        cursorRow = row.coerceIn(minRow, maxRow)
        cursorCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    private fun moveToOrigin(row1: Int, col1: Int) {
        val base = if (originMode) top else 0
        moveTo(base + row1 - 1, col1 - 1)
    }

    private fun saveCursor() {
        val s = Saved(cursorRow, cursorCol, pen, gl, charsets.copyOf(), originMode, autoWrap, pendingWrap)
        if (isAltScreen) savedAlt = s else savedMain = s
    }

    private fun restoreCursor() {
        val s = (if (isAltScreen) savedAlt else savedMain)
        if (s == null) {
            moveTo(0, 0)
            pen = CellStyle.DEFAULT
            return
        }
        pen = s.pen
        gl = s.gl
        s.charsets.copyInto(charsets)
        originMode = s.origin
        autoWrap = s.wrap
        cursorRow = s.row.coerceIn(0, rows - 1)
        cursorCol = s.col.coerceIn(0, cols - 1)
        pendingWrap = s.pendingWrap
    }

    // ---------------------------------------------------------------- editing

    private fun eraseInLine(row: Int, from: Int, to: Int) {
        screen[row].clear(from, to, CellStyle.erased(pen))
        if (from <= 0 && to >= cols) screen[row].wrapped = false
        screen.markDirty(row)
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(cursorRow, cursorCol, cols)
                for (r in cursorRow + 1 until rows) eraseInLine(r, 0, cols)
            }
            1 -> {
                for (r in 0 until cursorRow) eraseInLine(r, 0, cols)
                eraseInLine(cursorRow, 0, cursorCol + 1)
            }
            2 -> for (r in 0 until rows) eraseInLine(r, 0, cols)
            3 -> {
                for (r in 0 until rows) eraseInLine(r, 0, cols)
                screen.clearHistory()
            }
        }
    }

    private fun insertBlanks(n: Int) {
        val line = screen[cursorRow]
        val count = n.coerceAtMost(cols - cursorCol)
        if (count <= 0) return
        System.arraycopy(line.chars, cursorCol, line.chars, cursorCol + count, cols - cursorCol - count)
        System.arraycopy(line.styles, cursorCol, line.styles, cursorCol + count, cols - cursorCol - count)
        line.clear(cursorCol, cursorCol + count, CellStyle.erased(pen))
        fixWideEdges(line)
        screen.markDirty(cursorRow)
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorRow]
        val count = n.coerceAtMost(cols - cursorCol)
        if (count <= 0) return
        System.arraycopy(line.chars, cursorCol + count, line.chars, cursorCol, cols - cursorCol - count)
        System.arraycopy(line.styles, cursorCol + count, line.styles, cursorCol, cols - cursorCol - count)
        line.clear(cols - count, cols, CellStyle.erased(pen))
        fixWideEdges(line)
        screen.markDirty(cursorRow)
    }

    /** After shifting cells, blank any wide-char halves that lost their partner. */
    private fun fixWideEdges(line: TermLine) {
        for (c in 0 until cols) {
            val s = line.styles[c]
            val orphanTail = CellStyle.has(s, CellStyle.WIDE_TAIL) &&
                (c == 0 || !CellStyle.has(line.styles[c - 1], CellStyle.WIDE))
            val orphanHead = CellStyle.has(s, CellStyle.WIDE) &&
                (c == cols - 1 || !CellStyle.has(line.styles[c + 1], CellStyle.WIDE_TAIL))
            if (orphanTail || orphanHead) {
                line.chars[c] = 0
                line.styles[c] = s and CellStyle.CELL_FLAGS.inv()
            }
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow !in top..bottom) return
        screen.scrollDown(cursorRow, bottom, n, CellStyle.erased(pen))
        cursorCol = 0
        pendingWrap = false
    }

    private fun deleteLines(n: Int) {
        if (cursorRow !in top..bottom) return
        screen.scrollUp(cursorRow, bottom, n, CellStyle.erased(pen))
        cursorCol = 0
        pendingWrap = false
    }

    // ---------------------------------------------------------------- ESC dispatch

    private fun escDispatch(cp: Int) {
        if (nIntermediates > 0) {
            val inter = intermediates and 0xFF
            when (inter) {
                '('.code, ')'.code, '*'.code, '+'.code -> {
                    val slot = inter - '('.code
                    charsets[slot] = when (cp) {
                        '0'.code -> CS_DEC_SPECIAL
                        'A'.code -> CS_UK
                        else -> CS_ASCII
                    }
                }
                '#'.code -> if (cp == '8'.code) decAlign()
            }
            return
        }
        when (cp) {
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> lineFeed()
            'E'.code -> { cursorCol = 0; lineFeed() }
            'H'.code -> if (cursorCol < cols) tabs[cursorCol] = true
            'M'.code -> reverseIndex()
            'c'.code -> reset()
            '='.code -> applicationKeypad = true
            '>'.code -> applicationKeypad = false
            'n'.code -> gl = 2
            'o'.code -> gl = 3
        }
    }

    private fun decAlign() {
        for (r in 0 until rows) {
            val l = screen[r]
            for (c in 0 until cols) {
                l.chars[c] = 'E'.code
                l.styles[c] = CellStyle.DEFAULT
            }
        }
        screen.markAllDirty()
        top = 0
        bottom = rows - 1
        moveTo(0, 0)
    }

    // ---------------------------------------------------------------- CSI dispatch

    private fun csiDispatch(cp: Int) {
        val inter = if (nIntermediates == 1) intermediates and 0xFF else if (nIntermediates == 0) 0 else -1
        if (privateMarker == '?'.code) {
            when (cp) {
                'h'.code -> for (i in 0 until maxOf(nParams, 1)) setDecMode(param(i, 0), true)
                'l'.code -> for (i in 0 until maxOf(nParams, 1)) setDecMode(param(i, 0), false)
                'J'.code -> eraseInDisplay(param(0, 0))
                'K'.code -> when (param(0, 0)) {
                    0 -> eraseInLine(cursorRow, cursorCol, cols)
                    1 -> eraseInLine(cursorRow, 0, cursorCol + 1)
                    2 -> eraseInLine(cursorRow, 0, cols)
                }
            }
            return
        }
        if (privateMarker == '>'.code) {
            if (cp == 'c'.code) reply("\u001b[>0;10;1c")
            return
        }
        if (privateMarker != 0) return
        if (inter == ' '.code && cp == 'q'.code) {
            val p = param(0, 1)
            cursorShape = when (p) {
                3, 4 -> CursorShape.UNDERLINE
                5, 6 -> CursorShape.BAR
                else -> CursorShape.BLOCK
            }
            cursorBlink = p == 0 || p % 2 == 1
            return
        }
        if (inter == '!'.code && cp == 'p'.code) {
            softReset()
            return
        }
        if (inter != 0) return
        when (cp) {
            '@'.code -> insertBlanks(count(0))
            'A'.code -> moveCursorRow(-count(0))
            'B'.code, 'e'.code -> moveCursorRow(count(0))
            'C'.code, 'a'.code -> { cursorCol = (cursorCol + count(0)).coerceAtMost(cols - 1); pendingWrap = false }
            'D'.code -> { cursorCol = (cursorCol - count(0)).coerceAtLeast(0); pendingWrap = false }
            'E'.code -> { moveCursorRow(count(0)); cursorCol = 0 }
            'F'.code -> { moveCursorRow(-count(0)); cursorCol = 0 }
            'G'.code, '`'.code -> { cursorCol = (count(0) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'H'.code, 'f'.code -> moveToOrigin(count(0), count(1))
            'I'.code -> tab(count(0))
            'J'.code -> eraseInDisplay(param(0, 0))
            'K'.code -> when (param(0, 0)) {
                0 -> eraseInLine(cursorRow, cursorCol, cols)
                1 -> eraseInLine(cursorRow, 0, cursorCol + 1)
                2 -> eraseInLine(cursorRow, 0, cols)
            }
            'L'.code -> insertLines(count(0))
            'M'.code -> deleteLines(count(0))
            'P'.code -> deleteChars(count(0))
            'S'.code -> screen.scrollUp(top, bottom, count(0), CellStyle.erased(pen))
            'T'.code -> if (nParams <= 1) screen.scrollDown(top, bottom, count(0), CellStyle.erased(pen))
            'X'.code -> {
                eraseInLine(cursorRow, cursorCol, (cursorCol + count(0)).coerceAtMost(cols))
                pendingWrap = false
            }
            'Z'.code -> backTab(count(0))
            'b'.code -> if (lastPrinted >= 0) repeat(count(0).coerceAtMost(cols * rows)) { print(lastPrinted) }
            'c'.code -> if (param(0, 0) == 0) reply("\u001b[?62;22c")
            'd'.code -> {
                val base = if (originMode) top else 0
                moveTo(base + count(0) - 1, cursorCol)
            }
            'g'.code -> when (param(0, 0)) {
                0 -> if (cursorCol < cols) tabs[cursorCol] = false
                3 -> tabs.fill(false)
            }
            'h'.code -> for (i in 0 until maxOf(nParams, 1)) setAnsiMode(param(i, 0), true)
            'l'.code -> for (i in 0 until maxOf(nParams, 1)) setAnsiMode(param(i, 0), false)
            'm'.code -> selectGraphicRendition()
            'n'.code -> when (param(0, 0)) {
                5 -> reply("\u001b[0n")
                6 -> {
                    val r = if (originMode) cursorRow - top else cursorRow
                    reply("\u001b[${r + 1};${cursorCol + 1}R")
                }
            }
            'r'.code -> {
                val t = count(0) - 1
                val b = param(1, rows).let { if (it == 0) rows else it } - 1
                if (t < b && b < rows) {
                    top = t
                    bottom = b
                    moveToOrigin(1, 1)
                }
            }
            's'.code -> saveCursor()
            'u'.code -> restoreCursor()
            't'.code -> if (param(0, 0) == 18) reply("\u001b[8;$rows;${cols}t")
        }
    }

    private fun moveCursorRow(delta: Int) {
        val minRow = if (cursorRow >= top) top else 0
        val maxRow = if (cursorRow <= bottom) bottom else rows - 1
        cursorRow = (cursorRow + delta).coerceIn(minRow, maxRow)
        pendingWrap = false
    }

    private fun setAnsiMode(mode: Int, on: Boolean) {
        when (mode) {
            4 -> insertMode = on
            20 -> newLineMode = on
        }
    }

    private fun setDecMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            5 -> { reverseVideo = on; screen.markAllDirty() }
            6 -> { originMode = on; moveToOrigin(1, 1) }
            7 -> autoWrap = on
            9 -> mouseTracking = if (on) MouseTracking.X10 else MouseTracking.OFF
            12 -> cursorBlink = on
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, clearAlt = mode == 1047 && !on)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> {
                if (on) {
                    saveCursorFor(mainScreen)
                    switchScreen(true, clearAlt = false)
                    altScreen.clearAll(CellStyle.erased(pen))
                } else {
                    switchScreen(false, clearAlt = false)
                    restoreCursor()
                }
            }
            1000 -> mouseTracking = if (on) MouseTracking.NORMAL else MouseTracking.OFF
            1002 -> mouseTracking = if (on) MouseTracking.BUTTON_EVENT else MouseTracking.OFF
            1003 -> mouseTracking = if (on) MouseTracking.ANY_EVENT else MouseTracking.OFF
            1004 -> focusReporting = on
            1005 -> mouseEncoding = if (on) MouseEncoding.UTF8 else MouseEncoding.DEFAULT
            1006 -> mouseEncoding = if (on) MouseEncoding.SGR else MouseEncoding.DEFAULT
            1015 -> mouseEncoding = if (on) MouseEncoding.URXVT else MouseEncoding.DEFAULT
            2004 -> bracketedPaste = on
        }
    }

    private fun saveCursorFor(target: Screen) {
        val s = Saved(cursorRow, cursorCol, pen, gl, charsets.copyOf(), originMode, autoWrap, pendingWrap)
        if (target === altScreen) savedAlt = s else savedMain = s
    }

    private fun switchScreen(alt: Boolean, clearAlt: Boolean) {
        val target = if (alt) altScreen else mainScreen
        if (target === screen) return
        if (!alt && clearAlt) altScreen.clearAll(CellStyle.DEFAULT)
        screen = target
        screen.markAllDirty()
    }

    // ---------------------------------------------------------------- SGR

    private fun selectGraphicRendition() {
        if (nParams == 0) {
            pen = CellStyle.DEFAULT
            return
        }
        var i = 0
        while (i < nParams) {
            val p = param(i, 0)
            // Skip stray sub-parameters that belong to a code we already handled.
            if (subParam[i] && i > 0) { i++; continue }
            when (p) {
                0 -> pen = CellStyle.DEFAULT
                1 -> pen = pen or CellStyle.BOLD
                2 -> pen = pen or CellStyle.FAINT
                3 -> pen = pen or CellStyle.ITALIC
                4 -> {
                    val style = if (i + 1 < nParams && subParam[i + 1]) param(i + 1, 1) else 1
                    pen = pen and (CellStyle.UNDERLINE or CellStyle.DOUBLE_UNDERLINE).inv()
                    pen = when (style) {
                        0 -> pen
                        2 -> pen or CellStyle.DOUBLE_UNDERLINE
                        else -> pen or CellStyle.UNDERLINE
                    }
                }
                5, 6 -> pen = pen or CellStyle.BLINK
                7 -> pen = pen or CellStyle.INVERSE
                8 -> pen = pen or CellStyle.INVISIBLE
                9 -> pen = pen or CellStyle.STRIKETHROUGH
                21 -> pen = pen or CellStyle.DOUBLE_UNDERLINE
                22 -> pen = pen and (CellStyle.BOLD or CellStyle.FAINT).inv()
                23 -> pen = pen and CellStyle.ITALIC.inv()
                24 -> pen = pen and (CellStyle.UNDERLINE or CellStyle.DOUBLE_UNDERLINE).inv()
                25 -> pen = pen and CellStyle.BLINK.inv()
                27 -> pen = pen and CellStyle.INVERSE.inv()
                28 -> pen = pen and CellStyle.INVISIBLE.inv()
                29 -> pen = pen and CellStyle.STRIKETHROUGH.inv()
                in 30..37 -> pen = CellStyle.withFg(pen, p - 30)
                39 -> pen = CellStyle.withFg(pen, TermColor.DEFAULT)
                in 40..47 -> pen = CellStyle.withBg(pen, p - 40)
                49 -> pen = CellStyle.withBg(pen, TermColor.DEFAULT)
                in 90..97 -> pen = CellStyle.withFg(pen, p - 90 + 8)
                in 100..107 -> pen = CellStyle.withBg(pen, p - 100 + 8)
                38, 48 -> {
                    val (color, used) = extendedColor(i + 1)
                    if (color != null) {
                        pen = if (p == 38) CellStyle.withFg(pen, color) else CellStyle.withBg(pen, color)
                    }
                    i += used
                }
            }
            i++
        }
    }

    /** Parses `5;n`, `2;r;g;b` and their colon forms (`2::r:g:b`). Returns color and params consumed. */
    private fun extendedColor(start: Int): Pair<Int?, Int> {
        if (start >= nParams) return null to 0
        val colon = subParam[start]
        val kind = param(start, 0)
        if (kind == 5) {
            if (start + 1 >= nParams) return null to 1
            return param(start + 1, 0).coerceIn(0, 255) to 2
        }
        if (kind == 2) {
            // Colon form may carry an empty color-space id: 38:2::r:g:b
            var j = start + 1
            if (colon) {
                var n = 0
                var k = start + 1
                while (k < nParams && subParam[k]) { n++; k++ }
                if (n >= 4) j++
            }
            if (j + 2 >= nParams) return null to (nParams - start)
            val r = param(j, 0)
            val g = param(j + 1, 0)
            val b = param(j + 2, 0)
            return TermColor.rgb(r, g, b) to (j + 3 - start)
        }
        return null to 1
    }

    // ---------------------------------------------------------------- OSC

    private fun dispatchOsc() {
        val s = osc.toString()
        val semi = s.indexOf(';')
        if (semi <= 0) return
        when (s.substring(0, semi).toIntOrNull()) {
            0, 2 -> {
                title = s.substring(semi + 1).take(256)
                listener?.onTitleChanged(title)
            }
            // OSC 52 (clipboard) and color queries are deliberately ignored: a game must
            // not read or write the user's clipboard.
        }
    }

    // ---------------------------------------------------------------- reset/resize

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autoWrap = true
        insertMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        top = 0
        bottom = rows - 1
        pen = CellStyle.DEFAULT
        gl = 0
        charsets.fill(CS_ASCII)
        savedMain = null
        savedAlt = null
    }

    fun reset() {
        softReset()
        screen = mainScreen
        mainScreen.clearAll(CellStyle.DEFAULT)
        mainScreen.clearHistory()
        altScreen.clearAll(CellStyle.DEFAULT)
        newLineMode = false
        reverseVideo = false
        bracketedPaste = false
        focusReporting = false
        mouseTracking = MouseTracking.OFF
        mouseEncoding = MouseEncoding.DEFAULT
        cursorShape = CursorShape.BLOCK
        cursorBlink = true
        tabs = BooleanArray(cols) { it % 8 == 0 && it != 0 }
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
        title = ""
        state = GROUND
        generation++
    }

    fun resize(newCols: Int, newRows: Int) {
        require(newCols >= 2 && newRows >= 2) { "terminal must be at least 2x2" }
        if (newCols == cols && newRows == rows) return
        val shiftMain = mainScreen.resize(newCols, newRows, if (isAltScreen) 0 else cursorRow)
        altScreen.resize(newCols, newRows, if (isAltScreen) cursorRow else 0)
        if (!isAltScreen) cursorRow -= shiftMain
        cols = newCols
        rows = newRows
        top = 0
        bottom = rows - 1
        tabs = BooleanArray(cols) { it % 8 == 0 && it != 0 }
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
        generation++
    }

    private fun reply(s: String) = reply(s.toByteArray(Charsets.US_ASCII))

    companion object {
        private const val GROUND = 0
        private const val ESCAPE = 1
        private const val ESCAPE_INTERMEDIATE = 2
        private const val CSI_ENTRY = 3
        private const val CSI_PARAM = 4
        private const val CSI_INTERMEDIATE = 5
        private const val CSI_IGNORE = 6
        private const val OSC_STRING = 7
        private const val IGNORE_STRING = 8

        private const val MAX_PARAMS = 32
        private const val MAX_OSC = 4096
        private const val REPLACEMENT = 0xFFFD

        private const val CS_ASCII = 0
        private const val CS_DEC_SPECIAL = 1
        private const val CS_UK = 2

        /** DEC Special Graphics for 0x5F..0x7E (line drawing used by ncurses' ACS). */
        private val DEC_SPECIAL = intArrayOf(
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )
    }
}
