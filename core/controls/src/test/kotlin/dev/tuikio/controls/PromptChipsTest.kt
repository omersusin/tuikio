package dev.tuikio.controls

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptChipsTest {
    private fun chips(line: String, lineMode: Boolean = false, above: String? = null): List<String> {
        val lines = listOfNotNull(above, line)
        return PromptChips.detect(lines, lines.size - 1, line.length, lineMode).map { c ->
            c.label + "=" + when (val o = c.output) {
                is ActionOutput.Text -> o.text.replace("\r", "⏎")
                is ActionOutput.Key -> o.key.name
                is ActionOutput.Sequence -> "seq"
            }
        }
    }

    @Test
    fun yesNoQuestions() {
        assertEquals(listOf("Yes=y", "No=n"), chips("Are you an experienced player? [ny]"))
        assertEquals(listOf("Yes=y⏎", "No=n⏎"), chips("Would you like instructions (y or n)?", lineMode = true))
        assertEquals(listOf("Yes=y⏎", "No=n⏎"), chips("Instructions? (y-n)", lineMode = true))
        assertEquals(listOf("Yes=y⏎", "No=n⏎"), chips("y or n? ", lineMode = true))
    }

    @Test
    fun lettersPairsAndRanges() {
        assertEquals(listOf("T=T", "S=S", "F=F", "K=K", "C=C", "W=W"), chips("         a Knight, a Cave-man or a Wizard? [TSFKCW]"))
        assertEquals(listOf("H=H", "c=c"), chips("Choose type of the black player(H/c)"))
        assertEquals((1..9).map { "$it=$it" }, chips("Choose a level to start [1-9]:"))
    }

    @Test
    fun continuePrompts() {
        assertEquals(listOf("Continue=ENTER"), chips("paused - press RETURN to continue"))
        assertEquals(listOf("Continue= "), chips("--More--"))
        assertEquals(listOf("Continue= "), chips("Press any key to start."))
        assertEquals(listOf("Continue= "), chips("                    Type <space> to begin...."))
        assertEquals(listOf("Continue=ENTER"), chips("Hit return to continue..."))
        // A game reading lines only sees the key once Enter follows; Enter alone does it.
        assertEquals(listOf("Continue=ENTER"), chips("Press any key to start.", lineMode = true))
    }

    @Test
    fun ordinaryTextOffersNothing() {
        assertEquals(emptyList(), chips("You are standing at the end of a road before a small brick building."))
        assertEquals(emptyList(), chips("Score: 0  0.00%"))
        assertEquals(emptyList(), chips("[A-type]"))
        assertEquals(emptyList(), chips(""))
    }

    @Test
    fun promptOnTheLineAboveTheCursor() {
        assertEquals(listOf("Yes=y⏎", "No=n⏎"), chips("", lineMode = true, above = "Do you want the rules of the game? (y/n)"))
    }
}
