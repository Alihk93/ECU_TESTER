# ECU_TESTER — Handover Summary (2026-07-20)

> Paste-ready session primer. For the authoritative source of truth read `CLAUDE.md`
> first; full decision log + open-work punchlist live in `OPEN_DECISIONS.md`.

## What this project is
A real-time hardware-in-the-loop **car-ECU bench tester** for client **AL-AYED**, by the
firm **TON by Swiss**. An **ESP32-S3-WROOM-1 N16R8** (16 MB flash, 8 MB octal PSRAM)
acquires ~50 automotive signals + crank/cam waveforms, runs its own Wi-Fi SoftAP, and
serves telemetry over a **binary WebSocket** protocol to a display client. **Monitor-only**
— reads ECU outputs, never drives signals into the ECU.

- **Repo:** `Alihk93/ECU_TESTER`, branch `main` (HEAD current, clean, in sync).
- Two display clients: **Android TV app (`android/`, PRIMARY)** and the **web dashboard
  (`web/`, fallback)**. Both are just WS clients on `/ws`.

## Environment / build
**Firmware** (ESP-IDF v5.5.2, target `esp32s3`; serial `/dev/ttyACM0` first, else `/dev/ttyUSB0`):
```
export IDF_TOOLS_PATH=/home/ali/ESP_IDF/Version_5.5.2/IDF_Tool_v5.5.2
export IDF_PYTHON_ENV_PATH=$IDF_TOOLS_PATH/python_env/idf5.5_py3.13_env
source /home/ali/ESP_IDF/Version_5.5.2/IDF_v5.5.2/v5.5.2/esp-idf/export.sh
idf.py -C firmware/ECU_Tester build
idf.py -C firmware/ECU_Tester -p /dev/ttyACM0 flash
```
**Android** (userspace toolchain — nothing on PATH by default; Gradle wrapper 8.7,
minSdk 24, compileSdk 34, AGP 8.5.2, Kotlin 1.9.24, JDK 17):
```
export JAVA_HOME=~/Android/jdk17
export ANDROID_HOME=~/Android/Sdk; export ANDROID_SDK_ROOT=~/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd android && ./gradlew :app:assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
# adb=~/Android/Sdk/platform-tools/adb
```
**Dashboard/dev sim (no hardware):** `python3 tools/sim_server.py [--host 0.0.0.0]
[--corrupt] [--waveforms]` → serves `web/` + real binary protocol on `:8090`. `--corrupt`
= bad CRCs (integrity test); `--waveforms` = re-enable edge-list streaming (off by
default). Android USB dev loop: `adb reverse tcp:8090 tcp:8090` → `adb shell am start -n
com.alayed.ecutester/.IntroActivity` → `adb exec-out screencap`.

## Architecture — locked decisions (D0–D9, `OPEN_DECISIONS.md`)
- **D0** clean start (S-ECU patterns only, no code reuse).
- **D1** AP: SSID `ECU_TESTER`, pw `00000000` (WPA2-PSK, 8-char min), IP **`10.10.10.10`**
  (non-default, set explicitly).
- **D2** monitor-only — no stimulus into the ECU (manual controls exist only in sim mode).
- **D3** activity-only coil/injector bits (latched "fired since last frame", no dwell/PW).
- **D4** front-end designed in this KiCad project — **NOT started** (blocks real drivers).
- **D5** CKP = VR 60-2 (MAX9926); CMP1/CMP2 = Hall (jumperable to either).
- **D6** waveform = edge-list; **not streamed by default** since 2026-07-12
  (`s_wave_stream=false`; nothing plots it, scope is parametric).
- **D7** MCP23017 (status) + 74HC165×3 (24 activity bits) + ADS1115 (precision analog).
  Pinmap committed in `board_config.h`.
- **D8** (superseded by D9) — Chromium kiosk on a mini-PC; kept as fallback.
- **D9** (resolved) — display client = **native Android TV app**. GPU-composited, removes
  the TV-browser software-render FPS ceiling. Firmware/protocol/assets unchanged.

## The protocol contract — FOUR mirrors, change together
`docs/PROTOCOL.md` ⇄ `firmware/.../main/include/protocol.h` ⇄ `web/js/protocol.js` ⇄
`android/.../Protocol.kt`
- Binary, **little-endian**, envelope 8 B + payload + **CRC16-CCITT-FALSE** (poly 0x1021,
  init 0xFFFF, check vector 0x29B1).
- **TELEMETRY (0x01, 22 B):** `t_us(u32), rpm(u16), maf(u16), map(u16), iat(int16),
  ecu_v(u16), sensor_v(u16)` — all analog = raw mV at the ECU pin (UI applies transfer
  curves) — `coils/inj_reg/inj_gdi(u8 latched activity)`, `status(u16 bitfield)`,
  `iac(u8 phase nibble)`.
