package dev.tuikio.community

import com.sun.net.httpserver.HttpServer
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommunityTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = mutableListOf<String>()

    @AfterTest
    fun stop() = server.stop(0)

    private fun serve(routes: Map<String, Pair<Int, String>>): GitHubRest {
        server.createContext("/") { ex ->
            val key = "${ex.requestMethod} ${ex.requestURI.path}"
            requests += key + " auth=" + (ex.requestHeaders.getFirst("Authorization") ?: "-")
            val (code, body) = routes[key] ?: (404 to """{"message":"Not Found"}""")
            val bytes = body.toByteArray()
            if (code == 403) ex.responseHeaders.add("X-RateLimit-Remaining", "0")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return GitHubRest("http://127.0.0.1:${server.address.port}")
    }

    @Test
    fun readsRepoMetadataForPreviews() {
        val gh = serve(mapOf("GET /repos/dpc/rhex" to (200 to """{"full_name":"dpc/rhex","description":"hex roguelike","license":{"spdx_id":"MPL-2.0"},"pushed_at":"2024-01-01T00:00:00Z","archived":false,"default_branch":"master","html_url":"https://github.com/dpc/rhex"}""")))
        val info = gh.repo("dpc", "rhex")
        assertEquals("dpc/rhex", info.fullName)
        assertEquals("MPL-2.0", info.license)
        assertEquals("master", info.defaultBranch)
    }

    @Test
    fun surfacesApiFailuresAndRateLimits() {
        val gh = serve(mapOf("GET /repos/a/limited" to (403 to """{"message":"API rate limit exceeded"}""")))
        val missing = assertFailsWith<GitHubRest.GitHubException> { gh.repo("a", "missing") }
        assertEquals(404, missing.status)
        val limited = assertFailsWith<GitHubRest.GitHubException> { gh.repo("a", "limited") }
        assertTrue(limited.message!!.contains("rate limit"))
    }

    @Test
    fun opensAUserAuthoredPullRequestFlow() {
        val gh = serve(mapOf(
            "POST /repos/tuikio/catalog/forks" to (202 to """{"full_name":"alice/catalog"}"""),
            "GET /repos/alice/catalog/git/ref/heads/main" to (200 to """{"object":{"sha":"abc123"}}"""),
            "POST /repos/alice/catalog/git/refs" to (201 to "{}"),
            "PUT /repos/alice/catalog/contents/catalog/submissions/x.json" to (201 to "{}"),
            "POST /repos/tuikio/catalog/pulls" to (201 to """{"html_url":"https://github.com/tuikio/catalog/pull/7"}"""),
        ))
        val fork = gh.forkOf("tuikio", "catalog", "user-token")
        val sha = gh.branchSha(fork, "main", "user-token")
        gh.createBranch(fork, "suggest-x", sha, "user-token")
        gh.putFile(fork, "catalog/submissions/x.json", "{}".toByteArray(), "Suggest x", "suggest-x", "user-token")
        val url = gh.createPull("tuikio/catalog", "Suggest x", "alice:suggest-x", "main", "body", "user-token")
        assertEquals("https://github.com/tuikio/catalog/pull/7", url)
        assertTrue(requests.all { it.endsWith("auth=Bearer user-token") }, "every call is made as the user")
    }

    @Test
    fun rejectsPathInjection() {
        val gh = serve(emptyMap())
        assertFailsWith<IllegalArgumentException> { gh.repo("../../admin", "x") }
        assertFailsWith<IllegalArgumentException> { gh.repo("a", "b/../c") }
    }

    @Test
    fun appJwtIsAValidRs256Token() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.private.encoded) + "\n-----END PRIVATE KEY-----"
        val jwt = GitHubRest.appJwt("12345", pem, nowSeconds = 1_800_000_000)
        val (h, p, s) = jwt.split('.')
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(kp.public)
            update("$h.$p".toByteArray())
            verify(Base64.getUrlDecoder().decode(s))
        }
        assertTrue(ok)
        val payload = JsonReader(Json.parse(String(Base64.getUrlDecoder().decode(p))) as Json.Obj)
        assertEquals("12345", payload.string("iss"))
        assertEquals(1_800_000_540, payload.int("exp"))
    }

    @Test
    fun proofOfWorkAndReceipts() {
        val nonce = ProofOfWork.solve("challenge-1", 12)
        assertTrue(ProofOfWork.verify("challenge-1", nonce, 12))
        assertFalse(ProofOfWork.verify("challenge-2", nonce, 12) && ProofOfWork.verify("challenge-1", nonce + 1, 12))
        val secret = Receipts.newSecret()
        assertTrue(Receipts.isWellFormed(secret))
        assertTrue(Receipts.display(secret).matches(Regex("TK-[A-Z0-9]{4}-[A-Z0-9]{4}")))
        assertFalse(Receipts.isWellFormed("../etc/passwd"))
    }

    @Test
    fun pkceChallengeMatchesRfc7636() {
        // RFC 7636 appendix B test vector.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val p = Pkce.create()
        assertEquals(Pkce.challengeFor(p.verifier), p.challenge)
        assertTrue(p.authorizeUrl("cid", "dev.tuikio.app://oauth/callback", "public_repo").contains("code_challenge_method=S256"))
    }

    @Test
    fun submissionValidation() {
        val proof = SubmissionRequest.Proof("c", 1)
        val ok = SubmissionRequest(SubmissionKind.SOURCE, "https://github.com/dpc/rhex", "Rhex", "", Attribution.ANONYMOUS, SubmissionRequest.ANONYMOUS_CREDIT, null, proof)
        assertTrue(ok.problems().isEmpty())
        assertFalse(ok.copy(sourceUrl = "https://127.0.0.1/x").problems().isEmpty())
        assertFalse(ok.copy(credit = "Real Name").problems().isEmpty(), "anonymous can't carry a name")
        assertFalse(ok.copy(attribution = Attribution.GITHUB, credit = "not a login!").problems().isEmpty())
        val parsed = SubmissionRequest.fromJson(JsonReader(Json.parse(ok.toJson().write()) as Json.Obj))
        assertEquals(ok, parsed)
    }

    @Test
    fun publishDraftValidation() {
        val url = "https://github.com/alice/her-game"
        val draft = Json.obj(
            "title" to "Her game", "summary" to "Dodge things.", "category" to "arcade", "license" to "GPL-3.0-or-later",
            "controls_description" to "Arrows", "ownership" to "verified_owner", "source_url" to url,
        )
        val req = SubmissionRequest(SubmissionKind.GAME, url, "Her game", "", Attribution.GITHUB, "alice", draft, SubmissionRequest.Proof("c", 1))
        assertTrue(req.problems().isEmpty(), req.problems().toString())
        val parsed = SubmissionRequest.fromJson(JsonReader(Json.parse(req.toJson().write()) as Json.Obj))
        assertEquals(req, parsed)
        fun with(key: String, v: Any?) = Json.Obj(LinkedHashMap(draft.fields).apply { put(key, Json.of(v)) })
        assertFalse(req.copy(manifestDraft = null).problems().isEmpty(), "game needs details")
        assertFalse(req.copy(kind = SubmissionKind.SOURCE).problems().isEmpty(), "sources carry no game details")
        assertFalse(req.copy(manifestDraft = with("license", "All rights reserved")).problems().isEmpty())
        assertFalse(req.copy(manifestDraft = with("ownership", "trust me")).problems().isEmpty())
        assertFalse(req.copy(manifestDraft = with("source_url", "https://github.com/someone/else")).problems().isEmpty())
        assertFalse(req.copy(manifestDraft = with("executable", "rm -rf /")).problems().isEmpty(), "unknown fields rejected")
        assertFalse(req.copy(manifestDraft = with("summary", 42)).problems().isEmpty())
    }
}
