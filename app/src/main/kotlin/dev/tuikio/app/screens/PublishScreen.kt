package dev.tuikio.app.screens

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import dev.tuikio.app.MainActivity
import dev.tuikio.app.community.CommunityClient
import dev.tuikio.app.community.GitHubAuth
import dev.tuikio.app.nav.Screen
import dev.tuikio.app.ui.ButtonStyle
import dev.tuikio.app.ui.ChipKind
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.MATCH
import dev.tuikio.app.ui.Snack
import dev.tuikio.app.ui.StatusChip
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
import dev.tuikio.catalog.GameManifest
import dev.tuikio.catalog.SourceCheck
import dev.tuikio.catalog.SourceHost
import dev.tuikio.catalog.SourceUrl
import dev.tuikio.catalog.Spdx
import dev.tuikio.community.Attribution
import dev.tuikio.community.GitHubRest
import dev.tuikio.community.Receipts
import dev.tuikio.community.SubmissionKind
import dev.tuikio.community.SubmissionRequest
import dev.tuikio.json.Json

/**
 * Publish a game: rules → repository and ownership → details and controls → review → send.
 * Authorship is never assumed from a username: either GitHub confirms admin rights on the
 * repository, or the listing is marked as a third-party port.
 */
class PublishScreen : Screen() {
    private var step = 0
    private var repoUrl = ""
    private var ownership = "unknown"
    private var gameTitle = ""
    private var summary = ""
    private var category = "arcade"
    private var license = ""
    private var controls = ""
    private var receipt: String? = null

    override fun onBack(): Boolean {
        if (step in 1..3) { step--; nav.rebuild(); return true }
        return false
    }

    override fun build(ctx: Context): View = ctx.page(withDock = false, gapDp = 14) {
        topBar(nav, "Publish a game")
        addView(T.soft(ctx, "Step ${step + 1} of 5"))
        when (step) {
            0 -> rules(ctx, this)
            1 -> repo(ctx, this)
            2 -> details(ctx, this)
            3 -> review(ctx, this)
            else -> done(ctx, this)
        }
    }

    private fun rules(ctx: Context, parent: LinearLayout) {
        parent.addView(ctx.card(gapDp = 10) {
            addView(T.title(ctx, "Before you start", 19f))
            listOf(
                "Publish games you made, or ports you maintain with the original authors credited first.",
                "Every game needs a license that allows redistribution (MIT, BSD, GPL and friends).",
                "Collections list each game, its author and its license separately.",
                "Your source stays where it is; reviewers build it in an isolated worker, never on someone's phone.",
                "A game gets an Install button only after it passes a real Android test.",
            ).forEach { addView(T.body(ctx, "•  $it", 15f)) }
        })
        parent.addView(TButton(ctx, "I understand").apply { setOnClickListener { step = 1; nav.rebuild() } }, lp(MATCH, WRAP))
    }

    private fun repo(ctx: Context, parent: LinearLayout) {
        val field = TField(ctx, "Your game's repository", "https://github.com/you/your-game", InputType.TYPE_TEXT_VARIATION_URI)
        field.input.setText(repoUrl)
        parent.addView(field)
        val status = T.soft(ctx, "").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        parent.addView(status)
        val login = GitHubAuth.login(ctx)
        parent.addView(ctx.card(gapDp = 8) {
            addView(T.label(ctx, "Is this your game?"))
            addView(T.body(ctx, if (login != null) "We can ask GitHub whether @$login can administer this repository." else "Connect GitHub to prove you can administer the repository, or publish it as a port.", 14f))
            addView(TButton(ctx, "Check with GitHub", icon = Icon.BRANCH).apply {
                isEnabled = login != null
                setOnClickListener {
                    repoUrl = field.text.trim()
                    val src = (SourceUrl.check(repoUrl) as? SourceCheck.Ok)?.source
                    if (src == null || src.host != SourceHost.GITHUB) { status.text = "Ownership checks work with GitHub repositories."; return@setOnClickListener }
                    val token = GitHubAuth.token(ctx) ?: run { status.text = "Sign in to GitHub again."; return@setOnClickListener }
                    status.text = "Asking GitHub…"
                    app.background({ GitHubRest().canAdmin(src.owner!!, src.repo!!, token) }) { r ->
                        ownership = if (r.getOrDefault(false)) "verified_owner" else "unverified"
                        status.text = if (ownership == "verified_owner") "Confirmed: you can administer ${src.displayName}. It will be listed as an official upload."
                        else "GitHub didn't confirm admin rights. You can still publish it as a port."
                    }
                }
            }, lp(MATCH, WRAP))
            addView(TButton(ctx, "It's a port of someone else's game", ButtonStyle.SECONDARY).apply {
                setOnClickListener { ownership = "port"; status.text = "Listed as a community port; the original authors are credited first." }
            }, lp(MATCH, WRAP))
        })
        parent.addView(TButton(ctx, "Next").apply {
            setOnClickListener {
                repoUrl = field.text.trim()
                if (SourceUrl.check(repoUrl) !is SourceCheck.Ok) { status.text = "Enter a public https repository link."; return@setOnClickListener }
                if (ownership == "unknown") { status.text = "Choose how to list it: confirm ownership or mark it as a port."; return@setOnClickListener }
                step = 2
                nav.rebuild()
            }
        }, lp(MATCH, WRAP))
    }

