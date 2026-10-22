package dev.tuikio.app.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import dev.tuikio.app.MainActivity
import dev.tuikio.app.brand.BrandManager
import dev.tuikio.app.data.CatalogRepo
import dev.tuikio.app.nav.Screen
import dev.tuikio.app.nav.Tab
import dev.tuikio.app.ui.BrandPack
import dev.tuikio.app.ui.Brands
import dev.tuikio.app.ui.ButtonStyle
import dev.tuikio.app.ui.Contrast
import dev.tuikio.app.ui.CustomBrand
import dev.tuikio.app.ui.DarkMode
import dev.tuikio.app.ui.Fonts
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.MATCH
import dev.tuikio.app.ui.Motion
import dev.tuikio.app.ui.PebbleSwitch
import dev.tuikio.app.ui.Snack
import dev.tuikio.app.ui.StickerDrawable
import dev.tuikio.app.ui.T
import dev.tuikio.app.ui.TButton
import dev.tuikio.app.ui.TField
import dev.tuikio.app.ui.Theme
import dev.tuikio.app.ui.WRAP
import dev.tuikio.app.ui.card
import dev.tuikio.app.ui.dp
import dev.tuikio.app.ui.hstack
import dev.tuikio.app.ui.lp
import dev.tuikio.app.ui.vstack
import dev.tuikio.runtime.GameEnvironment
import dev.tuikio.runtime.GameSession
import dev.tuikio.runtime.LaunchSpec
import dev.tuikio.terminal.TermKey
import java.io.File

class YouScreen : Screen() {
    override val tab = Tab.YOU

