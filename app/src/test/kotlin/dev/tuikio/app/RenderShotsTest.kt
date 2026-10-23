package dev.tuikio.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import dev.tuikio.app.game.GameActivity
import dev.tuikio.app.game.GameLauncher
import dev.tuikio.app.game.TerminalView
import dev.tuikio.app.ui.Motion
import dev.tuikio.json.Json
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild

/**
 * Renders real games on the game screen with Robolectric's native (Skia) graphics and saves PNGs
 * of what a phone would show, portrait and landscape: the way to look at layout and text
 * rendering without a device. Runs only when asked:
 *
 *     ./gradlew :app:test --tests '*RenderShotsTest*' -Pshots=greed,vitetris   (or all, or primary:
 *     every game in the orientation it runs in)
 *
 * Images land in app/build/shots/.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderShotsTest {
    private val wanted = System.getProperty("tuikio.shots").orEmpty()

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    /** The view being shot: drawn every "frame", since Robolectric runs no draw passes itself. */
    private var frameView: View? = null
    private var scratch: Bitmap? = null

    /** Real time passes for the game process; the main looper keeps up with its output and frames are drawn. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            idle(16)
            frameView?.let { v ->
                if (v.width > 0 && v.height > 0) {
                    val b = scratch?.takeIf { it.width == v.width && it.height == v.height }
                        ?: Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { scratch = it }
                    v.draw(Canvas(b))
                }
            }
            Thread.sleep(16)
        }
    }

    /** Keys to send before the shot: native/tests/shots.json, else the game's play script (native/tests/play.json). */
    private val scripts: Map<String, List<String>> by lazy {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "native/tests").isDirectory }
        fun load(name: String) = ((Json.parse(File(root, "native/tests/$name").readText()) as Json.Obj).fields["games"] as Json.Obj)
            .fields.mapValues { (_, v) -> (v as Json.Arr).items.map { (it as Json.Str).value } }
        load("play.json") + load("shots.json")
    }

    private val KEYS = mapOf(
        "ENTER" to KeyEvent.KEYCODE_ENTER, "ESCAPE" to KeyEvent.KEYCODE_ESCAPE, "SPACE" to KeyEvent.KEYCODE_SPACE,
        "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    /**
     * Refitting without a size change (the drawn area grew, but the font size stays) must not
     * change a single pixel. It used to leave the text paint at a 100 px measuring size, so every
     * redrawn row showed tall, squeezed, overlapping glyphs.
     */
    @Test
    fun refitAtTheSameSizeKeepsTheRendering() {
        RuntimeEnvironment.setQualifiers("w914dp-h411dp-land-xxhdpi")
        val app = setUpApp()
        assumeTrue(File(app.applicationInfo.nativeLibraryDir, "libgame_greed.so").canExecute() || System.getProperty("tuikio.requireNative") == "true")
        val game = app.catalog.games.first { it.id == "greed" }
        app.installer.install(game) {}
        val c = Robolectric.buildActivity(GameActivity::class.java, Intent(app, GameActivity::class.java).putExtra(GameLauncher.EXTRA_GAME, game.id)).setup()
        val a = c.get()
        repeat(30) { idle(50); Thread.sleep(40) }
        val term = views(a.window.decorView).filterIsInstance<TerminalView>().first()
        // Wait for a settled screen: chips for the game's "Hit '?' for help" make room under it.
        var before = render(term)
        for (i in 0 until 40) {
            idle(100); Thread.sleep(60)
            val next = render(term)
            if (next.sameAs(before)) break
            before = next
        }
        term.refit()
        term.palette = term.palette // forces every row to be redrawn
        idle(50)
        val after = render(term)
        c.pause().stop().destroy()
        assertTrue("the terminal looks different after a refit that kept the font size", before.sameAs(after))
    }

    private fun render(v: View): Bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    private fun setUpApp(): TuikioApp {
        val app = RuntimeEnvironment.getApplication() as TuikioApp
        val nativeDir = File(System.getProperty("tuikio.nativeOut")!!)
        app.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!.nativeLibraryDir = nativeDir.absolutePath
        app.prefs.onboarded = true
        ShadowBuild.setSupportedAbis(arrayOf("x86_64"))
        return app
    }

    @Test
    fun portrait() = shoot("port", "w411dp-h914dp-port-xxhdpi")

    @Test
    fun landscape() = shoot("land", "w914dp-h411dp-land-xxhdpi")

    private fun shoot(tag: String, qualifiers: String) {
        assumeTrue("pass -Pshots=<ids|all> to render screenshots", wanted.isNotBlank())
        RuntimeEnvironment.setQualifiers(qualifiers)
        val app = setUpApp()
        Motion.setUserReduced(app, true)
        val out = File(System.getProperty("tuikio.shotsDir", "build/shots")).apply { mkdirs() }
        // "primary": every game, only in the orientation(s) it runs in.
        val ids = when (wanted) {
            "all" -> app.catalog.games.map { it.id }
            "primary" -> app.catalog.games.filter {
                it.runtime.orientation == dev.tuikio.catalog.Orientation.ANY ||
                    (it.runtime.orientation == dev.tuikio.catalog.Orientation.LANDSCAPE) == (tag == "land")
            }.map { it.id }
            else -> wanted.split(',').map { it.trim() }
        }
        for (id in ids) {
            val game = app.catalog.games.firstOrNull { it.id == id } ?: continue
            app.installer.install(game) {}
            val intent = Intent(app, GameActivity::class.java).putExtra(GameLauncher.EXTRA_GAME, game.id)
            val c = Robolectric.buildActivity(GameActivity::class.java, intent).setup()
            val a = c.get()
            // Let the game start and draw; the terminal refits as content appears.
            val term0 = views(a.window.decorView).filterIsInstance<TerminalView>().first()
            frameView = term0
            settle(1600)
            for (step in scripts[id].orEmpty()) {
                val (kind, arg) = step.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                when (kind) {
                    "text" -> term0.onText?.invoke(arg.replace("\n", "\r"))
                    "key" -> {
                        val code = KEYS[arg] ?: error("unknown key $arg")
                        term0.onKey?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, code)) // the view forwards key-downs only
                    }
                    "wait" -> settle(arg.toLong())
                    "expect" -> {
                        val emu = term0.emulator
                        val end = System.currentTimeMillis() + 6000
                        while (System.currentTimeMillis() < end && emu != null &&
                            !synchronized(emu.lock) { emu.screen.text().joinToString("\n").contains(arg) }) settle(100)
                    }
                    "type" -> term0.onText?.invoke(arg.replace("\n", "\r"))
                }
                settle(120)
            }
            settle(400)
            val root = a.window.decorView
            val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$id-$tag.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shots += id to bmp
            val term = views(root).filterIsInstance<TerminalView>().first()
            // The incremental (dirty-row) rendering must match a full repaint of the same screen.
            if (!term.bufferMatchesFullRepaint()) stale += "$id-$tag"
            val emu = term.emulator!!
            val text = synchronized(emu.lock) { emu.screen.text() }
            val flags = synchronized(emu.lock) {
                (0 until emu.rows).flatMap { r ->
                    val line = emu.screen[r]
                    (0 until emu.cols).filter { line.chars[it] in 0x2500..0x257F }.map { c ->
                        "r$r c$c U+%04X style=%x".format(line.chars[c], line.styles[c])
                    }
                }
            }
            File(out, "$id-$tag.cells").writeText(flags.joinToString("\n"))
            File(out, "$id-$tag.txt").writeText("view ${term.width}x${term.height} cell ${term.cellW}x${term.cellH} grid ${emu.cols}x${emu.rows}\n" +
                text.joinToString("\n") { "|$it" } + "\n")
            // For the audit: what the screen offers besides the game, and how big the game is drawn.
            val chips = views(root).filterIsInstance<dev.tuikio.app.ui.TButton>()
                .filter { it.isShown && it.contentDescription?.let { d -> d.startsWith("Answer ") || d.startsWith("Key ") } == true }
                .map { it.text.toString() }.toList()
            val (cr, cc) = synchronized(emu.lock) { emu.cursorRow to emu.cursorCol }
            File(out, "$id-$tag.json").writeText(Json.obj(
                "id" to id, "tag" to tag, "view" to listOf(term.width, term.height), "cell" to listOf(term.cellW.toDouble(), term.cellH.toDouble()),
                "grid" to listOf(emu.cols, emu.rows), "density" to app.resources.displayMetrics.density.toDouble(),
                "cursor" to listOf(cr, cc), "chips" to chips,
            ).write(pretty = true))
            frameView = null
            c.pause().stop().destroy()
        }
        Motion.setUserReduced(app, false)
        contactSheets(out, tag)
        assertTrue("incremental rendering differs from a full repaint: $stale", stale.isEmpty())
    }

    private val stale = ArrayList<String>()
    private val shots = ArrayList<Pair<String, Bitmap>>()

    /** Labelled thumbnails, 12 per sheet, for reviewing many games at a glance. */
    private fun contactSheets(out: File, tag: String) {
        if (shots.isEmpty()) return
        val (w0, h0) = shots[0].second.let { it.width to it.height }
        val cols = if (w0 > h0) 3 else 4
        val rows = if (w0 > h0) 4 else 3
        val tw = if (w0 > h0) 640 else 300
        val th = tw * h0 / w0
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD866.toInt(); textSize = 26f }
        shots.chunked(cols * rows).forEachIndexed { n, page ->
            val sheet = Bitmap.createBitmap(cols * tw, rows * (th + 34), Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(0xFF000000.toInt())
            page.forEachIndexed { i, (id, bmp) ->
                val x = (i % cols) * tw
                val y = (i / cols) * (th + 34)
                c.drawText(id, x + 8f, y + 26f, label)
                c.drawBitmap(bmp, null, android.graphics.Rect(x, y + 34, x + tw, y + 34 + th), null)
            }
            File(out, "sheet-$tag-${n + 1}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        shots.clear()
    }
}
