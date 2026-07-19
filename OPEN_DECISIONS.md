# OPEN_DECISIONS.md — ECU_TESTER

Gating decisions. Each blocks a concrete part of the design; nothing downstream
(pinmap, protocol field set, hardware front-end, firmware task detail) should be
finalized until the relevant item is resolved. Format: **context → options →
recommendation → status**.

Legend: 🔴 blocking · 🟡 should decide soon · 🟢 resolved

> **D0–D8 RESOLVED — 2026-07-02.** The header emoji show each item's *original*
> priority; the **Status** line under each records the final decision.
> **D9 added 2026-07-13** (display client → native Android TV app) — supersedes D8
> for the smart-TV fleet; see below.

---

## D0 — Relationship to S-ECU  🔴
**Context:** ECU_TESTER shares S-ECU's transport skeleton and the spec even names the
AP `S-ECU`. But scope, protocol, and features are a large step up.
**Options:** (a) ECU_TESTER supersedes S-ECU (S-ECU archived); (b) fork — reuse
`dashboard.js` + SVG art as a starting point; (c) fully parallel, clean start.
**Recommendation:** (a)/(b) — supersede, and salvage the SVG gauge art + needle-pivot
JS from S-ECU rather than redraw. The transport is already proven.
**Status:** 🟢 RESOLVED (2026-07-02) — **(c) clean start.** Written from scratch; S-ECU
contributes *patterns* only, no code/art reuse.

## D1 — AP credentials  🔴
**Context:** spec says SSID `S-ECU`, password `0000`. **WPA2-PSK requires ≥ 8
characters** — a 4-char key will be rejected by the Wi-Fi stack.
**Options:** (a) open AP (no password); (b) new key ≥ 8 chars (e.g. `S-ECU0000`);
(c) WPA3-SAE (no length floor, but older clients/TVs may not support it).
**Recommendation:** (b) for a bench tool — a short but valid key. Use (a) only if the
display device can't type a password at kiosk boot.
**Status:** 🟢 RESOLVED — **(b)** SSID `ECU_TESTER`, password `00000000`, **WPA2-PSK**
(8 chars = the WPA2 minimum). *(IP 10.10.10.10 is non-default — firmware sets the AP netif
IP explicitly; default SoftAP is 192.168.4.1.)*

## D2 — Monitor-only vs bidirectional stimulator  🔴 (biggest one)
**Context:** "test the ECU" is ambiguous. Two very different machines.
**Options:** (a) **monitor-only** — read ECU outputs, dashboard visualizes them;
(b) **bidirectional stimulator** — also *generate* signals into the ECU (fake CKP/CMP,
sensor voltages, loads) and observe its response. (b) needs output drivers, DAC/PWM
signal generation, and a lot more firmware + hardware.
**Recommendation:** confirm intent. If (b), split into phases: v1 monitor-only, v2 add
stimulus. Don't design the front-end twice.
**Status:** 🟢 RESOLVED — **(a) monitor-only.** No stimulus into the ECU. Manual controls
exist only in simulation mode. Front-end is input-conditioning only (no output drivers).

## D3 — Coil/injector: measure timing vs indicate activity  🔴
**Context:** dashboard shows firing animations. For real ECU testing, **dwell time**
and **injector pulse width** are the meaningful measurements.
**Options:** (a) **activity only** — a latched "fired since last frame" bit per channel
(cheap, edge-detect); (b) **timing** — capture rising/falling edges per channel and
report dwell/pulse-width (needs precise timestamp capture for up to 16–24 channels).
**Recommendation:** if this is a real tester, (b) at least for a few channels. (b) at
full 16–24 channels drives the hardware choice in D7 hard — likely a capture front-end.
**Status:** 🟢 RESOLVED — **(a) activity-only.** Latched "fired since last frame" bit per
channel via a 74HC165 chain; no dwell/pulse-width capture, so no fast per-channel capture
hardware.

## D4 — Signal conditioning / input front-end  🔴
**Context:** ECU signals are 12 V+ (coil drive has inductive flyback spikes into the
tens/hundreds of volts; VR CKP sensors are bipolar AC). **ESP32 GPIO is 3.3 V max.**
The spec doesn't mention conditioning at all — it's mandatory.
**Options:** (a) existing analog front-end board already handles it; (b) design it into
this KiCad project (dividers + clamps for analog, opto/comparator for digital edges,
protection diodes everywhere).
**Recommendation:** (b), and I need the **actual voltage range per signal** to size it.
**Status:** 🟢 RESOLVED — **(b) design the front-end in this KiCad project.** Locked part
selection + per-signal conditioning are in `hardware/README.md` (ADS1115, MCP23017,
74HC165, MAX9926, op-amp buffers, dividers/clamps/TVS).

