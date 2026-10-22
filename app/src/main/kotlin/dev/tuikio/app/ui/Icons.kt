package dev.tuikio.app.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Tuikio's own icon set: rounded strokes on a 24-unit grid, drawn with Path so they scale
 * crisply and recolor with the theme. No icon font, no third-party icon pack.
 */
enum class Icon(val build: Path.() -> Unit, val filled: Boolean = false) {
    SEARCH({ addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW); moveTo(15.5f, 15.5f); lineTo(21f, 21f) }),
    BACK({ moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }),
    CHEVRON({ moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }),
    CLOSE({ moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }),
    CHECK({ moveTo(4f, 12.5f); lineTo(9f, 17.5f); lineTo(20f, 6.5f) }),
    PLAY({ moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close() }, filled = true),
    PAUSE({ moveTo(7f, 5f); lineTo(7f, 19f); moveTo(17f, 5f); lineTo(17f, 19f) }),
    HEART({
        moveTo(12f, 20f)
        cubicTo(12f, 20f, 4f, 15.2f, 4f, 9.6f)
        cubicTo(4f, 7.1f, 5.9f, 5.2f, 8.3f, 5.2f)
        cubicTo(9.9f, 5.2f, 11.2f, 6.1f, 12f, 7.4f)
        cubicTo(12.8f, 6.1f, 14.1f, 5.2f, 15.7f, 5.2f)
        cubicTo(18.1f, 5.2f, 20f, 7.1f, 20f, 9.6f)
        cubicTo(20f, 15.2f, 12f, 20f, 12f, 20f)
        close()
    }),
    COMPASS({ addCircle(12f, 12f, 9f, Path.Direction.CW); moveTo(15.5f, 8.5f); lineTo(13.5f, 13.5f); lineTo(8.5f, 15.5f); lineTo(10.5f, 10.5f); close() }),
    LIBRARY({ addRoundRect(RectF(4f, 5f, 20f, 19f), 4f, 4f, Path.Direction.CW); moveTo(8f, 10f); lineTo(16f, 10f); moveTo(8f, 14f); lineTo(13f, 14f) }),
    PLUS({ moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }),
    PERSON({ addCircle(12f, 9f, 4f, Path.Direction.CW); moveTo(5f, 20f); cubicTo(6.5f, 16f, 9f, 14.5f, 12f, 14.5f); cubicTo(15f, 14.5f, 17.5f, 16f, 19f, 20f) }),
    PENCIL({ moveTo(14f, 4f); lineTo(20f, 10f); lineTo(11f, 19f); lineTo(5f, 19f); lineTo(5f, 13f); close() }),
    DOWNLOAD({ moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f); moveTo(5f, 20f); lineTo(19f, 20f) }),
    TRASH({ moveTo(4f, 7f); lineTo(20f, 7f); moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f); moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f) }),
    SHARE({ addCircle(6f, 12f, 2.5f, Path.Direction.CW); addCircle(18f, 6f, 2.5f, Path.Direction.CW); addCircle(18f, 18f, 2.5f, Path.Direction.CW); moveTo(8.2f, 10.9f); lineTo(15.8f, 7.1f); moveTo(8.2f, 13.1f); lineTo(15.8f, 16.9f) }),
    BRANCH({ addCircle(7f, 6f, 2.4f, Path.Direction.CW); addCircle(7f, 18f, 2.4f, Path.Direction.CW); addCircle(17f, 8f, 2.4f, Path.Direction.CW); moveTo(7f, 8.4f); lineTo(7f, 15.6f); moveTo(17f, 10.4f); cubicTo(17f, 14f, 11f, 13f, 8.4f, 16.2f) }),
    SHIELD({ moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); cubicTo(19f, 16f, 15.5f, 19.5f, 12f, 21f); cubicTo(8.5f, 19.5f, 5f, 16f, 5f, 11f); lineTo(5f, 6f); close() }),
    PALETTE({
        moveTo(12f, 3.5f)
        cubicTo(7f, 3.5f, 3.5f, 7.3f, 3.5f, 12f)
        cubicTo(3.5f, 16.7f, 7.2f, 20.5f, 11.5f, 20.5f)
        cubicTo(13f, 20.5f, 13.3f, 19.2f, 12.6f, 18.2f)
        cubicTo(11.8f, 17f, 12.6f, 15.5f, 14.1f, 15.5f)
        lineTo(16f, 15.5f)
        cubicTo(18.7f, 15.5f, 20.5f, 13.7f, 20.5f, 11.3f)
        cubicTo(20.5f, 6.9f, 16.7f, 3.5f, 12f, 3.5f)
        close()
        addCircle(8f, 11f, 1.2f, Path.Direction.CW); addCircle(11f, 7.5f, 1.2f, Path.Direction.CW); addCircle(15.5f, 8.5f, 1.2f, Path.Direction.CW)
    }),
    KEYBOARD({ addRoundRect(RectF(3f, 6f, 21f, 18f), 3f, 3f, Path.Direction.CW); moveTo(7f, 10f); lineTo(7.5f, 10f); moveTo(11f, 10f); lineTo(11.5f, 10f); moveTo(15f, 10f); lineTo(15.5f, 10f); moveTo(8f, 14f); lineTo(16f, 14f) }),
    WARNING({ moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close(); moveTo(12f, 10f); lineTo(12f, 14.5f); moveTo(12f, 17.3f); lineTo(12f, 17.6f) }),
    OFFLINE({ moveTo(3f, 9f); cubicTo(8f, 4.5f, 16f, 4.5f, 21f, 9f); moveTo(6.5f, 12.5f); cubicTo(9.5f, 10f, 14.5f, 10f, 17.5f, 12.5f); moveTo(10f, 16f); cubicTo(11f, 15.3f, 13f, 15.3f, 14f, 16f); moveTo(4f, 4f); lineTo(20f, 20f) }),
    INFO({ addCircle(12f, 12f, 9f, Path.Direction.CW); moveTo(12f, 11f); lineTo(12f, 16.5f); moveTo(12f, 7.6f); lineTo(12f, 7.9f) }),
    LINK({ moveTo(10f, 14f); lineTo(14f, 10f); moveTo(8.5f, 11.5f); lineTo(6.5f, 13.5f); cubicTo(5f, 15f, 5f, 17.5f, 6.5f, 19f); cubicTo(8f, 20.5f, 10.5f, 20.5f, 12f, 19f); lineTo(14f, 17f); moveTo(10f, 7f); lineTo(12f, 5f); cubicTo(13.5f, 3.5f, 16f, 3.5f, 17.5f, 5f); cubicTo(19f, 6.5f, 19f, 9f, 17.5f, 10.5f); lineTo(15.5f, 12.5f) }),
    IMAGE({ addRoundRect(RectF(3.5f, 5f, 20.5f, 19f), 3f, 3f, Path.Direction.CW); addCircle(9f, 10f, 1.8f, Path.Direction.CW); moveTo(4f, 17f); lineTo(9.5f, 12.5f); lineTo(13f, 15.5f); lineTo(16f, 13f); lineTo(20f, 16.5f) }),
    REFRESH({ moveTo(19f, 12f); cubicTo(19f, 15.9f, 15.9f, 19f, 12f, 19f); cubicTo(8.1f, 19f, 5f, 15.9f, 5f, 12f); cubicTo(5f, 8.1f, 8.1f, 5f, 12f, 5f); lineTo(16f, 5f); moveTo(13.5f, 2.5f); lineTo(16f, 5f); lineTo(13.5f, 7.5f) }),
    GAMEPAD({ addRoundRect(RectF(2.5f, 7f, 21.5f, 18f), 5.5f, 5.5f, Path.Direction.CW); moveTo(6f, 12.5f); lineTo(10f, 12.5f); moveTo(8f, 10.5f); lineTo(8f, 14.5f); moveTo(15.5f, 11.5f); lineTo(15.6f, 11.5f); moveTo(18f, 13.5f); lineTo(18.1f, 13.5f) }),
    ROTATE({ moveTo(18.5f, 9f); cubicTo(17.2f, 6f, 14.8f, 4.5f, 12f, 4.5f); cubicTo(7.9f, 4.5f, 4.5f, 7.9f, 4.5f, 12f); cubicTo(4.5f, 16.1f, 7.9f, 19.5f, 12f, 19.5f); cubicTo(15.4f, 19.5f, 18.2f, 17.3f, 19.2f, 14.2f); moveTo(19f, 4f); lineTo(18.7f, 9.2f); lineTo(13.6f, 8.6f) }),
    ARROW_UP({ moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f) }),
    ARROW_DOWN({ moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) }),
    ARROW_LEFT({ moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }),
    ARROW_RIGHT({ moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }),
    DROP({ moveTo(6f, 5f); lineTo(12f, 11f); lineTo(18f, 5f); moveTo(6f, 11f); lineTo(12f, 17f); lineTo(18f, 11f); moveTo(5f, 20.5f); lineTo(19f, 20.5f) }),
    FLAG({ moveTo(6f, 21f); lineTo(6f, 4f); lineTo(17f, 7.5f); lineTo(6f, 11.5f) }),
    BAG({ addRoundRect(RectF(4.5f, 8f, 19.5f, 20f), 3f, 3f, Path.Direction.CW); moveTo(9f, 8f); lineTo(9f, 6f); cubicTo(9f, 4.3f, 10.3f, 3.5f, 12f, 3.5f); cubicTo(13.7f, 3.5f, 15f, 4.3f, 15f, 6f); lineTo(15f, 8f) }),
    STAIRS({ moveTo(4f, 19f); lineTo(9f, 19f); lineTo(9f, 14f); lineTo(14f, 14f); lineTo(14f, 9f); lineTo(19f, 9f); lineTo(19f, 4f) }),
    SPARK({ moveTo(12f, 3f); lineTo(13.8f, 10.2f); lineTo(21f, 12f); lineTo(13.8f, 13.8f); lineTo(12f, 21f); lineTo(10.2f, 13.8f); lineTo(3f, 12f); lineTo(10.2f, 10.2f); close() }),
    SETTINGS({ addCircle(12f, 12f, 3f, Path.Direction.CW); moveTo(12f, 2.8f); lineTo(12f, 5.5f); moveTo(12f, 18.5f); lineTo(12f, 21.2f); moveTo(2.8f, 12f); lineTo(5.5f, 12f); moveTo(18.5f, 12f); lineTo(21.2f, 12f); moveTo(5.5f, 5.5f); lineTo(7.4f, 7.4f); moveTo(16.6f, 16.6f); lineTo(18.5f, 18.5f); moveTo(5.5f, 18.5f); lineTo(7.4f, 16.6f); moveTo(16.6f, 7.4f); lineTo(18.5f, 5.5f) }),
    DOT({ addCircle(12f, 12f, 4f, Path.Direction.CW) }, filled = true),
}

class IconDrawable(private val icon: Icon, color: Int, private val strokeUnits: Float = 2.4f) : Drawable() {
    private val path = Path().apply(icon.build)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = if (icon.filled) Paint.Style.FILL else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var color: Int
        get() = paint.color
        set(v) {
            paint.color = v
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = Math.min(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        canvas.scale(s, s)
        paint.strokeWidth = strokeUnits
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
