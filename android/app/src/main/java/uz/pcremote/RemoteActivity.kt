package uz.pcremote

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class RemoteActivity : Activity(), Conn.Listener {
    private lateinit var screen: ScreenView
    private lateinit var touchBlock: LinearLayout
    private lateinit var keyboard: PcKeyboardView
    private lateinit var tabPad: Button
    private lateinit var tabKbd: Button
    private lateinit var eyeBtn: Button
    private lateinit var qualityBtn: Button
    private var showKeyboard = false
    private val qualityNames = listOf("Past", "O'rta", "Yuqori")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!Conn.connected) { finish(); return }
        showKeyboard = savedInstanceState?.getBoolean("kbd") ?: false
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("kbd", showKeyboard)
    }

    // ------------------------------------------------------------ UI
    private fun smallBtn(text: String, onClick: (View) -> Unit) = button(text, onClick = onClick).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(4f), 0, dp(4f), 0)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
    }

    private fun build() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val m = dp(3f)

        // yuqori panel
        val top = hbox().apply { setPadding(dp(8f), dp(4f), dp(4f), dp(4f)) }
        val title = label("● ${Conn.pcName} · ${Conn.kind}", 13f, C.OK).apply { setSingleLine(true) }
        top.addView(title, lp(0, WRAP, 1f))
        eyeBtn = smallBtn("") { toggleScreen() }
        qualityBtn = smallBtn("") {
            Conn.quality = (Conn.quality + 1) % 3
            updateTopButtons(); Conn.applyScreen()
        }
        top.addView(eyeBtn, lp(WRAP, dp(36f), 0f, m))
        top.addView(qualityBtn, lp(WRAP, dp(36f), 0f, m))
        top.addView(smallBtn("✕") { Conn.disconnect(null); finish() }, lp(dp(44f), dp(36f), 0f, m))

        screen = ScreenView(this)
        if (!Conn.screenSupported) screen.placeholder = "Kompyuterda ekran uzatish o'chiq (mss/Pillow)"

        // media panel
        val media = hbox()
        val mediaKeys = listOf("⏮" to "prev", "⏯" to "play", "⏭" to "next", "🔉" to "voldown", "🔊" to "volup", "🔇" to "mute")
        for ((t, k) in mediaKeys) media.addView(smallBtn(t) { Conn.media(k) }, lp(0, MATCH, 1f, m))
        media.addView(smallBtn("Aa") { textDialog() }, lp(0, MATCH, 1f, m))

        // tablar
        val tabs = hbox()
        tabPad = smallBtn("🖱  Touchpad") { showKeyboard = false; updateTabs() }
        tabKbd = smallBtn("⌨  Klaviatura") { showKeyboard = true; updateTabs() }
        tabs.addView(tabPad, lp(0, MATCH, 1f, m))
        tabs.addView(tabKbd, lp(0, MATCH, 1f, m))

        // touchpad + tugmalar
        touchBlock = vbox()
        touchBlock.addView(TouchpadView(this), lp(MATCH, 0, 1f, m))
        val btns = hbox()
        btns.addView(mouseBtn("Chap", "left"), lp(0, MATCH, 2f, m))
        btns.addView(mouseBtn("O'rta", "middle"), lp(0, MATCH, 1f, m))
        btns.addView(mouseBtn("O'ng", "right"), lp(0, MATCH, 2f, m))
        touchBlock.addView(btns, lp(MATCH, dp(52f)))

        keyboard = PcKeyboardView(this)
        val content = FrameLayout(this)
        content.addView(touchBlock, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(keyboard, FrameLayout.LayoutParams(MATCH, MATCH))

        val root: LinearLayout
        if (landscape) {
            root = hbox().apply { gravity = Gravity.NO_GRAVITY }
            val left = vbox()
            left.addView(top, lp(MATCH, WRAP))
            left.addView(screen, lp(MATCH, 0, 1f, m))
            val right = vbox()
            right.addView(media, lp(MATCH, dp(44f)))
            right.addView(tabs, lp(MATCH, dp(40f)))
            right.addView(content, lp(MATCH, 0, 1f))
            root.addView(left, lp(0, MATCH, 1.15f))
            root.addView(right, lp(0, MATCH, 1f))
        } else {
            root = vbox()
            root.addView(top, lp(MATCH, WRAP))
            val h = resources.displayMetrics.widthPixels * 9 / 16
            root.addView(screen, lp(MATCH, h).apply { setMargins(m, 0, m, m) })
            root.addView(media, lp(MATCH, dp(44f)))
            root.addView(tabs, lp(MATCH, dp(40f)))
            root.addView(content, lp(MATCH, 0, 1f))
        }
        root.setBackgroundColor(C.BG)
        setContentView(root)
        updateTabs()
        updateTopButtons()
    }

    private fun mouseBtn(text: String, b: String): View = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(C.TEXT)
        background = rounded(C.KEY, dpf(10f))
        // bosib turish = tugmani ushlab turish (sudrash uchun)
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    Conn.button(b, "down"); v.background = rounded(C.KEY_PRESSED, dpf(10f))
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    Conn.button(b, "up"); v.background = rounded(C.KEY, dpf(10f))
                }
            }
            true
        }
    }

    private fun updateTabs() {
        touchBlock.visibility = if (showKeyboard) View.GONE else View.VISIBLE
        keyboard.visibility = if (showKeyboard) View.VISIBLE else View.GONE
        tabPad.background = rounded(if (!showKeyboard) C.ACCENT else C.KEY, dpf(10f))
        tabKbd.background = rounded(if (showKeyboard) C.ACCENT else C.KEY, dpf(10f))
    }

    private fun updateTopButtons() {
        eyeBtn.text = if (Conn.screenOn) "👁 Ekran" else "Ekran o'chiq"
        qualityBtn.text = qualityNames[Conn.quality]
        qualityBtn.visibility = if (Conn.screenOn) View.VISIBLE else View.GONE
        screen.visibility = if (Conn.screenOn) View.VISIBLE else View.GONE
    }

    private fun toggleScreen() {
        Conn.screenOn = !Conn.screenOn
        if (!Conn.screenOn) screen.clear()
        updateTopButtons()
        Conn.applyScreen()
    }

    private fun textDialog() {
        val edit = EditText(this).apply {
            hint = "Matn (har qanday til)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Kompyuterga matn yozish")
            .setView(edit)
            .setPositiveButton("Yuborish") { _, _ -> Conn.text(edit.text.toString()) }
            .setNeutralButton("Yuborish + Enter") { _, _ ->
                Conn.text(edit.text.toString()); Conn.key("enter", emptyList())
            }
            .setNegativeButton("Bekor", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        edit.requestFocus()
    }

    // ------------------------------------------------------------ hayot sikli
    override fun onResume() {
        super.onResume()
        if (!Conn.connected) { finish(); return }
        Conn.listener = this
        Conn.applyScreen()
    }

    override fun onPause() {
        super.onPause()
        // fonga o'tganda ekran uzatishni to'xtatamiz (trafik tejaladi)
        Conn.sendJson(JSONObject().put("t", "screen").put("on", false))
        if (Conn.listener === this) Conn.listener = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setMessage("Kompyuterdan uzilsinmi?")
            .setPositiveButton("Ha") { _, _ -> Conn.disconnect(null); finish() }
            .setNegativeButton("Yo'q", null)
            .show()
    }

    override fun onFrame(bmp: Bitmap) {
        if (Conn.screenOn) screen.setFrame(bmp)
    }

    override fun onDisconnected(reason: String) {
        Toast.makeText(this, "Uzildi: $reason", Toast.LENGTH_LONG).show()
        finish()
    }
}
