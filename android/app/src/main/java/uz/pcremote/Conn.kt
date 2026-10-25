package uz.pcremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Kompyuter bilan yagona ulanish (ilova bo'ylab umumiy). */
object Conn {
    interface Listener {
        fun onFrame(bmp: Bitmap) {}
        fun onDisconnected(reason: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var link: Link? = null
    @Volatile var listener: Listener? = null
    @Volatile var pcName: String = ""
        private set
    @Volatile var screenSupported = false
        private set
    val kind: String get() = link?.kind ?: ""
    val connected: Boolean get() = link != null

    // ekran sozlamalari
    @Volatile var screenOn = true
    @Volatile var quality = 1 // 0 past, 1 o'rta, 2 yuqori

    private val outQueue = LinkedBlockingQueue<Any>()
    private val MOVE_TOKEN = Any()
    private val STOP_TOKEN = Any()
    private val moveLock = Any()
    private var pendingDx = 0
    private var pendingDy = 0
    private var moveQueued = false

    /** Bloklovchi: fon oqimidan chaqiring. Muvaffaqiyatsiz bo'lsa xato matnini qaytaradi. */
    fun connect(newLink: Link, pin: String): String? {
        disconnect(null)
        val latch = CountDownLatch(1)
        var error: String? = "Kompyuter javob bermadi"
        var authed = false
        link = newLink
        outQueue.clear()

        val reader = Thread {
            var reason = "Aloqa uzildi"
            try {
                while (true) {
                    val (type, data) = newLink.recv()
                    if (type == T_JSON) {
                        val msg = JSONObject(String(data, Charsets.UTF_8))
                        when (msg.optString("t")) {
                            "ok" -> {
                                pcName = msg.optString("name", "Kompyuter")
                                screenSupported = msg.optBoolean("screen", false)
                                authed = true; error = null; latch.countDown()
                            }
                            "err" -> {
                                error = msg.optString("msg", "Xato"); latch.countDown()
                            }
                        }
                    } else if (type == T_JPEG) {
                        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                        sendJson(JSONObject().put("t", "ack"))
                        if (bmp != null) {
                            val l = listener
                            main.post { l?.onFrame(bmp) }
                        }
                    }
                }
            } catch (e: Exception) {
                reason = e.message ?: reason
            }
            latch.countDown()
            if (link === newLink) {
                link = null
                outQueue.put(STOP_TOKEN)
                newLink.close()
                if (authed) {
                    val l = listener
                    main.post { l?.onDisconnected(reason) }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val writer = Thread {
            try {
                while (true) {
                    val item = outQueue.take()
                    if (item === STOP_TOKEN || link !== newLink) break
                    if (item === MOVE_TOKEN) {
                        var dx: Int; var dy: Int
                        synchronized(moveLock) {
                            dx = pendingDx; dy = pendingDy
                            pendingDx = 0; pendingDy = 0; moveQueued = false
                        }
                        if (dx != 0 || dy != 0) {
                            val o = JSONObject().put("t", "move").put("dx", dx).put("dy", dy)
                            newLink.send(T_JSON, o.toString().toByteArray())
                        }
                    } else if (item is ByteArray) {
                        newLink.send(T_JSON, item)
                    }
                }
            } catch (_: Exception) {
                newLink.close()
            }
        }
        writer.isDaemon = true
        writer.start()

        sendJson(JSONObject().put("t", "hello").put("pin", pin).put("ver", 1))
        latch.await(12, TimeUnit.SECONDS)
        if (error != null) {
            disconnect(null)
            return error
        }
        return null
    }

    fun disconnect(reason: String?) {
        val l = link ?: return
        link = null
        outQueue.put(STOP_TOKEN)
        l.close()
        if (reason != null) {
            val lis = listener
            main.post { lis?.onDisconnected(reason) }
        }
    }

    // ------------------------------------------------------------ buyruqlar
    fun sendJson(o: JSONObject) {
        if (link != null) outQueue.put(o.toString().toByteArray())
    }

    fun move(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        synchronized(moveLock) {
            pendingDx += dx; pendingDy += dy
            if (moveQueued) return
            moveQueued = true
        }
        outQueue.put(MOVE_TOKEN)
    }

    fun abs(x: Float, y: Float) = sendJson(JSONObject().put("t", "abs").put("x", x.toDouble()).put("y", y.toDouble()))
    fun button(b: String, a: String = "click") = sendJson(JSONObject().put("t", "btn").put("b", b).put("a", a))
    fun scroll(dy: Int, dx: Int = 0) = sendJson(JSONObject().put("t", "scroll").put("dy", dy).put("dx", dx))
    fun text(s: String) = sendJson(JSONObject().put("t", "text").put("s", s))
    fun media(k: String) = sendJson(JSONObject().put("t", "media").put("k", k))

    fun key(k: String, mods: Collection<String>) {
        sendJson(JSONObject().put("t", "key").put("k", k).put("mods", JSONArray(mods.toList())))
    }

    fun applyScreen() {
        // Bluetooth sekin: sifatni avtomatik pasaytiramiz
        val bt = kind == "Bluetooth"
        val (w, q, fps) = when (quality) {
            0 -> Triple(if (bt) 400 else 640, if (bt) 25 else 35, if (bt) 4 else 8)
            2 -> Triple(if (bt) 800 else 1600, if (bt) 45 else 70, if (bt) 6 else 15)
            else -> Triple(if (bt) 560 else 1024, if (bt) 35 else 50, if (bt) 5 else 12)
        }
        sendJson(
            JSONObject().put("t", "screen").put("on", screenOn && screenSupported)
                .put("w", w).put("q", q).put("fps", fps)
        )
    }
}
