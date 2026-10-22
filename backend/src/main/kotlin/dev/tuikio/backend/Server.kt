package dev.tuikio.backend

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tuikio.catalog.ReviewState
import dev.tuikio.catalog.SourceCheck
import dev.tuikio.catalog.SourceHost
import dev.tuikio.catalog.SourceUrl
import dev.tuikio.community.Attribution
import dev.tuikio.community.GitHubRest
import dev.tuikio.community.Receipts
import dev.tuikio.community.SubmissionKind
import dev.tuikio.community.SubmissionRequest
import dev.tuikio.community.SubmissionStatus
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * Tuikio's submission server.
 *
 *  GET  /v1/challenge                 proof-of-work challenge
 *  POST /v1/submissions               suggest a source / publish a game / send a correction
 *  GET  /v1/submissions/{secret}      status for a receipt
 *  POST /v1/github/token              PKCE code exchange; the client secret stays here
 *  POST /v1/github/webhook            review outcomes from the catalog repository
 *  GET  /v1/preview?url=…             title/description for a source link (SSRF-safe)
 *  GET  /healthz
 *
 * Privacy: no accounts, no cookies, no analytics. Client addresses are only used as
 * per-process, per-day HMAC pseudonyms for rate limiting, in memory. Request bodies, tokens
 * and receipt secrets are never logged.
 */
