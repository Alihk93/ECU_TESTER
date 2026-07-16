#!/usr/bin/env bash
# Create the ECU_TESTER punchlist as GitHub issues (mirrors OPEN_DECISIONS.md
# "Punchlist" + the project board). Run once, after:
#   gh auth login        # or: export GH_TOKEN=<a PAT with 'repo' scope>
# Requires the GitHub CLI (https://cli.github.com). Safe to read before running.
set -euo pipefail
REPO="Alihk93/ECU_TESTER"

command -v gh >/dev/null || { echo "gh not installed: https://cli.github.com"; exit 1; }

mk() { gh issue create --repo "$REPO" --title "$1" --body "$2" ${3:+--label "$3"}; }

mk "KiCad front-end board (D4)" \
"Design the signal-conditioning front-end in this KiCad project: dividers/clamps/buffers, ADS1115, MCP23017, 74HC165, MAX9926, Hall clamps, TVS. Part selection + per-signal conditioning are specified in hardware/README.md. **Blocks all real-driver firmware work.** (OPEN_DECISIONS.md D4 / Punchlist #1.)"

mk "Real acquisition drivers (replace sim generators)" \
"Implement i2c_bus + ads1115 + mcp23017 drivers, the hc165 shift-chain reader, and ckp_capture (RMT/GPTimer, 60-2 decode + RPM). Replace the acq_task/net_task simulation generators field-by-field. Blocked on the D4 front-end board. (OPEN_DECISIONS.md Punchlist #2.)"

mk "App/browser -> device COMMAND channel" \
"ws_handler currently only completes the WS handshake. Parse COMMAND (0x80) and SUBSCRIBE (0x81) frames, validate CRC, apply to the sim, and reply ACK (0x8F) per docs/PROTOCOL.md §5. Nothing sends commands from either client yet. (OPEN_DECISIONS.md Punchlist #3.)"

mk "Verify Android app on real Android TV vs ESP32" \
"Run the Android app on an actual Android TV against the real ESP32 (10.10.10.10) — the remaining physical-hardware step for D9 (validated so far on a phone + sim over USB). Also reboot-test kiosk autostart; optional 'dpm set-device-owner' full lock-task. (OPEN_DECISIONS.md Punchlist #4.)"

echo "Done. Review: gh issue list --repo $REPO"
