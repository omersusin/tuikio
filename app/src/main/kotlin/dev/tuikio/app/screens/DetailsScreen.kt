package dev.tuikio.app.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tuikio.app.MainActivity
import dev.tuikio.app.data.Installer
import dev.tuikio.app.game.GameLauncher
import dev.tuikio.app.nav.Screen
import dev.tuikio.app.ui.ButtonStyle
import dev.tuikio.app.ui.ChipKind
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.MATCH
import dev.tuikio.app.ui.Sheet
import dev.tuikio.app.ui.Snack
import dev.tuikio.app.ui.StatusChip
import dev.tuikio.app.ui.StickerDrawable
import dev.tuikio.app.ui.StripeProgress
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
import dev.tuikio.catalog.GameManifest
import dev.tuikio.catalog.VerificationStatus
import dev.tuikio.controls.ActionOutput
import dev.tuikio.runtime.SaveArchive
import java.io.File

class DetailsScreen(private val gameId: String) : Screen() {
    override val tab = null
    private var installing = false

    override fun build(ctx: Context): View {
        val game = app.catalog.game(gameId) ?: return ctx.page(withDock = false) {
            topBar(nav, "Not found")
            addView(T.body(ctx, "This game is no longer in the catalog."))
        }
        val p = Theme.palette
        return ctx.page(withDock = false, gapDp = 16) {
            addView(ctx.hstack(10) {
                addView(ctx.iconButton(Icon.BACK, "Back") { nav.pop() })
                addView(View(ctx), lp(0, 1, 1f))
                val fav = app.library.isFavorite(game.id)
                addView(ctx.iconButton(Icon.HEART, if (fav) "Remove ${game.title} from favorites" else "Add ${game.title} to favorites",
                    fill = if (fav) p.accent else p.surface, ink = if (fav) p.onAccent else p.ink) {
                    app.library.toggleFavorite(game.id)
                    nav.rebuild()
                })
            })
            addView(FrameLayout(ctx).apply {
                background = StickerDrawable(ctx, fill = p.screen, outline = p.screenEdge, shadowDp = 0f)
                setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                addView(MiniScreen(ctx, app.catalog.art(game), artInk(game)), FrameLayout.LayoutParams(MATCH, ctx.dp(150)))
            })
            addView(ctx.vstack(8) {
                addView(T.title(ctx, game.title, 28f))
                addView(ctx.hstack(8) {
                    addView(statusChip(ctx, game))
                    addView(T.soft(ctx, gameSubtitle(game), 13f), lp(0, WRAP, 1f))
                })
                addView(T.body(ctx, game.description, 15f))
            })

            addView(primaryAction(ctx, game))
            if (game.status == VerificationStatus.PORTABLE && app.catalog.isBundled(game)) {
                addView(ctx.card(fill = p.surfaceAlt, shadow = false, dashed = true, gapDp = 6) {
                    addView(T.body(ctx, "Preview port", 15f, p.ink, 800))
                    addView(T.soft(ctx, "Built from verified source and played end to end on our build machine, but not yet confirmed on a real phone. If something looks off, Settings › Diagnostics can run a quick check."))
                })
            }

            addView(controlsCard(ctx, game))

            addView(ctx.vstack(8) {
                val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
                val rows = listOf(
                    "Runtime" to "Native port · $abi",
                    "Saves" to game.saveNotes.ifBlank { "Stays on this phone" },
                    "License" to game.upstream.license,
                    "Network" to if (game.network == dev.tuikio.catalog.NetworkUse.NONE) "None" else game.network.id,
                    "Screen" to "${game.runtime.minTerminal.cols}×${game.runtime.minTerminal.rows} or larger · ${game.runtime.orientation.id}",
                )
                rows.chunked(2).forEach { pair ->
                    addView(ctx.hstack(8) {
                        pair.forEach { (k, v) -> addView(infoCell(ctx, k, v), lp(0, WRAP, 1f)) }
                    })
                }
            })

            addView(ctx.card(gapDp = 8) {
                addView(T.label(ctx, "Who made this"))
                creditRow(ctx, this, if (game.upstream.authors.size > 1) "Authors" else "Author", game.upstream.authors.joinToString(", "))
                game.port?.let { creditRow(ctx, this, "Android port", it.maintainer) }
                creditRow(ctx, this, "Listing", originText(game))
                addView(linkRow(ctx, "Source code", game.upstream.sourceUrl))
                game.port?.sourceSha256?.let {
                    creditRow(ctx, this, "Built from", "${game.port?.sourceArchive} · sha256 ${it.take(12)}…")
                }
            })

            if (app.library.isInstalled(game.id)) addView(savesCard(ctx, game))
        }
    }