    override fun build(ctx: Context): View = ctx.page {
        val p = Theme.palette
        addView(T.title(ctx, "You", 28f))

        addView(ctx.card {
            addView(ctx.hstack(12) {
                addView(ImageView(ctx).apply { setImageResource(Theme.pack.iconRes); contentDescription = null }, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
                addView(ctx.vstack(2) {
                    addView(T.title(ctx, Theme.displayName, 20f))
                    addView(T.soft(ctx, Theme.tagline))
                }, lp(0, WRAP, 1f))
            })
            addView(TButton(ctx, "Brand Studio", icon = Icon.PALETTE).apply { setOnClickListener { nav.push(BrandStudioScreen()) } }, lp(MATCH, WRAP))
        })

        sectionHeader("Appearance")
        addView(ctx.hstack(8) {
            DarkMode.values().forEach { m ->
                val label = when (m) { DarkMode.SYSTEM -> "System"; DarkMode.LIGHT -> "Light"; DarkMode.DARK -> "Dark" }
                addView(TButton(ctx, label, if (Theme.darkMode == m) ButtonStyle.MINT else ButtonStyle.SECONDARY).apply {
                    minHeight = ctx.dp(44)
                    setOnClickListener { Theme.setDarkMode(m) }
                }, lp(0, WRAP, 1f))
            }
        })
        addView(switchRow(ctx, "Retro Phosphor theme", "Green-on-black, everywhere. Keeps your brand's name and mark.", Theme.phosphor) { Theme.setPhosphor(it) })
        addView(switchRow(ctx, "Reduce motion", Motion.reason()?.let { "Already reduced right now: $it." } ?: "Calmer transitions; no decorative movement.", Motion.userPrefersReduced) {
            Motion.setUserReduced(ctx, it)
        })

        sectionHeader("Controls")
        val prefs = app.prefs
        addView(switchRow(ctx, "Haptics", "A light tick on every control press.", prefs.haptics) { prefs.haptics = it })
        addView(switchRow(ctx, "Left-handed layout", "Mirror on-screen controls.", prefs.leftHanded) { prefs.leftHanded = it })
        addView(choiceRow(ctx, "Control size", listOf("Small" to 0.85f, "Medium" to 1f, "Large" to 1.2f), prefs.controlScale) { prefs.controlScale = it })
        addView(choiceRow(ctx, "Game text size", listOf("Small" to 0.8f, "Medium" to 1f, "Large" to 1.3f), prefs.terminalTextScale) { prefs.terminalTextScale = it })
        addView(switchRow(ctx, "Show games not ported yet", "Keep discovery links in Discover and Search.", prefs.showDiscoverOnly) { prefs.showDiscoverOnly = it })

        sectionHeader("Catalog")
        addView(ctx.card(shadow = false, gapDp = 8) {
            val doc = app.catalog.document
            addView(T.body(ctx, "Catalog v${doc.catalogVersion} · ${doc.games.size} games · ${doc.discovery.size} links", 15f, p.ink, 700))
            addView(T.soft(ctx, "Checked only when you tap below. Downloads are signature-verified before use."))
            val result = T.soft(ctx, "")
            addView(result)
            addView(TButton(ctx, "Check for catalog updates", ButtonStyle.SECONDARY, Icon.REFRESH).apply {
                setOnClickListener {
                    result.text = "Checking…"
                    app.background({ app.catalog.sync() }) { r ->
                        result.text = when (val s = r.getOrNull()) {
                            is CatalogRepo.SyncResult.NotConfigured -> "This build has no catalog update address yet; it uses the catalog that shipped with it."
                            is CatalogRepo.SyncResult.UpToDate -> "You have the latest catalog."
                            is CatalogRepo.SyncResult.Updated -> "Updated to catalog v${s.version}."
                            is CatalogRepo.SyncResult.Failed -> s.reason
                            null -> "Couldn't check: ${r.exceptionOrNull()?.message}"
                        }
                    }
                }
            }, lp(MATCH, WRAP))
        })

        sectionHeader("Help and privacy")
        addView(TButton(ctx, "Diagnostics", ButtonStyle.SECONDARY, Icon.GAMEPAD).apply { setOnClickListener { nav.push(DiagnosticsScreen()) } }, lp(MATCH, WRAP))
        addView(TButton(ctx, "Privacy", ButtonStyle.SECONDARY, Icon.SHIELD).apply { setOnClickListener { nav.push(AboutScreen(privacy = true)) } }, lp(MATCH, WRAP))
        addView(TButton(ctx, "About, credits and licenses", ButtonStyle.SECONDARY, Icon.INFO).apply { setOnClickListener { nav.push(AboutScreen()) } }, lp(MATCH, WRAP))
    }

    private fun switchRow(ctx: Context, title: String, body: String, value: Boolean, onChange: (Boolean) -> Unit): View = ctx.hstack(12) {
        val sw = PebbleSwitch(ctx).apply { checked = value; contentDescription = title; this.onChange = onChange }
        addView(ctx.vstack(2) {
            addView(T.body(ctx, title, 16f, Theme.palette.ink, 800))
            addView(T.soft(ctx, body, 13f))
        }, lp(0, WRAP, 1f))
        addView(sw)
        isClickable = true
        setOnClickListener { sw.performClick() }
    }

    private fun choiceRow(ctx: Context, title: String, options: List<Pair<String, Float>>, value: Float, onPick: (Float) -> Unit): View = ctx.vstack(6) {
        addView(T.body(ctx, title, 16f, Theme.palette.ink, 800))
        addView(ctx.hstack(8) {
            options.forEach { (label, v) ->
                addView(TButton(ctx, label, if (Math.abs(v - value) < 0.01f) ButtonStyle.MINT else ButtonStyle.SECONDARY).apply {
                    minHeight = ctx.dp(44)
                    setOnClickListener { onPick(v); nav.rebuild() }
                }, lp(0, WRAP, 1f))
            }
        })
    }
}

class BrandStudioScreen : Screen() {
    private var preview: BrandPack = Theme.pack

