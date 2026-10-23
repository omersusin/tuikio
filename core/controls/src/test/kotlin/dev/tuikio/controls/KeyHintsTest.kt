package dev.tuikio.controls

import kotlin.test.Test
import kotlin.test.assertEquals

/** Screens taken from the game audit (app RenderShotsTest): what each one offers as chips. */
class KeyHintsTest {
    private fun hints(vararg lines: String, covered: Set<String> = emptySet()) =
        KeyHints.detect(lines.toList(), covered).map { it.label + "=" + KeyHints.keyId(it.output) }

    @Test
    fun menus() {
        assertEquals(listOf("1 Player vs=1", "2 Player vs=2", "3 Two players=3", "4 Info=4", "q Quit=q"), hints(
            "  [ Main Menu ]", "", "  (1) Player vs CPU", "  (2) Player vs Player", "  (3) Two players over network", "",
            "  (4) Info", "  (q) Quit", "", "  Choose an action: 1"))
        assertEquals(listOf("m Male=m", "f Female=f"), hints("Choose a sex (? for Help):", "  m) Male       f) Female"))
        assertEquals(listOf("1 Addtris=1", "2 Tetris=2", "3 X-Tris=3", "Q Quit=Q"), hints(
            "     |  1) Addtris  |", "     |  2) Tetris   |", "     |  3) X-Tris   |", "     |  Q) Quit     |"))
    }

    @Test
    fun legends() {
        assertEquals(listOf("c Check=c", "H Give=H", "m Toggle marks=m", "N New puzzle=N", "Q Quit=Q", "r Redraw=r"), hints(
            "Commands", "  c - Check solution", "  H - Give a hint", "  m - Toggle marks", "  N - New puzzle",
            "  Q - Quit", "  r - Redraw", "  S - Solve puzzle"))
        assertEquals(listOf("s Snow=s", "r Rain=r", "h Hail=h"), hints("  s = Snow     r = Rain", "  h = Hail"))
        assertEquals(listOf("p Pick=p", "q Quit=q", "d Discard=d"), hints("|p: pick     q: quit", "|d: discard"))
        assertEquals(listOf("q Quit=q"), hints("|q = quit to end the game"))
    }

    @Test
    fun bracketsAndPress() {
        assertEquals(listOf("1 Play=1", "4 Play=4", "q Quit=q"), hints(
            "        Type:   <1>     to play \"Bloodbath\"", "                <4>     to play \"Invisible\"", "                <q>     to quit"))
        assertEquals(listOf("D Display=D", "Q Quit=Q"), hints("[D] Display options", "[Q] Quit"))
        assertEquals(listOf("e Fish=e"), hints("Suffering PSE? Press e to fish"))
        assertEquals(listOf("? Help=?"), hints("Greed v4.2 - Hit '?' for help."))
        assertEquals(listOf("q Quit=q", "n New game=n"), hints("(q)uit (n)ew game"))
    }

    @Test
    fun keysAButtonAlreadySendsAreLeftOut() {
        assertEquals(listOf("q Quit=q"), hints("  p - pause", "  q - quit", covered = setOf("p")))
        assertEquals(listOf("Esc Back=ESCAPE"), hints("  space - drop", "  esc - back", covered = setOf(" ")))
    }

    @Test
    fun movesAreLeftToThePad() {
        assertEquals(listOf("c Check=c"), KeyHints.detect(listOf("  h - Move left", "  j - Move down", "  c - Check solution"),
            hasPad = true).map { it.label + "=" + KeyHints.keyId(it.output) })
    }

    @Test
    fun ordinaryTextOffersNothing() {
        assertEquals(emptyList(), hints("              o=oo=oo=oo=oo=oo=oo=o"))
        assertEquals(emptyList(), hints("SPC,j:jump  a,l:fire  r,C-l:redraw"))
        assertEquals(emptyList(), hints("Select the endpoint of the line by pressing <enter> or <space>"))
        assertEquals(emptyList(), hints("You are standing at the end of a road before a small brick building."))
        assertEquals(emptyList(), hints("Score: 0  Level: 1", "Time: 00:01:23"))
        assertEquals(emptyList(), hints("Copyright (c) 1984 Ken Arnold"))
        assertEquals(emptyList(), hints("2 + 2 =   0", "What?"))
    }
}
