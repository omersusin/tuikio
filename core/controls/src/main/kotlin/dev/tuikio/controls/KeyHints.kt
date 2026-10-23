package dev.tuikio.controls

import dev.tuikio.terminal.TermKey

/**
 * The keys a game's screen tells you to press, anywhere on it: menus ("1) Start the game",
 * "(q) Quit", "m) Male"), legends ("q - quit", "s = Snow", "d: discard"), brackets ("<C> to
 * continue", "[H] How to play"), "(n)ew game" and "press E to ...". Each becomes a chip labelled
 * with its key and what it does, so a key the game asks for is always one tap away, even when
 * the control profile has no button for it. Keys a visible button already sends are left out.
 */
object KeyHints {
    const val MAX = 6

    private const val LABEL = """([A-Za-z][A-Za-z'’]*(?:[ -][A-Za-z'’]+){0,3})"""
    private const val NAMED = """space(?:bar)?|enter|return|esc(?:ape)?|tab|f\d{1,2}"""

    private val PATTERNS = listOf(
        // 1) Start the game · (q) Quit · m) Male   (menu items start a line or follow a gap)
        Regex("""(?:^|\s{2,}|[|:]\s*)\(?([0-9A-Za-z])\)\s+$LABEL"""),
        // <j> to move left · [H] How to play · <C> to continue
        Regex("""(?i)[<\[]($NAMED|[0-9a-z?.,])[>\]]\s*(?:to\s+|[=:-]\s*)?$LABEL"""),
        // q - quit · s = Snow · d: discard · SPACE - drop. Whitespace on one side of the separator
        // at least: "o=oo=oo" is an invader row and "C-l:redraw" means Ctrl-L.
        Regex("""(?i)(?:^|\s{2,}|[,|·•]\s*)($NAMED|[0-9a-z?])(?:\s{1,2}[-=:]\s{0,3}|[-=:]\s{1,3})$LABEL"""),
        // (q)uit · (n)ew game
        Regex("""\(([a-z])\)([a-z]{2,}(?: [a-z]+)?)"""),
        // press E to fish · hit 'p' to pause · press SPACE to start · type '?' for help
        Regex("""(?i)\b(?:press|hit|type)\s+(?:the\s+)?['"<]?($NAMED|[0-9a-z?])['">]?\s+(?:key\s+)?(?:to|for)\s+$LABEL"""),
    )

    private val DIRECTION = Regex("""(?i)\b(left|right|up|down|north|south|east|west)\b""")

    /**
     * Hints on [lines], in screen order, minus keys in [covered] (see [keyId]). With [hasPad], keys
     * that only move ("h - Move left") are left to the pad.
     */
    fun detect(lines: List<String>, covered: Set<String> = emptySet(), hasPad: Boolean = false): List<PromptChip> {
        val found = LinkedHashMap<String, PromptChip>()
        for (line in lines) {
            if (line.isBlank()) continue
            val hits = PATTERNS.flatMapIndexed { i, rx -> rx.findAll(line).map { Triple(it.range.first, i, it) } }
                .sortedBy { it.first }
            for ((_, i, m) in hits) {
                val raw = m.groupValues[1]
                val output = outputFor(raw) ?: continue
                val id = keyId(output)
                if (id in covered || id in found) continue
                val label = if (i == 3) raw + m.groupValues[2] else m.groupValues[2]
                if (hasPad && DIRECTION.containsMatchIn(label)) continue
                // "<enter> or <space>": a list of keys, not a key and what it does
                if (label.trim().split(Regex("\\s+")).first().lowercase() in FILLER) continue
                found[id] = PromptChip("${keyName(raw)} ${shortLabel(label)}", output)
                if (found.size >= MAX) return found.values.toList()
            }
        }
        return found.values.toList()
    }

    /** A comparable id for what an output sends: "q", "Q", " ", "ENTER", "F1"... */
    fun keyId(output: ActionOutput): String = when (output) {
        is ActionOutput.Text -> output.text
        is ActionOutput.Key -> output.key.name
        is ActionOutput.Sequence -> output.steps.joinToString("+") { keyId(it) }
    }

    private fun outputFor(raw: String): ActionOutput? {
        val low = raw.lowercase()
        return when {
            low.startsWith("space") -> ActionOutput.Text(" ")
            low == "enter" || low == "return" -> ActionOutput.Key(TermKey.ENTER)
            low.startsWith("esc") -> ActionOutput.Key(TermKey.ESCAPE)
            low == "tab" -> ActionOutput.Key(TermKey.TAB)
            low.matches(Regex("f\\d{1,2}")) -> low.drop(1).toInt().takeIf { it in 1..12 }
                ?.let { ActionOutput.Key(TermKey.values()[TermKey.F1.ordinal + it - 1]) }
            raw.length == 1 -> ActionOutput.Text(raw)
            else -> null
        }
    }

    private fun keyName(raw: String): String = when (val low = raw.lowercase()) {
        "space", "spacebar" -> "Space"
        "enter", "return" -> "Enter"
        "esc", "escape" -> "Esc"
        "tab" -> "Tab"
        else -> if (low.matches(Regex("f\\d{1,2}"))) raw.uppercase() else raw
    }

    private val FILLER = setOf("a", "an", "the", "to", "of", "and", "or", "for", "with", "in", "on", "at", "your", "by")

    /** At most two words and 12 characters: chips must stay small. */
    private fun shortLabel(label: String): String {
        val words = label.trim().split(Regex("\\s+")).take(2)
        var out = words.first()
        if (words.size > 1 && words[1].lowercase() !in FILLER && out.length + 1 + words[1].length <= 12) out += " " + words[1]
        if (out.length > 12) out = out.take(11) + "…"
        return out.replaceFirstChar { it.uppercaseChar() }
    }
}
