package dev.tuikio.catalog

import dev.tuikio.json.JsonReader
import java.net.URI
import java.util.Locale

enum class SourceHost(val label: String) {
    GITHUB("GitHub"), GITLAB("GitLab"), CODEBERG("Codeberg"), SOURCEHUT("sourcehut"),
    SOURCEFORGE("SourceForge"), OTHER("Website");
}

/** A user-supplied source link after validation and canonicalization. */
data class NormalizedSource(
    /** Canonical URL used for duplicate detection (repository root for known forges). */
    val canonicalUrl: String,
    val host: SourceHost,
    val owner: String?,
    val repo: String?,
    /** The part after the repository root (e.g. `/blob/main/games.yaml`), kept for display. */
    val subPath: String,
) {
    val key: String get() = canonicalUrl.lowercase(Locale.ROOT)
    val displayName: String get() = if (owner != null && repo != null) "$owner/$repo" else canonicalUrl.substringAfter("://")
}

sealed class SourceCheck {
    data class Ok(val source: NormalizedSource) : SourceCheck()
    data class Rejected(val reason: String) : SourceCheck()
}

/**
 * Validates links people paste into "Add a source". Only public HTTPS URLs on DNS names are
 * accepted: no IP literals, no credentials, no non-standard ports, no intranet-looking names.
 * This keeps the backend's metadata fetcher from being pointed at private networks (SSRF);
 * the backend additionally checks resolved addresses at connect time.
 */
object SourceUrl {
    private val BLOCKED_SUFFIXES = listOf(
        ".local", ".localhost", ".internal", ".intranet", ".lan", ".home", ".corp", ".arpa",
        ".test", ".invalid", ".example", ".onion",
    )
    private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val NAME = Regex("^[A-Za-z0-9_.-]{1,100}$")

    fun check(input: String): SourceCheck {
        val raw = input.trim()
        if (raw.isEmpty()) return SourceCheck.Rejected("Paste a link to a repository or project page.")
        if (raw.length > 512) return SourceCheck.Rejected("That link is too long.")
        if (raw.any { it < ' ' || it == ' ' }) return SourceCheck.Rejected("Links can't contain spaces or control characters.")
        val withScheme = if (!raw.contains("://")) "https://$raw" else raw
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return SourceCheck.Rejected("That doesn't look like a web address.")
        }
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return SourceCheck.Rejected("Only https:// links are accepted.")
        if (uri.rawUserInfo != null) return SourceCheck.Rejected("Links with a username or password are not accepted.")
        if (uri.port != -1 && uri.port != 443) return SourceCheck.Rejected("Links on unusual ports are not accepted.")
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return SourceCheck.Rejected("The link has no host name.")
        hostProblem(host)?.let { return SourceCheck.Rejected(it) }

        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { decode(it) }
        val forge = when (host) {
            "github.com", "www.github.com" -> SourceHost.GITHUB
            "gitlab.com" -> SourceHost.GITLAB
            "codeberg.org" -> SourceHost.CODEBERG
            "git.sr.ht" -> SourceHost.SOURCEHUT
            "sourceforge.net" -> SourceHost.SOURCEFORGE
            else -> SourceHost.OTHER
        }
        return when (forge) {
            SourceHost.GITHUB, SourceHost.CODEBERG -> {
                if (segments.size < 2) return SourceCheck.Rejected("Link to a repository, like https://${host.removePrefix("www.")}/owner/project.")
                val owner = segments[0]
                val repo = segments[1].removeSuffix(".git")
                if (!NAME.matches(owner) || !NAME.matches(repo)) return SourceCheck.Rejected("That repository name looks invalid.")
                val canonicalHost = host.removePrefix("www.")
                SourceCheck.Ok(
                    NormalizedSource("https://$canonicalHost/$owner/$repo", forge, owner, repo, rest(segments, 2)),
                )
            }
            SourceHost.GITLAB -> {
                // Groups can nest; the project path ends where GitLab's "/-/" routes begin.
                val end = segments.indexOf("-").let { if (it < 0) segments.size else it }
                val path = segments.subList(0, end).map { it.removeSuffix(".git") }
                if (path.size < 2 || path.any { !NAME.matches(it) }) return SourceCheck.Rejected("Link to a GitLab project, like https://gitlab.com/group/project.")
                SourceCheck.Ok(
                    NormalizedSource("https://gitlab.com/${path.joinToString("/")}", forge, path.dropLast(1).joinToString("/"), path.last(), rest(segments, end)),
                )
            }
            SourceHost.SOURCEHUT -> {
                if (segments.size < 2 || !segments[0].startsWith("~")) return SourceCheck.Rejected("Link to a sourcehut repository, like https://git.sr.ht/~owner/project.")
                SourceCheck.Ok(NormalizedSource("https://git.sr.ht/${segments[0]}/${segments[1]}", forge, segments[0], segments[1], rest(segments, 2)))
            }
            SourceHost.SOURCEFORGE -> {
                val i = segments.indexOf("projects")
                if (i < 0 || i + 1 >= segments.size) return SourceCheck.Rejected("Link to a SourceForge project page, like https://sourceforge.net/projects/name/.")
                val name = segments[i + 1]
                SourceCheck.Ok(NormalizedSource("https://sourceforge.net/projects/$name", forge, null, name, rest(segments, i + 2)))
            }
            SourceHost.OTHER -> {
                val path = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
                SourceCheck.Ok(NormalizedSource("https://$host$path", forge, null, null, ""))
            }
        }
    }

    /** Returns a reason the host is unacceptable, or null when it is a plausible public DNS name. */
    fun hostProblem(host: String): String? {
        if (host.isEmpty() || host.length > 253) return "The host name is invalid."
        if (host.startsWith("[") || host.contains(':')) return "IP addresses are not accepted; use the project's web address."
        if (host.all { it.isDigit() || it == '.' } || host.matches(Regex("^(0x[0-9a-f]+|[0-9]+)$"))) {
            return "IP addresses are not accepted; use the project's web address."
        }
        if (host == "localhost" || BLOCKED_SUFFIXES.any { host.endsWith(it) }) return "Local or private network addresses are not accepted."
        val labels = host.split('.')
        if (labels.size < 2) return "Use a full public host name, like example.org."
        if (labels.any { !LABEL.matches(it) }) return "The host name contains characters that are not allowed."
        if (labels.last().all { it.isDigit() }) return "The host name is invalid."
        return null
    }

    /** For links shown on details pages: http(s) only, no credentials. */
    fun requireDisplayable(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw JsonReader.InvalidException("invalid URL '$url'")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "https" && scheme != "http") || uri.host == null || uri.rawUserInfo != null) {
            throw JsonReader.InvalidException("URL must be http(s) without credentials: '$url'")
        }
    }

    private fun rest(segments: List<String>, from: Int) =
        if (from >= segments.size) "" else "/" + segments.subList(from, segments.size).joinToString("/")

    private fun decode(s: String) = try {
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }
}
