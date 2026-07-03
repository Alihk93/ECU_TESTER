<h1 align="center">ECU_TESTER</h1>

<p align="center">
  <b>Real-time hardware-in-the-loop ECU bench tester on ESP32-S3.</b><br>
  Acquires ~50 automotive signals + crank/cam waveforms and serves a professional
  local web dashboard over its own Wi-Fi — with a full no-hardware simulation mode.
</p>

<p align="center">
  <img alt="MCU" src="https://img.shields.io/badge/MCU-ESP32--S3--WROOM--1%20N16R8-informational">
  <img alt="Framework" src="https://img.shields.io/badge/ESP--IDF-v5.5.x-blue">
  <img alt="UI" src="https://img.shields.io/badge/UI-Local%20Web%20%2F%20WebSocket-orange">
  <img alt="Status" src="https://img.shields.io/badge/status-skeleton%20builds%20clean-brightgreen">
</p>

---

## What it does

An **ESP32-S3** reads a car ECU's live signals — analog sensors, coil/injector
activity, and crank/cam position waveforms — and streams them to a browser-based
dashboard (gauges, voltage meters, coil/injector indicators, and a real-time
oscilloscope). The device is its own Wi-Fi access point and web server; the
dashboard runs on any external display connected to it. It also runs a complete
**simulation mode** with no hardware attached, for demos, development, and testing.

This is the production-grade evolution of the [S-ECU](https://github.com/Alihk93/S-ECU)
proof-of-concept.

## Architecture at a glance

```
   ECU / bench harness
          │  (conditioned 12V→3.3V signals — see hardware/)
          ▼
   ┌──────────────────────────┐         Wi-Fi SoftAP (10.10.10.10)
   │  ESP32-S3-WROOM-1 N16R8   │  ◀────────────────────────────────▶  Browser
   │                          │         WebSocket (binary + CRC)         on a
   │  core 1: acquisition     │   ── telemetry frames @ 30–60 Hz ──▶   monitor /
   │  core 0: web + WS server │   ── waveform blocks (CKP/CMP)  ──▶     PC / TV
   │  LittleFS: web assets    │   ◀── command packets (sim controls) ─
   └──────────────────────────┘
```

- **Transport:** `esp_http_server` with native WebSocket (`CONFIG_HTTPD_WS_SUPPORT`).
- **Protocol:** packed binary, little-endian, fixed layout, CRC16-CCITT — see
  [`docs/PROTOCOL.md`](docs/PROTOCOL.md).
- **Two-tier data path:** fast on-device acquisition, display-rate wire. Raw 10 kHz
  is never streamed to the browser.

## Repo layout

| Path | Contents |
|------|----------|
| [`firmware/`](firmware/) | ESP-IDF v5.5.x project (acquisition + web/WS server) |
| [`web/`](web/) | Local dashboard (HTML/CSS/JS), served from LittleFS |
| [`hardware/`](hardware/) | KiCad PCB + signal inventory & front-end requirements |
| [`docs/`](docs/) | Protocol spec, architecture, setup, simulation, extension guides |
| [`CLAUDE.md`](CLAUDE.md) | Repo source of truth (read first) |
| [`OPEN_DECISIONS.md`](OPEN_DECISIONS.md) | Gating design questions + status |

## Quick start

```bash
git clone https://github.com/Alihk93/ECU_TESTER.git
cd ECU_TESTER/firmware/ECU_Tester
idf.py set-target esp32s3
idf.py build          # joltwallet/littlefs is pulled automatically (idf_component.yml)
idf.py -p /dev/ttyUSB0 flash monitor
# flash web assets to the LittleFS partition — see docs/SETUP.md
```

Then join Wi-Fi `ECU_TESTER` (password `00000000`) and open **http://10.10.10.10**.

## Status

🟢 **Boots on hardware; SoftAP up.** Structure, docs, and the firmware↔web protocol
contract are in place; the ESP-IDF v5.5.2 firmware builds for `esp32s3` (app ≈ 0xc3e50
bytes, ~74% of the app partition free) and, **verified on the device**, boots cleanly and
serves its Wi-Fi AP (`ECU_TESTER`, WPA2) at **10.10.10.10**. The acquisition path and
asset serving are still stubs (`TODO`s in `main.c`), and the dashboard and PCB are
work-in-progress — all design decisions D0–D8 are resolved (see
[`OPEN_DECISIONS.md`](OPEN_DECISIONS.md)).

## License

TBD — add before first public release.
