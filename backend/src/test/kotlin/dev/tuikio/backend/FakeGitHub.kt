package dev.tuikio.backend

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny in-process stand-in for the GitHub REST API and OAuth endpoint. */
class FakeGitHub : AutoCloseable {
    data class Call(val method: String, val path: String, val auth: String?, val body: String)

    val calls = CopyOnWriteArrayList<Call>()
    val files = CopyOnWriteArrayList<Pair<String, JsonReader>>()
    val pulls = CopyOnWriteArrayList<JsonReader>()

    /** token -> (id, login) */
    val users = mutableMapOf("tok-alice" to (101L to "alice"))

    /** repos the given login administers */
    val admins = mutableMapOf("alice" to setOf("alice/her-game"))
    var failPulls = false

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16).apply {
        createContext("/") { ex -> handle(ex) }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val auth = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        val path = ex.requestURI.rawPath
        calls += Call(ex.requestMethod, path, auth, body)
        val (status, json) = route(ex.requestMethod, path, auth, body)
        val bytes = json.write().toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun route(method: String, path: String, auth: String?, body: String): Pair<Int, Json> {
        val user = auth?.let { users[it] }
        return when {
            method == "POST" && path == "/login/oauth/access_token" -> {
                val form = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                if (form["client_secret"] == "csecret" && form["code"] == "good-code-123" && (form["code_verifier"]?.length ?: 0) >= 43) {
                    200 to Json.obj("access_token" to "tok-alice", "token_type" to "bearer", "scope" to "public_repo")
                } else {
                    200 to Json.obj("error" to "bad_verification_code")
                }
            }
            method == "GET" && path == "/user" ->
                if (user == null) 401 to Json.obj("message" to "Bad credentials") else 200 to Json.obj("id" to user.first, "login" to user.second)
            method == "POST" && Regex("^/app/installations/\\d+/access_tokens$").matches(path) ->
                if (auth?.split('.')?.size == 3) 201 to Json.obj("token" to "inst-token") else 401 to Json.obj("message" to "bad jwt")
            method == "GET" && Regex("^/repos/[^/]+/[^/]+$").matches(path) -> {
                val full = path.removePrefix("/repos/")
                val admin = user != null && full in (admins[user.second] ?: emptySet())
                200 to Json.obj(
                    "full_name" to full, "html_url" to "https://github.com/$full", "default_branch" to "main",
                    "description" to "A terminal game", "archived" to false, "license" to Json.obj("spdx_id" to "MIT"),
                    "permissions" to Json.obj("admin" to admin),
                )
            }
            method == "GET" && path.endsWith("/git/ref/heads/main") -> 200 to Json.obj("object" to Json.obj("sha" to "basesha"))
            method == "POST" && path.endsWith("/git/refs") -> 201 to Json.obj("ref" to "ok")
            method == "PUT" && path.contains("/contents/") -> {
                require(auth == "inst-token")
                files += path to JsonReader.parse(body)
                201 to Json.obj("content" to Json.obj())
            }
            method == "POST" && path.endsWith("/pulls") -> {
                require(auth == "inst-token")
                if (failPulls) return 502 to Json.obj("message" to "unavailable")
                pulls += JsonReader.parse(body)
                val full = path.removePrefix("/repos/").removeSuffix("/pulls")
                201 to Json.obj("html_url" to "https://github.com/$full/pull/${pulls.size}")
            }
            else -> 404 to Json.obj("message" to "Not Found")
        }
    }

    fun decodedFile(i: Int): JsonReader = JsonReader.parse(String(Base64.getDecoder().decode(files[i].second.string("content"))))
}