    override fun build(ctx: Context): View = ctx.page(withDock = false, gapDp = 14) {
        val activity = ctx as MainActivity
        topBar(nav, "Brand Studio")
        addView(previewCard(ctx, preview))
        addView(T.label(ctx, "Bundled brands"))
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(ctx.hstack(10) {
                Brands.ALL.forEach { b -> addView(packTile(ctx, b, b == preview) { preview = b; nav.rebuild() }, LinearLayout.LayoutParams(ctx.dp(128), WRAP)) }
            })
        })
        addView(TButton(ctx, if (preview == Theme.pack && Theme.custom == null) "Applied: ${preview.displayName}" else "Apply ${preview.displayName}").apply {
            setOnClickListener {
                Theme.setCustom(null)
                val ok = BrandManager.apply(ctx, preview, switchLauncher = true)
                Snack.show(activity.nav.overlay, if (ok) "${preview.displayName} applied. Your launcher may take a moment to update its icon." else "Applied in the app; your launcher didn't accept the icon change.")
            }
        }, lp(MATCH, WRAP))
        addView(ctx.hstack(8) {
            addView(TButton(ctx, "Create my brand", ButtonStyle.SECONDARY, Icon.SPARK).apply { setOnClickListener { nav.push(CustomBrandScreen()) } }, lp(0, WRAP, 1f))
            addView(TButton(ctx, "Reset to Tuikio", ButtonStyle.SECONDARY).apply {
                setOnClickListener { BrandManager.resetToTuikio(ctx); preview = Brands.TUIKIO; Snack.show(activity.nav.overlay, "Back to Tuikio.") }
            }, lp(0, WRAP, 1f))
        })
        addView(ctx.card(dashed = true, shadow = false, gapDp = 6) {
            addView(T.body(ctx, "What changes where", 15f, Theme.palette.ink, 800))
            addView(T.soft(ctx, "Bundled brands change the app inside and switch the app-drawer icon and name (some launchers refresh after a short delay). A custom name or picture changes the app inside; for your home screen we can add a pinned shortcut you approve. Your games, saves and account links never change."))
        })
    }

    private fun previewCard(ctx: Context, b: BrandPack): View {
        val pal = b.palette(Theme.systemIsDark())
        val s = b.shapes
        return ctx.vstack(12) {
            background = StickerDrawable(ctx, fill = pal.ground, outline = pal.outline, outlineDp = Math.max(s.outlineDp, 1f), shadowDp = s.shadowDp, cornerDp = s.cornerDp + 6, shadowColor = pal.shadow)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16) + ctx.dp(s.shadowDp.toInt()))
            contentDescription = "Preview of ${b.displayName}"
            addView(ctx.hstack(10) {
                addView(ImageView(ctx).apply { setImageResource(b.iconRes); contentDescription = null }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
                addView(android.widget.TextView(ctx).apply {
                    text = if (b.display.caps) b.displayName.uppercase() else b.displayName
                    typeface = Fonts.get(ctx, b.display)
                    textSize = 22f
                    setTextColor(pal.ink)
                }, lp(0, WRAP, 1f))
            })
            addView(T.body(ctx, b.tagline, 14f, pal.inkSoft, 600))
            addView(ctx.hstack(10) {
                background = StickerDrawable(ctx, fill = pal.surface, outline = pal.hairline, outlineDp = Math.max(s.outlineDp, 1f), shadowDp = 0f, cornerDp = s.cornerDp)
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                addView(T.body(ctx, "Vitetris", 15f, pal.ink, 800), lp(0, WRAP, 1f))
                addView(android.widget.TextView(ctx).apply {
                    text = "Play"
                    setTextColor(pal.onAccent)
                    typeface = Fonts.get(ctx, b.body.face, 800)
                    background = StickerDrawable(ctx, fill = pal.accent, outline = pal.outline, outlineDp = s.outlineDp, shadowDp = 0f, cornerDp = s.buttonCornerDp)
                    setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
                })
            })
        }
    }

    private fun packTile(ctx: Context, b: BrandPack, selected: Boolean, onPick: () -> Unit): View = ctx.vstack(6) {
        val pal = b.palette(Theme.systemIsDark())
        gravity = Gravity.CENTER_HORIZONTAL
        background = StickerDrawable(ctx, fill = pal.ground, outline = if (selected) Theme.palette.accent else pal.hairline, outlineDp = if (selected) 3f else 2f, shadowDp = 0f, cornerDp = 18f)
        setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(10), ctx.dp(12))
        isClickable = true
        isFocusable = true
        contentDescription = "${b.displayName}${if (selected) ", selected" else ""}"
        setOnClickListener { onPick() }
        addView(ImageView(ctx).apply { setImageResource(b.iconRes); contentDescription = null }, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)))
        addView(android.widget.TextView(ctx).apply {
            text = b.displayName
            typeface = Fonts.get(ctx, b.display)
            textSize = 14f
            setTextColor(pal.ink)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(ctx.hstack(4) {
            listOf(pal.accent, pal.accent2, pal.highlight).forEach { c ->
                addView(View(ctx).apply { background = StickerDrawable(ctx, fill = c, outline = pal.outline, outlineDp = 1f, shadowDp = 0f, cornerDp = -1f) }, LinearLayout.LayoutParams(ctx.dp(14), ctx.dp(14)))
            }
        })
    }
}