class Server(
    private val config: Config,
    private val clock: Clock = Clock.SYSTEM,
    private val github: GitHubRest = GitHubRest(config.githubApi, "Tuikio-Server"),
    private val fetcher: SafeFetcher = SafeFetcher(),
) {
    private val log = Logger.getLogger("tuikio.server")
    val store = Store(config.dataDir)
    val bot = ReviewBot(config, store, github, clock)
    private val limiter = RateLimiter(clock)
    private val ipHasher = IpHasher(clock)
    private val challenges = Challenges(clock, config.powBits)
    private val previewCache = ConcurrentHashMap<String, Pair<Long, Json>>()
    private lateinit var http: HttpServer

    val port: Int get() = http.address.port

    class HttpError(val status: Int, message: String) : Exception(message)

    fun start(): Server {
        http = HttpServer.create(InetSocketAddress(config.bind, config.port), 64)
        http.executor = Executors.newFixedThreadPool(16)
        http.createContext("/") { ex -> handle(ex) }
        http.start()
        bot.start()
        log.info("listening on ${config.bind}:$port; $config")
        return this
    }

    fun stop() {
        bot.stop()
        http.stop(0)
        (http.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.rawPath
            val method = ex.requestMethod
            val res: Pair<Int, Json?> = when {
                path == "/healthz" && method == "GET" -> 200 to Json.obj("ok" to true)
                path == "/v1/challenge" && method == "GET" -> challenge(ex)
                path == "/v1/submissions" && method == "POST" -> submit(ex)
                path.startsWith("/v1/submissions/") && method == "GET" -> status(ex, path.removePrefix("/v1/submissions/"))
                path == "/v1/github/token" && method == "POST" -> tokenExchange(ex)
                path == "/v1/github/webhook" && method == "POST" -> webhook(ex)
                path == "/v1/preview" && method == "GET" -> preview(ex)
                else -> throw HttpError(404, "Not found.")
            }
            respond(ex, res.first, res.second)
        } catch (e: HttpError) {
            respond(ex, e.status, Json.obj("error" to e.message))
        } catch (e: JsonReader.InvalidException) {
            respond(ex, 400, Json.obj("error" to "Invalid request."))
        } catch (e: Json.ParseException) {
            respond(ex, 400, Json.obj("error" to "Invalid JSON."))
        } catch (e: Exception) {
            log.warning("internal error: ${e.javaClass.simpleName}")
            respond(ex, 500, Json.obj("error" to "Something went wrong on our side."))
        } finally {
            ex.close()
        }
    }

    // ---- endpoints ----

    private fun challenge(ex: HttpExchange): Pair<Int, Json> {
        limit(ex, "challenge", config.limits.challengesPerHour)
        val (c, expiry) = challenges.issue()
        return 200 to Json.obj("challenge" to c, "bits" to challenges.bits, "expires_at" to expiry)
    }

    private fun submit(ex: HttpExchange): Pair<Int, Json> {
        limit(ex, "submit", config.limits.submissionsPerHour)
        val req = SubmissionRequest.fromJson(JsonReader.parse(body(ex)))
        val problems = req.problems()
        if (problems.isNotEmpty()) throw HttpError(422, problems.joinToString(" "))
        when (val r = challenges.redeem(req.proof.challenge, req.proof.nonce)) {
            is Challenges.Result.Rejected -> throw HttpError(403, r.reason)
            Challenges.Result.Ok -> Unit
        }
        val src = (SourceUrl.check(req.sourceUrl) as SourceCheck.Ok).source

        // Credit is only given to a GitHub account the request can prove it controls. The
        // token is used for these checks and then dropped; it is never stored or logged.
        var coAuthor: String? = null
        var credit = req.credit
        if (req.attribution == Attribution.GITHUB) {
            val token = bearer(ex) ?: throw HttpError(401, "Sign in to GitHub to be credited by name.")
            val user = try {
                github.user(token)
            } catch (e: GitHubRest.GitHubException) {
                throw HttpError(401, "GitHub didn't accept the sign-in; please sign in again.")
            }
            if (!user.login.equals(req.credit.removePrefix("@"), ignoreCase = true)) throw HttpError(403, "Credit must be the signed-in GitHub account.")
            credit = user.login
            coAuthor = "${user.login} <${user.noreplyEmail}>"
            val ownership = (req.manifestDraft?.get("ownership") as? Json.Str)?.value
            if (req.kind == SubmissionKind.GAME && ownership == "verified_owner") {
                if (src.host != SourceHost.GITHUB || !github.canAdmin(src.owner!!, src.repo!!, token)) {
                    throw HttpError(403, "Your GitHub account can't administer that repository, so it can't be listed as an official upload.")
                }
            }
        } else if ((req.manifestDraft?.get("ownership") as? Json.Str)?.value == "verified_owner") {
            throw HttpError(403, "Official uploads need GitHub sign-in to confirm ownership.")
        }

        val secret = Receipts.newSecret()
        val now = clock.now()
        val dedupeKey = when (req.kind) {
            SubmissionKind.SOURCE -> "source:${src.key}"
            SubmissionKind.GAME -> "game:${src.key}"
            SubmissionKind.CORRECTION -> "correction:${src.key}:${sha256Hex(req.note.trim().lowercase())}"
        }
        val rec = SubmissionRecord(
            id = "s" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(6)).lowercase().replace('-', 'x').replace('_', 'y'),
            receiptHash = sha256Hex(secret),
            receiptDisplay = Receipts.display(secret),
            kind = req.kind.id,
            dedupeKey = dedupeKey,
            canonicalUrl = src.canonicalUrl,
            displayName = src.displayName,
            title = req.title.trim(),
            note = req.note.trim(),
            attribution = req.attribution.id,
            credit = credit,
            coAuthor = coAuthor,
            draft = req.manifestDraft,
            state = ReviewState.SUBMITTED,
            prUrl = null,
            reason = null,
            attempts = 0,
            createdAt = now,
            updatedAt = now,
        )
        val holder = store.insertIfNew(rec)
        if (holder != null) {
            // Don't reveal anything about the other submission beyond its public PR.
            throw HttpError(409, "Someone already suggested this and it's in review." + (holder.prUrl?.let { " Follow it at $it" } ?: ""))
        }
        bot.enqueue(rec.receiptHash)
        log.info("accepted ${rec.kind} submission ${rec.id}")
        return 201 to Json.obj("receipt_secret" to secret, "status" to statusOf(rec).toJson())
    }

    private fun status(ex: HttpExchange, secret: String): Pair<Int, Json> {
        limit(ex, "status", config.limits.statusPerHour)
        if (!Receipts.isWellFormed(secret)) throw HttpError(404, "Unknown receipt.")
        val rec = store.get(sha256Hex(secret)) ?: throw HttpError(404, "Unknown receipt.")
        return 200 to statusOf(rec).toJson()
    }

    private fun statusOf(rec: SubmissionRecord) = SubmissionStatus(rec.receiptDisplay, rec.state, rec.prUrl, rec.reason)

    private fun tokenExchange(ex: HttpExchange): Pair<Int, Json> {
        limit(ex, "token", config.limits.tokenExchangesPerHour)
        if (!config.oauthConfigured) throw HttpError(503, "GitHub sign-in isn't set up on this server.")
        val r = JsonReader.parse(body(ex))
        r.requireOnly("code", "code_verifier", "redirect_uri")
        val code = r.string("code")
        val verifier = r.string("code_verifier")
        val redirect = r.string("redirect_uri")
        if (!Regex("^[A-Za-z0-9_-]{8,100}$").matches(code)) throw HttpError(400, "Invalid code.")
        if (!Regex("^[A-Za-z0-9._~-]{43,128}$").matches(verifier)) throw HttpError(400, "Invalid code verifier.")
        if (redirect !in config.redirectUris) throw HttpError(400, "Unknown redirect URI.")
        val form = listOf(
            "client_id" to config.oauthClientId!!, "client_secret" to config.oauthClientSecret!!,
            "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = (URI("${config.githubWeb}/login/oauth/access_token").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "Tuikio-Server")
            outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }
        val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)?.use { readCapped(it, 64 * 1024) }?.toString(Charsets.UTF_8).orEmpty()
        val res = runCatching { JsonReader.parse(text) }.getOrNull() ?: throw HttpError(502, "GitHub sign-in is unavailable right now.")
        val token = res.stringOrNull("access_token") ?: throw HttpError(400, "GitHub didn't accept the sign-in (${res.stringOrNull("error") ?: "unknown error"}).")
        return 200 to Json.obj("access_token" to token, "scope" to res.stringOrNull("scope"))
    }

    private fun webhook(ex: HttpExchange): Pair<Int, Json?> {
        val secret = config.webhookSecret ?: throw HttpError(503, "Webhook isn't configured.")
        val raw = bodyBytes(ex, 1024 * 1024)
        val sig = ex.requestHeaders.getFirst("X-Hub-Signature-256") ?: throw HttpError(401, "Missing signature.")
        val expected = "sha256=" + hmac(secret.toByteArray(), raw).joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(expected.toByteArray(), sig.trim().toByteArray())) throw HttpError(401, "Bad signature.")
        val event = ex.requestHeaders.getFirst("X-GitHub-Event")
        if (event == "ping") return 200 to Json.obj("ok" to true)
        if (event != "pull_request") return 204 to null
        val payload = JsonReader.parse(String(raw, Charsets.UTF_8))
        val pr = payload.obj("pull_request")
        val rec = store.byPrUrl(pr.string("html_url")) ?: return 204 to null
        val label = payload.objOrNull("label")?.stringOrNull("name")
        val next: Pair<ReviewState, String?>? = when (payload.string("action")) {
            "closed" -> if (pr.bool("merged", false)) ReviewState.APPROVED_SOURCE to null else ReviewState.REJECTED to "Closed by reviewers; see the pull request for details."
            "reopened" -> ReviewState.PENDING_REVIEW to null
            "labeled" -> when (label) {
                "changes-requested" -> ReviewState.CHANGES_REQUESTED to "Reviewers asked for changes on the pull request."
                "compatibility-testing" -> ReviewState.COMPATIBILITY_TESTING to null
                "store-ready" -> ReviewState.STORE_READY to null
                "delisted" -> ReviewState.DELISTED to "Removed from the catalog."
                else -> null
            }
            "unlabeled" -> if (label == "changes-requested") ReviewState.PENDING_REVIEW to null else null
            else -> null
        }
        if (next != null) {
            store.update(rec.receiptHash) { cur ->
                if (cur.state.canMoveTo(next.first)) cur.copy(state = next.first, reason = next.second, updatedAt = clock.now()) else cur
            }
        }
        return 204 to null
    }

    private fun preview(ex: HttpExchange): Pair<Int, Json> {
        limit(ex, "preview", config.limits.previewsPerHour)
        val url = query(ex)["url"] ?: throw HttpError(400, "Missing url.")
        val src = when (val c = SourceUrl.check(url)) {
            is SourceCheck.Ok -> c.source
            is SourceCheck.Rejected -> throw HttpError(422, c.reason)
        }
        val now = clock.now()
        previewCache[src.key]?.let { (at, json) -> if (now - at < 3_600_000L) return 200 to json }
        val json = try {
            if (src.host == SourceHost.GITHUB) {
                val repo = github.repo(src.owner!!, src.repo!!)
                Json.obj(
                    "url" to repo.htmlUrl, "title" to repo.fullName, "description" to repo.description.take(300),
                    "license" to repo.license, "pushed_at" to repo.pushedAt, "archived" to repo.archived,
                )
            } else {
                val page = fetcher.preview(src.canonicalUrl)
                Json.obj("url" to page.finalUrl, "title" to page.title, "description" to page.description)
            }
        } catch (e: SafeFetcher.Blocked) {
            throw HttpError(422, e.message ?: "That address isn't allowed.")
        } catch (e: IOException) {
            throw HttpError(502, "Couldn't load a preview for that link.")
        }
        if (previewCache.size > 1000) previewCache.clear()
        previewCache[src.key] = now to json
        return 200 to json
    }

    // ---- helpers ----

    private fun limit(ex: HttpExchange, bucket: String, perHour: Int) {
        if (!limiter.allow(bucket, ipHasher.hash(clientIp(ex)), perHour)) throw HttpError(429, "Too many requests; please try again later.")
    }

    private fun clientIp(ex: HttpExchange): String {
        if (config.trustForwardedFor) {
            ex.requestHeaders.getFirst("X-Forwarded-For")?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ex.remoteAddress.address.hostAddress
    }

    private fun bearer(ex: HttpExchange): String? =
        ex.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun query(ex: HttpExchange): Map<String, String> = (ex.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
        val i = it.indexOf('=')
        java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }

    private fun body(ex: HttpExchange): String {
        val type = ex.requestHeaders.getFirst("Content-Type").orEmpty()
        if (!type.startsWith("application/json")) throw HttpError(415, "Send JSON.")
        return String(bodyBytes(ex, config.limits.maxBodyBytes), Charsets.UTF_8)
    }

    private fun bodyBytes(ex: HttpExchange, max: Int): ByteArray = try {
        ex.requestBody.use { readCapped(it, max) }
    } catch (e: IOException) {
        throw HttpError(413, "Request too large.")
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("too large")
        }
        return out.toByteArray()
    }

    private fun respond(ex: HttpExchange, status: Int, body: Json?) {
        val h = ex.responseHeaders
        h.set("Cache-Control", "no-store")
        h.set("X-Content-Type-Options", "nosniff")
        h.set("Referrer-Policy", "no-referrer")
        if (body == null || status == 204) {
            ex.sendResponseHeaders(status, -1)
            return
        }
        val bytes = body.write().toByteArray(Charsets.UTF_8)
        h.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
