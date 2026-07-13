# Android app migration — display client (planned)

> **Status: PLAN (not started).** Direction set 2026-07-13. This replaces the
> **display client only** — the browser dashboard (`web/`) — with a native
> **Android TV app**. Firmware, wire protocol, assets, layout and architecture
> are unchanged. Read this before touching any `android/` code; follow the house
> rules in `CLAUDE.md` §7.

---

## 1. Why

The FPS ceiling on the bench TVs is a **renderer** problem, not a design problem:
TV-class Chromium software-composites, so every decorative pixel costs a frame
(the whole flat-skin / `steps()` / no-continuous-redraw rulebook in
`web/README.md` exists to work around that). Android TV's surface compositor is
**GPU-accelerated by default** — needle rotations, alpha fades and gauge redraws
become nearly free. Expected outcome: smooth 60 fps **and** better visuals
(real gliding needles instead of the stepped ones we were forced into), with the
ESP32 firmware and the WebSocket contract untouched.

## 2. Confirmed decisions (2026-07-13)

| # | Decision | Consequence |
|---|----------|-------------|
| A | **Target = smart / Android-TV TVs** (app installs directly, no new box) | Display device is Android-capable; D8's mini-PC kiosk is retired for these TVs but stays as a fallback path |
| B | **minSdk = 24 (Android 7.0)** | Modern OkHttp + full Java-8 APIs; no legacy shims. This is the new compat floor, the way old Chromium was for `web/` |
| C | **Updates = service-visit sideload (ADB)** | No partition changes — LittleFS `storage` (4 MB) keeps serving `web/` as-is. No in-app APK downloader in v1 |
| D | **Keep `web/`** as fallback + reference | Firmware unchanged; both clients coexist. Nothing deleted |

## 3. Locked (do NOT change during this migration)

- **Firmware** — `main.c`, tasks, SoftAP, `ws_broadcast`. The app is just another
  WebSocket client on `/ws`.
- **The contract** — `docs/PROTOCOL.md`, `protocol.h`. Same 8-byte envelope +
  22-byte TELEMETRY payload + CRC16-CCITT. `Protocol.kt` is a *port* of
  `protocol.js`, added as a **fourth** mirror of the same spec — the golden rule
  (change all mirrors in one commit) now covers `.md` / `.h` / `.js` / `.kt`.
- **Signal map + scaling** — §3.1 mV-at-the-pin encoding, status bitfield §3.2,
  the UI-side curves. Same as `web/README.md`.
- **Assets** — every PNG in `web/assets/img/` and both fonts (Saira, DSEG7) drop
  into `res/drawable` / `res/font` unchanged.
- **`tools/sim_server.py`** — stays the no-hardware dev path (see §7).

## 4. What gets rewritten (`web/` → `android/`)

| Web file | Android equivalent | Notes |
|----------|--------------------|-------|
| `js/protocol.js` | `Protocol.kt` | `ByteBuffer` `LITTLE_ENDIAN`; same CRC16, same field order. Unsigned care: mask `0xFFFF`/`0xFF`; `iat` stays signed |
| `js/websocket.js` | `EcuSocket.kt` (OkHttp `WebSocket`) | `onMessage(ws, ByteString)`; same 500 ms→5 s reconnect backoff |
| `js/live.js` | `LiveMapper.kt` | decoded frame → view updates; same coalesce + OR-latched firing bits + >2.5 s stale watchdog |
| `app.js` (view + `ECU` API) | custom `View`s + a `Dashboard` controller | RPM dial, mini-gauges, banks, scope (see §5) |
| `style.css` | XML layouts + drawables + a theme | the entire old-TV compat CSS section **evaporates** (GPU-composited, minSdk 24) |
| `js/diag.js` | `DiagOverlay` view | FPS via `Choreographer.FrameCallback` |
| `index.html` | one `Activity`, immersive-sticky fullscreen | |
| `fitStage()` zoom/transform | none | Android TV renders the app at 1080p UI resolution; design in a fixed 1920×1080 space, scale the root to fill |

## 5. Tech stack — decided

**Kotlin + classic Views/Canvas.** Not Compose, not a SurfaceView game-loop.

- **Needles** = a separate `View`/`ImageView` with `view.rotation = angle` → a
  GPU transform, no redraw. Glide between the ~30 Hz telemetry updates with a
  short `ValueAnimator` (now affordable; on web we had to step them).
