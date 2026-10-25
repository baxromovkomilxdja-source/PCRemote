package uz.pcremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * Kompyuter ekrani. Ekranga bosish = shu joyga sichqoncha bilan bosish:
 *  bir marta bosish -> chap klik, ikki marta -> ikki marta klik, uzoq bosish -> o'ng klik.
 */
class ScreenView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.MUTED; textAlign = Paint.Align.CENTER; textSize = ctx.dpf(14f)
    }
    var placeholder = "Ekran yuklanmoqda..."
        set(v) { field = v; invalidate() }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (point(e)) Conn.button("left")
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (point(e)) Conn.button("left", "dbl")
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (point(e)) {
                Conn.button("right")
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
    })

    fun setFrame(b: Bitmap) {
        bmp = b
        invalidate()
    }

    fun clear() {
        bmp = null
        invalidate()
    }

    /** Bosilgan nuqtani kompyuter ekranidagi nisbiy koordinataga aylantiradi. */
    private fun point(e: MotionEvent): Boolean {
        if (bmp == null || dst.width() <= 0f) return false
        val x = (e.x - dst.left) / dst.width()
        val y = (e.y - dst.top) / dst.height()
        if (x < 0f || x > 1f || y < 0f || y > 1f) return false
        Conn.abs(x, y)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF000000.toInt())
        val b = bmp
        if (b == null) {
            canvas.drawText(placeholder, width / 2f, height / 2f, textPaint)
            return
        }
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        src.set(0, 0, b.width, b.height)
        canvas.drawBitmap(b, src, dst, paint)
    }
}
