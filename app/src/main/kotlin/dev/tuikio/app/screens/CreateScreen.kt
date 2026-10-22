package dev.tuikio.app.screens

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import dev.tuikio.app.MainActivity
import dev.tuikio.app.community.CommunityClient
import dev.tuikio.app.community.GitHubAuth
import dev.tuikio.app.data.Sources
import dev.tuikio.app.nav.Screen
import dev.tuikio.app.nav.Tab
import dev.tuikio.app.ui.ButtonStyle
import dev.tuikio.app.ui.ChipKind
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.MATCH
import dev.tuikio.app.ui.Snack
import dev.tuikio.app.ui.StatusChip
import dev.tuikio.app.ui.StickerDrawable
import dev.tuikio.app.ui.StripeProgress
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
import dev.tuikio.catalog.ReviewState
import dev.tuikio.catalog.SourceCheck
import dev.tuikio.catalog.SourceHost
import dev.tuikio.catalog.SourceUrl
import dev.tuikio.community.Attribution
import dev.tuikio.community.GitHubRest
import dev.tuikio.community.Receipts
import dev.tuikio.community.SubmissionKind
import dev.tuikio.community.SubmissionRequest

class CreateScreen : Screen() {
    override val tab = Tab.CREATE

    override fun build(ctx: Context): View = ctx.page {
        val p = Theme.palette
        addView(T.title(ctx, "Create", 28f))
        addView(T.soft(ctx, "Grow the catalog: save game links you find, suggest them to everyone, or publish your own terminal game."))

        addView(ctx.card {
            addView(T.title(ctx, "Add a game source", 19f))
            addView(T.soft(ctx, "Paste a GitHub, GitLab, Codeberg or project link. We only read public details. Nothing is downloaded or run."))
            addView(TButton(ctx, "Add a source", icon = Icon.PLUS).apply { setOnClickListener { nav.push(AddSourceScreen()) } }, lp(MATCH, WRAP))
        })
        addView(ctx.card {
            addView(T.title(ctx, "Publish a game you made", 19f))
            addView(T.soft(ctx, "Link your repository, prove it's yours (or mark it as a port), describe controls, and send it for review."))
            addView(TButton(ctx, "Publish a game", ButtonStyle.MINT, Icon.SPARK).apply { setOnClickListener { nav.push(PublishScreen()) } }, lp(MATCH, WRAP))
        })

        val sources = app.sources.all
        sectionHeader("My sources", if (sources.isNotEmpty()) "${sources.size}" else null)
        if (sources.isEmpty()) addView(T.soft(ctx, "Links you save appear here. They stay on this phone unless you suggest them."))
        sources.forEach { s -> addView(sourceRow(ctx, s)) }

        addView(ctx.card(dashed = true, shadow = false, gapDp = 8) {
            val login = GitHubAuth.login(ctx)
            addView(T.body(ctx, "Creator profile", 15f, p.ink, 800))
            addView(T.soft(ctx, if (login != null) "GitHub connected as @$login" else "Optional. Connect GitHub to be credited by name and open pull requests from your own account."))
            addView(TButton(ctx, "Open creator profile", ButtonStyle.SECONDARY, Icon.PERSON).apply { setOnClickListener { nav.push(CreatorProfileScreen()) } }, lp(MATCH, WRAP))
        })
    }

    private fun sourceRow(ctx: Context, s: Sources.Source): View = ctx.vstack(6) {
        val p = Theme.palette
        background = StickerDrawable(ctx, fill = p.surface, outline = p.hairline, outlineDp = 2f, shadowDp = 0f, cornerDp = 18f)
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        addView(ctx.hstack(8) {
            addView(T.body(ctx, s.displayName, 16f, p.ink, 800), lp(0, WRAP, 1f))
            val sub = s.submission
            addView(
                if (sub == null) StatusChip(ctx, "On this phone", ChipKind.NEUTRAL, true)
                else StatusChip(ctx, stateLabel(sub.state), if (sub.state == ReviewState.APPROVED_SOURCE) ChipKind.PLAYS else ChipKind.PORTING, true),
            )
        })
        addView(T.soft(ctx, s.host + (if (s.license.isNotBlank()) " · ${s.license}" else ""), 13f))
        s.submission?.let { sub ->
            addView(T.body(ctx, "Receipt ${Receipts.display(sub.receipt)} · credited as ${sub.credit}", 13f, p.inkSoft, 700))
            sub.reason?.let { addView(T.soft(ctx, it, 13f)) }
            sub.prUrl?.let { addView(linkRow(ctx, "Pull request", it)) }
        }
        addView(ctx.hstack(8) {
            addView(linkRow(ctx, "Open", s.url), lp(0, WRAP, 1f))
            if (s.submission == null) addView(TButton(ctx, "Suggest", ButtonStyle.SECONDARY).apply {
                minHeight = ctx.dp(44)
                setOnClickListener { nav.push(AddSourceScreen(prefill = s.url, startAtCredit = true)) }
            })
            addView(TButton(ctx, "Remove", ButtonStyle.GHOST).apply {
                minHeight = ctx.dp(44)
                setOnClickListener { app.sources.remove(s.url); nav.rebuild() }
            })
        })
    }

