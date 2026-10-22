package dev.tuikio.app.screens

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import dev.tuikio.app.nav.Screen
import dev.tuikio.app.ui.ButtonStyle
import dev.tuikio.app.ui.ChipKind
import dev.tuikio.app.ui.MATCH
import dev.tuikio.app.ui.StatusChip
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
import dev.tuikio.catalog.EntryKind
import java.util.Locale

/** Searches playable games and the discovery list together, each clearly labeled. */
class SearchScreen(private val onlyDiscover: Boolean = false) : Screen() {
    private var query = ""
    private var category = "all"

    override fun build(ctx: Context): View = ctx.page(withDock = false, gapDp = 14) {
        topBar(nav, if (onlyDiscover) "Not ported yet" else "Search")
        val field = TField(ctx, "Search games and sources", "Title, genre or author")
        field.input.setText(query)
        field.input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        field.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        addView(field)
        val cats = listOf("all", "arcade", "puzzle", "roguelike", "adventure", "strategy", "cards", "words", "simulation")
        val chips = ctx.hstack(8)
        cats.forEach { c ->
            chips.addView(TButton(ctx, c.replaceFirstChar { it.uppercase() }, if (c == category) ButtonStyle.MINT else ButtonStyle.SECONDARY).apply {
                minHeight = ctx.dp(44)
                setOnClickListener {
                    category = c
                    nav.rebuild()
                }
            })
        }
        addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val results = ctx.vstack(10)
        addView(results)

        fun render() {
            results.removeAllViews()
            val q = query.trim().lowercase(Locale.ROOT)
            fun match(vararg fields: String) = q.isEmpty() || fields.any { it.lowercase(Locale.ROOT).contains(q) }
            if (!onlyDiscover) {
                val games = app.catalog.games.filter {
                    app.catalog.isBundled(it) && (category == "all" || it.category == category) &&
                        match(it.title, it.summary, it.category, it.upstream.authors.joinToString(" "), it.tags.joinToString(" "))
                }
                if (games.isNotEmpty()) {
                    results.addView(T.label(ctx, "Plays on ${Theme.displayName}"))
                    games.forEach { g ->
                        results.addView(ctx.card(gapDp = 6, shadow = false) {
                            isClickable = true
                            isFocusable = true
                            contentDescription = "${g.title}. Opens details."
                            setOnClickListener { nav.push(DetailsScreen(g.id)) }
                            addView(ctx.hstack(10) {
                                addView(T.body(ctx, g.title, 16f, Theme.palette.ink, 800), lp(0, WRAP, 1f))
                                addView(statusChip(ctx, g, small = true))
                            })
                            addView(T.soft(ctx, g.summary, 14f))
                        })
                    }
                }
            }
            val entries = app.catalog.discoverOnly.filter {
                (category == "all" || it.category == category) && match(it.title, it.category, it.kind.id, it.notes)
            }
            results.addView(T.label(ctx, "Discover only · ${entries.size}"))
            if (entries.isEmpty()) results.addView(T.soft(ctx, "Nothing matches. Know a game we're missing? Add it from Create."))
            entries.take(80).forEach { e -> results.addView(discoveryRow(ctx, e) { nav.push(DiscoveryEntryScreen(e.id)) }) }
            if (entries.size > 80) results.addView(T.soft(ctx, "Showing 80 of ${entries.size}. Type to narrow the list."))
        }
        field.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })
        render()
    }
}

/** Details for a link in the discovery list: what we know, what we don't, and why it's not installable yet. */
class DiscoveryEntryScreen(private val entryId: String) : Screen() {
    override fun build(ctx: Context): View = ctx.page(withDock = false) {
        val e = app.catalog.document.discovery.firstOrNull { it.id == entryId }
        topBar(nav, e?.title ?: "Not found")
        if (e == null) return@page
        addView(ctx.hstack(8) {
            addView(StatusChip(ctx, if (e.gameId != null) "Ported" else "Discover only", if (e.gameId != null) ChipKind.PLAYS else ChipKind.DISCOVER))
            addView(T.soft(ctx, e.category.replaceFirstChar { it.uppercase() } + " · " + e.kind.id.replace('_', ' ')), lp(0, WRAP, 1f))
        })
        if (e.gameId != null) {
            addView(TButton(ctx, "Open the playable version").apply { setOnClickListener { nav.push(DetailsScreen(e.gameId!!)) } }, lp(MATCH, WRAP))
        }
        addView(ctx.card(gapDp = 8) {
            addView(T.label(ctx, "Why it can't be installed yet"))
            val why = when (e.kind) {
                EntryKind.ONLINE_SERVICE -> "It's an online service. Tuikio would need an opt-in network mode with clear labels before offering it."
                EntryKind.INTERPRETER -> "It's an interpreter for other games. It needs a reviewed port plus a safe way to bring in story files."
                EntryKind.COLLECTION -> "It's a collection: every game inside needs its own license, build and control check."
                else -> "Nobody has finished checking its license, building it for Android and testing its controls yet."
            }
            addView(T.body(ctx, why, 15f))
            addView(T.soft(ctx, e.notes))
        })
        addView(ctx.card(gapDp = 6) {
            addView(T.label(ctx, "What we know"))
            fun row(k: String, v: String?) = addView(ctx.hstack(8) {
                addView(T.soft(ctx, k), lp(0, WRAP, 0.4f))
                addView(T.body(ctx, v ?: "Not checked yet", 14f, Theme.palette.ink, 700), lp(0, WRAP, 0.6f))
            })
            row("License", e.upstreamLicense?.takeIf { it != "UNKNOWN" })
            row("Added", e.importDate)
            row("Last checked", e.lastChecked)
            row("Status", e.status.name.lowercase().replace('_', ' '))
        })
        addView(linkRow(ctx, "Visit the project", e.sourceUrl))
        addView(T.soft(ctx, "Opening the link only shows the project's page. Nothing is downloaded or run."))
        addView(TButton(ctx, "Suggest a port or correction", ButtonStyle.SECONDARY).apply {
            setOnClickListener { nav.push(AddSourceScreen(prefill = e.sourceUrl)) }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }
}
