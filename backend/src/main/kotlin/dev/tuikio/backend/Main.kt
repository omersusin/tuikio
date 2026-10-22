package dev.tuikio.backend

import java.util.logging.Logger

fun main() {
    val config = Config.fromEnv()
    config.dataDir.mkdirs()
    // Submissions may contain contributor notes; keep the directory private to the service user.
    config.dataDir.setReadable(false, false)
    config.dataDir.setReadable(true, true)
    config.dataDir.setExecutable(false, false)
    config.dataDir.setExecutable(true, true)
    val log = Logger.getLogger("tuikio")
    if (!config.botConfigured) log.warning("GitHub App not configured: submissions are stored and queued, no review PRs are opened.")
    if (!config.oauthConfigured) log.warning("OAuth app not configured: GitHub sign-in is unavailable.")
    val server = Server(config).start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
}
