package dev.tuikio.app.ui

import android.content.Context
import android.graphics.Typeface

/** Every color a screen may use. Components never hard-code colors; brands swap whole palettes. */
class Palette(
    val isDark: Boolean,
    val ground: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val ink: Int,
    val inkSoft: Int,
    val outline: Int,
    val hairline: Int,
    val shadow: Int,
    val accent: Int,
    val onAccent: Int,
    val accent2: Int,
    val onAccent2: Int,
    val highlight: Int,
    val onHighlight: Int,
    val link: Int,
    val danger: Int,
    val dock: Int,
    val dockMuted: Int,
    val screen: Int,
    val screenInk: Int,
    val screenEdge: Int,
    /** Terminal colors 0..15 used for games' ANSI palette, tuned per brand for contrast. */
    val ansi: IntArray,
    val cursor: Int,
)

enum class FontFace(val asset: String?) {
    DISPLAY_ROUND("fonts/Unbounded-Variable.ttf"),
    SANS("fonts/Figtree-Variable.ttf"),
    MONO("fonts/JetBrainsMono-Variable.ttf"),
    SERIF(null),
}

class TypeSpec(val face: FontFace, val weight: Int, val letterSpacing: Float = 0f, val caps: Boolean = false)

/** How a brand moves. Components ask [Motion] for timings; reduced motion overrides all of these. */
enum class MotionStyle {
    /** Tuikio: quick spring with a small overshoot, like a sticker settling. */
    SPRINGY,

    /** Tuiko: slower, softer easing. */
    FLOATY,

    /** PockTTY: short, mechanical clicks. */
    CLICKY,

    /** Tuitopia: gentle drift. */
    DRIFT,

    /** Bitiko: stepped, no easing — pixels jump. */
    STEPPED,

    /** Ttybit: everything instant. */
    INSTANT,
}

class Shapes(
    val cornerDp: Float,
    /** Negative means fully rounded (pill). */
    val buttonCornerDp: Float,
    val outlineDp: Float,
    /** Hard drop shadow offset; 0 for none. */
    val shadowDp: Float,
    val softShadow: Boolean = false,
)

/** A complete bundled identity: name, launcher alias, mark, palettes, type, shapes, motion. */
class BrandPack(
    val id: String,
    val displayName: String,
    val tagline: String,
    /** Manifest activity-alias that carries this brand's launcher label and icon. */
    val alias: String,
    val iconRes: Int,
    val light: Palette?,
    val dark: Palette?,
    val display: TypeSpec,
    val body: TypeSpec,
    val label: TypeSpec,
    val shapes: Shapes,
    val motion: MotionStyle,
    val bundled: Boolean = true,
) {
    fun palette(systemDark: Boolean): Palette = when {
        systemDark && dark != null -> dark
        light != null -> light
        else -> dark!!
    }
}

