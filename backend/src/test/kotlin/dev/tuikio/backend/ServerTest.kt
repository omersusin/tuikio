package dev.tuikio.backend

import dev.tuikio.catalog.ReviewState
import dev.tuikio.community.Attribution
import dev.tuikio.community.ProofOfWork
import dev.tuikio.community.SubmissionKind
import dev.tuikio.community.SubmissionRequest
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {
    private lateinit var gh: FakeGitHub
    private lateinit var server: Server
    private lateinit var dataDir: File
    private val now = AtomicLong(1_760_000_000_000L)

    private fun config(limits: Config.Limits = Config.Limits(submissionsPerHour = 100)) = Config(
        port = 0,
        dataDir = dataDir,
        githubApi = gh.url,
        githubWeb = gh.url,
        catalogRepo = "tuikio/catalog",
        appId = "12345",
        appPrivateKeyPem = PEM,
        installationId = 7,
        oauthClientId = "cid",
        oauthClientSecret = "csecret",
        webhookSecret = "whsec",
        powBits = 8,
        limits = limits,
    )

    @BeforeEach
    fun setUp() {
        gh = FakeGitHub()
        dataDir = Files.createTempDirectory("tuikio-backend").toFile()
        server = Server(config(), clock = { now.get() }).start()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        gh.close()
        dataDir.deleteRecursively()
    }

    // ---- tiny HTTP client ----

    data class Res(val code: Int, val json: JsonReader?)

    private fun call(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Res {
        val conn = (URI("http://127.0.0.1:${server.port}$path").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                if ("Content-Type" !in headers) setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return Res(code, if (text.isBlank()) null else JsonReader.parse(text))
    }

    private fun proof(): SubmissionRequest.Proof {
        val c = call("GET", "/v1/challenge").json!!
        return SubmissionRequest.Proof(c.string("challenge"), ProofOfWork.solve(c.string("challenge"), c.int("bits")))
    }

    private fun source(url: String = "https://github.com/someone/tetris-tty", attribution: Attribution = Attribution.ANONYMOUS, credit: String = SubmissionRequest.ANONYMOUS_CREDIT, proof: SubmissionRequest.Proof = proof()) =
        SubmissionRequest(SubmissionKind.SOURCE, url, "Tetris in a TTY", "Plays great @everyone ``` <b>hi</b>", attribution, credit, null, proof)

    private fun submit(req: SubmissionRequest, token: String? = null): Res =
        call("POST", "/v1/submissions", req.toJson().write(), if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap())

    // ---- tests ----

    @Test
    fun `anonymous suggestion becomes a bot pull request and a trackable receipt`() {
        val res = submit(source())
        assertEquals(201, res.code)
        val secret = res.json!!.string("receipt_secret")
        assertEquals("SUBMITTED", res.json.obj("status").string("state"))
        server.bot.drain()

        val pr = gh.pulls.single()
        assertEquals("Suggest source: someone/tetris-tty", pr.string("title"))
        val body = pr.string("body")
        assertTrue(SubmissionRequest.ANONYMOUS_CREDIT in body)
        // Untrusted text is fenced with a longer fence than any backtick run inside it.
        assertTrue("````text\nPlays great @everyone ``` <b>hi</b>\n````" in body, body)
        val file = gh.decodedFile(0)
        assertEquals("https://github.com/someone/tetris-tty", file.string("source_url"))
        assertEquals(SubmissionRequest.ANONYMOUS_CREDIT, file.string("credit"))
        assertFalse(gh.files[0].second.string("message").contains("Co-authored-by"))
        // Nothing identifying is stored or published.
        val stored = dataDir.walk().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(secret in stored, "receipt secret must not be stored")
        assertFalse("127.0.0.1" in stored, "client address must not be stored")

        val status = call("GET", "/v1/submissions/$secret")
        assertEquals(200, status.code)
        assertEquals("PENDING_REVIEW", status.json!!.string("state"))
        assertEquals("https://github.com/tuikio/catalog/pull/1", status.json.string("pr_url"))
        assertTrue(status.json.string("receipt").matches(Regex("^TK-[A-Z0-9]{4}-[A-Z0-9]{4}$")))
    }

    @Test
    fun `proof of work is required, single use and expires`() {
        val good = proof()
        val wrongNonce = generateSequence(good.nonce + 1) { it + 1 }.first { !ProofOfWork.verify(good.challenge, it, 8) }
        assertEquals(403, submit(source(proof = SubmissionRequest.Proof(good.challenge, wrongNonce))).code)
        assertEquals(201, submit(source(proof = good)).code)
        assertEquals(403, submit(source(url = "https://github.com/someone/other", proof = good)).code, "replayed challenge")
        val forged = SubmissionRequest.Proof("v1.9999999999.AAAA.forgedmac", 0)
        assertEquals(403, submit(source(url = "https://github.com/someone/third", proof = forged)).code)
        val old = proof()
        now.addAndGet(11 * 60_000L)
        assertEquals(403, submit(source(url = "https://github.com/someone/fourth", proof = old)).code, "expired challenge")
    }

    @Test
    fun `duplicates are refused without revealing the other receipt`() {
        assertEquals(201, submit(source()).code)
        server.bot.drain()
        val dup = submit(source(url = "https://www.github.com/someone/tetris-tty.git"))
        assertEquals(409, dup.code)
        val msg = dup.json!!.string("error")
        assertTrue("pull/1" in msg)
        assertFalse(Regex("[A-Z2-9]{16}").containsMatchIn(msg))
    }

    @Test
    fun `invalid input and unsafe links are rejected`() {
        assertEquals(422, submit(source(url = "https://192.168.1.10/x/y")).code)
        assertEquals(422, submit(source(url = "http://github.com/a/b")).code)
        assertEquals(422, submit(source(credit = "Somebody Famous")).code)
        assertEquals(400, call("POST", "/v1/submissions", """{"kind":"source","extra":1}""").code)
        assertEquals(415, call("POST", "/v1/submissions", "kind=source", mapOf("Content-Type" to "text/plain")).code)
        assertEquals(413, call("POST", "/v1/submissions", "{\"x\":\"" + "a".repeat(70_000) + "\"}").code)
        assertEquals(404, call("GET", "/v1/submissions/../../etc/passwd").code)
        assertEquals(404, call("GET", "/v1/submissions/AAAAAAAAAAAAAAAA").code)
    }

    @Test
    fun `github credit must match the token and official uploads must be administered`() {
        val credited = source(attribution = Attribution.GITHUB, credit = "alice")
        assertEquals(401, submit(credited).code, "no token")
        assertEquals(401, submit(source(attribution = Attribution.GITHUB, credit = "alice"), token = "tok-wrong").code)
        assertEquals(403, submit(source(attribution = Attribution.GITHUB, credit = "bob"), token = "tok-alice").code, "credit for someone else")
        assertEquals(201, submit(source(attribution = Attribution.GITHUB, credit = "alice"), token = "tok-alice").code)
        server.bot.drain()
        assertTrue(gh.files[0].second.string("message").contains("Co-authored-by: alice <101+alice@users.noreply.github.com>"))
        assertTrue("@alice (verified GitHub sign-in)" in gh.pulls[0].string("body"))
        assertTrue(gh.calls.none { it.path.contains("/contents/") && it.auth == "tok-alice" }, "user token never writes")

        fun game(repo: String, ownership: String) = SubmissionRequest(
            SubmissionKind.GAME, "https://github.com/$repo", "My game", "", Attribution.GITHUB, "alice",
            Json.obj(
                "title" to "My game", "summary" to "Dodge things.", "category" to "arcade", "license" to "MIT",
                "controls_description" to "Arrows", "ownership" to ownership, "source_url" to "https://github.com/$repo",
            ),
            proof(),
        )
        assertEquals(403, submit(game("someone/not-hers", "verified_owner"), "tok-alice").code)
        assertEquals(201, submit(game("alice/her-game", "verified_owner"), "tok-alice").code)
        val anonOfficial = game("alice/her-game2", "verified_owner").copy(attribution = Attribution.ANONYMOUS, credit = SubmissionRequest.ANONYMOUS_CREDIT)
        assertEquals(403, submit(anonOfficial).code)
    }

    @Test
    fun `failed pull requests stay queued and are retried`() {
        gh.failPulls = true
        val secret = submit(source()).json!!.string("receipt_secret")
        server.bot.drain()
        assertEquals("SUBMITTED", call("GET", "/v1/submissions/$secret").json!!.string("state"))
        gh.failPulls = false
        server.bot.retryPending()
        assertEquals("PENDING_REVIEW", call("GET", "/v1/submissions/$secret").json!!.string("state"))
    }

    @Test
    fun `rate limits apply per client`() {
        server.stop()
        server = Server(config(Config.Limits(challengesPerHour = 3)), clock = { now.get() }).start()
        repeat(3) { assertEquals(200, call("GET", "/v1/challenge").code) }
        assertEquals(429, call("GET", "/v1/challenge").code)
        now.addAndGet(3_600_000L)
        assertEquals(200, call("GET", "/v1/challenge").code)
    }

    @Test
    fun `token exchange keeps the client secret server side`() {
        val verifier = "v".repeat(50)
        val ok = call("POST", "/v1/github/token", Json.obj("code" to "good-code-123", "code_verifier" to verifier, "redirect_uri" to "dev.tuikio.app://oauth/callback").write())
        assertEquals(200, ok.code)
        assertEquals("tok-alice", ok.json!!.string("access_token"))
        val sent = gh.calls.single { it.path == "/login/oauth/access_token" }.body
        assertTrue("code_verifier=$verifier" in sent && "client_secret=csecret" in sent)
        assertEquals(400, call("POST", "/v1/github/token", Json.obj("code" to "bad-code-999", "code_verifier" to verifier, "redirect_uri" to "dev.tuikio.app://oauth/callback").write()).code)
        assertEquals(400, call("POST", "/v1/github/token", Json.obj("code" to "good-code-123", "code_verifier" to "short", "redirect_uri" to "dev.tuikio.app://oauth/callback").write()).code)
        assertEquals(400, call("POST", "/v1/github/token", Json.obj("code" to "good-code-123", "code_verifier" to verifier, "redirect_uri" to "https://evil.example.com/cb").write()).code)
    }

    @Test
    fun `webhooks need a valid signature and move review state`() {
        val secret = submit(source()).json!!.string("receipt_secret")
        server.bot.drain()
        val prUrl = "https://github.com/tuikio/catalog/pull/1"
        fun hook(payload: String, sig: String? = sign(payload)) =
            call("POST", "/v1/github/webhook", payload, listOfNotNull("X-GitHub-Event" to "pull_request", sig?.let { "X-Hub-Signature-256" to it }).toMap())

        val labeled = """{"action":"labeled","label":{"name":"changes-requested"},"pull_request":{"html_url":"$prUrl","merged":false}}"""
        assertEquals(401, hook(labeled, sig = null).code)
        assertEquals(401, hook(labeled, sig = "sha256=" + "0".repeat(64)).code)
        assertEquals(204, hook(labeled).code)
        assertEquals("CHANGES_REQUESTED", call("GET", "/v1/submissions/$secret").json!!.string("state"))
        assertEquals(204, hook("""{"action":"unlabeled","label":{"name":"changes-requested"},"pull_request":{"html_url":"$prUrl","merged":false}}""").code)
        assertEquals(204, hook("""{"action":"closed","pull_request":{"html_url":"$prUrl","merged":true}}""").code)
        val st = call("GET", "/v1/submissions/$secret").json!!
        assertEquals(ReviewState.APPROVED_SOURCE.name, st.string("state"))
        assertNull(st.stringOrNull("reason"))
    }

    @Test
    fun `preview of a github link uses the API, other private targets are refused`() {
        val res = call("GET", "/v1/preview?url=" + java.net.URLEncoder.encode("https://github.com/someone/tetris-tty", "UTF-8"))
        assertEquals(200, res.code)
        assertEquals("someone/tetris-tty", res.json!!.string("title"))
        assertEquals("MIT", res.json.string("license"))
        assertEquals(422, call("GET", "/v1/preview?url=" + java.net.URLEncoder.encode("https://169.254.169.254/latest", "UTF-8")).code)
        assertEquals(422, call("GET", "/v1/preview?url=" + java.net.URLEncoder.encode("https://metadata.internal/", "UTF-8")).code)
        assertNotNull(call("GET", "/healthz").json)
    }

    private fun sign(payload: String) = "sha256=" + hmac("whsec".toByteArray(), payload.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        val PEM: String by lazy {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        }
    }
}
