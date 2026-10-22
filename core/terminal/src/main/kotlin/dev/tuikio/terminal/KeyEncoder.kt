package dev.tuikio.terminal

/** Logical keys a control profile or a hardware keyboard can produce. */
enum class TermKey {
    UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    ENTER, ESCAPE, TAB, BACKSPACE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    KP_0, KP_1, KP_2, KP_3, KP_4, KP_5, KP_6, KP_7, KP_8, KP_9,
    KP_ENTER, KP_PLUS, KP_MINUS, KP_MULTIPLY, KP_DIVIDE, KP_DECIMAL,
}

/** Modifier bit set, using xterm's encoding (1 + shift + 2*alt + 4*ctrl). */
object KeyMods {
    const val NONE = 0
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4
}

/**
 * Turns keys into the bytes xterm would send, honoring the modes the running program
 * set (application cursor keys, application keypad, newline mode, bracketed paste).
 */
class KeyEncoder(private val term: TerminalEmulator) {

    fun encode(key: TermKey, mods: Int = KeyMods.NONE): ByteArray = ascii(sequence(key, mods))

    fun sequence(key: TermKey, mods: Int = KeyMods.NONE): String {
        val m = if (mods == KeyMods.NONE) 0 else 1 + mods
        fun cursor(final: Char): String = when {
            m != 0 -> "\u001b[1;$m$final"
            term.applicationCursorKeys -> "\u001bO$final"
            else -> "\u001b[$final"
        }
        fun tilde(code: Int): String = if (m != 0) "\u001b[$code;$m~" else "\u001b[$code~"
        fun ss3(final: Char): String = if (m != 0) "\u001b[1;$m$final" else "\u001bO$final"
        fun keypad(app: Char, normal: String): String =
            if (term.applicationKeypad && mods == KeyMods.NONE) "\u001bO$app" else normal

        val base = when (key) {
            TermKey.UP -> cursor('A')
            TermKey.DOWN -> cursor('B')
            TermKey.RIGHT -> cursor('C')
            TermKey.LEFT -> cursor('D')
            TermKey.HOME -> cursor('H')
            TermKey.END -> cursor('F')
            TermKey.INSERT -> tilde(2)
            TermKey.DELETE -> tilde(3)
            TermKey.PAGE_UP -> tilde(5)
            TermKey.PAGE_DOWN -> tilde(6)
            TermKey.F1 -> ss3('P')
            TermKey.F2 -> ss3('Q')
            TermKey.F3 -> ss3('R')
            TermKey.F4 -> ss3('S')
            TermKey.F5 -> tilde(15)
            TermKey.F6 -> tilde(17)
            TermKey.F7 -> tilde(18)
            TermKey.F8 -> tilde(19)
            TermKey.F9 -> tilde(20)
            TermKey.F10 -> tilde(21)
            TermKey.F11 -> tilde(23)
            TermKey.F12 -> tilde(24)
            TermKey.ENTER -> if (term.newLineMode) "\r\n" else "\r"
            TermKey.ESCAPE -> "\u001b"
            TermKey.TAB -> if (mods and KeyMods.SHIFT != 0) "\u001b[Z" else "\t"
            TermKey.BACKSPACE -> if (mods and KeyMods.CTRL != 0) "\b" else "\u007f"
            TermKey.KP_0 -> keypad('p', "0")
            TermKey.KP_1 -> keypad('q', "1")
            TermKey.KP_2 -> keypad('r', "2")
            TermKey.KP_3 -> keypad('s', "3")
            TermKey.KP_4 -> keypad('t', "4")
            TermKey.KP_5 -> keypad('u', "5")
            TermKey.KP_6 -> keypad('v', "6")
            TermKey.KP_7 -> keypad('w', "7")
            TermKey.KP_8 -> keypad('x', "8")
            TermKey.KP_9 -> keypad('y', "9")
            TermKey.KP_ENTER -> keypad('M', "\r")
            TermKey.KP_PLUS -> keypad('k', "+")
            TermKey.KP_MINUS -> keypad('m', "-")
            TermKey.KP_MULTIPLY -> keypad('j', "*")
            TermKey.KP_DIVIDE -> keypad('o', "/")
            TermKey.KP_DECIMAL -> keypad('n', ".")
        }
        val singleByteKeys = key == TermKey.ENTER || key == TermKey.ESCAPE || key == TermKey.BACKSPACE ||
            (key == TermKey.TAB && mods and KeyMods.SHIFT == 0)
        return if (mods and KeyMods.ALT != 0 && singleByteKeys) "\u001b$base" else base
    }

