package dev.tuikio.community

import dev.tuikio.catalog.ReviewState
import dev.tuikio.catalog.SourceCheck
import dev.tuikio.catalog.SourceUrl
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** How a contributor wants to be credited. */
enum class Attribution(val id: String) {
    /** Shown as "Anonymous community contributor"; a bot opens the pull request. */
    ANONYMOUS("anonymous"),

    /** Credited by GitHub login; the contributor's own account opens the pull request when possible. */
    GITHUB("github");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown attribution '$s'")
    }
}

enum class SubmissionKind(val id: String) {
    SOURCE("source"), GAME("game"), CORRECTION("correction");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown kind '$s'")
    }
}

/**
 * What the app sends when suggesting a source or publishing a game. Everything in it may
 * become public in a pull request, and the app shows it to the contributor before sending.
 */
data class SubmissionRequest(
    val kind: SubmissionKind,
    val sourceUrl: String,
    val title: String,
    val note: String,
    val attribution: Attribution,
    /** Public credit text; for anonymous submissions always the fixed anonymous label. */
    val credit: String,
    /** Optional game manifest draft for "publish a game" (validated server-side). */
    val manifestDraft: Json.Obj?,
    /** Hashcash-style proof of work (anti-spam without CAPTCHAs or tracking). */
    val proof: Proof,
) {
    data class Proof(val challenge: String, val nonce: Long)

    fun toJson(): Json = Json.obj(
        "kind" to kind.id,
        "source_url" to sourceUrl,
        "title" to title,
        "note" to note,
        "attribution" to attribution.id,
        "credit" to credit,
        "manifest_draft" to manifestDraft,
        "proof" to Json.obj("challenge" to proof.challenge, "nonce" to proof.nonce.toString()),
    )

    /** Server-side and client-side validation; returns a list of human-readable problems. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (SourceUrl.check(sourceUrl) !is SourceCheck.Ok) out += "The link isn't a public https repository or project page."
        if (title.isBlank() || title.length > 80) out += "Title must be 1–80 characters."
        if (note.length > 1000) out += "Notes are limited to 1000 characters."
        if (attribution == Attribution.ANONYMOUS && credit != ANONYMOUS_CREDIT) out += "Anonymous submissions use the anonymous credit."
        if (attribution == Attribution.GITHUB && !Regex("^@?[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$").matches(credit)) out += "GitHub credit must be a GitHub login."
        if (listOf(title, note, credit).any { s -> s.any { it < ' ' && it != '\n' } }) out += "Text may not contain control characters."
        return out
    }

    companion object {
        const val ANONYMOUS_CREDIT = "Anonymous community contributor"

        fun fromJson(r: JsonReader): SubmissionRequest {
            r.requireOnly("kind", "source_url", "title", "note", "attribution", "credit", "manifest_draft", "proof")
            val proof = r.obj("proof")
            proof.requireOnly("challenge", "nonce")
            return SubmissionRequest(
                kind = SubmissionKind.parse(r.string("kind")),
                sourceUrl = r.string("source_url"),
                title = r.string("title"),
                note = r.stringOrNull("note").orEmpty(),
                attribution = Attribution.parse(r.string("attribution")),
                credit = r.string("credit"),
                manifestDraft = null,
                proof = Proof(proof.string("challenge"), proof.string("nonce").toLongOrNull() ?: r.fail("bad nonce")),
            )
        }
    }
}

/** Status the backend reports for a receipt. */
data class SubmissionStatus(val receipt: String, val state: ReviewState, val prUrl: String?, val reason: String?) {
    fun toJson(): Json = Json.obj("receipt" to receipt, "state" to state.name, "pr_url" to prUrl, "reason" to reason)

    companion object {
        fun fromJson(r: JsonReader) = SubmissionStatus(
            r.string("receipt"), ReviewState.parse(r.string("state")) ?: ReviewState.SUBMITTED, r.stringOrNull("pr_url"), r.stringOrNull("reason"),
        )
    }
}

/**
 * Receipts are random, unguessable and carry no identity. The short form (TK-XXXX-XXXX) is
 * for people; the full secret is what authorizes a status lookup.
 */
object Receipts {
    private val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray()
    private val random = SecureRandom()

    fun newSecret(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.joinToString("") { ALPHABET[(it.toInt() and 0xFF) % ALPHABET.size].toString() }
    }

    fun display(secret: String): String = "TK-" + secret.take(4) + "-" + secret.substring(4, 8)

    fun isWellFormed(secret: String) = secret.length == 16 && secret.all { it in ALPHABET }
}

/**
 * Hashcash proof of work: find a nonce so SHA-256(challenge:nonce) starts with `bits` zero
 * bits. Costs a phone a fraction of a second and makes bulk spam expensive, without CAPTCHAs,
 * fingerprinting or third-party services.
 */
object ProofOfWork {
    fun solve(challenge: String, bits: Int, maxTries: Long = 50_000_000): Long {
        var nonce = 0L
        while (nonce < maxTries) {
            if (verify(challenge, nonce, bits)) return nonce
            nonce++
        }
        throw IllegalStateException("no proof found")
    }

    fun verify(challenge: String, nonce: Long, bits: Int): Boolean {
        val d = MessageDigest.getInstance("SHA-256").digest("$challenge:$nonce".toByteArray())
        var remaining = bits
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (remaining >= 8) {
                if (v != 0) return false
                remaining -= 8
            } else {
                return remaining == 0 || (v ushr (8 - remaining)) == 0
            }
        }
        return true
    }
}

/** RFC 7636 PKCE values for the native-app OAuth flow (RFC 8252). */
class Pkce private constructor(val verifier: String, val challenge: String, val state: String) {
    companion object {
        private val random = SecureRandom()

        private fun urlSafe(bytes: Int): String {
            val b = ByteArray(bytes)
            random.nextBytes(b)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        fun create(): Pkce {
            val verifier = urlSafe(48)
            val challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            return Pkce(verifier, challenge, urlSafe(18))
        }

        fun challengeFor(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }

    fun authorizeUrl(clientId: String, redirectUri: String, scope: String): String =
        "https://github.com/login/oauth/authorize?client_id=${enc(clientId)}&redirect_uri=${enc(redirectUri)}" +
            "&scope=${enc(scope)}&state=${enc(state)}&code_challenge=${enc(challenge)}&code_challenge_method=S256&allow_signup=true"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
