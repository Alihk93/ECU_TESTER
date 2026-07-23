# ECU_TESTER — Handover Summary (2026-07-22)

> Paste-ready session primer. `CLAUDE.md` / `docs/PROTOCOL.md` / `OPEN_DECISIONS.md`
> remain the in-repo source of truth; this folds in recent work on top of them.

## 1. What it is
Real-time hardware-in-the-loop **car-ECU bench tester** for client **AL-AYED**, by the
firm **TON by Swiss**. An **ESP32-S3-WROOM-1 N16R8** acquires ~50 automotive signals +
crank/cam waveforms, runs its own Wi-Fi SoftAP, and serves telemetry over a **binary
WebSocket** to a display client. **Monitor-only** (reads ECU outputs, never drives the
ECU). Full **simulation mode** runs with no hardware attached.
- **Repo:** `Alihk93/ECU_TESTER`, branch `main`.
- **Two display clients:** **Android app (`android/`, PRIMARY)** and the **web dashboard
  (`web/`, fallback)** — both are WS clients on `/ws`.

## 2. Environment / build
**Firmware** (ESP-IDF v5.5.2, target `esp32s3`, serial `/dev/ttyACM0`):
```
export IDF_TOOLS_PATH=/home/ali/ESP_IDF/Version_5.5.2/IDF_Tool_v5.5.2
export IDF_PYTHON_ENV_PATH=$IDF_TOOLS_PATH/python_env/idf5.5_py3.13_env
source /home/ali/ESP_IDF/Version_5.5.2/IDF_v5.5.2/v5.5.2/esp-idf/export.sh
idf.py -C firmware/ECU_Tester build       # or: -p /dev/ttyACM0 flash
```
**Android** (userspace toolchain, nothing on PATH; Gradle 8.7, minSdk 24, compileSdk 34,
AGP 8.5.2, Kotlin 1.9.24, JDK 17):
```
export JAVA_HOME=~/Android/jdk17; export ANDROID_HOME=~/Android/Sdk; export ANDROID_SDK_ROOT=~/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd android && ./gradlew :app:assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
# adb=~/Android/Sdk/platform-tools/adb ; use the ABSOLUTE apk path with adb install
```
**Dev sim (no hardware):** `python3 tools/sim_server.py [--host 0.0.0.0] [--corrupt]
[--waveforms]` → serves `web/` + real binary protocol on `:8090`. `tools/ws_cmd_test.py`
exercises the command channel.

## 3. Architecture — locked decisions (D0–D9, full log in `OPEN_DECISIONS.md`)
- **D0** clean start (S-ECU patterns only). **D1** AP: SSID `ECU_TESTER`, pw `00000000`
  (WPA2), IP **`10.10.10.10`** (set explicitly). **D2** monitor-only (manual controls only
  in sim). **D3** activity-only coil/injector bits (latched, no dwell/PW). **D4** front-end
  designed in this KiCad project — **NOT started** (blocks real drivers). **D5** CKP =
  VR 60-2 (MAX9926), CMP1/CMP2 = Hall. **D6** waveform = edge-list, **default-off** (scope
  is parametric). **D7** MCP23017 (status) + 74HC165×3 (24 bits) + ADS1115 (analog).
  **D8** (superseded) Chromium kiosk. **D9** display = **native Android app**; firmware/
  protocol unchanged, app is a 4th protocol mirror.

## 4. Protocol contract — FOUR mirrors, change together
`docs/PROTOCOL.md` ⇄ `firmware/.../include/protocol.h` ⇄ `web/js/protocol.js` ⇄
`android/.../Protocol.kt`
- Binary, **little-endian**, envelope 8 B + payload + **CRC16-CCITT-FALSE** (poly 0x1021,
  init 0xFFFF, check 0x29B1).
- **TELEMETRY (0x01, 22 B):** `t_us(u32) rpm(u16) maf(u16) map(u16) iat(i16) ecu_v(u16)
  sensor_v(u16)` (analog = raw mV, UI applies curves), `coils/inj_reg/inj_gdi(u8 latched)`,
  `status(u16 bitfield)`, `iac(u8)`.
- **Status bits:** 0 battery, 1 switch, 2 start, 3 etc, 4 fan1, 5 fan2, 8 fuelPump,
  9 immoP, 10 immoN, 11 mrcP, 12 mrcN.
- **WAVEFORM (0x02):** edge-lists, default-off; clients bail before the CRC pass.
  **COMMAND(0x80)/SUBSCRIBE(0x81)/ACK(0x8F):** implemented — see §5.1 in `docs/PROTOCOL.md`.