    private fun infoCell(ctx: Context, k: String, v: String) = ctx.vstack(3) {
        background = StickerDrawable(ctx, fill = Theme.palette.surface, outline = Theme.palette.hairline, outlineDp = 2f, shadowDp = 0f, cornerDp = 16f)
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        addView(T.label(ctx, k))
        addView(T.body(ctx, v, 14f, Theme.palette.ink, 700))
    }

    private fun creditRow(ctx: Context, parent: LinearLayout, k: String, v: String) {
        parent.addView(ctx.hstack(8) {
            addView(T.soft(ctx, k), lp(0, WRAP, 0.45f))
            addView(T.body(ctx, v, 14f, Theme.palette.ink, 800).apply { gravity = Gravity.END }, lp(0, WRAP, 0.55f))
        })
    }

    private fun primaryAction(ctx: Context, game: GameManifest): View {
        val holder = ctx.vstack(10)
        val bundled = app.catalog.isBundled(game)
        when {
            !bundled -> {
                holder.addView(TButton(ctx, "Not in this build", ButtonStyle.SECONDARY).apply { isEnabled = false }, lp(MATCH, WRAP))
                holder.addView(T.soft(ctx, "This game's executable isn't included for your phone's processor (${Build.SUPPORTED_ABIS.firstOrNull()})."))
            }
            app.library.isInstalled(game.id) -> {
                holder.addView(TButton(ctx, "Play", icon = Icon.PLAY).apply {
                    setOnClickListener { GameLauncher.launch(ctx, game.id) }
                }, lp(MATCH, WRAP))
            }
            else -> {
                val exe = File(ctx.applicationInfo.nativeLibraryDir, "lib${game.runtime.executable}.so")
                val size = Formatter.formatShortFileSize(ctx, exe.length())
                val button = TButton(ctx, "Install · $size", icon = Icon.DOWNLOAD)
                val progress = StripeProgress(ctx).apply { visibility = View.GONE }
                val status = T.soft(ctx, "").apply {
                    visibility = View.GONE
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                button.setOnClickListener {
                    if (installing) return@setOnClickListener
                    installing = true
                    button.isEnabled = false
                    progress.visibility = View.VISIBLE
                    status.visibility = View.VISIBLE
                    app.background({
                        app.installer.install(game) { step ->
                            app.main.post {
                                progress.progress = step.fraction
                                status.text = step.label
                            }
                        }
                    }) { result ->
                        installing = false
                        val root = (ctx as MainActivity).nav.overlay
                        result.onSuccess {
                            Snack.show(root, "Kio carried ${game.title} home.", "Play") { GameLauncher.launch(ctx, game.id) }
                            nav.rebuild()
                        }.onFailure { e ->
                            val code = (e as? Installer.InstallException)?.code ?: "TK-ERR"
                            status.text = "${e.message} (code $code). Nothing was changed and your saves are untouched."
                            button.isEnabled = true
                            progress.visibility = View.GONE
                        }
                    }
                }
                holder.addView(button, lp(MATCH, WRAP))
                holder.addView(progress, lp(MATCH, WRAP))
                holder.addView(status)
            }
        }
        return holder
    }

    private fun controlsCard(ctx: Context, game: GameManifest): View = ctx.card(gapDp = 10) {
        addView(T.label(ctx, "Controls on touch"))
        val profile = runCatching { app.catalog.profile(game.controls).forGame(game.controlLabels, game.controlHide) }.getOrNull()
        if (profile == null) {
            addView(T.soft(ctx, "Uses the on-screen keyboard."))
            return@card
        }
        addView(T.body(ctx, profile.help, 14f))
        val flow = FlowRow(ctx)
        profile.actions.values.forEach { a ->
            val key = when (val o = a.output) {
                is ActionOutput.Key -> o.key.name.lowercase().replace('_', ' ')
                is ActionOutput.Text -> if (o.text == " ") "space" else o.text
                is ActionOutput.Sequence -> "…"
            }
            flow.addView(StatusChip(ctx, "${a.label} · $key", ChipKind.NEUTRAL, small = true))
        }
        addView(flow)
        addView(T.soft(ctx, "Keyboards and gamepads work too. Settings › Controls has size and left-handed options."))
    }

    private fun savesCard(ctx: Context, game: GameManifest): View = ctx.card(gapDp = 10) {
        val activity = ctx as Activity
        addView(T.label(ctx, "Your saves"))
        val bytes = app.installer.saveBytes(game.id)
        addView(T.body(ctx, if (bytes > 0) "${Formatter.formatShortFileSize(ctx, bytes)} on this phone" else "Nothing saved yet", 15f))
        addView(ctx.hstack(8) {
            addView(TButton(ctx, "Export", ButtonStyle.SECONDARY, Icon.SHARE).apply {
                setOnClickListener { SaveTransfer.export(activity, game.id) }
            }, lp(0, WRAP, 1f))
            addView(TButton(ctx, "Import", ButtonStyle.SECONDARY, Icon.DOWNLOAD).apply {
                setOnClickListener { SaveTransfer.import(activity, game.id) }
            }, lp(0, WRAP, 1f))
        })
        addView(TButton(ctx, "Uninstall…", ButtonStyle.DANGER, Icon.TRASH).apply {
            setOnClickListener { confirmUninstall(ctx, game) }
        }, lp(MATCH, WRAP))
    }

    private fun confirmUninstall(ctx: Context, game: GameManifest) {
        val root = (ctx as MainActivity).nav.overlay
        lateinit var sheet: Sheet
        val content = ctx.vstack(12) {
            addView(T.title(ctx, "Uninstall ${game.title}?", 21f))
            addView(T.soft(ctx, "Your saves stay on this phone unless you choose to delete them too."))
            addView(TButton(ctx, "Uninstall, keep saves").apply {
                setOnClickListener { app.installer.uninstall(game, deleteSaves = false); sheet.dismiss(); nav.rebuild() }
            }, lp(MATCH, WRAP))
            addView(TButton(ctx, "Uninstall and delete saves", ButtonStyle.DANGER).apply {
                setOnClickListener { app.installer.uninstall(game, deleteSaves = true); sheet.dismiss(); nav.rebuild() }
            }, lp(MATCH, WRAP))
            addView(TButton(ctx, "Cancel", ButtonStyle.GHOST).apply { setOnClickListener { sheet.dismiss() } }, lp(MATCH, WRAP))
        }
        sheet = Sheet(root, content).show()
    }
}

/** Export/import saves through the system file picker; no storage permission needed. */
object SaveTransfer {
    const val REQ_EXPORT = 4101
    const val REQ_IMPORT = 4102
    var pendingGame: String? = null

