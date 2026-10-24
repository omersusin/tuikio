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
import dev.tuikio.app.ui.IconButton
import dev.tuikio.app.ui.iconButton
import dev.tuikio.app.ui.lp
import dev.tuikio.app.ui.vstack
import dev.tuikio.catalog.GameManifest
import dev.tuikio.catalog.Orientation
import dev.tuikio.controls.ActionOutput
import dev.tuikio.controls.ControlDispatcher
import dev.tuikio.controls.PromptChip
import dev.tuikio.controls.KeyHints
import dev.tuikio.controls.PromptChips
import dev.tuikio.controls.ControlProfile
import dev.tuikio.controls.Scheduler
import dev.tuikio.runtime.GameSession
import dev.tuikio.runtime.LaunchSpec
import dev.tuikio.runtime.PtyFrames
import dev.tuikio.terminal.KeyMods
import dev.tuikio.terminal.TermKey
import dev.tuikio.terminal.TerminalEmulator

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
/** Height of the strip kept free under the game for answer chips. */
private const val CHIP_STRIP_DP = 54

/** Height of the Esc/Tab/Ctrl/F-keys row shown above the phone keyboard. */
private const val EXTRA_KEYS_DP = 48

class GameActivity : Activity() {
    private lateinit var game: GameManifest
    private lateinit var profile: ControlProfile
    private var session: GameSession? = null
    private lateinit var terminal: TerminalView
    private lateinit var controls: ControlsView
    private lateinit var dispatcher: ControlDispatcher
    private lateinit var root: FrameLayout
    /**
     * Named uiHandler, not handler: inside `someView.apply { … }` a bare `handler` resolves to
     * View.getHandler(), which is null once the view is detached (crash seen on device).
     */
    private val uiHandler = Handler(Looper.getMainLooper())
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
        profile = app.catalog.profile(g.controls).forGame(g.controlLabels, g.controlHide)
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
        root.viewTreeObserver.addOnGlobalLayoutListener { watchIme() }
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
        s.terminal.wordWrap = game.runtime.wrapWords
        session = s
        terminal.emulator = s.terminal
        dispatcher = ControlDispatcher(profile, s.keys, HandlerScheduler(uiHandler)) { bytes -> s.send(bytes) }
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
        terminal.onText = { text -> s.send(s.keys.text(text, extraKeys.takeMods())) }
        terminal.onKey = { e -> handleKey(e) }
        dispatcher.stateOf = { controls.stateOf(it) }
        // The terminal bell: a short sound (and tick), as the player set them in You.
        bell.sound = TuikioApp.instance.prefs.gameSounds
        bell.haptics = TuikioApp.instance.prefs.haptics
        s.terminal.listener = object : TerminalEmulator.Listener {
            override fun onBell() {
                uiHandler.post { if (!isFinishing) bell.ring(terminal) }
            }
        }
        s.listener = object : GameSession.Listener {
            override fun onOutput() {
                terminal.onOutput()
                scheduleWatch()
            }
            override fun onInputMode(lineInput: Boolean) = scheduleWatch() // answers gain or lose their Enter
            override fun onExit(exitCode: Int) {
                uiHandler.post { if (!isFinishing) showEnded(if (exitCode == 0) "${game.title} ended." else "${game.title} stopped (code $exitCode).") }
            }
        }
        try {
            s.start()
            startedAt = SystemClock.elapsedRealtime()
            // Games played by typing start with the keyboard up, once the window can take it.
            if (game.runtime.typing && !typing) uiHandler.postDelayed({ if (!isFinishing && !typing) setTyping(true) }, 400)
        } catch (e: Exception) {
            showEnded("Couldn't start ${game.title}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ adaptive controls
    //
    // The controls follow what the game shows: buttons with states change their look (Pause
    // becomes Resume once the game says it's paused), and when the game asks a question at its
    // cursor, the answers appear as chips ("Yes"/"No", the letters of "[TSFKCW]", "Continue").

    private val bell by lazy { GameBell(this) }

    /** The game's own keys (manifest "keys"), for the strip under the game. */
    private val gameKeys by lazy {
        game.keys.map { k -> PromptChip(k.label, k.key?.let { ActionOutput.Key(TermKey.valueOf(it)) } ?: ActionOutput.Text(k.text!!)) }
    }

    private val watchPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var shownChips: List<PromptChip> = emptyList()

    /** The chips' rows: one in landscape, up to two upright (where height is to spare). */
    private val chipRows by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL } }
    private var chipViews: List<View> = emptyList()
    /** How many chips the first row holds; the rest are on the second. */
    private var chipSplit = -1
    /** What the rows were last fitted to: the width, the orientation and the chips. */
    private var chipFit: Any? = null
    private val chipBar by lazy {
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // Chips that don't fit fade out at the edge, so it's clear there are more.
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
            visibility = View.GONE
            addView(chipRows, ViewGroup.LayoutParams(WRAP, WRAP))
        }
    }

    /** Looks at the screen at most every 120 ms, on the UI thread. Called from the reader threads. */
    private fun scheduleWatch() {
        if (!watchPending.compareAndSet(false, true)) return
        uiHandler.postDelayed({ watchPending.set(false); watchScreen() }, 120)
    }

    private fun watchScreen() {
        val s = session ?: return
        val emu = s.terminal
        val lines: List<String>
        val row: Int
        val col: Int
        val lineMode: Boolean
        synchronized(emu.lock) {
            lines = emu.screen.text()
            row = emu.cursorRow
            col = emu.cursorCol
            // The helper reports the terminal's mode; before it has, full-screen (curses) games
            // are on the alternate screen and read single keys, plain programs read lines.
            lineMode = s.lineInput ?: !emu.isAltScreen
        }
        controls.updateStates(lines.joinToString("\n"))
        val answers = PromptChips.detect(lines, row, col, lineMode)
        // While the game asks a question, only its answers (and keys the screen names) are offered;
        // otherwise the game's own keys stay in the strip.
        val own = if (answers.isEmpty()) gameKeys.filter { KeyHints.keyId(it.output) !in controls.visibleKeyIds() } else emptyList()
        // Keys the screen names that no button sends: one tap each, after the answers.
        val covered = controls.visibleKeyIds() + (answers + own).map { KeyHints.keyId(it.output) }
        val chips = answers + own + KeyHints.detect(lines, covered, controls.hasPad(), lineMode)
        if (chips != shownChips) showChips(chips, answers.size)
        if (chips.isNotEmpty()) placeChips()
    }

    private fun showChips(chips: List<PromptChip>, answers: Int) {
        shownChips = chips
        chipViews = chips.mapIndexed { i, chip ->
            val answer = i < answers
            val style = if (answer && (answers <= 2 || chip.label == "Continue")) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY
            TButton(this, chip.label, style).apply {
                minWidth = dp(if (chip.label.length == 1) 44 else 72)
                minHeight = dp(44)
                setPadding(dp(if (chip.label.length == 1) 8 else 12), 0, dp(if (chip.label.length == 1) 8 else 12), 0)
                contentDescription = if (answer) "Answer ${chip.label}" else "Key ${chip.label}"
                setOnClickListener { dispatcher.emit(chip.output) }
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(3), 0, dp(3), 0) }
            }
        }
        chipSplit = -1
        layoutChipRows(chipViews.size)
        chipBar.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        if (chips.isEmpty()) terminal.reservedBottomPx = 0
        if (chips.isNotEmpty()) chipBar.announceForAccessibility("Answers: " + chips.joinToString { it.label })
    }

    /**
     * At the bottom of the game area, in a strip the terminal keeps free while chips show: the
     * game moves up (and only shrinks if it must), so chips never cover what it draws.
     */
    private fun layoutChipRows(split: Int) {
        if (split == chipSplit) return
        chipSplit = split
        for (v in chipViews) (v.parent as? ViewGroup)?.removeView(v)
        chipRows.removeAllViews()
        listOf(chipViews.take(split), chipViews.drop(split)).filter { it.isNotEmpty() }.forEach { row ->
            chipRows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                row.forEach { addView(it) }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (chipRows.childCount > 0) topMargin = dp(6) })
        }
    }

    private fun placeChips() {
        val lp = chipBar.layoutParams as? FrameLayout.LayoutParams ?: return
        val avail = (if (terminal.width > 0) terminal.width else root.width) - dp(8)
        // Upright there is height to spare: chips that don't fit one row get a second (digits
        // 0-9 and a few keys); in landscape the game keeps the height and the row scrolls.
        val fit = Triple(avail, controls.landscape, chipViews)
        if (fit != chipFit) {
            chipFit = fit
            var split = chipViews.size
            if (!controls.landscape && avail > 0) {
                var used = 0
                for ((i, v) in chipViews.withIndex()) {
                    v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    used += v.measuredWidth + dp(6)
                    if (used > avail) { split = i; break }
                }
            }
            layoutChipRows(split)
        }
        val loc = IntArray(2).also { terminal.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val termBottom = loc[1] - rootLoc[1] + terminal.height
        val margin = root.height - termBottom + dp(4)
        terminal.reservedBottomPx = dp(CHIP_STRIP_DP) * chipRows.childCount.coerceAtLeast(1) - (if (chipRows.childCount > 1) dp(4) else 0)
        // As wide as the chips, but never wider than the game area (it scrolls instead).
        chipRows.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = if (avail > 0) minOf(chipRows.measuredWidth, avail) else WRAP
        if (lp.gravity != (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) || lp.bottomMargin != margin || lp.topMargin != 0 || lp.width != width) {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = margin
            lp.topMargin = 0
            lp.width = width
            chipBar.layoutParams = lp
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
                    profile.actions[actionId]?.let { dispatcher.emit(controls.stateOf(it)?.output ?: it.output) }
                }
            }, lp(MATCH, WRAP))
            addView(TButton(context, "Keep playing", ButtonStyle.GHOST).apply { setOnClickListener { sheet.dismiss() } }, lp(MATCH, WRAP))
        }
        sheet = Sheet(root, content).show()
    }

    private fun layoutForOrientation(landscape: Boolean) {
        // terminal and controls survive rotation; in portrait they sit inside a column, so
        // clearing root alone would leave them attached to the old column.
        for (v in listOf<View>(terminal, controls)) (v.parent as? ViewGroup)?.removeView(v)
        root.removeAllViews()
        controls.landscape = landscape
        // The pause sheet and its help go with the old views; the game stays paused and the
        // sheet comes back below.
        val paused = pauseHolds.isNotEmpty()
        if (paused) { pauseHolds.clear(); pauseHolds += "sheet" }
        pauseSheet = null
        pauseButtons.clear()
        fun pauseButton() = iconButton(if (paused) Icon.PLAY else Icon.PAUSE, if (paused) "Resume" else "Pause", fill = Theme.palette.surfaceAlt) {
            pauseSheet?.dismiss() ?: showPauseSheet()
        }.also { pauseButtons += it }
        fun keyboardButton() = iconButton(Icon.KEYBOARD, "Show keyboard", fill = Theme.palette.surfaceAlt) { toggleKeyboard() }
        val top: View = if (landscape) {
            // The side column is narrow: buttons on one row, the title under them (two lines at most).
            vstack(4) {
                setPadding(dp(12), dp(10), dp(12), dp(6))
                addView(hstack(10) {
                    gravity = Gravity.CENTER
                    addView(pauseButton())
                    if (profile.keyboard) addView(keyboardButton())
                }, lp(MATCH, WRAP))
                addView(T.body(context, game.title, 14f, Theme.palette.screenInk, 800).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, lp(MATCH, WRAP))
            }
        } else {
            hstack(10) {
                setPadding(dp(12), dp(10), dp(12), dp(6))
                addView(pauseButton())
                addView(T.body(context, game.title, 16f, Theme.palette.screenInk, 800).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, lp(0, WRAP, 1f))
                if (profile.keyboard) addView(keyboardButton())
            }
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
        (chipBar.parent as? ViewGroup)?.removeView(chipBar)
        root.addView(chipBar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        terminal.post {
            fitTerminal()
            if (shownChips.isNotEmpty()) placeChips()
        }
        applyTypingLayout()
        if (paused) showPauseSheet()
    }

    private fun fitTerminal() {
        val d = resources.displayMetrics.density
        // TerminalView fits what the game draws into the space, up to this cell height.
        terminal.maxCellPx = 30f * d * TuikioApp.instance.prefs.terminalTextScale
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controls.releaseAll()
        if (::dispatcher.isInitialized) dispatcher.releaseAll()
        layoutForOrientation(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onStart() {
        super.onStart()
        if (pauseHolds.isEmpty()) session?.resume()
        terminal.onOutput()
    }

    override fun onStop() {
        // Freeze the game: no CPU, no timers, no surprise game-over while you were away.
        if (::dispatcher.isInitialized) dispatcher.releaseAll()
        controls.releaseAll()
        if (!isFinishing) session?.suspend()
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        bell.release()
        val s = session
        if (s != null) {
            s.resume() // a frozen game can't react to the hang-up that stop() sends
            s.stop(1200)
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            if (startedAt > 0) TuikioApp.instance.library.recordSession(game.id, seconds)
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = showPauseSheet()

    // ------------------------------------------------------------------ pausing

    /** What keeps the game paused (the pause sheet, the help opened from it); it runs when empty. */
    private val pauseHolds = HashSet<String>()
    private val pauseButtons = ArrayList<IconButton>()
    private var pauseSheet: Sheet? = null
    private val thaw = Runnable { terminal.frozen = false }

    /** Really pauses: the game is stopped, its last frame stays up and Pause turns into Resume. */
    private fun holdPause(by: String) {
        if (!pauseHolds.add(by) || pauseHolds.size > 1) return
        uiHandler.removeCallbacks(thaw)
        terminal.frozen = true
        if (::dispatcher.isInitialized) dispatcher.releaseAll()
        controls.releaseAll()
        session?.suspend()
        pauseButtons.forEach { it.show(Icon.PLAY, "Resume") }
    }

    private fun releasePause(by: String) {
        if (!pauseHolds.remove(by) || pauseHolds.isNotEmpty()) return
        session?.resume()
        // A curses game redraws its whole screen as it continues; show it again once it has.
        uiHandler.postDelayed(thaw, 250)
        pauseButtons.forEach { it.show(Icon.PAUSE, "Pause") }
    }

    private fun showPauseSheet() {
        if (exited) {
            finish()
            return
        }
        if (pauseSheet != null) return
        holdPause("sheet")
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
                    uiHandler.postDelayed({ finish() }, if (quit != null) 600 else 0)
                }
            }, lp(MATCH, WRAP))
            addView(hstack(8) {
                addView(TButton(context, "Keyboard", ButtonStyle.SECONDARY, Icon.KEYBOARD).apply {
                    setOnClickListener { sheet.dismiss(); toggleKeyboard() }
                }, lp(0, WRAP, 1f))
                addView(TButton(context, "How to play", ButtonStyle.SECONDARY, Icon.INFO).apply {
                    setOnClickListener { showHelp(); sheet.dismiss() } // help keeps the game paused
                }, lp(0, WRAP, 1f))
            })
        }
        sheet = Sheet(root, content).show()
        sheet.onDismiss = { if (pauseSheet === sheet) pauseSheet = null; releasePause("sheet") }
        pauseSheet = sheet
    }

    private fun showHelp() {
        holdPause("help")
        lateinit var sheet: Sheet
        val content = vstack(10) {
            addView(T.title(context, profile.title, 21f))
            addView(T.body(context, profile.help, 15f))
            profile.actions.values.forEach { a -> addView(T.soft(context, "• ${a.label}")) }
            addView(TButton(context, "Got it").apply { setOnClickListener { sheet.dismiss() } }, lp(MATCH, WRAP))
        }
        sheet = Sheet(root, content).show()
        sheet.onDismiss = { releasePause("help") }
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

    // ------------------------------------------------------------------ typing

    /** Typing mode: the phone keyboard is up, with Esc/Tab/Ctrl/F-keys above it; the game's controls step aside. */
    private var typing = false
    private var imeSeen = false
    private val extraKeys by lazy {
        ExtraKeysBar(this, { key, mods -> session?.let { s -> s.send(s.keys.encode(key, mods)) } }, { setTyping(false) })
    }

    private fun toggleKeyboard() = setTyping(!typing)

    private fun setTyping(on: Boolean) {
        typing = on
        imeSeen = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) {
            terminal.requestFocus()
            @Suppress("DEPRECATION")
            imm.showSoftInput(terminal, InputMethodManager.SHOW_FORCED)
        } else {
            imm.hideSoftInputFromWindow(terminal.windowToken, 0)
            extraKeys.takeMods()
        }
        applyTypingLayout()
    }

    private fun applyTypingLayout() {
        (extraKeys.parent as? ViewGroup)?.removeView(extraKeys)
        controls.visibility = if (typing) View.GONE else View.VISIBLE
        val barH = dp(EXTRA_KEYS_DP)
        val column = terminal.parent as? LinearLayout
        if (column != null) {
            if (typing) column.addView(extraKeys, LinearLayout.LayoutParams(MATCH, barH))
        } else {
            (terminal.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = if (typing) barH + dp(4) else dp(8)
                terminal.layoutParams = lp
            }
            if (typing) root.addView(extraKeys, FrameLayout.LayoutParams(MATCH, barH, Gravity.BOTTOM))
        }
    }

    /** Leaves typing mode when the keyboard goes away (back key, the keyboard's own hide button). */
    private fun watchIme() {
        if (!typing) return
        val r = android.graphics.Rect().also { root.getWindowVisibleDisplayFrame(it) }
        val screenH = root.rootView.height
        val shown = screenH > 0 && screenH - r.bottom > screenH * 0.2f
        if (shown) imeSeen = true else if (imeSeen) setTyping(false)
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
        if (typing) mods = mods or extraKeys.takeMods()
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
