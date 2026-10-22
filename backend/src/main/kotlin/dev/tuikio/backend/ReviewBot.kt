package dev.tuikio.backend

import dev.tuikio.catalog.ReviewState
import dev.tuikio.community.GitHubRest
import dev.tuikio.community.SubmissionRequest
import dev.tuikio.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Opens one review pull request per submission against the catalog repository, as the
 * Tuikio GitHub App. The PR adds a single JSON file under `community/submissions/`; humans
 * review and merge it. Verified GitHub contributors are credited with a Co-authored-by trailer
 * on the commit, so the contribution shows on their profile without the app ever holding a
 * token that can write to the catalog.
 */
class ReviewBot(
    private val config: Config,
    private val store: Store,
    private val github: GitHubRest,
    private val clock: Clock,
) {
    private val log = Logger.getLogger("tuikio.bot")
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "review-bot").apply { isDaemon = true } }

    fun start() {
        worker.scheduleWithFixedDelay({ runCatching { retryPending() } }, 1, 10, TimeUnit.MINUTES)
    }

    fun stop() {
        worker.shutdownNow()
    }

    fun enqueue(receiptHash: String) {
        worker.execute { open(receiptHash) }
    }

    /** Waits for queued work (tests). */
    fun drain() {
        worker.submit {}.get(30, TimeUnit.SECONDS)
    }

    fun retryPending() {
        store.all().filter { it.state == ReviewState.SUBMITTED && it.prUrl == null && it.attempts < MAX_ATTEMPTS }.forEach { open(it.receiptHash) }
    }

    internal fun open(receiptHash: String) {
        val rec = store.get(receiptHash) ?: return
        if (rec.prUrl != null || rec.state != ReviewState.SUBMITTED) return
        if (!config.botConfigured) {
            store.update(receiptHash) { it.copy(reason = "Queued for review.", updatedAt = clock.now()) }
            return
        }
        try {
            val jwt = GitHubRest.appJwt(config.appId!!, config.appPrivateKeyPem!!, clock.now() / 1000)
            val token = github.installationToken(jwt, config.installationId!!)
            val (owner, name) = config.catalogRepo!!.split('/')
            val repo = github.repo(owner, name, token)
            val base = repo.defaultBranch
            val sha = github.branchSha(repo.fullName, base, token)
            // A fresh branch per attempt keeps retries simple after a partial failure.
            val branch = "community/${rec.kind}-${rec.id}" + if (rec.attempts > 0) "-r${rec.attempts}" else ""
            github.createBranch(repo.fullName, branch, sha, token)
            val day = SimpleDateFormat("yyyy-MM-dd").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(rec.createdAt))
            github.putFile(repo.fullName, "community/submissions/$day-${rec.id}.json", submissionFile(rec).toByteArray(), commitMessage(rec), branch, token)
            val pr = github.createPull(repo.fullName, prTitle(rec), branch, base, prBody(rec), token)
            store.update(receiptHash) { it.copy(state = ReviewState.PENDING_REVIEW, prUrl = pr, reason = null, updatedAt = clock.now()) }
            log.info("opened review PR for submission ${rec.id}")
        } catch (e: Exception) {
            log.warning("review PR for submission ${rec.id} failed: ${e.javaClass.simpleName}")
            store.update(receiptHash) { it.copy(attempts = it.attempts + 1, reason = "Queued for review.", updatedAt = clock.now()) }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5

        /** The public file the PR adds. Only what the contributor saw before sending. */
        fun submissionFile(rec: SubmissionRecord): String = Json.obj(
            "schema" to 1,
            "id" to rec.id,
            "kind" to rec.kind,
            "source_url" to rec.canonicalUrl,
            "title" to rec.title,
            "note" to rec.note,
            "credit" to rec.credit,
            "game" to rec.draft,
        ).write(pretty = true) + "\n"

        fun commitMessage(rec: SubmissionRecord): String {
            val sb = StringBuilder("Community ${rec.kind}: ${rec.displayName}\n\nSubmitted through the Tuikio app (${rec.id}).\n")
            if (rec.coAuthor != null) sb.append("\nCo-authored-by: ").append(rec.coAuthor).append('\n')
            return sb.toString()
        }

        fun prTitle(rec: SubmissionRecord): String = when (rec.kind) {
            "game" -> "Publish game: ${rec.displayName}"
            "correction" -> "Correction: ${rec.displayName}"
            else -> "Suggest source: ${rec.displayName}"
        }

        fun prBody(rec: SubmissionRecord): String {
            val credit = if (rec.attribution == "github") "@${rec.credit.removePrefix("@")} (verified GitHub sign-in)" else SubmissionRequest.ANONYMOUS_CREDIT
            return buildString {
                append("**Source:** ").append(rec.canonicalUrl).append('\n')
                append("**Kind:** ").append(rec.kind).append('\n')
                append("**Credit:** ").append(credit).append('\n')
                append("**Submission:** `").append(rec.id).append("`\n\n")
                append("Title as submitted:\n\n").append(fenced(rec.title)).append('\n')
                if (rec.note.isNotBlank()) append("\nNote from the contributor:\n\n").append(fenced(rec.note)).append('\n')
                if (rec.draft != null) append("\nGame details:\n\n").append(fenced(rec.draft.write(pretty = true), "json")).append('\n')
                append("\n---\n")
                append("Reviewer checklist: the link is a terminal game or a list of them; the license allows redistribution; ")
                append("the author is credited first. Listing is separate from installing: a game only gets an Install button after ")
                append("it is built in an isolated worker and tested on Android.\n")
            }
        }

        /**
         * Wraps untrusted text in a code fence longer than any backtick run inside it, so it
         * renders literally: no markdown, no links, no @mentions pinging people.
         */
        fun fenced(text: String, info: String = "text"): String {
            val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
            val fence = "`".repeat(maxOf(3, longest + 1))
            return "$fence$info\n$text\n$fence"
        }
    }
}
