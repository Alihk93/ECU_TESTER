# ECU_TESTER — Project Brief

> Portable summary for onboarding a new session. Read `CLAUDE.md` first every
> session; this file is the fast overview of architecture, decisions, state,
> and the punchlist.

## What it is
A real-time hardware-in-the-loop **car-ECU bench tester** for client **AL-AYED**.
An **ESP32-S3-WROOM-1 N16R8** (16 MB flash, 8 MB PSRAM) acquires ~50 automotive
signals + crank/cam waveforms, runs its own Wi-Fi SoftAP, and serves a **local
web dashboard** that renders in a browser on an external display (TV / monitor /
mini-PC). Also runs a full **simulation mode** with no hardware attached.
**Monitor-only** — reads ECU outputs, never drives signals into the ECU (manual
controls exist only in sim mode).

**Repo:** `Alihk93/ECU_TESTER`, branch `main`.

## Environment / build
- ESP-IDF **v5.5.2**, Ubuntu, target `esp32s3`. Device at `/dev/ttyUSB0`.
- Build env is finicky — system python is 3.14 but the IDF venv is 3.13, so the
  venv path must be exported or `export.sh` aborts:
  ```bash
  export IDF_TOOLS_PATH=/home/ali/ESP_IDF/Version_5.5.2/IDF_Tool_v5.5.2
  export IDF_PYTHON_ENV_PATH=$IDF_TOOLS_PATH/python_env/idf5.5_py3.13_env
  source /home/ali/ESP_IDF/Version_5.5.2/IDF_v5.5.2/v5.5.2/esp-idf/export.sh   # run directly, NOT in a pipeline
  idf.py -C firmware/ECU_Tester build
  idf.py -C firmware/ECU_Tester -p /dev/ttyUSB0 flash
  ```
- `idf.py flash` also writes the LittleFS `storage` partition (built from
  `web/`), so a flash deploys the dashboard too.
- **Dashboard dev with no hardware:** `python3 tools/sim_server.py` → serves
  `web/` + speaks the real binary protocol at `http://localhost:8090`. Point any
  served copy at real hardware with `?device=10.10.10.10`.

## Architecture (locked; S-ECU patterns only, D0 = clean start)
- **SoftAP** SSID `ECU_TESTER`, pw `00000000` (WPA2, 8-char min), IP
  **10.10.10.10** (firmware sets netif IP explicitly). Display client (D8) =
  **Chromium kiosk on a mini-PC** over HDMI — the guaranteed-60fps target; the
  bare TV browser is the hard case.
- **`esp_http_server` serves UI + hosts the WebSocket** (`CONFIG_HTTPD_WS_SUPPORT=y`,
  `.is_websocket=true`). No standalone ws component.
- **Dual-core FreeRTOS:** acq_task pinned core 1 (ADC/GPIO/waveform), net_task
  core 0 (packs frames, `httpd_get_client_list()` + `httpd_queue_work()`).
  Lock-free length-1 queue (`xQueueOverwrite`/`xQueuePeek`) for latest snapshot.
- **Two-tier data path:** acquisition fast on-device; wire at display rate —
  TELEMETRY ~30 Hz, WAVEFORM as edge-lists. Coil/injector firing bits **latched
  since last frame**.
- **Broadcast back-pressure is load-bearing:** `ws_broadcast` sends only when the
  socket is writable (zero-timeout select), drops otherwise, evicts after ~2 s;
  sockets get `SO_SNDTIMEO` + `TCP_NODELAY`. One blocking client froze all viewers.
- **Planned hardware front-end:** ADS1115 (precision analog: MAF/MAP/IAT/ECU-V),
  internal ADC1 (Sensor-V), MCP23017 (status lines), 74HC165 ×3 (24 coil/injector
  activity bits over SPI), MAX9926 (CKP VR 60-2), Hall dividers (CMP1/CMP2).
  Pinmap committed in `board_config.h`.

## Protocol (the contract — 3 files byte-for-byte in lockstep, change in ONE commit)
`docs/PROTOCOL.md` ⇄ `firmware/ECU_Tester/main/include/protocol.h` ⇄ `web/js/protocol.js`
- Binary, little-endian, CRC16-CCITT-FALSE. Envelope 8 B (magic `A5 5A`, version,
  msg_type, seq, payload_len) + payload + CRC.
- **TELEMETRY (0x01), 22 B:** t_us, rpm, maf, map, iat(int16), ecu_v, sensor_v
  (analog = **mV at the ECU pin**, UI applies curves), coils/inj_reg/inj_gdi
  (8-bit latched activity each), status (uint16 bitfield), iac (phase nibble).
- **WAVEFORM (0x02):** CKP/CMP1/CMP2; **mode 1 = edge-list is the default**.
- **COMMAND (0x80)/SUBSCRIBE (0x81)/ACK/HELLO** defined but **COMMAND not yet
  handled in firmware** (dashboard sends none).
