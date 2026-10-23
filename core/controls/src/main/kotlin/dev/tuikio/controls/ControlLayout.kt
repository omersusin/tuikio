package dev.tuikio.controls

/** A control after layout: center and radius in dp, in the coordinates of its controls area. */
data class PlacedControl(val element: ControlElement, val cx: Float, val cy: Float, val radius: Float)

/**
 * Positions a profile's controls so they never overlap or leave their area, whatever the
 * screen size, control scale or handedness. Each element starts at its anchor (plus offset);
 * if that spot collides with something already placed, or leaves the element's region, the
 * nearest free spot is searched outward from it. Pads are placed first, then primary buttons,
 * then the rest, so the important controls keep their intended positions.
 *
 * All values are dp. Pure Kotlin, so every bundled profile is checked on the host.
 */
object ControlLayout {
    /** Distance from region edges. */
    const val EDGE = 16f

    /** Minimum space between two controls. */
    const val GAP = 10f

    /** Space reserved above a pad that shows a label for its "up" action (e.g. "Rotate"). */
    const val LABEL = 18f

    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * Places [elements] in a [width]×[height] area. [regionFor] limits where each element may
     * go (the whole area by default; side columns in landscape). Returns null if some element
     * cannot be placed, so callers can grow the area and retry.
     */
    fun place(
        elements: List<ControlElement>,
        width: Float,
        height: Float,
        scale: Float = 1f,
        leftHanded: Boolean = false,
        labelled: (ControlElement) -> Boolean = { false },
        regionFor: (ControlElement) -> Region = { Region(0f, 0f, width, height) },
    ): List<PlacedControl>? {
        val order = elements.indices.sortedWith(
            compareBy<Int>(
                { if (elements[it].type == ElementType.DPAD4 || elements[it].type == ElementType.DPAD8) 0 else 1 },
                { if (elements[it].style == "primary") 0 else 1 },
                { -elements[it].sizeDp },
                { it },
            ),
        )
        val result = arrayOfNulls<PlacedControl>(elements.size)
        val done = ArrayList<PlacedControl>()
        for (i in order) {
            val e = elements[i]
            val region = regionFor(e)
            val r = e.sizeDp * scale / 2f
            val top = if (labelled(e)) LABEL else 0f
            val (nx, ny) = nominal(e, region, r, top, scale)
            val spot = findSpot(nx, ny, r, top, region, done) ?: return null
            val p = PlacedControl(e, spot.first, spot.second, r)
            done += p
            result[i] = p
        }
        val placed = result.map { it!! }
        return if (leftHanded) placed.map { it.copy(cx = width - it.cx) } else placed
    }

    /** Smallest portrait controls height (dp) that fits everything in [width] dp. */
    fun requiredHeight(elements: List<ControlElement>, width: Float, scale: Float = 1f, labelled: (ControlElement) -> Boolean = { false }): Float {
        if (elements.isEmpty()) return 0f
        var h = (elements.maxOf { it.sizeDp } * scale + 2 * EDGE).coerceAtLeast(64f)
        while (h < 1200f) {
            if (place(elements, width, h, scale, labelled = labelled) != null) return h
            h += 8f
        }
        return h
    }

    /**
     * Landscape: controls live in two side columns of [side] dp beside the terminal. Start-anchored
     * elements go left, everything else right; [topReserved] keeps the left column clear of the
     * top bar.
     */
    fun sideRegions(width: Float, height: Float, side: Float, topReserved: Float, reserveOnEnd: Boolean = false): (ControlElement) -> Region = { e ->
        when (e.anchor) {
            Anchor.TOP_START, Anchor.CENTER_START, Anchor.BOTTOM_START -> Region(0f, if (reserveOnEnd) 0f else topReserved, side, height)
            else -> Region(width - side, if (reserveOnEnd) topReserved else 0f, width, height)
        }
    }

    /** Smallest landscape side column (dp) that fits everything in [height] dp. */
    fun requiredSide(elements: List<ControlElement>, width: Float, height: Float, topReserved: Float, scale: Float = 1f, labelled: (ControlElement) -> Boolean = { false }, reserveOnEnd: Boolean = false, minSide: Float = 0f): Float {
        if (elements.isEmpty()) return minSide
        var side = maxOf(minSide, elements.maxOf { it.sizeDp * scale + 2 * EDGE })
        while (side < width / 2) {
            if (place(elements, width, height, scale, labelled = labelled, regionFor = sideRegions(width, height, side, topReserved, reserveOnEnd)) != null) return side
            side += 8f
        }
        return side
    }

