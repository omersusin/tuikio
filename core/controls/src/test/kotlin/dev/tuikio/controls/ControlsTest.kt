package dev.tuikio.controls

import dev.tuikio.json.JsonReader
import dev.tuikio.terminal.KeyEncoder
import dev.tuikio.terminal.TermKey
import dev.tuikio.terminal.TerminalEmulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ControlsTest {
    private val tetris = """
        {
          "schema_version": 1,
          "profile_id": "blocks.v1",
          "title": "Falling blocks",
          "orientation": "portrait",
          "actions": [
            {"id": "left", "label": "Left", "output": {"key": "LEFT"}, "repeat": {"delay_ms": 170, "interval_ms": 50}},
            {"id": "right", "label": "Right", "output": {"key": "RIGHT"}, "repeat": {"delay_ms": 170, "interval_ms": 50}},
            {"id": "rotate", "label": "Rotate", "output": {"key": "UP"}},
            {"id": "soft", "label": "Soft", "output": {"key": "DOWN"}, "repeat": {"delay_ms": 120, "interval_ms": 40}},
            {"id": "drop", "label": "Drop", "output": {"text": " "}, "haptic": "heavy"},
            {"id": "quit", "label": "Quit", "output": {"sequence": [{"text": "q"}, {"text": "y"}]}, "confirm": true}
          ],
          "layout": {
            "portrait": [
              {"type": "dpad4", "anchor": "bottom_start", "size": 168, "bind": {"up": "rotate", "down": "soft", "left": "left", "right": "right"}},
              {"type": "button", "anchor": "bottom_end", "size": 84, "bind": {"press": "drop"}, "style": "primary"}
            ]
          },
          "gestures": {"swipe_up": "rotate", "swipe_down": "drop"}
        }
    """.trimIndent()

    private class FakeScheduler : Scheduler {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        override fun schedule(delayMs: Long, task: () -> Unit): Scheduler.Cancellable {
            val flag = booleanArrayOf(false)
            tasks += Triple(now + delayMs, task, flag)
            return Scheduler.Cancellable { flag[0] = true }
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { !it.third[0] && it.first <= end }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                now = next.first
                next.second()
            }
            now = end
        }
    }

    private val term = TerminalEmulator(80, 24)
    private val sent = mutableListOf<String>()
    private val scheduler = FakeScheduler()
    private val profile = ControlProfile.parse(tetris)
    private val dispatcher = ControlDispatcher(profile, KeyEncoder(term), scheduler) { sent += String(it, Charsets.UTF_8) }

    @Test
    fun parsesActionsLayoutAndGestures() {
        assertEquals(6, profile.actions.size)
        assertEquals("rotate", profile.gestures["swipe_up"])
        assertEquals(2, profile.layout(landscapeMode = true).size, "landscape falls back to portrait")
        assertEquals(Haptic.HEAVY, profile.actions.getValue("drop").haptic)
    }

    @Test
    fun pressSendsKeysHonoringCursorMode() {
        dispatcher.press("rotate")
        term.feed("\u001b[?1h")
        dispatcher.press("rotate")
        assertEquals(listOf("\u001b[A", "\u001bOA"), sent)
    }

    @Test
    fun holdRepeatsAfterDelayUntilReleased() {
        dispatcher.press("left")
        scheduler.advance(169)
        assertEquals(1, sent.size)
        scheduler.advance(1 + 50 * 3)
        assertEquals(1 + 1 + 3, sent.size, "first repeat at 170 ms, then every 50 ms")
        dispatcher.release("left")
        scheduler.advance(500)
        assertEquals(5, sent.size)
        assertEquals(0, dispatcher.activeRepeats)
    }

    @Test
    fun releaseAllStopsEveryRepeat() {
        dispatcher.press("left")
        dispatcher.press("soft")
        dispatcher.releaseAll()
        scheduler.advance(1000)
        assertEquals(2, sent.size)
    }

    @Test
    fun confirmGuardsDestructiveActions() {
        dispatcher.confirm = { false }
        dispatcher.press("quit")
        assertTrue(sent.isEmpty())
        dispatcher.confirm = { true }
        dispatcher.press("quit")
        assertEquals(listOf("q", "y"), sent)
    }

    @Test
    fun rejectsInvalidProfiles() {
        fun bad(edit: (String) -> String) = assertFailsWith<JsonReader.InvalidException> { ControlProfile.parse(edit(tetris)) }
        bad { it.replace("\"key\": \"UP\"", "\"key\": \"WARP\"") }
        bad { it.replace("\"press\": \"drop\"", "\"press\": \"nope\"") }
        bad { it.replace("\"delay_ms\": 170", "\"delay_ms\": 5") }
        bad { it.replace("\"text\": \" \"", "\"text\": \"\\u001b[2J\"") }
        bad { it.replace("\"swipe_up\"", "\"shake\"") }
        bad { it.replace("\"size\": 84", "\"size\": 4000") }
        bad { it.replace("\"title\": \"Falling blocks\",", "\"title\": \"x\", \"run\": \"rm -rf /\",") }
    }

    @Test
    fun padGeometryMapsAnglesToDirections() {
        assertEquals("up", PadGeometry.direction(0f, -50f, 100f, eightWay = false))
        assertEquals("right", PadGeometry.direction(60f, -20f, 100f, eightWay = false))
        assertEquals(null, PadGeometry.direction(5f, 5f, 100f, eightWay = false))
        assertEquals("center", PadGeometry.direction(5f, 5f, 100f, eightWay = true))
        assertEquals("up_left", PadGeometry.direction(-50f, -50f, 100f, eightWay = true))
        assertEquals("down_right", PadGeometry.direction(50f, 50f, 100f, eightWay = true))
        assertEquals("down", PadGeometry.direction(4f, 80f, 100f, eightWay = true))
    }

    @Test
    fun keyboardTextAndSequences() {
        dispatcher.emit(ActionOutput.Sequence(listOf(ActionOutput.Key(TermKey.ESCAPE), ActionOutput.Text(":wq"), ActionOutput.Key(TermKey.ENTER))))
        assertEquals(listOf("\u001b", ":wq", "\r"), sent)
    }
}
