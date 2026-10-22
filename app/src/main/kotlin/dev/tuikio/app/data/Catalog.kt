package dev.tuikio.app.data

import android.content.Context
import android.os.Build
import dev.tuikio.catalog.CatalogDocument
import dev.tuikio.catalog.CatalogSignature
import dev.tuikio.catalog.DiscoveryEntry
import dev.tuikio.catalog.GameManifest
import dev.tuikio.catalog.Origin
import dev.tuikio.catalog.VerificationStatus
import dev.tuikio.controls.ControlProfile
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The catalog the app shows. A validated snapshot ships inside the APK; when a signed
 * catalog URL and public key are configured, the app can refresh it on request (never in
 * the background), verifying the ECDSA signature before replacing the cached copy.
 */
class CatalogRepo(private val context: Context) {
    @Volatile
    var document: CatalogDocument = loadBundled()
        private set

    private val profiles = HashMap<String, ControlProfile>()
    private val cacheDir = File(context.filesDir, "catalog")

    /** Executable name -> sha256 for this device's ABI, from native/build.py's manifest. */
    val executableHashes: Map<String, String> by lazy { loadNativeHashes() }

    init {
        // A previously synced, signature-checked snapshot wins over the bundled one if newer.
        try {
            val cached = File(cacheDir, "catalog.json")
            if (cached.isFile) {
                val doc = CatalogDocument.parse(cached.readText())
                if (doc.catalogVersion > document.catalogVersion) document = doc
            }
        } catch (e: Exception) {
            File(cacheDir, "catalog.json").delete()
        }
    }

    private fun loadBundled(): CatalogDocument =
        context.assets.open("catalog/catalog.json").use { CatalogDocument.parse(it.readBytes().toString(Charsets.UTF_8)) }

    fun profile(id: String): ControlProfile = synchronized(profiles) {
        profiles.getOrPut(id) {
            context.assets.open("catalog/profiles/$id.json").use { ControlProfile.parse(it.readBytes().toString(Charsets.UTF_8)) }
        }
    }

    val games: List<GameManifest> get() = document.games

    fun game(id: String): GameManifest? = document.game(id)

    /** Discovery entries without a playable port in this build. */
    val discoverOnly: List<DiscoveryEntry>
        get() = document.discovery.filter { it.gameId == null && it.kind != dev.tuikio.catalog.EntryKind.DISCOVERY_LIST && it.kind != dev.tuikio.catalog.EntryKind.PACKAGING_SOURCE }

