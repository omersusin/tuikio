package dev.tuikio.terminal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalEmulatorTest {
    private val replies = mutableListOf<String>()
    private fun term(cols: Int = 10, rows: Int = 4) =
        TerminalEmulator(cols, rows, scrollback = 50) { replies += String(it, Charsets.UTF_8) }

    private fun TerminalEmulator.lines() = screen.text()
    private fun TerminalEmulator.style(row: Int, col: Int) = screen[row].styles[col]

    @Test
    fun printsAndWrapsWithPendingWrapAtTheMargin() {
        val t = term(5, 3)
        t.feed("abcde")
        assertEquals(0, t.cursorRow)
        assertEquals(4, t.cursorCol, "cursor parks on the last column until the next glyph")
        t.feed("f")
        assertEquals(listOf("abcde", "f", ""), t.lines())
        assertTrue(t.screen[0].wrapped)
    }

    @Test
    fun wordWrapMovesTheBrokenWordToTheNextLine() {
        val t = term(20, 4)
        t.wordWrap = true
        t.feed("You are standing at the end of a road.\r\n")
        assertEquals(listOf("You are standing at", "the end of a road.", "", ""), t.lines().map { it.trimEnd() })
        t.feed("A small stream flows out of the building")
        assertEquals("A small stream flows", t.lines()[2].trimEnd(), "a space at the break isn't carried over")
        assertEquals("out of the building", t.lines()[3].trimEnd())
    }

    @Test
    fun wordWrapLeavesWordsTooLongToMoveAndIsOffByDefault() {
        val t = term(10, 3)
        t.wordWrap = true
        t.feed("abcdefghijklmn")
        assertEquals(listOf("abcdefghij", "klmn", ""), t.lines(), "no space to break at: hard wrap")
        val plain = term(10, 3)
        plain.feed("aaaa bbbbbbb")
        assertEquals(listOf("aaaa bbbbb", "bb", ""), plain.lines())
    }

    @Test
    fun carriageReturnClearsPendingWrap() {
        val t = term(5, 3)
        t.feed("abcde\rX")
        assertEquals(listOf("Xbcde", "", ""), t.lines())
    }

    @Test
    fun cursorPositioningAndErasing() {
        val t = term(6, 3)
        t.feed("aaaaaa\r\nbbbbbb\r\ncccccc")
        t.feed("\u001b[2;3H\u001b[K")
        assertEquals(listOf("aaaaaa", "bb", "cccccc"), t.lines())
        t.feed("\u001b[1;4H\u001b[1K")
        assertEquals(listOf("    aa", "bb", "cccccc"), t.lines())
        t.feed("\u001b[2J")
        assertEquals(listOf("", "", ""), t.lines())
    }

    @Test
    fun eraseKeepsBackgroundColorLikeXterm() {
        val t = term(4, 2)
        t.feed("\u001b[44m\u001b[2J\u001b[0m")
        assertEquals(4, CellStyle.bg(t.style(1, 3)))
    }

    @Test
    fun sgrBasicIndexedAndTrueColor() {
        val t = term(20, 2)
        t.feed("\u001b[1;31mA\u001b[0m\u001b[38;5;208mB\u001b[48;2;1;2;3mC\u001b[38:2::9:8:7mD\u001b[7;4mE\u001b[27;24;22mF")
        assertTrue(CellStyle.has(t.style(0, 0), CellStyle.BOLD))
        assertEquals(1, CellStyle.fg(t.style(0, 0)))
        assertEquals(208, CellStyle.fg(t.style(0, 1)))
        assertFalse(CellStyle.has(t.style(0, 1), CellStyle.BOLD), "SGR 0 resets bold")
        assertEquals(TermColor.rgb(1, 2, 3), CellStyle.bg(t.style(0, 2)))
        assertEquals(TermColor.rgb(9, 8, 7), CellStyle.fg(t.style(0, 3)))
        assertTrue(CellStyle.has(t.style(0, 4), CellStyle.INVERSE))
        assertTrue(CellStyle.has(t.style(0, 4), CellStyle.UNDERLINE))
        assertFalse(CellStyle.has(t.style(0, 5), CellStyle.INVERSE))
        assertFalse(CellStyle.has(t.style(0, 5), CellStyle.UNDERLINE))
    }

    @Test
    fun brightColorsAndDefaults() {
        val t = term(4, 1)
        t.feed("\u001b[92;103mX\u001b[39;49mY")
        assertEquals(10, CellStyle.fg(t.style(0, 0)))
        assertEquals(11, CellStyle.bg(t.style(0, 0)))
        assertEquals(TermColor.DEFAULT, CellStyle.fg(t.style(0, 1)))
        assertEquals(TermColor.DEFAULT, CellStyle.bg(t.style(0, 1)))
    }

    @Test
    fun alternateScreenPreservesMainScreenAndCursor() {
        val t = term(8, 3)
        t.feed("shell$ ")
        val col = t.cursorCol
        t.feed("\u001b[?1049h")
        assertTrue(t.isAltScreen)
        assertEquals(listOf("", "", ""), t.lines())
        t.feed("\u001b[2;2Hgame")
        t.feed("\u001b[?1049l")
        assertFalse(t.isAltScreen)
        assertEquals(listOf("shell$", "", ""), t.lines())
        assertEquals(0, t.cursorRow)
        assertEquals(col, t.cursorCol)
    }

    @Test
    fun scrollRegionOnlyScrollsInsideMargins() {
        val t = term(4, 5)
        t.feed("top\r\n1\r\n2\r\n3\r\nbot")
        t.feed("\u001b[2;4r")      // rows 2..4
        t.feed("\u001b[4;1H\n")     // LF on the bottom margin
        assertEquals(listOf("top", "2", "3", "", "bot"), t.lines())
        t.feed("\u001b[2;1H\u001bM") // reverse index at the top margin
        assertEquals(listOf("top", "", "2", "3", "bot"), t.lines())
    }

    @Test
    fun insertAndDeleteLinesRespectTheRegion() {
        val t = term(3, 4)
        t.feed("a\r\nb\r\nc\r\nd")
        t.feed("\u001b[2;1H\u001b[L")
        assertEquals(listOf("a", "", "b", "c"), t.lines())
        t.feed("\u001b[2M")
        assertEquals(listOf("a", "c", "", ""), t.lines())
    }

    @Test
    fun insertDeleteEraseCharactersAndRepeat() {
        val t = term(8, 1)
        t.feed("abcdef\u001b[1;3H\u001b[2@")
        assertEquals("ab  cdef", t.lines()[0])
        t.feed("\u001b[3P")
        assertEquals("abdef", t.lines()[0])
        t.feed("\u001b[2X")
        assertEquals("ab  f", t.lines()[0].trimEnd())
        t.feed("\r=\u001b[3b")
        assertEquals("====f", t.lines()[0])
    }

    @Test
    fun wideCharactersTakeTwoCellsAndRepairWhenOverwritten() {
        val t = term(6, 2)
        t.feed("漢字")
        assertEquals("漢字", t.lines()[0])
        assertTrue(CellStyle.has(t.style(0, 0), CellStyle.WIDE))
        assertTrue(CellStyle.has(t.style(0, 1), CellStyle.WIDE_TAIL))
        assertEquals(4, t.cursorCol)
        t.feed("\u001b[1;2Hx") // overwrite the tail of 漢
        assertEquals(" x字", t.lines()[0])
        assertFalse(CellStyle.has(t.style(0, 0), CellStyle.WIDE))
    }

    @Test
    fun wideCharacterAtTheLastColumnWrapsWhole() {
        val t = term(5, 2)
        t.feed("abcd漢")
        assertEquals(listOf("abcd", "漢"), t.lines())
    }

    @Test
    fun combiningMarksJoinThePreviousCell() {
        val t = term(5, 1)
        t.feed("éx")
        assertEquals("éx", t.lines()[0])
        assertEquals(2, t.cursorCol)
    }

    @Test
    fun decSpecialGraphicsDrawsBoxes() {
        val t = term(5, 2)
        t.feed("\u001b(0lqqk\r\nx  x\u001b(B")
        assertEquals(listOf("┌──┐", "│  │"), t.lines())
        t.feed("q")
        assertEquals("│  │q", t.lines()[1])
    }

    @Test
    fun shiftOutUsesG1() {
        val t = term(4, 1)
        t.feed("\u001b)0\u000eqq\u000fq")
        assertEquals("──q", t.lines()[0])
    }

    @Test
    fun reportsCursorPositionAndDeviceAttributes() {
        val t = term(10, 5)
        t.feed("\u001b[3;7H\u001b[6n\u001b[c\u001b[>c\u001b[5n")
        assertEquals(listOf("\u001b[3;7R", "\u001b[?62;22c", "\u001b[>0;10;1c", "\u001b[0n"), replies)
    }

    @Test
    fun originModeIsRelativeToMargins() {
        val t = term(5, 5)
        t.feed("\u001b[2;4r\u001b[?6h\u001b[1;1HX\u001b[6n")
        assertEquals("X", t.lines()[1])
        assertEquals("\u001b[1;2R", replies.last(), "row is relative to the top margin")
    }

    @Test
    fun utf8SplitAcrossReadsDecodes() {
        val t = term(5, 1)
        val bytes = "é漢".toByteArray()
        for (b in bytes) t.feed(byteArrayOf(b))
        assertEquals("é漢", t.lines()[0])
    }

    @Test
    fun invalidUtf8BecomesReplacementCharacters() {
        val t = term(5, 1)
        t.feed(byteArrayOf(0xC3.toByte(), 'a'.code.toByte(), 0xFF.toByte()))
        assertEquals("�a�", t.lines()[0])
    }

    @Test
    fun titleFromOscWithBelOrStringTerminator() {
        val t = term()
        t.feed("\u001b]0;Moon Buggy\u0007")
        assertEquals("Moon Buggy", t.title)
        t.feed("\u001b]2;Greed\u001b\\after")
        assertEquals("Greed", t.title)
        assertEquals("after", t.lines()[0])
    }

    @Test
    fun applicationModesAreTracked() {
        val t = term()
        t.feed("\u001b[?1h\u001b=\u001b[?25l\u001b[?1000h\u001b[?1006h\u001b[?2004h")
        assertTrue(t.applicationCursorKeys)
        assertTrue(t.applicationKeypad)
        assertFalse(t.cursorVisible)
        assertEquals(TerminalEmulator.MouseTracking.NORMAL, t.mouseTracking)
        assertEquals(TerminalEmulator.MouseEncoding.SGR, t.mouseEncoding)
        assertTrue(t.bracketedPaste)
        t.feed("\u001b[?1l\u001b>\u001b[?25h\u001b[?1000l")
        assertFalse(t.applicationCursorKeys)
        assertFalse(t.applicationKeypad)
        assertTrue(t.cursorVisible)
        assertEquals(TerminalEmulator.MouseTracking.OFF, t.mouseTracking)
    }

    @Test
    fun tabsStopEveryEightColumns() {
        val t = term(20, 1)
        t.feed("a\tb\tc")
        assertEquals(8, t.screen[0].text().indexOf('b'))
        assertEquals(16, t.screen[0].text().indexOf('c'))
    }

    @Test
    fun linesScrolledOffTheMainScreenGoToScrollback() {
        val t = term(5, 2)
        t.feed("one\r\ntwo\r\nthree\r\nfour")
        assertEquals(listOf("three", "four"), t.lines())
        assertEquals(2, t.screen.scrollbackSize)
        assertEquals("one", t.screen.historyLine(0).text())
    }

    @Test
    fun resizeKeepsContentAndCursorVisible() {
        val t = term(6, 4)
        t.feed("a\r\nb\r\nc\r\nd")
        t.resize(3, 2)
        assertEquals(listOf("c", "d"), t.lines())
        assertEquals(1, t.cursorRow)
        t.resize(8, 5)
        assertEquals(5, t.lines().size)
        assertEquals("c", t.lines()[0])
    }

    @Test
    fun damageIsTrackedPerRow() {
        val t = term(5, 4)
        val dirty = BooleanArray(4)
        t.screen.collectDamage(dirty)
        t.feed("\u001b[3;1Hx")
        assertTrue(t.screen.collectDamage(dirty))
        assertEquals(listOf(false, false, true, false), dirty.toList())
        assertFalse(t.screen.collectDamage(dirty))
    }

    @Test
    fun saveAndRestoreCursorKeepsPen() {
        val t = term(6, 2)
        t.feed("\u001b[31m\u001b7\u001b[0m\u001b[2;4H\u001b8Z")
        assertEquals("Z", t.lines()[0])
        assertEquals(1, CellStyle.fg(t.style(0, 0)))
    }

    @Test
    fun cancelAbortsAnEscapeSequence() {
        val t = term(6, 1)
        t.feed("\u001b[31\u0018ok")
        assertEquals("ok", t.lines()[0])
        assertEquals(TermColor.DEFAULT, CellStyle.fg(t.style(0, 0)))
    }

    @Test
    fun randomGarbageNeverCrashesOrEscapesTheGrid() {
        val rnd = Random(20261022)
        val t = term(40, 12)
        val alphabet = "\u001b[];?:0123456789mHJKLMPXr@hl\u0007\r\n\t\u0008()0Bc7Dqx漢é".toByteArray()
        repeat(2000) {
            val n = rnd.nextInt(1, 200)
            val chunk = ByteArray(n) { if (rnd.nextInt(4) == 0) rnd.nextInt(256).toByte() else alphabet[rnd.nextInt(alphabet.size)] }
            t.feed(chunk)
            assertTrue(t.cursorRow in 0 until t.rows && t.cursorCol in 0 until t.cols)
            if (rnd.nextInt(100) == 0) t.resize(rnd.nextInt(2, 60), rnd.nextInt(2, 30))
        }
    }
}
