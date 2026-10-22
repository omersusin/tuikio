package dev.tuikio.terminal

/**
 * How many terminal columns a code point occupies: 0 (combining/format), 1 or 2
 * (East Asian wide and full-width, most emoji). Control characters return 0; callers
 * handle them before printing.
 */
object UnicodeWidth {
    fun of(cp: Int): Int {
        if (cp < 0x20) return 0
        if (cp < 0x7F) return 1
        if (cp < 0xA0) return 0
        if (inRanges(UnicodeWidthTable.ZERO, cp)) return 0
        if (inRanges(UnicodeWidthTable.WIDE, cp)) return 2
        return 1
    }

    private fun inRanges(table: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = table.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < table[mid * 2] -> hi = mid - 1
                cp > table[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