    /** Whether this build actually contains the game's executable for this device. */
    fun isBundled(game: GameManifest): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "lib${game.runtime.executable}.so").isFile

    /**
     * Playable here: bundled in this APK and at least host-verified. STORE_READY games are fully
     * verified; PORTABLE games are labeled as preview ports until a device test is recorded.
     */
    fun isPlayable(game: GameManifest): Boolean =
        isBundled(game) && game.status >= VerificationStatus.PORTABLE

    fun art(game: GameManifest): String = ART[game.id] ?: DEFAULT_ART

    private fun loadNativeHashes(): Map<String, String> = try {
        val text = context.assets.open("native/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JsonReader.parse(text)
        val abi = Build.SUPPORTED_ABIS.firstOrNull { root.has(it) } ?: return emptyMap()
        val entries = root.obj(abi)
        entries.keys().associate { name -> name to entries.obj(name).string("sha256") }
    } catch (e: Exception) {
        emptyMap()
    }

    sealed class SyncResult {
        object NotConfigured : SyncResult()
        object UpToDate : SyncResult()
        data class Updated(val version: Int) : SyncResult()
        data class Failed(val reason: String) : SyncResult()
    }

    /**
     * Fetches the remote catalog with a conditional request. Blocking: call off the UI thread.
     * Requires both a URL and a signing public key in assets/config.json.
     */
    fun sync(): SyncResult {
        val cfg = AppConfig.load(context)
        val url = cfg.catalogUrl ?: return SyncResult.NotConfigured
        val pem = cfg.catalogPublicKey ?: return SyncResult.NotConfigured
        cacheDir.mkdirs()
        val etagFile = File(cacheDir, "etag")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                if (etagFile.isFile) setRequestProperty("If-None-Match", etagFile.readText().trim())
            }
            when (conn.responseCode) {
                304 -> SyncResult.UpToDate
                200 -> {
                    val body = conn.inputStream.use { readCapped(it, 8 * 1024 * 1024) }
                    val sig = (URL("$url.sig").openConnection() as HttpURLConnection).run {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        inputStream.use { readCapped(it, 4096) }.toString(Charsets.UTF_8)
                    }
                    if (!CatalogSignature.verify(body, sig, CatalogSignature.publicKey(pem))) {
                        return SyncResult.Failed("The downloaded catalog's signature did not verify; kept the current one.")
                    }
                    val doc = CatalogDocument.parse(body.toString(Charsets.UTF_8))
                    if (doc.catalogVersion <= document.catalogVersion) return SyncResult.UpToDate
                    val tmp = File(cacheDir, "catalog.json.tmp")
                    tmp.writeBytes(body)
                    if (!tmp.renameTo(File(cacheDir, "catalog.json"))) throw java.io.IOException("could not store catalog")
                    conn.getHeaderField("ETag")?.let { etagFile.writeText(it) }
                    document = doc
                    SyncResult.Updated(doc.catalogVersion)
                }
                else -> SyncResult.Failed("Catalog server answered ${conn.responseCode}.")
            }
        } catch (e: Exception) {
            SyncResult.Failed("Couldn't reach the catalog: ${e.message}")
        }
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw java.io.IOException("response too large")
        }
        return out.toByteArray()
    }

    companion object {
        fun originLabel(origin: Origin): String = when (origin) {
            Origin.OFFICIAL_UPLOAD -> "Official upload"
            Origin.VERIFIED_PORT -> "Verified port"
            Origin.COMMUNITY_SUBMITTED -> "Community submitted"
            Origin.TUIKIO_ORIGINAL -> "Tuikio original"
        }

        const val DEFAULT_ART = "  ┌──────┐\n  │ >_   │\n  └──────┘"

        /** Small ASCII vignettes drawn from what each game actually shows on screen. */
        val ART = mapOf(
            "lantern-tide" to " ≈≈≈≈≈≈≈≈≈≈ \n  ✦ ·  v  · \n · ▓ K  ✦ · \n  · · ⌂ · · ",
            "2048" to "┌────┬────┐\n│  2 │  4 │\n├────┼────┤\n│ 16 │  8 │\n└────┴────┘",
            "vitetris" to "│   ▓▓   │\n│  ▓▓    │\n│░░   ▒▒ │\n│░░░▒▒▒▒▒│\n└────────┘",
            "robotfindskitten" to "  %     #  \n     #     \n  @    &   \n        ~  ",
            "moon-buggy" to "   .   *   . \n             \n    _o_o_    \n ##### ### ##",
            "freesweep" to " 1 1 2 ▪ ▪ \n 1 ⚑ 3 ▪ ▪ \n 1 1 2 ▪ ▪ \n . . 1 ▪ ▪ ",
            "ninvaders" to " <oo> <oo>  \n  o-o  o-o  \n   <o>  <o> \n      ^     ",
            "greed" to "3 5 1 8 2 9 4\n7 2 @ 6 1 3 5\n4 9 2 . . 7 1\n1 3 8 5 2 6 9",
            "nsnake" to "┌──────────┐\n│ oooo@  ● │\n│          │\n└──────────┘",
        )
    }
}

/** Optional build-time configuration (assets/config.json); everything degrades to "off". */
data class AppConfig(
    val catalogUrl: String?,
    val catalogPublicKey: String?,
    val submissionsUrl: String?,
    val githubClientId: String?,
) {
    companion object {
        fun load(context: Context): AppConfig = try {
            val r = JsonReader.parse(context.assets.open("config.json").use { it.readBytes().toString(Charsets.UTF_8) })
            AppConfig(r.stringOrNull("catalog_url"), r.stringOrNull("catalog_public_key"), r.stringOrNull("submissions_url"), r.stringOrNull("github_client_id"))
        } catch (e: Exception) {
            AppConfig(null, null, null, null)
        }
    }
}

/** Small JSON-file persistence with atomic replace. */
object JsonFiles {
    fun read(file: File): Json? = try {
        if (file.isFile) Json.parse(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun write(file: File, json: Json) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.write(pretty = true))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