    /**
     * The player's control scale, reduced (to no less than 70%) when landscape side columns
     * would otherwise take more than [maxFraction] of the width each, so small phones still
     * leave the game most of the screen.
     */
    fun fittedLandscapeScale(elements: List<ControlElement>, width: Float, height: Float, topReserved: Float, scale: Float, labelled: (ControlElement) -> Boolean = { false }, maxFraction: Float = 0.3f, reserveOnEnd: Boolean = false): Float {
        var s = scale
        while (s > 0.7f && requiredSide(elements, width, height, topReserved, s, labelled, reserveOnEnd) > width * maxFraction) s -= 0.05f
        return s
    }

    /** Same for portrait: controls may take at most [maxFraction] of the screen height. */
    fun fittedPortraitScale(elements: List<ControlElement>, width: Float, screenHeight: Float, scale: Float, labelled: (ControlElement) -> Boolean = { false }, maxFraction: Float = 0.42f): Float {
        var s = scale
        while (s > 0.7f && requiredHeight(elements, width, s, labelled) > screenHeight * maxFraction) s -= 0.05f
        return s
    }

    private fun nominal(e: ControlElement, g: Region, r: Float, top: Float, scale: Float): Pair<Float, Float> {
        val x = when (e.anchor) {
            Anchor.TOP_START, Anchor.CENTER_START, Anchor.BOTTOM_START -> g.left + EDGE + r
            Anchor.TOP_END, Anchor.CENTER_END, Anchor.BOTTOM_END -> g.right - EDGE - r
            Anchor.BOTTOM_CENTER -> (g.left + g.right) / 2f
        } + e.offsetDp.first * scale
        val y = when (e.anchor) {
            Anchor.TOP_START, Anchor.TOP_END -> g.top + EDGE + top + r
            Anchor.CENTER_START, Anchor.CENTER_END -> (g.top + g.bottom) / 2f
            Anchor.BOTTOM_START, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_END -> g.bottom - EDGE - r
        } + e.offsetDp.second * scale
        return x to y
    }

    private fun fits(x: Float, y: Float, r: Float, top: Float, g: Region, placed: List<PlacedControl>): Boolean {
        val m = EDGE / 2
        if (x - r < g.left + m || x + r > g.right - m || y - r - top < g.top + m || y + r > g.bottom - m) return false
        return placed.all { p ->
            val dx = p.cx - x
            val dy = p.cy - y
            val min = p.radius + r + GAP
            dx * dx + dy * dy >= min * min
        }
    }

    private fun findSpot(nx: Float, ny: Float, r: Float, top: Float, g: Region, placed: List<PlacedControl>): Pair<Float, Float>? {
        // Clamp into the region first: an offset pointing off-screen shouldn't force a search.
        val m = EDGE / 2
        val x0 = nx.coerceIn(g.left + m + r, maxOf(g.left + m + r, g.right - m - r))
        val y0 = ny.coerceIn(g.top + m + r + top, maxOf(g.top + m + r + top, g.bottom - m - r))
        if (fits(x0, y0, r, top, g, placed)) return x0 to y0
        // Search rings around the anchor, trying directions toward the region's middle first.
        val toward = Math.atan2((g.top + g.bottom) / 2.0 - y0, (g.left + g.right) / 2.0 - x0)
        val maxD = Math.hypot(g.width.toDouble(), g.height.toDouble()).toFloat()
        var d = 4f
        while (d <= maxD) {
            for (k in 0 until 24) {
                val step = (k + 1) / 2 * (if (k % 2 == 0) 1 else -1)
                val a = toward + Math.toRadians(15.0 * step)
                val x = x0 + (Math.cos(a) * d).toFloat()
                val y = y0 + (Math.sin(a) * d).toFloat()
                if (fits(x, y, r, top, g, placed)) return x to y
            }
            d += 4f
        }
        return null
    }
}
