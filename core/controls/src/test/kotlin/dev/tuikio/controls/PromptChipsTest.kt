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
        assertEquals(listOf("m=m⏎", "s=s⏎"), chips("Move or shoot? (m-s)", lineMode = true))
        assertEquals((1..6).map { "$it=$it" }, chips("Please choose one of : [123456]"))
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
    fun questionsWithoutAnAnswerKey() {
        // backgammon, phantasia, cribbage
        assertEquals(listOf("Yes=y", "No=n"), chips("Do you want the rules of the game?"))
        assertEquals(listOf("Yes=y⏎", "No=n⏎"), chips("Would you like instructions? N", lineMode = true))
        assertEquals(listOf("Long=l", "Short=s"), chips("*Long (to 121) or Short (to 61)?"))
        assertEquals(listOf("Black=b⏎", "White=w⏎"), chips("black or white?", lineMode = true))
        assertEquals(listOf("Long=l", "Short=s"), chips("Long or short help?"))
        // Not yes/no: a choice that isn't the whole question, or not a question with an auxiliary
        assertEquals(emptyList(), chips("Is this a regular game or a tournament game?"))
        assertEquals(emptyList(), chips("What is your name?"))
        assertEquals(emptyList(), chips("How many acres do you wish to buy?", lineMode = true))
    }

    @Test
    fun tutorPrompt() {
        assertEquals(listOf("Continue= "), chips("-->", above = "(Remember to hit a space or a newline to continue.)"))
        assertEquals(listOf("Continue= "), chips("hit a space to continue"))
    }

    @Test
    fun promptOnTheLineAboveTheCursor() {
        assertEquals(listOf("Yes=y⏎", "No=n⏎"), chips("", lineMode = true, above = "Do you want the rules of the game? (y/n)"))
    }
}
