#!/usr/bin/env python3
"""
PC Remote - kompyuter tomoni (agent).

Telefon quyidagi yo'llar bilan ulanadi (hammasi bir vaqtda ishlaydi):
  1. Wi-Fi / LAN   - TCP port 5050 (+ UDP 5051 orqali avtomatik topish)
  2. USB kabel     - adb reverse tcp:5050 tcp:5050  (telefon 127.0.0.1 ga ulanadi)
  3. Bluetooth     - RFCOMM kanal 4 (Windows / Linux)
  4. Internet      - relay server orqali (--relay wss://... yoki config faylda)

Ishga tushirish:  python agent.py
"""
import argparse
import hmac
import io
import json
import os
import platform
import queue
import secrets
import socket
import struct
import sys
import threading
import time

VERSION = 1
TCP_PORT = 5050
DISCOVERY_PORT = 5051
BT_CHANNEL = 4
T_JSON, T_JPEG = 1, 2

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "pcremote.json")

# ---------------------------------------------------------------- deps
try:
    from pynput.mouse import Button, Controller as MouseController
    from pynput.keyboard import Key, KeyCode, Controller as KeyboardController
except Exception as e:  # pragma: no cover
    print("pynput topilmadi yoki ishlamadi:", e)
    print("O'rnating:  pip install -r requirements.txt")
    sys.exit(1)

try:
    import mss
    from PIL import Image, ImageDraw
    HAVE_SCREEN = True
    _MSS = getattr(mss, "MSS", None) or mss.mss  # yangi versiyalarda MSS
except Exception as e:
    print("Ogohlantirish: ekran uzatish o'chiq (mss/Pillow yo'q):", e)
    HAVE_SCREEN = False

if platform.system() == "Windows":
    try:  # haqiqiy piksellar bilan ishlash (DPI scaling muammosi uchun)
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass

mouse = MouseController()
kbd = KeyboardController()
input_lock = threading.Lock()


# ---------------------------------------------------------------- config
def load_config(args):
    cfg = {}
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            cfg = {}
    changed = False
    if not cfg.get("pin"):
        cfg["pin"] = "".join(secrets.choice("0123456789") for _ in range(6))
        changed = True
    if not cfg.get("room"):
        alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        cfg["room"] = "".join(secrets.choice(alphabet) for _ in range(8))
        changed = True
    cfg.setdefault("name", socket.gethostname())
    cfg.setdefault("relay", "")
    cfg.setdefault("port", TCP_PORT)
    cfg.setdefault("bluetooth", True)
    for k in ("pin", "room", "name", "relay", "port"):
        v = getattr(args, k, None)
        if v:
            cfg[k] = v
            changed = True
    if args.no_bt:
        cfg["bluetooth"] = False
    if changed:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
    return cfg


# ---------------------------------------------------------------- input
SPECIAL = {
    "esc": Key.esc, "tab": Key.tab, "caps_lock": Key.caps_lock,
    "shift": Key.shift, "ctrl": Key.ctrl, "alt": Key.alt, "cmd": Key.cmd,
    "space": Key.space, "enter": Key.enter, "backspace": Key.backspace,
    "delete": Key.delete, "home": Key.home, "end": Key.end,
    "page_up": Key.page_up, "page_down": Key.page_down,
    "up": Key.up, "down": Key.down, "left": Key.left, "right": Key.right,
}
for _name in ("insert", "print_screen", "menu"):
    if hasattr(Key, _name):
        SPECIAL[_name] = getattr(Key, _name)
for _i in range(1, 13):
    SPECIAL["f%d" % _i] = getattr(Key, "f%d" % _i)

MEDIA = {}
for _k, _attr in (("play", "media_play_pause"), ("next", "media_next"),
                  ("prev", "media_previous"), ("volup", "media_volume_up"),
                  ("voldown", "media_volume_down"), ("mute", "media_volume_mute")):
    if hasattr(Key, _attr):
        MEDIA[_k] = getattr(Key, _attr)

BUTTONS = {"left": Button.left, "right": Button.right, "middle": Button.middle}
MODS = {"shift": Key.shift, "ctrl": Key.ctrl, "alt": Key.alt, "cmd": Key.cmd}


def resolve_key(k):
    if k in SPECIAL:
        return SPECIAL[k]
    if isinstance(k, str) and len(k) == 1:
        return KeyCode.from_char(k)
    return None


