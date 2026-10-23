package dev.tuikio.runtime

import dev.tuikio.catalog.GameManifest
import dev.tuikio.json.Json
import dev.tuikio.terminal.TermKey
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plays the first moves of every game scripted in native/tests/play.json, starting each one the
 * way the app does: LaunchSpec.forManifest (so $PAGER and the manifest's args and env apply) in a
 * fresh home holding the manifest's data files, checked against their SHA-256.
 */
class PlayScriptsTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "catalog/games").isDirectory }
    private val out = File(System.getProperty("tuikio.nativeOut", File(root, "native/build/out").path), "x86_64")

    @TestFactory
    fun scriptedGames(): List<DynamicTest> {
        val scripts = (Json.parse(File(root, "native/tests/play.json").readText()) as Json.Obj).let { it.fields["games"] as Json.Obj }
        return scripts.fields.entries.sortedBy { it.key }.map { (id, steps) ->
            DynamicTest.dynamicTest(id) { play(id, (steps as Json.Arr).items.map { (it as Json.Str).value }) }
        }
    }

    private fun play(id: String, steps: List<String>) {
        val manifestFile = File(root, "catalog/games/$id.json")
        assertTrue(manifestFile.isFile, "native/tests/play.json scripts $id, which has no manifest")
        val m = GameManifest.parse(manifestFile.readText())
        val native = System.getProperty("tuikio.requireNative") == "true"
        val exe = File(out, "lib${m.runtime.executable}.so")
        if (native) assertTrue(exe.canExecute(), "${exe.name} missing") else assumeTrue(exe.canExecute(), "native build missing")
        val home = Files.createTempDirectory("play-$id-").toFile()
        for (d in m.dataFiles) {
            val src = File(out.parentFile, "assets/${d.asset}")
            assertTrue(src.isFile, "$id: data file ${d.asset} missing from the native build")
            val sha = MessageDigest.getInstance("SHA-256").digest(src.readBytes()).joinToString("") { "%02x".format(it) }
            assertTrue(sha == d.sha256, "$id: ${d.asset} doesn't match the manifest's SHA-256 (regenerate with tools/ports/manifests.py)")
            src.copyTo(File(home, d.dest).apply { parentFile.mkdirs() }, overwrite = true)
        }
        File(home, ".tmp").mkdirs()
        val s = GameSession(LaunchSpec.forManifest(m, out, home), m.runtime.preferredTerminal.cols, m.runtime.preferredTerminal.rows)
        s.terminal.wordWrap = m.runtime.wrapWords // as the game screen does
        s.start()
        try {
            for (step in steps) {
                val (kind, arg) = step.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                when (kind) {
                    "expect" -> if (!s.awaitScreen(6000) { lines -> lines.joinToString("\n").contains(arg) }) {
                        fail("$id: \"$arg\" not seen. Screen:\n" + s.screenText().joinToString("\n") { "|$it" } + "\nstderr: ${s.diagnostics}")
                    }
                    "type" -> s.send(s.keys.text(arg.replace("\n", "\r")))
                    "key" -> s.send(s.keys.encode(TermKey.valueOf(arg)))
                    "wait" -> Thread.sleep(arg.toLong())
                    else -> fail("$id: unknown step $step")
                }
            }
            Thread.sleep(200)
            assertTrue(s.isRunning, "$id: exited (${s.exitCode}) after its script. stderr: ${s.diagnostics}")
        } finally {
            s.stop(800)
            home.deleteRecursively()
        }
    }
}
