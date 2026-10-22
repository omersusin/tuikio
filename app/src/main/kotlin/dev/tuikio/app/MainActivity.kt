package dev.tuikio.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import dev.tuikio.app.nav.Dock
import dev.tuikio.app.nav.Navigator
import dev.tuikio.app.nav.Tab
import dev.tuikio.app.screens.CreateScreen
import dev.tuikio.app.screens.DiscoverScreen
import dev.tuikio.app.screens.LibraryScreen
import dev.tuikio.app.screens.OnboardingScreen
import dev.tuikio.app.screens.YouScreen
import dev.tuikio.app.ui.Insets
import dev.tuikio.app.ui.Sheet
import dev.tuikio.app.ui.Theme
import dev.tuikio.app.ui.dp

class MainActivity : Activity() {
    companion object {
        /**
         * A one-shot notice from another of our own components (e.g. the sign-in callback).
         * Kept in-process rather than in an intent extra: the launcher aliases are exported,
         * so any app could otherwise start us with a forged "Signed in" message.
         */
        @Volatile
        var pendingMessage: String? = null
    }

    lateinit var nav: Navigator
        private set
    private lateinit var root: FrameLayout
    private lateinit var dock: Dock
    private val themeListener: () -> Unit = {
        applyWindowColors()
        nav.rebuild()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.resolve()
        edgeToEdge()
        root = FrameLayout(this)
        val host = FrameLayout(this)
        root.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dock = Dock(this) { tab -> openTab(tab) }
        root.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(16))
        })
        nav = Navigator(host, root, dock)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets -> onInsets(insets) }
        applyWindowColors()

        val app = TuikioApp.instance
        if (!app.prefs.onboarded) nav.setRoot(OnboardingScreen { finishOnboarding() })
        else openTab(Tab.DISCOVER)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        Theme.addListener(themeListener)
        showPendingMessage()
    }

    override fun onDestroy() {
        Theme.removeListener(themeListener)
        super.onDestroy()
    }

    private fun finishOnboarding() {
        TuikioApp.instance.prefs.onboarded = true
        openTab(Tab.DISCOVER)
    }

    fun openTab(tab: Tab) {
        val screen = when (tab) {
            Tab.DISCOVER -> DiscoverScreen()
            Tab.LIBRARY -> LibraryScreen()
            Tab.CREATE -> CreateScreen()
            Tab.YOU -> YouScreen()
        }
        if (nav.current?.tab == tab && nav.depth == 1) return
        nav.setRoot(screen)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        showPendingMessage()
    }

    private fun showPendingMessage() {
        val msg = pendingMessage ?: return
        pendingMessage = null
        root.post { dev.tuikio.app.ui.Snack.show(root, msg); nav.rebuild() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            dev.tuikio.app.screens.SaveTransfer.REQ_EXPORT, dev.tuikio.app.screens.SaveTransfer.REQ_IMPORT ->
                dev.tuikio.app.screens.SaveTransfer.onResult(this, requestCode, data?.data)
            dev.tuikio.app.brand.BrandManager.REQ_PICK_LOGO -> dev.tuikio.app.brand.BrandManager.onLogoPicked(this, data?.data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun handleBack(): Boolean {
        // An open sheet closes first, then inline steps, then the stack.
        for (i in root.childCount - 1 downTo 0) {
            val sheet = root.getChildAt(i).getTag(dev.tuikio.app.R.id.tag_sheet) as? Sheet
            if (sheet != null) {
                sheet.dismiss()
                return true
            }
        }
        if (nav.handleBack()) return true
        if (nav.current?.tab != Tab.DISCOVER && nav.current?.tab != null) {
            openTab(Tab.DISCOVER)
            return true
        }
        finish()
        return true
    }

    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun onInsets(insets: WindowInsets): WindowInsets {
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            top = i.top
            bottom = maxOf(i.bottom, ime.bottom)
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
        }
        Insets.top = top
        Insets.bottom = bottom
        (dock.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(16) + bottom
        dock.requestLayout()
        root.getChildAt(0).setPadding(0, 0, 0, 0)
        Insets.notifyChanged()
        return insets
    }

    @Suppress("DEPRECATION")
    private fun applyWindowColors() {
        val p = Theme.palette
        window.decorView.setBackgroundColor(p.ground)
        if (Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (p.isDark) 0 else light, light)
            return
        }
        var flags = window.decorView.systemUiVisibility
        flags = if (!p.isDark) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        flags = if (!p.isDark) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.decorView.systemUiVisibility = flags
    }
}
