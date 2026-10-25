package uz.pcremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Noutbuk touchpadi kabi:
 *  - 1 barmoq siljitish  -> kursor harakati
 *  - 1 barmoq bosish     -> chap klik
 *  - 2 barmoq bosish     -> o'ng klik
 *  - 3 barmoq bosish     -> o'rta klik
 *  - 2 barmoq siljitish  -> scroll (yuqori/past va chap/o'ng)
 *  - bosib, darhol yana bosib siljitish -> sudrab olib borish (drag)
 */
class TouchpadView(ctx: Context) : View(ctx) {
    var sensitivity = 1.6f

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val tapTimeout = 220L
    private val scrollStep = ctx.dpf(18f)

    private var downTime = 0L
    private var lastUpTime = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var moved = false
    private var maxPointers = 1
    private var dragging = false
    private var accX = 0f
    private var accY = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.PANEL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF323A4B.toInt(); style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.5f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A6478.toInt(); textAlign = Paint.Align.CENTER; textSize = ctx.dpf(12f)
    }
    private val rect = RectF()
    private val radius = ctx.dpf(16f)

    override fun onDraw(canvas: Canvas) {
        rect.set(borderPaint.strokeWidth, borderPaint.strokeWidth, width - borderPaint.strokeWidth, height - borderPaint.strokeWidth)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        val cy = height / 2f
        canvas.drawText(if (dragging) "Sudralmoqda..." else "Touchpad", width / 2f, cy - hintPaint.textSize, hintPaint)
        canvas.drawText("2 barmoq: o'ng klik / scroll", width / 2f, cy + hintPaint.textSize * 0.5f, hintPaint)
        canvas.drawText("bosib-bosib siljitish: sudrash", width / 2f, cy + hintPaint.textSize * 2f, hintPaint)
    }

    private fun centerX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun centerY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = now
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y; lastT = now
                moved = false
                maxPointers = 1
                accX = 0f; accY = 0f
                // Ikkinchi bosish tez kelsa -> sudrash rejimi
                if (now - lastUpTime < 260 && hypot(e.x - lastUpX, e.y - lastUpY) < slop * 4) {
                    dragging = true
                    Conn.button("left", "down")
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                lastX = centerX(e); lastY = centerY(e)
                scrollAccX = 0f; scrollAccY = 0f
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // qolgan barmoqlarning markazidan davom etamiz (sakrash bo'lmasin)
                var sx = 0f; var sy = 0f; var n = 0
                for (i in 0 until e.pointerCount) {
                    if (i == e.actionIndex) continue
                    sx += e.getX(i); sy += e.getY(i); n++
                }
                if (n > 0) { lastX = sx / n; lastY = sy / n }
            }
            MotionEvent.ACTION_MOVE -> {
                val x = centerX(e)
                val y = centerY(e)
                val dx = x - lastX
                val dy = y - lastY
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                if (e.pointerCount >= 2) {
                    if (moved) {
                        scrollAccY += dy
                        scrollAccX += dx
                        while (abs(scrollAccY) >= scrollStep) {
                            val dir = if (scrollAccY > 0) 1 else -1
                            Conn.scroll(dir) // barmoq pastga -> sahifa yuqoriga (tabiiy scroll)
                            scrollAccY -= dir * scrollStep
                        }
                        while (abs(scrollAccX) >= scrollStep * 1.5f) {
                            val dir = if (scrollAccX > 0) 1 else -1
                            Conn.scroll(0, -dir)
                            scrollAccX -= dir * scrollStep * 1.5f
                        }
                    }
                } else if (moved) {
                    val dt = (now - lastT).coerceAtLeast(1L)
                    val speed = hypot(dx, dy) / dt // piksel / ms
                    val accel = sensitivity * (1f + min(speed * 1.2f, 2.5f))
                    accX += dx * accel
                    accY += dy * accel
                    val ix = accX.toInt()
                    val iy = accY.toInt()
                    if (ix != 0 || iy != 0) {
                        Conn.move(ix, iy)
                        accX -= ix; accY -= iy
                    }
                }
                lastX = x; lastY = y; lastT = now
            }
            MotionEvent.ACTION_UP -> {
                val isTap = !moved && now - downTime < tapTimeout + 150
                if (dragging) {
                    dragging = false
                    Conn.button("left", "up")
                    invalidate()
                    lastUpTime = 0
                } else if (isTap) {
                    when (maxPointers) {
                        1 -> Conn.button("left")
                        2 -> Conn.button("right")
                        else -> Conn.button("middle")
                    }
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    lastUpTime = if (maxPointers == 1) now else 0
                    lastUpX = e.x; lastUpY = e.y
                } else {
                    lastUpTime = 0
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { dragging = false; Conn.button("left", "up"); invalidate() }
            }
        }
        return true
    }
}
