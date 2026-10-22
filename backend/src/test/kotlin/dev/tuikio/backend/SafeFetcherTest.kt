package dev.tuikio.backend

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeFetcherTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `private, local and special-purpose addresses are not public`() {
        val blocked = listOf(
            "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.255",
            "192.0.0.8", "192.0.2.1", "192.168.0.1", "198.18.0.1", "198.51.100.7", "203.0.113.9", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "2001:db8::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
            "64:ff9b::a9fe:a9fe", "2002:c0a8:0101::1", "2001:0::1", "100::1",
        )
        blocked.forEach { assertFalse(AddressPolicy.isPublic(ip(it)), it) }
        val allowed = listOf("93.184.216.34", "140.82.112.3", "1.1.1.1", "172.32.0.1", "2606:4700::1111", "2a00:1450:4001::200e", "::ffff:8.8.8.8")
        allowed.forEach { assertTrue(AddressPolicy.isPublic(ip(it)), it) }
    }

    /** A plain-TCP stand-in for remote sites; [connect] routes every "TLS" connection here. */
    private class Site(val responses: Map<String, String>) : AutoCloseable {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val requests = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    s.use {
                        val req = it.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { l -> l.isNotEmpty() }.toList() }
                        requests += req.joinToString("\n")
                        val host = req.first { l -> l.startsWith("Host: ") }.removePrefix("Host: ")
                        val path = req.first().split(' ')[1]
                        // The client may hang up early (size caps); that's expected.
                        runCatching { it.getOutputStream().write((responses["$host$path"] ?: "HTTP/1.0 404 Not Found\r\n\r\n").toByteArray()) }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun fetcher(site: Site, dns: Map<String, List<String>>, connected: MutableList<String> = mutableListOf()) = SafeFetcher(
        resolve = { host -> (dns[host] ?: throw java.net.UnknownHostException(host)).map { ip(it) }.toTypedArray() },
        connect = { address, host ->
            connected += "${address.hostAddress}/$host"
            Socket(InetAddress.getLoopbackAddress(), site.server.localPort)
        },
    )

    @Test
    fun `fetches a title and description from a public page, connecting to the checked address`() {
        val html = """<html><head><title>Ignored &amp; old</title><meta property="og:title" content="Rogue &amp; friends">
            <meta name="description" content='A <b>terminal</b> game list'></head></html>"""
        Site(mapOf("games.example.org/" to "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n$html")).use { site ->
            val connected = mutableListOf<String>()
            val page = fetcher(site, mapOf("games.example.org" to listOf("93.184.216.34")), connected).preview("https://games.example.org/")
            assertEquals("Rogue & friends", page.title)
            assertEquals("A terminal game list", page.description)
            assertEquals(listOf("93.184.216.34/games.example.org"), connected)
            assertTrue(site.requests.single().startsWith("GET / HTTP/1.0\nHost: games.example.org"))
        }
    }

    @Test
    fun `refuses hosts that resolve to private addresses, even partly`() {
        Site(emptyMap()).use { site ->
            val f = fetcher(site, mapOf("rebind.example.org" to listOf("93.184.216.34", "10.0.0.5"), "meta.example.org" to listOf("169.254.169.254")))
            assertThrows<SafeFetcher.Blocked> { f.preview("https://rebind.example.org/") }
            assertThrows<SafeFetcher.Blocked> { f.preview("https://meta.example.org/") }
            assertTrue(site.requests.isEmpty(), "no connection may be made")
        }
    }

    @Test
    fun `redirects are re-checked from scratch`() {
        val site = Site(
            mapOf(
                "a.example.org/" to "HTTP/1.1 302 Found\r\nLocation: https://inside.example.org/admin\r\n\r\n",
                "b.example.org/" to "HTTP/1.1 301 Moved\r\nLocation: http://b.example.org/plain\r\n\r\n",
                "c.example.org/" to "HTTP/1.1 302 Found\r\nLocation: /next\r\n\r\n",
                "c.example.org/next" to "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<title>Done</title>",
                "d.example.org/" to "HTTP/1.1 302 Found\r\nLocation: /\r\n\r\n",
                "e.example.org/" to "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\nELF",
            ),
        )
        site.use {
            val f = fetcher(
                site,
                mapOf(
                    "a.example.org" to listOf("93.184.216.34"), "inside.example.org" to listOf("192.168.1.1"),
                    "b.example.org" to listOf("93.184.216.34"), "c.example.org" to listOf("93.184.216.34"),
                    "d.example.org" to listOf("93.184.216.34"), "e.example.org" to listOf("93.184.216.34"),
                ),
            )
            assertThrows<SafeFetcher.Blocked> { f.preview("https://a.example.org/") }
            assertThrows<SafeFetcher.Blocked> { f.preview("https://b.example.org/") }
            assertEquals("Done", f.preview("https://c.example.org/").title)
            assertThrows<IOException> { f.preview("https://d.example.org/") }
            assertThrows<IOException> { f.preview("https://e.example.org/") }
        }
    }

    @Test
    fun `body size is capped`() {
        val big = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<title>Big</title>" + "x".repeat(2_000_000)
        Site(mapOf("big.example.org/" to big)).use { site ->
            val f = SafeFetcher(
                resolve = { arrayOf(ip("93.184.216.34")) },
                connect = { _, _ -> Socket(InetAddress.getLoopbackAddress(), site.server.localPort) },
                maxBytes = 1024,
            )
            assertEquals(1024, f.get("https://big.example.org/").body.size)
        }
    }
}
