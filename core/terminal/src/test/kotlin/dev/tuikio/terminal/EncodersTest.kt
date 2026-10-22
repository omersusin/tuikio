package dev.tuikio.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EncodersTest {
    private val term = TerminalEmulator(80, 24)
    private val keys = KeyEncoder(term)
    private val mouse = MouseEncoder(term)

    private fun s(b: ByteArray?) = b?.let { String(it, Charsets.UTF_8) }

    @Test
    fun arrowsFollowApplicationCursorMode() {
        assertEquals("\u001b[A", keys.sequence(TermKey.UP))
        term.feed("\u001b[?1h")
        assertEquals("\u001bOA", keys.sequence(TermKey.UP))
        assertEquals("\u001bOH", keys.sequence(TermKey.HOME))
        assertEquals("\u001b[1;5C", keys.sequence(TermKey.RIGHT, KeyMods.CTRL), "modifiers force CSI form")
    }

    @Test
    fun editingAndFunctionKeys() {
        assertEquals("\u001b[3~", keys.sequence(TermKey.DELETE))
        assertEquals("\u001b[5;2~", keys.sequence(TermKey.PAGE_UP, KeyMods.SHIFT))
        assertEquals("\u001bOP", keys.sequence(TermKey.F1))
        assertEquals("\u001b[24~", keys.sequence(TermKey.F12))
        assertEquals("\u007f", keys.sequence(TermKey.BACKSPACE))
        assertEquals("\u001b[Z", keys.sequence(TermKey.TAB, KeyMods.SHIFT))
        assertEquals("\r", keys.sequence(TermKey.ENTER))
        assertEquals("\u001b\r", keys.sequence(TermKey.ENTER, KeyMods.ALT))
    }

    @Test
    fun keypadDigitsInApplicationKeypadMode() {
        assertEquals("7", keys.sequence(TermKey.KP_7))
        term.feed("\u001b=")
        assertEquals("\u001bOw", keys.sequence(TermKey.KP_7))
    }

    @Test
    fun ctrlAndAltText() {
        assertContentEquals(byteArrayOf(3), keys.text("c", KeyMods.CTRL))
        assertContentEquals(byteArrayOf(0x1b, 'x'.code.toByte()), keys.text("x", KeyMods.ALT))
        assertEquals("héllo", s(keys.text("héllo")))
    }

    @Test
    fun pasteIsBracketedAndSanitized() {
        assertEquals("ab\rc", s(keys.paste("a\u001b[201~b\nc\u0007")))
        term.feed("\u001b[?2004h")
        assertEquals("\u001b[200~hi\u001b[201~", s(keys.paste("hi")))
    }

    @Test
    fun mouseIsSilentUntilRequested() {
        assertNull(mouse.encode(MouseEncoder.Action.PRESS, 0, 3, 4))
    }

    @Test
    fun sgrMouseReportsPressAndRelease() {
        term.feed("\u001b[?1000h\u001b[?1006h")
        assertEquals("\u001b[<0;4;5M", s(mouse.encode(MouseEncoder.Action.PRESS, 0, 3, 4)))
        assertEquals("\u001b[<0;4;5m", s(mouse.encode(MouseEncoder.Action.RELEASE, 0, 3, 4)))
        assertNull(mouse.encode(MouseEncoder.Action.MOVE, 0, 3, 4), "plain 1000 mode has no motion")
    }

    @Test
    fun legacyMouseEncoding() {
        term.feed("\u001b[?1002h")
        assertEquals("\u001b[M !\"", s(mouse.encode(MouseEncoder.Action.PRESS, 0, 0, 1)))
        assertEquals("\u001b[M#!\"", s(mouse.encode(MouseEncoder.Action.RELEASE, 0, 0, 1)))
        assertEquals("\u001b[M@!\"", s(mouse.encode(MouseEncoder.Action.MOVE, 0, 0, 1)))
    }

    @Test
    fun widthTable() {
        assertEquals(1, UnicodeWidth.of('a'.code))
        assertEquals(2, UnicodeWidth.of('漢'.code))
        assertEquals(2, UnicodeWidth.of(0x1F600))
        assertEquals(0, UnicodeWidth.of(0x0301))
        assertEquals(1, UnicodeWidth.of('─'.code))
        assertEquals(1, UnicodeWidth.of(0x2800 + 0x3F), "braille is one cell")
    }
}