def tap_key(k, mods):
    key = resolve_key(k)
    if key is None:
        return
    held = [MODS[m] for m in mods if m in MODS]
    try:
        for m in held:
            kbd.press(m)
        kbd.press(key)
        kbd.release(key)
    finally:
        for m in reversed(held):
            kbd.release(m)


def screen_rect():
    """Asosiy monitor: (left, top, width, height)."""
    if HAVE_SCREEN:
        with _MSS() as s:
            m = s.monitors[1]
            return m["left"], m["top"], m["width"], m["height"]
    return 0, 0, 1920, 1080


def handle_command(msg):
    t = msg.get("t")
    with input_lock:
        if t == "move":
            mouse.move(int(msg.get("dx", 0)), int(msg.get("dy", 0)))
        elif t == "abs":
            left, top, w, h = screen_rect()
            x = min(max(float(msg.get("x", 0)), 0.0), 1.0)
            y = min(max(float(msg.get("y", 0)), 0.0), 1.0)
            mouse.position = (left + int(x * (w - 1)), top + int(y * (h - 1)))
        elif t == "btn":
            b = BUTTONS.get(msg.get("b", "left"), Button.left)
            a = msg.get("a", "click")
            if a == "down":
                mouse.press(b)
            elif a == "up":
                mouse.release(b)
            elif a == "dbl":
                mouse.click(b, 2)
            else:
                mouse.click(b, 1)
        elif t == "scroll":
            mouse.scroll(int(msg.get("dx", 0)), int(msg.get("dy", 0)))
        elif t == "key":
            tap_key(msg.get("k", ""), msg.get("mods", []) or [])
        elif t == "text":
            s = str(msg.get("s", ""))[:2000]
            if s:
                kbd.type(s)
        elif t == "media":
            key = MEDIA.get(msg.get("k"))
            if key is not None:
                kbd.press(key)
                kbd.release(key)