class CustomBrandScreen : Screen() {
    private var accent: Int? = Theme.custom?.accent
    private var base: String = Theme.custom?.basePack ?: Theme.pack.id

    override fun build(ctx: Context): View = ctx.page(withDock = false, gapDp = 14) {
        val activity = ctx as MainActivity
        val custom = Theme.custom
        topBar(nav, "Create my brand")
        addView(T.soft(ctx, "Stays on this phone. Nothing is uploaded unless you choose to share it."))
        val name = TField(ctx, "Display name", "Name shown inside the app").apply { input.setText(custom?.name ?: "") }
        val tagline = TField(ctx, "Tagline (optional)", "A few words under the name").apply { input.setText(custom?.tagline ?: "") }
        addView(name)
        addView(tagline)
        addView(ctx.hstack(12) {
            val logo = custom?.logoFile
            addView(ImageView(ctx).apply {
                if (logo != null) setImageBitmap(BitmapFactory.decodeFile(logo.absolutePath)) else setImageResource(Theme.pack.iconRes)
                contentDescription = "Current logo"
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
                }
            }, LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(72)))
            addView(TButton(ctx, if (logo != null) "Change picture" else "Import picture", ButtonStyle.SECONDARY, Icon.IMAGE).apply {
                setOnClickListener {
                    Theme.setCustom((Theme.custom ?: CustomBrand(name.text.ifBlank { Theme.displayName }, tagline.text, base, accent, null)))
                    BrandManager.pickLogo(activity)
                }
            }, lp(0, WRAP, 1f))
        })
        addView(T.soft(ctx, "PNG, JPEG or WebP. We crop it to a circle-safe square and keep a 512px copy without its metadata."))
        addView(T.label(ctx, "Built on"))
        val flow = FlowRow(ctx)
        Brands.ALL.forEach { b ->
            flow.addView(TButton(ctx, b.displayName, if (b.id == base) ButtonStyle.MINT else ButtonStyle.SECONDARY).apply {
                minHeight = ctx.dp(44)
                setOnClickListener { base = b.id; nav.rebuild() }
            })
        }
        addView(flow)
        addView(T.label(ctx, "Accent"))
        val swatches = listOf<Int?>(null, 0xFFFF6B4A.toInt(), 0xFF2BD9A0.toInt(), 0xFF5AA9FF.toInt(), 0xFFC48BFF.toInt(), 0xFFFFC93C.toInt(), 0xFFFF7EB6.toInt())
        addView(ctx.hstack(10) {
            swatches.forEach { c ->
                val color = c ?: Brands.byId(base).palette(Theme.systemIsDark()).accent
                addView(View(ctx).apply {
                    background = StickerDrawable(ctx, fill = color, outline = if (c == accent) Theme.palette.ink else Theme.palette.hairline, outlineDp = if (c == accent) 3f else 2f, shadowDp = 0f, cornerDp = -1f)
                    contentDescription = if (c == null) "Brand default accent" else "Accent color"
                    isClickable = true
                    setOnClickListener { accent = c; nav.rebuild() }
                }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
            }
        })
        val ground = Brands.byId(base).palette(Theme.systemIsDark()).ground
        accent?.let { a ->
            val ratio = Contrast.ratio(a, ground)
            if (ratio < 1.6) addView(T.body(ctx, "That accent nearly disappears on this background (contrast ${"%.1f".format(ratio)}:1). Buttons keep readable text, but consider another color.", 14f, Theme.palette.danger, 700))
        }
        addView(TButton(ctx, "Save my brand").apply {
            setOnClickListener {
                val n = name.text.trim().take(24)
                if (n.isEmpty()) { Snack.show(activity.nav.overlay, "Give your brand a name first."); return@setOnClickListener }
                Theme.setCustom(CustomBrand(n, tagline.text.trim().take(48), base, accent, Theme.custom?.logoPath))
                Snack.show(activity.nav.overlay, "Saved. $n is your brand inside the app.")
            }
        }, lp(MATCH, WRAP))
        if (custom != null) {
            addView(TButton(ctx, "Add a home-screen shortcut", ButtonStyle.SECONDARY, Icon.PLUS).apply {
                setOnClickListener {
                    val ok = BrandManager.requestPinnedShortcut(ctx, Theme.custom!!)
                    Snack.show(activity.nav.overlay, if (ok) "Your launcher will ask you to place the shortcut." else "Your launcher doesn't support pinned shortcuts.")
                }
            }, lp(MATCH, WRAP))
            addView(T.soft(ctx, "A shortcut, not a rename: the app-drawer icon stays a bundled identity, and the shortcut may look different on other launchers."))
            addView(TButton(ctx, "Remove my brand", ButtonStyle.DANGER).apply {
                setOnClickListener { Theme.setCustom(null); File(ctx.filesDir, "brand").deleteRecursively(); nav.pop() }
            }, lp(MATCH, WRAP))
        }
    }
}

