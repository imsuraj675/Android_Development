# Sender

A local-network file and text sharing app for Android. Run the app, open the URL on any browser on the same network, and start transferring — no accounts, no internet, no third-party servers.

<!-- ![App screenshot](.github/images/screenshot_main.png) -->

---

## How it works

Sender runs a lightweight HTTP + WebSocket server directly on your phone (port 8080). Other devices connect through a browser — no app needed on the receiving end.

```
Phone (Sender app)  ←── WiFi / Hotspot / USB ──→  Any browser
```

---

## Features

- **Send & receive files** — single files, multiple files, or auto-bundled as ZIP
- **Send & receive text** — plain messages or saved as `.txt`
- **No internet required** — works on WiFi, hotspot, or USB tethering
- **QR code** — tap the QR icon to scan and connect instantly
- **mDNS** — connect via `phone.local:8080` on supported networks
- **Transfer progress** — live progress bar in-app and in the notification
- **Cancel transfers** — cancel mid-transfer from the app or the browser
- **Device pairing** — trust-based pairing with optional rename and block
- **Share intent** — share files or text from any app directly into Sender

---

<!-- ## Screenshots

| Home | Browser receiver | Transfer in progress |
|------|-----------------|----------------------|
| ![](.github/images/screenshot_home.png) | ![](.github/images/screenshot_browser.png) | ![](.github/images/screenshot_transfer.png) |

--- -->

## Requirements

- Android 14+ (API 34)
- Both devices on the same local network (WiFi, hotspot, or USB tethering)

---

## Build & install

```bash
# Clone
git clone https://github.com/<your-username>/Sender.git
cd Sender

# Debug APK
./gradlew assembleDebug

# Install directly to a connected device
./gradlew installDebug
```

> **Signed release build** — copy `keystore.properties.example` to `keystore.properties`, fill in your keystore details, then run `./gradlew assembleRelease`.

---

## Usage

1. Open the **Sender** app and enable the **Discoverable** toggle.
2. On another device, open a browser and navigate to the URL shown (e.g. `192.168.x.x:8080`), or scan the QR code.
3. The browser page lets you upload files/text to the phone, and download anything the phone shares.
4. To send from the phone, type a message or pick a file in the app and tap **Send**.

## Features:
1. You can share text/files via the share option from other apps.
2. You can send data to multiple devices at once.
3. You can use an alias for primary url i.e, `phone.local:8080` and this will redirect to current primary url, so instead of typing whole url, this can come in handy.
4. You can rename the connected devices for easier identification

> Pull down from the top of the main screen to refresh network interfaces and clear the received-items log.

---

## Contributing

Contributions are welcome. Please keep them focused — one fix or feature per pull request.

1. Fork the repo and create a branch from `main`.
2. Make your change; ensure the debug build passes (`./gradlew assembleDebug`).
3. Open a pull request with a short description of what and why.

**Found a bug?** Open an issue with steps to reproduce and your Android version.

---

## Tech stack

| Layer | Library |
|-------|---------|
| UI | Jetpack Compose + Material3 |
| Server | Ktor CIO (embedded) |
| Real-time | Ktor WebSockets |
| mDNS | JmDNS |
| QR codes | ZXing Core |

---

## License

MIT — see [LICENSE](LICENSE).
