# CLAUDE.md — ECU_TESTER

> Source of truth for this repo. Read this first, every session.
> If something here conflicts with a chat message, ask before diverging.

---

## 1. What this project is

**ECU_TESTER** is a real-time hardware-in-the-loop **car-ECU bench tester**. An
**ESP32-S3** acquires ~50 analog + digital automotive signals (and crank/cam
waveforms) in real time, and serves a **professional local web dashboard** over
its own Wi-Fi. The dashboard renders in a browser on an external display
(monitor/PC/TV), connected to the device's access point. It must also run a
full **simulation mode** with no hardware attached, for demos and development.

**Client / brand:** AL-AYED.

**Lineage (conceptual only — D0 = clean start):** the earlier proof-of-concept
**S-ECU** (`https://github.com/Alihk93/S-ECU`) validated the transport approach
(SoftAP + `esp_http_server` WebSocket, dual-core FreeRTOS, ADC1-only, lock-free
length-1 queue). ECU_TESTER **reuses those _patterns_ but is written from scratch** —
no S-ECU code or SVG art is copied in. On top of the shared transport it adds a
documented **packed-binary + CRC** protocol, a CKP/CMP **waveform oscilloscope**, and
a browser→server **command channel** for the simulation controls.

---

## 2. Hardware & environment

- **MCU / module:** ESP32-S3-WROOM-1 **N16R8** (16 MB flash, 8 MB octal PSRAM).
- **Framework:** ESP-IDF **v5.5.x** (pinned locally to 5.5.2).
- **OS / IDE:** Ubuntu · VS Code · Claude Code · GitHub · KiCad (PCB).
- **Network:** SoftAP. SSID **`ECU_TESTER`**, password **`00000000`** (WPA2-PSK — 8
  chars, the WPA2 minimum), IP **10.10.10.10** (non-default; firmware sets the AP
  netif IP explicitly — default SoftAP would be 192.168.4.1).
- **Display client (D8 = a):** Chromium in **kiosk mode on a mini-PC**
  (`chromium --kiosk --app=http://10.10.10.10`, autostarted by the OS). The
  ESP32-S3 has **no display output**; it is the *server*, not the renderer, so
  "boot into dashboard" is a property of the display device, not the firmware.
  (Avoid IE — its WebSocket support is effectively dead.)

---

## 3. Architecture — locked decisions

These are settled and carried forward from S-ECU unless a decision below reopens one.

- **SoftAP** at `10.10.10.10`, multiple simultaneous viewers supported.
- **`esp_http_server` serves the UI *and* hosts the WebSocket.** There is **no**
  standalone `esp_websocket_server` component — enable `CONFIG_HTTPD_WS_SUPPORT=y`
  and register the URI with `.is_websocket = true`, use the `httpd_ws_*` API.
- **Bidirectional WebSocket.** Server→browser telemetry *and* browser→server
  commands (the manual sim controls). This is new vs S-ECU.
- **FreeRTOS, dual-core:**
  - **Acquisition task pinned to core 1** — ADC oneshot + averaging, GPIO/timer
    edge capture, waveform ring buffers. Kept off the Wi-Fi core.
  - **Network/telemetry task on core 0** — packs frames, broadcasts to every live
    client via `httpd_get_client_list()` + `httpd_queue_work()`.
- **Lock-free shared state:** length-1 queue with `xQueueOverwrite` (producer) /
  `xQueuePeek` (consumer) — always-latest snapshot, no mutex. Waveforms use a
  separate SPSC ring buffer (see ARCHITECTURE.md).
- **Analog acquisition (D4=b, D7):** 5 analog channels — MAF, MAP, IAT, ECU-V,
  Sensor-V. Four precision channels (MAP, MAF, IAT, ECU-V) go through an external
  **ADS1115** (16-bit, I²C) for clean, monotonic gauge readings; the coarse generic
  **Sensor-V** meter uses one **internal ADC1** channel (oversampled). ADC2 stays
  unused (dead while Wi-Fi is active).
- **Digital acquisition (D3 = activity-only, D7):**
  - **Status lines (~14):** Battery, Switch, Start, ETC, Fan1/2, Fuel Pump, IMMO±,
    MRC±, IAC → **MCP23017** I²C expander (16 ch), polled ~50–100 Hz (these are slow).
  - **Coil ×8 + injector-regular ×8 + injector-GDI ×8 = 24 activity lines** → a
    **74HC165** parallel-in shift-register chain (3× 8-bit) read over SPI. Firmware
    latches "fired since last frame" per channel. **No dwell/pulse-width timing** —
    D3 is activity-only, so no per-channel capture hardware is needed.
