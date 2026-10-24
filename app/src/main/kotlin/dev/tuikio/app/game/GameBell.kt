package dev.tuikio.app.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The terminal bell (BEL, ^G): what terminal games ring on a wrong key, a hit or an alarm.
 * A short, soft blip made here (no sound file), played only while the phone isn't silenced,
 * and a light tick when the player keeps haptics on. Rings closer together than [MIN_GAP_MS]
 * are dropped: some games ring once per frame while a key is held.
 */
class GameBell(private val context: Context) {
    var sound = true
    var haptics = true

    private var track: AudioTrack? = null
    private var last = 0L

    fun ring(view: View) {
        val now = SystemClock.uptimeMillis()
        if (now - last < MIN_GAP_MS) return
        last = now
        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!sound || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val t = track ?: create()?.also { track = it } ?: return
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: IllegalStateException) {
            release()
        }
    }

    fun release() {
        track?.release()
        track = null
    }

    private fun create(): AudioTrack? = try {
        val pcm = blip()
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MIN_GAP_MS = 120L
        private const val RATE = 22050

        /** 70 ms gliding from 1046 Hz to 784 Hz (C6 to G5), with a soft attack and decay. */
        internal fun blip(): ShortArray {
            val n = RATE * 70 / 1000
            var phase = 0.0
            return ShortArray(n) { i ->
                val t = i.toDouble() / n
                val freq = 1046.5 + (784.0 - 1046.5) * t
                phase += 2 * Math.PI * freq / RATE
                val env = Math.min(1.0, t * 20) * Math.pow(1 - t, 2.0)
                (Math.sin(phase) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
            }
        }
    }
}
