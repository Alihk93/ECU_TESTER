# ECU_TESTER — Hardware (KiCad)

The PCB acquires automotive ECU signals and feeds them, **safely conditioned**, to the
ESP32-S3. Nothing from the ECU connects to a bare GPIO — see the front-end requirements
below. This is a **monitor-only** tester (D2) with the conditioning front-end designed
**in this project** (D4). KiCad project files go in this folder.

Suggested KiCad layout (add when starting the schematic):
```
hardware/
├── ecu_tester.kicad_pro
├── ecu_tester.kicad_sch      (root sheet)
├── sheets/                   (front-end, MCU, power, expansion — hierarchical)
├── ecu_tester.kicad_pcb
├── symbols/  footprints/     (project libs)
└── output/                   (gerbers/BOM — gitignored)
```

## Signal inventory (LOCKED — ranges are design targets; trim to the actual ECU)

Analog values are referred to the **actual ECU pin** (firmware compensates the divider,
see `docs/PROTOCOL.md` §3.1).

| # | Signal | Kind | Range at ECU pin | Front-end | To MCU via |
|--:|--------|------|------------------|-----------|-----------|
| 1 | MAP | analog | 0–5 V | ÷2 divider + RC + Schottky clamp + op-amp buffer | ADS1115 AIN0 |
| 2 | MAF | analog | 0–5 V | ÷2 divider + RC + clamp + buffer | ADS1115 AIN1 |
| 3 | IAT | analog | 0–5 V | ÷2 divider + RC + clamp + buffer | ADS1115 AIN2 |
| 4 | ECU voltage | analog | 0–25 V | ÷~8.5 divider + RC + clamp + buffer | ADS1115 AIN3 |
| 5 | Sensor voltage (generic) | analog | 0–5 V | ÷2 divider + RC + clamp + buffer | ESP32 ADC1 (GPIO4) |
| 6 | CKP | waveform | VR, bipolar AC (mV → 100 V+) | **MAX9926** VR interface (protection + adaptive comparator) → 3.3 V edges | GPIO5 (capture) |
| 7 | CMP1 | waveform | Hall, 0–5 V (or 0–12 V) square | level-shift/divide + Schottky clamp (+ optional comparator) | GPIO6 (capture) |
| 8 | CMP2 | waveform | Hall, 0–5 V square | as CMP1 | GPIO7 (capture) |
| 9–16 | Ignition coils ×8 | activity | ~5–12 V drive command | tap **low-side drive command** (not the coil primary); series R + Schottky clamp to 3.3 V | 74HC165 chain |
| 17–24 | Injectors regular ×8 | activity | ~12 V drive command | tap low-side drive; series R + clamp | 74HC165 chain |
| 25–32 | Injectors GDI ×8 | activity | drive command (boost driver 60–90 V rail — do **not** tap the rail) | tap the **command** line only; extra TVS + clamp | 74HC165 chain |
| 33–46 | Battery, Switch, Start, ETC, Fan1/2, Fuel Pump, IMMO±, MRC±, IAC | state | 0/12 V | ÷ divider + Schottky clamp (or opto/comparator for noisy lines) | MCP23017 |

**Why tap the drive command, not the load:** in monitor-only mode you observe the ECU's
**low-side gate command** to each igniter/injector driver — a ground-referenced,
logic-ish signal — never the coil primary (hundreds of volts of flyback) or the GDI
boost rail. That gives clean activity detection with only modest clamping.

**Sensor type is jumperable:** each of CKP/CMP1/CMP2 has a selection jumper so a Hall
crank or a VR cam can be wired without a board respin. Default: CKP = VR (60-2),
CMP = Hall.

## Front-end parts (locked selection — D4/D7)
- **ADS1115** (16-bit, 4-ch, I²C @ 0x48) — precision analog: MAP/MAF/IAT/ECU-V. Chosen
  over the internal ADC for monotonic, low-jitter gauges. (Generic Sensor-V uses the
  internal ADC1 on GPIO4, oversampled — a coarse "is it present" meter.)
- **MCP23017** (16-ch I/O expander, I²C @ 0x20) — the ~14 slow status lines. Polled
  50–100 Hz; I²C is plenty for signals this slow.
- **74HC165 ×3** (parallel-in shift registers, 24 bits) — coil + injector **activity**.
  Read over SPI (QH→GPIO16, CLK→GPIO17, SH/LD→GPIO18). Firmware latches "fired since
  last frame". No per-channel timing (D3 = activity-only) ⇒ no capture front-end MCU.
- **MAX9926** — VR interface for the CKP crank sensor: adaptive threshold across the
  huge amplitude range, protection built in. Outputs clean 3.3 V edges to GPIO5.
- **Op-amp buffers** (rail-to-rail, e.g. MCP6002/6004) after the ADC dividers — the
  ADS1115 and ESP32 ADC both want a low source impedance for accurate readings.

## Front-end requirements (mandatory)
- **Every input protected:** series resistor + Schottky clamp diodes to the 3.3 V rail
  and GND; add a **TVS** on any line exposed to flyback/transients (coil/injector
  command lines, ECU-V inlet). Never feed a VR sensor to a GPIO directly — the MAX9926
  does the clamping/threshold.
- **Analog:** divider to land each node in 0–3.0 V at the ADC input, RC anti-alias
  filter, clamp, op-amp buffer. Firmware stores the divider ratio and reports true
  ECU-pin millivolts.
- **Grounding:** separate analog and digital ground pours, single-point (star) tie at
  the supply return — same discipline as the HX711 layout. Keep the CKP/CMP capture
  traces and the switching coil-command taps away from the analog divider section.
- **Power:** board runs from the bench 12 V; protected buck to 5 V (op-amps, Hall
  sensors if powered) and 3.3 V (ESP32-S3, logic). Reverse-battery + load-dump
  protection on the 12 V inlet.

## GPIO map (mirrors `firmware/main/include/board_config.h`)
I²C SDA 47 / SCL 21 · Sensor-V ADC1 GPIO4 · 74HC165 QH 16 / CLK 17 / LD 18 ·
CKP 5 · CMP1 6 · CMP2 7 · status LED 48.
Reserved: 26–37 (octal flash/PSRAM), 0/3/45/46 (strapping), 19/20 (USB), 43/44 (UART0).

## Resolved hardware decisions
D2 monitor-only · D3 activity-only · D4 conditioning in this project · D5 CKP = VR 60-2,
CMP = Hall · D7 ADS1115 + MCP23017 + 74HC165 (no capture MCU). Full log:
`OPEN_DECISIONS.md`.
