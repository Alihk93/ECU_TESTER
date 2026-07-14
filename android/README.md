# ECU_TESTER — Android TV app (display client)

Native Android TV replacement for the browser dashboard (`../web/`). The FPS
ceiling on the bench TVs is a renderer problem (TV Chromium software-composites);
Android TV's GPU compositor removes it. See `../docs/ANDROID_MIGRATION.md` for the
full plan and confirmed decisions.

**Firmware and the wire protocol are unchanged** — this app is just another
WebSocket client on `/ws`. `Protocol.kt` is the fourth mirror of the contract
(`docs/PROTOCOL.md` ⇄ `protocol.h` ⇄ `web/js/protocol.js` ⇄ `Protocol.kt`); change
all four together.

Client-provided visual references (e.g. the coil spark bolt shape/color) live in
`../docs/design-refs/` — not used at runtime, kept for provenance.

## Status — M1 vertical slice

One screen: connect to `/ws`, decode `rpm`, drive a gliding RPM needle at 60 fps,
with a corner FPS/link meter. Proves the whole path (OkHttp + Kotlin decode + GPU
needle) before rebuilding the full cluster.

```
app/src/main/
├── java/com/alayed/ecutester/
│   ├── Protocol.kt      binary decoder — the contract (port of protocol.js)
│   ├── EcuSocket.kt     OkHttp WebSocket + reconnect (port of websocket.js)
│   ├── LiveMapper.kt    frames -> UI, coalesced per painted frame (port of live.js)
│   ├── MainActivity.kt  immersive fullscreen host + FPS meter
│   └── ui/RpmDialView.kt  custom-View dial, ValueAnimator needle glide
└── res/  layout, colors (marine skin), theme
```

## Build (pinned toolchain)

minSdk 24 · compileSdk 34 · AGP 8.5.2 · Gradle 8.7 · Kotlin 1.9.24 · **JDK 17**.
A JDK/AGP/Gradle mismatch is the usual first-build failure.

```sh
cd android
./gradlew assembleDebug         # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Run against the sim (no hardware)

`WS_URL` in `MainActivity.kt` defaults to the device (`ws://10.10.10.10/ws`).

- **Android TV emulator + `tools/sim_server.py`:** point it at `ws://10.0.2.2:8090/ws`
  (the emulator's host alias).
- **Real Android TV box + laptop sim:** use the laptop's LAN IP and start the sim
  bound to all interfaces (`python3 tools/sim_server.py`, then bind `0.0.0.0`).
- **Real Android TV box + device:** join Wi-Fi `ECU_TESTER` / `00000000`, keep the
  default `10.10.10.10`.

## Kiosk / autostart (appliance mode)

The app is built to run unattended on a dedicated TV (D9, migration doc §6.1). Three
layers, each independent:

1. **Autostart on boot** — `BootReceiver` launches the dashboard on `BOOT_COMPLETED`
   (needs no special setup; the `RECEIVE_BOOT_COMPLETED` permission is declared).
2. **Stay up** — immersive-sticky fullscreen + `FLAG_KEEP_SCREEN_ON`, and a crash
   handler that relaunches the activity ~1.5 s after any uncaught exception.
3. **Full lock (optional, no exit)** — screen-pinning via lock-task, enabled ONLY
   when the app is **device owner**. Set it once on a factory-fresh / account-free
   device:
   ```sh
   adb shell dpm set-device-owner com.alayed.ecutester/.EcuDeviceAdminReceiver
   ```
   Then `MainActivity.setupKiosk()` allowlists itself and calls `startLockTask()`.
   Undo with `adb shell dpm remove-active-admin com.alayed.ecutester/.EcuDeviceAdminReceiver`.
   Without device owner this is a no-op, so dev/phone builds are unaffected.

Alternatively (no device owner), a dedicated box can just set ECU_TESTER as its
**default launcher** (the activity also declares `CATEGORY_HOME`): it then owns the
screen and the Home button returns to it.

## Updates

Service-visit sideload via `adb install -r` (decision C in the migration doc). No
in-app updater; the ESP32's LittleFS is untouched.
