package uz.pcremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

const val T_JSON = 1
const val T_JPEG = 2

/** Kompyuter bilan aloqa kanali: TCP, Bluetooth yoki Internet (relay). */
interface Link {
    val kind: String
    fun send(type: Int, payload: ByteArray)
    /** Bloklovchi o'qish. Ulanish yopilsa IOException tashlaydi. */
    fun recv(): Pair<Int, ByteArray>
    fun close()
}

/** Oqim (TCP / Bluetooth) uchun: [turi 1 bayt][uzunlik 4 bayt][ma'lumot]. */
open class StreamLink(
    override val kind: String,
    input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit
) : Link {
    private val inp = DataInputStream(input.buffered(64 * 1024))
    private val wlock = Any()

    override fun send(type: Int, payload: ByteArray) {
        val head = ByteArray(5)
        head[0] = type.toByte()
        val n = payload.size
        head[1] = (n ushr 24).toByte()
        head[2] = (n ushr 16).toByte()
        head[3] = (n ushr 8).toByte()
        head[4] = n.toByte()
        synchronized(wlock) {
            output.write(head)
            output.write(payload)
            output.flush()
        }
    }

    override fun recv(): Pair<Int, ByteArray> {
        val type = inp.readUnsignedByte()
        val len = inp.readInt()
        if (len < 0 || len > 16 * 1024 * 1024) throw IOException("Noto'g'ri paket")
        val buf = ByteArray(len)
        inp.readFully(buf)
        return Pair(type, buf)
    }

    override fun close() {
        try { closer() } catch (_: Exception) {}
    }
}

object Links {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val BT_CHANNEL = 4

    fun tcp(host: String, port: Int, kind: String = "Wi-Fi"): Link {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 6000)
        return StreamLink(kind, s.getInputStream(), s.getOutputStream()) { s.close() }
    }

    @SuppressLint("MissingPermission")
    fun bluetooth(device: BluetoothDevice): Link {
        var lastError: Exception? = null
        // 1) To'g'ridan-to'g'ri kanal 4 (Windows Python SDP yozuvini e'lon qilmaydi)
        // 2) Standart SPP UUID (Linux da sdptool bilan e'lon qilinadi)
        val attempts = listOf<() -> BluetoothSocket>(
            {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, BT_CHANNEL) as BluetoothSocket
            },
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
        )
        for (make in attempts) {
            var sock: BluetoothSocket? = null
            try {
                sock = make()
                sock.connect()
                val connected = sock
                return StreamLink("Bluetooth", connected.inputStream, connected.outputStream) { connected.close() }
            } catch (e: Exception) {
                lastError = e
                try { sock?.close() } catch (_: Exception) {}
            }
        }
        throw IOException("Bluetooth ulanmadi: ${lastError?.message ?: "noma'lum xato"}")
    }

    fun relay(baseUrl: String, room: String): Link = WsLink(baseUrl, room)
}

/** Internet orqali: relay serverga WebSocket. */
class WsLink(baseUrl: String, room: String) : Link {
    override val kind = "Internet"
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val eof = ByteArray(0)
    private val ready = CountDownLatch(1)
    @Volatile private var pcOnline = false
    @Volatile private var failMsg: String? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val ws: WebSocket

    init {
        var base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/ws")) base = base.dropLast(3)
        if (base.startsWith("https://")) base = "wss://" + base.removePrefix("https://")
        if (base.startsWith("http://")) base = "ws://" + base.removePrefix("http://")
        if (!base.startsWith("ws://") && !base.startsWith("wss://")) base = "wss://$base"
        val url = "$base/ws?room=${room.trim()}&role=phone"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (text) {
                    "pc:online" -> { pcOnline = true; ready.countDown() }
                    "pc:offline" -> {
                        if (!pcOnline) { failMsg = "Kompyuter relay serverga ulanmagan (agent ishlayaptimi? xona kodi to'g'rimi?)" }
                        pcOnline = false
                        ready.countDown()
                        queue.put(eof)
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                queue.put(bytes.toByteArray())
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready.countDown(); queue.put(eof)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (failMsg == null) failMsg = "Relay serverga ulanib bo'lmadi: ${t.message}"
                ready.countDown(); queue.put(eof)
            }
        })
        if (!ready.await(15, TimeUnit.SECONDS)) {
            close(); throw IOException("Relay server javob bermadi")
        }
        if (!pcOnline) {
            close(); throw IOException(failMsg ?: "Kompyuter topilmadi")
        }
    }

    override fun send(type: Int, payload: ByteArray) {
        val data = ByteArray(payload.size + 1)
        data[0] = type.toByte()
        System.arraycopy(payload, 0, data, 1, payload.size)
        if (!ws.send(data.toByteString())) throw IOException("Yuborib bo'lmadi")
    }

    override fun recv(): Pair<Int, ByteArray> {
        val d = queue.take()
        if (d === eof || d.isEmpty()) throw IOException(failMsg ?: "Aloqa uzildi")
        return Pair(d[0].toInt() and 0xFF, d.copyOfRange(1, d.size))
    }

    override fun close() {
        try { ws.close(1000, null) } catch (_: Exception) {}
        queue.put(eof)
        client.dispatcher.executorService.shutdown()
    }
}