- **High-speed capture — CKP/CMP1/CMP2 (D5/D6):** three dedicated GPIOs fed from the
  conditioning front-end (clean 3.3 V edges). **CKP = VR, 60-2** via a **MAX9926**
  adaptive-threshold interface; **CMP1/CMP2 = Hall** via divider+clamp (each input
  jumperable to accept the other type). Edge timing via RMT / GPTimer input capture →
  RPM + the scope trace.
- **Asset storage: LittleFS partition** (not `EMBED_FILES`). The dashboard is large
  and may include a photo; LittleFS lets the browser cache assets and lets us update
  the UI without reflashing the app. This diverges from S-ECU on purpose — see
  ARCHITECTURE.md §"Asset delivery" for the tradeoff.
- **Protocol: packed binary, little-endian, fixed layout, CRC16-CCITT, sequence
  numbers.** Fully specified in [`docs/PROTOCOL.md`](docs/PROTOCOL.md). This replaces
  S-ECU's JSON because of the data volume (50 signals + waveforms).

### Two-tier data path (important)
You **cannot** stream 10 kHz × N channels of raw samples to a browser. Acquisition
runs fast on-device; the wire runs at a display-appropriate rate:
- **Telemetry frames** (analog + packed digital state) at ~**30–60 Hz**.
- **Waveform blocks** (CKP/CMP) sent as decimated windows and/or edge lists, not raw
  10 kHz streams. See ARCHITECTURE.md §"Waveform path" and open decision D6.
- Coil/injector "firing" bits are **latched since last frame** so short pulses are
  never missed between frames.

---

## 4. Decisions — RESOLVED (full log in [`OPEN_DECISIONS.md`](OPEN_DECISIONS.md))

All gating questions D0–D8 answered **2026-07-02**:

- **D0 clean start.** Written from scratch; no S-ECU code/art reuse (patterns only).
- **D1 AP.** SSID `ECU_TESTER`, password `00000000`, WPA2-PSK.
- **D2 monitor-only.** Read ECU outputs; no signal generation into the ECU. Manual
  controls exist only in simulation mode.
- **D3 activity-only** for coils/injectors (latched bit per channel; no timing).
- **D4 design the front-end here** (this KiCad project) — dividers/clamps/buffers,
  ADS1115, MCP23017, 74HC165, MAX9926, Hall clamps. See `hardware/README.md`.
- **D5 CKP = VR 60-2; CMP1/CMP2 = Hall** (inputs jumperable to either type).
- **D6 waveform = edge-list** primary (exact, tiny; ideal for missing-tooth), plus a
  coarse decimated envelope for the analog look; triggered single-shot deferred.
- **D7 expansion:** MCP23017 (slow status) + 74HC165 (coil/injector activity) +
  ADS1115 (precision analog). No capture-front-end MCU (D3 = activity-only).
- **D8 display = Chromium kiosk on a mini-PC.**

**Next build targets (for Claude Code):** `i2c_bus` + `ads1115` + `mcp23017` drivers,
`hc165` shift-chain reader, `ckp_capture` (RMT/GPTimer) with 60-2 decode + RPM,
`ws_server` (telemetry packer + command handler), then the fresh web dashboard.
**Start with [`docs/M1_VERTICAL_SLICE.md`](docs/M1_VERTICAL_SLICE.md)** — a thin
end-to-end slice (one ADS1115 channel → gauge) that validates the wire contract on
hardware before scaling up.

---

## 5. Repo layout

```
ECU_TESTER/
├── CLAUDE.md              ← you are here
├── README.md             GitHub-facing overview
├── OPEN_DECISIONS.md     gating questions + status (punchlist)
├── docs/
│   ├── PROTOCOL.md        binary packet spec (the firmware↔web contract)
│   ├── ARCHITECTURE.md    system + firmware + web architecture, data-path tiers
│   ├── SETUP.md           build / flash / deploy
│   ├── SIMULATION.md      simulation-mode guide
│   └── ADDING_COMPONENTS.md   how to add gauges / sensors / indicators
├── firmware/
│   └── ECU_Tester/        ESP-IDF v5.5.x project (run idf.py from here)
│       ├── CMakeLists.txt
│       ├── sdkconfig.defaults
│       ├── partitions.csv
│       └── main/
│           ├── CMakeLists.txt
│           ├── idf_component.yml   managed deps (joltwallet/littlefs)
│           ├── main.c         dual-core task skeleton (TODOs, no committed pinmap)
│           └── include/
│               ├── board_config.h   committed pinmap + AP creds (mirrors hardware/README.md)
│               └── protocol.h       packed structs + CRC — firmware side of the contract
├── web/                   local dashboard (served from LittleFS)
│   ├── index.html         landscape/fullscreen shell
│   ├── css/style.css
│   ├── js/protocol.js     DataView decoder — web side of the contract
│   ├── js/websocket.js    connection management
│   ├── js/app.js          bootstrap + component wiring
│   └── assets/            SVG art, photo(s)
└── hardware/
    └── README.md          KiCad structure, signal inventory, front-end requirements
```

