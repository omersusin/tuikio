package dev.tuikio.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** System bar insets, published by MainActivity so screens can pad edge-to-edge content. */
object Insets {
    var top = 0
    var bottom = 0
    private val listeners = LinkedHashSet<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun notifyChanged() = listeners.toList().forEach { it() }
}

/** Announces the view as a heading to TalkBack (API 28+; a no-op before). */
fun View.markHeading() {
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = context.dp(v)

/**
 * The signature "sticker" surface: fill, outline and a hard offset shadow, with the brand's
 * corner radius. When pressed, the shadow collapses as the view sinks onto it.
 */
class StickerDrawable(
    private val context: Context,
    var fill: Int,
    var outline: Int = Theme.palette.outline,
    var outlineDp: Float = Theme.shapes.outlineDp,
    var shadowDp: Float = Theme.shapes.shadowDp,
    var cornerDp: Float = Theme.shapes.cornerDp,
    var shadowColor: Int = Theme.palette.shadow,
    var dashed: Boolean = false,
    var softShadow: Boolean = Theme.shapes.softShadow,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 0 = resting, 1 = fully pressed (sunk onto its shadow). */
    var pressAmount = 0f
        set(v) {
            field = v
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val shadow = context.dp(shadowDp)
        val stroke = context.dp(outlineDp)
        val maxR = (b.height() - shadow) / 2f
        val r = if (cornerDp < 0) maxR else Math.min(context.dp(cornerDp), maxR)
        val sink = shadow * pressAmount
        if (shadow > 0 && Color.alpha(shadowColor) > 0) {
            paint.style = Paint.Style.FILL
            paint.color = shadowColor
            rect.set(b.left.toFloat(), b.top + shadow, b.right.toFloat(), b.bottom.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        } else if (softShadow) {
            paint.style = Paint.Style.FILL
            paint.color = shadowColor
            paint.setShadowLayer(context.dp(10f), 0f, context.dp(4f), shadowColor)
            rect.set(b.left + stroke, b.top + stroke, b.right - stroke, b.bottom - stroke)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.clearShadowLayer()
        }
        rect.set(b.left + stroke / 2, b.top + stroke / 2 + sink, b.right - stroke / 2, b.bottom - shadow + sink - stroke / 2)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(rect, r, r, paint)
        if (stroke > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = outline
            paint.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(context.dp(6f), context.dp(5f)), 0f) else null
            canvas.drawRoundRect(rect, r, r, paint)
            paint.pathEffect = null
        }
    }

    /** Space the shadow occupies below the face, so content can be centered on the face. */
    val shadowPx: Int get() = context.dp(shadowDp.toInt())

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object Color {
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}

enum class ButtonStyle { PRIMARY, SECONDARY, MINT, GHOST, DANGER }

/**
 * Tuikio's button. A real Button for accessibility (focusable, clickable, announces its
 * role), drawn with a [StickerDrawable] and a sink-on-press motion plus a light haptic tick.
 */
@SuppressLint("AppCompatCustomView")
open class TButton(context: Context, label: String, val style: ButtonStyle = ButtonStyle.PRIMARY, icon: Icon? = null) :
    TextView(context) {
    private val sticker: StickerDrawable

    init {
        val p = Theme.palette
        val (fill, ink) = when (style) {
            ButtonStyle.PRIMARY -> p.accent to p.onAccent
            ButtonStyle.MINT -> p.accent2 to p.onAccent2
            ButtonStyle.SECONDARY -> p.surface to p.ink
            ButtonStyle.GHOST -> 0 to p.ink
            ButtonStyle.DANGER -> p.surface to p.danger
        }
        sticker = StickerDrawable(
            context, fill = fill,
            outline = if (style == ButtonStyle.PRIMARY && Theme.shapes.outlineDp == 0f) fill else p.outline,
            shadowDp = if (style == ButtonStyle.PRIMARY) Theme.shapes.shadowDp else 0f,
            cornerDp = Theme.shapes.buttonCornerDp,
        )
        background = sticker
        text = if (Theme.activePack.label.caps && Theme.activePack.motion == MotionStyle.STEPPED) label.uppercase() else label
        setTextColor(ink)
        typeface = Theme.body(context, 800)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minHeight = context.dp(if (style == ButtonStyle.PRIMARY) 52 else 48)
        minimumHeight = minHeight
        val shadow = sticker.shadowPx
        setPadding(context.dp(20), 0, context.dp(20), shadow)
        isClickable = true
        isFocusable = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (icon != null) {
            val d = IconDrawable(icon, ink, if (icon.filled) 0f else 2.8f)
            val s = context.dp(18)
            d.setBounds(0, 0, s, s)
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = context.dp(8)
        }
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sticker.pressAmount = 1f
                if (Theme.shapes.shadowDp == 0f) Motion.press(this, true, dpf(1.5f))
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                sticker.pressAmount = 0f
                if (Theme.shapes.shadowDp == 0f) Motion.press(this, false, 0f)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }
}

enum class ChipKind { PLAYS, PORTING, DISCOVER, ORIGINAL, ONLINE, NEUTRAL }

/** Status chip: shape, icon and words together, never color alone. */
@SuppressLint("ViewConstructor")
class StatusChip(context: Context, text: String, kind: ChipKind, small: Boolean = false) : TextView(context) {
    init {
        val p = Theme.palette
        val (fill, icon) = when (kind) {
            ChipKind.PLAYS -> p.accent2 to Icon.CHECK
            ChipKind.PORTING -> p.highlight to Icon.PENCIL
            ChipKind.DISCOVER -> p.surface to Icon.SEARCH
            ChipKind.ORIGINAL -> p.accent to Icon.SPARK
            ChipKind.ONLINE -> p.surfaceAlt to Icon.LINK
            ChipKind.NEUTRAL -> p.surfaceAlt to null
        }
        val ink = when (kind) {
            ChipKind.PLAYS -> p.onAccent2
            ChipKind.PORTING -> p.onHighlight
            ChipKind.ORIGINAL -> p.onAccent
            else -> p.ink
        }
        background = StickerDrawable(
            context, fill = fill, outline = p.outline, outlineDp = if (Theme.shapes.outlineDp > 0) 2f else 0f, shadowDp = 0f,
            cornerDp = -1f, dashed = kind == ChipKind.DISCOVER,
        )
        this.text = text
        setTextColor(ink)
        typeface = Theme.body(context, 800)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 11f else 13f)
        gravity = Gravity.CENTER_VERTICAL
        val h = context.dp(if (small) 24 else 30)
        minHeight = h
        setPadding(context.dp(if (small) 8 else 10), 0, context.dp(if (small) 10 else 12), 0)
        if (icon != null) {
            val d = IconDrawable(icon, ink, if (icon.filled) 0f else 3f)
            val s = context.dp(if (small) 11 else 13)
            d.setBounds(0, 0, s, s)
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = context.dp(5)
        }
        maxLines = 1
    }
}

/** Text helpers bound to the current theme. */
object T {
    fun title(ctx: Context, text: CharSequence, sizeSp: Float = 26f, color: Int = Theme.palette.ink) = TextView(ctx).apply {
        this.text = if (Theme.activePack.display.caps) text.toString().uppercase() else text
        setTextColor(color)
        typeface = Theme.display(ctx)
        letterSpacing = Theme.activePack.display.letterSpacing
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(0f, 1.05f)
        isFocusable = false
        markHeading()
    }

    fun body(ctx: Context, text: CharSequence, sizeSp: Float = 16f, color: Int = Theme.palette.ink, weight: Int = 500) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        typeface = Theme.body(ctx, weight)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(0f, 1.3f)
    }

    fun soft(ctx: Context, text: CharSequence, sizeSp: Float = 14f) = body(ctx, text, sizeSp, Theme.palette.inkSoft, 600)

    fun label(ctx: Context, text: CharSequence, color: Int = Theme.palette.inkSoft) = TextView(ctx).apply {
        val caps = Theme.activePack.label.caps || Theme.activePack == Brands.TUIKIO
        this.text = if (caps) text.toString().uppercase() else text
        setTextColor(color)
        typeface = Theme.label(ctx)
        letterSpacing = Math.max(Theme.activePack.label.letterSpacing, 0.04f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        markHeading()
    }

    fun mono(ctx: Context, text: CharSequence, sizeSp: Float = 12f, color: Int = Theme.palette.screenInk) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        typeface = Theme.mono(ctx, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(0f, 1.0f)
        includeFontPadding = false
        setHorizontallyScrolling(true)
    }
}

/** Layout shorthands for screens built in code. */
fun Context.vstack(gapDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = Gap(dp(gapDp))
    }
    block()
}

