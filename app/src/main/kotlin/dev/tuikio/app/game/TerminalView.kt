package dev.tuikio.app.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.tuikio.app.ui.Palette
import dev.tuikio.terminal.CellStyle
import dev.tuikio.terminal.TermColor
import dev.tuikio.terminal.TerminalEmulator

/**
 * Draws a [TerminalEmulator] screen. Rendering is incremental: rows the emulator marked dirty
 * are repainted into an offscreen bitmap (style runs drawn with one drawText each), and each
 * frame blits that bitmap. Frames are only requested when output arrived or the cursor blinks,
 * and blinking stops after a few seconds of inactivity, so an idle game costs almost nothing.
 */
class TerminalView(context: Context) : View(context), Choreographer.FrameCallback {
    var emulator: TerminalEmulator? = null
        set(v) {
            field = v
            resetFraming()
            fullRedraw = true
            requestFrame()
        }

    /**
     * Largest cell height in px (the player's "Game text size"). Within that limit the font is
     * chosen so that what the game actually draws fills the view: see [refit].
     */
    var maxCellPx = 0f
        set(v) {
            field = v
            refit()
        }

    var palette: Palette? = null
        set(v) {
            field = v
            fullRedraw = true
            requestFrame()
        }

    /** Terminal font, scaled so that [cols]×[rows] fit (set by GameActivity). */
    var typeface: Typeface = Typeface.MONOSPACE
    var onTap: ((col: Int, row: Int) -> Unit)? = null
    var onSwipe: ((dir: String) -> Unit)? = null
    var onKey: ((KeyEvent) -> Boolean)? = null
    var onText: ((String) -> Unit)? = null

    var cellW = 10f
        private set
    var cellH = 20f
        private set
    private var baseline = 16f
    private var originX = 0f
    private var originY = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    /** Only for measuring candidate sizes: [textPaint] must always stay at the cell's size. */
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint()
    private var buffer: Bitmap? = null
    private var bufferCanvas: Canvas? = null
    private var fullRedraw = true
    private var dirty = BooleanArray(0)
    private var frameRequested = false
    private var lastActivity = 0L
    private var cursorOn = true
    private var lastBlink = 0L
    private var lastCursorRow = -1
    private val sb = StringBuilder()
    private val blinkTick = Runnable { requestFrame() }

