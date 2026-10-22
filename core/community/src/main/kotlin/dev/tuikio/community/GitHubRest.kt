package dev.tuikio.community

import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The few GitHub REST calls Tuikio needs, over plain HttpURLConnection (works on Android and
 * the JVM, no SDK). [baseUrl] is configurable so tests can point it at a local fake server.
 */
class GitHubRest(
    private val baseUrl: String = "https://api.github.com",
    private val userAgent: String = "Tuikio",
) {
    class GitHubException(val status: Int, message: String) : IOException(message)

    data class RepoInfo(
        val fullName: String,
        val description: String,
        val license: String?,
        val pushedAt: String?,
        val archived: Boolean,
        val defaultBranch: String,
        val htmlUrl: String,
    )

    fun repo(owner: String, repo: String, token: String? = null): RepoInfo {
        val r = JsonReader(call("GET", "/repos/${seg(owner)}/${seg(repo)}", token) as Json.Obj)
        return RepoInfo(
            fullName = r.string("full_name"),
            description = r.stringOrNull("description").orEmpty(),
            license = r.objOrNull("license")?.stringOrNull("spdx_id")?.takeIf { it != "NOASSERTION" },
            pushedAt = r.stringOrNull("pushed_at"),
            archived = r.bool("archived", false),
            defaultBranch = r.stringOrNull("default_branch") ?: "main",
            htmlUrl = r.string("html_url"),
        )
    }

    data class User(val id: Long, val login: String) {
        /** GitHub's private commit e-mail, which attributes a Co-authored-by trailer to this account. */
        val noreplyEmail: String get() = "$id+$login@users.noreply.github.com"
    }

    fun user(token: String): User {
        val r = JsonReader(call("GET", "/user", token) as Json.Obj)
        val id = r.int("id").toLong()
        val login = r.string("login")
        require(Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$").matches(login)) { "unexpected login" }
        return User(id, login)
    }

    fun userLogin(token: String): String = user(token).login

    /** Whether [login] can administer the repository (used to verify "this game is mine"). */
    fun canAdmin(owner: String, repo: String, token: String): Boolean = try {
        val r = JsonReader(call("GET", "/repos/${seg(owner)}/${seg(repo)}", token) as Json.Obj)
        r.objOrNull("permissions")?.bool("admin", false) ?: false
    } catch (e: GitHubException) {
        false
    }

    fun forkOf(owner: String, repo: String, token: String): String {
        val r = JsonReader(call("POST", "/repos/${seg(owner)}/${seg(repo)}/forks", token, Json.obj("default_branch_only" to true)) as Json.Obj)
        return r.string("full_name")
    }

    fun branchSha(fullName: String, branch: String, token: String): String {
        val r = JsonReader(call("GET", "/repos/$fullName/git/ref/heads/${seg(branch)}", token) as Json.Obj)
        return r.obj("object").string("sha")
    }

    fun createBranch(fullName: String, branch: String, fromSha: String, token: String) {
        call("POST", "/repos/$fullName/git/refs", token, Json.obj("ref" to "refs/heads/$branch", "sha" to fromSha))
    }

    fun putFile(fullName: String, path: String, content: ByteArray, message: String, branch: String, token: String) {
        call(
            "PUT", "/repos/$fullName/contents/${path.split('/').joinToString("/") { seg(it) }}", token,
            Json.obj("message" to message, "content" to Base64.getEncoder().encodeToString(content), "branch" to branch),
        )
    }

    /** Opens a pull request and returns its html_url. */
    fun createPull(fullName: String, title: String, head: String, base: String, body: String, token: String): String {
        val r = JsonReader(call("POST", "/repos/$fullName/pulls", token, Json.obj("title" to title, "head" to head, "base" to base, "body" to body, "maintainer_can_modify" to true)) as Json.Obj)
        return r.string("html_url")
    }

    /** Exchanges a GitHub App JWT for a short-lived installation token (server side only). */
    fun installationToken(appJwt: String, installationId: Long): String {
        val r = JsonReader(call("POST", "/app/installations/$installationId/access_tokens", null, Json.obj(), bearer = appJwt) as Json.Obj)
        return r.string("token")
    }

    private fun seg(s: String): String {
        require(Regex("^[A-Za-z0-9_.~-]{1,100}$").matches(s)) { "invalid path segment" }
        return s
    }

    private fun call(method: String, path: String, token: String?, body: Json? = null, bearer: String? = null): Json {
        val conn = (java.net.URI(baseUrl + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", userAgent)
            val auth = bearer ?: token
            if (auth != null) setRequestProperty("Authorization", "Bearer $auth")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.write().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { readCapped(it, 2 * 1024 * 1024) }?.toString(Charsets.UTF_8).orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JsonReader(Json.parse(text) as Json.Obj).stringOrNull("message") }.getOrNull() ?: "HTTP $code"
            val limited = code == 403 && conn.getHeaderField("X-RateLimit-Remaining") == "0"
            throw GitHubException(code, if (limited) "GitHub rate limit reached; try again later." else msg)
        }
        return if (text.isBlank()) Json.Obj(emptyMap()) else Json.parse(text)
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("response too large")
        }
        return out.toByteArray()
    }

    companion object {
        /** RS256 JWT for a GitHub App, valid ~9 minutes (GitHub allows at most 10). */
        fun appJwt(appId: String, privateKeyPkcs8Pem: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
            val payload = enc.encodeToString("""{"iat":${nowSeconds - 60},"exp":${nowSeconds + 540},"iss":"$appId"}""".toByteArray())
            val der = Base64.getDecoder().decode(privateKeyPkcs8Pem.lines().filter { !it.startsWith("-----") }.joinToString("").trim())
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update("$header.$payload".toByteArray())
                sign()
            }
            return "$header.$payload.${enc.encodeToString(sig)}"
        }
    }
}
