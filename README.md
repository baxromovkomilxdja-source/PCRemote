# PC Remote

Telefondan (Android) kompyuterni boshqarish: **ekranni ko'rish**, **to'liq kompyuter klaviaturasi**, **touchpad**, musiqa/kino tugmalari.

```
PCRemote/
├── android/   ← Android ilova (Kotlin)
├── pc/        ← Kompyuterda ishlaydigan dastur (Python)
├── relay/     ← Internet orqali ulanish uchun server (ixtiyoriy)
└── .github/workflows/build-apk.yml  ← APK ni avtomatik yig'adi
```

---

## 1. Kompyuterga o'rnatish

1. Python 3.9+ o'rnating: https://www.python.org/downloads/ (Windowsda **"Add Python to PATH"** belgisini qo'ying).
2. `pc` papkasini kompyuterga ko'chiring.
3. Ishga tushiring:
   - **Windows:** `start_windows.bat` ni ikki marta bosing.
   - **Linux / Mac:** `sh start_linux_mac.sh`
4. Oynada **PIN kod**, **IP manzil** va (yoqilgan bo'lsa) **xona kodi** chiqadi. PIN `pcremote.json` faylida saqlanadi va o'zgarmaydi.

> Windows Firewall so'rasa — **"Ruxsat berish" (Allow)** ni bosing (Private tarmoq uchun). Aks holda telefon kompyuterni topa olmaydi.
>
> Mac: Tizim sozlamalari → Maxfiylik → **Accessibility** va **Screen Recording** da Terminal/Python ga ruxsat bering.

---

## 2. APK ni olish (telefonga o'rnatish)

### A yo'l — GitHub (kompyuterga hech narsa o'rnatmasdan)
1. https://github.com da bepul hisob oching, yangi repository yarating.
2. Butun `PCRemote` papkasini shu repositoryga yuklang (**Add file → Upload files**, `.github` papkasi ham bo'lsin).
3. **Actions** bo'limiga kiring — "Build APK" avtomatik boshlanadi (~5 daqiqa).
4. Tugagach, ichiga kiring → pastda **Artifacts → PCRemote-apk** ni yuklab oling, zip ichida `app-debug.apk`.
5. APK ni telefonga o'tkazib o'rnating ("Noma'lum manbalardan o'rnatish"ga ruxsat bering).

### B yo'l — Android Studio
1. Android Studio ni o'rnating, **Open** → `android` papkasini tanlang.
2. Telefonni USB bilan ulang (USB debugging yoqilgan) → **Run ▶**.
   Yoki **Build → Build APK(s)**.

---

## 3. Ulanish yo'llari

| Yo'l | Qachon | Ekran tezligi |
|---|---|---|
| **Wi-Fi / LAN** | Telefon va kompyuter bitta routerda. Kompyuter **kabel (LAN)** bilan ulangan bo'lsa ham ishlaydi — routerning o'zi bog'laydi | Tez |
| **USB** | Wi-Fi umuman yo'q bo'lsa, eng tez va barqaror | Eng tez |
| **Internet** | Telefon va kompyuter turli joyda (mobil internet ham bo'ladi) | Internetga bog'liq |
| **Bluetooth** | Tarmoq yo'q, kabel yo'q | Sekin (past sifat) |

### Wi-Fi / LAN
Ilovada **Wi-Fi** → **Kompyuterni qidirish** → topilganini tanlang → PIN → **Ulanish**.
Topilmasa IP ni qo'lda kiriting (kompyuter oynasida ko'rsatilgan).

> "Kompyuterda Wi-Fi yo'q, lekin internet bor" — demak u kabel orqali routerga ulangan. Telefonni shu routerning Wi-Fi'iga ulasangiz, **Wi-Fi rejimi ishlaydi**.

### USB
1. Telefonda: Sozlamalar → Telefon haqida → "Build number" ni 7 marta bosing → Dasturchi sozlamalari → **USB debugging** ni yoqing.
2. Kompyuterga [adb (platform-tools)](https://developer.android.com/tools/releases/platform-tools) ni yuklab oling.
3. Kabel bilan ulab, buyruq bering: `adb reverse tcp:5050 tcp:5050`
4. Ilovada **USB** → PIN → **Ulanish**.

### Internet (relay server orqali)
Ikki variant:

**Variant 1 — Tailscale (eng oson, server kerak emas):**
Kompyuter va telefonga [Tailscale](https://tailscale.com) ni o'rnating, bitta hisob bilan kiring. Keyin ilovada **Wi-Fi** rejimini tanlab, kompyuterning Tailscale IP manzilini (100.x.x.x) kiriting. Hamma joydan ishlaydi.

**Variant 2 — O'z relay serveringiz:**
1. `relay` papkasini bepul hostingga joylashtiring, masalan [Render.com](https://render.com):
   New → Web Service → repository → Root directory: `relay` → Build: `pip install -r requirements.txt` → Start: `python relay.py`.
   (Yoki istalgan VPS da: `pip install aiohttp && python relay.py`, yoki Dockerfile bilan.)
2. Kompyuterda: `python agent.py --relay wss://SIZNING-RELAY.onrender.com` (bir marta yozsangiz, keyin eslab qoladi).
3. Kompyuter oynasidagi **xona kodi**ni ilovada **Internet** bo'limiga kiriting.

> Render bepul tarifida server 15 daqiqa ishlatilmasa "uxlaydi", birinchi ulanish ~30 soniya kutishi mumkin.

### Bluetooth
1. Kompyuter va telefonni Bluetooth sozlamalarida **juftlang (pair)**.
2. Ilovada **Bluetooth** → kompyuterni tanlang → PIN → **Ulanish**.
- Windows va Linuxda ishlaydi (Mac da yo'q). Ba'zi kompyuter/telefon juftliklarida ishlamasligi mumkin — unda USB yoki Wi-Fi dan foydalaning.
- Linuxda `bluez` kerak; xato bo'lsa `sudo python3 agent.py` bilan sinab ko'ring.

---

## 4. Boshqaruv

**Ekran** (yuqorida): kompyuter ekrani jonli ko'rinadi. Ekranga bosish — o'sha joyga klik; ikki marta bosish — ikki marta klik; uzoq bosish — o'ng klik.
"👁 Ekran" tugmasi ekranni o'chiradi (trafik tejaladi), **Past / O'rta / Yuqori** — sifat.

**Touchpad:**
- 1 barmoq siljitish → kursor
- 1 barmoq bosish → chap klik
- 2 barmoq bosish → o'ng klik, 3 barmoq → o'rta klik
- 2 barmoq siljitish → scroll
- tez ikki marta bosib, ikkinchisida siljitish → sudrash (drag)
- pastdagi Chap / O'rta / O'ng tugmalarni bosib turish ham mumkin

**Klaviatura:** to'liq kompyuter tartibi (Esc, F1–F12, raqamlar, harflar, Shift, Ctrl, Alt, Win, strelkalar, Home/End, Del, PrtSc).
- Shift/Ctrl/Alt/Win: 1 bosish — keyingi tugma uchun (masalan Ctrl → C = nusxa), 2 bosish — qulf, 3 bosish — o'chirish.
- Win ni **uzoq bosish** — Start menyu.
- Tugmani bosib turish — takrorlaydi (Backspace, strelkalar).

**Media:** ⏮ ⏯ ⏭ 🔉 🔊 🔇 — musiqa/kinoni almashtirish va ovoz.
**Aa** — telefon klaviaturasi bilan istalgan tilda matn yozib, kompyuterga yuborish.

---

## 5. Xavfsizlik
- Har bir ulanish PIN bilan himoyalangan; 5 marta xato kiritilsa 1 daqiqaga bloklanadi.
- PIN ni almashtirish: `python agent.py --pin 123456`
- Wi-Fi rejimida ma'lumot shifrlanmaydi — faqat ishonchli (uy) tarmoqda ishlating. Internetda **wss://** (HTTPS) relay yoki Tailscale ishlating.

## 6. Muammolar
| Muammo | Yechim |
|---|---|
| Telefon kompyuterni topmaydi | Firewall da Python ga ruxsat bering; ikkalasi bitta routerdami tekshiring; IP ni qo'lda kiriting |
| Klaviatura harflari boshqacha | Kompyuterda **inglizcha (US)** tartib yoqilgan bo'lsin; boshqa tilda yozish uchun **Aa** dan foydalaning |
| Kursor juda sekin/tez | `TouchpadView.kt` dagi `sensitivity` qiymatini o'zgartiring |
| Ekran qora (Mac) | Screen Recording ruxsatini bering |
| Windowsda kursor noto'g'ri joyga bosadi | Agent avtomatik DPI ni to'g'rilaydi; yana muammo bo'lsa ekran masshtabini 100% qiling |

## Protokol (dasturchilar uchun)
Har bir paket: `[turi: 1 bayt][uzunlik: 4 bayt][ma'lumot]` (TCP/Bluetooth), relayda esa bitta WebSocket xabar = `[turi][ma'lumot]`.
Turi `1` = JSON buyruq, `2` = JPEG kadr. Buyruqlar: `hello, move, abs, btn, scroll, key, text, media, screen, ack, ping`.
