package dev.tuikio.app.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.IconDrawable
import dev.tuikio.app.ui.Theme
import dev.tuikio.controls.ControlElement
import dev.tuikio.controls.ControlProfile
import dev.tuikio.controls.ElementType
import dev.tuikio.controls.Haptic
import dev.tuikio.controls.ControlLayout
import dev.tuikio.controls.PadGeometry
import dev.tuikio.controls.PlacedControl

/**
 * Draws a game's control profile and turns multitouch into action presses/releases. Pads
 * follow the thumb: sliding from "left" to "up" releases one and presses the other, so
 * diagonal play feels continuous. Buttons repeat only if the profile says so (the
 * dispatcher handles timing). Everything scales with the player's control-size setting and
 * mirrors for left-handed play.
 */
@SuppressLint("ViewConstructor")
class ControlsView(context: Context, private val profile: ControlProfile) : View(context) {
    companion object {
        /** Height of the pause/title/keyboard bar that shares the left column in landscape. */
        const val TOP_BAR_DP = 72f
    }

    var landscape = false
        set(v) {
            field = v
            layoutElements()
            invalidate()
        }
    var scale = 1f
    var leftHanded = false
    var hapticsEnabled = true
    var onPress: (String) -> Unit = {}
    var onRelease: (String) -> Unit = {}
    var onKeyboard: () -> Unit = {}

    private class Placed(val element: ControlElement, val cx: Float, val cy: Float, val radius: Float) {
        /** Action currently held by this element (direction-dependent for pads). */
        var held: String? = null
    }

