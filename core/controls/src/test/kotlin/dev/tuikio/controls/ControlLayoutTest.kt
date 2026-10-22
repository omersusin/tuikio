package dev.tuikio.controls

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every bundled profile must lay out cleanly on real phone sizes, in both orientations. */
class ControlLayoutTest {
    private val profilesDir: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "catalog/profiles") }.first { it.isDirectory }

    private val profiles = profilesDir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        .map { it.name to ControlProfile.parse(it.readText()) }

    private fun labelled(p: ControlProfile): (ControlElement) -> Boolean = { e ->
        (e.type == ElementType.DPAD4 || e.type == ElementType.DPAD8) &&
            e.bindings["up"]?.let { p.actions[it]?.label } !in setOf(null, "Up", "North")
    }

    private fun assertClean(name: String, placed: List<PlacedControl>, w: Float, h: Float) {
        for (p in placed) {
            assertTrue(p.cx - p.radius >= 0 && p.cx + p.radius <= w && p.cy - p.radius >= 0 && p.cy + p.radius <= h, "$name: ${p.element.type} out of area at (${p.cx}, ${p.cy})")
        }
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val a = placed[i]
            val b = placed[j]
            val dist = Math.hypot((a.cx - b.cx).toDouble(), (a.cy - b.cy).toDouble())
            assertTrue(dist >= a.radius + b.radius + ControlLayout.GAP - 0.01, "$name: ${a.element.bindings} overlaps ${b.element.bindings}")
        }
    }

    @Test
    fun portraitLayoutsNeverOverlap() {
        for ((name, p) in profiles) for ((width, screenH) in listOf(320f to 640f, 360f to 740f, 393f to 852f, 412f to 915f, 480f to 960f)) for (wanted in listOf(0.85f, 1f, 1.25f)) {
            val els = p.layout(false)
            val scale = ControlLayout.fittedPortraitScale(els, width, screenH, wanted, labelled(p))
            val h = ControlLayout.requiredHeight(els, width, scale, labelled(p))
            assertTrue(h <= screenH * 0.42f, "$name: controls need ${h}dp of $screenH at width $width scale $scale")
            for (left in listOf(false, true)) {
                val placed = assertNotNull(ControlLayout.place(els, width, h, scale, left, labelled(p)), "$name @ $width")
                assertClean("$name portrait $width×$h scale $scale left=$left", placed, width, h)
            }
        }
    }

    @Test
    fun landscapeLayoutsNeverOverlap() {
        for ((name, p) in profiles) for ((w, h) in listOf(640f to 320f, 780f to 360f, 852f to 393f, 915f to 412f)) for (wanted in listOf(0.85f, 1f, 1.25f)) {
            val els = p.layout(true)
            val scale = ControlLayout.fittedLandscapeScale(els, w, h, 72f, wanted, labelled(p))
            val side = ControlLayout.requiredSide(els, w, h, 72f, scale, labelled(p))
            assertTrue(side <= w * 0.3f, "$name: side columns of ${side}dp leave too little room for the game at $w×$h scale $scale")
            val placed = assertNotNull(ControlLayout.place(els, w, h, scale, false, labelled(p), ControlLayout.sideRegions(w, h, side, 72f)), "$name @ $w×$h")
            assertClean("$name landscape $w×$h scale $scale", placed, w, h)
        }
    }

    @Test
    fun theVitetrisOverlapIsGone() {
        val p = profiles.first { it.first.startsWith("blocks") }.second
        val els = p.layout(false)
        val h = ControlLayout.requiredHeight(els, 393f, 1f, labelled(p))
        val placed = ControlLayout.place(els, 393f, h, 1f, false, labelled(p))!!
        assertClean("blocks", placed, 393f, h)
        // Drop keeps its bottom-right corner; the pad keeps the bottom-left one.
        val drop = placed.first { it.element.bindings["press"] == "drop" }
        assertTrue(drop.cx > 393f / 2 && drop.cy > h / 2)
    }
}
