# ECU_TESTER — Adding Sensors & UI Components

Two independent axes: a **data field** (firmware + protocol) and a **UI component**
(web). Keeping them decoupled is why the protocol is fixed-layout and the UI is modular.

## Add a new telemetry field
1. Add the field to `ecu_telemetry_t` in `firmware/main/include/protocol.h`.
2. Mirror it in `decodeTelemetry()` in `web/js/protocol.js` at the **same offset**.
3. Update `docs/PROTOCOL.md` §3 (offset table + scaling). Bump `version` if the layout
   change is not backward-compatible.
4. Populate it in `acq_task` (`firmware/main/main.c`).
**All in one commit** — the three must never drift (CLAUDE.md "contract invariant").

## Add a new UI component
The dashboard is a fixed 1920×1080 page: static markup in `web/index.html`,
`web/app.js` as the view layer (builds the dynamic DOM, exposes the global `ECU`
display API), and `web/js/live.js` as the only bridge from protocol frames to that API.
1. Add the element's markup in `index.html` (or a build function in `app.js` if it is
   repeated, like the banks/gauges), styled flat per the marine skin.
2. Add a setter on the `ECU` object in `app.js` that updates the cached element —
   skip the DOM write when the displayed value is unchanged.
3. Call the setter from `applyTelemetry()` in `web/js/live.js`.
Respect the TV/kiosk performance rules in `web/README.md` (flat colors only, stepped
animation timing, no per-frame `:root` writes, canvas — not SVG — for anything that
redraws continuously).

## Add a new manual control
Add a `cmd_id` to `docs/PROTOCOL.md` §5, handle it in the firmware WS handler, and emit it
from the controls panel via `encodeCommand()`.
