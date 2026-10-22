package dev.tuikio.catalog

import dev.tuikio.json.JsonReader
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** What kind of thing a discovery link points at; not every link is a single game. */
enum class EntryKind(val id: String) {
    GAME("game"), COLLECTION("collection"), INTERPRETER("interpreter"), ENGINE("engine"),
    ONLINE_SERVICE("online_service"), DISCOVERY_LIST("discovery_list"), SIMULATION("simulation"),
    PACKAGING_SOURCE("packaging_source");

    companion object {
        fun parse(s: String) = values().firstOrNull { it.id == s } ?: throw JsonReader.InvalidException("unknown kind '$s'")
    }
}

/**
 * A link in the discovery catalog. Many fields stay null until someone investigates;
 * unknown is recorded as unknown rather than guessed.
 */
data class DiscoveryEntry(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val kind: EntryKind,
    val category: String,
    val status: VerificationStatus,
    val upstreamLicense: String?,
    val gameplayActions: List<String>,
    val expectedTerminal: TerminalSize?,
    val preferredOrientation: Orientation?,
    val controlProfile: String?,
    val backend: String,
    val platformSupport: String?,
    val importDate: String,
    val lastChecked: String?,
    val version: String?,
    val dependencies: List<String>,
    val screenshotProvenance: String?,
    val buildRecipe: String?,
    /** Set when a playable [GameManifest] exists for this entry. */
    val gameId: String?,
    val notes: String,
) {
    companion object {
        fun fromJson(r: JsonReader): DiscoveryEntry {
            r.requireOnly(
                "id", "title", "source_url", "kind", "category", "status", "upstream_license", "gameplay_actions",
                "expected_terminal_size", "preferred_orientation", "control_profile", "backend", "platform_support",
                "import_date", "last_checked", "version", "dependencies", "screenshot_provenance", "build_recipe",
                "game_id", "notes",
            )
            val id = r.string("id")
            if (!Regex("^[a-z0-9][a-z0-9-]{0,63}$").matches(id)) r.fail("bad id '$id'")
            val url = r.string("source_url")
            SourceUrl.requireDisplayable(url)
            val category = r.string("category")
            if (category !in GameManifest.CATEGORIES && category != "meta") r.fail("unknown category '$category'")
            val size = r.ints("expected_terminal_size")
            return DiscoveryEntry(
                id = id,
                title = r.string("title"),
                sourceUrl = url,
                kind = EntryKind.parse(r.string("kind")),
                category = category,
                status = VerificationStatus.parse(r.string("status")),
                upstreamLicense = r.stringOrNull("upstream_license")?.also { if (it != "UNKNOWN") Spdx.requireValid(it) },
                gameplayActions = r.strings("gameplay_actions"),
                expectedTerminal = if (size.size == 2) TerminalSize(size[0], size[1]) else null,
                preferredOrientation = r.stringOrNull("preferred_orientation")?.let { Orientation.parse(it) },
                controlProfile = r.stringOrNull("control_profile"),
                backend = r.stringOrNull("backend") ?: "undecided",
                platformSupport = r.stringOrNull("platform_support"),
                importDate = r.string("import_date"),
                lastChecked = r.stringOrNull("last_checked"),
                version = r.stringOrNull("version"),
                dependencies = r.strings("dependencies"),
                screenshotProvenance = r.stringOrNull("screenshot_provenance"),
                buildRecipe = r.stringOrNull("build_recipe"),
                gameId = r.stringOrNull("game_id"),
                notes = r.stringOrNull("notes").orEmpty(),
            )
        }
    }
}

data class CatalogDocument(
    val catalogVersion: Int,
    val generated: String,
    val games: List<GameManifest>,
    val discovery: List<DiscoveryEntry>,
) {
    fun game(id: String): GameManifest? = games.firstOrNull { it.id == id }

    companion object {
        const val SCHEMA_VERSION = 1

        fun parse(text: String): CatalogDocument {
            val r = JsonReader.parse(text)
            r.requireOnly("schema_version", "catalog_version", "generated", "games", "discovery")
            if (r.int("schema_version") != SCHEMA_VERSION) r.fail("unsupported catalog schema")
            val games = r.objects("games").map { GameManifest.fromJson(it) }
            val discovery = r.objects("discovery").map { DiscoveryEntry.fromJson(it) }
            val dupGames = games.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dupGames.isNotEmpty()) r.fail("duplicate game ids: $dupGames")
            val dupIds = discovery.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dupIds.isNotEmpty()) r.fail("duplicate discovery ids: $dupIds")
            val gameIds = games.map { it.id }.toSet()
            discovery.forEach { e ->
                if (e.gameId != null && e.gameId !in gameIds) r.fail("discovery '${e.id}' points at unknown game '${e.gameId}'")
            }
            return CatalogDocument(r.int("catalog_version"), r.string("generated"), games, discovery)
        }
    }
}

/**
 * Detached ECDSA P-256 / SHA-256 signatures over catalog snapshots. The app bundles the
 * public key; the private key lives only in the catalog repository's CI secret store.
 */
object CatalogSignature {
    fun publicKey(pem: String): PublicKey {
        val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("").trim()
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    fun verify(data: ByteArray, signatureBase64: String, key: PublicKey): Boolean = try {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(key)
        sig.update(data)
        sig.verify(Base64.getDecoder().decode(signatureBase64.trim()))
    } catch (e: Exception) {
        false
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
