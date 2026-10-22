package dev.tuikio.catalogcli

import dev.tuikio.catalog.CatalogDocument
import dev.tuikio.catalog.CatalogSignature
import dev.tuikio.catalog.DiscoveryEntry
import dev.tuikio.catalog.GameManifest
import dev.tuikio.catalog.SourceCheck
import dev.tuikio.catalog.SourceUrl
import dev.tuikio.controls.ControlProfile
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Validates the catalog repository and assembles the snapshot the app ships and syncs.
 *
 *   catalog-cli validate <catalog-dir>
 *   catalog-cli assemble <catalog-dir> <out-dir> [--version N]
 *
 * assemble writes catalog.json, profiles/<id>.json and, when TUIKIO_CATALOG_SIGNING_KEY
 * (a PKCS#8 EC private key in base64) is set, catalog.json.sig. The same validation runs in
 * CI on every catalog pull request, so the app and the review pipeline agree on what is valid.
 */
fun main(args: Array<String>) {
    if (args.size < 2) usage()
    val dir = File(args[1])
    val problems = mutableListOf<String>()
    val (games, discovery, profiles) = load(dir, problems)
    if (problems.isNotEmpty()) {
        System.err.println("Catalog is invalid:")
        problems.forEach { System.err.println("  - $it") }
        exitProcess(1)
    }
    when (args[0]) {
        "validate" -> println("ok: ${games.size} games, ${discovery.size} discovery entries, ${profiles.size} control profiles")
        "assemble" -> {
            if (args.size < 3) usage()
            val out = File(args[2])
            val version = args.indexOf("--version").takeIf { it > 0 }?.let { args[it + 1].toInt() } ?: 1
            assemble(dir, out, version, games)
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("usage: catalog-cli validate <dir> | assemble <dir> <out> [--version N]")
    exitProcess(2)
}

private data class Loaded(val games: List<GameManifest>, val discovery: List<DiscoveryEntry>, val profiles: Map<String, ControlProfile>)

private fun load(dir: File, problems: MutableList<String>): Loaded {
    val profiles = LinkedHashMap<String, ControlProfile>()
    File(dir, "profiles").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.forEach { f ->
        try {
            val p = ControlProfile.parse(f.readText())
            if ("${p.id}.json" != f.name) problems += "${f.name}: profile_id '${p.id}' must match the file name"
            profiles[p.id] = p
        } catch (e: Exception) {
            problems += "${f.name}: ${e.message}"
        }
    }
    val games = ArrayList<GameManifest>()
    File(dir, "games").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.forEach { f ->
        try {
            val g = GameManifest.parse(f.readText())
            if ("${g.id}.json" != f.name) problems += "${f.name}: id '${g.id}' must match the file name"
            if (g.controls !in profiles) problems += "${f.name}: control profile '${g.controls}' does not exist"
            games += g
        } catch (e: Exception) {
            problems += "${f.name}: ${e.message}"
        }
    }
    val discovery = ArrayList<DiscoveryEntry>()
    val discoveryFile = File(dir, "discovery.json")
    try {
        val arr = Json.parse(discoveryFile.readText()) as? Json.Arr ?: throw JsonReader.InvalidException("expected an array")
        arr.items.forEachIndexed { i, j ->
            try {
                discovery += DiscoveryEntry.fromJson(JsonReader(j as Json.Obj, "discovery[$i]"))
            } catch (e: Exception) {
                problems += "discovery.json: ${e.message}"
            }
        }
    } catch (e: Exception) {
        problems += "discovery.json: ${e.message}"
    }
    val ids = discovery.groupBy { it.id }.filterValues { it.size > 1 }.keys
    if (ids.isNotEmpty()) problems += "discovery.json: duplicate ids $ids"
    val gameIds = games.map { it.id }.toSet()
    discovery.filter { it.gameId != null && it.gameId !in gameIds }.forEach { problems += "discovery '${it.id}' references unknown game '${it.gameId}'" }
    // Duplicate detection the way submissions are deduplicated: canonical repository URLs.
    val byCanonical = HashMap<String, MutableList<String>>()
    discovery.forEach { e ->
        val check = SourceUrl.check(e.sourceUrl.replaceFirst("http://", "https://"))
        if (check is SourceCheck.Ok) byCanonical.getOrPut(check.source.key) { ArrayList() } += e.id
    }
    byCanonical.filterValues { it.size > 1 }.forEach { (url, list) ->
        val allowed = list.all { it.startsWith("shellgame") || it == "shell-games" || it == "spire-ascent" } ||
            list.all { it.startsWith("awesome-ttygames") }
        if (!allowed) problems += "possible duplicates for $url: $list"
    }
    checkCredits(File(dir, "credits.json"), problems)
    return Loaded(games, discovery, profiles)
}

private fun assemble(dir: File, out: File, version: Int, games: List<GameManifest>) {
    out.mkdirs()
    val generated = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
    val discoveryJson = Json.parse(File(dir, "discovery.json").readText())
    val doc = Json.obj(
        "schema_version" to CatalogDocument.SCHEMA_VERSION,
        "catalog_version" to version,
        "generated" to generated,
        "games" to games.map { it.toJson() },
        "discovery" to discoveryJson,
    )
    val bytes = doc.write(pretty = false).toByteArray(Charsets.UTF_8)
    CatalogDocument.parse(String(bytes, Charsets.UTF_8)) // the output must round-trip through the app's parser
    File(out, "catalog.json").writeBytes(bytes)
    File(dir, "credits.json").takeIf { it.isFile }?.copyTo(File(out, "credits.json"), overwrite = true)
    val profilesOut = File(out, "profiles").apply { mkdirs() }
    File(dir, "profiles").listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { it.copyTo(File(profilesOut, it.name), overwrite = true) }
    val key = System.getenv("TUIKIO_CATALOG_SIGNING_KEY")
    if (key != null) {
        val pk = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.trim())))
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(pk); update(bytes) }.sign()
        File(out, "catalog.json.sig").writeText(Base64.getEncoder().encodeToString(sig) + "\n")
        println("signed catalog.json")
    }
    println("assembled ${games.size} games into ${out.path} (sha256 ${CatalogSignature.sha256Hex(bytes)})")
}

/** credits.json: people, contributors, bundled components (with their license files), tools, sources. */
private fun checkCredits(f: File, problems: MutableList<String>) {
    if (!f.isFile) return
    try {
        val r = JsonReader.parse(f.readText())
        r.requireOnly("schema_version", "people", "contributors_note", "contributors", "components", "tools", "sources")
        if (r.int("schema_version") != 1) problems += "credits.json: unsupported schema_version"
        val licenses = File(f.parentFile.parentFile, "LICENSES")
        for (c in r.objects("components")) {
            c.requireOnly("name", "by", "license", "license_file", "url", "what")
            val lf = c.string("license_file")
            if (!File(licenses, lf).isFile) problems += "credits.json: ${c.string("name")}: LICENSES/$lf is missing"
        }
        for (key in listOf("people", "contributors", "tools", "sources")) {
            for (o in r.objects(key)) {
                o.requireOnly("name", "login", "role", "url", "by", "what", "kind")
                o.string("name")
                o.stringOrNull("url")?.let { SourceUrl.requireDisplayable(it) }
            }
        }
    } catch (e: Exception) {
        problems += "credits.json: ${e.message}"
    }
}