## 5. Firmware state (`firmware/ECU_Tester/`) — committed
- Compiles clean for `esp32s3` (~73% app free). Verified on the real ESP32 (SoftAP @
  10.10.10.10, LittleFS mounted, dashboard served, boots clean). Re-flashed + verified this
  session — `App version` shows the HEAD commit.
- **Live data path DONE in SIMULATION:** `acq_task`(core1) generates RPM/analog/status/IAC;
  `net_task`(core0) integrates crank angle (**wrapped to the 720° cam cycle** so the
  accumulator never grows unbounded) → latched firing bits + edge-lists, broadcasts
  TELEMETRY ~30 Hz. `ws_broadcast` = non-blocking per-client sends + **time-based ~2 s
  eviction** (a wall clock, not a frame count, so it holds at any telemetry/waveform rate).
- **COMMAND/SUBSCRIBE — DONE:** `ws_handler` receives B→S frames, validates
  magic/version/len/CRC, dispatches, replies ACK (ACK envelope seq now **increments**
  per §1, matching the sim). COMMAND drives sim overrides (RPM/analog-mV/
  IAC forced; negative value releases to auto; coil/inj/status held toggles); SUBSCRIBE sets
  rate (1–60 Hz) + waveform on/off. Mirrored in `sim_server.py`; verified 6/6 by
  `tools/ws_cmd_test.py`. **No client sends commands yet.**
- Don't silently retune: `sdkconfig.defaults` (`CONFIG_HTTPD_WS_SUPPORT=y`), `partitions.csv`
  (4 MB `storage` littlefs), `main/CMakeLists.txt` (builds the LittleFS image from `web/`),
  the committed pinmap in `board_config.h`.

## 6. Web dashboard (`web/`, FALLBACK)
Flat marine skin (navy `#33516f`, no gradients/shadows/filters). **ES5 classic scripts** +
**flexbox only** (old-TV compat down to ~Chromium 49). Scope is a parametric standing
display. Contract mirror `js/protocol.js`; `js/live.js` maps frames → the `ECU` API in
`app.js`. Unchanged recently.

## 7. Android app (`android/`, PRIMARY)
Kotlin, classic Views/Canvas, OkHttp WS, one universal APK (TV + phone + tablet),
landscape-locked, kiosk plumbing. `IntroActivity` (cold-launch entry) → `MainActivity`
(dashboard). Files: `Protocol.kt`, `EcuSocket.kt`, `LiveMapper.kt`, `DemoDriver.kt`,
`Dashboard.kt`, `ui/{StageLayout,RpmDialView,MiniGaugeView,ScopeView,IntroView,IntroAudio}.kt`.

**Intro (`IntroView.kt` + `intro_bg.webp`) — committed:**
- New **photographic** AL-AYED splash: 3-car hero + tech panels (SYSTEM CHECK / DATA STREAM
  / ECU STATUS / LIVE DATA) + contacts + Setting/Enter buttons. Source was a WhatsApp-
  compressed **1600×900** JPEG scaled to 1920×1080, clean encode (earlier sharpen looked
  "waxy", grain looked "noisy" — the AI/compression is the ceiling; a real full-res source
  would be sharper).
- **No brand-logo row** (`logos = emptyArray`). The old "remove Ford+Lexus / redistribute 9
  logos" work is now moot.
- **Marques scene trimmed** — intro is Power On → Cars → Emblem → Showtime (~4.75 s).
- **Focus ring** (`setBtnBox`/`entBtnBox`) fitted to the new buttons; the Setting button was
  widened ~18 px in the image so both buttons match; boxes symmetric.
- `IntroView` is full-screen `match_parent` (renders at display resolution), unlike the
  dashboard (locked to 1920×1080 via `StageLayout`). Design ref: `docs/design-refs/ecu-intro/`.
- SETTING opens a change-password modal that **only writes SharedPreferences** (decorative);
  it now enforces a **≥8-char** minimum to match the WPA2 AP-password key it's destined for.

**Dashboard (`Dashboard.kt`, `MainActivity.kt`, `dashboard.xml`, drawables) — committed:**
- **Top bar:** removed **Uptime**; added a **Back button** (styled like the DEVICE panel —
  `bd_line9`, `txt_label`, `ic_back_arrow`) that returns to `IntroActivity`; device status
  moved into Uptime's slot. Order: **[Back] [DEVICE status] [logo] [VOLTAGE] [CURRENT]**.
