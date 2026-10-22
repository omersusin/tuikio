package dev.tuikio.backend

import dev.tuikio.catalog.ReviewState
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One submission as the server keeps it. It holds what the contributor chose to publish plus
 * review bookkeeping; never IP addresses, tokens or the receipt secret itself (only its hash).
 */
data class SubmissionRecord(
    /** Public, random id used in branch names and PR text. Unrelated to the receipt. */
    val id: String,
    val receiptHash: String,
    val receiptDisplay: String,
    val kind: String,
    val dedupeKey: String,
    val canonicalUrl: String,
    val displayName: String,
    val title: String,
    val note: String,
    val attribution: String,
    val credit: String,
    /** Co-authored-by identity for verified GitHub credit, e.g. "login <id+login@users.noreply.github.com>". */
    val coAuthor: String?,
    val draft: Json.Obj?,
    val state: ReviewState,
    val prUrl: String?,
    val reason: String?,
    val attempts: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): Json = Json.obj(
        "id" to id, "receipt_hash" to receiptHash, "receipt_display" to receiptDisplay, "kind" to kind,
        "dedupe_key" to dedupeKey, "canonical_url" to canonicalUrl, "display_name" to displayName,
        "title" to title, "note" to note, "attribution" to attribution, "credit" to credit, "co_author" to coAuthor,
        "draft" to draft, "state" to state.name, "pr_url" to prUrl, "reason" to reason, "attempts" to attempts,
        "created_at" to createdAt, "updated_at" to updatedAt,
    )

    companion object {
        fun fromJson(r: JsonReader) = SubmissionRecord(
            id = r.string("id"),
            receiptHash = r.string("receipt_hash"),
            receiptDisplay = r.string("receipt_display"),
            kind = r.string("kind"),
            dedupeKey = r.string("dedupe_key"),
            canonicalUrl = r.string("canonical_url"),
            displayName = r.string("display_name"),
            title = r.string("title"),
            note = r.string("note"),
            attribution = r.string("attribution"),
            credit = r.string("credit"),
            coAuthor = r.stringOrNull("co_author"),
            draft = r.raw("draft") as? Json.Obj,
            state = ReviewState.parse(r.string("state")) ?: r.fail("bad state"),
            prUrl = r.stringOrNull("pr_url"),
            reason = r.stringOrNull("reason"),
            attempts = r.int("attempts"),
            createdAt = (r.raw("created_at") as Json.Num).value.toLong(),
            updatedAt = (r.raw("updated_at") as Json.Num).value.toLong(),
        )
    }
}

/**
 * Small file-backed store: one JSON file per submission plus hash-named index files for
 * duplicate detection and webhook lookups. Writes go through a temp file and an atomic rename
 * so a crash never leaves half a record. Volume is a handful of submissions a day, so one lock
 * is plenty.
 */
class Store(root: File) {
    private val submissions = File(root, "submissions").apply { mkdirs() }
    private val bySource = File(root, "index/source").apply { mkdirs() }
    private val byPr = File(root, "index/pr").apply { mkdirs() }
    private val lock = Any()

    fun get(receiptHash: String): SubmissionRecord? = synchronized(lock) { read(fileFor(receiptHash)) }

    fun byDedupeKey(key: String): SubmissionRecord? = synchronized(lock) {
        val idx = File(bySource, sha256Hex(key))
        if (!idx.isFile) null else read(fileFor(idx.readText().trim()))
    }

    fun byPrUrl(url: String): SubmissionRecord? = synchronized(lock) {
        val idx = File(byPr, sha256Hex(url))
        if (!idx.isFile) null else read(fileFor(idx.readText().trim()))
    }

    /** Inserts [rec] unless an active submission already holds its dedupe key; returns the holder in that case. */
    fun insertIfNew(rec: SubmissionRecord): SubmissionRecord? = synchronized(lock) {
        val existing = byDedupeKey(rec.dedupeKey)
        if (existing != null && existing.state != ReviewState.REJECTED) return existing
        put(rec)
        atomicWrite(File(bySource, sha256Hex(rec.dedupeKey)), rec.receiptHash)
        null
    }

    fun put(rec: SubmissionRecord) = synchronized(lock) {
        atomicWrite(fileFor(rec.receiptHash), rec.toJson().write(pretty = true))
        if (rec.prUrl != null) atomicWrite(File(byPr, sha256Hex(rec.prUrl)), rec.receiptHash)
    }

    /** Applies [change] under the lock and returns the stored result. */
    fun update(receiptHash: String, change: (SubmissionRecord) -> SubmissionRecord): SubmissionRecord? = synchronized(lock) {
        val cur = get(receiptHash) ?: return null
        val next = change(cur)
        if (next != cur) put(next)
        next
    }

    fun all(): List<SubmissionRecord> = synchronized(lock) {
        (submissions.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { read(it) }
    }

    private fun fileFor(receiptHash: String): File {
        require(Regex("^[0-9a-f]{64}$").matches(receiptHash)) { "bad key" }
        return File(submissions, "$receiptHash.json")
    }

    private fun read(f: File): SubmissionRecord? = if (!f.isFile) null else SubmissionRecord.fromJson(JsonReader.parse(f.readText()))

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
