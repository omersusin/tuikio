package dev.tuikio.catalog

import dev.tuikio.json.Json
import dev.tuikio.json.JsonReader
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private val manifest = """
        {
          "schema_version": 1,
          "id": "vitetris",
          "title": "Vitetris",
          "summary": "Falling blocks.",
          "description": "Classic falling blocks.",
          "category": "puzzle",
          "tags": ["blocks"],
          "players": "1-2",
          "origin": "verified_port",
          "upstream": {"authors": ["Victor Nilsson"], "license": "BSD-2-Clause", "source_url": "https://github.com/vicgeralds/vitetris", "version": "0.59.1"},
          "port": {"maintainer": "Tuikio ports team", "recipe": "native/build.py#vitetris", "source_archive": "vitetris_0.59.1.orig.tar.gz", "source_sha256": "699443df03c8d4bf2051838c1015da72039bbbdd0ab0eede891c59c840bdf58d"},
          "runtime": {"backend": "native_pty", "executable": "game_vitetris", "args": [], "env": {}, "terminal": {"min": [40, 22], "preferred": [48, 24]}, "orientation": "portrait"},
          "controls": "blocks.v1",
          "saves": {"paths": [".vitetris"], "notes": "Options and high scores"},
          "data_files": [],
          "network": "none",
          "status": "PORTABLE",
          "verification": {"host_tested": "2026-10-22", "android_tested": null, "notes": ""}
        }
    """.trimIndent()

    @Test
    fun manifestRoundTrips() {
        val m = GameManifest.parse(manifest)
        assertEquals("game_vitetris", m.runtime.executable)
        assertEquals(TerminalSize(40, 22), m.runtime.minTerminal)
        assertFalse(m.status.installable)
        val again = GameManifest.parse(m.toJson().write(pretty = true))
        assertEquals(m, again)
    }

    @Test
    fun manifestRejectsDangerousOrSloppyValues() {
        fun bad(from: String, to: String) =
            assertFailsWith<JsonReader.InvalidException>("$from -> $to") { GameManifest.parse(manifest.replace(from, to)) }
        bad("\"game_vitetris\"", "\"../../system/bin/sh\"")
        bad("\"env\": {}", "\"env\": {\"LD_PRELOAD\": \"/data/x.so\"}")
        bad("\"env\": {}", "\"env\": {\"HOME\": \"/sdcard\"}")
        bad("\".vitetris\"", "\"../../shared_prefs\"")
        bad("\"BSD-2-Clause\"", "\"BSD 2 clause!\"")
        bad("\"status\": \"PORTABLE\"", "\"status\": \"STORE_READY\"")
        bad("\"https://github.com/vicgeralds/vitetris\"", "\"javascript:alert(1)\"")
        bad("\"min\": [40, 22]", "\"min\": [400000, 22]")
        bad("\"network\": \"none\"", "\"network\": \"none\", \"autorun\": true")
    }

    /** The per-game additions: keys, labels and the card picture round-trip and validate. */
    @Test
    fun gameKeysLabelsAndArt() {
        val good = manifest.replace("\"controls\": \"blocks.v1\",", """
            "controls": "blocks.v1",
            "control_labels": {"drop": "Slam"},
            "control_hide": ["back"],
            "keys": [{"label": "Hold", "text": "c"}, {"label": "Menu", "key": "ESCAPE"}],
            "art": {"rows": ["│▓▓ │", "└───┘"], "fg": [".00..", "....."], "bg": [".11..", "....."], "palette": ["a9", "#203040"]},
        """.trimIndent())
        val m = GameManifest.parse(good)
        assertEquals(mapOf("drop" to "Slam"), m.controlLabels)
        assertEquals(setOf("back"), m.controlHide)
        assertEquals(listOf(GameKey("Hold", text = "c"), GameKey("Menu", key = "ESCAPE")), m.keys)
        assertEquals(5, m.art!!.cols)
        assertEquals(m, GameManifest.parse(m.toJson().write(pretty = true)))
        fun bad(from: String, to: String) =
            assertFailsWith<JsonReader.InvalidException>("$from -> $to") { GameManifest.parse(good.replace(from, to)) }
        bad("\"text\": \"c\"", "\"text\": \"c\", \"key\": \"TAB\"")
        bad("\"key\": \"ESCAPE\"", "\"key\": \"PRINT_SCREEN\"")
        bad("\"label\": \"Hold\"", "\"label\": \"Hold the piece for later\"")
        bad("[\".00..\", \".....\"]", "[\".00.\", \".....\"]") // a row narrower than its text
        bad("[\".11..\", \".....\"]", "[\".12..\", \".....\"]") // a colour not in the palette
        bad("\"#203040\"", "\"red\"")
    }

    @Test
    fun androidTestedNeedsEvidence() {
        val ok = manifest.replace("\"PORTABLE\"", "\"ANDROID_TESTED\"").replace("\"android_tested\": null", "\"android_tested\": \"2026-10-22 Pixel 8\"")
        assertEquals(VerificationStatus.ANDROID_TESTED, GameManifest.parse(ok).status)
    }

    @Test
    fun jsonParserIsStrictAndBounded() {
        assertFailsWith<Json.ParseException> { Json.parse("{\"a\":1,\"a\":2}") }
        assertFailsWith<Json.ParseException> { Json.parse("[1,]") }
        assertFailsWith<Json.ParseException> { Json.parse("{'a':1}") }
        assertFailsWith<Json.ParseException> { Json.parse("[".repeat(100) + "]".repeat(100)) }
        assertFailsWith<Json.ParseException> { Json.parse("1e999") }
        assertEquals(Json.Str("é\n\u0001"), Json.parse("\"\\u00e9\\n\\u0001\""))
        val doc = Json.obj("a" to listOf(1, 2.5, "x\"y", null, true))
        assertEquals(doc, Json.parse(doc.write()))
    }

    @Test
    fun sourceUrlsAreCanonicalized() {
        fun ok(s: String) = (SourceUrl.check(s) as SourceCheck.Ok).source
        assertEquals("https://github.com/ligurio/awesome-ttygames", ok("https://github.com/ligurio/awesome-ttygames/blob/master/games.yaml").canonicalUrl)
        assertEquals("/blob/master/games.yaml", ok("https://github.com/ligurio/awesome-ttygames/blob/master/games.yaml").subPath)
        assertEquals("https://github.com/tmewett/BrogueCE", ok("github.com/tmewett/BrogueCE.git").canonicalUrl)
        assertEquals("https://gitlab.com/dustyweb/terminal-phase", ok("https://gitlab.com/dustyweb/terminal-phase/-/tree/master").canonicalUrl)
        assertEquals("https://sourceforge.net/projects/nsnake", ok("https://sourceforge.net/projects/nsnake/").canonicalUrl)
        assertEquals("https://www.seehuhn.de/pages/moon-buggy", ok("https://www.seehuhn.de/pages/moon-buggy?x=1#top").canonicalUrl)
        assertEquals(ok("https://GitHub.com/Foo/Bar").key, ok("https://github.com/foo/bar/").key)
    }

    @Test
    fun sourceUrlsRejectSsrfAndJunk() {
        listOf(
            "http://github.com/a/b",
            "https://127.0.0.1/x",
            "https://[::1]/x",
            "https://2130706433/x",
            "https://0x7f000001/x",
            "https://localhost/x",
            "https://router.lan/x",
            "https://metadata.google.internal/computeMetadata",
            "https://user:pass@github.com/a/b",
            "https://github.com:8443/a/b",
            "file:///etc/passwd",
            "https://github.com/onlyowner",
            "https://exa mple.org",
            "",
        ).forEach { assertIs<SourceCheck.Rejected>(SourceUrl.check(it), it) }
    }

    @Test
    fun reviewStatesOnlyMoveForward() {
        assertTrue(ReviewState.PERSONAL_SOURCE.canMoveTo(ReviewState.SUBMITTED))
        assertFalse(ReviewState.SUBMITTED.canMoveTo(ReviewState.STORE_READY), "no skipping review")
        assertFalse(ReviewState.APPROVED_SOURCE.canMoveTo(ReviewState.STORE_READY), "listed is not installable")
        assertFalse(ReviewState.REJECTED.canMoveTo(ReviewState.PENDING_REVIEW))
        assertNull(ReviewState.parse("HACKED"))
    }

    @Test
    fun catalogSignaturesVerify() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val data = "{\"catalog\":1}".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(data)
            Base64.getEncoder().encodeToString(sign())
        }
        val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.public.encoded) + "\n-----END PUBLIC KEY-----"
        val key = CatalogSignature.publicKey(pem)
        assertTrue(CatalogSignature.verify(data, sig, key))
        assertFalse(CatalogSignature.verify("{\"catalog\":2}".toByteArray(), sig, key))
        assertFalse(CatalogSignature.verify(data, "bm90IGEgc2lnbmF0dXJl", key))
    }

    @Test
    fun spdxExpressions() {
        assertTrue(Spdx.isValid("MIT"))
        assertTrue(Spdx.isValid("GPL-2.0-or-later"))
        assertTrue(Spdx.isValid("MIT OR Apache-2.0"))
        assertFalse(Spdx.isValid("MIT OR"))
        assertFalse(Spdx.isValid("AND"))
    }
}
