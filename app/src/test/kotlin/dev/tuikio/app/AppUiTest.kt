package dev.tuikio.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.tuikio.app.game.GameActivity
import dev.tuikio.app.game.GameLauncher
import dev.tuikio.app.game.TerminalView
import dev.tuikio.app.nav.Tab
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild

/**
 * The app on Robolectric's real Android framework, driving the real x86_64 game binaries.
 * These paths only run on Android, which is why they need more than unit tests: each of the
 * crashes found on the first device run (insets before setContentView, rotation re-parenting,
 * a detached view's null Handler on quit) is reproduced here.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AppUiTest {
    private lateinit var app: TuikioApp
    private val nativeDir = File(System.getProperty("tuikio.nativeOut")!!)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as TuikioApp
        // The binaries the package installer would have extracted.
        app.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!.nativeLibraryDir = nativeDir.absolutePath
        app.prefs.onboarded = true
        // The host-built binaries are x86_64; install checks them against that ABI's checksums.
        ShadowBuild.setSupportedAbis(arrayOf("x86_64"))
    }

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS) else shadowOf(Looper.getMainLooper()).idle()
    }

    /** Waits for real background work (game output, installs) while running the main looper. */
    private fun waitFor(what: String, timeoutMs: Long = 6000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle(20)
            if (cond()) return
            Thread.sleep(30)
        }
        fail("timed out waiting for $what")
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun texts(v: View) = views(v).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    /** Clicks the clickable view that shows [label] (as text or content description). */
    private fun click(root: View, label: String) {
        val target = views(root).firstOrNull { (it is TextView && it.text.toString() == label) || it.contentDescription?.toString() == label }
            ?: fail("no view labelled '$label' in ${texts(root)}") as Nothing
        var v: View? = target
        while (v != null && !v.isClickable) v = v.parent as? View
        (v ?: fail("'$label' is not clickable") as Nothing).performClick()
        idle()
    }

    private fun install(id: String) {
        val game = app.catalog.game(id)!!
        app.installer.install(game) {}
        assertTrue(app.library.isInstalled(id))
    }

    private fun gameActivity(id: String): ActivityController<GameActivity> {
        val intent = Intent(app, GameActivity::class.java).putExtra(GameLauncher.EXTRA_GAME, id)
        return Robolectric.buildActivity(GameActivity::class.java, intent).setup()
    }

    private fun screenText(a: GameActivity): String {
        val term = views(a.window.decorView).filterIsInstance<TerminalView>().first()
        val emu = term.emulator ?: return ""
        return synchronized(emu.lock) { emu.screen.text().joinToString("\n") }
    }

    private fun rotate(c: ActivityController<GameActivity>, landscape: Boolean) {
        val config = Configuration(c.get().resources.configuration).apply {
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            val w = screenWidthDp
            screenWidthDp = screenHeightDp
            screenHeightDp = w
        }
        c.configurationChange(config)
        idle()
    }

    /** Draws a view into a bitmap so its onDraw code runs (Robolectric doesn't render). */
    private fun drawOnce(v: View) {
        if (v.width == 0 || v.height == 0) v.layout(0, 0, 1080, 1600)
        v.draw(Canvas(Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun everyTabOpensAndShowsTheCatalog() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val a = c.get()
        assertTrue("Discover lists 2048", texts(a.window.decorView).any { it.contains("2048") })
        for (tab in Tab.values()) {
            a.openTab(tab)
            idle()
            assertTrue("$tab shows something", texts(a.window.decorView).isNotEmpty())
            views(a.window.decorView).forEach { if (it.width > 0) drawOnce(it) }
        }
        c.pause().stop().destroy()
    }

    @Test
    fun aGameStartsDrawsSurvivesRotationAndQuits() {
        install("2048")
        val c = gameActivity("2048")
        val a = c.get()
        waitFor("2048 to draw its board") { screenText(a).contains("pts") }
        views(a.window.decorView).forEach { drawOnce(it) }

        rotate(c, landscape = true)
        assertTrue(views(a.window.decorView).any { it is TerminalView && it.parent != null })
        rotate(c, landscape = false)
        rotate(c, landscape = true)

        click(a.window.decorView, "Pause")
        click(a.window.decorView, "Save and quit")
        idle(800)
        assertTrue("activity finishes after quit", a.isFinishing)
        c.pause().stop().destroy()
    }

    /**
     * With animations off (Battery Saver, accessibility, developer options) sheets close at once,
     * so their buttons are already detached when the click handler runs: the device crash.
     */
    @Test
    fun quittingWithReducedMotion() {
        dev.tuikio.app.ui.Motion.setUserReduced(app, true)
        try {
            install("2048")
            val c = gameActivity("2048")
            val a = c.get()
            waitFor("2048 to draw") { screenText(a).contains("pts") }
            click(a.window.decorView, "Pause")
            click(a.window.decorView, "Save and quit")
            idle(800)
            assertTrue("activity finishes after quit", a.isFinishing)
            c.pause().stop().destroy()
        } finally {
            dev.tuikio.app.ui.Motion.setUserReduced(app, false)
        }
    }

    @Test
    fun backgroundingPausesAndResumesTheGame() {
        install("2048")
        val c = gameActivity("2048")
        waitFor("output") { screenText(c.get()).contains("pts") }
        c.pause().stop()
        idle()
        c.restart().start().resume()
        idle()
        c.pause().stop().destroy()
    }

    @Test
    fun portraitAndLandscapeStartsWork() {
        install("vitetris")
        val c = gameActivity("vitetris")
        c.get().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        rotate(c, landscape = true)
        waitFor("vitetris to draw") { screenText(c.get()).isNotBlank() }
        c.pause().stop().destroy()
    }
}
