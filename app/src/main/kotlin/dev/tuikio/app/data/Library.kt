package dev.tuikio.app.data

import android.content.Context
import android.content.res.AssetManager
import dev.tuikio.catalog.GameManifest
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import dev.tuikio.runtime.SaveArchive
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What the player has installed, likes and played. Stored as one small JSON file. */
class Library(private val context: Context) {
    data class Entry(
        val gameId: String,
        val installedAt: Long,
        val version: String?,
        var lastPlayed: Long = 0,
        var playSeconds: Long = 0,
        var favorite: Boolean = false,
        var note: String = "",
    )

    private val file = File(context.filesDir, "library.json")
    private val entries = LinkedHashMap<String, Entry>()
    private val favoritesNotInstalled = LinkedHashSet<String>()
    private val listeners = LinkedHashSet<() -> Unit>()

    init {
        load()
    }

    private fun load() {
        val root = JsonFiles.read(file) as? Json.Obj ?: return
        val r = JsonReader(root)
        for (e in r.objects("installed")) {
            val id = e.string("game_id")
            entries[id] = Entry(
                gameId = id,
                installedAt = e.stringOrNull("installed_at")?.toLongOrNull() ?: 0,
                version = e.stringOrNull("version"),
                lastPlayed = e.stringOrNull("last_played")?.toLongOrNull() ?: 0,
                playSeconds = e.stringOrNull("play_seconds")?.toLongOrNull() ?: 0,
                favorite = e.bool("favorite", false),
                note = e.stringOrNull("note").orEmpty(),
            )
        }
        favoritesNotInstalled += r.strings("favorites")
    }

    private fun save() {
        JsonFiles.write(
            file,
            Json.obj(
                "installed" to entries.values.map {
                    Json.obj(
                        "game_id" to it.gameId, "installed_at" to it.installedAt.toString(), "version" to it.version,
                        "last_played" to it.lastPlayed.toString(), "play_seconds" to it.playSeconds.toString(),
                        "favorite" to it.favorite, "note" to it.note,
                    )
                },
                "favorites" to favoritesNotInstalled.toList(),
            ),
        )
        listeners.toList().forEach { it() }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun isInstalled(id: String) = entries.containsKey(id)
    fun entry(id: String): Entry? = entries[id]
    val installed: List<Entry> get() = entries.values.sortedByDescending { maxOf(it.lastPlayed, it.installedAt) }
    val recent: Entry? get() = entries.values.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }

    fun isFavorite(id: String) = entries[id]?.favorite ?: (id in favoritesNotInstalled)

    fun toggleFavorite(id: String) {
        val e = entries[id]
        if (e != null) e.favorite = !e.favorite
        else if (!favoritesNotInstalled.remove(id)) favoritesNotInstalled += id
        save()
    }

    val favorites: List<String> get() = entries.values.filter { it.favorite }.map { it.gameId } + favoritesNotInstalled

    internal fun markInstalled(game: GameManifest) {
        val wasFav = favoritesNotInstalled.remove(game.id)
        entries[game.id] = Entry(game.id, System.currentTimeMillis(), game.upstream.version, favorite = wasFav)
        save()
    }

    internal fun markRemoved(id: String) {
        val e = entries.remove(id)
        if (e?.favorite == true) favoritesNotInstalled += id
        save()
    }

    fun recordSession(id: String, seconds: Long) {
        val e = entries[id] ?: return
        e.lastPlayed = System.currentTimeMillis()
        e.playSeconds += seconds
        save()
    }

    fun homeOf(id: String): File = File(context.filesDir, "games/$id/home")
}

/**
 * Installs games that ship inside this APK. "Installing" here is real work, not a download
 * animation: it verifies the packaged executable against the build manifest's SHA-256,
 * prepares the game's private save directory and copies its data files after checking their
 * hashes. Uninstalling keeps saves unless the player explicitly deletes them.
 */
class Installer(private val context: Context, private val catalog: CatalogRepo, private val library: Library) {
    sealed class Step(val label: String, val fraction: Float) {
        class Checking(label: String, fraction: Float) : Step(label, fraction)
        class Done(label: String) : Step(label, 1f)
    }