class AboutScreen(private val privacy: Boolean = false) : Screen() {
    override fun build(ctx: Context): View = ctx.page(withDock = false) {
        topBar(nav, if (privacy) "Privacy" else "About")
        if (privacy) {
            listOf(
                "No accounts" to "Browsing, installing and playing never need an account.",
                "No analytics" to "Tuikio has no analytics or advertising SDKs and sends no usage data.",
                "Network only when you ask" to "Checking the catalog, previewing a source, signing in to GitHub and sending a suggestion are the only times Tuikio goes online. Games marked offline never use the network.",
                "Anonymous suggestions" to "They carry no name or account. The submission server sees your network address while handling the request and keeps only a short-lived hashed form for spam limits.",
                "GitHub" to "If you connect GitHub, your token is encrypted with a key kept in Android's Keystore. Disconnecting deletes it from this phone.",
                "Your pictures" to "A custom logo is cropped and re-saved on this phone without its metadata. It is never uploaded.",
            ).forEach { (h, b) -> addView(ctx.card(shadow = false, gapDp = 4) { addView(T.body(ctx, h, 15f, Theme.palette.ink, 800)); addView(T.soft(ctx, b)) }) }
            return@page
        }
        addView(ctx.card(gapDp = 6) {
            val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            addView(T.title(ctx, Theme.displayName, 22f))
            addView(T.soft(ctx, "Version $v · ${ctx.packageName}"))
            addView(T.soft(ctx, "Tuikio is free software under the GNU GPL v3 or later. Each game keeps its own license, listed below with its source."))
            addView(linkRow(ctx, "Tuikio source code", "https://github.com/omersusin/tuikio"))
        })
        sectionHeader("Games and their authors")
        app.catalog.games.forEach { g ->
            addView(ctx.vstack(2) {
                addView(T.body(ctx, g.title, 15f, Theme.palette.ink, 800))
                addView(T.soft(ctx, "${g.upstream.authors.joinToString(", ")} · ${g.upstream.license}", 13f))
                addView(linkRow(ctx, "Source", g.upstream.sourceUrl))
            })
        }
        sectionHeader("Built with")
        listOf(
            "ncurses 6.4 (X11/MIT-style license) — terminal handling for ported games",
            "musl libc (MIT) — the C library linked into each game",
            "Unbounded, Figtree and JetBrains Mono fonts (SIL Open Font License 1.1)",
            "Kotlin standard library (Apache-2.0)",
        ).forEach { addView(T.soft(ctx, "•  $it")) }
        addView(TButton(ctx, "License texts and game sources", ButtonStyle.SECONDARY).apply { setOnClickListener { nav.push(LicensesScreen()) } }, lp(MATCH, WRAP))
    }
}