- **WAVEFORM (0x02):** CKP/CMP edge-lists; default-off. All clients bail out **before** the
  CRC pass on a WAVEFORM frame (FPS optimization).
- **COMMAND (0x80)/SUBSCRIBE (0x81)/ACK (0x8F):** defined in spec, **not handled by
  firmware** (`ws_handler` only completes the WS handshake).
- **Status bits:** 0 battery, 1 switch, 2 start, 3 etc, 4 fan1, 5 fan2, 8 fuelPump,
  9 immoP, 10 immoN, 11 mrcP, 12 mrcN.

## Firmware state (`firmware/ECU_Tester/`, ESP-IDF)
- Compiles clean for `esp32s3` (~73% app partition free). **Verified on the physical
  ESP32 2026-07-19**: SoftAP up @ `10.10.10.10`, LittleFS mounted, dashboard served, WS
  clients connect, no evictions over 5+ min.
- **Live data path done end-to-end in SIMULATION:** `acq_task` (core 1) generates
  RPM/analog-mV/status/IAC; `net_task` (core 0) integrates a crank-angle accumulator →
  latched coil/injector firing bits + CKP 60-2/CMP edge-lists, broadcasts TELEMETRY
  ~30 Hz. `ws_broadcast` = non-blocking per-client sends (zero-timeout `select`) + ~2 s
  eviction so one dozing client can't freeze others.
