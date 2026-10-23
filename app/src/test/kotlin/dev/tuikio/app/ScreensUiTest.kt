package dev.tuikio.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.tuikio.app.game.GameActivity
import dev.tuikio.app.game.GameLauncher
import dev.tuikio.app.game.TerminalView
import dev.tuikio.app.screens.AboutScreen
import dev.tuikio.app.screens.AddSourceScreen
import dev.tuikio.app.screens.BrandStudioScreen
import dev.tuikio.app.screens.CreatorProfileScreen
import dev.tuikio.app.screens.CustomBrandScreen
import dev.tuikio.app.screens.DetailsScreen
import dev.tuikio.app.screens.DiagnosticsScreen
import dev.tuikio.app.screens.DiscoveryEntryScreen
import dev.tuikio.app.screens.LicensesScreen
import dev.tuikio.app.screens.PublishScreen
import dev.tuikio.app.screens.SearchScreen
import dev.tuikio.app.ui.Brands
import dev.tuikio.app.ui.Motion
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild

/** Every screen and every bundled game, on Robolectric's Android 16 framework. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScreensUiTest {
    private lateinit var app: TuikioApp
    private val nativeDir = File(System.getProperty("tuikio.nativeOut")!!)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as TuikioApp
        app.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!.nativeLibraryDir = nativeDir.absolutePath
        app.prefs.onboarded = true
        ShadowBuild.setSupportedAbis(arrayOf("x86_64"))
    }

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS) else shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, timeoutMs: Long = 8000, cond: () -> Boolean) {
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

    private fun clickableAncestor(v: View): View? {
        var c: View? = v
        while (c != null && !c.isClickable) c = c.parent as? View
        return c
    }

    /** Clicks the first view showing [label] that is (or sits inside) something clickable. */
    private fun click(root: View, label: String, exact: Boolean = true) {
        val matches = views(root).filter {
            // Not the strip under the game: its chips send keys to the game ("Quit" sends q).
            if (it.contentDescription?.let { d -> d.startsWith("Key ") || d.startsWith("Answer ") } == true) return@filter false
            val t = (it as? TextView)?.text?.toString()
            // Some brand packs set text in capitals, so compare case-insensitively.
            (if (exact) t.equals(label, ignoreCase = true) else t?.startsWith(label, ignoreCase = true) == true) ||
                it.contentDescription?.toString().equals(label, ignoreCase = true)
        }.toList()
        if (matches.isEmpty()) fail("no view labelled '$label' in ${texts(root)}")
        val target = matches.firstNotNullOfOrNull { clickableAncestor(it) } ?: fail("'$label' is not clickable") as Nothing
        target.performClick()
        idle()
    }

    private val opened = ArrayList<org.robolectric.android.controller.ActivityController<*>>()

    @org.junit.After
    fun tearDown() {
        opened.forEach { runCatching { it.pause().stop().destroy() } }
    }

    private fun main(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).also { opened += it }.setup().get().also { idle() }

    private fun screenText(a: GameActivity): String {
        val term = views(a.window.decorView).filterIsInstance<TerminalView>().firstOrNull() ?: return ""
        val emu = term.emulator ?: return ""
        return synchronized(emu.lock) { emu.screen.text().joinToString("\n") }
    }

    @Test
    fun everyGameInstallsRunsRotatesAndQuits() {
        Motion.setUserReduced(app, true)
        val failures = ArrayList<String>()
        for (game in app.catalog.games) {
            try {
                app.installer.install(game) {}
                val intent = Intent(app, GameActivity::class.java).putExtra(GameLauncher.EXTRA_GAME, game.id)
                val c = Robolectric.buildActivity(GameActivity::class.java, intent).setup()
                val a = c.get()
                waitFor("${game.id} to draw") { screenText(a).isNotBlank() }
                for (landscape in listOf(true, false)) {
                    val cfg = Configuration(a.resources.configuration).apply {
                        orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    }
                    c.configurationChange(cfg)
                    idle()
                }
                click(a.window.decorView, "Pause")
                val quit = texts(a.window.decorView).firstOrNull { it == "Save and quit" || it == "Quit" }
                    ?: throw AssertionError("no quit button; finishing=${a.isFinishing}; screen:\n" + screenText(a) + "\nviews: " + texts(a.window.decorView))
                click(a.window.decorView, quit)
                idle(800)
                if (!a.isFinishing) failures += "${game.id}: still running after quit"
                c.pause().stop().destroy()
            } catch (t: Throwable) {
                failures += "${game.id}: $t"
            }
        }
        Motion.setUserReduced(app, false)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyDetailsAndDiscoveryPageOpens() {
        val a = main()
        for (g in app.catalog.games) {
            a.nav.push(DetailsScreen(g.id))
            idle()
            assertTrue(g.id, texts(a.window.decorView).any { it.contains(g.title) })
            a.nav.handleBack()
            idle()
        }
        for (e in app.catalog.document.discovery.take(40)) {
            a.nav.push(DiscoveryEntryScreen(e.id))
            idle()
            a.nav.handleBack()
            idle()
        }
        a.nav.push(SearchScreen())
        idle()
    }

    @Test
    fun communityAndCreatorScreensOpenWithoutAServer() {
        val a = main()
        for (s in listOf(AddSourceScreen(), AddSourceScreen("https://github.com/someone/some-game"), PublishScreen(), CreatorProfileScreen())) {
            a.nav.push(s)
            idle()
            assertTrue(texts(a.window.decorView).isNotEmpty())
            a.nav.handleBack()
            idle()
        }
    }

    @Test
    fun brandStudioAppliesEveryPackAndSwitchesTheLauncher() {
        val a = main()
        a.nav.push(BrandStudioScreen())
        idle()
        // Every other pack, then back to the default one.
        for (pack in Brands.ALL.drop(1) + Brands.ALL.first()) {
            click(a.window.decorView, pack.displayName)
            click(a.window.decorView, "Apply ${pack.displayName}")
            idle()
            val enabled = Brands.ALL.filter {
                val state = app.packageManager.getComponentEnabledSetting(android.content.ComponentName(app, it.alias))
                state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            assertEquals("exactly one launcher identity after applying ${pack.displayName}", listOf(pack), enabled)
        }
        a.nav.push(CustomBrandScreen())
        idle()
    }

    @Test
    fun aboutLicensesAndDiagnostics() {
        val a = main()
        a.nav.push(AboutScreen())
        idle()
        a.nav.push(AboutScreen(privacy = true))
        idle()
        a.nav.push(LicensesScreen())
        idle()
        val files = app.assets.list("licenses")!!.filter { it.endsWith(".txt") }
        assertTrue("license texts are bundled", files.size >= 10)
        for (f in files) {
            a.nav.push(LicensesScreen(f))
            idle()
            a.nav.handleBack()
            idle()
        }
        a.nav.push(DiagnosticsScreen())
        idle()
        click(a.window.decorView, "Run the runtime check")
        waitFor("diagnostics result", 15000) { texts(a.window.decorView).any { it.contains("RESULT:") } }
        val report = texts(a.window.decorView).first { it.contains("RESULT:") }
        assertTrue(report, report.contains("RESULT: PASS"))
    }
}
