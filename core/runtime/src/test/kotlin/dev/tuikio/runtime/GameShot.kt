package dev.tuikio.runtime

import dev.tuikio.catalog.GameManifest
import dev.tuikio.terminal.CellStyle
import dev.tuikio.terminal.TermColor
import dev.tuikio.terminal.TermKey
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Host "screenshots" of real games: runs a game through the same PTY helper, GameSession and
 * TerminalEmulator as the app, follows a small input script, and renders the terminal to a PNG
 * with the app's terminal font. Also prints the screen text and the box of cells the game drew
 * (what the app's content framing will fit to the phone).
 *
 *   ./gradlew :core:runtime:shot -Pgame=2048 [-Pscript="wait 800; key UP; wait 400"] [-Pout=build/shots]
 *   ./gradlew :core:runtime:shot -Pgame=all
 *
 * Script steps: `wait <ms>`, `key <TermKey>`, `text <chars>` (use \n for Enter), `size <cols>x<rows>`.
 */
object GameShot {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "catalog/games").isDirectory }
    private val nativeOut = File(System.getProperty("tuikio.nativeOut", File(root, "native/build/out").path), "x86_64")

    @JvmStatic
    fun main(args: Array<String>) {
        val game = args.getOrNull(0) ?: "all"
        val script = args.getOrNull(1)?.takeIf { it.isNotBlank() }
        val outDir = File(args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: File(root, "build/shots").path).apply { mkdirs() }
        val ids = if (game == "all") File(root, "catalog/games").listFiles { f -> f.name.endsWith(".json") }!!.map { it.nameWithoutExtension }.sorted() else listOf(game)
        for (id in ids) shoot(id, script ?: defaultScript(id), outDir)
    }

    private fun defaultScript(id: String): String {
        val f = File(root, "catalog/shots/$id.txt")
        return if (f.isFile) f.readText() else "wait 1500"
    }

    fun shoot(id: String, script: String, outDir: File) {
        val manifest = GameManifest.parse(File(root, "catalog/games/$id.json").readText())
        var cols = manifest.runtime.preferredTerminal.cols
        var rows = manifest.runtime.preferredTerminal.rows
        val home = Files.createTempDirectory("shot-$id-").toFile()
        // Data files the app would install from assets (e.g. robotfindskitten's messages).
        for (d in manifest.dataFiles) {
            val src = File(nativeOut.parentFile, "assets/${d.asset}")
            if (src.isFile) src.copyTo(File(home, d.dest).apply { parentFile.mkdirs() }, overwrite = true)
        }
        val steps = script.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        steps.firstOrNull { it.startsWith("size ") }?.let { s ->
            val (c, r) = s.removePrefix("size ").split('x').map { it.trim().toInt() }
            cols = c; rows = r
        }
        val spec = LaunchSpec.forManifest(manifest, nativeOut, home)
        val session = GameSession(spec, cols, rows)
        session.start()
        var shotIndex = 0
        for (step in steps) {
            val arg = step.substringAfter(' ', "")
            when (step.substringBefore(' ')) {
                "wait" -> Thread.sleep(arg.toLong())
                "key" -> session.send(session.keys.encode(TermKey.valueOf(arg)))
                "text" -> session.send(session.keys.text(arg.replace("\\n", "\r").replace("\\e", "\u001b")))
                "shot" -> render(session, File(outDir, "$id-${++shotIndex}.png"))
            }
        }
        val png = File(outDir, "$id.png")
        val box = render(session, png)
        println("== $id ${cols}x$rows  drawn box: cols ${box[0]}..${box[2]}, rows ${box[1]}..${box[3]}  -> $png")
        session.screenText().forEachIndexed { i, l -> println(String.format("%2d|%s", i, l)) }
        session.stop(800)
    }

    private val ansi = intArrayOf(
        0x1D1A38, 0xFF6B6B, 0x3BE3B0, 0xFFC83D, 0x6AA7FF, 0xC48BFF, 0x4DD9E6, 0xD9D4F2,
        0x5B5680, 0xFF8F8F, 0x7DF0CD, 0xFFDA75, 0x9CC4FF, 0xDAB6FF, 0x8AE9F2, 0xFFFFFF,
    )

    private fun color(c: Int, fg: Boolean): Color = Color(
        when {
            c == TermColor.DEFAULT -> if (fg) 0xF3F0FF else 0x0E0C20
            TermColor.isRgb(c) -> TermColor.rgbValue(c)
            c < 16 -> ansi[c]
            else -> TermColor.xterm256(c)
        },
    )

    /** Renders the screen; returns the drawn-cells box [c0, r0, c1, r1]. */
    private fun render(session: GameSession, file: File): IntArray {
        val term = session.terminal
        val font = Font.createFont(Font.TRUETYPE_FONT, File(root, "design/fonts/JetBrainsMono-Variable.ttf")).deriveFont(22f)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().apply { this.font = font }
        val fm = probe.fontMetrics
        val cw = fm.charWidth('M')
        val ch = fm.height
        val box = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
        synchronized(term.lock) {
            val img = BufferedImage(term.cols * cw, term.rows * ch, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = font
            g.color = color(TermColor.DEFAULT, false)
            g.fillRect(0, 0, img.width, img.height)
            for (r in 0 until term.rows) {
                val line = term.screen[r]
                for (c in 0 until term.cols) {
                    val st = line.styles[c]
                    val cp = line.chars[c]
                    var fg = color(CellStyle.fg(st), true)
                    var bg = color(CellStyle.bg(st), false)
                    if (CellStyle.has(st, CellStyle.INVERSE)) { val t = fg; fg = bg; bg = t }
                    val ink = (cp != 0 && cp != ' '.code) || CellStyle.bg(st) != TermColor.DEFAULT || CellStyle.has(st, CellStyle.INVERSE)
                    if (ink) {
                        box[0] = minOf(box[0], c); box[1] = minOf(box[1], r); box[2] = maxOf(box[2], c); box[3] = maxOf(box[3], r)
                    }
                    g.color = bg
                    g.fillRect(c * cw, r * ch, cw, ch)
                    if (cp != 0 && cp != ' '.code && !CellStyle.has(st, CellStyle.WIDE_TAIL)) {
                        g.color = fg
                        g.drawString(String(Character.toChars(cp)), c * cw, r * ch + fm.ascent)
                    }
                }
            }
            ImageIO.write(img, "png", file)
        }
        return box
    }
}
