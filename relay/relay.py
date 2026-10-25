#!/usr/bin/env python3
"""
PC Remote - internet relay server.

Kompyuter va telefon ikkalasi ham shu serverga WebSocket orqali ulanadi
(bir xil xona kodi bilan), server esa ma'lumotni ular orasida uzatadi.
Shu tufayli kompyuter va telefon turli tarmoqlarda bo'lsa ham ishlaydi.

  Kompyuter: wss://SERVER/ws?room=XONA&role=pc
  Telefon:   wss://SERVER/ws?room=XONA&role=phone

Ishga tushirish:  python relay.py      (PORT muhit o'zgaruvchisi, standart 8080)
Render/Railway/VPS ga joylashtirsa bo'ladi.
"""
import asyncio
import os
import re

from aiohttp import web, WSMsgType

ROOM_RE = re.compile(r"^[A-Za-z0-9_-]{4,64}$")
rooms = {}  # room -> {"pc": ws, "phone": ws}


async def notify(ws, text):
    if ws is not None and not ws.closed:
        try:
            await ws.send_str(text)
        except Exception:
            pass


async def ws_handler(request):
    room = request.query.get("room", "")
    role = request.query.get("role", "")
    if not ROOM_RE.match(room) or role not in ("pc", "phone"):
        return web.Response(status=400, text="room/role noto'g'ri")

    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=16 * 1024 * 1024)
    await ws.prepare(request)

    slot = rooms.setdefault(room, {})
    old = slot.get(role)
    if old is not None and not old.closed:
        await old.close(message=b"replaced")
    slot[role] = ws
    other_role = "phone" if role == "pc" else "pc"

    # ikkalasi ham bor bo'lsa, kompyuterga yangi telefon kelganini bildiramiz
    if role == "phone":
        if slot.get("pc") is not None and not slot["pc"].closed:
            await notify(slot["pc"], "peer:on")
            await notify(ws, "pc:online")
        else:
            await notify(ws, "pc:offline")
    elif slot.get("phone") is not None and not slot["phone"].closed:
        await notify(ws, "peer:on")
        await notify(slot["phone"], "pc:online")

    try:
        async for msg in ws:
            if msg.type == WSMsgType.BINARY:
                peer = slot.get(other_role)
                if peer is not None and not peer.closed:
                    try:
                        await peer.send_bytes(msg.data)
                    except Exception:
                        pass
            elif msg.type == WSMsgType.ERROR:
                break
    finally:
        if slot.get(role) is ws:
            slot.pop(role, None)
            peer = slot.get(other_role)
            if role == "phone":
                await notify(peer, "peer:off")
            else:
                await notify(peer, "pc:offline")
        if not slot:
            rooms.pop(room, None)
    return ws


async def health(request):
    return web.Response(text="PC Remote relay ishlayapti. Xonalar: %d\n" % len(rooms))


def main():
    app = web.Application()
    app.router.add_get("/", health)
    app.router.add_get("/ws", ws_handler)
    port = int(os.environ.get("PORT", "8080"))
    print("Relay port %d da ishlayapti" % port)
    web.run_app(app, host="0.0.0.0", port=port, print=None)


if __name__ == "__main__":
    main()
