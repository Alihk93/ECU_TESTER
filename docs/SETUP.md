# ECU_TESTER — Setup Guide

## Prerequisites
- ESP-IDF **v5.5.x** installed and exported (`. $HOME/esp/esp-idf/export.sh`).
- Ubuntu + VS Code; USB access to the ESP32-S3 (`/dev/ttyUSB0` or `/dev/ttyACM0`).

## First build
```bash
cd firmware/ECU_Tester
idf.py set-target esp32s3        # do this once; it writes sdkconfig for esp32s3
idf.py build                     # joltwallet/littlefs is pulled automatically
idf.py -p /dev/ttyUSB0 flash monitor
```
> The LittleFS managed component (`joltwallet/littlefs`) is declared in
> `main/idf_component.yml`, so the component manager fetches it into
> `managed_components/` on first configure — no manual `add-dependency` step.
> Do `set-target` **before** the first `build`; a bare `build` in a fresh tree
> would default to the `esp32` target.

## Web assets → LittleFS `storage` partition
The dashboard lives in `web/` and is flashed as a LittleFS image into the `storage`
partition (see `firmware/ECU_Tester/partitions.csv`), separate from the app binary.
```bash
# generate + flash the image (offset/size from the partition table)
# TODO: wire this into a make target / tools/flash_web.sh
```
> Rationale for LittleFS over embedding: `docs/ARCHITECTURE.md` §4.

## Connect
Join Wi-Fi **`ECU_TESTER`** (password **`00000000`**, WPA2-PSK — D1), open
**http://10.10.10.10**.

## Verify (goal-driven — CLAUDE.md §7)
- Serial shows `ECU_TESTER up. Join Wi-Fi 'ECU_TESTER'...`.
- Browser `conn` badge turns **connected**.
- Telemetry frames arrive and pass CRC (no dropped-frame spam in console).

## Notes
- If `menuconfig` fails with a missing `_curses` module, append options directly to
  `sdkconfig.defaults` (as done on S-ECU).
- `idf.py fullclean` if you hit a stale `python`/`python3` interpreter mismatch.
