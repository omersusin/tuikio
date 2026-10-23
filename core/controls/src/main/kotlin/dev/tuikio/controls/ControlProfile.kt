package dev.tuikio.controls

import dev.tuikio.catalog.Orientation
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import dev.tuikio.terminal.KeyMods
import dev.tuikio.terminal.TermKey

/** What pressing an action sends to the game. */
sealed class ActionOutput {
    data class Key(val key: TermKey, val mods: Int = KeyMods.NONE) : ActionOutput()
    data class Text(val text: String, val mods: Int = KeyMods.NONE) : ActionOutput()
    data class Sequence(val steps: List<ActionOutput>) : ActionOutput()
}

data class RepeatPolicy(val delayMs: Long, val intervalMs: Long)

enum class Haptic { NONE, TICK, CLICK, HEAVY }

data class ControlAction(
    val id: String,
    val label: String,
    val icon: String?,
    val output: ActionOutput,
    val repeat: RepeatPolicy?,
    val haptic: Haptic,
    /** Ask before sending (quit, restart): one stray tap must not end a long game. */
    val confirm: Boolean,
    /** Alternate looks the button takes while the game's screen shows something (paused...). */
    val states: List<ActionState> = emptyList(),
) {
    /** The state that applies to [screen] (the terminal's text), or null for the normal look. */
    fun stateFor(screen: CharSequence): ActionState? = states.firstOrNull { it.match.containsMatchIn(screen) }
}

/**
 * While the screen matches [match], the action is shown as [label]/[icon] and, if given, sends
 * [output] instead: a Pause button reads "Resume" once the game says it is paused, and sends
 * whatever that game wants to carry on.
 */
data class ActionState(val match: Regex, val label: String, val icon: String?, val output: ActionOutput?)

enum class Anchor(val id: String) {
    TOP_START("top_start"), TOP_END("top_end"), CENTER_START("center_start"), CENTER_END("center_end"),
    BOTTOM_START("bottom_start"), BOTTOM_CENTER("bottom_center"), BOTTOM_END("bottom_end");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown anchor '$s'")
    }
}

enum class ElementType(val id: String) {
    DPAD4("dpad4"), DPAD8("dpad8"), BUTTON("button"), KEYBOARD("keyboard");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown element type '$s'")
    }
}

/**
 * One on-screen control. Positions are relative to an anchor of the controls area, in dp,
 * so layouts adapt to screen size; users can override anchor, offset and size per game.
 */
data class ControlElement(
    val type: ElementType,
    val anchor: Anchor,
    val offsetDp: Pair<Int, Int>,
    val sizeDp: Int,
    /** For pads: direction (`up`, `down_left`, `center`…) to action id. For buttons: `press`. */
    val bindings: Map<String, String>,
    val style: String,
)

data class Gestures(val bindings: Map<String, String>) {
    operator fun get(gesture: String): String? = bindings[gesture]
}

enum class PointerMode(val id: String) {
    /** Taps on the board are sent as xterm mouse reports when the game enabled mouse input. */
    MOUSE("mouse"),
    NONE("none");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown pointer mode '$s'")
    }
}

/**
 * A declarative control scheme for one game or family of games, loaded from JSON
 * (see catalog/schema/control-profile.schema.json). It decides which controls exist and
 * where; [ControlDispatcher] turns presses into terminal input.
 */
