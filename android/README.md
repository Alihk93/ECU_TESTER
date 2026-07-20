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

## Device compatibility — one universal APK

**The same APK runs on Android TV, phone, and tablet — no per-form-factor build.**
This is deliberate, not incidental:

- Both `touchscreen` and `leanback` are declared `uses-feature … required="false"`
  in the manifest — the first admits TVs (no touchscreen), the second admits
  phones/tablets (no TV hardware). That pairing is what lets one APK install on all.
- Three launcher intents: `LAUNCHER` (phone/tablet drawer), `LEANBACK_LAUNCHER`
  (TV Apps row), `HOME`.
- No native code (pure Kotlin + OkHttp) → no ABI split; runs on 32-bit `armeabi-v7a`
  and 64-bit alike.
- `StageLayout` scales the fixed 1920×1080 cluster by `min(w/1920, h/1080)` and
  centers it, so it letterboxes to any screen size/aspect without a redesign.
- `minSdk 24` (Android 7) covers all three.

Proven on a Huawei P30 **phone** (dev) and a G08 4K **Android TV** (2026-07-19).

Caveats (cosmetic, not blockers — do **not** warrant a separate build):

- **Landscape-locked** (`screenOrientation="landscape"`): a phone/tablet held in
  portrait shows the landscape cluster rotated to fill — correct for a dashboard,
  but it is not a portrait phone UI.
- **Letterbox bars** on non-16:9 screens (tall phones get side bars); the cluster
  itself stays pixel-correct.
- The app claims `CATEGORY_HOME` and auto-starts on boot (appliance behavior). On a
  dedicated TV that is the point; on a **personal** phone/tablet it is mildly
  intrusive (can offer to be the home launcher, starts on boot). Harmless — the
  device-owner lock stays a no-op unless explicitly set. If a "clean" phone/tablet
  demo build is ever wanted, drop the `HOME` intent + `BootReceiver` as a build
  flavor — not a separate codebase.

## Intro splash (cinematic)

`IntroActivity` is the cold-launch entry (app icon / TV Apps row / boot); it plays a
~6 s cinematic AL-AYED intro, then **ENTER** hands off to `MainActivity` (the dashboard).
`CATEGORY_HOME` stays on MainActivity, so a Home-press returns to the dashboard, not a
replayed intro. Native Canvas port of a Claude Design handoff (source under
`../docs/design-refs/ecu-intro/`).

- `ui/IntroView.kt` — 1920×1080 stage, 5 scenes (Power On → Cars → Emblem → Marques →
  Showtime) revealing feathered-ellipse crops of one composite
  (`res/drawable-nodpi/intro_bg.webp`, a 183 KB re-encode of the 5.8 MB source). A global
  `SPEED` (1.6×) time-scale sets the ~6 s runtime; tap/DPAD-center skips to the end.
- `ui/IntroAudio.kt` — procedural sound bed (ambient/whoosh/thump/blips/impact/ripple/
  chime/click) rendered to PCM and played via AudioTrack, cued per scene.
- End state: TV-remote + touch nav; **ENTER** → dashboard, **SETTING** → change-password
  modal (SharedPreferences, default `00000000`).

> Deliberately a **separate top-level activity**, not launched from
> `MainActivity.onCreate` — doing that tangled the lifecycle and starved the intro's
> layout/draw. Plays on every cold start (skippable); change if once-only is wanted.

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
