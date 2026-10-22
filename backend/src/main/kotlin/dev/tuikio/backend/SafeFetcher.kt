package dev.tuikio.backend

import dev.tuikio.catalog.SourceCheck
import dev.tuikio.catalog.SourceUrl
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Which resolved addresses the server may connect to on a user's behalf. */
object AddressPolicy {
    fun isPublic(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 0xFF }
        return when (a) {
            is Inet4Address -> isPublicV4(b)
            is Inet6Address -> isPublicV6(b)
            else -> false
        }
    }

    private fun isPublicV4(b: List<Int>): Boolean {
        val (x, y, z) = b
        return !(
            x == 0 || x == 10 || x == 127 || x >= 224 ||
                (x == 100 && y in 64..127) || // carrier-grade NAT
                (x == 169 && y == 254) || // link-local, cloud metadata
                (x == 172 && y in 16..31) ||
                (x == 192 && y == 168) ||
                (x == 192 && y == 0 && (z == 0 || z == 2)) ||
                (x == 192 && y == 88 && z == 99) ||
                (x == 198 && (y == 18 || y == 19)) ||
                (x == 198 && y == 51 && z == 100) ||
                (x == 203 && y == 0 && z == 113)
            )
    }

    private fun isPublicV6(b: List<Int>): Boolean {
        // Addresses that embed IPv4 are judged by the IPv4 inside.
        if (b.subList(0, 10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF) return isPublicV4(b.subList(12, 16)) // ::ffff:a.b.c.d
        if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B) return isPublicV4(b.subList(12, 16)) // 64:ff9b::/96 NAT64
        if (b[0] == 0x20 && b[1] == 0x02) return isPublicV4(b.subList(2, 6)) // 2002::/16 6to4
        if (b.subList(0, 12).all { it == 0 }) return false // ::/96 incl. ::, ::1 and IPv4-compatible
        if (b[0] and 0xFE == 0xFC) return false // fc00::/7 unique local
        if (b[0] == 0xFE && b[1] and 0xC0 == 0x80) return false // fe80::/10
        if (b[0] == 0xFF) return false // multicast
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0D && b[3] == 0xB8) return false // documentation
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) return false // Teredo
        if (b[0] == 0x01 && b[1] == 0x00 && b.subList(2, 8).all { it == 0 }) return false // discard-only
        return b[0] and 0xE0 == 0x20 // global unicast 2000::/3
    }
}

/**
 * Fetches a public web page for a link preview without becoming an SSRF tool:
 * - only URLs that pass [SourceUrl.check] (https, port 443, DNS names, no credentials);
 * - every resolved address must be public, and the connection goes to that exact address,
 *   so DNS rebinding between check and connect can't redirect it;
 * - TLS with SNI and hostname verification for the original name;
 * - at most [maxRedirects] redirects, each re-checked from scratch;
 * - capped header and body sizes, short timeouts, HTML only.
 */
class SafeFetcher(
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    private val connect: (InetAddress, String) -> Socket = ::tlsConnect,
    private val maxBytes: Int = 256 * 1024,
    private val maxRedirects: Int = 3,
) {
    class Blocked(message: String) : IOException(message)

    data class Page(val finalUrl: String, val title: String?, val description: String?)

    fun preview(url: String): Page {
        var current = url
        repeat(maxRedirects + 1) {
            val res = get(current)
            if (res.status in 300..399) {
                val loc = res.headers["location"] ?: throw IOException("redirect without location")
                current = URI(current).resolve(loc.trim()).toString()
                return@repeat
            }
            if (res.status !in 200..299) throw IOException("HTTP ${res.status}")
            val type = res.headers["content-type"].orEmpty().lowercase()
            if (!type.startsWith("text/html") && !type.startsWith("application/xhtml")) throw IOException("not an HTML page")
            val html = String(res.body, Charsets.UTF_8)
            return Page(current, Html.title(html), Html.description(html))
        }
        throw IOException("too many redirects")
    }

    internal data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    internal fun get(url: String): Response {
        val src = when (val c = SourceUrl.check(url)) {
            is SourceCheck.Ok -> c.source
            is SourceCheck.Rejected -> throw Blocked(c.reason)
        }
        val uri = URI(url)
        val host = uri.host.lowercase().trimEnd('.')
        val addresses = resolve(host)
        if (addresses.isEmpty()) throw IOException("no address")
        if (addresses.any { !AddressPolicy.isPublic(it) }) throw Blocked("${src.displayName} resolves to a private address.")
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        require(path.none { it <= ' ' }) { "bad path" }
        connect(addresses[0], host).use { sock ->
            sock.soTimeout = 8_000
            val req = "GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: TuikioPreview/1 (+https://github.com/omersusin/tuikio)\r\n" +
                "Accept: text/html\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
            val input = BufferedInputStream(sock.getInputStream())
            val statusLine = readLine(input) ?: throw IOException("empty response")
            val status = Regex("^HTTP/1\\.[01] (\\d{3})").find(statusLine)?.groupValues?.get(1)?.toInt() ?: throw IOException("bad status line")
            val headers = HashMap<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                headerBytes += line.length
                if (headerBytes > 16 * 1024) throw IOException("headers too large")
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val body = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (body.size() < maxBytes) {
                val n = input.read(buf, 0, minOf(buf.size, maxBytes - body.size()))
                if (n < 0) break
                body.write(buf, 0, n)
            }
            return Response(status, headers, body.toByteArray())
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) throw IOException("line too long")
        }
    }

    companion object {
        fun tlsConnect(address: InetAddress, host: String): Socket {
            val raw = Socket()
            raw.connect(InetSocketAddress(address, 443), 5_000)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(host))
            }
            ssl.startHandshake()
            return ssl
        }
    }
}

/** Just enough HTML reading for a title and a description. Output is plain text. */
object Html {
    fun title(html: String): String? =
        meta(html, "og:title") ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let { clean(it) }

    fun description(html: String): String? = meta(html, "og:description") ?: meta(html, "description")

    private fun meta(html: String, name: String): String? {
        val tag = Regex("<meta\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.firstOrNull { t ->
            Regex("(?:name|property)\\s*=\\s*[\"']${Regex.escape(name)}[\"']", RegexOption.IGNORE_CASE).containsMatchIn(t)
        } ?: return null
        return Regex("content\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)?.let { m ->
            clean(m.groupValues[2].ifEmpty { m.groupValues[3] })
        }
    }

    fun clean(s: String): String? {
        val text = s.replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace(Regex("\\s+"), " ").trim()
            .filter { it >= ' ' }
        return text.take(300).ifEmpty { null }
    }
}