## D5 — CKP/CMP sensor types + pattern  🟡
**Context:** acquisition method depends on sensor electrical type and tooth pattern.
**Options:** VR (variable reluctance — analog AC, needs conditioning to a square wave)
vs Hall (already digital). Pattern e.g. 36-1, 60-2, or OEM-specific; CMP index count.
**Recommendation:** tell me the sensor types and target pattern(s); it sets the capture
peripheral (MCPWM capture / GPIO ISR + `esp_timer`) and the scope rendering.
**Status:** 🟢 RESOLVED — **CKP = VR, 60-2** (via MAX9926 adaptive-threshold interface);
**CMP1/CMP2 = Hall** (divider + clamp). Each high-speed input is jumperable to the other
type.

## D6 — Waveform display mode  🟡
**Context:** the scope must show missing-tooth, cam sync, timing relationships — raw
10 kHz can't go over the wire.
**Options:** (a) **scrolling decimated window** (oscilloscope look, ~30–60 fps frames);
(b) **edge list** (send tooth edge timestamps — tiny, exact timing, best for missing-
tooth); (c) **triggered capture** (grab a buffer around a trigger, send it whole, like a
real DSO). Not mutually exclusive.
**Recommendation:** (b) + a coarse (a) envelope for the "analog" look. Add (c) later if
you want single-shot capture. Need the time window + samples/frame you want on screen.
**Status:** 🟢 RESOLVED — **(b) edge-list** is the default wire format for CKP/CMP1/CMP2
(exact, tiny, ideal for missing-tooth), with an optional coarse **(a)** decimated envelope
for the analog look; **(c)** triggered single-shot deferred. Rolling window ≈ 2 crank
revolutions (tunable via SUBSCRIBE).

## D7 — GPIO budget / expansion  🟡
**Context:** ~30 digital inputs + 5 ADC + 3 high-speed + I2C/UART **exceeds usable GPIO**
on WROOM-1 **N16R8** (octal PSRAM consumes several pins).
**Options:** (a) I/O expander (MCP23017 over I2C) for the slow status lines
(battery/switch/fans/pump/IMMO/MRC); (b) 74HC165 shift registers (parallel-in) for coil/
injector *states*; (c) a small front-end MCU/CPLD for fast multi-channel edge capture if
D3 = timing. Fast edge capture can't go through a slow I2C expander.
**Recommendation:** slow status → MCP23017; coil/injector activity → 74HC165 chain;
revisit if D3 requires per-channel timing (then a capture front-end).
**Status:** 🟢 RESOLVED — **MCP23017** (slow status) + **74HC165** chain (coil/injector
activity) + **ADS1115** (precision analog). No capture front-end MCU (D3 = activity-only).
Concrete GPIO **pinmap committed** in `firmware/ECU_Tester/main/include/board_config.h`,
mirroring the "GPIO map" in `hardware/README.md`: I²C SDA 47 / SCL 21 · Sensor-V ADC1
GPIO4 · CKP/CMP1/CMP2 = 5/6/7 · 74HC165 QH/CLK/LD = 16/17/18 (26–37 reserved for octal
flash/PSRAM; 0/3/45/46 strapping; 19/20 USB; 43/44 UART0).

## D8 — Client display + kiosk auto-launch  🟡
**Context:** spec mentions "Internet Explorer", "smart TV", "PC desktop", and "boot
automatically into the dashboard". The ESP32-S3 has no display output — it can't launch
a browser. "Boot into dashboard" is a property of the *display device*.
**Options:** target device = (a) mini-PC / NUC running a Chromium kiosk (`--kiosk
http://10.10.10.10`, autostart via the OS); (b) smart-TV browser; (c) tablet.
**Recommendation:** (a) Chromium kiosk on a small PC — reliable fullscreen + solid
WebSocket support. **Avoid IE** (effectively dead; poor/absent WebSocket support).
**Status:** 🟢 RESOLVED — **(a) Chromium kiosk on a mini-PC**
(`chromium --kiosk --app=http://10.10.10.10`, OS autostart). IE avoided.
**⤷ Superseded for the smart-TV fleet by D9** (native Android app). The mini-PC
Chromium path stays available as a fallback.