- Status bits: 0 battery, 1 switch, 2 start, 3 etc, 4 fan1, 5 fan2, 8 fuelPump,
  9 immoP, 10 immoN, 11 mrcP, 12 mrcN.

## Decisions D0–D8 (all RESOLVED 2026-07-02; full log in `OPEN_DECISIONS.md`)
D0 clean start · D1 AP creds above · D2 **monitor-only** · D3 **activity-only**
coils/injectors (latched bit, no dwell/PW) · D4 design front-end in this KiCad
project · D5 CKP=VR 60-2, CMP1/2=Hall (jumperable) · D6 waveform = **edge-list**
primary · D7 ADS1115+MCP23017+74HC165 (no capture MCU) · D8 **Chromium kiosk on
mini-PC**.

## Web dashboard — HTML/CSS/JS (current)
Files: `web/index.html`, `web/style.css`, `web/app.js` (view layer + global `ECU`
API + `ECUqs()`), `web/js/protocol.js` (decoder, exposes `window.ECUProto`),
`js/websocket.js` (auto-reconnect, exposes `window.EcuSocket`), `js/live.js`
(frames→ECU), `js/diag.js` (perf meter). **No `components/` dir**, and (2026-07-11)
**no ES modules** — all five are classic `<script>` tags loaded in that order; see
"Universal old+new TV target" below.
- **Layout:** fixed 1920×1080 canvas fitted via Blink `zoom` (fallback transform),
  **flexbox + margin gutters, not CSS Grid**. Top bar (DEVICE/UPTIME/white
  logo/VOLTAGE/CURRENT 7-seg), status grid (FAN1/FAN2/IMO/HIP/IAC), CKP/CMP1/CMP2
  **canvas** oscilloscope + decorative CAN, relay indicator row, big RPM dial, six
  0–5 V mini gauges (CTS/MAF/MAP/IAT/5V/IGF), 8-cell COIL/INJ/GDI banks with
  spark/spray bursts.
