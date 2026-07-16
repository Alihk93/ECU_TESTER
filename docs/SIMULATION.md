# ECU_TESTER — Simulation Mode Guide

The dashboard must run **with no hardware attached** (demos, dev, testing). Two layers:

## Firmware simulation (recommended default)
The ESP32 generates realistic values and pushes them through the **real** protocol
path — so sim also validates the wire format and the full pipeline.
- Manual controls in the dashboard send `COMMAND` packets (docs/PROTOCOL.md §5) that
  drive the sim generators (MAF/MAP/IAT/voltages/RPM, coil/injector activity, CKP/CMP).
- Realistic behavior to model: gear-model RPM, load-based values, a synthetic CKP with
  the chosen missing-tooth pattern (D5), cam sync on CMP.
- Toggle sim vs live via a build flag or a runtime `SUBSCRIBE`/`COMMAND`.

## Front-end simulation (fallback)
Pure JS fakes the data with no device at all — for showing the UI anywhere.
- `web/app.js` has a hook: if the socket never connects, a JS generator drives the
  same component handlers.

## What sim must cover (from the spec)
Gauges, voltage readings, current, coil spark, injector spray, CKP/CMP waveforms
(PWM-like), and all status indicators.
