package dev.tuikio.app.ui

import android.animation.TimeInterpolator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * Tuikio's motion system: a few durations, one easing per brand, and a single switch that
 * turns decoration off. Motion is reduced when the user asks for it, when system animations
 * are disabled, in Battery Saver, and when the device reports thermal pressure. Gameplay and
 * input never wait on decorative animation.
 */
object Motion {
    const val FAST = 120L
    const val BASE = 220L
    const val SLOW = 340L

    private var userReduced = false
    private var powerSave = false
    private var thermalHot = false
    private var animatorOff = false

    fun init(context: Context) {
        val app = context.applicationContext
        userReduced = app.getSharedPreferences("motion", Context.MODE_PRIVATE).getBoolean("reduced", false)
        animatorOff = Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerSave = pm.isPowerSaveMode
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                powerSave = pm.isPowerSaveMode
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        if (Build.VERSION.SDK_INT >= 29) {
            thermalHot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
            pm.addThermalStatusListener { status -> thermalHot = status >= PowerManager.THERMAL_STATUS_MODERATE }
        }
    }

    fun setUserReduced(context: Context, reduced: Boolean) {
        userReduced = reduced
        context.getSharedPreferences("motion", Context.MODE_PRIVATE).edit().putBoolean("reduced", reduced).apply()
    }

    val userPrefersReduced: Boolean get() = userReduced

    /** True when decorative motion should be skipped entirely. */
    val reduced: Boolean get() = userReduced || animatorOff || powerSave || thermalHot

    /** Why motion is reduced right now, for the Settings screen. */
    fun reason(): String? = when {
        userReduced -> null
        animatorOff -> "System animations are off"
        powerSave -> "Battery Saver is on"
        thermalHot -> "Your phone is warm"
        else -> null
    }

    fun duration(base: Long): Long {
        if (reduced) return 0
        return when (Theme.activePack.motion) {
            MotionStyle.INSTANT -> 0
            MotionStyle.CLICKY -> (base * 0.6).toLong()
            MotionStyle.FLOATY, MotionStyle.DRIFT -> (base * 1.3).toLong()
            else -> base
        }
    }

    fun easing(): TimeInterpolator = when (Theme.activePack.motion) {
        MotionStyle.SPRINGY -> SPRING
        MotionStyle.FLOATY -> DecelerateInterpolator(2.2f)
        MotionStyle.CLICKY -> PathInterpolator(0.2f, 0f, 0f, 1f)
        MotionStyle.DRIFT -> PathInterpolator(0.45f, 0f, 0.2f, 1f)
        MotionStyle.STEPPED -> TimeInterpolator { t -> Math.floor(t * 4.0).toFloat() / 4f }
        MotionStyle.INSTANT -> LinearInterpolator()
    }

    /** Critically-ish damped spring with a small overshoot (ζ≈0.62), sampled analytically. */
    val SPRING = TimeInterpolator { t ->
        val zeta = 0.62
        val omega = 9.5
        val wd = omega * Math.sqrt(1 - zeta * zeta)
        val x = 1 - Math.exp(-zeta * omega * t) * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t))
        x.toFloat()
    }

    /** Sticker press: the element sinks onto its shadow. Instant when motion is reduced. */
    fun press(view: View, pressed: Boolean, depthPx: Float) {
        val target = if (pressed) depthPx else 0f
        if (reduced || Theme.activePack.motion == MotionStyle.INSTANT) {
            view.translationY = target
            return
        }
        view.animate().cancel()
        view.animate().translationY(target).setDuration(if (pressed) 60 else duration(FAST + 60)).setInterpolator(if (pressed) LinearInterpolator() else easing()).start()
    }

    /** Fade-and-rise for content appearing; skipped when reduced. Index staggers the first few items. */
    fun enter(view: View, index: Int = 0) {
        if (reduced) return
        val d = duration(BASE)
        if (d == 0L) return
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 12
        view.animate().alpha(1f).translationY(0f).setStartDelay(minOf(index, 6) * 35L).setDuration(d).setInterpolator(easing()).start()
    }
}