## D9 — Display client: browser dashboard vs native Android TV app  🟡
**Context:** the bench TVs software-composite in Chromium; the flat skin + perf
passes got the browser to ~12–25 fps but the ceiling is the *renderer*, not the
design. D8 chose a mini-PC Chromium kiosk as the guaranteed-60 path, but the fleet
TVs run Android TV OS and can host a native app.
**Options:** (a) keep the web dashboard on a Chromium kiosk / mini-PC (D8);
(b) **native Android TV app** installed on the smart TVs — GPU-composited, so the
renderer ceiling disappears; (c) both.
**Recommendation:** (b) as the primary display; keep (a)/`web/` as fallback +
reference (firmware unchanged, still serves it).
**Status:** 🟢 RESOLVED (2026-07-13) — **(b) native Android TV app.** minSdk 24,
Kotlin + classic Views/Canvas, OkHttp WebSocket, ADB-sideload updates. Firmware and
the wire protocol are unchanged — the app is another WS client on `/ws`; `Protocol.kt`
is a fourth contract mirror (`docs/ANDROID_MIGRATION.md`). `web/` is kept as the
fallback, **not** deprecated. **M1 slice (RPM needle) built + verified on-device
2026-07-14** — 60 fps gliding needle + CRC-gate reject test both pass. Full-cluster
port next. Open sub-items: kiosk/autostart mechanism, TV 1080p-UI confirmation
(`docs/ANDROID_MIGRATION.md` §10).

---

## Punchlist — open work (all gating decisions D0–D9 resolved)

Mirrors the ECU_TESTER project board. These are execution items, not decisions.

1. **KiCad front-end board (D4)** — dividers/clamps/buffers, ADS1115, MCP23017,
   74HC165, MAX9926, Hall clamps, TVS. Not started; **blocks all real-driver work.**
   Part selection + per-signal conditioning already specified in `hardware/README.md`.
2. **Real acquisition drivers** — `i2c_bus` + `ads1115` + `mcp23017`, `hc165`
   shift-chain reader, `ckp_capture` (RMT/GPTimer, 60-2 decode + RPM). Replace the
   `acq_task`/`net_task` sim generators field-by-field. Blocked on item 1.
3. **App/browser → device COMMAND channel** — `ws_handler` only completes the WS
   handshake; parse COMMAND (0x80)/SUBSCRIBE (0x81) and reply ACK (0x8F)
   (`docs/PROTOCOL.md §5`). Nothing sends commands from either client yet.
4. **Verify Android app on a real Android TV vs the ESP32** — **mostly DONE
   (2026-07-19).** On a G08 4K TV (Android 12): APK installs, reports a 1920×1080 UI
   override (stage scales 1:1), links to `10.10.10.10` over the `ECU_TESTER` AP, renders
   live at ~51 fps, stable 5+ min with zero WS evictions. Watchdog (review finding #1)
   validated: DISCONNECTED in 3 s on device power loss, clean freeze, auto-recovers. **Only
   the kiosk-reboot autostart sub-item is still unverified** (reboot TV → app auto-launch;
   optional `dpm set-device-owner` lock).

**Minor / deferred (from the 2026-07-16 code review):**
- Concurrent-write race on WS sockets — `net_task` and the httpd task can both
  `send()` one fd; latent, only bites if client WS pings get enabled later.
- `ws_broadcast` strike counter inherits across fd reuse — cosmetic, self-healing
  (a recycled fd can be evicted early). Clear `strikes[fd]` on WS handshake to fix.

**Minor / deferred (from the 2026-07-19 on-TV bench):**
- Android reconnect is slow — ~22 s from Wi-Fi-back to WS reconnect after a device
  outage (recovery is automatic, but slower than the 5 s backoff cap implies; likely
  Android deferring sockets on a no-internet network + grown backoff). Reset the
  EcuSocket backoff harder on network-available.
- ScopeView keeps scrolling while DISCONNECTED — cosmetic; arguably freeze it to
  signal "no data" (it runs off the last RPM, independent of telemetry).

## Resolved / assumptions currently baked into the scaffold
- ESP32-S3-WROOM-1 **N16R8**, ESP-IDF **5.5.x**. 🟢
- SoftAP + `esp_http_server` WebSocket, dual-core, ADC1-only, lock-free length-1 queue. 🟢
- **Binary + CRC** protocol (not JSON), little-endian. 🟢 (see PROTOCOL.md)
- **LittleFS** for web assets (not `EMBED_FILES`). 🟢 (see ARCHITECTURE.md tradeoff)
