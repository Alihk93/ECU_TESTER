# ECU TESTER Dashboard — AL-AYED

The device dashboard, served by the ESP32-S3 from LittleFS at `http://10.10.10.10`
(SoftAP `ECU_TESTER`). Plain HTML/CSS/JS — no build step, fully offline
(fonts and images are self-hosted). Designed on a 1920 × 1080 canvas, scales to
fill any window — crisp on a 4K TV in Chromium kiosk mode.

**Universal target: old AND new TV browsers.** All `web/*.js` is **ES5 classic
scripts** (no `<script type="module">`, no `const`/`let`/`class`/arrow functions/
template literals/`URLSearchParams`/`padStart`/`toLocaleString`) and the layout is
**flexbox + margins, not CSS Grid** — both ES modules and CSS Grid are unsupported
on older TV-class Chromium (< 61 / < 57) and were confirmed on the client's bench
TV to break silently: modules never ran (dashboard stuck in demo mode, no
WebSocket) and Grid scrambled the gauge/bank layout. See "Old-TV compatibility"
below before adding any modern JS/CSS syntax.

## Files

```
index.html        — page structure (top bar, status grid, scope, gauges, banks)
style.css         — visual design + animation keyframes + live-mode gating
app.js            — rendering + the ECU display API (view layer) + ECUqs() helper
js/protocol.js    — binary frame decoder (docs/PROTOCOL.md — the contract); exposes window.ECUProto
js/websocket.js   — WS connection management, auto-reconnect; exposes window.EcuSocket
js/live.js        — device link: decoded frames -> ECU API calls
js/diag.js        — corner FPS/WS/age/res meter
assets/img|fonts  — component photos, Saira + DSEG7 fonts
```

All five scripts are loaded as plain `<script>` tags (not modules) in
`index.html`, in that order — `protocol.js`/`websocket.js` publish globals
instead of `export`ing, and `live.js`/`diag.js` consume them off `window`.

`js/protocol.js` must stay byte-for-byte in lockstep with `protocol.h` and
`docs/PROTOCOL.md` (see CLAUDE.md §5 — change all three in one commit).

## Old-TV compatibility

The client's display fleet includes TVs as old as ~Chromium 49–60. Confirmed
breakages and their fixes:

- **ES modules never execute** on Chromium < 61 — `<script type="module">`
  silently no-ops, so a dashboard built on modules never opens its WebSocket
  (looks like a permanently-stuck demo, `WS --/s` on the meter forever). Fix:
  ES5 classic scripts only, globals instead of import/export.
- **CSS Grid is unsupported** on Chromium < 57 — grid cells collapse/reorder
  unpredictably instead of erroring. Fix: flexbox + fixed `margin` gutters
  (`flex-wrap: wrap` + per-cell `width: calc(N% - gutter)` + `:nth-child` margin
  resets) for the status grid, mini-gauges and the coil/inj/GDI banks.
- **No `URLSearchParams`, `String.padStart`, `Number.prototype.toLocaleString`**
  — replaced with `window.ECUqs(name)` (hand-rolled query parser, set by
  app.js) and manual zero-padding / thousands-comma formatting.
- **`setTimeout`-paced continuous redraws can flood a weak TV** — a fixed-
  interval loop keeps firing even when the TV can't keep up, and the backlog
  collapses the whole page (measured FPS 3). Any continuous redraw loop must be
  driven by `requestAnimationFrame`, which self-throttles to what the device can
  actually paint (or — the choice made for the scope — avoid a continuous loop
  entirely; see below).

Whenever you add JS or CSS, grep for regressions before committing:
`grep -nE '\b(const|let)[[:space:]]|\bclass[[:space:]]|=>' web/*.js web/js/*.js`
(want zero real hits) and avoid `display: grid` in `style.css`.

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
- fans: **both spin clockwise** at a constant speed, gated on/off by the
  FAN1/FAN2 status bits (`setFanRunning()` in app.js) — turning on eases up to
  speed over ~1 s, turning off coasts to a stop via a CSS `transition` (captures
  the live angle, then transitions it forward); no more RPM-scaled speed or
  reverse rotation on fan2
- IMO / HIP cells and the relay indicators light from status bits
- needle wobble stops — needles follow real 30 Hz telemetry
- CKP / CMP1 / CMP2 are a **parametric standing display**, not a plot of the
  WAVEFORM edge-lists: three clean square waves (distinct per-lane frequency/
  duty/amplitude) whose frequency tracks RPM, redrawn only when the RPM bucket
  changes — i.e. it doesn't animate continuously. WAVEFORM frames still arrive
  over the wire (protocol unchanged) but are decoded minimally and not plotted
  (`ECU.feedWaveform` is a no-op). CAN HI/LO stay decorative, standing waves
  with a slow CSS scroll (no CAN channel in protocol v1).

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
| Scope CKP / CMP1 / CMP2 | parametric standing square waves, frequency driven by `rpm` (not a plot of WAVEFORM data) |

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
  rewriting is the priciest repeated paint on these renderers. It's a
  **parametric standing display**: `drawScope()` runs once at boot and again
  only when `scopeTick()` sees the RPM-derived frequency bucket change — no
  continuous per-frame redraw loop at all (the previous scrolling/edge-list
  version cost the most FPS of anything on the page; removing continuous
  redraw, not just cheapening it, is what fixed the weak-TV framerate).
- **Needles rotate as element transforms** (`#rpm-needle`, `.mg-needle-rot`
  overlay SVGs) — compositor-only; rotating a group *inside* an SVG repaints
  the whole dial per frame.
- **No blanket `will-change`** — two dozen permanently promoted layers exhaust
  TV GPU memory; elements self-promote while actually animating.
- **Continuous animations use `steps()` timing** (sparks + sprays step-end
  opacity pops, CAN 16/cycle scroll) — a smooth 60 Hz animation damages the
  screen every frame on a software renderer; stepped reads the same, costs far
  less. Fans are the exception: `setFanRunning()` toggles `fan-run`/`fan-coast`
  classes so a CSS `animation`(ease-in spin-up + linear steady spin) and a CSS
  `transition` (coast-down) drive them — no per-frame JS either way.
- **Any continuous redraw loop must use `requestAnimationFrame`, never
  `setTimeout`** — a `setTimeout` loop keeps firing on its fixed interval even
  when a weak TV can't keep up, and the backlog collapses the whole page
  (measured FPS 3 on the client's TV with a `setTimeout(33)` scope-scroll loop).
  rAF self-throttles to what the device can actually paint. Best of all is no
  continuous loop — the scope now redraws only on a state change (see above).
- **Animated sprites own cached layers** (`will-change: opacity` on spark/spray,
  `contain: paint` on bank cells): the static page rasterizes once; per frame
  only the few animated bitmaps get composited.
- **Skip DOM writes when the displayed value is unchanged** (needles, 7-seg
  readouts).
- **Watchdog reconnect** (`live.js`): TV Wi-Fi power save can kill the TCP
  stream silently; stale telemetry (> 2.5 s) forces a reconnect instead of a
  frozen dashboard.
- **Waveform frames are decoded minimally, not plotted** (`protocol.js`
  `decodeWaveform` only reads the mode byte) — the scope is parametric now, so
  fully decoding thousands of edges/s into typed arrays was pure GC churn with
  no visual payoff.
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
