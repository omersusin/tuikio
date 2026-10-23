package dev.tuikio.controls

import dev.tuikio.terminal.TermKey

/** A one-tap answer offered for a question the game is asking. */
data class PromptChip(val label: String, val output: ActionOutput)

/**
 * Reads the question a game is asking at its cursor and offers the answers as chips: "(y/n)"
 * gives Yes and No, "(H/c)" gives H and c, "[TSFKCW]" a chip per letter (or digit), "[1-9]" the digits,
 * "Do you want the rules?" Yes and No, "Long or Short?" Long and Short (their first letters, lowercase),
 * and "--More--" or "Press any key" gives Continue. No per-game setup: it looks at the line the
 * cursor is on (and the one above, for prompts printed before a newline), which is where a game
 * waits for its answer.
 *
 * [lineMode] is true when the game reads whole lines (the terminal is in canonical mode): an
 * answer then needs Enter after it, and so does "press any key". Games that read single keys
 * would take a stray Enter as the next answer or command.
 */
object PromptChips {
    private const val MAX = 10

    private val CONTINUE_SPACE = Regex("""(?i)--\s*more\s*--|\(more\)|press (?:any|a) key|hit (?:any|a) key|any key to|type <space>|(?:press|hit) (?:the |a )?space|press space(?:bar)?|<space> to""")
    private val CONTINUE_ENTER = Regex("""(?i)(?:press|hit|type) (?:return|enter|<return>|<enter>|<cr>)|\[(?:press|hit) (?:return|enter)\]|return to continue|enter to continue""")
    private val YES_NO = Regex("""(?i)\(\s*y\s*(?:/|-|or)\s*n\s*\)|\[\s*y\s*/?\s*n\s*\]|\by(?:es)?\s*(?:/|or)\s*n(?:o)?\s*\??\s*$|\[\s*n\s*y\s*\]""")
    private val PAIR = Regex("""[(\[]\s*([A-Za-z0-9])\s*(?:/|\||or)\s*([A-Za-z0-9])\s*[)\]]""")
    private val LETTERS = Regex("""\[([A-Za-z0-9]{2,8})\]""")
    private val RANGE = Regex("""[(\[]\s*(\d)\s*(?:-|\.\.|to)\s*(\d)\s*[)\]]""")
    /** "(m-s)": two letters with a dash (a dash between digits is a range). */
    private val LETTER_PAIR = Regex("""[(\[]\s*([A-Za-z])\s*-\s*([A-Za-z])\s*[)\]]""")
    /**
     * A question with no answer key: "Do you want the rules of the game?", "Would you like
     * instructions? N" (some games print their default after it).
     */
    private val ASKS_YES_NO = Regex("""(?i)^\W*(?:do|does|did|would|will|shall|should|are|is|was|were|can|could|may|have|has|want)\s+(?:you|i|we|it|this|that|they|there|he|she)\b[^?]*\?\s*(?:\[?[yn]\]?)?\s*$""")
    /**
     * "Long (to 121) or Short (to 61)?", "Heads or tails?", "Long or short help?": the whole
     * question is a choice of two (and what they are of).
     */
    private val CHOICE = Regex("""^\W*([A-Za-z]+)(?:\s*\([^)]*\))?,?\s+or\s+([A-Za-z]+)(?:\s*\([^)]*\))?(?:\s+[a-z]+)?\s*\?\s*$""")

    fun detect(lines: List<String>, cursorRow: Int, cursorCol: Int, lineMode: Boolean): List<PromptChip> {
        if (cursorRow !in lines.indices) return emptyList()
        // The question is on the cursor's line, up to the cursor; or on the line just above.
        val here = lines[cursorRow].let { if (cursorCol in 1..it.length) it.substring(0, cursorCol) else it }.trimEnd()
        val above = if (cursorRow > 0) lines[cursorRow - 1].trimEnd() else ""
        val text = if (here.isNotBlank()) here else above
        if (text.isBlank()) return emptyList()
        val tail = text.takeLast(80)
        fun answer(s: String) = ActionOutput.Text(if (lineMode) s + "\r" else s)

        if (YES_NO.containsMatchIn(tail)) return listOf(PromptChip("Yes", answer("y")), PromptChip("No", answer("n")))
        PAIR.findAll(tail).lastOrNull()?.let { m ->
            val (a, b) = m.destructured
            if (!a.equals(b, ignoreCase = true)) return listOf(PromptChip(a, answer(a)), PromptChip(b, answer(b)))
        }
        RANGE.findAll(tail).lastOrNull()?.let { m ->
            val lo = m.groupValues[1].toInt()
            val hi = m.groupValues[2].toInt()
            if (hi > lo && hi - lo < MAX) return (lo..hi).map { PromptChip("$it", answer("$it")) }
        }
        LETTER_PAIR.findAll(tail).lastOrNull()?.let { m ->
            val (a, b) = m.destructured
            if (!a.equals(b, ignoreCase = true)) return listOf(PromptChip(a, answer(a)), PromptChip(b, answer(b)))
        }
        LETTERS.findAll(tail).lastOrNull()?.let { m ->
            val letters = m.groupValues[1]
            if (letters.toSet().size == letters.length) {
                return letters.map { c ->
                    val label = when (c) {
                        'y' -> "Yes"
                        'n' -> "No"
                        else -> c.toString()
                    }
                    PromptChip(label, answer(c.toString()))
                }
            }
        }
        // The last sentence on the line, for questions without an answer key.
        val question = tail.split(Regex("""(?<=[.!:])\s+""")).last()
        CHOICE.find(question)?.let { m ->
            val (a, b) = m.destructured
            if (a[0].lowercaseChar() != b[0].lowercaseChar()) {
                // Lowercase: hack only takes l or s; games that want capitals read either.
                return listOf(a, b).map { w -> PromptChip(w.replaceFirstChar { it.uppercaseChar() }, answer(w.take(1).lowercase())) }
            }
        }
        if (" or " !in question && ASKS_YES_NO.containsMatchIn(question)) {
            return listOf(PromptChip("Yes", answer("y")), PromptChip("No", answer("n")))
        }
        // Backgammon's tutor waits at a bare "-->" for a space or newline.
        if (text.trim() == "-->") return listOf(PromptChip("Continue", if (lineMode) ActionOutput.Key(TermKey.ENTER) else ActionOutput.Text(" ")))
        if (CONTINUE_ENTER.containsMatchIn(tail)) return listOf(PromptChip("Continue", ActionOutput.Key(TermKey.ENTER)))
        if (CONTINUE_SPACE.containsMatchIn(tail)) {
            return listOf(PromptChip("Continue", if (lineMode) ActionOutput.Key(TermKey.ENTER) else ActionOutput.Text(" ")))
        }
        return emptyList()
    }
}