fun Context.hstack(gapDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = Gap(dp(gapDp))
    }
    block()
}

/** An invisible divider that just spaces children (gap). */
class Gap(private val size: Int) : Drawable() {
    override fun draw(canvas: Canvas) = Unit
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSPARENT
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** A sticker-backed container. */
fun Context.card(fill: Int = Theme.palette.surface, padDp: Int = 16, shadow: Boolean = true, dashed: Boolean = false, gapDp: Int = 10, block: LinearLayout.() -> Unit): LinearLayout =
    vstack(gapDp) {
        val d = StickerDrawable(
            this@card, fill = fill, shadowDp = if (shadow) Theme.shapes.shadowDp else 0f, dashed = dashed,
            outline = if (dashed) Theme.palette.inkSoft else Theme.palette.outline,
            outlineDp = if (Theme.shapes.outlineDp == 0f && !dashed) 0f else Math.max(Theme.shapes.outlineDp, if (dashed) 2f else 0f),
        )
        background = d
        val pad = dp(padDp)
        setPadding(pad, pad, pad, pad + d.shadowPx)
        block()
    }

/** Single-line text input with a visible label (never placeholder-only). */
class TField(context: Context, labelText: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT) : LinearLayout(context) {
    val input: EditText

    init {
        orientation = VERTICAL
        val label = T.body(context, labelText, 15f, Theme.palette.ink, 800)
        addView(label)
        input = EditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(Theme.palette.ink)
            setHintTextColor(Theme.palette.inkSoft)
            typeface = Theme.body(context, 600)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = StickerDrawable(context, fill = Theme.palette.surface, shadowDp = 0f, cornerDp = Math.min(Theme.shapes.cornerDp, 16f),
                outlineDp = Math.max(Theme.shapes.outlineDp, 1.5f))
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
            minHeight = context.dp(52)
            isSingleLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
        }
        label.labelFor = View.generateViewId().also { input.id = it }
        addView(input, lp().apply { topMargin = context.dp(6) })
    }

