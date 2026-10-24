package dev.tuikio.app.screens

import android.content.Context
import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import dev.tuikio.app.game.GameLauncher
import dev.tuikio.app.nav.Screen
import dev.tuikio.app.nav.Tab
import dev.tuikio.app.ui.ButtonStyle
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.MATCH
import dev.tuikio.app.ui.StickerDrawable
import dev.tuikio.app.ui.T
import dev.tuikio.app.ui.TButton
import dev.tuikio.app.ui.Theme
import dev.tuikio.app.ui.WRAP
import dev.tuikio.app.ui.card
import dev.tuikio.app.ui.dp
import dev.tuikio.app.ui.hstack
import dev.tuikio.app.ui.iconButton
import dev.tuikio.app.ui.lp
import dev.tuikio.app.ui.vstack
import dev.tuikio.runtime.SaveArchive
import java.io.File

class LibraryScreen : Screen() {
    override val tab = Tab.LIBRARY
    private var section = 0

    override fun build(ctx: Context): View = ctx.page {
        val p = Theme.palette
        addView(ctx.hstack {
            addView(T.title(ctx, "Library", 28f), lp(0, WRAP, 1f))
            addView(T.body(ctx, "Plays offline", 13f, p.inkSoft, 800))
        })
        val tabs = listOf("Installed · ${app.library.installed.size}", "Favorites · ${app.library.favorites.size}")
        addView(ctx.hstack(8) {
            tabs.forEachIndexed { i, label ->
                addView(TButton(ctx, label, if (i == section) ButtonStyle.MINT else ButtonStyle.SECONDARY).apply {
                    minHeight = ctx.dp(44)
                    contentDescription = label + if (i == section) ", selected" else ""
                    setOnClickListener { section = i; nav.rebuild() }
                }, lp(0, WRAP, 1f))
            }
        })

        val ids = if (section == 0) app.library.installed.map { it.gameId } else app.library.favorites
        val games = ids.mapNotNull { app.catalog.game(it) }
        if (games.isEmpty()) {
            addView(
                if (section == 0) ctx.emptyState("Your shelf is empty", "Kio is ready to carry a game home. Everything you install plays offline, and nothing needs an account.", "Find a game") {
                    (ctx as dev.tuikio.app.MainActivity).openTab(Tab.DISCOVER)
                } else ctx.emptyState("No favorites yet", "Tap the heart on any game to keep it here.", null, null),
            )
        } else {
            games.forEach { g ->
                val entry = app.library.entry(g.id)
                addView(ctx.hstack(12) {
                    background = StickerDrawable(ctx, fill = p.surface, outline = p.hairline, outlineDp = 2f, shadowDp = 0f, cornerDp = 20f)
                    setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${g.title}. Opens details."
                    setOnClickListener { nav.push(DetailsScreen(g.id)) }
                    addView(MiniScreen(ctx, app.catalog.art(g), artInk(g), 12f, cells = g.art), LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(56)))
                    addView(ctx.vstack(2) {
                        addView(T.body(ctx, g.title, 16f, p.ink, 800).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                        val sub = when {
                            entry == null -> "Not installed"
                            entry.playSeconds > 0 -> "${entry.playSeconds / 60} min played"
                            else -> "Installed, not played yet"
                        }
                        addView(T.soft(ctx, sub, 13f))
                    }, lp(0, WRAP, 1f))
                    if (entry != null && app.catalog.isPlayable(g)) {
                        addView(ctx.iconButton(Icon.PLAY, "Play ${g.title}", fill = p.accent, ink = p.onAccent) { GameLauncher.launch(ctx, g.id) })
                    }
                })
            }
        }

        // Storage: what Tuikio uses on this phone, honestly measured.
        val files = ctx.filesDir
        val saves = SaveArchive.sizeOf(File(files, "games"))
        val cache = SaveArchive.sizeOf(ctx.cacheDir)
        val gamesBytes = app.library.installed.mapNotNull { app.catalog.game(it.gameId) }
            .sumOf { File(ctx.applicationInfo.nativeLibraryDir, "lib${it.runtime.executable}.so").length() }
        addView(ctx.card(dashed = true, shadow = false, gapDp = 8) {
            addView(T.body(ctx, "On this phone", 15f, p.ink, 800))
            addView(T.soft(ctx, "Games ${Formatter.formatShortFileSize(ctx, gamesBytes)} · Saves ${Formatter.formatShortFileSize(ctx, saves)} · Cache ${Formatter.formatShortFileSize(ctx, cache)}"))
            addView(T.soft(ctx, "${Formatter.formatShortFileSize(ctx, files.usableSpace)} free"))
            if (cache > 0) addView(TButton(ctx, "Clear cache", ButtonStyle.SECONDARY).apply {
                setOnClickListener { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; nav.rebuild() }
            }, lp(MATCH, WRAP))
        })
    }
}

class OnboardingScreen(private val done: () -> Unit) : Screen() {
    private var page = 0

    override fun build(ctx: Context): View = ctx.page(withDock = false, gapDp = 20) {
        val pages = listOf(
            Triple("Hi, I'm Kio.", "I carry terminal games to new homes, the way a hermit crab moves into a new shell. Your phone is the next one.", "Next"),
            Triple("Real games, made for touch.", "Each game runs for real in its own little terminal. You get controls made for that game, not a keyboard to wrestle with.", "Next"),
            Triple("Yours, and private.", "No account, no ads, no tracking. Games and saves stay on this phone. Going online only happens when you ask.", "Let's play"),
        )
        val (title, body, cta) = pages[page]
        addView(View(ctx), LinearLayout.LayoutParams(MATCH, ctx.dp(24)))
        addView(android.widget.ImageView(ctx).apply {
            setImageResource(dev.tuikio.app.R.drawable.kio)
            contentDescription = "Kio, a coral hermit crab carrying a tiny terminal as its shell"
        }, LinearLayout.LayoutParams(MATCH, ctx.dp(220)))
        addView(T.title(ctx, title, 30f))
        addView(T.body(ctx, body, 17f))
        addView(ctx.hstack(8) {
            pages.indices.forEach { i ->
                addView(View(ctx).apply {
                    background = StickerDrawable(ctx, fill = if (i == page) Theme.palette.accent else Theme.palette.hairline, outlineDp = 0f, shadowDp = 0f, cornerDp = -1f)
                }, LinearLayout.LayoutParams(ctx.dp(if (i == page) 28 else 10), ctx.dp(10)))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(TButton(ctx, cta).apply {
            setOnClickListener {
                if (page < pages.size - 1) { page++; nav.rebuild() } else done()
            }
        }, lp(MATCH, WRAP))
        if (page < pages.size - 1) addView(TButton(ctx, "Skip", ButtonStyle.GHOST).apply { setOnClickListener { done() } }, lp(MATCH, WRAP))
    }
}