    private fun details(ctx: Context, parent: LinearLayout) {
        val t = TField(ctx, "Title", "My terminal game").apply { input.setText(gameTitle) }
        val s = TField(ctx, "One-line summary", "What makes it fun, in a sentence").apply { input.setText(summary) }
        val l = TField(ctx, "License (SPDX id)", "MIT, GPL-3.0-or-later…").apply { input.setText(license) }
        val c = TField(ctx, "Controls", "Arrows move, space fires, q quits", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { input.setText(controls) }
        listOf(t, s, l, c).forEach { parent.addView(it) }
        parent.addView(T.label(ctx, "Category"))
        val cats = GameManifest.CATEGORIES.sorted()
        val flow = FlowRow(ctx)
        cats.forEach { cat ->
            flow.addView(TButton(ctx, cat, if (cat == category) ButtonStyle.MINT else ButtonStyle.SECONDARY).apply {
                minHeight = ctx.dp(44)
                setOnClickListener { gameTitle = t.text; summary = s.text; license = l.text; controls = c.text; category = cat; nav.rebuild() }
            })
        }
        parent.addView(flow)
        val error = T.body(ctx, "", 14f, Theme.palette.danger, 700)
        parent.addView(error)
        parent.addView(TButton(ctx, "Review").apply {
            setOnClickListener {
                gameTitle = t.text.trim(); summary = s.text.trim(); license = l.text.trim(); controls = c.text.trim()
                error.text = when {
                    gameTitle.isEmpty() || gameTitle.length > 60 -> "Title must be 1–60 characters."
                    summary.length > 140 -> "Keep the summary under 140 characters."
                    !Spdx.isValid(license) -> "Use an SPDX license id, like MIT or GPL-3.0-or-later."
                    else -> ""
                }
                if (error.text.isEmpty()) { step = 3; nav.rebuild() }
            }
        }, lp(MATCH, WRAP))
    }

    private fun draft(): Json.Obj = Json.obj(
        "title" to gameTitle, "summary" to summary, "category" to category, "license" to license,
        "controls_description" to controls, "ownership" to ownership, "source_url" to repoUrl,
    )

    private fun review(ctx: Context, parent: LinearLayout) {
        val p = Theme.palette
        parent.addView(ctx.card(gapDp = 8) {
            addView(ctx.hstack(8) {
                addView(T.title(ctx, gameTitle, 20f), lp(0, WRAP, 1f))
                addView(StatusChip(ctx, if (ownership == "verified_owner") "Official upload" else "Community port", if (ownership == "verified_owner") ChipKind.ORIGINAL else ChipKind.PORTING, true))
            })
            addView(T.body(ctx, summary, 15f))
            addView(T.soft(ctx, "$category · $license"))
            addView(T.mono(ctx, repoUrl, 12f, p.inkSoft))
        })
        parent.addView(ctx.vstack(6) {
            background = StickerDrawable(ctx, fill = p.screen, outline = p.screenEdge, shadowDp = 0f, cornerDp = 14f)
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            addView(T.mono(ctx, draft().write(pretty = true), 11f, p.screenInk).apply { setHorizontallyScrolling(false) })
        })
        parent.addView(T.soft(ctx, "Everything above becomes public in the review pull request. Reviewers will build it in an isolated worker and test it on Android before it can be installed."))
        val status = T.soft(ctx, "")
        parent.addView(status)
        val login = GitHubAuth.login(ctx)
        parent.addView(TButton(ctx, "Submit for review").apply {
            setOnClickListener {
                isEnabled = false
                status.text = "Sending…"
                val attribution = if (login != null) Attribution.GITHUB else Attribution.ANONYMOUS
                val credit = login ?: SubmissionRequest.ANONYMOUS_CREDIT
                app.background({
                    CommunityClient.submit(ctx) { proof -> SubmissionRequest(SubmissionKind.GAME, repoUrl, gameTitle, summary, attribution, credit, draft(), proof) }
                }) { r ->
                    r.onSuccess { (secret, _) -> receipt = secret; step = 4; nav.rebuild() }
                        .onFailure { e ->
                            isEnabled = true
                            status.text = if (e is CommunityClient.NotConfigured) "The community server for this build isn't deployed yet, so nothing was sent. Your draft is shown above." else "Couldn't send: ${e.message}"
                        }
                }
            }
        }, lp(MATCH, WRAP))
    }

    private fun done(ctx: Context, parent: LinearLayout) {
        parent.addView(ctx.emptyState("Sent for review", "Receipt ${receipt?.let { Receipts.display(it) } ?: ""}. Reviewers may ask for changes; you'll see them in Create.", "Back to Create") { nav.pop() })
        Snack.show((ctx as MainActivity).nav.overlay, "Thanks for publishing on ${Theme.displayName}!")
    }
}

class CreatorProfileScreen : Screen() {
    override fun build(ctx: Context): View = ctx.page(withDock = false) {
        val prefs = ctx.getSharedPreferences("creator", Context.MODE_PRIVATE)
        topBar(nav, "Creator profile")
        addView(T.soft(ctx, "Public only when you publish or suggest with your GitHub identity. Nothing here is uploaded otherwise."))
        val name = TField(ctx, "Display name", "How you'd like to be credited").apply { input.setText(prefs.getString("name", "")) }
        val bio = TField(ctx, "Short bio (optional)", "What you make").apply { input.setText(prefs.getString("bio", "")) }
        val site = TField(ctx, "Website (optional)", "https://…", InputType.TYPE_TEXT_VARIATION_URI).apply { input.setText(prefs.getString("site", "")) }
        listOf(name, bio, site).forEach { addView(it) }
        addView(TButton(ctx, "Save profile").apply {
            setOnClickListener {
                prefs.edit().putString("name", name.text.trim().take(40)).putString("bio", bio.text.trim().take(200)).putString("site", site.text.trim().take(200)).apply()
                Snack.show((ctx as MainActivity).nav.overlay, "Profile saved on this phone.")
            }
        }, lp(MATCH, WRAP))
        addView(ctx.card(gapDp = 8) {
            val login = GitHubAuth.login(ctx)
            addView(T.label(ctx, "GitHub"))
            if (login != null) {
                addView(T.body(ctx, "Connected as @$login", 15f, Theme.palette.ink, 800))
                addView(T.soft(ctx, "Used only to credit you and open pull requests from your account. Disconnecting deletes the token from this phone."))
                addView(TButton(ctx, "Disconnect GitHub", ButtonStyle.DANGER).apply {
                    setOnClickListener { GitHubAuth.signOut(ctx); nav.rebuild() }
                }, lp(MATCH, WRAP))
            } else {
                addView(T.soft(ctx, "Optional. Sign in through your browser with PKCE; Tuikio never sees your password."))
                addView(TButton(ctx, "Connect GitHub", ButtonStyle.SECONDARY, Icon.BRANCH).apply {
                    isEnabled = GitHubAuth.isConfigured(ctx)
                    setOnClickListener { GitHubAuth.begin(ctx as MainActivity) }
                }, lp(MATCH, WRAP))
                if (!GitHubAuth.isConfigured(ctx)) addView(T.soft(ctx, "Not available in this build yet: the project's GitHub App and server aren't configured."))
            }
        })
        val submitted = app.sources.all.filter { it.submission != null }
        sectionHeader("Contributions", "${submitted.size}")
        if (submitted.isEmpty()) addView(T.soft(ctx, "Suggestions and games you send for review show up here."))
        submitted.forEach { s ->
            addView(ctx.hstack(8) {
                addView(T.body(ctx, s.displayName, 15f, Theme.palette.ink, 700), lp(0, WRAP, 1f))
                addView(StatusChip(ctx, CreateScreen.stateLabel(s.submission!!.state), ChipKind.NEUTRAL, true))
            })
        }
    }
}
