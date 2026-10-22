package dev.tuikio.app.data

import android.content.Context
import dev.tuikio.catalog.ReviewState
import dev.tuikio.catalog.SourceCheck
import dev.tuikio.catalog.SourceUrl
import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.io.File

/**
 * "My Sources": links the player saved on this phone. Saving never runs or downloads
 * anything; suggesting a source to the community is a separate, explicit step that records
 * a receipt here so its review can be followed.
 */
class Sources(private val context: Context) {
    data class Source(
        val url: String,
        val displayName: String,
        val host: String,
        val label: String,
        val addedAt: Long,
        val description: String = "",
        val license: String = "",
        val lastCommit: String = "",
        var submission: Submission? = null,
    )

    data class Submission(
        val receipt: String,
        val mode: String,
        val credit: String,
        var state: ReviewState,
        var prUrl: String? = null,
        var reason: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val file = File(context.filesDir, "sources.json")
    private val items = ArrayList<Source>()

    init {
        val root = JsonFiles.read(file) as? Json.Obj
        if (root != null) {
            for (s in JsonReader(root).objects("sources")) {
                val sub = s.objOrNull("submission")?.let {
                    Submission(
                        it.string("receipt"), it.string("mode"), it.string("credit"),
                        ReviewState.parse(it.string("state")) ?: ReviewState.SUBMITTED,
                        it.stringOrNull("pr_url"), it.stringOrNull("reason"),
                        it.stringOrNull("created_at")?.toLongOrNull() ?: 0,
                    )
                }
                items += Source(
                    s.string("url"), s.string("display_name"), s.string("host"), s.stringOrNull("label").orEmpty(),
                    s.stringOrNull("added_at")?.toLongOrNull() ?: 0, s.stringOrNull("description").orEmpty(),
                    s.stringOrNull("license").orEmpty(), s.stringOrNull("last_commit").orEmpty(), sub,
                )
            }
        }
    }

    val all: List<Source> get() = items.toList()

    fun find(url: String): Source? {
        val key = (SourceUrl.check(url) as? SourceCheck.Ok)?.source?.key ?: url.lowercase()
        return items.firstOrNull { (SourceUrl.check(it.url) as? SourceCheck.Ok)?.source?.key == key }
    }

    fun add(source: Source) {
        items.removeAll { it.url.equals(source.url, ignoreCase = true) }
        items.add(0, source)
        save()
    }

    fun remove(url: String) {
        items.removeAll { it.url == url }
        save()
    }

    fun update(source: Source) {
        val i = items.indexOfFirst { it.url == source.url }
        if (i >= 0) items[i] = source else items.add(0, source)
        save()
    }

    private fun save() {
        JsonFiles.write(
            file,
            Json.obj(
                "sources" to items.map { s ->
                    Json.obj(
                        "url" to s.url, "display_name" to s.displayName, "host" to s.host, "label" to s.label,
                        "added_at" to s.addedAt.toString(), "description" to s.description, "license" to s.license,
                        "last_commit" to s.lastCommit,
                        "submission" to s.submission?.let {
                            Json.obj(
                                "receipt" to it.receipt, "mode" to it.mode, "credit" to it.credit, "state" to it.state.name,
                                "pr_url" to it.prUrl, "reason" to it.reason, "created_at" to it.createdAt.toString(),
                            )
                        },
                    )
                },
            ),
        )
    }
}
