# ECU_TESTER — Architecture Guide

## 1. System topology

The ESP32-S3 is the **acquisition engine + web server**. The dashboard renders in a
browser on a separate display device. The ESP32-S3 has **no display output of its own** —
it does not "run the UI"; it serves it.

```
  ECU under test / bench harness
        │  raw automotive levels (12 V+, VR AC, flyback)
        ▼
  ┌─────────────────────────┐   analog conditioning + digital opto/comparator
  │  Signal-conditioning FE  │   (see hardware/ — dividers, clamps, protection)
  └─────────────────────────┘
        │  safe 0–3.3 V
        ▼
  ┌───────────────────────────────────────────┐
  │  ESP32-S3-WROOM-1 N16R8                     │
  │                                            │      Wi-Fi SoftAP 10.10.10.10
  │  CORE 1  acquisition task                  │  ◀──────────────────────────────▶
  │    • ADC1 oneshot + averaging (5 analog)   │      WebSocket, binary + CRC
  │    • edge capture (coils/inj, CKP/CMP)     │   telemetry 30–60 Hz  ──▶  Browser
  │    • waveform ring buffers                 │   waveform blocks     ──▶  (display
  │        │ xQueueOverwrite / SPSC ring       │   commands (sim)      ◀──   device)
  │  CORE 0  network task + esp_http_server    │
  │    • pack frames, CRC                      │
  │    • broadcast to all WS clients           │
  │    • serve web assets from LittleFS        │
  └───────────────────────────────────────────┘
```

## 2. Firmware task architecture

Two tasks, deliberately split across cores so acquisition timing isn't perturbed by the
Wi-Fi/LWIP stack (which lives on core 0):

| task | core | priority | responsibility |
|------|-----:|---------:|----------------|
| `acq_task` | 1 | high | sample ADC1, capture digital edges, fill waveform ring buffers, publish a latest-state snapshot |
| `net_task` | 0 | medium | drain snapshot + waveform buffers, build+CRC frames, broadcast to WS clients |
| (`httpd`) | 0 | — | `esp_http_server` internal — serves assets + owns the WS sockets |

**Shared state, lock-free (carried from S-ECU):**
- **Scalar/state snapshot** → a length-1 FreeRTOS queue with `xQueueOverwrite` (producer,
  `acq_task`) / `xQueuePeek` (consumer, `net_task`). Always-latest, no mutex, no priority
  inversion.
- **Waveforms** → a single-producer/single-consumer ring buffer per channel. `acq_task`
  writes samples/edges; `net_task` reads windows out. Ring depth sized to cover jitter
  between the acquisition rate and the send rate.

**ISR rules:** edge capture and the acquisition timer run in ISR context — no blocking
calls, only `*FromISR` APIs, minimal work (timestamp + push), defer the rest to `acq_task`.

## 3. The two-tier data path (the core performance idea)

Acquisition is fast; the wire is slow-by-design. **Raw 10 kHz × N channels is never sent
to the browser** — a browser can neither receive nor render that, and the Wi-Fi stack would
choke.

- **Analog + digital state:** sampled fast, published as a latest snapshot, sent as one
  `TELEMETRY` frame at **30–60 Hz**. Human eyes and gauges don't need more.
- **Coil/injector activity:** a firing pulse can be shorter than a frame interval, so the
  bits are **latched since the last frame** (set-on-edge, cleared-after-send) — never
  aliased away. Dwell/pulse-width measurement, if needed (D3), rides a separate field set.
- **Waveforms (CKP/CMP):** the ISR captures at full rate into ring buffers; `net_task`
  sends **decimated windows** (scope look) and/or **edge lists** (exact tooth timing) as
  `WAVEFORM` blocks. See `docs/PROTOCOL.md` §4 and open decision D6.

This keeps latency low and the radio uncongested while preserving the timing fidelity that
matters (missing-tooth, cam sync).

## 4. Asset delivery — LittleFS (a deliberate divergence from S-ECU)

S-ECU embedded a small gzipped UI directly in the app binary via `EMBED_FILES`. ECU_TESTER
uses a **LittleFS partition** instead.

| approach | pros | cons |
|----------|------|------|
| `EMBED_FILES` (S-ECU) | one binary, fastest read, no FS mount | UI change = recompile+reflash app; large assets bloat the app image; no browser caching story |
| **LittleFS (chosen)** | assets separate from app; update UI without touching firmware; serve with `Cache-Control` so the browser caches heavy files (photo, SVG) after first load | needs a partition + image build step; slightly slower first read |

**Why LittleFS here:** the dashboard is large and may include a real photo (per the spec's
"Local Web page" section). Browser caching + independent UI updates matter at this size.
Serve **media** (images/fonts) with `Cache-Control: public, max-age=31536000` so heavy files
load once and every later interaction is pure WebSocket traffic; serve **code**
(HTML/JS/CSS/JSON) with `no-cache` so a reflashed UI shows immediately — a kiosk browser
otherwise keeps running year-stale JS after a web-partition update. Set flash to
**QIO @ 80 MHz** for read speed (`sdkconfig.defaults`).

## 5. Web/UI architecture

- **Landscape, fullscreen**, runs in a Chromium kiosk on the display device (D8). Avoid IE.
- **Modular components.** The spec's `.qml` examples (Gauge, VoltageMeter, Indicator,
  WaveformView, InjectorAnimation, CoilIndicator) map to **web components / JS modules** —
  QML/Qt does **not** run on the ESP32-S3, so those names are design intent, not files to
  build literally. Each component takes a data source and renders itself, so new gauges/
  indicators are added without touching layout (see `docs/ADDING_COMPONENTS.md`).
- **No heavy raster in the hot path.** Gauges/dials are SVG (scales to 4K, few KB); the
  oscilloscope is an HTML5 `<canvas>`; a background photo (if used) is cached once.
- **Data flow:** `websocket.js` owns the connection; `protocol.js` decodes binary frames
  into JS objects; `app.js` fans data out to components and sends `COMMAND`/`SUBSCRIBE`.

## 6. Constraints to respect (see OPEN_DECISIONS.md)

- **GPIO budget (D7):** ~30 digital inputs + 5 ADC + 3 high-speed + I2C/UART exceed usable
  GPIO on N16R8 (octal PSRAM consumes pins). Plan I/O expansion for slow status lines;
  fast edge capture can't route through a slow I2C expander.
- **Input voltage (D4):** every ECU line needs conditioning to 0–3.3 V with protection.
  Nothing connects to a bare GPIO.
- **ADC1 only:** ADC2 is dead while Wi-Fi runs. Five analog channels fit ADC1.
- **AP IP:** 10.10.10.10 is non-default; set the AP netif IP + DHCP pool explicitly.

## 7. Simulation mode

The dashboard must run with **no hardware attached**. Two places sim can live (D-dependent):
- **Firmware sim** — ESP32 generates realistic telemetry/waveforms and pushes them through
  the *real* protocol path. Best for exercising the end-to-end pipeline and the wire format.
- **Front-end sim** — pure JS fakes the data with no device at all. Best for pure UI demos.

**Recommendation:** support both behind a flag. Manual controls (sliders/toggles) send
`COMMAND` packets that drive the firmware sim; a JS-only fallback lets the UI demo without a
board. See `docs/SIMULATION.md`.
