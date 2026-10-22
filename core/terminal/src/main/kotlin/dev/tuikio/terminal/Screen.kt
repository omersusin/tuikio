package dev.tuikio.terminal

/**
 * A grid of [TermLine]s with optional bounded scrollback. The main screen keeps lines that
 * scroll off the top (useful for text adventures); the alternate screen keeps none.
 */
class Screen(cols: Int, rows: Int, private val maxScrollback: Int) {
    var cols = cols
        private set
    var rows = rows
        private set

    private var lines = Array(rows) { TermLine(cols) }
    private val history = ArrayDeque<TermLine>()

    /** Rows changed since the renderer last collected damage. */
    private var dirty = BooleanArray(rows) { true }

    val scrollbackSize: Int get() = history.size

    operator fun get(row: Int): TermLine = lines[row]

    fun historyLine(indexFromOldest: Int): TermLine = history.elementAt(indexFromOldest)

    fun markDirty(row: Int) {
        if (row in 0 until rows) dirty[row] = true
    }

    fun markDirty(from: Int, toExclusive: Int) {
        for (r in from.coerceAtLeast(0) until toExclusive.coerceAtMost(rows)) dirty[r] = true
    }

    fun markAllDirty() = dirty.fill(true)

    /** Copies the dirty flags into [out] (sized [rows]) and clears them; returns whether any were set. */
    fun collectDamage(out: BooleanArray): Boolean {
        var any = false
        for (r in 0 until rows) {
            val d = dirty[r]
            if (r < out.size) out[r] = d
            if (d) any = true
            dirty[r] = false
        }
        return any
    }

    fun clearAll(style: Long) {
        for (l in lines) {
            l.clear(0, cols, style)
            l.wrapped = false
        }
        markAllDirty()
    }

    fun clearHistory() = history.clear()

    /** Scrolls rows [top, bottom] up by [n]; lines leaving a full-screen region enter scrollback. */
    fun scrollUp(top: Int, bottom: Int, n: Int, fill: Long) {
        val count = n.coerceAtMost(bottom - top + 1)
        if (count <= 0) return
        repeat(count) {
            val gone = lines[top]
            if (top == 0 && maxScrollback > 0) {
                val keep = TermLine(cols)
                keep.copyFrom(gone)
                history.addLast(keep)
                while (history.size > maxScrollback) history.removeFirst()
            }
            System.arraycopy(lines, top + 1, lines, top, bottom - top)
            gone.clear(0, cols, fill)
            gone.wrapped = false
            lines[bottom] = gone
        }
        markDirty(top, bottom + 1)
    }

    fun scrollDown(top: Int, bottom: Int, n: Int, fill: Long) {
        val count = n.coerceAtMost(bottom - top + 1)
        if (count <= 0) return
        repeat(count) {
            val gone = lines[bottom]
            System.arraycopy(lines, top, lines, top + 1, bottom - top)
            gone.clear(0, cols, fill)
            gone.wrapped = false
            lines[top] = gone
        }
        markDirty(top, bottom + 1)
    }

    /**
     * Resizes the grid. When shrinking vertically, rows above the cursor are pushed into
     * scrollback so the cursor's line stays visible; returns how many rows the content moved up.
     */
    fun resize(newCols: Int, newRows: Int, cursorRow: Int): Int {
        var shift = 0
        if (newRows < rows && cursorRow >= newRows) shift = cursorRow - newRows + 1
        if (shift > 0 && maxScrollback > 0) {
            for (i in 0 until shift) {
                history.addLast(lines[i].resized(cols))
                while (history.size > maxScrollback) history.removeFirst()
            }
        }
        val next = Array(newRows) { r ->
            val src = r + shift
            if (src < rows) lines[src].resized(newCols) else TermLine(newCols)
        }
        lines = next
        cols = newCols
        rows = newRows
        dirty = BooleanArray(newRows) { true }
        return shift
    }

    /** The visible text, one string per row, trailing blanks trimmed. Handy for tests and logs. */
    fun text(): List<String> = lines.map { it.text() }
}