    /**
     * Keeps showing the last frame while true. A game told to stop leaves its screen first
     * (curses' suspend handler), and that should not flash up behind the pause sheet; what
     * changed meanwhile is drawn once this is cleared.
     */
    var frozen = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) requestFrame()
        }

    /** Measured redraw cost, shown in Settings › Diagnostics. */
    var lastFrameMicros = 0L
        private set
    var framesDrawn = 0L
        private set

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Picks a font size so the grid fills this view; returns the size in px. */
    fun fitGrid(cols: Int, rows: Int, maxCellH: Float, minCellH: Float = 6f): Float {
        if (width == 0 || height == 0) return 0f
        measurePaint.typeface = typeface
        measurePaint.textSize = 100f
        val advance = measurePaint.measureText("M")
        val fm = measurePaint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val sizeForW = width / cols.toFloat() / advance * 100f
        val sizeForH = height / rows.toFloat() / lineH * 100f
        var size = minOf(sizeForW, sizeForH)
        size = size.coerceAtMost(maxCellH / lineH * 100f).coerceAtLeast(minCellH / lineH * 100f)
        applyFontSize(size)
        return size
    }

    // ------------------------------------------------------------------ content framing
    //
    // Many games draw into only part of the terminal (2048 uses 28 of 40 columns, starting
    // at the left). The view tracks the union of every cell the game has drawn this session
    // and sizes and centers *that*, so the game fills the phone instead of hugging a corner.
    // The box only grows (reset when the grid changes size), so it settles within the first
    // frames instead of jumping around during play.

    private var boxC0 = Int.MAX_VALUE
    private var boxR0 = Int.MAX_VALUE
    private var boxC1 = -1
    private var boxR1 = -1
    private var boxCols = 0
    private var boxRows = 0
    private var refitPending = false

    private fun resetFraming() {
        boxC0 = Int.MAX_VALUE; boxR0 = Int.MAX_VALUE; boxC1 = -1; boxR1 = -1
        boxCols = 0; boxRows = 0
    }

    /** Grows the box with row [r]'s drawn cells; returns true if it changed. */
    private fun frameRow(emu: TerminalEmulator, r: Int, cols: Int): Boolean {
        val line = emu.screen[r]
        var first = -1
        var last = -1
        for (c in 0 until cols) {
            val cp = line.chars[c]
            val st = line.styles[c]
            val ink = (cp != 0 && cp != ' '.code) || CellStyle.bg(st) != TermColor.DEFAULT || CellStyle.has(st, CellStyle.INVERSE)
            if (ink) {
                if (first < 0) first = c
                last = c
            }
        }
        if (first < 0) return false
        var changed = false
        if (first < boxC0) { boxC0 = first; changed = true }
        if (last > boxC1) { boxC1 = last; changed = true }
        if (r < boxR0) { boxR0 = r; changed = true }
        if (r > boxR1) { boxR1 = r; changed = true }
        return changed
    }

    /** Cells to fit and center: what the game drew so far, or the whole grid before that. */
    private fun frameBox(cols: Int, rows: Int): IntArray =
        if (boxC1 < 0 || boxCols != cols || boxRows != rows) intArrayOf(0, 0, cols - 1, rows - 1)
        else intArrayOf(boxC0, boxR0, boxC1, boxR1)

    /** Picks the font size for the current box and view size. */
    fun refit() {
        refitPending = false
        val emu = emulator ?: return
        if (width == 0 || height == 0 || maxCellPx <= 0f) return
        val box = synchronized(emu.lock) { frameBox(emu.cols, emu.rows) }
        val bc = box[2] - box[0] + 1
        val br = box[3] - box[1] + 1
        measurePaint.typeface = typeface
        measurePaint.textSize = 100f
        val advance = measurePaint.measureText("M")
        val fm = measurePaint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val forW = width / bc.toFloat() / advance * 100f
        // Cell height is rounded up to whole pixels, so fit against the floor of the share.
        val forH = Math.floor(height / br.toDouble()).toFloat() / lineH * 100f
        val forMax = Math.floor(maxCellPx.toDouble()).toFloat() / lineH * 100f
        val size = minOf(forW, forH, forMax).coerceAtLeast(6f / lineH * 100f)
        if (Math.abs(size - currentSize) > 0.25f) applyFontSize(size) else requestFrame()
    }

    private var currentSize = 0f

    /** How many columns/rows fit at a given font size (for games that adapt to any size). */
    fun gridFor(textSizePx: Float): Pair<Int, Int> {
        measurePaint.typeface = typeface
        measurePaint.textSize = textSizePx
        val w = measurePaint.measureText("M")
        val fm = measurePaint.fontMetrics
        val h = fm.descent - fm.ascent
        return (width / w).toInt().coerceAtLeast(2) to (height / h).toInt().coerceAtLeast(2)
    }

    fun applyFontSize(sizePx: Float) {
        currentSize = sizePx
        textPaint.typeface = typeface
        textPaint.textSize = sizePx
        cellW = textPaint.measureText("M")
        val fm = textPaint.fontMetrics
        cellH = Math.ceil((fm.descent - fm.ascent).toDouble()).toFloat()
        baseline = -fm.ascent
        fullRedraw = true
        requestFrame()
    }

    /** Called (from any thread) when the emulator changed. */
    fun onOutput() {
        lastActivity = SystemClock.uptimeMillis()
        post { requestFrame() }
    }

    fun requestFrame() {
        if (!frameRequested && isAttachedToWindow) {
            frameRequested = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameRequested = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        frameRequested = false
        buffer?.recycle()
        buffer = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fullRedraw = true
        refit()
    }

    override fun onDraw(canvas: Canvas) {
        val emu = emulator ?: return
        val pal = palette ?: return
        if (frozen) {
            canvas.drawColor(pal.screen)
            buffer?.let { canvas.drawBitmap(it, originX, originY, null) }
            return
        }
        val t0 = System.nanoTime()
        var cursorRow: Int
        var cursorCol: Int
        var cursorVisible: Boolean
        var box: IntArray
        synchronized(emu.lock) {
            val cols = emu.cols
            val rows = emu.rows
            val bw = Math.ceil((cols * cellW).toDouble()).toInt().coerceAtLeast(1)
            val bh = Math.ceil((rows * cellH).toDouble()).toInt().coerceAtLeast(1)
            var bmp = buffer
            if (bmp == null || bmp.width != bw || bmp.height != bh) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                buffer = bmp
                bufferCanvas = Canvas(bmp)
                fullRedraw = true
            }
            if (dirty.size != rows) dirty = BooleanArray(rows)
            val anyDamage = emu.screen.collectDamage(dirty)
            val c = bufferCanvas!!
            if (boxCols != cols || boxRows != rows) {
                resetFraming()
                boxCols = cols
                boxRows = rows
            }
            var boxGrew = false
            if (fullRedraw || anyDamage) {
                for (r in 0 until rows) {
                    if (fullRedraw || dirty[r]) {
                        drawRow(c, emu, r, cols, pal)
                        if (frameRow(emu, r, cols)) boxGrew = true
                    }
                }
                fullRedraw = false
            }
            if (boxGrew && !refitPending) {
                refitPending = true
                post { refit() }
            }
            box = frameBox(cols, rows)
            cursorRow = emu.cursorRow
            cursorCol = emu.cursorCol
            cursorVisible = emu.cursorVisible
        }
        val bmp = buffer ?: return
        // Center the drawn content, on whole pixels: a fractional offset makes the GPU pick
        // pixels from neighbouring rows unevenly, which showed as ragged lines along edges.
        val contentW = (box[2] - box[0] + 1) * cellW
        val contentH = (box[3] - box[1] + 1) * cellH
        originX = Math.floor(((width - contentW) / 2f - box[0] * cellW).toDouble()).toFloat()
        originY = Math.floor(((height - contentH) / 2f - box[1] * cellH).toDouble()).toFloat()
        canvas.drawColor(pal.screen)
        canvas.drawBitmap(bmp, originX, originY, null)

        // Cursor: blink only while the game is active, to avoid waking the GPU forever.
        val now = SystemClock.uptimeMillis()
        val active = now - lastActivity < 5000
        if (cursorVisible) {
            if (active && now - lastBlink > 530) {
                cursorOn = !cursorOn
                lastBlink = now
            }
            if (!active) cursorOn = true
            if (cursorOn) {
                fillPaint.color = pal.cursor
                fillPaint.alpha = 200
                val x = originX + cursorCol * cellW
                val y = originY + cursorRow * cellH
                canvas.drawRect(x, y + cellH - Math.max(2f, cellH / 7f), x + cellW, y + cellH, fillPaint)
                fillPaint.alpha = 255
            }
            if (active) {
                removeCallbacks(blinkTick)
                postDelayed(blinkTick, 540)
            }
        }
        if (lastCursorRow != cursorRow) {
            lastCursorRow = cursorRow
        }
        lastFrameMicros = (System.nanoTime() - t0) / 1000
        framesDrawn++
    }

    /**
     * Test hook: true if the incrementally maintained buffer matches a repaint of every row from
     * the same emulator snapshot (pending damage is applied first, as a frame would).
     */
    internal fun bufferMatchesFullRepaint(): Boolean {
        val emu = emulator ?: return true
        val pal = palette ?: return true
        synchronized(emu.lock) {
            val bmp = buffer ?: return true
            val c = bufferCanvas ?: return true
            val rows = emu.rows
            if (dirty.size != rows) dirty = BooleanArray(rows)
            if (emu.screen.collectDamage(dirty)) for (r in 0 until rows) if (dirty[r]) drawRow(c, emu, r, emu.cols, pal)
            val fresh = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val fc = Canvas(fresh)
            for (r in 0 until rows) drawRow(fc, emu, r, emu.cols, pal)
            return fresh.sameAs(bmp).also { fresh.recycle() }
        }
    }

    private fun color(c: Int, pal: Palette, fg: Boolean): Int = when {
        c == TermColor.DEFAULT -> if (fg) pal.screenInk else pal.screen
        TermColor.isRgb(c) -> 0xFF000000.toInt() or TermColor.rgbValue(c)
        c < 16 -> 0xFF000000.toInt() or pal.ansi[c]
        else -> 0xFF000000.toInt() or TermColor.xterm256(c)
    }

    private val rowRect = RectF()

    private fun drawRow(c: Canvas, emu: TerminalEmulator, r: Int, cols: Int, pal: Palette) {
        val line = emu.screen[r]
        val y = r * cellH
        rowRect.set(0f, y, cols * cellW, y + cellH)
        // Keep every glyph inside its row: tall fallback glyphs (✦ ♥, box drawing in some fonts)
        // would otherwise leave slivers in the rows around them that a later repaint of just
        // this row can't clean up.
        c.save()
        c.clipRect(rowRect)
        fillPaint.color = pal.screen
        c.drawRect(rowRect, fillPaint)
        val reverse = emu.reverseVideo
        var col = 0
        while (col < cols) {
            val style = line.styles[col]
            // Gather a run of cells sharing this style.
            var end = col + 1
            while (end < cols && (line.styles[end] and CellStyle.CELL_FLAGS.inv()) == (style and CellStyle.CELL_FLAGS.inv())) end++
            // Like xterm and most terminals, bold turns the 8 basic colors bright: curses games
            // draw "bold black" expecting dark gray (nSnake's box shadows, for one).
            val fgIndex = CellStyle.fg(style).let { if (it < 8 && CellStyle.has(style, CellStyle.BOLD)) it + 8 else it }
            var fg = color(fgIndex, pal, true)
            var bg = color(CellStyle.bg(style), pal, false)
            if (CellStyle.has(style, CellStyle.INVERSE) xor reverse) {
                val t = fg; fg = bg; bg = t
            }
            if (bg != pal.screen) {
                fillPaint.color = bg
                c.drawRect(col * cellW, y, end * cellW, y + cellH, fillPaint)
            }
            if (!CellStyle.has(style, CellStyle.INVISIBLE)) {
                textPaint.color = if (CellStyle.has(style, CellStyle.FAINT)) (fg and 0x00FFFFFF) or (0x99 shl 24) else fg
                textPaint.isFakeBoldText = CellStyle.has(style, CellStyle.BOLD)
                textPaint.textSkewX = if (CellStyle.has(style, CellStyle.ITALIC)) -0.2f else 0f
                textPaint.isUnderlineText = CellStyle.has(style, CellStyle.UNDERLINE) || CellStyle.has(style, CellStyle.DOUBLE_UNDERLINE)
                textPaint.isStrikeThruText = CellStyle.has(style, CellStyle.STRIKETHROUGH)
                // Draw glyph by glyph at exact cell positions: fonts' fallback glyphs (box drawing,
                // CJK) may have different advances, and terminal games rely on a strict grid.
                for (i in col until end) {
                    val cp = line.chars[i]
                    if (cp == 0 || cp == ' '.code || CellStyle.has(line.styles[i], CellStyle.WIDE_TAIL)) continue
                    sb.setLength(0)
                    sb.appendCodePoint(cp)
                    line.combiningAt(i)?.let { sb.append(it) }
                    val x = i * cellW
                    if (cp in 0x2500..0x259F && drawBoxGlyph(c, cp, x, y, textPaint.color)) continue
                    if (CellStyle.has(line.styles[i], CellStyle.WIDE)) {
                        val w = textPaint.measureText(sb, 0, sb.length)
                        c.save()
                        c.clipRect(x, y, x + 2 * cellW, y + cellH)
                        c.drawText(sb, 0, sb.length, x + (2 * cellW - w) / 2, y + baseline, textPaint)
                        c.restore()
                    } else {
                        val w = textPaint.measureText(sb, 0, sb.length)
                        if (w > cellW * 1.05f) {
                            c.save()
                            c.scale(cellW / w, 1f, x, y)
                            c.drawText(sb, 0, sb.length, x, y + baseline, textPaint)
                            c.restore()
                        } else {
                            c.drawText(sb, 0, sb.length, x + (cellW - w) / 2, y + baseline, textPaint)
                        }
                    }
                }
            }
            col = end
        }
        c.restore()
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.SQUARE }

    /**
     * Draws common box-drawing and block characters geometrically so borders connect
     * seamlessly regardless of the font. Returns false for glyphs it doesn't handle.
     */
    private fun drawBoxGlyph(c: Canvas, cp: Int, x: Float, y: Float, color: Int): Boolean {
        val w = cellW
        val h = cellH
        val cx = x + w / 2
        val cy = y + h / 2
        boxPaint.color = color
        val light = Math.max(1f, w / 8f)
        val heavy = light * 2
        boxPaint.strokeWidth = light
        boxPaint.style = Paint.Style.STROKE
        fun lines(up: Float, down: Float, left: Float, right: Float) {
            if (up > 0) { boxPaint.strokeWidth = up; c.drawLine(cx, y, cx, cy, boxPaint) }
            if (down > 0) { boxPaint.strokeWidth = down; c.drawLine(cx, cy, cx, y + h, boxPaint) }
            if (left > 0) { boxPaint.strokeWidth = left; c.drawLine(x, cy, cx, cy, boxPaint) }
            if (right > 0) { boxPaint.strokeWidth = right; c.drawLine(cx, cy, x + w, cy, boxPaint) }
        }
        val l = light
        val H = heavy
        when (cp) {
            0x2500 -> lines(0f, 0f, l, l)
            0x2501 -> lines(0f, 0f, H, H)
            0x2502 -> lines(l, l, 0f, 0f)
            0x2503 -> lines(H, H, 0f, 0f)
            0x250C, 0x256D -> lines(0f, l, 0f, l)
            0x2510, 0x256E -> lines(0f, l, l, 0f)
            0x2514, 0x2570 -> lines(l, 0f, 0f, l)
            0x2518, 0x256F -> lines(l, 0f, l, 0f)
            0x251C -> lines(l, l, 0f, l)
            0x2524 -> lines(l, l, l, 0f)
            0x252C -> lines(0f, l, l, l)
            0x2534 -> lines(l, 0f, l, l)
            0x253C -> lines(l, l, l, l)
            0x2580 -> { boxPaint.style = Paint.Style.FILL; c.drawRect(x, y, x + w, cy, boxPaint) }
            0x2584 -> { boxPaint.style = Paint.Style.FILL; c.drawRect(x, cy, x + w, y + h, boxPaint) }
            0x2588 -> { boxPaint.style = Paint.Style.FILL; c.drawRect(x, y, x + w, y + h, boxPaint) }
            0x258C -> { boxPaint.style = Paint.Style.FILL; c.drawRect(x, y, cx, y + h, boxPaint) }
            0x2590 -> { boxPaint.style = Paint.Style.FILL; c.drawRect(cx, y, x + w, y + h, boxPaint) }
            0x2591, 0x2592, 0x2593 -> {
                boxPaint.style = Paint.Style.FILL
                boxPaint.alpha = when (cp) { 0x2591 -> 64; 0x2592 -> 128; else -> 192 }
                c.drawRect(x, y, x + w, y + h, boxPaint)
                boxPaint.alpha = 255
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ input

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val emu = emulator ?: return false
            val col = ((e.x - originX) / cellW).toInt()
            val row = ((e.y - originY) / cellH).toInt()
            if (col in 0 until emu.cols && row in 0 until emu.rows) onTap?.invoke(col, row)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            val min = resources.displayMetrics.density * 36
            if (Math.abs(dx) < min && Math.abs(dy) < min) return false
            val dir = if (Math.abs(dx) > Math.abs(dy)) (if (dx > 0) "swipe_right" else "swipe_left") else (if (dy > 0) "swipe_down" else "swipe_up")
            onSwipe?.invoke(dir)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = onKey?.invoke(event) == true || super.onKeyDown(keyCode, event)

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                onText?.invoke(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(8)) {
                    onKey?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKey?.invoke(event)
                return true
            }
        }
    }

    // ------------------------------------------------------------------ accessibility

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val emu = emulator ?: return
        val text = synchronized(emu.lock) { emu.screen.text().filter { it.isNotBlank() }.joinToString("\n") }
        info.text = text
        info.contentDescription = "Game screen"
    }

    /** Where the cursor's row is drawn, in view pixels (top and bottom), for placing overlays. */
    fun cursorRowSpan(): Pair<Float, Float> {
        val emu = emulator ?: return 0f to 0f
        val row = synchronized(emu.lock) { emu.cursorRow }
        val top = originY + row * cellH
        return top to top + cellH
    }

    fun screenRect(out: Rect) {
        val b = buffer ?: return out.setEmpty()
        out.set(originX.toInt(), originY.toInt(), (originX + b.width).toInt(), (originY + b.height).toInt())
    }
}
