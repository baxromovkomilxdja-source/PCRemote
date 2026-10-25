package uz.pcremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class MainActivity : Activity() {
    private val modes = listOf("wifi" to "Wi-Fi", "net" to "Internet", "bt" to "Bluetooth", "usb" to "USB")
    private var mode = "wifi"
    private var selectedBt: BluetoothDevice? = null
    private var busy = false

    private lateinit var tabs: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var connectBtn: View
    private val pinInput by lazy { input("PIN kod (kompyuter oynasida)", prefs.getString("pin", "")!!, numeric = true) }
    private val hostInput by lazy { input("Kompyuter IP manzili, masalan 192.168.1.5", prefs.getString("host", "")!!) }
    private val portInput by lazy { input("Port", prefs.getString("port", "5050")!!, numeric = true) }
    private val relayInput by lazy { input("Relay server, masalan myrelay.onrender.com", prefs.getString("relay", "")!!) }
    private val roomInput by lazy { input("Xona kodi (kompyuter oynasida)", prefs.getString("room", "")!!) }
    private val prefs by lazy { getSharedPreferences("pcremote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = prefs.getString("mode", "wifi") ?: "wifi"

        val root = vbox(dp(18f))
        root.addView(label("PC Remote", 26f, bold = true))
        root.addView(label("Telefoningizdan kompyuterni boshqaring", 14f, C.MUTED), lp(MATCH, WRAP).apply { bottomMargin = dp(16f) })

        tabs = hbox()
        root.addView(tabs, lp(MATCH, dp(46f)))

        panel = vbox()
        root.addView(panel, lp(MATCH, WRAP).apply { topMargin = dp(14f) })

        root.addView(label("PIN kod", 13f, C.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(14f) })
        root.addView(pinInput, lp(MATCH, WRAP).apply { topMargin = dp(4f) })

        connectBtn = button("Ulanish", primary = true) { doConnect() }
        root.addView(connectBtn, lp(MATCH, dp(54f)).apply { topMargin = dp(18f) })

        status = label("", 14f, C.MUTED).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(status, lp(MATCH, WRAP).apply { topMargin = dp(12f) })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        renderTabs()
        renderPanel()
    }

    override fun onResume() {
        super.onResume()
        if (!busy) setStatus("", C.MUTED)
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        for ((id, title) in modes) {
            val b = button(title, primary = id == mode) {
                mode = id; prefs.edit().putString("mode", id).apply()
                renderTabs(); renderPanel()
            }
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            b.setPadding(0, 0, 0, 0)
            tabs.addView(b, lp(0, MATCH, 1f).apply { setMargins(dp(3f), 0, dp(3f), 0) })
        }
    }

    private fun detach(v: View) { (v.parent as? LinearLayout)?.removeView(v) }

    private fun hint(text: String) = label(text, 13f, C.MUTED).apply { setLineSpacing(0f, 1.2f) }

    private fun renderPanel() {
        panel.removeAllViews()
        listOf(hostInput, portInput, relayInput, roomInput).forEach { detach(it) }
        when (mode) {
            "wifi" -> {
                panel.addView(hint("Kompyuter va telefon bitta routerga ulangan bo'lsin. Kompyuter kabel (LAN) orqali ulangan bo'lsa ham ishlaydi."))
                val found = vbox()
                val search = button("Kompyuterni qidirish") { discover(found) }
                panel.addView(search, lp(MATCH, dp(48f)).apply { topMargin = dp(10f) })
                panel.addView(found, lp(MATCH, WRAP))
                panel.addView(hostInput, lp(MATCH, WRAP).apply { topMargin = dp(10f) })
                panel.addView(portInput, lp(MATCH, WRAP).apply { topMargin = dp(8f) })
            }
            "net" -> {
                panel.addView(hint("Kompyuter va telefon turli tarmoqlarda bo'lsa (mobil internet ham bo'ladi). Kompyuterda agent --relay bilan ishga tushgan bo'lishi kerak."))
                panel.addView(relayInput, lp(MATCH, WRAP).apply { topMargin = dp(10f) })
                panel.addView(roomInput, lp(MATCH, WRAP).apply { topMargin = dp(8f) })
            }
            "bt" -> {
                panel.addView(hint("Avval telefonni kompyuter bilan Bluetooth sozlamalarida juftlang (pair). Ekran sifati past bo'ladi — Bluetooth sekin."))
                val list = vbox()
                panel.addView(list, lp(MATCH, WRAP).apply { topMargin = dp(8f) })
                fillBluetooth(list)
            }
            "usb" -> {
                panel.addView(hint(
                    "1) Telefonda Dasturchi sozlamalari → USB debugging ni yoqing.\n" +
                    "2) Kabel bilan ulang.\n" +
                    "3) Kompyuterda buyruq:  adb reverse tcp:5050 tcp:5050\n" +
                    "Internet ham, Wi-Fi ham kerak emas, eng tez ulanish."
                ))
            }
        }
    }

    // ------------------------------------------------------------ Wi-Fi qidirish
    private fun discover(container: LinearLayout) {
        container.removeAllViews()
        setStatus("Qidirilmoqda...", C.MUTED)
        Thread {
            val results = LinkedHashMap<String, String>()
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("pcremote").apply { setReferenceCounted(false) }
            try {
                lock.acquire()
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 700
                    val msg = "PCREMOTE?".toByteArray()
                    val targets = mutableListOf("255.255.255.255")
                    @Suppress("DEPRECATION")
                    val dhcp = wifi.dhcpInfo
                    if (dhcp != null && dhcp.ipAddress != 0) {
                        val bc = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                        targets.add("${bc and 0xFF}.${bc shr 8 and 0xFF}.${bc shr 16 and 0xFF}.${bc ushr 24 and 0xFF}")
                    }
                    val end = System.currentTimeMillis() + 2500
                    var lastSend = 0L
                    while (System.currentTimeMillis() < end) {
                        if (System.currentTimeMillis() - lastSend > 800) {
                            for (t in targets) {
                                try { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), 5051)) } catch (_: Exception) {}
                            }
                            lastSend = System.currentTimeMillis()
                        }
                        try {
                            val buf = ByteArray(512)
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            val txt = String(p.data, 0, p.length)
                            if (txt.startsWith("PCREMOTE ")) {
                                val j = JSONObject(txt.removePrefix("PCREMOTE "))
                                val ip = p.address.hostAddress ?: continue
                                results["$ip:${j.optInt("port", 5050)}"] = j.optString("name", ip)
                            }
                        } catch (_: SocketTimeoutException) {}
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Qidirishda xato: ${e.message}", C.ERR) }
            } finally {
                try { lock.release() } catch (_: Exception) {}
            }
            runOnUiThread {
                if (results.isEmpty()) {
                    setStatus("Topilmadi. Agent ishlayaptimi? Firewall 5050/5051 portlarga ruxsat beryaptimi? IP ni qo'lda kiriting.", C.ERR)
                } else {
                    setStatus("Topildi: ${results.size} ta kompyuter", C.OK)
                    for ((addr, name) in results) {
                        container.addView(button("🖥  $name  ($addr)") {
                            hostInput.setText(addr.substringBefore(":"))
                            portInput.setText(addr.substringAfter(":"))
                        }, lp(MATCH, dp(48f)).apply { topMargin = dp(6f) })
                    }
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ Bluetooth
    @SuppressLint("MissingPermission")
    private fun fillBluetooth(list: LinearLayout) {
        list.removeAllViews()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            list.addView(button("Bluetooth ruxsatini berish") {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7)
            }, lp(MATCH, dp(48f)))
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { list.addView(hint("Bu telefonda Bluetooth yo'q.")); return }
        if (!adapter.isEnabled) { list.addView(hint("Bluetooth o'chiq. Uni yoqing va bu oynaga qayting.")); return }
        val devices = adapter.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) { list.addView(hint("Juftlangan qurilma yo'q.")); return }
        val savedAddr = prefs.getString("bt", "")
        for (d in devices) {
            if (d.address == savedAddr && selectedBt == null) selectedBt = d
            val name = d.name ?: d.address
            val b = button((if (d == selectedBt) "✓  " else "") + name, primary = d == selectedBt) {
                selectedBt = d
                prefs.edit().putString("bt", d.address).apply()
                fillBluetooth(list)
            }
            list.addView(b, lp(MATCH, dp(48f)).apply { topMargin = dp(6f) })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) renderPanel()
    }

    // ------------------------------------------------------------ ulanish
    private fun setStatus(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
    }

    private fun doConnect() {
        if (busy) return
        val pin = pinInput.text.toString().trim()
        if (pin.isEmpty()) { setStatus("PIN kodni kiriting", C.ERR); return }
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: 5050
        val relay = relayInput.text.toString().trim()
        val room = roomInput.text.toString().trim()
        val bt = selectedBt

        when (mode) {
            "wifi" -> if (host.isEmpty()) { setStatus("IP manzilni kiriting yoki qidiring", C.ERR); return }
            "net" -> if (relay.isEmpty() || room.isEmpty()) { setStatus("Relay server va xona kodini kiriting", C.ERR); return }
            "bt" -> if (bt == null) { setStatus("Kompyuterni ro'yxatdan tanlang", C.ERR); return }
        }
        prefs.edit().putString("pin", pin).putString("host", host).putString("port", port.toString())
            .putString("relay", relay).putString("room", room).apply()

        busy = true
        connectBtn.isEnabled = false
        setStatus("Ulanmoqda...", C.MUTED)
        Thread {
            val err: String? = try {
                val link = when (mode) {
                    "net" -> Links.relay(relay, room)
                    "bt" -> Links.bluetooth(bt!!)
                    "usb" -> Links.tcp("127.0.0.1", port, "USB")
                    else -> Links.tcp(host, port, "Wi-Fi")
                }
                Conn.connect(link, pin)
            } catch (e: Exception) {
                when (mode) {
                    "usb" -> "USB orqali ulanmadi. 'adb reverse tcp:5050 tcp:5050' bajarildimi?"
                    "wifi" -> "Ulanmadi: ${e.message}. IP to'g'rimi, agent ishlayaptimi?"
                    else -> e.message ?: "Ulanmadi"
                }
            }
            runOnUiThread {
                busy = false
                connectBtn.isEnabled = true
                if (err == null) {
                    setStatus("Ulandi", C.OK)
                    startActivity(Intent(this, RemoteActivity::class.java))
                } else {
                    setStatus(err, C.ERR)
                }
            }
        }.start()
    }
}
