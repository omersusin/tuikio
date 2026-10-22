package dev.tuikio.terminal

import java.io.File

/**
 * Parse throughput of [TerminalEmulator] on real game output (streams recorded by
 * tools/perf/measure_games.py --capture) plus a synthetic worst case: full-screen repaints
 * with a truecolor SGR sequence on every cell. Run: ./gradlew :core:terminal:bench -Pcaptures=DIR
 */
object TerminalBench {
    @JvmStatic
    fun main(args: Array<String>) {
        val inputs = LinkedHashMap<String, ByteArray>()
        args.firstOrNull()?.let { dir -> File(dir).listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.name }?.forEach { inputs[it.nameWithoutExtension] = it.readBytes() } }
        inputs["synthetic truecolor repaint"] = synthetic()
        println(String.format("%-28s %10s %12s", "stream", "bytes", "MB/s (JVM)"))
        for ((name, data) in inputs) {
            if (data.isEmpty()) continue
            val term = TerminalEmulator(80, 24)
            // Warm up the JIT, then time enough repetitions for a stable figure.
            val reps = maxOf(1, (8_000_000 / data.size))
            repeat(reps) { term.feed(data) }
            val t0 = System.nanoTime()
            repeat(reps) { term.feed(data) }
            val secs = (System.nanoTime() - t0) / 1e9
            println(String.format("%-28s %10d %12.1f", name, data.size, data.size.toDouble() * reps / secs / 1e6))
        }
    }

    private fun synthetic(): ByteArray {
        val sb = StringBuilder()
        repeat(10) { frame ->
            sb.append("\u001b[H")
            for (r in 0 until 24) for (c in 0 until 80) {
                sb.append("\u001b[38;2;${(r * 10 + frame) % 256};${c * 3 % 256};200m").append(if ((r + c + frame) % 7 == 0) '█' else 'a' + (c % 26))
            }
        }
        return sb.toString().toByteArray()
    }
}
