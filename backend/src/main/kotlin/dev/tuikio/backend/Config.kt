package dev.tuikio.backend

import java.io.File

/**
 * Server settings. Secrets are only ever read from files named by environment variables
 * (`*_FILE`), never from command-line arguments, so they don't show up in process listings,
 * shell history or CI logs.
 */
data class Config(
    val bind: String = "127.0.0.1",
    val port: Int = 8080,
    val dataDir: File,
    val githubApi: String = "https://api.github.com",
    val githubWeb: String = "https://github.com",
    /** "owner/repo" of the catalog repository the review bot opens pull requests against. */
    val catalogRepo: String? = null,
    val appId: String? = null,
    val appPrivateKeyPem: String? = null,
    val installationId: Long? = null,
    val oauthClientId: String? = null,
    val oauthClientSecret: String? = null,
    val webhookSecret: String? = null,
    val redirectUris: Set<String> = setOf("dev.tuikio.app://oauth/callback"),
    /** Proof-of-work difficulty; 20 bits is well under a second on a phone. */
    val powBits: Int = 20,
    /** Set only when running behind exactly one reverse proxy that appends X-Forwarded-For. */
    val trustForwardedFor: Boolean = false,
    val limits: Limits = Limits(),
) {
    data class Limits(
        val challengesPerHour: Int = 60,
        val submissionsPerHour: Int = 6,
        val statusPerHour: Int = 240,
        val tokenExchangesPerHour: Int = 20,
        val previewsPerHour: Int = 60,
        val maxBodyBytes: Int = 64 * 1024,
    )

    val botConfigured: Boolean get() = catalogRepo != null && appId != null && appPrivateKeyPem != null && installationId != null
    val oauthConfigured: Boolean get() = oauthClientId != null && oauthClientSecret != null

    override fun toString(): String =
        "Config(bind=$bind, port=$port, dataDir=$dataDir, catalogRepo=$catalogRepo, bot=${if (botConfigured) "configured" else "off"}, " +
            "oauth=${if (oauthConfigured) "configured" else "off"}, webhook=${if (webhookSecret != null) "configured" else "off"}, powBits=$powBits)"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun secret(name: String): String? = env["${name}_FILE"]?.let { File(it).readText().trim() }?.takeIf { it.isNotEmpty() }
            return Config(
                bind = env["TUIKIO_BIND"] ?: "127.0.0.1",
                port = env["TUIKIO_PORT"]?.toInt() ?: 8080,
                dataDir = File(env["TUIKIO_DATA_DIR"] ?: "data"),
                githubApi = env["TUIKIO_GITHUB_API"] ?: "https://api.github.com",
                githubWeb = env["TUIKIO_GITHUB_WEB"] ?: "https://github.com",
                catalogRepo = env["TUIKIO_CATALOG_REPO"]?.also { require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(it)) { "TUIKIO_CATALOG_REPO must be owner/repo" } },
                appId = env["TUIKIO_GITHUB_APP_ID"],
                appPrivateKeyPem = secret("TUIKIO_GITHUB_APP_PRIVATE_KEY"),
                installationId = env["TUIKIO_GITHUB_APP_INSTALLATION_ID"]?.toLong(),
                oauthClientId = env["TUIKIO_GITHUB_CLIENT_ID"],
                oauthClientSecret = secret("TUIKIO_GITHUB_CLIENT_SECRET"),
                webhookSecret = secret("TUIKIO_WEBHOOK_SECRET"),
                powBits = env["TUIKIO_POW_BITS"]?.toInt() ?: 20,
                trustForwardedFor = env["TUIKIO_TRUST_FORWARDED_FOR"] == "1",
            )
        }
    }
}
