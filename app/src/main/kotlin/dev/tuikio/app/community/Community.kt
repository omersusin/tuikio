package dev.tuikio.app.community

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.tuikio.app.MainActivity
import dev.tuikio.app.TuikioApp
import dev.tuikio.app.data.AppConfig
import dev.tuikio.community.Attribution
import dev.tuikio.community.GitHubRest
import dev.tuikio.community.Pkce
import dev.tuikio.community.ProofOfWork
import dev.tuikio.community.SubmissionRequest
import dev.tuikio.community.SubmissionStatus
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GitHub sign-in for attributed contributions: OAuth authorization code with PKCE in the
 * system browser, returning to dev.tuikio.app://oauth/callback. The app never holds a client
 * secret; the code is exchanged by Tuikio's submission server (which keeps the secret) and the
 * resulting user token is stored encrypted with an Android Keystore key. Browsing, installing
 * and playing never require any of this.
 */
object GitHubAuth {
    const val REDIRECT = "dev.tuikio.app://oauth/callback"
    private const val KEY_ALIAS = "tuikio-github-token"
    private const val PREFS = "github"

    private var pending: Pkce? = null

    fun isConfigured(context: Context): Boolean {
        val cfg = AppConfig.load(context)
        return cfg.githubClientId != null && cfg.submissionsUrl != null
    }

    fun login(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("login", null)

    fun begin(activity: Activity) {
        val cfg = AppConfig.load(activity)
        val clientId = cfg.githubClientId ?: return
        val pkce = Pkce.create()
        pending = pkce
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending_state", pkce.state).putString("pending_verifier", pkce.verifier).apply()
        val url = pkce.authorizeUrl(clientId, REDIRECT, "public_repo")
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** Handles the browser redirect. Blocking network call: run off the UI thread. */
    fun complete(context: Context, uri: Uri): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expectedState = prefs.getString("pending_state", null) ?: throw IllegalStateException("No sign-in was in progress.")
        val verifier = prefs.getString("pending_verifier", null) ?: throw IllegalStateException("No sign-in was in progress.")
        prefs.edit().remove("pending_state").remove("pending_verifier").apply()
        uri.getQueryParameter("error")?.let { throw IllegalStateException("GitHub sign-in was cancelled ($it).") }
        if (uri.getQueryParameter("state") != expectedState) throw SecurityException("Sign-in response didn't match this request.")
        val code = uri.getQueryParameter("code") ?: throw IllegalStateException("GitHub didn't return a code.")
        val server = AppConfig.load(context).submissionsUrl ?: throw IllegalStateException("No submission server configured.")
        val res = post("$server/v1/github/token", Json.obj("code" to code, "code_verifier" to verifier, "redirect_uri" to REDIRECT))
        val token = res.string("access_token")
        val login = GitHubRest().userLogin(token)
        storeToken(context, token)
        prefs.edit().putString("login", login).apply()
        return login
    }

    fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    fun token(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blob = prefs.getString("token", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun storeToken(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("token", Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    internal fun post(url: String, body: Json, bearer: String? = null): JsonReader {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            outputStream.use { it.write(body.write().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        val json = runCatching { JsonReader(Json.parse(text) as Json.Obj) }.getOrNull()
        if (code !in 200..299) throw IllegalStateException(json?.stringOrNull("error") ?: "Server answered $code.")
        return json ?: throw IllegalStateException("Unexpected server response.")
    }
}

/** Talks to Tuikio's submission server (see backend/). Every call is user-initiated. */
object CommunityClient {
    class NotConfigured : Exception("This build has no community server configured yet.")

    /** Fetches a challenge, solves the proof of work, submits; returns the receipt and state. */
    fun submit(context: Context, request: (SubmissionRequest.Proof) -> SubmissionRequest): Pair<String, SubmissionStatus> {
        val server = AppConfig.load(context).submissionsUrl ?: throw NotConfigured()
        val challenge = get("$server/v1/challenge")
        val proof = SubmissionRequest.Proof(challenge.string("challenge"), ProofOfWork.solve(challenge.string("challenge"), challenge.int("bits")))
        val req = request(proof)
        val problems = req.problems()
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString(" "))
        // Credited submissions carry the GitHub token once so the server can confirm the
        // login (and repository ownership for official uploads). The server doesn't keep it.
        val bearer = if (req.attribution == Attribution.GITHUB) GitHubAuth.token(context) ?: throw IllegalStateException("Sign in to GitHub again to be credited.") else null
        val res = GitHubAuth.post("$server/v1/submissions", req.toJson(), bearer)
        return res.string("receipt_secret") to SubmissionStatus.fromJson(res.obj("status"))
    }

    fun status(context: Context, secret: String): SubmissionStatus {
        val server = AppConfig.load(context).submissionsUrl ?: throw NotConfigured()
        return SubmissionStatus.fromJson(get("$server/v1/submissions/$secret"))
    }

    private fun get(url: String): JsonReader {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Server answered $code.")
        return JsonReader(Json.parse(text) as Json.Obj)
    }
}

/** Receives the GitHub redirect, finishes sign-in off the main thread, returns to the app. */
class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val app = TuikioApp.instance
        if (uri == null) {
            finish()
            return
        }
        app.background({ GitHubAuth.complete(this, uri) }) { r ->
            val msg = r.fold({ "Signed in to GitHub as @$it." }, { "GitHub sign-in failed: ${it.message}" })
            MainActivity.pendingMessage = msg
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }
}
