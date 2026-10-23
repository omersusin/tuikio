package dev.tuikio.runtime

import dev.tuikio.catalog.GameManifest
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every bundled game survives what the app does when it goes to the background and back:
 * suspend (SIGTSTP, then SIGSTOP), resume (SIGCONT), and a resume that was never preceded by a
 * suspend (the app's first onStart). moon-buggy used to crash on a bare SIGCONT.
 */
class SuspendResumeTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "catalog/games").isDirectory }
    private val out = File(System.getProperty("tuikio.nativeOut", File(root, "native/build/out").path), "x86_64")

    private fun start(m: GameManifest): GameSession {
        val home = Files.createTempDirectory("susp-${m.id}-").toFile()
        for (d in m.dataFiles) {
            val src = File(out.parentFile, "assets/${d.asset}")
            if (src.isFile) src.copyTo(File(home, d.dest).apply { parentFile.mkdirs() }, overwrite = true)
        }
        val s = GameSession(LaunchSpec.forManifest(m, out, home), m.runtime.preferredTerminal.cols, m.runtime.preferredTerminal.rows)
        s.start()
        return s
    }

    @Test
    fun everyGameSurvivesSuspendAndResume() {
        val manifests = File(root, "catalog/games").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { GameManifest.parse(it.readText()) }
        assumeTrue(File(out, "libtuikio_pty.so").canExecute() || System.getProperty("tuikio.requireNative") == "true")
        val problems = ArrayList<String>()
        for (m in manifests) {
            val s = start(m)
            try {
                if (!s.awaitScreen(5000) { lines -> lines.any { it.isNotBlank() } }) { problems += "${m.id}: drew nothing"; continue }
                s.resume() // never suspended: must be a no-op
                Thread.sleep(300)
                repeat(2) {
                    s.suspend()
                    Thread.sleep(500)
                    if (!s.isRunning) problems += "${m.id}: died while suspended (exit ${s.exitCode}) ${s.diagnostics.trim()}"
                    s.resume()
                    Thread.sleep(500)
                    if (!s.isRunning) problems += "${m.id}: died after resume (exit ${s.exitCode}) ${s.diagnostics.trim()}"
                }
                val text = s.screenText().joinToString("\n")
                if ("ABORTED" in text || "ABORTED" in s.diagnostics) problems += "${m.id}: aborted. stderr: ${s.diagnostics.trim()}\n$text"
            } finally {
                s.stop(800)
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
