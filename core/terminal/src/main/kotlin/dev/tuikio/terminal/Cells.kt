package dev.tuikio.terminal

/**
 * Colors are 25-bit values: 0..255 are palette indexes, [DEFAULT] means "the theme's
 * default fg/bg", and values with bit 24 set carry a 24-bit RGB color.
 */
object TermColor {
    const val DEFAULT = 256
    private const val RGB_FLAG = 0x1000000

    fun rgb(r: Int, g: Int, b: Int): Int = RGB_FLAG or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    fun isRgb(color: Int): Boolean = color and RGB_FLAG != 0
    fun rgbValue(color: Int): Int = color and 0xFFFFFF

    /** xterm's 256-color palette as 0xRRGGBB, with the 16 base colors left to the theme. */
    fun xterm256(index: Int): Int = when {
        index < 16 -> BASE16[index]
        index < 232 -> {
            val i = index - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
        }
        else -> {
            val v = 8 + (index - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
    }

    /** Fallback base colors (VGA-like). Themes normally override these. */
    val BASE16 = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    )
}

/**
 * A cell's style packed into one Long: fg (25 bits) | bg (25 bits) | attribute flags.
 * Packing keeps a screen row as two primitive arrays and avoids per-cell objects.
 */
object CellStyle {
    const val BOLD = 1L shl 50
    const val FAINT = 1L shl 51
    const val ITALIC = 1L shl 52
    const val UNDERLINE = 1L shl 53
    const val BLINK = 1L shl 54
    const val INVERSE = 1L shl 55
    const val INVISIBLE = 1L shl 56
    const val STRIKETHROUGH = 1L shl 57
    const val DOUBLE_UNDERLINE = 1L shl 58

    /** The first cell of a two-column character. */
    const val WIDE = 1L shl 60

    /** The second, empty cell covered by a two-column character. */
    const val WIDE_TAIL = 1L shl 61

    private const val COLOR_MASK = 0x1FFFFFFL
    private const val ATTR_MASK = (0x1FFL shl 50)
    const val CELL_FLAGS = WIDE or WIDE_TAIL

    val DEFAULT: Long = encode(TermColor.DEFAULT, TermColor.DEFAULT, 0L)

    fun encode(fg: Int, bg: Int, attrs: Long): Long =
        (fg.toLong() and COLOR_MASK) or ((bg.toLong() and COLOR_MASK) shl 25) or (attrs and (ATTR_MASK or CELL_FLAGS))

    fun fg(style: Long): Int = (style and COLOR_MASK).toInt()
    fun bg(style: Long): Int = ((style ushr 25) and COLOR_MASK).toInt()
    fun attrs(style: Long): Long = style and ATTR_MASK
    fun withFg(style: Long, fg: Int): Long = (style and COLOR_MASK.inv()) or (fg.toLong() and COLOR_MASK)
    fun withBg(style: Long, bg: Int): Long = (style and (COLOR_MASK shl 25).inv()) or ((bg.toLong() and COLOR_MASK) shl 25)
    fun has(style: Long, flag: Long): Boolean = style and flag != 0L

    /** Style used for erased cells: only the background survives (xterm "bce"). */
    fun erased(pen: Long): Long = encode(TermColor.DEFAULT, bg(pen), 0L)
}

/** One screen row: code points (0 = blank) and packed styles, plus rare combining marks. */
class TermLine(cols: Int) {
    var chars = IntArray(cols)
        private set
    var styles = LongArray(cols) { CellStyle.DEFAULT }
        private set

    /** Combining marks appended to a base cell, keyed by column. Usually null. */
    var combining: HashMap<Int, String>? = null
        private set

    /** True when text flowed onto the next row by auto-wrap (used to copy logs as paragraphs). */
    var wrapped = false

    val width: Int get() = chars.size

    fun clear(from: Int, to: Int, style: Long) {
        val end = minOf(to, chars.size)
        for (i in from.coerceAtLeast(0) until end) {
            chars[i] = 0
            styles[i] = style
        }
        combining?.let { map ->
            map.keys.removeAll { it in from until end }
            if (map.isEmpty()) combining = null
        }
    }

    fun addCombining(col: Int, cp: Int) {
        val map = combining ?: HashMap<Int, String>().also { combining = it }
        map[col] = (map[col] ?: "") + String(Character.toChars(cp))
    }

    fun combiningAt(col: Int): String? = combining?.get(col)

    fun copyFrom(other: TermLine) {
        val n = minOf(width, other.width)
        System.arraycopy(other.chars, 0, chars, 0, n)
        System.arraycopy(other.styles, 0, styles, 0, n)
        other.combining?.let { src -> combining = HashMap(src.filterKeys { it < n }) }
        wrapped = other.wrapped
    }

    fun resized(cols: Int): TermLine {
        val l = TermLine(cols)
        l.copyFrom(this)
        if (cols > width) l.clear(width, cols, CellStyle.DEFAULT)
        // A wide character cut in half at the new right edge becomes a blank.
        if (cols < width && cols > 0 && CellStyle.has(l.styles[cols - 1], CellStyle.WIDE)) {
            l.chars[cols - 1] = 0
            l.styles[cols - 1] = l.styles[cols - 1] and CellStyle.CELL_FLAGS.inv()
        }
        return l
    }

    /** Text of the row with trailing blanks trimmed; wide tails are skipped. */
    fun text(): String {
        val sb = StringBuilder()
        var lastNonBlank = 0
        for (i in chars.indices) {
            if (CellStyle.has(styles[i], CellStyle.WIDE_TAIL)) continue
            val c = chars[i]
            if (c == 0) sb.append(' ') else sb.appendCodePoint(c)
            combining?.get(i)?.let { sb.append(it) }
            if (c != 0 && c != ' '.code) lastNonBlank = sb.length
        }
        sb.setLength(lastNonBlank)
        return sb.toString()
    }
}
