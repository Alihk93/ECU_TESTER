# ECU TESTER Dashboard — AL-AYED

The device dashboard, served by the ESP32-S3 from LittleFS at `http://10.10.10.10`
(SoftAP `ECU_TESTER`). Plain HTML/CSS/JS — no build step, fully offline
(fonts and images are self-hosted). Designed on a 1920 × 1080 canvas, scales to
fill any window — crisp on a 4K TV in Chromium kiosk mode.

## Files

```
index.html        — page structure (top bar, status grid, scope, gauges, banks)
style.css         — visual design + animation keyframes + live-mode gating
app.js            — rendering + the ECU display API (view layer)
js/live.js        — device link: decoded frames -> ECU API calls
js/websocket.js   — WS connection management, auto-reconnect
js/protocol.js    — binary frame decoder (docs/PROTOCOL.md — the contract)
assets/img|fonts  — component photos, Saira + DSEG7 fonts
```

`js/protocol.js` must stay byte-for-byte in lockstep with `protocol.h` and
`docs/PROTOCOL.md` (see CLAUDE.md §5 — change all three in one commit).

## Performance meter

`js/diag.js` shows a corner meter — **FPS** (real redraw rate) · **WS/s**
(protocol frames received, ~90 healthy) · **age** (ms since last telemetry;
climbing = stream stalled, not the renderer) · **res** (viewport × dpr, e.g.
`1920×1080@1`). Tap it or press **D** to hide/show; it's on by default, so add
`?diag=0` to disable it for a production kiosk. The flat skin is the only design.

## Demo vs live

The page free-runs as a **demo** (everything animating, RPM-scaled tempo) until
the first valid device frame arrives. Then it enters **live mode** (`.is-live`):

- coils / injectors / GDI animate only while their latched activity bit is set
- fans spin only while FAN1/FAN2 status bits are on (speed still RPM-scaled)
- IMO / HIP cells and the relay indicators light from status bits
- needle wobble stops — needles follow real 30 Hz telemetry
- CKP / CMP1 / CMP2 render real WAVEFORM edge-lists (60-2 gap, cam sync);
  CAN HI/LO stay decorative (no CAN channel in protocol v1)

## Signal map (protocol v1)

| UI | source |
|----|--------|
| DEVICE / UPTIME | WS link state; uptime restarts on connect |
| VOLTAGE | `ecu_v` mV ÷ 1000 |
| RPM dial + reading | `rpm` |
| MAF · MAP · IAT · 5V mini gauges | `maf` · `map` · `iat` · `sensor_v` (mV ÷ 1000, raw pin volts) |
| CTS · IGF mini gauges, CURRENT | **no protocol field yet** — zeroed in live mode |
| FAN1 / FAN2 / IMO / HIP cells | status bits `fan1` / `fan2` / `immoP\|immoN` / `fuelPump` |
| BAT-ON · SW-ON · MRC± · ETC-ON · ST-OFF · PFC-OFF | status bits (PFC-OFF and HIP both show the Fuel Pump bit) |
| IAC phase LEDs | `iac` bits 0–3 |
| COIL / INJ / GDI 1–8 | latched activity bytes (6-cyl firmware fires 1–6; 7–8 idle) |
| Scope CKP / CMP1 / CMP2 | WAVEFORM edge-lists (mode 1) |

## TV / kiosk performance rules

The display device is often a TV-class browser (old Chromium, weak SoC, often
software-composited and single-threaded). Hard rules learned on real hardware —
keep them or the dashboard visibly janks:

- **The flat marine skin IS the design** (client decision 2026-07-10 after
  measuring 8 FPS on the bench TV): one unified navy background (`--navy`),
  flat colors and 1px borders only — no gradients, box-shadows, text-shadows,
  glows or filters anywhere, no ghost digits behind the 7-seg readouts. Do not
  reintroduce decorative paint.

- **Never write `:root` CSS variables per frame** — it invalidates style for
  the whole document. The RPM-driven tempo vars are quantized to 250-rpm
  buckets (`updateTempo`).
- **Continuous animations must be compositor-only**: `transform`/`opacity` on
  HTML elements. Transforms *inside* an SVG repaint per frame (the CAN scroll
  animates the `<svg>` element itself), and CSS `filter:` on an animating layer
  costs per-frame filtering (spark/GDI brightness is baked into the PNGs).
- **The scope is a single `<canvas>`, never mutated SVG paths** — SVG path
  rewriting is the priciest repeated paint on these renderers. It strokes at
  ~15 fps on a setTimeout tick (not a 60 Hz rAF) with a wall-clock-advanced
  window (smooth scroll, no per-batch jumps) and a slow fallback for rAF stalls.
- **Needles rotate as element transforms** (`#rpm-needle`, `.mg-needle-rot`
  overlay SVGs) — compositor-only; rotating a group *inside* an SVG repaints
  the whole dial per frame.
- **No blanket `will-change`** — two dozen permanently promoted layers exhaust
  TV GPU memory; elements self-promote while actually animating.
- **Continuous animations use `steps()` timing** (fans 16/rev, sparks + sprays
  step-end opacity pops, CAN 16/cycle) — a smooth 60 Hz animation damages the
  screen every frame on a software renderer; stepped reads the same, costs far less.
- **Animated sprites own cached layers** (`will-change: opacity` on spark/spray,
  `contain: paint` on bank cells): the static page rasterizes once; per frame
  only the few animated bitmaps get composited.
- **Skip DOM writes when the displayed value is unchanged** (needles, 7-seg
  readouts).
- **Watchdog reconnect** (`live.js`): TV Wi-Fi power save can kill the TCP
  stream silently; stale telemetry (> 2.5 s) forces a reconnect instead of a
  frozen dashboard.
- **No `aspect-ratio` / `min()`** — unsupported on TV browsers (Chromium ≤ 87);
  the RPM bezel is a fixed 384×384 px in the 1920×1080 canvas.
- **Stage fitting**: a `transform: scale()` on the stage makes a software
  compositor resample the whole screen every frame (measured 5–7 FPS on a TV).
  `fitStage()` snaps to identity within 2% of 1080p **in both fit modes** (so a
  1920×1070 TV renders pixel-native, not at 0.99×); `?fit=zoom` (default) scales
  by layout (Blink `zoom`) — paints once at native size.

## Develop without hardware

```sh
python3 tools/sim_server.py        # serves web/ + speaks the binary protocol
# -> http://localhost:8090         (same generators as the firmware sim)
```

Or point any served copy at real hardware with `?device=10.10.10.10`.

## Deploy

`idf.py build flash` packs `web/` into the LittleFS image and flashes it with
the app (see docs/SETUP.md). HTML/JS/CSS are served `no-cache` so a reflash
shows immediately; images/fonts cache for a year.
