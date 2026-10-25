package uz.pcremote

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Kod orqali UI yaratish uchun kichik yordamchilar. */
object C {
    const val BG = 0xFF12161E.toInt()
    const val PANEL = 0xFF1E2430.toInt()
    const val KEY = 0xFF2A3140.toInt()
    const val KEY_PRESSED = 0xFF3D8BFD.toInt()
    const val ACCENT = 0xFF3D8BFD.toInt()
    const val TEXT = 0xFFE8ECF3.toInt()
    const val MUTED = 0xFF8A94A6.toInt()
    const val OK = 0xFF37C871.toInt()
    const val ERR = 0xFFFF5C5C.toInt()
}

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun Context.dpf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.label(text: String, size: Float = 14f, color: Int = C.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

fun Context.button(text: String, primary: Boolean = false, onClick: (View) -> Unit) =
    Button(this).apply {
        this.text = text
        setAllCaps(false)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = rounded(if (primary) C.ACCENT else C.KEY, dpf(10f))
        stateListAnimator = null
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        setOnClickListener(onClick)
    }

fun Context.input(hint: String, value: String = "", numeric: Boolean = false) =
    EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(C.TEXT)
        setHintTextColor(C.MUTED)
        setSingleLine(true)
        background = rounded(C.KEY, dpf(10f))
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

fun Context.vbox(pad: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(pad, pad, pad, pad)
}

fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int, h: Int, weight: Float = 0f, margin: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(margin, margin, margin, margin) }

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
