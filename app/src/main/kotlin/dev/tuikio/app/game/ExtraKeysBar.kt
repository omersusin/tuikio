package dev.tuikio.app.game

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.tuikio.app.ui.StickerDrawable
import dev.tuikio.app.ui.Theme
import dev.tuikio.app.ui.dp
import dev.tuikio.terminal.KeyMods
import dev.tuikio.terminal.TermKey

/**
 * The keys a phone keyboard doesn't have, in a row above it while you type: Esc, Tab, Ctrl and
 * Alt (each applies to the next key, then lets go), arrows, Home/End, Page Up/Down and F1-F12.
 * Games ask for these (Esc to back out, F1 for help, F12 to quit), and without them the game
 * would be stuck.
 */
class ExtraKeysBar(context: Context, private val onKey: (TermKey, Int) -> Unit, private val onDone: () -> Unit) :
    HorizontalScrollView(context) {

    private var mods = KeyMods.NONE
    private val modViews = HashMap<Int, TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Theme.palette.surfaceAlt)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun add(label: String, description: String, onTap: (TextView) -> Unit) {
            row.addView(key(label, description).apply { setOnClickListener { onTap(this) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        add("Done", "Hide the keyboard") { onDone() }
        add("Esc", "Escape") { send(TermKey.ESCAPE) }
        add("Tab", "Tab") { send(TermKey.TAB) }
        for ((m, name) in listOf(KeyMods.CTRL to "Ctrl", KeyMods.ALT to "Alt")) {
            add(name, "$name, for the next key") { v ->
                mods = mods xor m
                highlight(v, mods and m != 0)
            }
            modViews[m] = row.getChildAt(row.childCount - 1) as TextView
        }
        for ((label, key) in listOf("←" to TermKey.LEFT, "↓" to TermKey.DOWN, "↑" to TermKey.UP, "→" to TermKey.RIGHT,
            "Home" to TermKey.HOME, "End" to TermKey.END, "PgUp" to TermKey.PAGE_UP, "PgDn" to TermKey.PAGE_DOWN)) {
            add(label, key.name.lowercase().replace('_', ' ')) { send(key) }
        }
        for (n in 1..12) add("F$n", "F$n") { send(TermKey.values()[TermKey.F1.ordinal + n - 1]) }
        addView(row)
    }

    /** The modifiers armed for the next key, released as they are used (typed text uses them too). */
    fun takeMods(): Int {
        val m = mods
        if (m != KeyMods.NONE) {
            mods = KeyMods.NONE
            modViews.forEach { (_, v) -> highlight(v, false) }
        }
        return m
    }

    private fun send(key: TermKey) = onKey(key, takeMods())

    private fun key(label: String, description: String) = TextView(context).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        minWidth = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Theme.body(context, 800)
        isClickable = true
        isFocusable = true
        highlight(this, false)
    }

    private fun highlight(v: TextView, on: Boolean) {
        val p = Theme.palette
        v.background = StickerDrawable(context, fill = if (on) p.accent else p.surface, outline = p.outline, shadowDp = 0f, cornerDp = 10f)
        v.setTextColor(if (on) p.onAccent else p.ink)
    }
}