    companion object {
        fun stateLabel(s: ReviewState) = when (s) {
            ReviewState.PERSONAL_SOURCE -> "On this phone"
            ReviewState.SUBMITTED -> "Submitted"
            ReviewState.PENDING_REVIEW -> "In review"
            ReviewState.CHANGES_REQUESTED -> "Changes requested"
            ReviewState.APPROVED_SOURCE -> "Listed"
            ReviewState.COMPATIBILITY_TESTING -> "Being ported"
            ReviewState.STORE_READY -> "Installable"
            ReviewState.REJECTED -> "Not accepted"
            ReviewState.DELISTED -> "Delisted"
        }
    }
}

/**
 * Link → Preview → Save (and optionally suggest) → Credit → Receipt. Saving is local only;
 * suggesting is a separate choice, and the public fields are shown before anything is sent.
 */
class AddSourceScreen(private val prefill: String = "", private val startAtCredit: Boolean = false) : Screen() {
    private var step = if (startAtCredit) 2 else 0
    private var url = prefill
    private var checked: dev.tuikio.catalog.NormalizedSource? = null
    private var info: GitHubRest.RepoInfo? = null
    private var infoError: String? = null
    private var suggest = true
    private var attribution = Attribution.ANONYMOUS
    private var title = ""
    private var note = ""
    private var receipt: String? = null
    private var busy = false

    init {
        if (startAtCredit) checked = (SourceUrl.check(prefill) as? SourceCheck.Ok)?.source
    }

    override fun onBack(): Boolean {
        if (step in 1..2 && !busy) {
            step--
            nav.rebuild()
            return true
        }
        return false
    }

    override fun build(ctx: Context): View = ctx.page(withDock = false, gapDp = 14) {
        val p = Theme.palette
        topBar(nav, "Add a game source")
        addView(ctx.hstack(6) {
            listOf("Link", "Preview", "Credit", "Receipt").forEachIndexed { i, label ->
                addView(View(ctx).apply {
                    background = StickerDrawable(ctx, fill = if (i <= step) p.accent else p.hairline, outlineDp = 0f, shadowDp = 0f, cornerDp = -1f)
                    contentDescription = "$label${if (i == step) ", current step" else ""}"
                }, lp(0, ctx.dp(8), 1f))
            }
        })
        when (step) {
            0 -> stepLink(ctx, this)
            1 -> stepPreview(ctx, this)
            2 -> stepCredit(ctx, this)
            else -> stepReceipt(ctx, this)
        }
    }

