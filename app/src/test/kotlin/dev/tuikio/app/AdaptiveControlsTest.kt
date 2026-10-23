package dev.tuikio.app

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.tuikio.app.game.ControlsView
import dev.tuikio.app.game.GameActivity
import dev.tuikio.app.game.GameLauncher
import dev.tuikio.app.game.TerminalView
import dev.tuikio.app.ui.Motion
import dev.tuikio.app.ui.TButton
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild

/** The game screen's controls follow the game: button states and answer chips, with real games. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AdaptiveControlsTest {
    private lateinit var app: TuikioApp
    private val nativeDir = File(System.getProperty("tuikio.nativeOut")!!)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as TuikioApp
        app.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!.nativeLibraryDir = nativeDir.absolutePath
        app.prefs.onboarded = true
        ShadowBuild.setSupportedAbis(arrayOf("x86_64"))
        Motion.setUserReduced(app, true)
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun waitFor(what: String, a: GameActivity, timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(40, TimeUnit.MILLISECONDS)
            if (cond()) return
            Thread.sleep(30)
        }
        fail("timed out waiting for $what. Screen:\n" + screen(a))
    }

    private fun screen(a: GameActivity): String {
        val emu = views(a.window.decorView).filterIsInstance<TerminalView>().first().emulator ?: return ""
        return synchronized(emu.lock) { emu.screen.text().joinToString("\n") }
    }

    private fun chips(a: GameActivity) = views(a.window.decorView).filterIsInstance<TButton>()
        .filter { it.contentDescription?.startsWith("Answer ") == true && it.isShown }.map { it.text.toString() }.toList()

    private fun launch(id: String): GameActivity {
        val game = app.catalog.games.first { it.id == id }
        assumeTrue(File(nativeDir, "lib${game.runtime.executable}.so").canExecute() || System.getProperty("tuikio.requireNative") == "true")
        app.installer.install(game) {}
        return Robolectric.buildActivity(GameActivity::class.java, Intent(app, GameActivity::class.java).putExtra(GameLauncher.EXTRA_GAME, id)).setup().get()
    }

    @Test
    fun pauseButtonBecomesResumeWhileTheGameIsPaused() {
        val a = launch("lantern-tide")
        val term = views(a.window.decorView).filterIsInstance<TerminalView>().first()
        val controls = views(a.window.decorView).filterIsInstance<ControlsView>().first()
        waitFor("title", a) { screen(a).contains("L A N T E R N") }
        term.onText!!(" ")
        waitFor("board", a) { screen(a).contains("Tide") }
        controls.onPress("pause")
        controls.onRelease("pause")
        waitFor("the Pause button to read Resume", a) { controls.contentDescription?.contains("Resume") == true }
        controls.onPress("pause") // sends the state's output: resumes
        controls.onRelease("pause")
        waitFor("the button to read Pause again", a) { controls.contentDescription == null && !screen(a).contains("Paused") }
    }

    @Test
    fun questionsOfferTheirAnswersAsChips() {
        val a = launch("hack-bsd")
        val term = views(a.window.decorView).filterIsInstance<TerminalView>().first()
        waitFor("name prompt", a) { screen(a).contains("Who are you?") }
        assertTrue("no chips for a free-text question: ${chips(a)}", chips(a).isEmpty())
        term.onText!!("Kio\r")
        waitFor("Yes/No chips", a) { chips(a) == listOf("Yes", "No") }
        // hack reads single keys: "y" alone answers, an Enter after it would pick a class at random.
        views(a.window.decorView).filterIsInstance<TButton>().first { it.text == "Yes" && it.isShown }.performClick()
        waitFor("one chip per character class", a) { chips(a) == listOf("T", "S", "F", "K", "C", "W") }
        views(a.window.decorView).filterIsInstance<TButton>().first { it.text == "F" && it.isShown }.performClick()
        waitFor("the game to start", a) { screen(a).contains("welcome to hack") }
        // Only a "--More--" can still ask for something now.
        waitFor("the class chips to go away", a) { chips(a).isEmpty() || (chips(a) == listOf("Continue") && screen(a).contains("--More--")) }
    }
}
