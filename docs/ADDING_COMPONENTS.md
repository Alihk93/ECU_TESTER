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
The spec's `.qml` names map to web components (QML doesn't run on the ESP32-S3):
`Gauge`, `VoltageMeter`, `Indicator`, `WaveformView`, `InjectorAnimation`, `CoilIndicator`.
1. Create `web/js/components/<Name>.js` exporting a component that takes `(mountEl, opts)`
   and exposes an `update(data)` method.
2. Mount it into its panel in `web/index.html` from `app.js`.
3. Feed it from the `onFrame` dispatcher in `app.js`.
Layout stays in CSS grid, so adding a gauge/indicator does not require redesigning the UI
(a spec requirement).

## Add a new manual control
Add a `cmd_id` to `docs/PROTOCOL.md` §5, handle it in the firmware WS handler, and emit it
from the controls panel via `encodeCommand()`.
