package dev.tuikio.runtime

import dev.tuikio.catalog.GameManifest
import dev.tuikio.terminal.KeyEncoder
import dev.tuikio.terminal.MouseEncoder
import dev.tuikio.terminal.TerminalEmulator
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Frames understood by native/pty/tuikio_pty.c on its stdin. */
object PtyFrames {
    const val INPUT = 1
    const val RESIZE = 2
    const val SIGNAL = 3
    const val MAX_PAYLOAD = 4096

    const val SIG_INT = 1
    const val SIG_HUP = 2
    const val SIG_TERM = 3
    const val SIG_CONT = 4

    /** Freezes the game (and its timers) while the app is in the background. */
    const val SIG_STOP = 5

    /** Terminal stop request: lets games that handle it save state before [SIG_STOP]. */
    const val SIG_TSTP = 6

    fun frame(type: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): ByteArray {
        require(len <= MAX_PAYLOAD)
        val out = ByteArray(3 + len)
        out[0] = type.toByte()
        out[1] = (len ushr 8).toByte()
        out[2] = len.toByte()
        System.arraycopy(payload, off, out, 3, len)
        return out
    }

    fun resize(cols: Int, rows: Int): ByteArray =
        frame(RESIZE, byteArrayOf((cols ushr 8).toByte(), cols.toByte(), (rows ushr 8).toByte(), rows.toByte()))
}

/** Everything needed to start one game process. */
data class LaunchSpec(
    val ptyHelper: File,
    val executable: File,
    val args: List<String>,
    val home: File,
    val env: Map<String, String>,
) {
    fun command(cols: Int, rows: Int): List<String> =
        listOf(ptyHelper.absolutePath, cols.toString(), rows.toString(), executable.absolutePath) + args

    companion object {
        const val PTY_HELPER = "libtuikio_pty.so"
        /** native/pty/tuikio_pager.c: games that page their instructions get it as $PAGER. */
        const val PAGER = "libtuikio_pager.so"

        /**
         * Resolves a manifest's executable inside [nativeLibDir] only. Android extracts
         * `lib*.so` files there from the signed APK and the directory is read-only to the app,
         * which is what makes executing them permissible on API 29+.
         */
        fun forManifest(manifest: GameManifest, nativeLibDir: File, home: File): LaunchSpec {
            val exe = File(nativeLibDir, "lib${manifest.runtime.executable}.so")
            val helper = File(nativeLibDir, PTY_HELPER)
            require(exe.parentFile.canonicalPath == nativeLibDir.canonicalPath) { "executable escapes the native library directory" }
            if (!exe.isFile) throw IOException("${manifest.title} is not part of this build (${exe.name} missing)")
            if (!helper.isFile) throw IOException("runtime helper missing (${helper.name})")
            val pager = File(nativeLibDir, PAGER)
            val extra = (if (pager.isFile) mapOf("PAGER" to pager.absolutePath) else emptyMap()) + manifest.runtime.env
            return LaunchSpec(helper, exe, manifest.runtime.args, home, GameEnvironment.build(home, extra))
        }
    }
}

/**
 * The environment every game sees. Nothing is inherited from the app process: games get a
 * private HOME (their save directory), a terminal description matching [TerminalEmulator],
 * UTF-8, and no shell or PATH to escape into.
 */
object GameEnvironment {
    fun build(home: File, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val tmp = File(home, ".tmp")
        val env = linkedMapOf(
            "HOME" to home.absolutePath,
            // Shells set PWD and some games read it unchecked; the game starts in its home.
            "PWD" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "USER" to "player",
            "LOGNAME" to "player",
            "SHELL" to "/nonexistent",
            "PATH" to "/nonexistent",
            // ncurses waits this long after ESC to tell it from an arrow key's prefix.
            "ESCDELAY" to "40",
        )
        env.putAll(extra)
        return env
    }
}

/**
 * One running game: the process, its terminal state and its input encoders. Output is read on
 * a dedicated thread and fed into [terminal] under [TerminalEmulator.lock]; the UI renders from
 * the same lock. Stopping always ends the process: closing stdin makes the helper hang up the
 * game, and a forced kill follows after a grace period.
 */