# ---------------------------------------------------------------- links
class StreamLink:
    """TCP va Bluetooth uchun: [turi 1 bayt][uzunlik 4 bayt][ma'lumot]."""

    def __init__(self, sock, label):
        self.sock = sock
        self.label = label
        self.wlock = threading.Lock()

    def _read_exact(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("ulanish yopildi")
            buf.extend(chunk)
        return bytes(buf)

    def recv(self):
        head = self._read_exact(5)
        typ, ln = head[0], struct.unpack(">I", head[1:])[0]
        if ln > 8 * 1024 * 1024:
            raise ConnectionError("juda katta paket")
        return typ, self._read_exact(ln)

    def send(self, typ, payload):
        with self.wlock:
            self.sock.sendall(bytes([typ]) + struct.pack(">I", len(payload)) + payload)

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


class QueueLink:
    """Relay (internet) uchun: kiruvchi xabarlar navbatdan, chiquvchilar WebSocket ga."""

    def __init__(self, send_fn, label):
        self.q = queue.Queue()
        self.send_fn = send_fn
        self.label = label
        self.closed = False

    def recv(self):
        item = self.q.get()
        if item is None:
            raise ConnectionError("telefon uzildi")
        return item[0], item[1:]

    def send(self, typ, payload):
        if self.closed:
            raise ConnectionError("yopilgan")
        self.send_fn(bytes([typ]) + payload)

    def close(self):
        if not self.closed:
            self.closed = True
            self.q.put(None)


# ---------------------------------------------------------------- session
failed_attempts = {}  # manba -> (soni, oxirgi vaqt)
fail_lock = threading.Lock()


class Session:
    def __init__(self, link, cfg, source):
        self.link = link
        self.cfg = cfg
        self.source = source
        self.authed = False
        self.alive = True
        self.screen_on = False
        self.fps = 10
        self.quality = 50
        self.width = 960
        self.ack = threading.Event()
        self.ack.set()
        self.screen_thread = None

    def send_json(self, obj):
        self.link.send(T_JSON, json.dumps(obj).encode("utf-8"))

    def run(self):
        print("[+] Ulanish:", self.link.label)
        try:
            while self.alive:
                typ, data = self.link.recv()
                if typ != T_JSON:
                    continue
                try:
                    msg = json.loads(data.decode("utf-8"))
                except Exception:
                    continue
                if not self.authed:
                    self.handle_hello(msg)
                    continue
                self.dispatch(msg)
        except Exception as e:
            print("[-] Uzildi:", self.link.label, "-", e)
        finally:
            self.alive = False
            self.ack.set()
            self.link.close()

    def handle_hello(self, msg):
        if msg.get("t") != "hello":
            return
        with fail_lock:
            cnt, last = failed_attempts.get(self.source, (0, 0))
            if cnt >= 5 and time.time() - last < 60:
                self.send_json({"t": "err", "msg": "Juda ko'p xato urinish. 1 daqiqa kuting."})
                raise ConnectionError("bloklangan")
        if hmac.compare_digest(str(msg.get("pin", "")), str(self.cfg["pin"])):
            self.authed = True
            with fail_lock:
                failed_attempts.pop(self.source, None)
            _, _, w, h = screen_rect()
            self.send_json({"t": "ok", "name": self.cfg["name"], "w": w, "h": h,
                            "screen": HAVE_SCREEN, "ver": VERSION})
            print("    PIN to'g'ri, boshqaruv ochildi:", self.link.label)
        else:
            with fail_lock:
                cnt, _ = failed_attempts.get(self.source, (0, 0))
                failed_attempts[self.source] = (cnt + 1, time.time())
            self.send_json({"t": "err", "msg": "PIN noto'g'ri"})
            print("    Noto'g'ri PIN:", self.link.label)

    def dispatch(self, msg):
        t = msg.get("t")
        if t == "ack":
            self.ack.set()
        elif t == "ping":
            self.send_json({"t": "pong"})
        elif t == "screen":
            self.fps = max(1, min(int(msg.get("fps", self.fps)), 30))
            self.quality = max(10, min(int(msg.get("q", self.quality)), 90))
            self.width = max(240, min(int(msg.get("w", self.width)), 1920))
            on = bool(msg.get("on", True)) and HAVE_SCREEN
            self.screen_on = on
            if on and (self.screen_thread is None or not self.screen_thread.is_alive()):
                self.ack.set()
                self.screen_thread = threading.Thread(target=self.screen_loop, daemon=True)
                self.screen_thread.start()
        else:
            try:
                handle_command(msg)
            except Exception as e:
                print("    Buyruq xatosi:", t, e)

    def screen_loop(self):
        with _MSS() as sct:
            mon = sct.monitors[1]
            while self.alive and self.screen_on:
                start = time.time()
                # telefon oldingi kadrni olmaguncha kutamiz (sekin tarmoqda navbat to'lmasin)
                if not self.ack.wait(timeout=2.0):
                    self.ack.set()
                    continue
                if not (self.alive and self.screen_on):
                    break
                self.ack.clear()
                try:
                    shot = sct.grab(mon)
                    img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
                    try:  # kursorni chizish (mss kursorni ko'rsatmaydi)
                        cx, cy = mouse.position
                        cx -= mon["left"]
                        cy -= mon["top"]
                        d = ImageDraw.Draw(img)
                        r = max(6, mon["width"] // 220)
                        d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(255, 255, 255), width=3)
                        d.ellipse((cx - r + 3, cy - r + 3, cx + r - 3, cy + r - 3), fill=(230, 40, 40))
                    except Exception:
                        pass
                    if img.width > self.width:
                        h = int(img.height * self.width / img.width)
                        img = img.resize((self.width, h), Image.BILINEAR)
                    buf = io.BytesIO()
                    img.save(buf, "JPEG", quality=self.quality)
                    self.link.send(T_JPEG, buf.getvalue())
                except Exception as e:
                    print("    Ekran xatosi:", e)
                    self.alive = False
                    break
                wait = 1.0 / self.fps - (time.time() - start)
                if wait > 0:
                    time.sleep(wait)


def start_session(link, cfg, source):
    threading.Thread(target=Session(link, cfg, source).run, daemon=True).start()


# ---------------------------------------------------------------- servers
def tcp_server(cfg):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", int(cfg["port"])))
    srv.listen(5)
    while True:
        c, addr = srv.accept()
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        start_session(StreamLink(c, "Wi-Fi/USB %s" % addr[0]), cfg, addr[0])


def discovery_server(cfg):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", DISCOVERY_PORT))
    while True:
        data, addr = s.recvfrom(256)
        if data.strip() == b"PCREMOTE?":
            reply = json.dumps({"name": cfg["name"], "port": int(cfg["port"])})
            s.sendto(b"PCREMOTE " + reply.encode("utf-8"), addr)


def bluetooth_server(cfg):
    if not hasattr(socket, "AF_BLUETOOTH"):
        print("Bluetooth: bu Python/OS da qo'llab-quvvatlanmaydi (Windows yoki Linux kerak).")
        return
    try:
        srv = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        addr_any = getattr(socket, "BDADDR_ANY", "00:00:00:00:00:00")
        srv.bind((addr_any, BT_CHANNEL))
        srv.listen(1)
    except Exception as e:
        print("Bluetooth: ishga tushmadi (%s). Wi-Fi/USB/Internet ishlayveradi." % e)
        return
    if platform.system() == "Linux":
        os.system("sdptool add --channel=%d SP >/dev/null 2>&1" % BT_CHANNEL)
    print("Bluetooth: kanal %d da kutyapti (telefonni avval kompyuter bilan juftlang)." % BT_CHANNEL)
    while True:
        c, addr = srv.accept()
        start_session(StreamLink(c, "Bluetooth %s" % (addr[0] if addr else "?")), cfg, "bt")


def relay_client(cfg):
    url = cfg.get("relay", "").strip()
    if not url:
        return
    try:
        import websocket  # websocket-client
    except ImportError:
        print("Internet: 'websocket-client' o'rnatilmagan (pip install websocket-client).")
        return
    base = url.rstrip("/")
    if base.endswith("/ws"):
        base = base[:-3]
    full = "%s/ws?room=%s&role=pc" % (base, cfg["room"])
    while True:
        ws = None
        current = None
        try:
            ws = websocket.create_connection(full, timeout=30)
            ws.settimeout(None)
            wlock = threading.Lock()

            def send(data, _ws=ws):
                with wlock:
                    _ws.send_binary(data)

            print("Internet: relay serverga ulandi. Xona kodi:", cfg["room"])
            while True:
                op, data = ws.recv_data()
                if op == websocket.ABNF.OPCODE_TEXT:
                    text = data.decode("utf-8", "ignore")
                    if text == "peer:on":
                        if current:
                            current.close()
                        current = QueueLink(send, "Internet (relay)")
                        start_session(current, cfg, "relay")
                    elif text == "peer:off" and current:
                        current.close()
                        current = None
                elif op == websocket.ABNF.OPCODE_BINARY and current and data:
                    current.q.put(data)
                elif op == websocket.ABNF.OPCODE_CLOSE:
                    break
        except Exception as e:
            print("Internet: relay bilan aloqa yo'q (%s). 5 soniyadan keyin qayta urinadi." % e)
        finally:
            if current:
                current.close()
            if ws:
                try:
                    ws.close()
                except Exception:
                    pass
        time.sleep(5)


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main():
    ap = argparse.ArgumentParser(description="PC Remote agent")
    ap.add_argument("--pin", help="PIN kod (standart: avtomatik yaratiladi)")
    ap.add_argument("--port", type=int, help="TCP port (standart 5050)")
    ap.add_argument("--name", help="Kompyuter nomi")
    ap.add_argument("--relay", help="Relay server manzili, masalan wss://myrelay.onrender.com")
    ap.add_argument("--room", help="Internet xona kodi")
    ap.add_argument("--no-bt", action="store_true", help="Bluetooth ni o'chirish")
    args = ap.parse_args()
    cfg = load_config(args)

    threading.Thread(target=tcp_server, args=(cfg,), daemon=True).start()
    threading.Thread(target=discovery_server, args=(cfg,), daemon=True).start()
    if cfg.get("bluetooth", True):
        threading.Thread(target=bluetooth_server, args=(cfg,), daemon=True).start()
    if cfg.get("relay"):
        threading.Thread(target=relay_client, args=(cfg,), daemon=True).start()

    time.sleep(0.3)
    print("=" * 52)
    print(" PC Remote ishlayapti -", cfg["name"])
    print("=" * 52)
    print(" PIN kod:      ", cfg["pin"])
    print(" Wi-Fi/LAN IP: ", ", ".join(local_ips()) or "topilmadi", " port", cfg["port"])
    print(" USB:           adb reverse tcp:%s tcp:%s" % (cfg["port"], cfg["port"]))
    if cfg.get("relay"):
        print(" Internet:      ", cfg["relay"], " xona:", cfg["room"])
    else:
        print(" Internet:      o'chiq (--relay wss://... bilan yoqing)")
    print(" To'xtatish:    Ctrl+C")
    print("=" * 52)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("\nTo'xtatildi.")


if __name__ == "__main__":
    main()
