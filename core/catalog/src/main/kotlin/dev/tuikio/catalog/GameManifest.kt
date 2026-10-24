package dev.tuikio.catalog

import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader

/** How far a game has come, from a link someone found to something you can install. */
enum class VerificationStatus {
    /** A link worth looking at. Nothing checked. */
    CANDIDATE,

    /** Someone is assessing license, build and controls. */
    INVESTIGATING,

    /** Builds for Android ABIs and plays in the host-side runtime tests; no device test yet. */
    PORTABLE,

    /** Played end to end on a real Android device or emulator, with evidence recorded. */
    ANDROID_TESTED,

    /** Tested, licensed, reviewed: may show an Install button in the public catalog. */
    STORE_READY;

    val installable: Boolean get() = this == STORE_READY

    companion object {
        fun parse(s: String): VerificationStatus =
            values().firstOrNull { it.name == s } ?: throw JsonReader.InvalidException("unknown status '$s'")
    }
}

/** Who stands behind a listing. Shown on every details page. */
enum class Origin(val id: String) {
    OFFICIAL_UPLOAD("official_upload"),
    VERIFIED_PORT("verified_port"),
    COMMUNITY_SUBMITTED("community_submitted"),
    TUIKIO_ORIGINAL("tuikio_original");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown origin '$s'")
    }
}

enum class Orientation(val id: String) {
    PORTRAIT("portrait"), LANDSCAPE("landscape"), ANY("any");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown orientation '$s'")
    }
}

enum class NetworkUse(val id: String) {
    NONE("none"), OPTIONAL("optional"), REQUIRED("required");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown network use '$s'")
    }
}

data class Upstream(
    val authors: List<String>,
    val license: String,
    val sourceUrl: String,
    val version: String?,
)

data class PortInfo(
    val maintainer: String,
    val recipe: String,
    val sourceArchive: String?,
    val sourceSha256: String?,
    /** File under LICENSES/ (assets/licenses/) with the game's license text; games built from
     *  one source tree share it. Defaults to `<id>.txt`. */
    val licenseFile: String? = null,
)

data class TerminalSize(val cols: Int, val rows: Int)

data class RuntimeSpec(
    val backend: String,
    /** Name of the packaged executable; resolved to `lib<executable>.so` in nativeLibraryDir. */
    val executable: String,
    val args: List<String>,
    val env: Map<String, String>,
    val minTerminal: TerminalSize,
    val preferredTerminal: TerminalSize,
    val orientation: Orientation,
    /** Word wrap for prose printed line by line (manifest "wrap": "words"); see TerminalEmulator.wordWrap. */
    val wrapWords: Boolean = false,
    /** Played by typing (manifest "input": "typing"): the keyboard comes up when the game starts. */
    val typing: Boolean = false,
)

data class DataFile(val asset: String, val dest: String, val sha256: String)

/**
 * A picture of the game for its card: a few rows of its real screen, captured from the game
 * (tools/catalog/capture_art.py). [fg] and [bg] hold a code per cell: '.' is the screen's own
 * ink (fg) or nothing (bg), any other character indexes [palette] by its place in [CODES].
 * Palette entries: "d" default, "s"/"i" the screen's background/ink, "a0".."a15" the theme's
 * ANSI colours, "x16".."x255" xterm's, "#rrggbb" exact.
 */
data class GameArt(val rows: List<String>, val fg: List<String>, val bg: List<String>, val palette: List<String>) {
    val cols: Int get() = rows.firstOrNull()?.let { it.codePointCount(0, it.length) } ?: 0

    companion object {
        const val CODES = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val COLOR = Regex("^(d|s|i|a(1[0-5]|[0-9])|x(1[6-9]|[2-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])|#[0-9a-f]{6})$")
    }
}

/**
 * A key the game uses that its control profile has no button for ("b" places a bomb, "i" opens
 * the inventory): shown on the game screen as a button with [label]. Sends [text], or the named
 * [key] (a TermKey name, from [NAMES]).
 */
data class GameKey(val label: String, val text: String? = null, val key: String? = null) {
    companion object {
        val NAMES = setOf(
            "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PAGE_UP", "PAGE_DOWN", "INSERT", "DELETE",
            "ENTER", "ESCAPE", "TAB", "BACKSPACE",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
        )
    }
}

data class Verification(val hostTested: String?, val androidTested: String?, val notes: String)