    private var placed = emptyList<Placed>()
    private val pointers = HashMap<Int, Placed>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = HashMap<String, IconDrawable>()

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Game controls: ${profile.title}"
    }

    private val density get() = resources.displayMetrics.density

    /** Pads that print their "up" action above themselves (e.g. "Rotate") need a little headroom. */
    private val labelled: (ControlElement) -> Boolean = { e ->
        (e.type == ElementType.DPAD4 || e.type == ElementType.DPAD8) &&
            e.bindings["up"]?.let { profile.actions[it]?.label } !in setOf(null, "Up", "North")
    }

    private fun screenDp(): Pair<Float, Float> = resources.displayMetrics.let { it.widthPixels / it.density to it.heightPixels / it.density }

    /** Landscape: the player's scale, shrunk if needed so each side column stays under 30% of the width. */
    private fun landscapeScale(w: Float, h: Float) =
        ControlLayout.fittedLandscapeScale(profile.layout(true), w, h, TOP_BAR_DP, scale, labelled, reserveOnEnd = leftHanded)

    private fun portraitScale(w: Float, screenH: Float) = ControlLayout.fittedPortraitScale(profile.layout(false), w, screenH, scale, labelled)

    /** Width of each landscape side column, so the terminal isn't covered (px). */
    fun sideInsetPx(): Int {
        val (w, h) = screenDp()
        val els = profile.layout(true)
        return (ControlLayout.requiredSide(els, w, h, TOP_BAR_DP, landscapeScale(w, h), labelled, leftHanded) * density).toInt()
    }

    /** Height of the controls area below the terminal in portrait (px). */
    fun portraitHeightPx(): Int {
        val (w, h) = screenDp()
        return (ControlLayout.requiredHeight(profile.layout(false), w, portraitScale(w, h), labelled) * density).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutElements()

    private fun layoutElements() {
        if (width == 0 || height == 0) return
        val d = density
        val w = width / d
        val h = height / d
        val els = profile.layout(landscape)
        var s = if (landscape) landscapeScale(w, h) else portraitScale(w, screenDp().second)
        var result: List<PlacedControl>? = null
        // The area is sized from the same rules, so this normally succeeds first time; shrink
        // a little if rounding or an unusual window size leaves too little room.
        while (result == null && s >= 0.5f) {
            result = if (landscape) {
                val side = ControlLayout.requiredSide(els, w, h, TOP_BAR_DP, s, labelled, leftHanded)
                ControlLayout.place(els, w, h, s, leftHanded, labelled, ControlLayout.sideRegions(w, h, side, TOP_BAR_DP, leftHanded))
            } else {
                ControlLayout.place(els, w, h, s, leftHanded, labelled)
            }
            s -= 0.05f
        }
        placed = result.orEmpty().map { Placed(it.element, it.cx * d, it.cy * d, it.radius * d) }
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        for (pl in placed) {
            val e = pl.element
            val pressed = pl.held != null
            when (e.type) {
                ElementType.DPAD4, ElementType.DPAD8 -> drawPad(canvas, pl, pressed)
                ElementType.BUTTON, ElementType.KEYBOARD -> {
                    val primary = e.style == "primary"
                    val fill = when {
                        pressed -> p.accent
                        primary -> p.accent
                        else -> p.surfaceAlt
                    }
                    paint.style = Paint.Style.FILL
                    paint.color = if (pressed) blend(fill, 0xFF000000.toInt(), 0.15f) else fill
                    val sink = if (pressed) 3 * resources.displayMetrics.density else 0f
                    if (!pressed && Theme.shapes.shadowDp > 0) {
                        paint.color = p.shadow
                        canvas.drawCircle(pl.cx, pl.cy + 3 * resources.displayMetrics.density, pl.radius, paint)
                        paint.color = fill
                    }
                    canvas.drawCircle(pl.cx, pl.cy + sink, pl.radius - 2, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2 * resources.displayMetrics.density
                    paint.color = p.screenEdge
                    canvas.drawCircle(pl.cx, pl.cy + sink, pl.radius - 2, paint)
                    val actionId = e.bindings["press"]
                    val action = actionId?.let { profile.actions[it] }
                    val ink = if (primary || pressed) p.onAccent else p.ink
                    drawLabel(canvas, action?.icon, if (e.type == ElementType.KEYBOARD) "Keys" else action?.label.orEmpty(), pl.cx, pl.cy + sink, pl.radius, ink)
                }
            }
        }
    }

    private fun drawPad(canvas: Canvas, pl: Placed, pressed: Boolean) {
        val p = Theme.palette
        val d = resources.displayMetrics.density
        paint.style = Paint.Style.FILL
        paint.color = p.surfaceAlt
        canvas.drawCircle(pl.cx, pl.cy, pl.radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.color = p.screenEdge
        canvas.drawCircle(pl.cx, pl.cy, pl.radius, paint)
        val eight = pl.element.type == ElementType.DPAD8
        val dirs = if (eight) listOf("up", "up_right", "right", "down_right", "down", "down_left", "left", "up_left") else listOf("up", "right", "down", "left")
        val angles = mapOf("right" to 0.0, "up_right" to 45.0, "up" to 90.0, "up_left" to 135.0, "left" to 180.0, "down_left" to 225.0, "down" to 270.0, "down_right" to 315.0)
        val heldDir = pl.held?.let { h -> pl.element.bindings.entries.firstOrNull { it.value == h }?.key }
        for (dir in dirs) {
            if (dir !in pl.element.bindings) continue
            val a = Math.toRadians(angles.getValue(dir))
            val dist = pl.radius * 0.62f
            val x = pl.cx + (Math.cos(a) * dist).toFloat()
            val y = pl.cy - (Math.sin(a) * dist).toFloat()
            val active = dir == heldDir
            if (active) {
                paint.style = Paint.Style.FILL
                paint.color = p.accent
                canvas.drawCircle(x, y, pl.radius * 0.3f, paint)
            }
            drawArrow(canvas, x, y, pl.radius * (if (eight && dir.contains('_')) 0.13f else 0.18f), angles.getValue(dir), if (active) p.onAccent else p.ink)
        }
        if (eight && "center" in pl.element.bindings) {
            paint.style = Paint.Style.FILL
            paint.color = if (heldDir == "center") p.accent else p.surface
            canvas.drawCircle(pl.cx, pl.cy, pl.radius * 0.2f, paint)
        }
        // The game's own label for each direction, e.g. "Rotate" on up.
        val upAction = pl.element.bindings["up"]?.let { profile.actions[it] }
        if (upAction != null && upAction.label !in setOf("Up", "North")) {
            labelPaint.textSize = 11 * d
            labelPaint.color = p.screenInk
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.typeface = Theme.body(context, 800)
            canvas.drawText(upAction.label, pl.cx, pl.cy - pl.radius - 6 * d, labelPaint)
        }
    }

    private val arrow = Path()

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, s: Float, angleDeg: Double, color: Int) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate((-angleDeg).toFloat())
        arrow.reset()
        arrow.moveTo(s, 0f)
        arrow.lineTo(-s * 0.7f, -s)
        arrow.lineTo(-s * 0.7f, s)
        arrow.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(arrow, paint)
        canvas.restore()
    }

    private fun drawLabel(canvas: Canvas, iconName: String?, label: String, cx: Float, cy: Float, r: Float, color: Int) {
        val d = resources.displayMetrics.density
        val icon = iconName?.let { name -> Icon.values().firstOrNull { it.name.equals(name, ignoreCase = true) } }
        labelPaint.color = color
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.typeface = Theme.body(context, 800)
        labelPaint.textSize = (if (r > 40 * d) 14 else 12) * d
        if (icon != null && r > 30 * d) {
            val dr = icons.getOrPut(icon.name) { IconDrawable(icon, color, 2.6f) }
            dr.color = color
            val s = (r * 0.62f).toInt()
            dr.setBounds((cx - s / 2).toInt(), (cy - s * 0.85f).toInt(), (cx + s / 2).toInt(), (cy + s * 0.15f).toInt())
            dr.draw(canvas)
            canvas.drawText(label, cx, cy + r * 0.52f, labelPaint)
        } else {
            canvas.drawText(label, cx, cy + labelPaint.textSize / 3, labelPaint)
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - f)) + (((b shr s) and 0xFF) * f)).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val hit = hitTest(event.getX(i), event.getY(i)) ?: return pointers.isNotEmpty()
                pointers[event.getPointerId(i)] = hit
                update(hit, event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pl = pointers[event.getPointerId(i)] ?: continue
                    if (pl.element.type == ElementType.DPAD4 || pl.element.type == ElementType.DPAD8) update(pl, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pl = pointers.remove(event.getPointerId(event.actionIndex))
                if (pl != null) releaseHeld(pl)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    fun releaseAll() {
        pointers.values.forEach { releaseHeld(it) }
        pointers.clear()
    }

    private fun hitTest(x: Float, y: Float): Placed? = placed.minByOrNull { Math.hypot((x - it.cx).toDouble(), (y - it.cy).toDouble()) - it.radius }
        ?.takeIf { Math.hypot((x - it.cx).toDouble(), (y - it.cy).toDouble()) <= it.radius * 1.15 }

    private fun update(pl: Placed, x: Float, y: Float) {
        val e = pl.element
        val next: String? = when (e.type) {
            ElementType.DPAD4, ElementType.DPAD8 -> {
                val dir = PadGeometry.direction(x - pl.cx, y - pl.cy, pl.radius, eightWay = e.type == ElementType.DPAD8)
                dir?.let { e.bindings[it] }
            }
            ElementType.BUTTON -> e.bindings["press"]
            ElementType.KEYBOARD -> {
                if (pl.held == null) {
                    pl.held = "__keyboard"
                    onKeyboard()
                    invalidate()
                }
                return
            }
        }
        if (next == pl.held) return
        releaseHeld(pl)
        if (next != null) {
            pl.held = next
            haptic(profile.actions[next]?.haptic ?: Haptic.TICK)
            onPress(next)
        }
        invalidate()
    }

    private fun releaseHeld(pl: Placed) {
        val h = pl.held ?: return
        pl.held = null
        if (h != "__keyboard") onRelease(h)
        invalidate()
    }

    private fun haptic(h: Haptic) {
        if (!hapticsEnabled) return
        val c = when (h) {
            Haptic.NONE -> return
            Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.CLICK -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.HEAVY -> HapticFeedbackConstants.LONG_PRESS
        }
        performHapticFeedback(c)
    }

    /** Gamepad/keyboard bridge: press the action a pad direction is bound to. */
    fun padAction(direction: String): String? =
        placed.firstOrNull { it.element.type == ElementType.DPAD4 || it.element.type == ElementType.DPAD8 }?.element?.bindings?.get(direction)

    /** Primary and secondary button actions, for gamepad A/B. */
    fun buttonActions(): List<String> = profile.layout(landscape)
        .filter { it.type == ElementType.BUTTON }
        .sortedByDescending { it.style == "primary" }
        .mapNotNull { it.bindings["press"] }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = profile.actions.values.joinToString(", ") { it.label }
    }

    @Suppress("unused")
    private val debugRect = RectF()
}