    /**
     * Encodes typed text. Ctrl turns letters into C0 controls (Ctrl+C = 0x03); Alt prefixes ESC.
     * Each code point is UTF-8 encoded.
     */
    fun text(text: String, mods: Int = KeyMods.NONE): ByteArray {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (mods and KeyMods.CTRL != 0) {
                cp = when (cp) {
                    in 'a'.code..'z'.code -> cp - 'a'.code + 1
                    in '@'.code..'_'.code -> cp - '@'.code
                    ' '.code, '2'.code -> 0
                    '/'.code -> 0x1F
                    '?'.code -> 0x7F
                    else -> cp
                }
            }
            if (mods and KeyMods.ALT != 0) sb.append('\u001b')
            sb.appendCodePoint(cp)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Pasted text, wrapped in bracketed-paste markers when the program asked for them. */
    fun paste(text: String): ByteArray {
        // Strip control characters a paste must never inject (including the paste end marker).
        val clean = text.replace("\u001b[201~", "").filter { it == '\n' || it == '\t' || it >= ' ' }
            .replace('\n', '\r')
        val body = clean.toByteArray(Charsets.UTF_8)
        if (!term.bracketedPaste) return body
        return ascii("\u001b[200~") + body + ascii("\u001b[201~")
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.UTF_8)
}

/** Encodes taps and drags on terminal cells as xterm mouse reports, when the program enabled them. */
class MouseEncoder(private val term: TerminalEmulator) {
    enum class Action { PRESS, RELEASE, MOVE }

    /** Returns null when the program did not ask for this kind of mouse event. */
    fun encode(action: Action, button: Int, col: Int, row: Int, mods: Int = KeyMods.NONE): ByteArray? {
        val mode = term.mouseTracking
        if (mode == TerminalEmulator.MouseTracking.OFF) return null
        if (action == Action.MOVE && mode != TerminalEmulator.MouseTracking.BUTTON_EVENT &&
            mode != TerminalEmulator.MouseTracking.ANY_EVENT
        ) return null
        if (action == Action.RELEASE && mode == TerminalEmulator.MouseTracking.X10) return null
        val x = col.coerceIn(0, term.cols - 1) + 1
        val y = row.coerceIn(0, term.rows - 1) + 1
        var code = button.coerceIn(0, 2)
        if (action == Action.MOVE) code += 32
        if (mode != TerminalEmulator.MouseTracking.X10) {
            if (mods and KeyMods.SHIFT != 0) code += 4
            if (mods and KeyMods.ALT != 0) code += 8
            if (mods and KeyMods.CTRL != 0) code += 16
        }
        return when (term.mouseEncoding) {
            TerminalEmulator.MouseEncoding.SGR -> {
                val final = if (action == Action.RELEASE) 'm' else 'M'
                "\u001b[<$code;$x;$y$final".toByteArray(Charsets.US_ASCII)
            }
            TerminalEmulator.MouseEncoding.URXVT -> {
                val c = if (action == Action.RELEASE) 3 else code
                "\u001b[${c + 32};$x;${y}M".toByteArray(Charsets.US_ASCII)
            }
            else -> {
                // Legacy encodings cannot say which button was released: always "3" plus modifiers.
                val c = if (action == Action.RELEASE) 3 or (code and 0x1C) else code
                val utf8 = term.mouseEncoding == TerminalEmulator.MouseEncoding.UTF8
                if (!utf8 && (x > 223 || y > 223)) return null
                val sb = StringBuilder("\u001b[M")
                sb.appendCodePoint(32 + c)
                sb.appendCodePoint(32 + x)
                sb.appendCodePoint(32 + y)
                if (utf8) sb.toString().toByteArray(Charsets.UTF_8)
                else ByteArray(sb.length) { sb[it].code.toByte() }
            }
        }
    }
}