    class InstallException(message: String, val code: String) : IOException(message)

    /** Blocking: run off the UI thread. Reports progress through [progress]. */
    fun install(game: GameManifest, progress: (Step) -> Unit) {
        if (!catalog.isBundled(game)) throw InstallException("${game.title} isn't included in this build for your phone's processor.", "TK-ABI")
        val freeBytes = context.filesDir.usableSpace
        if (freeBytes < 8L * 1024 * 1024) throw InstallException("Your phone is almost out of space. Free a few megabytes and try again.", "TK-SPACE")

        progress(Step.Checking("Checking the game file", 0.15f))
        val exe = File(context.applicationInfo.nativeLibraryDir, "lib${game.runtime.executable}.so")
        val expected = catalog.executableHashes["lib${game.runtime.executable}.so"]
        if (expected != null) {
            val actual = sha256(exe)
            if (actual != expected) throw InstallException("The game file didn't match its signed checksum, so it wasn't installed.", "TK-SUM")
        }
        progress(Step.Checking("Preparing a home for saves", 0.45f))
        val home = library.homeOf(game.id)
        home.mkdirs()
        File(home, ".tmp").mkdirs()

        game.dataFiles.forEachIndexed { i, df ->
            progress(Step.Checking("Copying game data", 0.5f + 0.4f * (i + 1) / game.dataFiles.size))
            val bytes = readAsset(context.assets, df.asset)
            if (sha256(bytes) != df.sha256) throw InstallException("A data file for ${game.title} is damaged, so the install stopped.", "TK-DATA")
            val dst = File(home, df.dest)
            dst.parentFile?.mkdirs()
            // An empty data file is a placeholder for something the game writes (a score file,
            // or ".keep" for a directory): create it once, never overwrite what the game saved.
            if (bytes.isEmpty() && dst.exists()) return@forEachIndexed
            val tmp = File(dst.parentFile, dst.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dst)) throw InstallException("Couldn't write game data.", "TK-IO")
        }
        library.markInstalled(game)
        progress(Step.Done("Installed"))
    }

    fun uninstall(game: GameManifest, deleteSaves: Boolean) {
        val home = library.homeOf(game.id)
        if (deleteSaves) {
            home.parentFile?.deleteRecursively()
        } else {
            // Remove only what we installed; everything the game wrote stays.
            game.dataFiles.filter { it.sha256 != EMPTY_SHA256 }.forEach { File(home, it.dest).delete() }
            File(home, ".tmp").deleteRecursively()
        }
        library.markRemoved(game.id)
    }

    fun saveBytes(id: String): Long = SaveArchive.sizeOf(library.homeOf(id))

    companion object {
        fun sha256(file: File): String = file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun readAsset(assets: AssetManager, path: String): ByteArray = assets.open(path).use { it.readBytes() }
    }
}

/** Plain settings, each a single SharedPreferences value. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()
    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()
    /** The terminal bell games ring (a mistake, a hit, an alarm), as a short sound. */
    var gameSounds: Boolean
        get() = p.getBoolean("game_sounds", true)
        set(v) = p.edit().putBoolean("game_sounds", v).apply()
    var leftHanded: Boolean
        get() = p.getBoolean("left_handed", false)
        set(v) = p.edit().putBoolean("left_handed", v).apply()
    var controlScale: Float
        get() = p.getFloat("control_scale", 1f)
        set(v) = p.edit().putFloat("control_scale", v).apply()
    var terminalTextScale: Float
        get() = p.getFloat("terminal_text_scale", 1f)
        set(v) = p.edit().putFloat("terminal_text_scale", v).apply()
    var showControlHints: Boolean
        get() = p.getBoolean("control_hints", true)
        set(v) = p.edit().putBoolean("control_hints", v).apply()
    var showDiscoverOnly: Boolean
        get() = p.getBoolean("show_discover_only", true)
        set(v) = p.edit().putBoolean("show_discover_only", v).apply()
}