- **Scope** = one custom `View`, `invalidate()` **only** when the RPM-frequency
  bucket changes — the same event-driven discipline the parametric scope has
  now, no continuous loop.
- **Banks (coil/inj/gdi)** = `ImageView`s; spark/spray overlays fade via short
  alpha animations gated on the latched activity bit.
- **7-seg readouts** = `TextView` + DSEG7 font, `setText` only on change.
- **Only-changed-view-invalidates** carries over from the web view layer — but
  now nothing is fighting the renderer.
- Why **not Compose**: its win is dynamic reactive UI; a fixed instrument cluster
  doesn't need it and it carries more runtime baseline on older Android TV.
- Why **not a SurfaceView loop**: overkill, and it reintroduces the continuous
  per-frame redraw we deliberately removed.

**WebSocket**: OkHttp (`okhttp3.WebSocket`). Supports API 21+; minSdk 24 is
comfortable.

## 6. The two things that genuinely change shape

1. **"Boot into dashboard"** — was `chromium --kiosk` autostart on the mini-PC.
   On Android TV: a `LEANBACK_LAUNCHER` intent-filter, `FLAG_KEEP_SCREEN_ON`,
   immersive-sticky fullscreen, and boot-autostart. True unattended kiosk
   (relaunch-on-crash, no exit) needs a small spike — device-owner or a launcher
   replacement on a box we control. **Open item, not a blocker for the slice.**
2. **Updates** — was: a reflash pushes new UI to every kiosk instantly
   (`no-cache`). Now: **sideload the APK via ADB on service visits** (decision C).
   Simple, appliance-appropriate, zero partition impact. (An in-app Wi-Fi updater
   pulling the APK the ESP32 serves is possible later but needs a LittleFS/APK-
   size rethink — deferred.)

## 7. Dev loop (no hardware)

- **Android Studio + Android TV emulator** → point at `tools/sim_server.py` via
  the emulator host alias `10.0.2.2:8090`. Same binary protocol, same generators
  as the firmware sim.
- **Physical Android TV box on the bench Wi-Fi** → point at `10.10.10.10` (real
  device) or at the laptop sim. One tiny dev-env tweak: `sim_server.py` binds
  `127.0.0.1` — bind `0.0.0.0` (or the LAN IP) to reach it from a real box.
- Gradle build → APK → `adb install`.

## 8. Repo layout

```
ECU_TESTER/
├── web/            ← KEPT: fallback + reference (firmware still serves it)
├── android/        ← NEW: Gradle project (app module, Kotlin)
│   └── app/src/main/
│       ├── java/…/  Protocol.kt, EcuSocket.kt, LiveMapper.kt, views…
│       ├── res/     drawable (PNGs), font (Saira/DSEG7), layout, values
│       └── AndroidManifest.xml
└── firmware/       ← UNCHANGED
```

## 9. First milestone — M1-Android (mirror of `docs/M1_VERTICAL_SLICE.md`)

**One real Android TV box → connect `/ws` (or sim) → decode `rpm` → drive the RPM
needle at 60 fps.** Proves the whole path — OkHttp + Kotlin decode + GPU needle —
before rebuilding all 40+ components.

**PASS** iff, against `sim_server.py` (or a live device):
- the RPM needle tracks the breathing rpm smoothly, gliding between updates;
- the reading matches the sim's rpm;
- a deliberately corrupted CRC (flip one payload byte in the sim) **freezes** the
  needle — proving `Protocol.kt`'s CRC/magic gating end-to-end, not the happy path;
- the diag overlay reads a steady 60 fps on the actual TV.

If it passes, the contract is validated on Android and the full rebuild
(all gauges/banks/scope/status) is a safe next step.

## 10. Open items to resolve before deployment (not before the slice)

- Exact kiosk/autostart mechanism per §6.1 (device-owner vs launcher replacement).
- Confirm the fleet's TVs report **1080p UI resolution** to apps (nearly all do,
  even 4K panels) so the fixed 1920×1080 design space maps 1:1.
- Whether to bump `CLAUDE.md` §Architecture + `OPEN_DECISIONS.md` with a formal
  **D9 "display client = Android TV app"** once the slice passes (keep the source
  of truth honest; don't mark `web/` deprecated — it's the fallback per decision D).