    val text: String get() = input.text.toString()
}

/** A pebble-shaped on/off switch with a real switch role for accessibility. */
class PebbleSwitch(context: Context) : View(context) {
    var checked = false
        set(v) {
            field = v
            knob = if (v) 1f else 0f
            invalidate()
        }
    var onChange: (Boolean) -> Unit = {}
    private var knob = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            checked = !checked
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange(checked)
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(56), dp(48))

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        val h = dpf(30f)
        val top = (height - h) / 2
        val rect = RectF(dpf(2f), top, width - dpf(2f), top + h)
        paint.style = Paint.Style.FILL
        paint.color = if (checked) p.accent2 else p.surfaceAlt
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpf(2f)
        paint.color = p.outline
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val r = h / 2 - dpf(5f)
        val cx = rect.left + h / 2 + (rect.width() - h) * knob
        paint.style = Paint.Style.FILL
        paint.color = if (checked) p.onAccent2 else p.inkSoft
        canvas.drawCircle(cx, rect.centerY(), r, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = checked
    }
}

/** Striped progress bar (determinate), announced as a progress bar. */
class StripeProgress(context: Context) : View(context) {
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            contentDescription = "${(field * 100).toInt()} percent"
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(16))

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        val r = height / 2f
        val rect = RectF(dpf(1f), dpf(1f), width - dpf(1f), height - dpf(1f))
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = p.surfaceAlt
        canvas.drawRoundRect(rect, r, r, paint)
        val fill = RectF(rect.left, rect.top, rect.left + rect.width() * progress, rect.bottom)
        paint.shader = LinearGradient(0f, 0f, dpf(10f), dpf(10f), p.accent2, Color.withAlpha(p.accent2, 0xAA), Shader.TileMode.MIRROR)
        canvas.drawRoundRect(fill, r, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpf(2f)
        paint.color = p.outline
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.ProgressBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, progress * 100)
    }
}