- **Real acquisition drivers NOT built** (blocked on D4 hardware) — still sim generators.
- Committed review fixes: **LRU-counter touch on WS send** (don't evict live viewers),
  **NVS erase-and-retry** (no boot-loop).
- Load-bearing (don't silently retune): `sdkconfig.defaults` (`CONFIG_HTTPD_WS_SUPPORT=y`),
  `partitions.csv` (4 MB `storage` littlefs), `main/CMakeLists.txt` (builds the LittleFS
  image from `web/`).

## Web dashboard (`web/`, FALLBACK client)
- Flat marine skin (navy `#33516f`, **no gradients/shadows/filters** — client-locked).
  **ES5 classic scripts** (old-TV compat down to ~Chromium 49). **Flexbox only** (no CSS
  Grid, no flex `gap`, no `inset:`, no `space-evenly`, no `NodeList.forEach`). Full
  1920×1080 cluster scaled to viewport.
- Contract mirror `js/protocol.js`; `js/live.js` maps frames → the `ECU` API in `app.js`
  (coalesced, rAF-paced). Scope is a **parametric standing display** (not a live plot).
- **The intro splash is Android-only** — the 6.5 MB image won't fit the 4 MB LittleFS and
  the intro's blur/gradient effects violate the old-TV perf rules.

## Android app (`android/`, PRIMARY client)
- Kotlin, classic Views/Canvas (not Compose/SurfaceView), OkHttp WS, minSdk 24. **One
  universal APK runs on TV + phone + tablet** (touchscreen & leanback both
  `required="false"`, dual launcher intents, no native ABI split, StageLayout
  scale-to-fit). Landscape-locked; kiosk plumbing (BootReceiver autostart, crash-relaunch,
  show-over-lockscreen, optional device-owner lock-task).
- Files: `Protocol.kt` (4th mirror), `EcuSocket.kt` (OkHttp WS + 500ms→5s backoff),
  `LiveMapper.kt` (coalesce per painted frame), `DemoDriver.kt` (free-run demo until first
  real frame), `Dashboard.kt` (view controller), `ui/StageLayout.kt`, `ui/RpmDialView.kt`,
  `ui/MiniGaugeView.kt`, `ui/ScopeView.kt`, `MainActivity.kt`.
- Committed review fixes: **stale-stream watchdog** (1 s tick, cycle socket if telemetry
  stales >2.5 s — the TV-freeze fix), **locale-pinned formatting** (`Locale.US`), **spark
  re-flash while firing bit held** (no freeze at high RPM).
- **VERIFIED on a real Android TV vs the real ESP32 (2026-07-19)** — G08 4K TV, Android 12:
  reports a **1920×1080 UI override** (stage scales 1:1), links to `10.10.10.10` over the
  AP, **~51 fps** live, stable 5+ min, zero evictions. **Watchdog validated**: DISCONNECTED
  in **3 s** on device power-loss, clean freeze, auto-recovers (~22 s Wi-Fi-back → WS
  reconnect). Also verified on a Galaxy Tab S6 Lite (Android 13, current USB dev device)
  and a Huawei P30 phone.

## Cinematic AL-AYED intro splash (Android — added 2026-07-20)
- **`IntroActivity`** is the cold-launch entry (app icon / TV Apps row / boot); plays a
  **~5.9 s** 5-scene intro (Power On → Cars → Emblem → Marques → Showtime), then
  **ENTER → MainActivity** (dashboard). `CATEGORY_HOME` stays on MainActivity so **Home
  returns to the dashboard, not a replayed intro**. `BootReceiver` → IntroActivity.
- **`ui/IntroView.kt`** — native Canvas port of a Claude Design handoff (`ecu-intro.jsx`).
  All art is one composite image; each element is a feathered-ellipse crop revealed/scaled/
  brightened per scene, + overlays (ground glow, streak, flash, gleam, vignette, letterbox
  bars). Global `SPEED=1.6f` time-scale.
- **`ui/IntroAudio.kt`** — procedural sound bed (ambient/whoosh/thump/blips/impact/ripple/
  chime/click) rendered to PCM, played via AudioTrack, cued per scene.
- End state: TV-remote + touch nav; **ENTER → dashboard**, **SETTING → change-password
  modal** (SharedPreferences, **default `00000000`**).
- Asset: 5.8 MB source PNG re-encoded to a **183 KB WebP** at display size
  (`res/drawable-nodpi/intro_bg.webp`); duplicate small top-logo painted out of the asset.
- **Key gotcha:** the intro MUST be a **separate top-level activity** — launching it from
  `MainActivity.onCreate` tangled the lifecycle and starved its layout/draw (~60 s black
  screen). Design source filed at `docs/design-refs/ecu-intro/`. See `android/README.md`.
- Open design calls: plays on **every cold start** (skippable) — could be once-only; ~5.9 s
  pace is tunable; Settings modal drops immersive on the tablet (invisible on a real TV).

## Punchlist — what's left (`OPEN_DECISIONS.md` → "Punchlist")
1. **KiCad front-end board (D4)** — dividers/clamps/buffers, ADS1115, MCP23017, 74HC165,
   MAX9926, Hall clamps, TVS. **Not started; blocks all real-driver work.** Parts spec'd in
   `hardware/README.md`.
2. **Real acquisition drivers** — `i2c_bus`+`ads1115`+`mcp23017`, `hc165` reader,
   `ckp_capture` (RMT/GPTimer, 60-2 decode). Replace the sim generators. Blocked on #1.
3. **App/browser → device COMMAND channel** — `ws_handler` needs to parse 0x80/0x81 and
   reply ACK; nothing sends commands yet.
4. **Kiosk-reboot autostart** on the real TV (one remaining D9 sub-item; optional
   `dpm set-device-owner` full lock). Settle: shop-floor TV must permanently rejoin the
   `ECU_TESTER` AP on boot.

**Minor / deferred**
- (2026-07-16 review) WS concurrent-write race (latent, only if client pings enabled);
  `ws_broadcast` strike counter inherits across fd reuse (cosmetic, self-healing).
- (2026-07-19 bench) Android reconnect slow (~22 s — reset EcuSocket backoff harder on
  network-available); ScopeView scrolls while DISCONNECTED (cosmetic).
- Protocol v1 gaps if ever wanted: CTS, IGF, CURRENT (zeroed/pinned in both UIs, no wire
  field).
- **GitHub issues NOT created** (`gh` not installed + connector needs OAuth). Helper ready:
  `tools/create_issues.sh` (run after `gh auth login`).

## House style (CLAUDE.md §7, Karpathy guidelines)
Think before coding (state assumptions, options + a recommendation on tradeoffs).
Simplicity first, no speculative abstractions. Surgical changes only, match existing style.
Goal-driven: define pass/fail, loop build→flash/install→observe→done. Don't silently retune
`sdkconfig.defaults`/`partitions.csv`/pinmap/Gradle plugin versions.

## PM / dashboard sync
After meaningful work: regenerate `pm-sync.json` from `git log` (lastCommit/
lastCommitDate/lastSync only), commit `chore: sync dashboard`. **Dashboard import is the
user's manual step** (Dashboard → Import → `pm-sync.json`). A reconciled full backup with
updated phases/tasks exists at `~/Downloads/iot-pm-backup-2026-07-16-reconciled.json`
(import optional).

## Persisted memory files (`~/.claude/projects/.../memory/`)
`idf-build-env.md`, `tv-render-perf.md`, `fps-target-and-loop.md`, `old-tv-es5-compat.md`,
`android-migration.md` (full D9 history + on-TV verification + the "keep the dev PC online"
bench method), `android-build-env.md`. These auto-load in future sessions.