/** Loads the bundled variable fonts once per weight. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    fun get(context: Context, spec: TypeSpec): Typeface = get(context, spec.face, spec.weight)

    fun get(context: Context, face: FontFace, weight: Int): Typeface {
        val key = "${face.name}:$weight"
        cache[key]?.let { return it }
        val tf = when (face) {
            FontFace.SERIF -> Typeface.create(Typeface.SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            else -> try {
                Typeface.Builder(context.assets, face.asset!!).setFontVariationSettings("'wght' $weight").build()
            } catch (e: RuntimeException) {
                if (face == FontFace.MONO) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD
            }
        }
        cache[key] = tf
        return tf
    }
}

private fun ansi(vararg c: Int) = c

object Brands {
    private val tuikioAnsiDark = ansi(
        0x1B1838, 0xFF6B6B, 0x3BE3B0, 0xFFC93C, 0x6CA8FF, 0xC48BFF, 0x4FD8E8, 0xD9D4F2,
        0x6B6491, 0xFF8F8F, 0x7DF0C9, 0xFFDD7A, 0x9CC4FF, 0xDDB6FF, 0x8CE9F2, 0xFFFFFF,
    )

    val TUIKIO = BrandPack(
        id = "tuikio",
        displayName = "Tuikio",
        tagline = "Terminal games, carried home.",
        alias = "dev.tuikio.app.LauncherTuikio",
        iconRes = dev.tuikio.app.R.mipmap.ic_launcher_tuikio,
        light = Palette(
            isDark = false, ground = 0xFFF6F3FB.toInt(), surface = 0xFFFFFFFF.toInt(), surfaceAlt = 0xFFEFEAF8.toInt(),
            ink = 0xFF1E1A3A.toInt(), inkSoft = 0xFF4A4566.toInt(), outline = 0xFF1E1A3A.toInt(), hairline = 0xFFE2DCEF.toInt(),
            shadow = 0xFF1E1A3A.toInt(), accent = 0xFFFF6B4A.toInt(), onAccent = 0xFF1E1A3A.toInt(),
            accent2 = 0xFF2BD9A0.toInt(), onAccent2 = 0xFF1E1A3A.toInt(), highlight = 0xFFFFC93C.toInt(), onHighlight = 0xFF1E1A3A.toInt(),
            link = 0xFFC2381C.toInt(), danger = 0xFFC2381C.toInt(), dock = 0xFF1E1A3A.toInt(), dockMuted = 0xFFC9C4E8.toInt(),
            screen = 0xFF1B1838.toInt(), screenInk = 0xFFE9E6FF.toInt(), screenEdge = 0xFF3A3470.toInt(),
            ansi = tuikioAnsiDark, cursor = 0xFFFF6B4A.toInt(),
        ),
        dark = Palette(
            isDark = true, ground = 0xFF121027.toInt(), surface = 0xFF1D1A38.toInt(), surfaceAlt = 0xFF262246.toInt(),
            ink = 0xFFF3F0FF.toInt(), inkSoft = 0xFFB9B3DA.toInt(), outline = 0xFF0A0817.toInt(), hairline = 0xFF2E2A52.toInt(),
            shadow = 0xFF06050F.toInt(), accent = 0xFFFF7A5C.toInt(), onAccent = 0xFF1E1A3A.toInt(),
            accent2 = 0xFF3BE3B0.toInt(), onAccent2 = 0xFF1E1A3A.toInt(), highlight = 0xFFFFC93C.toInt(), onHighlight = 0xFF1E1A3A.toInt(),
            link = 0xFFFF9A80.toInt(), danger = 0xFFFF8F8F.toInt(), dock = 0xFF262246.toInt(), dockMuted = 0xFFB9B3DA.toInt(),
            screen = 0xFF0E0C20.toInt(), screenInk = 0xFFE9E6FF.toInt(), screenEdge = 0xFF3A3470.toInt(),
            ansi = tuikioAnsiDark, cursor = 0xFFFF7A5C.toInt(),
        ),
        display = TypeSpec(FontFace.DISPLAY_ROUND, 800),
        body = TypeSpec(FontFace.SANS, 500),
        label = TypeSpec(FontFace.SANS, 800, 0.06f, caps = true),
        shapes = Shapes(cornerDp = 24f, buttonCornerDp = -1f, outlineDp = 2f, shadowDp = 4f),
        motion = MotionStyle.SPRINGY,
    )

    val TUIKO = BrandPack(
        id = "tuiko",
        displayName = "Tuiko",
        tagline = "Cozy little terminal games.",
        alias = "dev.tuikio.app.LauncherTuiko",
        iconRes = dev.tuikio.app.R.mipmap.ic_launcher_tuiko,
        light = Palette(
            isDark = false, ground = 0xFFFFF6F1.toInt(), surface = 0xFFFFFFFF.toInt(), surfaceAlt = 0xFFFBEDF1.toInt(),
            ink = 0xFF3D3350.toInt(), inkSoft = 0xFF6B5E7E.toInt(), outline = 0xFFEBD3E0.toInt(), hairline = 0xFFF3E1EA.toInt(),
            shadow = 0x339678C8, accent = 0xFFC8B6FF.toInt(), onAccent = 0xFF3D3350.toInt(),
            accent2 = 0xFFFFB4A2.toInt(), onAccent2 = 0xFF3D3350.toInt(), highlight = 0xFFFFE59A.toInt(), onHighlight = 0xFF3D3350.toInt(),
            link = 0xFF8A4FB8.toInt(), danger = 0xFFB0405A.toInt(), dock = 0xFFFFFFFF.toInt(), dockMuted = 0xFF8A7D9C.toInt(),
            screen = 0xFF3D3350.toInt(), screenInk = 0xFFFFF0F6.toInt(), screenEdge = 0xFF5A4E70.toInt(),
            ansi = ansi(0x3D3350, 0xFF8FA3, 0x9FE6C3, 0xFFE59A, 0xA9C8FF, 0xD9B8FF, 0x9EE3EC, 0xF4EAF2, 0x7A6E8F, 0xFFB0BE, 0xC2F2DA, 0xFFF0C2, 0xC9DCFF, 0xE8D4FF, 0xC4F0F5, 0xFFFFFF),
            cursor = 0xFFFFB4A2.toInt(),
        ),
        dark = Palette(
            isDark = true, ground = 0xFF2A2236.toInt(), surface = 0xFF372E46.toInt(), surfaceAlt = 0xFF41374F.toInt(),
            ink = 0xFFFFF0F6.toInt(), inkSoft = 0xFFD7C8DE.toInt(), outline = 0xFF4D425E.toInt(), hairline = 0xFF4D425E.toInt(),
            shadow = 0x55000000, accent = 0xFFC8B6FF.toInt(), onAccent = 0xFF2A2236.toInt(),
            accent2 = 0xFFFFB4A2.toInt(), onAccent2 = 0xFF2A2236.toInt(), highlight = 0xFFFFE59A.toInt(), onHighlight = 0xFF2A2236.toInt(),
            link = 0xFFD9C6FF.toInt(), danger = 0xFFFFA3B5.toInt(), dock = 0xFF41374F.toInt(), dockMuted = 0xFFBFAFCC.toInt(),
            screen = 0xFF1F1929.toInt(), screenInk = 0xFFFFF0F6.toInt(), screenEdge = 0xFF4D425E.toInt(),
            ansi = ansi(0x1F1929, 0xFF8FA3, 0x9FE6C3, 0xFFE59A, 0xA9C8FF, 0xD9B8FF, 0x9EE3EC, 0xF4EAF2, 0x7A6E8F, 0xFFB0BE, 0xC2F2DA, 0xFFF0C2, 0xC9DCFF, 0xE8D4FF, 0xC4F0F5, 0xFFFFFF),
            cursor = 0xFFFFB4A2.toInt(),
        ),
        display = TypeSpec(FontFace.SANS, 800, -0.01f),
        body = TypeSpec(FontFace.SANS, 600),
        label = TypeSpec(FontFace.SANS, 800, 0.04f),
        shapes = Shapes(cornerDp = 30f, buttonCornerDp = 22f, outlineDp = 0f, shadowDp = 0f, softShadow = true),
        motion = MotionStyle.FLOATY,
    )

    val POCKTTY = BrandPack(
        id = "pocktty",
        displayName = "PockTTY",
        tagline = "A pocket arcade for terminal games.",
        alias = "dev.tuikio.app.LauncherPockTTY",
        iconRes = dev.tuikio.app.R.mipmap.ic_launcher_pocktty,
        light = Palette(
            isDark = false, ground = 0xFFD9D4C7.toInt(), surface = 0xFFF2EEE3.toInt(), surfaceAlt = 0xFFE6E1D3.toInt(),
            ink = 0xFF2E2E36.toInt(), inkSoft = 0xFF55555F.toInt(), outline = 0xFF3A3A44.toInt(), hairline = 0xFFC4BEAF.toInt(),
            shadow = 0xFF3A3A44.toInt(), accent = 0xFFD6336C.toInt(), onAccent = 0xFFFFFFFF.toInt(),
            accent2 = 0xFFB6C77A.toInt(), onAccent2 = 0xFF2F3D19.toInt(), highlight = 0xFFF2C84B.toInt(), onHighlight = 0xFF2E2E36.toInt(),
            link = 0xFFA61E4D.toInt(), danger = 0xFFA61E4D.toInt(), dock = 0xFF3A3A44.toInt(), dockMuted = 0xFFC4BEAF.toInt(),
            screen = 0xFFB6C77A.toInt(), screenInk = 0xFF1E2A0E.toInt(), screenEdge = 0xFF3A3A44.toInt(),
            ansi = ansi(0xB6C77A, 0x3B4A1C, 0x2F3D19, 0x4A5A26, 0x2A3615, 0x3E4C1E, 0x34431A, 0x1E2A0E, 0x6E7F3C, 0x2F3D19, 0x1E2A0E, 0x2A3615, 0x1E2A0E, 0x2F3D19, 0x1E2A0E, 0x101808),
            cursor = 0xFF1E2A0E.toInt(),
        ),
        dark = null,
        display = TypeSpec(FontFace.MONO, 800, 0.02f, caps = true),
        body = TypeSpec(FontFace.SANS, 600),
        label = TypeSpec(FontFace.MONO, 700, 0.08f, caps = true),
        shapes = Shapes(cornerDp = 10f, buttonCornerDp = 8f, outlineDp = 3f, shadowDp = 3f),
        motion = MotionStyle.CLICKY,
    )

    val TUITOPIA = BrandPack(
        id = "tuitopia",
        displayName = "Tuitopia",
        tagline = "An archipelago of tiny games.",
        alias = "dev.tuikio.app.LauncherTuitopia",
        iconRes = dev.tuikio.app.R.mipmap.ic_launcher_tuitopia,
        light = null,
        dark = Palette(
            isDark = true, ground = 0xFF12303A.toInt(), surface = 0xFF173E49.toInt(), surfaceAlt = 0xFF1D4B57.toInt(),
            ink = 0xFFF3EBD9.toInt(), inkSoft = 0xFFBFD6D3.toInt(), outline = 0xFFF3D9A4.toInt(), hairline = 0xFF2C5E69.toInt(),
            shadow = 0x66000000, accent = 0xFFFF8A3D.toInt(), onAccent = 0xFF12303A.toInt(),
            accent2 = 0xFF6BBF59.toInt(), onAccent2 = 0xFF0E2A17.toInt(), highlight = 0xFFF3D9A4.toInt(), onHighlight = 0xFF12303A.toInt(),
            link = 0xFFFFB27A.toInt(), danger = 0xFFFF9A8A.toInt(), dock = 0xFF0B2229.toInt(), dockMuted = 0xFF9FC2C0.toInt(),
            screen = 0xFF0B2229.toInt(), screenInk = 0xFFF3EBD9.toInt(), screenEdge = 0xFF2C5E69.toInt(),
            ansi = ansi(0x0B2229, 0xFF7A6B, 0x6BBF59, 0xF3D9A4, 0x5FB4D8, 0xD79BE0, 0x7FD3D3, 0xE8E0CC, 0x4E7A80, 0xFF9A8A, 0x95DB85, 0xFFE9C2, 0x8CCFEA, 0xEBC0F0, 0xA8E8E8, 0xFFFFFF),
            cursor = 0xFFFF8A3D.toInt(),
        ),
        display = TypeSpec(FontFace.SERIF, 700),
        body = TypeSpec(FontFace.SANS, 500),
        label = TypeSpec(FontFace.SANS, 800, 0.06f, caps = true),
        shapes = Shapes(cornerDp = 18f, buttonCornerDp = 14f, outlineDp = 2f, shadowDp = 0f),
        motion = MotionStyle.DRIFT,
    )

    val BITIKO = BrandPack(
        id = "bitiko",
        displayName = "Bitiko",
        tagline = "Every game, one bit at a time.",
        alias = "dev.tuikio.app.LauncherBitiko",
        iconRes = dev.tuikio.app.R.mipmap.ic_launcher_bitiko,
        light = null,
        dark = Palette(
            isDark = true, ground = 0xFF0B0B12.toInt(), surface = 0xFF15151F.toInt(), surfaceAlt = 0xFF1E1E2C.toInt(),
            ink = 0xFFF4F4F8.toInt(), inkSoft = 0xFFC9C9D6.toInt(), outline = 0xFF3CF2FF.toInt(), hairline = 0xFF2A2A3D.toInt(),
            shadow = 0xFF3CF2FF.toInt(), accent = 0xFFFF3CAC.toInt(), onAccent = 0xFF0B0B12.toInt(),
            accent2 = 0xFF3CF2FF.toInt(), onAccent2 = 0xFF0B0B12.toInt(), highlight = 0xFFFFE14D.toInt(), onHighlight = 0xFF0B0B12.toInt(),
            link = 0xFF3CF2FF.toInt(), danger = 0xFFFF6B6B.toInt(), dock = 0xFF15151F.toInt(), dockMuted = 0xFF9A9AB0.toInt(),
            screen = 0xFF000000.toInt(), screenInk = 0xFFF4F4F8.toInt(), screenEdge = 0xFF3CF2FF.toInt(),
            ansi = ansi(0x000000, 0xFF3C5A, 0x3CFF8C, 0xFFE14D, 0x3C8CFF, 0xFF3CAC, 0x3CF2FF, 0xD0D0E0, 0x55556E, 0xFF7088, 0x7DFFB0, 0xFFF08A, 0x7DB0FF, 0xFF7DC8, 0x7DF7FF, 0xFFFFFF),
            cursor = 0xFFFF3CAC.toInt(),
        ),
        display = TypeSpec(FontFace.MONO, 800, 0.05f, caps = true),
        body = TypeSpec(FontFace.MONO, 500),
        label = TypeSpec(FontFace.MONO, 800, 0.08f, caps = true),
        shapes = Shapes(cornerDp = 0f, buttonCornerDp = 0f, outlineDp = 3f, shadowDp = 4f),
        motion = MotionStyle.STEPPED,
    )

    val TTYBIT = BrandPack(
        id = "ttybit",
        displayName = "Ttybit",
        tagline = "terminal games. nothing else.",
        alias = "dev.tuikio.app.LauncherTtybit",
        iconRes = dev.tuikio.app.R.mipmap.ic_launcher_ttybit,
        light = null,
        dark = Palette(
            isDark = true, ground = 0xFF16181C.toInt(), surface = 0xFF1F2227.toInt(), surfaceAlt = 0xFF262A30.toInt(),
            ink = 0xFFE8E6E1.toInt(), inkSoft = 0xFFA9ADB7.toInt(), outline = 0xFF4A4E57.toInt(), hairline = 0xFF2E3238.toInt(),
            shadow = 0x00000000, accent = 0xFFFFB000.toInt(), onAccent = 0xFF16181C.toInt(),
            accent2 = 0xFF6C7280.toInt(), onAccent2 = 0xFFF4F4F4.toInt(), highlight = 0xFFFFB000.toInt(), onHighlight = 0xFF16181C.toInt(),
            link = 0xFFFFC233.toInt(), danger = 0xFFFF7A6B.toInt(), dock = 0xFF1F2227.toInt(), dockMuted = 0xFF8A8F99.toInt(),
            screen = 0xFF0E0F12.toInt(), screenInk = 0xFFE8E6E1.toInt(), screenEdge = 0xFF2E3238.toInt(),
            ansi = ansi(0x0E0F12, 0xE5735F, 0x8FBF7F, 0xFFB000, 0x7FA6D9, 0xC39AD9, 0x7FC6C6, 0xD8D6D1, 0x5C6068, 0xF28C7A, 0xA9D69A, 0xFFC94D, 0x9DBDE6, 0xD6B5E6, 0x9DDADA, 0xFFFFFF),
            cursor = 0xFFFFB000.toInt(),
        ),
        display = TypeSpec(FontFace.MONO, 700),
        body = TypeSpec(FontFace.SANS, 500),
        label = TypeSpec(FontFace.MONO, 700, 0.04f),
        shapes = Shapes(cornerDp = 3f, buttonCornerDp = 2f, outlineDp = 1f, shadowDp = 0f),
        motion = MotionStyle.INSTANT,
    )

    val ALL = listOf(TUIKIO, TUIKO, POCKTTY, TUITOPIA, BITIKO, TTYBIT)

    fun byId(id: String?): BrandPack = ALL.firstOrNull { it.id == id } ?: TUIKIO

    /** Optional retro look from Settings; keeps the active brand's name and mark. */
    val PHOSPHOR = Palette(
        isDark = true, ground = 0xFF031A0B.toInt(), surface = 0xFF06240F.toInt(), surfaceAlt = 0xFF0A2E14.toInt(),
        ink = 0xFF33FF66.toInt(), inkSoft = 0xFF22C251.toInt(), outline = 0xFF1D7A3A.toInt(), hairline = 0xFF0F4A22.toInt(),
        shadow = 0x00000000, accent = 0xFF33FF66.toInt(), onAccent = 0xFF031A0B.toInt(),
        accent2 = 0xFF1D7A3A.toInt(), onAccent2 = 0xFFB8FFC9.toInt(), highlight = 0xFF33FF66.toInt(), onHighlight = 0xFF031A0B.toInt(),
        link = 0xFF7DFF9C.toInt(), danger = 0xFFB8FFC9.toInt(), dock = 0xFF06240F.toInt(), dockMuted = 0xFF22C251.toInt(),
        screen = 0xFF000A03.toInt(), screenInk = 0xFF33FF66.toInt(), screenEdge = 0xFF1D7A3A.toInt(),
        ansi = ansi(0x000A03, 0x2BD957, 0x33FF66, 0x5CFF85, 0x22C251, 0x40E070, 0x4DFF7A, 0x33FF66, 0x128A35, 0x5CFF85, 0x80FFA0, 0x9CFFB4, 0x40E070, 0x66FF8C, 0x80FFA0, 0xC8FFD6),
        cursor = 0xFF33FF66.toInt(),
    )
}