**Contract invariant:** `firmware/ECU_Tester/main/include/protocol.h` and `web/js/protocol.js`
**must stay byte-for-byte in lockstep** with `docs/PROTOCOL.md`. Change all three
together, in the same commit, or the dashboard silently decodes garbage.

---

## 6. Build / flash / run

```bash
# firmware  (managed dep joltwallet/littlefs is fetched automatically on configure)
cd firmware/ECU_Tester
idf.py set-target esp32s3      # once; a bare first build would default to esp32
idf.py build

# flash the app
idf.py -p /dev/ttyUSB0 flash monitor

# build + flash the web assets into the LittleFS partition (see docs/SETUP.md)
# (littlefs image is generated from web/ and flashed to the `storage` partition)
```

Connect the display device to Wi-Fi SSID `ECU_TESTER` (password `00000000`), open `http://10.10.10.10`.

> **Do not** silently retune `sdkconfig.defaults`, edit `partitions.csv`, change
> `CMakeLists.txt`, or remap pins unless the task requires it — these hit every
> build and flash. Call it out first.

---

## 7. House style & working rules (Karpathy guidelines)

Applies to every change in this repo:

1. **Think before coding.** State assumptions explicitly; if multiple readings
   exist, present them — don't pick silently. If a simpler approach exists, say so.
2. **Simplicity first.** Minimum code that solves the task. No speculative
   abstractions, no HAL wrapper around one `gpio_set_level`. Check the `esp_err_t`
   returns that actually fail; skip guards for impossible cases.
3. **Surgical changes.** Touch only what the task needs. Match existing style. Don't
   refactor working code. Remove only orphans *your* change created.
4. **Goal-driven.** Define the pass/fail check up front (an `ESP_LOGx` line, a pin
   state, a scope trace, a heap watermark, a host unit test), then loop
   **build → flash → observe that signal → done**.
5. **Presentation:** reasoning step by step; code changes as VS Code-style diffs with
   `+`/`−`; flag every assumption; on any tradeoff, give options **and** a recommendation.

For ISR context: no blocking calls, use the `*FromISR` APIs. State task priority,
stack size, and core affinity whenever you add a task.

---

## 8. Status

Scaffold created; **all decisions D0–D8 resolved (2026-07-02)**. Front-end specified in
`hardware/README.md`; protocol signal set + scaling locked in `docs/PROTOCOL.md`.

**Build:** the firmware **compiles clean for `esp32s3`** on ESP-IDF v5.5.2 (`idf.py
build` green; app ≈ 0x56e10 bytes, ~89% of the app partition free). The LittleFS
managed component (`joltwallet/littlefs`, resolved 1.22.1) is wired via
`main/idf_component.yml`. `board_config.h` now carries the resolved D1 AP credentials
(`ECU_TESTER` / `00000000`) **and the committed GPIO pinmap** — the firmware mirror of
the `hardware/README.md` GPIO map (Sensor-V ADC1 GPIO4; CKP/CMP1/CMP2 = 5/6/7; I²C
SDA 47 / SCL 21 for ADS1115+MCP23017; 74HC165 QH/CLK/LD = 16/17/18).

**Bench (first hardware bring-up):** the SoftAP path is implemented and
**verified on the device** — `wifi_softap_start()` brings up `esp_netif` + the default
event loop + Wi-Fi AP (`ECU_TESTER` / WPA2) at `10.10.10.10`, and the board boots
cleanly and stays up. (Earlier the stub skipped `esp_netif_init()`, so `httpd_start()`
hit lwIP with no TCP/IP task and the chip reboot-looped on an "Invalid mbox" assert.)

**Still open (not a scaffold gap — real work):** the acquisition path (`acq_task` +
ADC/expander/capture drivers) and asset serving (`littlefs_mount` + `static_get_handler`)
are still `TODO` stubs, and the web dashboard is a work in progress. The WebSocket
telemetry/command handlers are not wired to real data yet.