data class GameManifest(
    val id: String,
    val title: String,
    val summary: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    val players: String,
    val origin: Origin,
    val upstream: Upstream,
    val port: PortInfo?,
    val runtime: RuntimeSpec,
    val controls: String,
    /** Profile actions renamed for this game (action id to label): "select" reads "Reveal". */
    val controlLabels: Map<String, String> = emptyMap(),
    /** Profile actions this game has no use for: their buttons are left out. */
    val controlHide: Set<String> = emptySet(),
    /** The game's own keys beyond its control profile, in the order they're shown. */
    val keys: List<GameKey> = emptyList(),
    /** Its card picture, from its real screen; null draws the generic one. */
    val art: GameArt? = null,
    val savePaths: List<String>,
    val saveNotes: String,
    val dataFiles: List<DataFile>,
    val network: NetworkUse,
    val status: VerificationStatus,
    val verification: Verification,
) {
    /** The license text under LICENSES/ (bundled as assets/licenses/) that covers this game. */
    val licenseFile: String
        get() = if (origin == Origin.TUIKIO_ORIGINAL) "tuikio-GPL-3.0.txt" else port?.licenseFile ?: "$id.txt"

    companion object {
        const val SCHEMA_VERSION = 1
        val ID = Regex("^[a-z0-9][a-z0-9-]{1,47}$")
        val EXECUTABLE = Regex("^game_[a-z0-9_]{1,40}$")
        val CATEGORIES = setOf("arcade", "puzzle", "roguelike", "adventure", "strategy", "cards", "words", "simulation")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val LICENSE_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}\\.txt$")
        private val ENV_KEY = Regex("^[A-Z][A-Z0-9_]{0,31}$")

        /** Variables the runtime owns; a manifest may never override them. */
        private val RESERVED_ENV = setOf(
            "HOME", "PATH", "TERM", "TERMINFO", "TERMINFO_DIRS", "TMPDIR", "SHELL", "USER", "LOGNAME",
            "LANG", "LC_ALL", "COLORTERM", "ESCDELAY", "PWD",
        )

        fun parse(text: String): GameManifest = fromJson(JsonReader.parse(text))

        fun fromJson(r: JsonReader): GameManifest {
            r.requireOnly(
                "schema_version", "id", "title", "summary", "description", "category", "tags", "players",
                "origin", "upstream", "port", "runtime", "controls", "control_labels", "control_hide", "keys", "art", "saves", "data_files", "network",
                "status", "verification",
            )
            val version = r.int("schema_version")
            if (version != SCHEMA_VERSION) r.fail("unsupported schema_version $version")
            val id = r.string("id")
            if (!ID.matches(id)) r.fail("id '$id' must match ${ID.pattern}")
            val category = r.string("category")
            if (category !in CATEGORIES) r.fail("unknown category '$category'")

            val up = r.obj("upstream")
            up.requireOnly("authors", "license", "source_url", "version")
            val upstream = Upstream(
                authors = up.strings("authors").also { if (it.isEmpty()) up.fail("at least one author is required") },
                license = Spdx.requireValid(up.string("license")),
                sourceUrl = up.string("source_url").also { SourceUrl.requireDisplayable(it) },
                version = up.stringOrNull("version"),
            )

            val port = r.objOrNull("port")?.let { p ->
                p.requireOnly("maintainer", "recipe", "source_archive", "source_sha256", "license_file")
                PortInfo(
                    maintainer = p.string("maintainer"),
                    recipe = p.string("recipe"),
                    sourceArchive = p.stringOrNull("source_archive"),
                    sourceSha256 = p.stringOrNull("source_sha256")?.also {
                        if (!SHA256.matches(it)) p.fail("source_sha256 must be lowercase hex SHA-256")
                    },
                    licenseFile = p.stringOrNull("license_file")?.also {
                        if (!LICENSE_FILE.matches(it)) p.fail("license_file must be a plain .txt file name")
                    },
                )
            }

            val rt = r.obj("runtime")
            rt.requireOnly("backend", "executable", "args", "env", "terminal", "orientation", "wrap", "input")
            val wrap = rt.stringOrNull("wrap")
            if (wrap != null && wrap != "words") rt.fail("unknown wrap '$wrap'")
            val input = rt.stringOrNull("input")
            if (input != null && input != "typing") rt.fail("unknown input '$input'")
            val backend = rt.string("backend")
            if (backend != "native_pty") rt.fail("unsupported backend '$backend'")
            val exe = rt.string("executable")
            if (!EXECUTABLE.matches(exe)) rt.fail("executable '$exe' must match ${EXECUTABLE.pattern}")
            val args = rt.strings("args")
            if (args.size > 16 || args.any { it.length > 64 || it.any { c -> c < ' ' } }) rt.fail("args too long or contain control characters")
            val env = rt.stringMap("env")
            for ((k, v) in env) {
                if (!ENV_KEY.matches(k) || k in RESERVED_ENV || k.startsWith("LD_")) rt.fail("environment variable '$k' is not allowed")
                if (v.length > 128 || v.any { it < ' ' }) rt.fail("environment value for '$k' is invalid")
            }
            val term = rt.obj("terminal")
            term.requireOnly("min", "preferred")
            fun size(key: String): TerminalSize {
                val v = term.ints(key)
                if (v.size != 2 || v[0] !in 20..400 || v[1] !in 8..200) term.fail("$key must be [cols, rows] within 20x8..400x200")
                return TerminalSize(v[0], v[1])
            }
            val min = size("min")
            val preferred = size("preferred")
            if (preferred.cols < min.cols || preferred.rows < min.rows) term.fail("preferred size is smaller than min")

            val controlLabels = r.stringMap("control_labels")
            if (controlLabels.values.any { it.isBlank() || it.length > 14 }) r.fail("control_labels must be 1..14 characters")
            val controlHide = r.strings("control_hide").toSet()
            val keys = r.objects("keys").map { k ->
                k.requireOnly("label", "text", "key")
                val label = k.string("label")
                if (label.isBlank() || label.length > 14) k.fail("label must be 1..14 characters")
                if (k.has("text") == k.has("key")) k.fail("a key needs exactly one of text or key")
                val text = k.stringOrNull("text")?.also { t ->
                    if (t.isEmpty() || t.length > 16 || t.any { it < ' ' && it != '\r' }) k.fail("text must be 1..16 printable characters")
                }
                val key = k.stringOrNull("key")?.also { if (it !in GameKey.NAMES) k.fail("unknown key '$it'") }
                GameKey(label, text, key)
            }
            if (keys.size > 16) r.fail("at most 16 keys")
            if (keys.map { it.text ?: it.key!! }.toSet().size != keys.size) r.fail("keys must send different things")

            val art = r.objOrNull("art")?.let { a ->
                a.requireOnly("rows", "fg", "bg", "palette")
                val rows = a.strings("rows")
                val fg = a.strings("fg")
                val bg = a.strings("bg")
                val palette = a.strings("palette")
                if (rows.isEmpty() || rows.size > 16 || fg.size != rows.size || bg.size != rows.size) a.fail("art needs 1..16 rows, with fg and bg for each")
                val cols = rows[0].codePointCount(0, rows[0].length)
                if (cols !in 1..48) a.fail("art rows must be 1..48 columns")
                if (palette.size > GameArt.CODES.length || palette.any { !GameArt.COLOR.matches(it) }) a.fail("art palette has an invalid colour")
                val codes = GameArt.CODES.take(palette.size).toSet() + '.'
                for (i in rows.indices) {
                    if (rows[i].codePointCount(0, rows[i].length) != cols || fg[i].length != cols || bg[i].length != cols) a.fail("art row $i is not $cols cells wide")
                    if (rows[i].codePoints().anyMatch { it < 0x20 }) a.fail("art row $i has a control character")
                    if (fg[i].any { it !in codes } || bg[i].any { it !in codes }) a.fail("art row $i uses a colour code not in its palette")
                }
                GameArt(rows, fg, bg, palette)
            }

            val saves = r.objOrNull("saves")
            saves?.requireOnly("paths", "notes")
            val savePaths = saves?.strings("paths").orEmpty().onEach { SafePath.requireRelative(it) }

            val dataFiles = r.objects("data_files").map { d ->
                d.requireOnly("asset", "dest", "sha256")
                val asset = d.string("asset")
                SafePath.requireRelative(asset)
                // aapt2 leaves hidden files and directories out of the APK.
                if (asset.split('/').any { it.startsWith(".") }) d.fail("asset '$asset' has a hidden path component, which APK packaging drops")
                val dest = d.string("dest")
                SafePath.requireRelative(dest)
                val sha = d.string("sha256")
                if (!SHA256.matches(sha)) d.fail("sha256 must be lowercase hex SHA-256")
                DataFile(asset, dest, sha)
            }

            val ver = r.objOrNull("verification")
            ver?.requireOnly("host_tested", "android_tested", "notes")
            val status = VerificationStatus.parse(r.string("status"))
            val verification = Verification(
                hostTested = ver?.stringOrNull("host_tested"),
                androidTested = ver?.stringOrNull("android_tested"),
                notes = ver?.stringOrNull("notes").orEmpty(),
            )
            if (status >= VerificationStatus.ANDROID_TESTED && verification.androidTested == null) {
                r.fail("status $status requires verification.android_tested evidence")
            }

            return GameManifest(
                id = id,
                title = r.string("title").also { if (it.isBlank() || it.length > 60) r.fail("title must be 1..60 characters") },
                summary = r.string("summary").also { if (it.length > 140) r.fail("summary is limited to 140 characters") },
                description = r.string("description").also { if (it.length > 4000) r.fail("description is limited to 4000 characters") },
                category = category,
                tags = r.strings("tags").also { t -> if (t.size > 12 || t.any { it.length > 24 }) r.fail("too many or too long tags") },
                players = r.stringOrNull("players") ?: "1",
                origin = Origin.parse(r.string("origin")),
                upstream = upstream,
                port = port,
                runtime = RuntimeSpec(backend, exe, args, env, min, preferred, Orientation.parse(rt.string("orientation")), wrap == "words", input == "typing"),
                controls = r.string("controls"),
                controlLabels = controlLabels,
                controlHide = controlHide,
                keys = keys,
                art = art,
                savePaths = savePaths,
                saveNotes = saves?.stringOrNull("notes").orEmpty(),
                dataFiles = dataFiles,
                network = NetworkUse.parse(r.stringOrNull("network") ?: "none"),
                status = status,
                verification = verification,
            )
        }
    }

    fun toJson(): Json = Json.obj(
        "schema_version" to SCHEMA_VERSION,
        "id" to id,
        "title" to title,
        "summary" to summary,
        "description" to description,
        "category" to category,
        "tags" to tags,
        "players" to players,
        "origin" to origin.id,
        "upstream" to Json.obj(
            "authors" to upstream.authors,
            "license" to upstream.license,
            "source_url" to upstream.sourceUrl,
            "version" to upstream.version,
        ),
        "port" to port?.let {
            Json.obj(
                "maintainer" to it.maintainer, "recipe" to it.recipe,
                "source_archive" to it.sourceArchive, "source_sha256" to it.sourceSha256,
                "license_file" to it.licenseFile,
            )
        },
        "runtime" to Json.obj(
            "backend" to runtime.backend,
            "executable" to runtime.executable,
            "args" to runtime.args,
            "env" to runtime.env,
            "terminal" to Json.obj(
                "min" to listOf(runtime.minTerminal.cols, runtime.minTerminal.rows),
                "preferred" to listOf(runtime.preferredTerminal.cols, runtime.preferredTerminal.rows),
            ),
            "orientation" to runtime.orientation.id,
            "wrap" to (if (runtime.wrapWords) "words" else null),
            "input" to (if (runtime.typing) "typing" else null),
        ),
        "controls" to controls,
        "control_labels" to controlLabels.takeIf { it.isNotEmpty() },
        "control_hide" to controlHide.takeIf { it.isNotEmpty() }?.toList(),
        "art" to art?.let { Json.obj("rows" to it.rows, "fg" to it.fg, "bg" to it.bg, "palette" to it.palette) },
        "keys" to keys.takeIf { it.isNotEmpty() }?.map { Json.obj("label" to it.label, "text" to it.text, "key" to it.key) },
        "saves" to Json.obj("paths" to savePaths, "notes" to saveNotes),
        "data_files" to dataFiles.map { Json.obj("asset" to it.asset, "dest" to it.dest, "sha256" to it.sha256) },
        "network" to network.id,
        "status" to status.name,
        "verification" to Json.obj(
            "host_tested" to verification.hostTested,
            "android_tested" to verification.androidTested,
            "notes" to verification.notes,
        ),
    )
}