    fun export(activity: Activity, gameId: String) {
        pendingGame = gameId
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "tuikio-$gameId-saves.zip")
        activity.startActivityForResult(i, REQ_EXPORT)
    }

    fun import(activity: Activity, gameId: String) {
        pendingGame = gameId
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
        activity.startActivityForResult(i, REQ_IMPORT)
    }

    /** Called from MainActivity.onActivityResult. */
    fun onResult(activity: MainActivity, requestCode: Int, uri: Uri?) {
        val id = pendingGame ?: return
        uri ?: return
        val app = dev.tuikio.app.TuikioApp.instance
        val home = app.library.homeOf(id)
        app.background({
            when (requestCode) {
                REQ_EXPORT -> activity.contentResolver.openOutputStream(uri)!!.use { SaveArchive.export(home, id, it) }
                REQ_IMPORT -> activity.contentResolver.openInputStream(uri)!!.use { SaveArchive.import(it, id, home) }
            }
        }) { r ->
            val msg = r.fold(
                { if (requestCode == REQ_EXPORT) "Saves exported." else "Saves restored." },
                { "Couldn't ${if (requestCode == REQ_EXPORT) "export" else "import"} saves: ${it.message}" },
            )
            Snack.show(activity.nav.overlay, msg)
            activity.nav.rebuild()
        }
    }
}

/** Simple wrapping row for chips. */
class FlowRow(context: Context) : android.view.ViewGroup(context) {
    private val gap = context.dp(8)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

fun linkRow(ctx: Context, label: String, url: String): View = TextView(ctx).apply {
    text = "$label ↗"
    setTextColor(Theme.palette.link)
    typeface = Theme.body(ctx, 800)
    textSize = 15f
    setPadding(0, ctx.dp(10), 0, ctx.dp(10))
    contentDescription = "$label, opens $url in your browser"
    isClickable = true
    setOnClickListener {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }
}
