# ECU_TESTER — Communication Protocol Specification

**Transport:** WebSocket **binary** frames (`HTTPD_WS_TYPE_BINARY`) over the SoftAP,
`esp_http_server` with `CONFIG_HTTPD_WS_SUPPORT=y`.
**Byte order:** **little-endian** everywhere (matches native ESP32-S3 memory layout, so a
packed struct can be `memcpy`'d to the wire and JS reads it with `DataView(..., true)`).
**Alignment:** all multi-byte fields are naturally packed; C structs use
`__attribute__((packed))`.
**Status:** **v1 — signal set LOCKED** (D0–D8 resolved 2026-07-02). `protocol.h` and
`protocol.js` implement this file and must change with it, in the same commit.
Byte layout below is verified: envelope 8 B + TELEMETRY 22 B payload + CRC, and the
CRC passes the standard `0x29B1` check vector.

> Why binary and not S-ECU's JSON: at ~50 signals + waveform data, minified JSON is large
> to serialize on-device and slow to parse per frame. A fixed packed layout is smaller,
> parses in constant time, and gives us CRC error detection for free.

---

## 1. Frame envelope

Every message — either direction — is one WebSocket binary frame with this envelope:

```
 offset  size  field        description
 ------  ----  -----------  -------------------------------------------------
   0      2    magic        0xA5 0x5A  (sanity/sync; also useful if ever logged
                                        to a serial capture where frames aren't
                                        self-delimiting)
   2      1    version      protocol version, currently 0x01
   3      1    msg_type     see §2
   4      2    seq          uint16, increments per sent frame, wraps at 0xFFFF
                            (receiver detects drops/reorder)
   6      2    payload_len  uint16, length in bytes of the payload that follows
   8      N    payload      msg_type-specific, see §3–§5
  8+N     2    crc16        CRC16/CCITT-FALSE over bytes [0 .. 8+N-1] inclusive
 ------  ----
 total = 10 + N bytes
```

**CRC:** CRC16/CCITT-FALSE — poly `0x1021`, init `0xFFFF`, no reflect, no final XOR.
Computed over the **entire frame except the CRC field itself** (magic → end of payload).
Receiver recomputes and drops the frame on mismatch. (Reference implementation in
`protocol.h` / `protocol.js`.)

---

## 2. Message types

| `msg_type` | Dir | Name | Meaning |
|-----------:|-----|------|---------|
| `0x01` | S→B | `TELEMETRY` | Analog values + packed digital state (§3) |
| `0x02` | S→B | `WAVEFORM`   | One CKP/CMP waveform block (§4) |
| `0x0F` | S→B | `HELLO`      | Sent once on connect: fw version, capabilities, channel counts |
| `0x80` | B→S | `COMMAND`    | Manual sim control from the dashboard (§5) |
| `0x81` | B→S | `SUBSCRIBE`  | Client requests/adjusts stream (rates, which waveforms) |
| `0x8F` | S→B | `ACK`        | Ack/nack for a `COMMAND`/`SUBSCRIBE` (echoes seq + status) |

S→B = server (ESP32) to browser · B→S = browser to server.

---

## 3. `TELEMETRY` payload (msg_type `0x01`)

Sent at the telemetry rate (default 30 Hz, adjustable via `SUBSCRIBE`). Fixed layout.

```
 offset  size  field           type      units / encoding
 ------  ----  --------------  --------  ---------------------------------------
   0      4    t_us            uint32    device timestamp (esp_timer, microseconds)
   4      2    rpm             uint16    revolutions per minute (derived from CKP)
 --- analog (raw ADC or scaled fixed-point — see §3.1) ---
   6      2    maf             uint16    Mass Air Flow      (scale S_MAF)
   8      2    map             uint16    Manifold Abs Press (scale S_MAP)
  10      2    iat             uint16    Intake Air Temp    (scale S_IAT, signed*)
  12      2    ecu_v           uint16    ECU voltage   0–25 V (scale S_ECU_V)
  14      2    sensor_v        uint16    Sensor voltage 0–5 V (scale S_SENSOR_V)
 --- packed digital state (bitfields, §3.2) ---
  16      1    coils           uint8     bit i = coil i activity   (i = 0..7)
  17      1    inj_reg         uint8     bit i = injector i (regular)
  18      1    inj_gdi         uint8     bit i = injector i (GDI)
  19      2    status          uint16    status bits, see §3.2
  21      1    iac             uint8     IAC stepper phase, bits 0-3 = phases A-D
 ------  ----
 total payload = 22 bytes   (v1 draft)
```

\* IAT can be negative; transmit as `uint16` carrying an `int16` two's-complement value,
or apply an offset. Decide with the scaling table (§3.1). `protocol.js` reads it as `int16`.

### 3.1 Scaling (LOCKED) — analog fields are millivolts at the ECU pin
To keep the wire packed we send scaled integers, not floats. **Every analog field is
transmitted as `uint16` millivolts (mV) referred to the actual ECU pin** — the firmware
already compensates the front-end divider ratio, so the value is the real node voltage,
not the divided one. The browser applies each sensor's transfer function for display. A
bench tester monitors *arbitrary* ECUs, so the firmware stays sensor-agnostic and all
curve knowledge lives in the UI (editable per profile).

| field | encoding | range | UI display |
|-------|----------|-------|-----------|
| `ecu_v`    | mV, uint16 | 0–25000 | ÷1000 → V |
| `sensor_v` | mV, uint16 | 0–5000  | ÷1000 → V |
| `maf`      | mV, uint16 | 0–5000  | MAF curve → g/s |
| `map`      | mV, uint16 | 0–5000  | MAP curve → kPa |
| `iat`      | mV, uint16 | 0–5000  | NTC/transfer curve → °C |

**Sources:** `ecu_v`, `map`, `maf`, `iat` come from the **ADS1115** (16-bit, I²C);
`sensor_v` from the **internal ADC1** (oversampled). `protocol.js` decodes `iat` as
`int16`, harmless for a 0–5000 positive range — the temperature sign lives in the UI
curve, not the wire.

### 3.2 Bitfields
- `coils` / `inj_reg` / `inj_gdi`: **bit set = channel active/fired at any point since the
  previous telemetry frame** ("latched activity"). Latch is cleared after the frame is
  built, so a firing pulse shorter than the frame interval is never dropped. *(D3 =
  activity-only — there is **no** dwell/pulse-width block; §3.3 is retained only as a
  future option.)*
- `status` (uint16), bit → signal:

  | bit | signal | bit | signal |
  |----:|--------|----:|--------|
  | 0 | Battery present | 8 | Fuel Pump |
  | 1 | Switch (key) | 9 | IMMO+ |
  | 2 | Start button | 10 | IMMO− |
  | 3 | ETC | 11 | MRC+ |
  | 4 | Fan 1 | 12 | MRC− |
  | 5 | Fan 2 | 13 | (reserved) |
  | 6 | (reserved) | 14 | (reserved) |
  | 7 | (reserved) | 15 | (reserved) |

### 3.3 Optional timing extension — **NOT USED in v1 (D3 = activity-only)**
Retained only as a future option if timing capture is ever added. Would append after
`iac`, per active channel or as a fixed array:
`dwell_us[8]` (uint16) for coils, `pw_us[8]`/`pw_us[16]` (uint16) for injectors.
Defer until D3 is resolved so we don't pay the bytes if activity-only is enough.

---

## 4. `WAVEFORM` payload (msg_type `0x02`)

One block per channel per send. Mode is chosen per open decision **D6**.

```
 offset  size  field         type     description
 ------  ----  ------------  -------  -------------------------------------------
   0      1    channel       uint8    0=CKP, 1=CMP1, 2=CMP2
   1      1    mode          uint8    0=samples (decimated window), 1=edge list
   2      4    t0_us         uint32   timestamp of first sample/edge
   6      4    dt_us         uint32   sample interval (mode 0) — µs between samples
  10      2    count         uint16   number of samples (mode 0) or edges (mode 1)
  12      *    data          --       mode 0: count × int16 samples
                                       mode 1: count × { uint32 edge_t_us, uint8 level }
 ------  ----
```

- **mode 1 (edge list) — LOCKED DEFAULT (D6).** Exact transition timestamps: tiny on
  the wire and preserves missing-tooth / cam-sync timing perfectly. This is the default
  for all three channels (CKP, CMP1, CMP2). Only these three are waveform channels —
  coils/injectors are activity bits (§3.2), not scope traces.
- **mode 0 (decimated window):** optional coarse "analog envelope" for the scope look.
  `dt_us` is the *decimated* interval, not the acquisition interval. Available but not
  the default.
- *(Triggered single-shot capture on the sync gap is a future addition — not in v1.)*
- A `SUBSCRIBE` (§5) selects which channels stream, the mode, and the window/rate
  (default rolling window ≈ 2 crank revolutions).

---

## 5. Browser → server: `COMMAND` (`0x80`) and `SUBSCRIBE` (`0x81`)

`COMMAND` payload — drives a value/toggle in **simulation mode** (see D2 for whether these
also drive real outputs):

```
 offset  size  field    type    description
 ------  ----  -------  ------  ------------------------------------------
   0      1    cmd_id   uint8   what to set (see table below)
   1      1    channel  uint8   channel index where applicable (e.g. coil 0..7)
   2      4    value    int32   analog value (fixed-point, same scale as §3.1)
                                or 0/1 for toggles
```

| `cmd_id` | target |
|---------:|--------|
| `0x01` | MAF (slider) |
| `0x02` | MAP |
| `0x03` | IAT |
| `0x04` | ECU voltage |
| `0x05` | ECU current |
| `0x10` | coil toggle (uses `channel`) |
| `0x11` | injector toggle (uses `channel`) |
| `0x20` | battery / switch / pump / IMMO / MRC / fan (via `channel` = status bit index) |
| `0x30` | IAC |
| `0x40` | RPM (sim) / CKP / CMP drive |

`SUBSCRIBE` payload — client tunes its stream:

```
 offset  size  field           type    description
   0      1    telemetry_hz    uint8   requested telemetry rate (e.g. 30, 60)
   1      1    wave_channels   uint8   bitmask: bit0 CKP, bit1 CMP1, bit2 CMP2
   2      1    wave_mode       uint8   0 samples / 1 edges
   3      2    wave_window_ms  uint16  time window to keep on screen
```

Server replies with `ACK` (`0x8F`): payload `{ uint16 acked_seq, uint8 status }`
(status `0x00` = ok, non-zero = rejected reason).

---

## 6. Versioning & compatibility
- `version` in the envelope gates breaking changes. Bump it and update `HELLO` when the
  layout changes incompatibly.
- `HELLO` (§2) lets the browser learn channel counts and capabilities at connect, so the
  UI can adapt instead of hardcoding.
- **Golden rule:** any edit here → edit `protocol.h` and `protocol.js` in the same commit,
  and bump `version` if the change is not backward-compatible.
