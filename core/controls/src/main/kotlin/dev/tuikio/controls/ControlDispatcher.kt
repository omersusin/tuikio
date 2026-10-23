package dev.tuikio.controls

import dev.tuikio.terminal.KeyEncoder

/** Minimal timer abstraction so repeat timing is testable without real clocks. */
interface Scheduler {
    fun interface Cancellable {
        fun cancel()
    }

    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/**
 * Turns control presses into terminal input. Held actions with a [RepeatPolicy] repeat
 * after an initial delay, like a keyboard's typematic repeat, until released. Rules of
 * the game are never changed: every press is exactly the key the game expects.
 */
class ControlDispatcher(
    private val profile: ControlProfile,
    private val encoder: KeyEncoder,
    private val scheduler: Scheduler,
    private val send: (ByteArray) -> Unit,
) {
    private val repeating = HashMap<String, Scheduler.Cancellable>()

    /** Called for actions with [ControlAction.confirm]; return true to send. */
    var confirm: (ControlAction) -> Boolean = { true }

    /** Notified on each press (for haptics and the on-screen key hint). */
    var onPressed: (ControlAction) -> Unit = {}

    /** The state an action is in right now (see [ControlAction.states]); set by the game screen. */
    var stateOf: (ControlAction) -> ActionState? = { null }

    fun press(actionId: String) {
        val action = profile.actions[actionId] ?: return
        if (action.confirm && !confirm(action)) return
        onPressed(action)
        emit(stateOf(action)?.output ?: action.output)
        val repeat = action.repeat ?: return
        repeating.remove(actionId)?.cancel()
        repeating[actionId] = scheduleRepeat(action, repeat.delayMs, repeat.intervalMs)
    }

    fun release(actionId: String) {
        repeating.remove(actionId)?.cancel()
    }

    fun releaseAll() {
        repeating.values.forEach { it.cancel() }
        repeating.clear()
    }

    val activeRepeats: Int get() = repeating.size

    private fun scheduleRepeat(action: ControlAction, delay: Long, interval: Long): Scheduler.Cancellable {
        val repeat = object : Scheduler.Cancellable {
            var pending: Scheduler.Cancellable? = null
            var cancelled = false

            fun tick() {
                if (cancelled) return
                emit(action.output)
                pending = scheduler.schedule(interval) { tick() }
            }

            override fun cancel() {
                cancelled = true
                pending?.cancel()
            }
        }
        repeat.pending = scheduler.schedule(delay) { repeat.tick() }
        return repeat
    }

    fun emit(output: ActionOutput) {
        when (output) {
            is ActionOutput.Key -> send(encoder.encode(output.key, output.mods))
            is ActionOutput.Text -> send(encoder.text(output.text, output.mods))
            is ActionOutput.Sequence -> output.steps.forEach { emit(it) }
        }
    }
}

/**
 * Maps a touch position on a d-pad (relative to its center, any unit) to a direction.
 * A dead zone in the middle maps to `center` (8-way) or nothing (4-way); diagonals cover
 * 45° wedges so they are as easy to hit as the cardinals.
 */
object PadGeometry {
    fun direction(dx: Float, dy: Float, radius: Float, eightWay: Boolean, deadZone: Float = 0.22f): String? {
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist < radius * deadZone) return if (eightWay) "center" else null
        val angle = Math.toDegrees(Math.atan2(-dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
        if (!eightWay) {
            return when {
                angle < 45 || angle >= 315 -> "right"
                angle < 135 -> "up"
                angle < 225 -> "left"
                else -> "down"
            }
        }
        val sector = ((angle + 22.5) / 45).toInt() % 8
        return arrayOf("right", "up_right", "up", "up_left", "left", "down_left", "down", "down_right")[sector]
    }
}
