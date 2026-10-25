package uz.pcremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Kompyuter klaviaturasi (15 birlik kenglikdagi noutbuk tartibi).
 * Shift / Ctrl / Alt / Win: 1 marta bosish = keyingi tugma uchun, 2 marta = qulflash, 3 marta = o'chirish,
 * uzoq bosish = tugmaning o'zi (masalan Win -> Start menyu).
 * Tugmani bosib turish = takrorlash (masalan Backspace, strelkalar).
 */
class PcKeyboardView(ctx: Context) : View(ctx) {

    private class K(val label: String, val code: String, val w: Float = 1f, val shiftLabel: String? = null) {
        val rect = RectF()
        val isMod get() = code in MOD_CODES
    }

    private enum class ModState { OFF, ONCE, LOCK }

    companion object {
        val MOD_CODES = setOf("shift", "ctrl", "alt", "cmd")
        private fun chars(s: String, shifted: String) = s.indices.map { K(s[it].toString(), s[it].toString(), 1f, shifted[it].toString()) }
    }

    private val rows: List<List<K>> = listOf(
        listOf(K("Esc", "esc")) + (1..12).map { K("F$it", "f$it") } + listOf(K("PrtSc", "print_screen"), K("Del", "delete")),
        chars("`1234567890-=", "~!@#$%^&*()_+") + listOf(K("⌫", "backspace", 2f)),
        listOf(K("Tab", "tab", 1.5f)) + chars("qwertyuiop[]", "QWERTYUIOP{}") + listOf(K("\\", "\\", 1.5f, "|")),
        listOf(K("Caps", "caps_lock", 1.75f)) + chars("asdfghjkl;'", "ASDFGHJKL:\"") + listOf(K("Enter", "enter", 2.25f)),
        listOf(K("Shift", "shift", 2f)) + chars("zxcvbnm,./", "ZXCVBNM<>?") + listOf(K("Shift", "shift", 1f), K("↑", "up"), K("End", "end")),
        listOf(K("Ctrl", "ctrl", 1.25f), K("Win", "cmd", 1.25f), K("Alt", "alt", 1.25f), K("", "space", 6.25f),
            K("Ctrl", "ctrl"), K("Home", "home"), K("←", "left"), K("↓", "down"), K("→", "right"))
    )
    private val allKeys = rows.flatten()

    private val mods = HashMap<String, ModState>().apply { MOD_CODES.forEach { put(it, ModState.OFF) } }
    private val pressed = HashMap<Int, K>() // pointerId -> tugma
    private val handler = Handler(Looper.getMainLooper())
    private var repeatKey: K? = null
    private var repeated = false
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            repeated = true
            sendKey(k)
            handler.postDelayed(this, 55)
        }
    }

    private var modLongKey: K? = null
    private var modLongFired = false
    private val modLongRunnable = Runnable {
        val k = modLongKey ?: return@Runnable
        modLongFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Conn.key(k.code, emptyList())
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT; textAlign = Paint.Align.CENTER }
    private val gap = ctx.dpf(2.5f)
    private val radius = ctx.dpf(5f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val unit = w / 15f
        val rowH = h / rows.size.toFloat()
        rows.forEachIndexed { r, row ->
            var x = 0f
            for (k in row) {
                k.rect.set(x + gap, r * rowH + gap, x + k.w * unit - gap, (r + 1) * rowH - gap)
                x += k.w * unit
            }
        }
        textPaint.textSize = minOf(rowH * 0.36f, unit * 0.42f)
    }

    private val shiftOn get() = mods["shift"] != ModState.OFF

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(C.BG)
        val pressedSet = pressed.values.toSet()
        for (k in allKeys) {
            val state = if (k.isMod) mods[k.code] else null
            keyPaint.color = when {
                k in pressedSet -> C.KEY_PRESSED
                state == ModState.LOCK -> C.ACCENT
                state == ModState.ONCE -> 0xFF2F5FA8.toInt()
                k.isMod || k.label.length > 1 -> 0xFF232A37.toInt()
                else -> C.KEY
            }
            canvas.drawRoundRect(k.rect, radius, radius, keyPaint)
            val label = if (shiftOn && k.shiftLabel != null) k.shiftLabel else k.label
            val small = label.length > 2
            val old = textPaint.textSize
            if (small) textPaint.textSize = old * 0.72f
            val ty = k.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, k.rect.centerX(), ty, textPaint)
            textPaint.textSize = old
        }
    }

    private fun keyAt(x: Float, y: Float): K? {
        for (k in allKeys) if (k.rect.contains(x, y)) return k
        // bo'shliqqa tushsa, eng yaqin tugma
        return allKeys.minByOrNull {
            val dx = it.rect.centerX() - x; val dy = it.rect.centerY() - y; dx * dx + dy * dy
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val k = keyAt(e.getX(i), e.getY(i)) ?: return true
                pressed[e.getPointerId(i)] = k
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (k.isMod) {
                    // uzoq bosish: tugmaning o'zini yuborish (masalan Win -> Start menyu)
                    handler.removeCallbacks(modLongRunnable)
                    modLongKey = k
                    modLongFired = false
                    handler.postDelayed(modLongRunnable, 500)
                } else if (k.code != "caps_lock") {
                    handler.removeCallbacks(repeatRunnable)
                    repeatKey = k
                    repeated = false
                    handler.postDelayed(repeatRunnable, 420)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val k = pressed.remove(id)
                if (k != null) {
                    if (k === repeatKey) {
                        handler.removeCallbacks(repeatRunnable)
                        repeatKey = null
                        if (!repeated) sendKey(k)
                    } else if (k.isMod) {
                        handler.removeCallbacks(modLongRunnable)
                        if (modLongKey === k && modLongFired) {
                            // uzoq bosilgan edi - tugmaning o'zi yuborilgan
                        } else {
                            toggleMod(k.code)
                        }
                        modLongKey = null
                    } else {
                        sendKey(k)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed.clear()
                handler.removeCallbacks(repeatRunnable)
                repeatKey = null
                invalidate()
            }
        }
        return true
    }

    private fun toggleMod(code: String) {
        mods[code] = when (mods[code]) {
            ModState.OFF -> ModState.ONCE
            ModState.ONCE -> ModState.LOCK
            else -> ModState.OFF
        }
    }

    private fun sendKey(k: K) {
        val active = mods.filter { it.value != ModState.OFF }.keys
        Conn.key(k.code, active)
        var changed = false
        for (m in MOD_CODES) if (mods[m] == ModState.ONCE) { mods[m] = ModState.OFF; changed = true }
        if (changed) invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(repeatRunnable)
    }
}
