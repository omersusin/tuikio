package dev.tuikio.app.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher
import dev.tuikio.app.TuikioApp
import dev.tuikio.app.ui.ButtonStyle
import dev.tuikio.app.ui.Fonts
import dev.tuikio.app.ui.FontFace
import dev.tuikio.app.ui.Icon
import dev.tuikio.app.ui.MATCH
import dev.tuikio.app.ui.Sheet
import dev.tuikio.app.ui.T
import dev.tuikio.app.ui.TButton
import dev.tuikio.app.ui.Theme
import dev.tuikio.app.ui.WRAP
import dev.tuikio.app.ui.dp
import dev.tuikio.app.ui.hstack
import dev.tuikio.app.ui.iconButton
import dev.tuikio.app.ui.lp
import dev.tuikio.app.ui.vstack
import dev.tuikio.catalog.GameManifest
import dev.tuikio.catalog.Orientation
import dev.tuikio.controls.ControlDispatcher
import dev.tuikio.controls.ControlProfile
import dev.tuikio.controls.Scheduler
import dev.tuikio.runtime.GameSession
import dev.tuikio.runtime.LaunchSpec
import dev.tuikio.runtime.PtyFrames
import dev.tuikio.terminal.KeyMods
import dev.tuikio.terminal.TermKey

object GameLauncher {
    const val EXTRA_GAME = "game"

    fun launch(context: Context, gameId: String) {
        context.startActivity(Intent(context, GameActivity::class.java).putExtra(EXTRA_GAME, gameId))
    }
}

/**
 * Full-screen play: the game's real terminal plus its control profile. The game process is
 * frozen (SIGSTOP) while the activity is in the background and resumed (SIGCONT) on return,
 * so a real-time game neither burns battery nor runs on unattended. Leaving always ends it.
 */
class GameActivity : Activity() {
    private lateinit var game: GameManifest
    private lateinit var profile: ControlProfile
    private var session: GameSession? = null
    private lateinit var terminal: TerminalView
    private lateinit var controls: ControlsView
    private lateinit var dispatcher: ControlDispatcher
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var exited = false
    private var cols = 80
    private var rows = 24

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = TuikioApp.instance
        val id = intent.getStringExtra(GameLauncher.EXTRA_GAME)
        val g = id?.let { app.catalog.game(it) }
        if (g == null || !app.library.isInstalled(g.id)) {
            finish()
            return
        }
        game = g
        profile = app.catalog.profile(g.controls)
        requestedOrientation = when (g.runtime.orientation) {
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            Orientation.ANY -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        cols = g.runtime.preferredTerminal.cols
        rows = g.runtime.preferredTerminal.rows

        root = FrameLayout(this).apply { setBackgroundColor(Theme.palette.screen) }
        terminal = TerminalView(this).apply {
            typeface = Fonts.get(this@GameActivity, FontFace.MONO, 500)
            palette = Theme.palette
            contentDescription = "${game.title} screen"
        }
        controls = ControlsView(this, profile).apply {
            scale = app.prefs.controlScale
            leftHanded = app.prefs.leftHanded
            hapticsEnabled = app.prefs.haptics
        }
        setContentView(root)
        // Only after setContentView: before it the window has no decor view, and
        // Window.getInsetsController() throws on Android 11+ (crash seen on Android 17).
        immersive()
        layoutForOrientation(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

        startSession()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { showPauseSheet() }
        }
    }

    private fun startSession() {
        val app = TuikioApp.instance
        val spec = try {
            LaunchSpec.forManifest(game, java.io.File(applicationInfo.nativeLibraryDir), app.library.homeOf(game.id))
        } catch (e: Exception) {
            showEnded("Couldn't start ${game.title}: ${e.message}")
            return
        }
        val s = GameSession(spec, cols, rows)
        session = s
        terminal.emulator = s.terminal
        dispatcher = ControlDispatcher(profile, s.keys, HandlerScheduler(handler)) { bytes -> s.send(bytes) }
        dispatcher.confirm = { action -> confirmAction(action.label); false }
        controls.onPress = { dispatcher.press(it) }
        controls.onRelease = { dispatcher.release(it) }
        controls.onKeyboard = { toggleKeyboard() }
        terminal.onSwipe = { g -> profile.gestures[g]?.let { tapAction(it) } }
        terminal.onTap = { col, row ->
            s.mouse.encode(dev.tuikio.terminal.MouseEncoder.Action.PRESS, 0, col, row)?.let { press ->
                s.send(press)
                s.mouse.encode(dev.tuikio.terminal.MouseEncoder.Action.RELEASE, 0, col, row)?.let { s.send(it) }
            } ?: terminal.requestFocus()
        }
        terminal.onText = { text -> s.send(s.keys.text(text)) }
        terminal.onKey = { e -> handleKey(e) }
        s.listener = object : GameSession.Listener {
            override fun onOutput() = terminal.onOutput()
            override fun onExit(exitCode: Int) {
                handler.post { if (!isFinishing) showEnded(if (exitCode == 0) "${game.title} ended." else "${game.title} stopped (code $exitCode).") }
            }
        }
        try {
            s.start()
            startedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            showEnded("Couldn't start ${game.title}: ${e.message}")
        }
    }