/** The full license texts and the source listing that ship inside the APK (assets/licenses). */
class LicensesScreen(private val file: String? = null) : Screen() {
    override fun build(ctx: Context): View = ctx.page(withDock = false) {
        if (file != null) {
            topBar(nav, file.removeSuffix(".txt"))
            val text = runCatching { ctx.assets.open("licenses/$file").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrElse { "Couldn't open $file." }
            addView(T.mono(ctx, text, 11f, Theme.palette.ink).apply { setHorizontallyScrolling(false); setTextIsSelectable(true) })
            return@page
        }
        topBar(nav, "Licenses")
        addView(T.soft(ctx, "Where each game's exact source code comes from, and every license that applies to what's inside this app."))
        val files = (ctx.assets.list("licenses") ?: emptyArray()).filter { it.endsWith(".txt") }.sortedWith(compareBy({ it != "SOURCES.txt" }, { it.lowercase() }))
        files.forEach { f ->
            addView(TButton(ctx, if (f == "SOURCES.txt") "Game source archives" else f.removeSuffix(".txt"), ButtonStyle.SECONDARY).apply {
                setOnClickListener { nav.push(LicensesScreen(f)) }
            }, lp(MATCH, WRAP))
        }
    }
}

/**
 * Runs a real end-to-end check on this phone: starts the PTY helper with a bundled game,
 * waits for it to draw, sends input and confirms a clean exit. The report helps verify a
 * device and can be copied into a bug report (it contains no personal data).
 */
class DiagnosticsScreen : Screen() {
    private var report: String? = null
    private var running = false

    override fun build(ctx: Context): View = ctx.page(withDock = false) {
        topBar(nav, "Diagnostics")
        addView(T.soft(ctx, "Runs 2048 for a moment in the background to check that games can start, draw and receive input on this phone."))
        val out = T.mono(ctx, report ?: "No check run yet.", 12f, Theme.palette.ink).apply { setHorizontallyScrolling(false) }
        addView(ctx.card(shadow = false) { addView(out) })
        addView(TButton(ctx, if (running) "Running…" else "Run the runtime check", icon = Icon.PLAY).apply {
            isEnabled = !running
            setOnClickListener {
                running = true
                nav.rebuild()
                app.background({ selfTest(ctx) }) { r ->
                    running = false
                    report = r.getOrElse { "FAILED: ${it.message}" }
                    nav.rebuild()
                }
            }
        }, lp(MATCH, WRAP))
        report?.let { text ->
            addView(TButton(ctx, "Copy report", ButtonStyle.SECONDARY).apply {
                setOnClickListener {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tuikio diagnostics", text))
                    Snack.show((ctx as MainActivity).nav.overlay, "Report copied.")
                }
            }, lp(MATCH, WRAP))
        }
    }

    private fun selfTest(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        sb.appendLine("Native dir: ${libDir.listFiles()?.size ?: 0} files")
        val helper = File(libDir, LaunchSpec.PTY_HELPER)
        val game = File(libDir, "libgame_2048.so")
        sb.appendLine("Helper present: ${helper.isFile} · executable: ${helper.canExecute()}")
        if (!helper.isFile || !game.isFile) return sb.append("RESULT: FAIL (executables missing)").toString()
        val home = File(ctx.cacheDir, "selftest").apply { deleteRecursively(); mkdirs() }
        val session = GameSession(LaunchSpec(helper, game, emptyList(), home, GameEnvironment.build(home)), 40, 20)
        val t0 = SystemClock.elapsedRealtime()
        session.start()
        val drew = session.awaitScreen(5000) { it.joinToString("").contains("pts") }
        val firstFrameMs = SystemClock.elapsedRealtime() - t0
        sb.appendLine("Game drew its board: $drew (${firstFrameMs} ms)")
        val before = session.screenText().joinToString("\n")
        repeat(3) { session.send(session.keys.encode(TermKey.LEFT)); session.send(session.keys.encode(TermKey.UP)) }
        val moved = session.awaitScreen(3000) { it.joinToString("\n") != before }
        sb.appendLine("Input reached the game: $moved")
        session.send(session.keys.text("q"))
        session.awaitScreen(2000) { it.joinToString("").contains("QUIT") }
        session.send(session.keys.text("y"))
        val code = session.awaitExit(3000)
        sb.appendLine("Clean exit: ${code == 0} (code $code)")
        session.stop(500)
        home.deleteRecursively()
        val pass = drew && moved && code == 0
        sb.append("RESULT: ${if (pass) "PASS" else "FAIL"}")
        return sb.toString()
    }
}