/** Relative paths inside an app-private directory: no absolute paths, no `..`, no surprises. */
object SafePath {
    private val SEGMENT = Regex("^[A-Za-z0-9._-]{1,64}$")

    fun isRelative(path: String): Boolean {
        if (path.isEmpty() || path.length > 200 || path.startsWith("/") || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.size > 8) return false
        return parts.all { it != "." && it != ".." && SEGMENT.matches(it) }
    }

    fun requireRelative(path: String) {
        if (!isRelative(path)) throw JsonReader.InvalidException("unsafe relative path '$path'")
    }
}

/** Accepts SPDX license identifiers and simple expressions (`MIT OR Apache-2.0`). */
object Spdx {
    private val TOKEN = Regex("^[A-Za-z0-9.+-]{2,64}$")

    fun isValid(expr: String): Boolean {
        val tokens = expr.replace("(", " ").replace(")", " ").split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        var expectId = true
        for (t in tokens) {
            if (expectId) {
                if (!TOKEN.matches(t) || t in setOf("AND", "OR", "WITH")) return false
            } else if (t !in setOf("AND", "OR", "WITH")) return false
            expectId = !expectId
        }
        return !expectId
    }

    fun requireValid(expr: String): String {
        if (!isValid(expr)) throw JsonReader.InvalidException("invalid SPDX license expression '$expr'")
        return expr
    }
}