data class ControlProfile(
    val id: String,
    val title: String,
    val help: String,
    val orientation: Orientation,
    val actions: Map<String, ControlAction>,
    val portrait: List<ControlElement>,
    val landscape: List<ControlElement>,
    val gestures: Gestures,
    val pointer: PointerMode,
    val keyboard: Boolean,
) {
    fun layout(landscapeMode: Boolean): List<ControlElement> = if (landscapeMode && landscape.isNotEmpty()) landscape else portrait

    /**
     * This profile as one game uses it: actions named for what they do there ("Select" reads
     * "Reveal" in Mines), and buttons for actions the game has no use for left out.
     */
    fun forGame(labels: Map<String, String>, hidden: Set<String>): ControlProfile {
        if (labels.isEmpty() && hidden.isEmpty()) return this
        fun keep(e: ControlElement) = e.type != ElementType.BUTTON || e.bindings["press"] !in hidden
        return copy(
            actions = actions.mapValues { (id, a) -> labels[id]?.let { a.copy(label = it) } ?: a },
            portrait = portrait.filter(::keep),
            landscape = landscape.filter(::keep),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private val ID = Regex("^[a-z0-9][a-z0-9_.-]{1,47}$")
        private val DIRECTIONS4 = setOf("up", "down", "left", "right")
        private val DIRECTIONS8 = DIRECTIONS4 + setOf("up_left", "up_right", "down_left", "down_right", "center")
        private val GESTURES = setOf("swipe_up", "swipe_down", "swipe_left", "swipe_right", "double_tap", "long_press")

        fun parse(text: String): ControlProfile = fromJson(JsonReader.parse(text))

        fun fromJson(r: JsonReader): ControlProfile {
            r.requireOnly("schema_version", "profile_id", "title", "help", "orientation", "actions", "layout", "gestures", "pointer", "keyboard")
            if (r.int("schema_version") != SCHEMA_VERSION) r.fail("unsupported schema_version")
            val id = r.string("profile_id")
            if (!ID.matches(id)) r.fail("profile_id '$id' is invalid")
            val actions = LinkedHashMap<String, ControlAction>()
            for (a in r.objects("actions")) {
                a.requireOnly("id", "label", "icon", "output", "repeat", "haptic", "confirm", "states")
                val aid = a.string("id")
                if (!Regex("^[a-z0-9_]{1,32}$").matches(aid)) a.fail("action id '$aid' is invalid")
                if (aid in actions) a.fail("duplicate action id '$aid'")
                val repeat = a.objOrNull("repeat")?.let { rp ->
                    rp.requireOnly("delay_ms", "interval_ms")
                    val d = rp.int("delay_ms")
                    val i = rp.int("interval_ms")
                    if (d !in 50..2000 || i !in 16..1000) rp.fail("repeat timings out of range (delay 50..2000, interval 16..1000 ms)")
                    RepeatPolicy(d.toLong(), i.toLong())
                }
                actions[aid] = ControlAction(
                    id = aid,
                    label = a.string("label").also { if (it.isBlank() || it.length > 16) a.fail("label must be 1..16 characters") },
                    icon = a.stringOrNull("icon"),
                    output = output(a.obj("output"), 0),
                    repeat = repeat,
                    haptic = a.stringOrNull("haptic")?.let { h -> Haptic.values().firstOrNull { it.name.equals(h, true) } ?: a.fail("unknown haptic '$h'") } ?: Haptic.TICK,
                    confirm = a.bool("confirm", false),
                    states = a.objects("states").also { if (it.size > 4) a.fail("at most 4 states") }.map { st ->
                        st.requireOnly("when", "label", "icon", "output")
                        val pattern = st.string("when")
                        val match = runCatching { Regex(pattern) }.getOrElse { st.fail("'when' is not a valid regular expression: ${it.message}") }
                        ActionState(
                            match = match,
                            label = st.string("label").also { if (it.isBlank() || it.length > 16) st.fail("label must be 1..16 characters") },
                            icon = st.stringOrNull("icon"),
                            output = st.objOrNull("output")?.let { output(it, 0) },
                        )
                    },
                )
            }
            if (actions.isEmpty()) r.fail("a profile needs at least one action")
            val layout = r.obj("layout")
            layout.requireOnly("portrait", "landscape")
            val portrait = layout.objects("portrait").map { element(it, actions.keys) }
            val landscape = layout.objects("landscape").map { element(it, actions.keys) }
            if (portrait.isEmpty()) layout.fail("portrait layout is required")
            val gestures = r.objOrNull("gestures")?.let { g ->
                val map = LinkedHashMap<String, String>()
                for (k in g.keys()) {
                    if (k !in GESTURES) g.fail("unknown gesture '$k'")
                    val target = g.string(k)
                    if (target !in actions) g.fail("gesture '$k' points at unknown action '$target'")
                    map[k] = target
                }
                Gestures(map)
            } ?: Gestures(emptyMap())
            return ControlProfile(
                id = id,
                title = r.string("title"),
                help = r.stringOrNull("help").orEmpty(),
                orientation = Orientation.parse(r.stringOrNull("orientation") ?: "any"),
                actions = actions,
                portrait = portrait,
                landscape = landscape,
                gestures = gestures,
                pointer = PointerMode.parse(r.stringOrNull("pointer") ?: "none"),
                keyboard = r.bool("keyboard", true),
            )
        }

        private fun element(e: JsonReader, actions: Set<String>): ControlElement {
            e.requireOnly("type", "anchor", "offset", "size", "bind", "style")
            val type = ElementType.parse(e.string("type"))
            val offset = e.ints("offset")
            if (offset.isNotEmpty() && (offset.size != 2 || offset.any { it !in -400..400 })) e.fail("offset must be [dx, dy] within ±400 dp")
            val size = e.int("size")
            if (size !in 40..280) e.fail("size must be 40..280 dp")
            val bind = e.stringMap("bind")
            val allowed = when (type) {
                ElementType.DPAD4 -> DIRECTIONS4
                ElementType.DPAD8 -> DIRECTIONS8
                ElementType.BUTTON -> setOf("press")
                ElementType.KEYBOARD -> emptySet()
            }
            for ((k, v) in bind) {
                if (k !in allowed) e.fail("'$k' is not a valid binding for ${type.id}")
                if (v !in actions) e.fail("binding '$k' points at unknown action '$v'")
            }
            if (type == ElementType.BUTTON && "press" !in bind) e.fail("a button needs bind.press")
            if (type == ElementType.DPAD4 && !bind.keys.containsAll(DIRECTIONS4)) e.fail("dpad4 needs up, down, left and right")
            return ControlElement(
                type = type,
                anchor = Anchor.parse(e.string("anchor")),
                offsetDp = if (offset.size == 2) offset[0] to offset[1] else 0 to 0,
                sizeDp = size,
                bindings = bind,
                style = e.stringOrNull("style") ?: "normal",
            )
        }

        private fun output(o: JsonReader, depth: Int): ActionOutput {
            if (depth > 2) o.fail("sequences nest too deeply")
            o.requireOnly("key", "text", "mods", "sequence")
            val mods = o.strings("mods").fold(KeyMods.NONE) { acc, m ->
                acc or when (m) {
                    "shift" -> KeyMods.SHIFT
                    "alt" -> KeyMods.ALT
                    "ctrl" -> KeyMods.CTRL
                    else -> o.fail("unknown modifier '$m'")
                }
            }
            val kinds = listOf("key", "text", "sequence").count { o.has(it) }
            if (kinds != 1) o.fail("output needs exactly one of key, text or sequence")
            return when {
                o.has("key") -> {
                    val name = o.string("key")
                    val key = TermKey.values().firstOrNull { it.name == name } ?: o.fail("unknown key '$name'")
                    ActionOutput.Key(key, mods)
                }
                o.has("text") -> {
                    val t = o.string("text")
                    if (t.isEmpty() || t.length > 64) o.fail("text must be 1..64 characters")
                    if (t.any { it < ' ' && it != '\r' && it != '\t' }) o.fail("text may not contain control characters; use key")
                    ActionOutput.Text(t, mods)
                }
                else -> {
                    val steps = o.array("sequence").mapIndexed { i, j ->
                        output(JsonReader(j as? Json.Obj ?: o.fail("sequence[$i] must be an object"), "sequence[$i]"), depth + 1)
                    }
                    if (steps.isEmpty() || steps.size > 8) o.fail("sequence must have 1..8 steps")
                    ActionOutput.Sequence(steps)
                }
            }
        }
    }
}