    /** A tap on an action outside of the press/release cycle (gestures, menus). */
    private fun tapAction(id: String) {
        dispatcher.press(id)
        dispatcher.release(id)
    }

    private fun confirmAction(label: String) {
        lateinit var sheet: Sheet
        val actionId = profile.actions.values.firstOrNull { it.label == label }?.id ?: return
        val content = vstack(12) {
            addView(T.title(context, "$label?", 21f))
            addView(T.soft(context, "Sends the game's own \"$label\" command."))
            addView(TButton(context, label).apply {
                setOnClickListener {
                    sheet.dismiss()
                    profile.actions[actionId]?.let { dispatcher.emit(it.output) }
                }
            }, lp(MATCH, WRAP))
            addView(TButton(context, "Keep playing", ButtonStyle.GHOST).apply { setOnClickListener { sheet.dismiss() } }, lp(MATCH, WRAP))
        }
        sheet = Sheet(root, content).show()
    }

    private fun layoutForOrientation(landscape: Boolean) {
        root.removeAllViews()
        controls.landscape = landscape
        val top = hstack(10) {
            setPadding(dp(12), dp(10), dp(12), dp(6))
            addView(iconButton(Icon.PAUSE, "Pause", fill = Theme.palette.surfaceAlt) { showPauseSheet() })
            addView(T.body(context, game.title, 16f, Theme.palette.screenInk, 800).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
            if (profile.keyboard) addView(iconButton(Icon.KEYBOARD, "Show keyboard", fill = Theme.palette.surfaceAlt) { toggleKeyboard() })
        }
        if (landscape) {
            val side = controls.sideInsetPx()
            root.addView(terminal, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(side, dp(8), side, dp(8)) })
            root.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(top, FrameLayout.LayoutParams(side, WRAP, Gravity.TOP or Gravity.START))
        } else {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
            column.addView(terminal, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            column.addView(controls, LinearLayout.LayoutParams(MATCH, controls.portraitHeightPx()))
            root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        terminal.post { fitTerminal() }
    }

    private fun fitTerminal() {
        val d = resources.displayMetrics.density
        val maxCell = 26f * d * TuikioApp.instance.prefs.terminalTextScale
        terminal.fitGrid(cols, rows, maxCell)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controls.releaseAll()
        if (::dispatcher.isInitialized) dispatcher.releaseAll()
        layoutForOrientation(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onStart() {
        super.onStart()
        session?.signal(PtyFrames.SIG_CONT)
        terminal.onOutput()
    }

    override fun onStop() {
        // Freeze the game: no CPU, no timers, no surprise game-over while you were away.
        if (::dispatcher.isInitialized) dispatcher.releaseAll()
        controls.releaseAll()
        if (!isFinishing) session?.signal(PtyFrames.SIG_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val s = session
        if (s != null) {
            s.signal(PtyFrames.SIG_CONT)
            s.stop(1200)
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            if (startedAt > 0) TuikioApp.instance.library.recordSession(game.id, seconds)
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = showPauseSheet()

    private fun showPauseSheet() {
        if (exited) {
            finish()
            return
        }
        lateinit var sheet: Sheet
        val content = vstack(12) {
            addView(hstack(12) {
                addView(android.widget.ImageView(context).apply { setImageResource(dev.tuikio.app.R.drawable.kio); contentDescription = null },
                    LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(T.title(context, "Paused", 22f), lp(0, WRAP, 1f))
            })
            addView(TButton(context, "Resume", icon = Icon.PLAY).apply { setOnClickListener { sheet.dismiss() } }, lp(MATCH, WRAP))
            val quit = profile.actions["quit"]
            addView(TButton(context, if (quit != null) "Save and quit" else "Quit", ButtonStyle.SECONDARY).apply {
                setOnClickListener {
                    sheet.dismiss()
                    // Ask the game to quit its own way first, so it can write scores and saves.
                    quit?.let { dispatcher.emit(it.output) }
                    handler.postDelayed({ finish() }, if (quit != null) 600 else 0)
                }
            }, lp(MATCH, WRAP))
            addView(hstack(8) {
                addView(TButton(context, "Keyboard", ButtonStyle.SECONDARY, Icon.KEYBOARD).apply {
                    setOnClickListener { sheet.dismiss(); toggleKeyboard() }
                }, lp(0, WRAP, 1f))
                addView(TButton(context, "How to play", ButtonStyle.SECONDARY, Icon.INFO).apply {
                    setOnClickListener { sheet.dismiss(); showHelp() }
                }, lp(0, WRAP, 1f))
            })
        }
        sheet = Sheet(root, content).show()
    }

    private fun showHelp() {
        lateinit var sheet: Sheet
        val content = vstack(10) {
            addView(T.title(context, profile.title, 21f))
            addView(T.body(context, profile.help, 15f))
            profile.actions.values.forEach { a -> addView(T.soft(context, "• ${a.label}")) }
            addView(TButton(context, "Got it").apply { setOnClickListener { sheet.dismiss() } }, lp(MATCH, WRAP))
        }
        sheet = Sheet(root, content).show()
    }

    private fun showEnded(message: String) {
        exited = true
        controls.releaseAll()
        lateinit var sheet: Sheet
        val content = vstack(12) {
            addView(T.title(context, message, 20f))
            addView(TButton(context, "Play again", icon = Icon.REFRESH).apply {
                setOnClickListener {
                    sheet.dismiss()
                    session?.stop(300)
                    exited = false
                    startSession()
                }
            }, lp(MATCH, WRAP))
            addView(TButton(context, "Back to library", ButtonStyle.SECONDARY).apply { setOnClickListener { finish() } }, lp(MATCH, WRAP))
        }
        sheet = Sheet(root, content).show()
    }

    private fun toggleKeyboard() {
        terminal.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        @Suppress("DEPRECATION")
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    // ------------------------------------------------------------------ hardware input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val gamepad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD && event.device?.isExternal == true
        if (gamepad && handleGamepad(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleGamepad(e: KeyEvent): Boolean {
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }
        val action = when {
            dir != null -> controls.padAction(dir)
            e.keyCode == KeyEvent.KEYCODE_BUTTON_A -> controls.buttonActions().getOrNull(0)
            e.keyCode == KeyEvent.KEYCODE_BUTTON_B -> controls.buttonActions().getOrNull(1)
            e.keyCode == KeyEvent.KEYCODE_BUTTON_X -> controls.buttonActions().getOrNull(2)
            e.keyCode == KeyEvent.KEYCODE_BUTTON_START -> { if (e.action == KeyEvent.ACTION_DOWN) showPauseSheet(); return true }
            e.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT -> { if (e.action == KeyEvent.ACTION_DOWN) toggleKeyboard(); return true }
            else -> null
        } ?: return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) dispatcher.press(action)
        if (e.action == KeyEvent.ACTION_UP) dispatcher.release(action)
        return true
    }

    /** Pad direction currently held by a hat switch or the left stick. */
    private var stickDirection: String? = null

    /** Many controllers report their D-pad as a hat axis, and the left stick moves too. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event)
        }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (Math.abs(hatX) > 0.5f) hatX else event.getAxisValue(MotionEvent.AXIS_X)
        val y = if (Math.abs(hatY) > 0.5f) hatY else event.getAxisValue(MotionEvent.AXIS_Y)
        val dir = when {
            Math.abs(x) < 0.5f && Math.abs(y) < 0.5f -> null
            Math.abs(x) > Math.abs(y) -> if (x > 0) "right" else "left"
            else -> if (y > 0) "down" else "up"
        }
        if (dir != stickDirection) {
            stickDirection?.let { controls.padAction(it) }?.let { dispatcher.release(it) }
            stickDirection = dir
            dir?.let { controls.padAction(it) }?.let { dispatcher.press(it) }
        }
        return true
    }

    private fun handleKey(e: KeyEvent): Boolean {
        val s = session ?: return false
        var mods = KeyMods.NONE
        if (e.isShiftPressed) mods = mods or KeyMods.SHIFT
        if (e.isAltPressed) mods = mods or KeyMods.ALT
        if (e.isCtrlPressed) mods = mods or KeyMods.CTRL
        val key = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> TermKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.RIGHT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> TermKey.ENTER
            KeyEvent.KEYCODE_DEL -> TermKey.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.DELETE
            KeyEvent.KEYCODE_ESCAPE -> TermKey.ESCAPE
            KeyEvent.KEYCODE_TAB -> TermKey.TAB
            KeyEvent.KEYCODE_MOVE_HOME -> TermKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> TermKey.END
            KeyEvent.KEYCODE_PAGE_UP -> TermKey.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> TermKey.INSERT
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> TermKey.values()[TermKey.F1.ordinal + (e.keyCode - KeyEvent.KEYCODE_F1)]
            else -> null
        }
        if (key != null) {
            s.send(s.keys.encode(key, mods))
            return true
        }
        val ch = e.getUnicodeChar(e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (ch > 0) {
            s.send(s.keys.text(String(Character.toChars(ch)), mods and KeyMods.SHIFT.inv()))
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        val decor = window.decorView // installs the decor view if it doesn't exist yet
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("unused")
    private val unusedLp = ViewGroup.LayoutParams(0, 0)
}

/** Repeat timing on the UI thread's Handler. */
class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Scheduler.Cancellable {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return Scheduler.Cancellable { handler.removeCallbacks(r) }
    }
}