- **THE FLAT MARINE SKIN IS THE DESIGN** (client decision 2026-07-10). Unified
  "white navy" background (`--navy` #33516f, screens `--navy-deep` #22374f), flat
  colors + 1px borders only. **No gradients / shadows / glows / filters / 7-seg
  ghost digits anywhere — do not reintroduce.** Logo = `logo_w.png` (white). Bank
  cells borderless (number badges kept). IMO indicator = `imo2.png`, SW-ON =
  `swon2.png` (renamed from `immo.png`/`swon.png` on image swap, 2026-07-11 — new
  filenames beat the year-long media cache on the kiosk).
- **Demo→live:** free-runs as demo until first frame, then `.is-live` gates every
  animation behind real telemetry. The scope is a **parametric standing display**
  (2026-07-11) — three clean square waves per lane (CKP/CMP1/CMP2), frequency
  tracking RPM, redrawn only when the RPM bucket changes (no continuous redraw
  loop); WAVEFORM frames still arrive but are decoded minimally, not plotted. Fans
  both spin **clockwise**, constant speed, gated on/off by FAN1/FAN2 status bits
  with a CSS ease-in spin-up and coast-down transition (no more RPM-scaled speed
  or fan2 reverse). live.js coalesces to ~12.5 Hz, OR-latches firing bits,
  stale-stream (>2.5 s) watchdog reconnect.
- **Signal quirks:** CTS/IGF/CURRENT have **no protocol v1 field** → zeroed in live
  mode. HIP + PFC-OFF both show the Fuel Pump bit. Banks show 8 channels (6-cyl sim
  fires 1–6). Full map in `web/README.md`.
- `js/diag.js` = corner **FPS / WS-per-sec / telemetry-age / resolution meter** (tap or
  press D to hide; on by default, `?diag=0` disables it for production). No render modes.

## Universal old+new TV target (2026-07-11)
The client tested on an **older TV (~Chromium 49–60)** and hit two silent
breakages fixed this session — see `web/README.md` "Old-TV compatibility" for
the full list:
- **ES modules never execute** pre-Chromium-61 → dashboard stuck in demo mode
  forever (WebSocket never opened). Fixed: all JS is ES5 classic scripts.
- **CSS Grid unsupported** pre-Chromium-57 → status grid/mini-gauges/banks
  scrambled. Fixed: flexbox + fixed margin gutters.
- A `setTimeout`-paced continuous scope redraw **flooded** the weak TV (backlog
  piles up faster than it paints, FPS collapsed to 3). Fixed by removing the
  continuous redraw entirely (parametric standing scope, redraws on state change).

## TV/kiosk performance rules (LOAD-BEARING)
The display browsers render **single-threaded in software**; a desktop dev browser
never reproduces the lag. Bench TV measured **8 FPS** on the original decorated
design. Keep all of these or it janks:
- Flat skin only — decorative paint is the #1 cost.
- **Stepped `steps()` animation timing** (spark/spray step-end opacity pops, CAN
  steps(16)) — smooth 60 Hz animation damages the screen every frame. Fans use a
  CSS `animation` (spin-up + steady) / `transition` (coast) pair instead, toggled
  by JS on status-bit change — no per-frame cost either way.
- Animated sprites on **cached layers** (`will-change: opacity`) + `contain: paint`
  on bank cells → static page rasterizes once, only sprites re-composite.
- **Canvas, not mutated SVG**, for the scope — and prefer **no continuous redraw
  loop** at all (state-change-driven) over even a cheap per-frame one; if a
  continuous loop is unavoidable, drive it with `requestAnimationFrame`, never
  `setTimeout` (a fixed interval floods a TV that can't keep up).
- Needles rotate as **element transforms** (own SVG), never a group inside the dial.
- **No per-frame `:root` CSS-var writes** (tempo quantized to 250-rpm buckets).
- **No `aspect-ratio` / `min()`** (unsupported on old TV Chromium; RPM bezel fixed 384×384).
- **No CSS Grid** (unsupported pre-Chromium-57) — flexbox + margin gutters only.
- Firmware serves HTML/JS/CSS **`no-cache`**, media long-cached (kiosk otherwise runs
  year-stale JS). New assets get new filenames (`logo_w`, `spark_lit`, `spray_gdi`,
  `imo2`, `swon2`) to beat the year cache.

**FPS history on the client's TV:** decorated 8 → paint-stripped 10 → flat skin
12–25 → +stepped/layer-isolation "so far so good." **2026-07-11 pass 1** (HD TV,
`1920×1070@1`): coarser stepped anims, 15 fps setTimeout scope, native-1080p fit
snap, fan redline cap → held ~12–30 (software-render floor). `will-change`
layer cuts were tried and **reverted** (per-event repaint cost more than the
promoted layers saved on this weak TV). **2026-07-11 pass 2 (universal-TV pass):**
testing on the client's *older* TV surfaced two breakages invisible on the newer
one — ES modules silently dead (stuck in permanent demo mode) and CSS Grid
scrambling the layout — fixed by going ES5 + flexbox (see "Universal old+new TV
target" above). That same pass added a smooth scope drift, which **flooded the
old TV via `setTimeout`** (FPS crashed to 3); fixed by dropping the scope to a
**parametric standing display with no continuous redraw loop** (state-change-
driven only). Old TV measured **FPS 9** / new TV **FPS 20** just before the
no-continuous-redraw fix — not yet re-measured after it. Confirm on both TVs
before calling this done; the mini-PC kiosk (D8) remains the fallback for a
guaranteed locked 60.

## Current state (works)
- Firmware **compiles clean** for esp32s3 (~73% app partition free), LittleFS
  storage 4 MB from `web/`.
- **SoftAP + LittleFS asset serving verified on device.**
- **Live data path DONE end-to-end in firmware SIM** (`firmware/ECU_Tester/main/main.c`):
  acq_task generates RPM/analog-mV/status/IAC; net_task integrates a crank-angle
  accumulator → latched firing bits + CKP 60-2 / CMP1 / CMP2 edge-lists, broadcasts
  TELEMETRY (~30 Hz) + WAVEFORM. Verified: WS 101, clean decode, idle rpm ~810,
  coils in 1-5-3-6-2-4 order.
- New dashboard committed + pushed; flashable image rebuilt.

## Punchlist — what's LEFT (real work)
1. **Real acquisition drivers** replacing the sim generators field-by-field at the
   bench: `i2c_bus` + `ads1115` (MAF/MAP/IAT/ECU-V) + `mcp23017` (status), `hc165`
   shift-chain (24 coil/injector activity bits), `ckp_capture` (RMT/GPTimer, 60-2
   decode + RPM) for CKP/CMP.
2. **Browser→server COMMAND handler** — firmware `ws_handler` currently only accepts
   the handshake; wire `0x80` COMMAND (+ `encodeCommand` in protocol.js) if manual
   sim controls are wanted back.
3. **Protocol v1 gaps** if desired: CTS, IGF, CURRENT fields (currently zeroed in UI).
4. **KiCad front-end board** (D4) — dividers/clamps/buffers, ADS1115, MCP23017,
   74HC165, MAX9926, Hall clamps (`hardware/README.md`).
5. Confirm real-TV FPS after latest flash; if short, remaining page-side dials are
   CAN scroll / fan step rate; else rely on mini-PC kiosk.

## PM / dashboard sync
`CLAUDE.md` imports a sync-block: after meaningful work, update `pm-sync.json` (git
fields only) and commit as `chore: sync dashboard`, then **Dashboard → Import →
pm-sync.json** (manual; only the user can do it). Note: the referenced `npm run sync`
script / pre-push hook do **not** exist in this repo — write `pm-sync.json` by hand
from `git log`. Auto-memory lives at
`~/.claude/projects/-home-ali-MyProjects-ECU-TESTER/memory/`
(`idf-build-env`, `tv-render-perf`).
