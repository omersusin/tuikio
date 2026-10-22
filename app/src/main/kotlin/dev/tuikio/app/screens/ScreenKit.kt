package dev.tuikio.app.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tuikio.app.TuikioApp
import dev.tuikio.app.data.CatalogRepo
import dev.tuikio.catalog.GameManifest
import dev.tuikio.catalog.Origin
import dev.tuikio.catalog.VerificationStatus
import dev.tuikio.app.ui.ChipKind
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.Insets
import dev.tuikio.app.ui.MATCH
import dev.tuikio.app.ui.StatusChip
import dev.tuikio.app.ui.StickerDrawable
import dev.tuikio.app.ui.T
import dev.tuikio.app.ui.Theme
import dev.tuikio.app.ui.WRAP
import dev.tuikio.app.ui.dp
import dev.tuikio.app.ui.dpf
import dev.tuikio.app.ui.hstack
import dev.tuikio.app.ui.iconButton
import dev.tuikio.app.ui.lp
import dev.tuikio.app.ui.vstack

/** A vertically scrolling page that respects system bars and leaves room for the dock. */
fun Context.page(withDock: Boolean = true, gapDp: Int = 18, block: LinearLayout.() -> Unit): View {
    val content = vstack(gapDp) {
        setPadding(dp(18), dp(12) + Insets.top, dp(18), dp(if (withDock) 112 else 28) + Insets.bottom)
        block()
    }
    return ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(Theme.palette.ground)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }
}

/** Back button + title row for pushed screens. */
fun LinearLayout.topBar(nav: dev.tuikio.app.nav.Navigator, title: String, vararg actions: View) {
    val ctx = context
    addView(ctx.hstack(12) {
        addView(ctx.iconButton(Icon.BACK, "Back") { nav.pop() })
        addView(T.title(ctx, title, 22f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
        actions.forEach { addView(it) }
    })
}

fun LinearLayout.sectionHeader(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    val ctx = context
    addView(ctx.hstack {
        addView(T.title(ctx, title, 18f), lp(0, WRAP, 1f))
        if (trailing != null) {
            addView(TextView(ctx).apply {
                text = trailing
                setTextColor(Theme.palette.link)
                typeface = Theme.body(ctx, 800)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(ctx.dp(8), ctx.dp(12), 0, ctx.dp(12))
                if (onTrailing != null) {
                    isClickable = true
                    setOnClickListener { onTrailing() }
                }
            })
        }
    })
}

/** Honest status for a game in this build. */
fun statusChip(ctx: Context, game: GameManifest, small: Boolean = false): StatusChip {
    val catalog = TuikioApp.instance.catalog
    return when {
        !catalog.isBundled(game) -> StatusChip(ctx, "Discover only", ChipKind.DISCOVER, small)
        game.status >= VerificationStatus.ANDROID_TESTED -> StatusChip(ctx, "Plays here", ChipKind.PLAYS, small)
        game.origin == Origin.TUIKIO_ORIGINAL -> StatusChip(ctx, "Tuikio original", ChipKind.ORIGINAL, small)
        else -> StatusChip(ctx, "Preview port", ChipKind.PORTING, small)
    }
}

/** A tiny dark "screen" showing a game's ASCII vignette in its theme ink. */
@SuppressLint("ViewConstructor")
class MiniScreen(context: Context, private val art: String, private val ink: Int, private val radiusDp: Float = 16f) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lines = art.lines()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        paint.style = Paint.Style.FILL
        paint.color = p.screen
        val r = dpf(Math.min(radiusDp, Theme.shapes.cornerDp.coerceAtLeast(2f)))
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        val cols = lines.maxOfOrNull { it.length } ?: 1
        val pad = dpf(10f)
        val cell = Math.min((width - 2 * pad) / cols / 0.6f, (height - 2 * pad) / lines.size / 1.18f).coerceAtMost(dpf(15f))
        paint.textSize = cell
        paint.typeface = Theme.mono(context, 700)
        paint.color = ink
        val blockW = cols * cell * 0.6f
        val blockH = lines.size * cell * 1.18f
        val x0 = (width - blockW) / 2
        var y = (height - blockH) / 2 + cell
        for (l in lines) {
            canvas.drawText(l, x0, y, paint)
            y += cell * 1.18f
        }
    }
}

/** Ink color for a game's vignette, keyed by category so shelves read as a set. */
fun artInk(game: GameManifest): Int {
    val a = Theme.palette.ansi
    return 0xFF000000.toInt() or when (game.category) {
        "arcade" -> a[9]
        "puzzle" -> a[10]
        "adventure" -> a[11]
        "roguelike" -> a[13]
        else -> a[14]
    }
}

/** The game "cartridge" used on shelves: mini screen, title and honest status. */
@SuppressLint("ViewConstructor")
class Cartridge(context: Context, val game: GameManifest, onOpen: () -> Unit) : LinearLayout(context) {
    private val sticker = StickerDrawable(context, fill = Theme.palette.surface)

    init {
        orientation = VERTICAL
        background = sticker
        setPadding(dp(8), dp(8), dp(8), dp(10) + sticker.shadowPx)
        isClickable = true
        isFocusable = true
        contentDescription = "${game.title}. ${statusChip(context, game).text}. Opens details."
        val screen = MiniScreen(context, TuikioApp.instance.catalog.art(game), artInk(game))
        addView(screen, LayoutParams(MATCH, dp(92)))
        addView(T.body(context, game.title, 15f, Theme.palette.ink, 800).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(10), dp(4), dp(6))
        })
        addView(statusChip(context, game, small = true), LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onOpen()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> sticker.pressAmount = 1f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sticker.pressAmount = 0f
        }
        return super.onTouchEvent(event)
    }
}

/** A horizontally scrolling shelf of cartridges. */
fun Context.shelf(games: List<GameManifest>, onOpen: (GameManifest) -> Unit): View {
    val row = hstack(14) {
        setPadding(0, dp(2), dp(18), dp(6))
        games.forEachIndexed { i, g ->
            val c = Cartridge(this@shelf, g) { onOpen(g) }
            addView(c, LinearLayout.LayoutParams(dp(152), WRAP))
            dev.tuikio.app.ui.Motion.enter(c, i)
        }
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(row)
    }
}

fun gameSubtitle(game: GameManifest): String {
    val cat = game.category.replaceFirstChar { it.uppercase() }
    val net = if (game.network == dev.tuikio.catalog.NetworkUse.NONE) "offline" else "online"
    return "$cat · ${game.players} player${if (game.players == "1") "" else "s"} · $net"
}

fun originText(game: GameManifest) = CatalogRepo.originLabel(game.origin)

fun Context.emptyState(title: String, body: String, actionLabel: String?, action: (() -> Unit)?): View = vstack(14) {
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(dp(12), dp(28), dp(12), dp(28))
    addView(android.widget.ImageView(context).apply {
        setImageResource(dev.tuikio.app.R.drawable.kio)
        contentDescription = "Kio the hermit crab"
    }, LinearLayout.LayoutParams(dp(150), dp(150)))
    addView(T.title(context, title, 22f).apply { gravity = Gravity.CENTER })
    addView(T.soft(context, body, 15f).apply { gravity = Gravity.CENTER })
    if (actionLabel != null && action != null) {
        addView(dev.tuikio.app.ui.TButton(context, actionLabel).apply { setOnClickListener { action() } }, lp(MATCH, WRAP))
    }
}