/** Round icon-only button with a required content description. */
fun Context.iconButton(icon: Icon, description: String, fill: Int = Theme.palette.surface, ink: Int = Theme.palette.ink, onClick: () -> Unit): View =
    object : View(this) {
        private val d = StickerDrawable(this@iconButton, fill = fill, shadowDp = 0f, cornerDp = -1f, outlineDp = Math.max(Theme.shapes.outlineDp, 1.5f))
        private val ic = IconDrawable(icon, ink, 2.6f)

        init {
            background = d
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(48), dp(48))
        override fun onDraw(canvas: Canvas) {
            val s = dp(20)
            ic.setBounds((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
            ic.draw(canvas)
        }

        override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
    }

/** A short-lived message bubble at the bottom, with Kio, instead of a toast or snackbar. */
object Snack {
    fun show(root: FrameLayout, message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val ctx = root.context
        val p = Theme.palette
        val bubble = ctx.hstack(10) {
            background = StickerDrawable(ctx, fill = p.ink, outline = p.ink, shadowDp = 0f, cornerDp = 22f)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(dev.tuikio.app.R.drawable.kio)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))
            addView(T.body(ctx, message, 15f, p.ground, 700), lp(0, WRAP, 1f))
            if (actionLabel != null && action != null) {
                addView(TextView(ctx).apply {
                    text = actionLabel
                    setTextColor(p.accent)
                    typeface = Theme.body(ctx, 800)
                    setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                    setOnClickListener { action() }
                })
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val params = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(ctx.dp(16), 0, ctx.dp(16), ctx.dp(100))
        }
        root.addView(bubble, params)
        bubble.alpha = 0f
        bubble.translationY = ctx.dp(16f)
        bubble.animate().alpha(1f).translationY(0f).setDuration(Motion.duration(Motion.BASE)).setInterpolator(Motion.easing()).start()
        bubble.postDelayed({
            bubble.animate().alpha(0f).setDuration(Motion.duration(Motion.FAST)).withEndAction { root.removeView(bubble) }.start()
        }, if (action != null) 5000 else 3200)
    }
}

/** A modal sheet sliding up from the bottom over a scrim. Back or a tap on the scrim closes it. */
class Sheet(private val root: FrameLayout, content: View) {
    private val scrim = FrameLayout(root.context)
    private val panel: LinearLayout
    var onDismiss: () -> Unit = {}

    init {
        val ctx = root.context
        val p = Theme.palette
        scrim.setBackgroundColor(Color.withAlpha(0xFF0A0817.toInt(), 0x99))
        scrim.isClickable = true
        scrim.setOnClickListener { dismiss() }
        panel = ctx.vstack(12) {
            background = object : Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun draw(canvas: Canvas) {
                    val r = ctx.dp(Math.max(Theme.shapes.cornerDp, 8f) + 6f)
                    val b = bounds
                    val path = Path().apply {
                        addRoundRect(RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom + r), floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                    }
                    paint.color = p.ground
                    paint.style = Paint.Style.FILL
                    canvas.drawPath(path, paint)
                    if (Theme.shapes.outlineDp > 0) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = ctx.dp(2f)
                        paint.color = p.outline
                        canvas.drawPath(path, paint)
                    }
                }

                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: ColorFilter?) = Unit

                @Deprecated("Deprecated in Java")
                override fun getOpacity() = PixelFormat.TRANSLUCENT
            }
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(28))
            isClickable = true
            addView(content)
        }
        scrim.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        scrim.setTag(dev.tuikio.app.R.id.tag_sheet, this)
        panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, panel.paddingBottom + Insets.bottom)
    }

    val isShowing: Boolean get() = scrim.parent != null

    fun show(): Sheet {
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        val d = Motion.duration(Motion.BASE)
        if (d > 0) {
            panel.translationY = root.height.toFloat()
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(d).start()
            panel.animate().translationY(0f).setDuration(d + 60).setInterpolator(Motion.easing()).start()
        }
        panel.requestFocus()
        return this
    }

    fun dismiss() {
        if (!isShowing) return
        val d = Motion.duration(Motion.FAST)
        if (d == 0L) {
            root.removeView(scrim)
        } else {
            panel.animate().translationY(panel.height.toFloat()).setDuration(d).start()
            scrim.animate().alpha(0f).setDuration(d).withEndAction { root.removeView(scrim) }.start()
        }
        onDismiss()
    }
}
