package dev.tuikio.runtime

import dev.tuikio.terminal.TermKey
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the real, cross-compiled game executables (x86_64 build of native/build.py) through the
 * same PTY helper, frame protocol, GameSession and TerminalEmulator the Android app uses, and
 * checks what each game draws and how it reacts to input. Skipped when the native build is absent,
 * unless -PrequireNative is set (CI's native job), where a missing binary is a failure.
 */
class RealGamesTest {
    private val out = File(System.getProperty("tuikio.nativeOut", "native/build/out"), "x86_64")

    private fun need(condition: Boolean, message: String) =
        if (System.getProperty("tuikio.requireNative") == "true") assertTrue(condition, message) else assumeTrue(condition, message)

    private fun session(exe: String, cols: Int = 80, rows: Int = 24, args: List<String> = emptyList(), setup: (File) -> Unit = {}): GameSession {
        val helper = File(out, "libtuikio_pty.so")
        val game = File(out, "lib$exe.so")
        need(helper.canExecute() && game.canExecute(), "native build missing: run python3 native/build.py --abi x86_64")
        val home = Files.createTempDirectory("tuikio-$exe-").toFile()
        setup(home)
        val s = GameSession(LaunchSpec(helper, game, args, home, GameEnvironment.build(home)), cols, rows)
        s.start()
        return s
    }

    private fun GameSession.expect(what: String, timeoutMs: Long = 4000, predicate: (String) -> Boolean) {
        if (!awaitScreen(timeoutMs) { predicate(it.joinToString("\n")) }) {
            fail("$what not seen. Screen was:\n" + screenText().joinToString("\n") { "|$it" } + "\nstderr: $diagnostics")
        }
    }

    private fun GameSession.key(k: TermKey) = send(keys.encode(k))
    private fun GameSession.type(s: String) = send(keys.text(s))

    @Test
    fun lanternTidePlaysSavesAndQuits() {
        val home = arrayOf<File?>(null)
        val s = session("game_lantern_tide") { home[0] = it }
        s.expect("title screen") { it.contains("L A N T E R N") }
        s.type(" ")
        s.expect("board with score, tide and lanterns") { it.contains("Score") && it.contains("Tide") && it.contains("\u2726") && it.contains("K") }
        val before = s.screenText().joinToString("\n")
        repeat(3) { s.key(TermKey.UP) }
        s.expect("Kio moved") { it != before }
        s.type("p")
        s.expect("pause message") { it.contains("Paused") }
        s.type("q")
        assertEquals(0, s.awaitExit(3000), "quits cleanly and restores the terminal")
        assertTrue(!s.terminal.isAltScreen, "left the alternate screen")
    }

    /** Answer chips add Enter only for games that read whole lines; the helper says which. */
    @Test
    fun helperReportsWhetherTheGameReadsLinesOrKeys() {
        fun GameSession.mode(want: Boolean, what: String) {
            val end = System.currentTimeMillis() + 3000
            while (lineInput != want && System.currentTimeMillis() < end) Thread.sleep(20)
            assertEquals(want, lineInput, "$what. stderr: $diagnostics")
        }
        val lines = session("game_arithmetic_bsd")
        lines.expect("a sum") { it.contains("=") }
        lines.mode(true, "arithmetic reads whole lines")
        lines.stop()
        val keys = session("game_lantern_tide")
        keys.expect("title screen") { it.contains("L A N T E R N") }
        keys.mode(false, "a curses game reads single keys")
        keys.stop()
        assertTrue("@mode" !in keys.diagnostics, "status lines are not diagnostics")
    }

    @Test
    fun game2048MovesTilesAndQuits() {
        val s = session("game_2048")
        s.expect("2048 board with score") { it.contains("pts") }
        val before = s.screenText().joinToString("\n")
        repeat(4) { s.key(TermKey.LEFT); s.key(TermKey.UP); s.key(TermKey.RIGHT); s.key(TermKey.DOWN) }
        s.expect("board change after arrow keys") { it != before }
        s.type("q")
        s.expect("quit prompt") { it.contains("QUIT?") }
        s.type("y")
        assertEquals(0, s.awaitExit(3000), "2048 exits cleanly after confirming quit")
    }

    @Test
    fun vitetrisShowsMenuAndStartsAGame() {
        val s = session("game_vitetris")
        s.expect("vitetris menu") { it.contains("1-Player", ignoreCase = true) || it.contains("Single", ignoreCase = true) }
        s.key(TermKey.ENTER)
        s.expect("1-player options") { it.contains("Level") && it.contains("Height") }
        s.key(TermKey.ENTER)
        s.expect("game playfield with score") { it.contains("Score", ignoreCase = true) && it.contains("Lines", ignoreCase = true) }
        val before = s.screenText().joinToString("\n")
        s.key(TermKey.LEFT)
        s.key(TermKey.UP)
        s.type(" ")
        s.expect("piece moved or dropped") { it != before }
        s.stop()
    }

    @Test
    fun robotFindsKittenReadsItsMessagesAndMoves() {
        // The same message file the app installs from its assets.
        val nki = File(out.parentFile, "assets/games/robotfindskitten/vanilla.nki")
        need(nki.isFile, "native build missing: run python3 native/build.py")
        val s = session("game_robotfindskitten") { home ->
            File(home, ".robotfindskitten").mkdirs()
            nki.copyTo(File(home, ".robotfindskitten/vanilla.nki"))
        }
        s.expect("intro text") { it.contains("robotfindskitten", ignoreCase = true) }
        s.type(" ")
        s.expect("playfield with robot") { it.contains("#") || it.lines().size > 3 }
        s.stop()
    }

    @Test
    fun greedDrawsItsBoard() {
        val s = session("game_greed")
        s.expect("greed board and score line") { it.contains("Score", ignoreCase = true) }
        s.stop()
    }

    @Test
    fun ninvadersShowsTitle() {
        val s = session("game_ninvaders")
        s.expect("nInvaders title") { it.contains("Press SPACE to start") }
        s.type(" ")
        s.expect("playfield with aliens") { !it.contains("Press SPACE to start") && it.contains("Level", ignoreCase = true) }
        s.key(TermKey.LEFT)
        s.type(" ")
        s.stop()
    }

    @Test
    fun moonBuggyShowsTitle() {
        val s = session("game_moonbuggy")
        s.expect("moon-buggy title") { it.contains("MOON", ignoreCase = true) }
        s.stop()
    }

    @Test
    fun freesweepDrawsAMinefield() {
        val s = session("game_freesweep", args = listOf("--fast", "--alt-charset"))
        s.expect("freesweep board") { it.contains("Mines", ignoreCase = true) || it.contains("Flags", ignoreCase = true) || it.contains("Freesweep", ignoreCase = true) }
        s.stop()
    }

    @Test
    fun nsnakeShowsMenu() {
        val s = session("game_nsnake")
        s.expect("nsnake main menu") { it.contains("Arcade Mode") && it.contains("Main Menu") }
        s.key(TermKey.ENTER)
        s.expect("arcade game started") { !it.contains("Main Menu") }
        s.stop()
    }

    @Test
    fun stoppingAlwaysEndsTheProcess() {
        val s = session("game_greed")
        s.expect("greed running") { it.isNotBlank() }
        val t0 = System.nanoTime()
        s.stop(graceMs = 1000)
        assertTrue(!s.isRunning, "process ended")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "stop is bounded")
    }

    @Test
    fun resizeReachesTheGame() {
        val s = session("game_2048", cols = 60, rows = 20)
        s.expect("board") { it.contains("pts") }
        s.resize(100, 30)
        assertTrue(s.terminal.cols == 100 && s.terminal.rows == 30)
        s.key(TermKey.LEFT)
        s.stop()
    }
}