    private fun stepLink(ctx: Context, parent: LinearLayout) {
        val field = TField(ctx, "Repository or project link", "https://github.com/owner/game", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        field.input.setText(url)
        parent.addView(field)
        val error = T.body(ctx, "", 14f, Theme.palette.danger, 700).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        parent.addView(error)
        parent.addView(T.soft(ctx, "GitHub first; GitLab, Codeberg, sourcehut, SourceForge and project sites work too. Only public details are read."))
        parent.addView(TButton(ctx, "Check this source").apply {
            setOnClickListener {
                url = field.text.trim()
                when (val c = SourceUrl.check(url)) {
                    is SourceCheck.Rejected -> { error.text = c.reason; error.visibility = View.VISIBLE }
                    is SourceCheck.Ok -> {
                        checked = c.source
                        title = c.source.repo ?: c.source.displayName
                        step = 1
                        fetchPreview(ctx)
                        nav.rebuild()
                    }
                }
            }
        }, lp(MATCH, WRAP))
        parent.addView(android.widget.ImageView(ctx).apply { setImageResource(dev.tuikio.app.R.drawable.kio); contentDescription = null }, LinearLayout.LayoutParams(MATCH, ctx.dp(140)))
    }

    private fun fetchPreview(ctx: Context) {
        val src = checked ?: return
        if (src.host != SourceHost.GITHUB) return
        busy = true
        app.background({ GitHubRest().repo(src.owner!!, src.repo!!) }) { r ->
            busy = false
            r.onSuccess { info = it; title = it.fullName.substringAfter('/') }.onFailure { infoError = it.message }
            if (step == 1) nav.rebuild()
        }
    }

    private fun stepPreview(ctx: Context, parent: LinearLayout) {
        val src = checked ?: return
        val p = Theme.palette
        val known = app.catalog.document.discovery.firstOrNull {
            (SourceUrl.check(it.sourceUrl.replaceFirst("http://", "https://")) as? SourceCheck.Ok)?.source?.key == src.key
        }
        parent.addView(ctx.card(gapDp = 8) {
            addView(ctx.hstack(8) {
                addView(T.body(ctx, src.displayName, 17f, p.ink, 800), lp(0, WRAP, 1f))
                addView(StatusChip(ctx, "Unverified", ChipKind.DISCOVER, true))
            })
            addView(T.mono(ctx, src.canonicalUrl, 12f, p.inkSoft))
            val i = info
            when {
                busy -> addView(T.soft(ctx, "Reading public details from ${src.host.label}…"))
                i != null -> {
                    if (i.description.isNotBlank()) addView(T.body(ctx, i.description, 14f))
                    addView(T.soft(ctx, "License file: ${i.license ?: "not detected"}"))
                    addView(T.soft(ctx, "Last push: ${i.pushedAt?.take(10) ?: "unknown"}${if (i.archived) " · archived" else ""}"))
                }
                infoError != null -> addView(T.soft(ctx, "Couldn't read details ($infoError). You can still save the link."))
                else -> addView(T.soft(ctx, "Details for ${src.host.label} links are read by our review server after you suggest them."))
            }
            addView(T.soft(ctx, if (known != null) "Already in the catalog as \"${known.title}\"." else "Not in the catalog yet."))
        })
        parent.addView(T.soft(ctx, "Saving keeps this link on your phone only. It doesn't make anything installable."))
        val toggle = TButton(ctx, if (suggest) "Also suggest to the community: on" else "Also suggest to the community: off", if (suggest) ButtonStyle.MINT else ButtonStyle.SECONDARY)
        toggle.setOnClickListener { suggest = !suggest; nav.rebuild() }
        parent.addView(toggle, lp(MATCH, WRAP))
        parent.addView(TButton(ctx, if (suggest) "Save, then choose credit" else "Save to My Sources").apply {
            setOnClickListener {
                app.sources.add(
                    Sources.Source(
                        src.canonicalUrl, src.displayName, src.host.label, "", System.currentTimeMillis(),
                        info?.description.orEmpty(), info?.license.orEmpty(), info?.pushedAt.orEmpty(),
                    ),
                )
                if (suggest) {
                    step = 2
                    nav.rebuild()
                } else {
                    Snack.show((ctx as MainActivity).nav.overlay, "Saved to My Sources.")
                    nav.pop()
                }
            }
        }, lp(MATCH, WRAP))
    }

    private fun stepCredit(ctx: Context, parent: LinearLayout) {
        val src = checked ?: return
        val p = Theme.palette
        val login = GitHubAuth.login(ctx)
        parent.addView(T.label(ctx, "How should we credit you?"))
        fun option(mode: Attribution, heading: String, body: String) = ctx.vstack(4) {
            val on = attribution == mode
            background = StickerDrawable(ctx, fill = if (on) p.surface else p.surfaceAlt, outline = if (on) p.outline else p.hairline,
                outlineDp = if (on) 3f else 2f, shadowDp = if (on) Theme.shapes.shadowDp else 0f, cornerDp = 20f)
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "$heading. ${if (on) "Selected" else "Not selected"}. $body"
            setOnClickListener { attribution = mode; nav.rebuild() }
            addView(T.body(ctx, heading, 16f, p.ink, 800))
            addView(T.soft(ctx, body, 13f))
        }
        parent.addView(option(Attribution.ANONYMOUS, "Submit anonymously",
            "Shown as \"${SubmissionRequest.ANONYMOUS_CREDIT}\". No account needed. Our server still sees your network address to stop spam, and keeps it only as a short-lived hash."))
        parent.addView(option(Attribution.GITHUB, "Submit with my GitHub identity",
            if (login != null) "As @$login. We open the pull request from your own fork when we can; if our bot opens it, it says so." else "Sign in through your browser first. We never see your password."))
        if (attribution == Attribution.GITHUB && login == null) {
            parent.addView(TButton(ctx, "Connect GitHub", ButtonStyle.SECONDARY, Icon.BRANCH).apply {
                isEnabled = GitHubAuth.isConfigured(ctx)
                setOnClickListener { GitHubAuth.begin(ctx as MainActivity) }
            }, lp(MATCH, WRAP))
            if (!GitHubAuth.isConfigured(ctx)) parent.addView(T.soft(ctx, "GitHub sign-in isn't set up in this build yet (it needs the project's GitHub App)."))
        }
        val titleField = TField(ctx, "Title shown in the catalog", "Game or project name").apply { input.setText(title) }
        val noteField = TField(ctx, "Note for reviewers (optional, public)", "Why it's a good fit, how to play…").apply { input.setText(note) }
        parent.addView(titleField)
        parent.addView(noteField)
        parent.addView(ctx.card(dashed = true, shadow = false, gapDp = 6) {
            addView(T.label(ctx, "Public in the pull request"))
            addView(T.body(ctx, "Link: ${src.canonicalUrl}", 14f))
            addView(T.body(ctx, "Credited to: ${if (attribution == Attribution.ANONYMOUS) SubmissionRequest.ANONYMOUS_CREDIT else "@" + (login ?: "your-login")}", 14f))
            addView(T.body(ctx, "Plus the title and note above.", 14f))
        })
        val status = T.soft(ctx, "").apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val progress = StripeProgress(ctx).apply { visibility = View.GONE }
        parent.addView(progress, lp(MATCH, WRAP))
        parent.addView(status)
        parent.addView(TButton(ctx, "Send for review").apply {
            isEnabled = !(attribution == Attribution.GITHUB && login == null)
            setOnClickListener {
                title = titleField.text.trim()
                note = noteField.text.trim()
                busy = true
                isEnabled = false
                progress.visibility = View.VISIBLE
                progress.progress = 0.3f
                status.visibility = View.VISIBLE
                status.text = "Doing a quick anti-spam check on your phone…"
                val credit = if (attribution == Attribution.ANONYMOUS) SubmissionRequest.ANONYMOUS_CREDIT else (login ?: "")
                app.background({
                    CommunityClient.submit(ctx) { proof ->
                        SubmissionRequest(SubmissionKind.SOURCE, src.canonicalUrl, title, note, attribution, credit, null, proof)
                    }
                }) { r ->
                    busy = false
                    r.onSuccess { (secret, st) ->
                        val s = app.sources.find(src.canonicalUrl)
                        if (s != null) {
                            s.submission = Sources.Submission(secret, attribution.id, credit, st.state, st.prUrl, st.reason)
                            app.sources.update(s)
                        }
                        receipt = secret
                        step = 3
                        nav.rebuild()
                    }.onFailure { e ->
                        progress.visibility = View.GONE
                        isEnabled = true
                        status.text = if (e is CommunityClient.NotConfigured) "The community server for this build isn't deployed yet. Your link is saved in My Sources; you can also share it as text." else "Couldn't send: ${e.message}"
                        if (e is CommunityClient.NotConfigured) parent.addView(TButton(ctx, "Share as text", ButtonStyle.SECONDARY, Icon.SHARE).apply {
                            setOnClickListener {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, "Terminal game for Tuikio: $title — ${src.canonicalUrl}\n$note"), "Share suggestion"))
                            }
                        }, lp(MATCH, WRAP))
                    }
                }
            }
        }, lp(MATCH, WRAP))
    }

    private fun stepReceipt(ctx: Context, parent: LinearLayout) {
        val secret = receipt ?: return
        val p = Theme.palette
        parent.addView(ctx.card(fill = p.accent2) {
            addView(T.label(ctx, "Receipt", p.onAccent2))
            addView(T.title(ctx, Receipts.display(secret), 26f, p.onAccent2).apply { typeface = Theme.mono(ctx, 800) })
            addView(T.body(ctx, "Kept in My Sources so you can follow the review. It's random and not tied to your name.", 14f, p.onAccent2))
        })
        listOf("Submitted" to true, "Automated checks: links, license, duplicates" to false, "Human review on GitHub" to false, "Listed in Discover" to false)
            .forEach { (label, done) -> parent.addView(T.body(ctx, (if (done) "✓  " else "○  ") + label, 15f, if (done) p.ink else p.inkSoft, 700)) }
        parent.addView(T.soft(ctx, "Being listed is not the same as installable. A game gets Install only after a port passes real device tests."))
        parent.addView(TButton(ctx, "Done").apply { setOnClickListener { nav.pop() } }, lp(MATCH, WRAP))
    }
}