- Removed cell **outlines** from the fan box + IMO/HIP/IAC.
- **Fans faster:** `ensureFanLoop` target 300 → **1100 °/s** (watch for wagon-wheel aliasing
  at 60 Hz).
- **Bright red:** ring `bd_indic_circle_red` → `#FF4A3D` 3px; BAT-ON/SW-ON/MRC-/ETC-ON icons
  tinted `#FF4A3D`; labels `#ff3b2a` up to 18 px; **IMMO car** tinted `#FF4A3D` and forced
  always-full-brightness.
- **(2026-07-23 demo pass, `Dashboard.kt` + `colors.xml`)** Panel **outlines unified** to
  the frame-border colour — `line`/`border_scope`/`border_can`/`border_coil`/`border_inj`/
  `border_gdi` now all `@color/frame_border`, so every box matches the outer frame. Added two
  **10-second demo toggles that OVERRIDE live status** (the indicator row + HIP no longer show
  real bits): (1) the **HIP** high-pressure **head** tints red on/off — a red-MULTIPLY overlay
  of `hip.png` masked (vertical+horizontal `DST_IN` gradients) to just the top dome, clear of
  the collar and the left fuel fitting; (2) the whole **indicator row** flips ON↔OFF together —
  text `X-ON`/`X-OFF` (MRC+/MRC- keep their text), red(ON)/green(OFF) text+symbol+ring, and the
  **5 relay symbols flip closed(ON, `relay_no`)/open(OFF, `relay_nc`)**; BAT/SW keep their icon.
  Both verified on the Galaxy Tab via `adb exec-out screencap`.

## 8. Devices & testing notes
- **ESP32:** flashed + verified on the real board.
- **Galaxy Tab S6 Lite (SM-P610, Android 13, USB):** primary verify device. **USB is very
  flaky** — keeps dropping; when it comes up MTP-only, toggle USB debugging and accept the
  prompt; use a good cable and the ABSOLUTE apk path. Amber "Eye Comfort" tint in
  screenshots is the display, not the app. Simulated `DPAD_LEFT` on a touch device advances
  the intro instead of moving focus.
- **CBOX 3.0 (Amlogic, Android 9):** tiny internal storage — installs fail ("not enough
  space", ~320 MB reserve); free space or lower `sys_storage_threshold`. DHCP IP churns
  (.50→.62→.56…). No SD.
- **TCL Smart TV Pro (Android 12):** **4K panel but Android UI is firmware-locked to a
  1920×1080 override** → hardware upscales 1080p→4K (why the intro looks soft); `wm size
  3840x2160` is rejected/reverted, so 4K rendering can't be forced. IP also churns.
- To see **live data** on any client, join it to the `ECU_TESTER` Wi-Fi (targets
  `10.10.10.10`); otherwise it free-runs the `DemoDriver`.

## 9. Punchlist — what's left
1. **KiCad front-end board (D4)** — not started; blocks all real-driver work. Parts in
   `hardware/README.md`.
2. **Real acquisition drivers** (`i2c_bus`+`ads1115`+`mcp23017`, `hc165`, `ckp_capture`
   RMT/GPTimer 60-2). Blocked on #1.
3. **A UI that SENDS COMMAND/SUBSCRIBE** — firmware handler ready; nothing drives it yet.
4. **Real AP-password change** — new `cmd_id` + NVS-backed creds + reboot (intro modal only
   writes SharedPreferences today, though it now validates the WPA2 ≥8-char minimum).
5. **Kiosk-reboot autostart** on the real TV (last D9 sub-item).
- Minor/deferred: protocol-v1 gaps (CTS/IGF/CURRENT zeroed in UIs); Android reconnect ~22 s;
  a higher-res intro source for sharpness.

## 10. House style (CLAUDE.md §7, Karpathy guidelines)
Think before coding (assumptions, options + a recommendation). Simplicity first, surgical
changes, match existing style. Define pass/fail then loop build→flash/install→observe→done.
Don't silently retune `sdkconfig.defaults`/`partitions.csv`/pinmap/Gradle versions. The 4
protocol mirrors change together in one commit. After meaningful work regenerate
`pm-sync.json` and commit `chore: sync dashboard` (dashboard import is the user's manual
step).

## 11. Persisted memory (`~/.claude/projects/.../memory/`)
`idf-build-env.md`, `tv-render-perf.md`, `fps-target-and-loop.md`, `old-tv-es5-compat.md`,
`android-migration.md`, `android-build-env.md` — auto-load in future sessions.