class GameSession(
    private val spec: LaunchSpec,
    cols: Int,
    rows: Int,
    scrollback: Int = 400,
    private val starter: (List<String>, Map<String, String>, File) -> Process = ::defaultStart,
) {
    interface Listener {
        /** Called on the reader thread after new output was applied to [terminal]. */
        fun onOutput() {}
        fun onExit(exitCode: Int) {}

        /** Called on a reader thread when [lineInput] changes. */
        fun onInputMode(lineInput: Boolean) {}
    }

    @Volatile
    var listener: Listener? = null

    val terminal = TerminalEmulator(cols, rows, scrollback) { reply -> send(reply) }
    val keys = KeyEncoder(terminal)
    val mouse = MouseEncoder(terminal)

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private val writeLock = Any()
    private val stderrTail = StringBuilder()

    @Volatile
    var exitCode: Int? = null
        private set

    val isRunning: Boolean get() = process?.isAlive == true

    /** Last lines the helper or game wrote to stderr; useful in error reports, never contains input. */
    val diagnostics: String get() = synchronized(stderrTail) { stderrTail.toString() }

    /**
     * Whether the game reads whole lines (true: an answer needs Enter) or single keys (false), as
     * the helper reports from the terminal's mode; null until it has.
     */
    @Volatile
    var lineInput: Boolean? = null
        private set

    fun start() {
        check(process == null) { "already started" }
        spec.home.mkdirs()
        File(spec.home, ".tmp").mkdirs()
        val p = starter(spec.command(terminal.cols, terminal.rows), spec.env, spec.home)
        process = p
        stdin = p.outputStream
        thread("tuikio-game-out") { pump(p.inputStream) }
        thread("tuikio-game-err") { drainErrors(p.errorStream) }
        thread("tuikio-game-wait") {
            val code = try {
                p.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            exitCode = code
            listener?.onExit(code)
        }
    }

    private fun pump(input: InputStream) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                synchronized(terminal.lock) { terminal.feed(buf, 0, n) }
                listener?.onOutput()
            }
        } catch (e: IOException) {
            // Process ended or stream closed by stop(); the wait thread reports the exit.
        }
    }

    private fun drainErrors(err: InputStream) {
        try {
            err.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                when (line) {
                    "@mode line", "@mode keys" -> {
                        val lines = line == "@mode line"
                        lineInput = lines
                        listener?.onInputMode(lines)
                    }
                    else -> synchronized(stderrTail) {
                        stderrTail.append(line).append('\n')
                        if (stderrTail.length > 4096) stderrTail.delete(0, stderrTail.length - 4096)
                    }
                }
            }
        } catch (e: IOException) {
            // ignore: stream closed
        }
    }

    /** Sends raw input bytes to the game, split into protocol frames. Thread-safe. */
    fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(writeLock) {
            val out = stdin ?: return
            try {
                var off = 0
                while (off < bytes.size) {
                    val len = minOf(PtyFrames.MAX_PAYLOAD, bytes.size - off)
                    out.write(PtyFrames.frame(PtyFrames.INPUT, bytes, off, len))
                    off += len
                }
                out.flush()
            } catch (e: IOException) {
                // The game already exited; input is dropped.
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        synchronized(terminal.lock) { terminal.resize(cols, rows) }
        writeFrame(PtyFrames.resize(cols, rows))
    }

    fun signal(sig: Int) = writeFrame(PtyFrames.frame(PtyFrames.SIGNAL, byteArrayOf(sig.toByte())))

    private val suspendLock = Any()
    private var suspended = false
    private var suspendGeneration = 0

    /**
     * Pauses the game the way terminal job control does: SIGTSTP first, so games that handle
     * it can save their state and restore the terminal, then SIGSTOP after [graceMs] so every
     * game is frozen for certain. (The game leads its own session, so the kernel would discard
     * a default-action SIGTSTP; that's why the SIGSTOP follows.)
     */
    fun suspend(graceMs: Long = 250) {
        synchronized(suspendLock) {
            if (suspended || process == null) return
            suspended = true
            val token = ++suspendGeneration
            signal(PtyFrames.SIG_TSTP)
            scheduler.schedule({
                synchronized(suspendLock) { if (suspended && suspendGeneration == token) signal(PtyFrames.SIG_STOP) }
            }, graceMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Undoes [suspend]. Does nothing when the game wasn't suspended: some games (moon-buggy)
     * assume every SIGCONT follows their own SIGTSTP handling and crash otherwise.
     */
    fun resume() {
        synchronized(suspendLock) {
            if (!suspended) return
            suspended = false
            suspendGeneration++
            signal(PtyFrames.SIG_CONT)
        }
    }

    val isSuspended: Boolean get() = synchronized(suspendLock) { suspended }

    private fun writeFrame(frame: ByteArray) {
        synchronized(writeLock) {
            try {
                stdin?.apply { write(frame); flush() }
            } catch (e: IOException) {
                // exited
            }
        }
    }

    /**
     * Ends the game and waits for it. Most games save on SIGHUP or have already saved; games
     * that need an explicit "save and quit" key get it from their control profile first.
     */
    fun stop(graceMs: Long = 1500): Int? {
        val p = process ?: return null
        synchronized(writeLock) {
            try {
                stdin?.close()
            } catch (e: IOException) {
                // already closed
            }
            stdin = null
        }
        if (!p.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
            p.destroy()
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            p.waitFor(1, TimeUnit.SECONDS)
        }
        return if (p.isAlive) null else p.exitValue()
    }

    /** Waits until the screen satisfies [predicate] or the timeout passes. For tests and scripted demos. */
    fun awaitScreen(timeoutMs: Long, predicate: (List<String>) -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val text = synchronized(terminal.lock) { terminal.screen.text() }
            if (predicate(text)) return true
            Thread.sleep(20)
        }
        return false
    }

    /** Waits for the game to exit by itself; returns its exit code or null on timeout. */
    fun awaitExit(timeoutMs: Long): Int? {
        val p = process ?: return null
        return if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.exitValue() else null
    }

    fun screenText(): List<String> = synchronized(terminal.lock) { terminal.screen.text() }

    companion object {
        private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tuikio-game-suspend").apply { isDaemon = true }
        }

        fun defaultStart(command: List<String>, env: Map<String, String>, cwd: File): Process {
            val pb = ProcessBuilder(command).directory(cwd)
            pb.environment().clear()
            pb.environment().putAll(env)
            return pb.start()
        }

        private fun thread(name: String, body: () -> Unit) {
            Thread(body, name).apply { isDaemon = true }.start()
        }
    }
}
