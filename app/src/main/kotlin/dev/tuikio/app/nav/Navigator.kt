package dev.tuikio.app.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tuikio.app.TuikioApp
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.IconDrawable
import dev.tuikio.app.ui.Motion
import dev.tuikio.app.ui.Theme
import dev.tuikio.app.ui.dp
import dev.tuikio.app.ui.dpf

enum class Tab(val label: String, val icon: Icon) {
    DISCOVER("Discover", Icon.COMPASS),
    LIBRARY("Library", Icon.LIBRARY),
    CREATE("Create", Icon.PLUS),
    YOU("You", Icon.PERSON),
}

/** A full-screen page. Screens build their views in code from the current theme. */
abstract class Screen {
    lateinit var nav: Navigator
    open val tab: Tab? = null
    open val showsDock: Boolean get() = tab != null
    val app: TuikioApp get() = TuikioApp.instance

    abstract fun build(ctx: Context): View

    /** Called after the screen became visible (also after returning to it). */
    open fun onShown() {}
    open fun onHidden() {}

    /** Return true to consume the back gesture (e.g. to close an inline step). */
    open fun onBack(): Boolean = false
}

/**
 * A minimal stack navigator. Each tab has a root; detail screens push on top. Transitions
 * follow the brand's motion style and collapse to instant changes when motion is reduced.
 */
class Navigator(
    private val host: FrameLayout,
    val overlay: FrameLayout,
    private val dock: Dock,
) {
    private class Entry(val screen: Screen, var view: View?)

    private val stack = ArrayList<Entry>()
    val current: Screen? get() = stack.lastOrNull()?.screen
    val depth: Int get() = stack.size

    fun setRoot(screen: Screen) {
        val old = stack.toList()
        stack.clear()
        old.forEach { it.screen.onHidden() }
        show(screen, Direction.FADE, removeOld = old.mapNotNull { it.view })
    }

    fun push(screen: Screen) {
        stack.lastOrNull()?.screen?.onHidden()
        show(screen, Direction.FORWARD, removeOld = listOfNotNull(stack.lastOrNull()?.view))
    }

    /** Returns false when there is nothing to go back to. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        val top = stack.removeAt(stack.size - 1)
        top.screen.onHidden()
        val below = stack.last()
        val view = below.view ?: below.screen.build(host.context).also { below.view = it }
        host.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        animateOut(top.view, Direction.BACK)
        animateIn(view, Direction.BACK)
        syncDock(below.screen)
        below.screen.onShown()
        return true
    }

    fun handleBack(): Boolean {
        if (current?.onBack() == true) return true
        return pop()
    }

    /** Rebuilds the visible screen, e.g. after the theme or brand changed. */
    fun rebuild() {
        stack.forEach { e ->
            if (e !== stack.last()) e.view = null
        }
        val top = stack.lastOrNull() ?: return
        top.view?.let { host.removeView(it) }
        val v = top.screen.build(host.context)
        top.view = v
        host.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dock.refreshTheme()
        syncDock(top.screen)
        top.screen.onShown()
    }

    private enum class Direction { FORWARD, BACK, FADE }

    private fun show(screen: Screen, dir: Direction, removeOld: List<View>) {
        screen.nav = this
        val view = screen.build(host.context)
        stack += Entry(screen, view)
        host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        removeOld.forEach { animateOut(it, dir) }
        animateIn(view, dir)
        syncDock(screen)
        screen.onShown()
        view.announceForAccessibility(screen.javaClass.simpleName.removeSuffix("Screen"))
    }

    private fun syncDock(screen: Screen) {
        dock.visibility = if (screen.showsDock) View.VISIBLE else View.GONE
        screen.tab?.let { dock.select(it, animate = true) }
    }

    private fun animateIn(view: View, dir: Direction) {
        val d = Motion.duration(Motion.BASE)
        if (d == 0L) return
        val shift = view.dpf(28f)
        view.alpha = 0f
        view.translationX = when (dir) {
            Direction.FORWARD -> shift
            Direction.BACK -> -shift
            Direction.FADE -> 0f
        }
        view.animate().alpha(1f).translationX(0f).setDuration(d).setInterpolator(Motion.easing()).start()
    }

    private fun animateOut(view: View?, dir: Direction) {
        view ?: return
        val d = Motion.duration(Motion.FAST)
        if (d == 0L) {
            host.removeView(view)
            return
        }
        val shift = view.dpf(28f)
        view.animate().alpha(0f).translationX(
            when (dir) {
                Direction.FORWARD -> -shift / 2
                Direction.BACK -> shift
                Direction.FADE -> 0f
            },
        ).setDuration(d).withEndAction { host.removeView(view) }.start()
    }
}

/**
 * The floating "shell dock": an ink pill with four tabs. The active tab sits on an accent
 * pebble that glides between tabs. Each tab is a separate, focusable element for TalkBack
 * and keyboard users.
 */
class Dock(context: Context, private val onSelect: (Tab) -> Unit) : LinearLayout(context) {
    private val tabs = HashMap<Tab, TabView>()
    private var selected = Tab.DISCOVER
    private var pebbleX = 0f
    private var pebbleW = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        for (t in Tab.values()) {
            val v = TabView(context, t)
            tabs[t] = v
            addView(v, LayoutParams(0, dp(52), 1f))
        }
        elevation = dpf(8f)
    }

    fun refreshTheme() {
        tabs.values.forEach { it.refresh(it.tab == selected) }
        invalidate()
    }

    fun select(tab: Tab, animate: Boolean) {
        selected = tab
        tabs.values.forEach { it.refresh(it.tab == tab) }
        val target = tabs[tab] ?: return
        if (target.width == 0) {
            post { select(tab, false) }
            return
        }
        val toX = target.left.toFloat()
        val toW = target.width.toFloat()
        val d = Motion.duration(Motion.BASE)
        if (!animate || d == 0L || pebbleW == 0f) {
            pebbleX = toX
            pebbleW = toW
            invalidate()
            return
        }
        val fromX = pebbleX
        val fromW = pebbleW
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d
            interpolator = Motion.easing()
            addUpdateListener {
                val f = it.animatedValue as Float
                pebbleX = fromX + (toX - fromX) * f
                pebbleW = fromW + (toW - fromW) * f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        val r = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = p.dock
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        if (Theme.shapes.outlineDp > 0 && p.isDark) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpf(1.5f)
            paint.color = p.hairline
            canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), r, r, paint)
        }
        if (pebbleW > 0) {
            paint.style = Paint.Style.FILL
            paint.color = p.accent
            val top = paddingTop.toFloat()
            val bottom = height - paddingBottom.toFloat()
            val pr = (bottom - top) / 2
            canvas.drawRoundRect(RectF(pebbleX, top, pebbleX + pebbleW, bottom), pr, pr, paint)
        }
    }

    private inner class TabView(context: Context, val tab: Tab) : LinearLayout(context) {
        private val icon = IconDrawable(tab.icon, Theme.palette.dockMuted, 2.4f)
        private val iconView = View(context)
        private val label = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = tab.label
            iconView.background = icon
            addView(iconView, LayoutParams(dp(22), dp(22)))
            label.text = tab.label
            label.textSize = 11f
            label.gravity = Gravity.CENTER
            addView(label)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelect(tab)
            }
            refresh(false)
        }

        fun refresh(active: Boolean) {
            val p = Theme.palette
            val c = if (active) p.onAccent else p.dockMuted
            icon.color = c
            label.setTextColor(c)
            label.typeface = Theme.body(context, if (active) 800 else 600)
            isSelected = active
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.Button::class.java.name
            info.isSelected = isSelected
        }
    }
}
