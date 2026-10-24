package dev.tuikio.app

import dev.tuikio.app.game.GameBell
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bell's blip: short, soft and without clicks at either end. */
class GameBellTest {
    @Test
    fun blipIsShortSoftAndClickFree() {
        val pcm = GameBell.blip()
        assertTrue("70 ms at 22.05 kHz", pcm.size in 1500..1600)
        val peak = pcm.maxOf { Math.abs(it.toInt()) }
        assertTrue("well under full scale: $peak", peak in 3000..(Short.MAX_VALUE * 0.4).toInt())
        assertTrue("starts silent", Math.abs(pcm.first().toInt()) < 200)
        assertTrue("ends silent", pcm.takeLast(20).all { Math.abs(it.toInt()) < 200 })
    }
}
