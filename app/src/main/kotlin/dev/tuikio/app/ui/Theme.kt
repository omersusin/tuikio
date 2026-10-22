package dev.tuikio.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import java.io.File

/** A user's own local identity: a name, an optional imported logo, a base pack and accent. */
data class CustomBrand(
    val name: String,
    val tagline: String,
    val basePack: String,
    val accent: Int?,
    /** Downscaled PNG in app-private storage; never uploaded anywhere. */
    val logoPath: String?,
) {
    val logoFile: File? get() = logoPath?.let { File(it) }?.takeIf { it.isFile }
}

enum class DarkMode { SYSTEM, LIGHT, DARK }

/**
 * The app's current look. Everything visual reads from here, so switching a brand or the
 * retro theme recolors the whole app without restarting it. Branding never touches game
 * data, saves, accounts or the package identity.
 */
object Theme {
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val listeners = LinkedHashSet<() -> Unit>()

    var pack: BrandPack = Brands.TUIKIO
        private set
    var custom: CustomBrand? = null
        private set
    var phosphor = false
        private set
    var darkMode = DarkMode.SYSTEM
        private set

    lateinit var palette: Palette
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("theme", Context.MODE_PRIVATE)
        pack = Brands.byId(prefs.getString("pack", "tuikio"))
        phosphor = prefs.getBoolean("phosphor", false)
        darkMode = DarkMode.values().firstOrNull { it.name == prefs.getString("dark", "SYSTEM") } ?: DarkMode.SYSTEM
        custom = prefs.getString("custom_name", null)?.let { name ->
            CustomBrand(
                name = name,
                tagline = prefs.getString("custom_tagline", "").orEmpty(),
                basePack = prefs.getString("custom_base", "tuikio").orEmpty(),
                accent = if (prefs.contains("custom_accent")) prefs.getInt("custom_accent", 0) else null,
                logoPath = prefs.getString("custom_logo", null),
            )
        }
        // Initial resolution is silent: listeners belong to screens of a running app, and the
        // app's other state (catalog, library) isn't ready yet at this point.
        resolve(notify = false)
    }

    /** The pack whose palette, type and shapes are in use (a custom brand builds on one). */
    val activePack: BrandPack get() = custom?.let { Brands.byId(it.basePack) } ?: pack

    val displayName: String get() = custom?.name?.takeIf { it.isNotBlank() } ?: pack.displayName
    val tagline: String get() = custom?.tagline?.takeIf { it.isNotBlank() } ?: pack.tagline

    fun systemIsDark(): Boolean =
        (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun resolve(notify: Boolean = true) {
        val base = activePack
        val dark = when (darkMode) {
            DarkMode.SYSTEM -> systemIsDark()
            DarkMode.LIGHT -> false
            DarkMode.DARK -> true
        }
        var p = if (phosphor) Brands.PHOSPHOR else base.palette(dark)
        val accent = custom?.accent
        if (accent != null && !phosphor) p = withAccent(p, accent)
        palette = p
        if (notify) listeners.toList().forEach { it() }
    }

    fun setPack(next: BrandPack) {
        pack = next
        prefs.edit().putString("pack", next.id).apply()
        resolve()
    }

    fun setCustom(brand: CustomBrand?) {
        custom = brand
        val e = prefs.edit()
        if (brand == null) {
            e.remove("custom_name").remove("custom_tagline").remove("custom_base").remove("custom_accent").remove("custom_logo")
        } else {
            e.putString("custom_name", brand.name).putString("custom_tagline", brand.tagline).putString("custom_base", brand.basePack)
            if (brand.accent != null) e.putInt("custom_accent", brand.accent) else e.remove("custom_accent")
            if (brand.logoPath != null) e.putString("custom_logo", brand.logoPath) else e.remove("custom_logo")
        }
        e.apply()
        resolve()
    }

    fun setPhosphor(on: Boolean) {
        phosphor = on
        prefs.edit().putBoolean("phosphor", on).apply()
        resolve()
    }

    fun setDarkMode(mode: DarkMode) {
        darkMode = mode
        prefs.edit().putString("dark", mode.name).apply()
        resolve()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    // ---------------------------------------------------------------- type helpers

    fun display(ctx: Context): Typeface = if (phosphor) Fonts.get(ctx, FontFace.MONO, 800) else Fonts.get(ctx, activePack.display)
    fun body(ctx: Context, weight: Int = activePack.body.weight): Typeface =
        if (phosphor) Fonts.get(ctx, FontFace.MONO, weight) else Fonts.get(ctx, activePack.body.face, weight)
    fun label(ctx: Context): Typeface = if (phosphor) Fonts.get(ctx, FontFace.MONO, 700) else Fonts.get(ctx, activePack.label)
    fun mono(ctx: Context, weight: Int = 600): Typeface = Fonts.get(ctx, FontFace.MONO, weight)

    val shapes: Shapes get() = if (phosphor) Shapes(6f, 4f, 1f, 0f) else activePack.shapes

    /** Swaps the accent and keeps text on it readable. */
    private fun withAccent(p: Palette, accent: Int): Palette {
        val onAccent = if (Contrast.luminance(accent) > 0.4) 0xFF1E1A3A.toInt() else 0xFFFFFFFF.toInt()
        return Palette(
            p.isDark, p.ground, p.surface, p.surfaceAlt, p.ink, p.inkSoft, p.outline, p.hairline, p.shadow,
            accent, onAccent, p.accent2, p.onAccent2, p.highlight, p.onHighlight, p.link, p.danger, p.dock, p.dockMuted,
            p.screen, p.screenInk, p.screenEdge, p.ansi, accent,
        )
    }
}

/** WCAG contrast math, used to warn about unreadable custom colors. */
object Contrast {
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
    }
}
