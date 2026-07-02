# M1 — First Vertical Slice (bring-up)

**One ADS1115 channel → TELEMETRY frame → browser gauge, end-to-end.**
Purpose: prove the whole contract path on real hardware — I²C read → 22-byte pack →
CRC → WebSocket binary → `protocol.js` decode → a needle that tracks a pot — **before**
scaling to all channels. If the gauge doesn't move, there are only a handful of places
to look.

> Hand this file to Claude Code. It is scoped so every file is small and the pass/fail
> is a single observable. Follow the house rules in `CLAUDE.md` §7.

---

## Scope

**In:** SoftAP up, one analog channel (ECU-V on ADS1115 AIN3), a 10 Hz TELEMETRY
broadcast with only `ecu_v` populated (all other fields 0), a one-page browser gauge
served from flash, using the **real** `protocol.js` decoder.

**Out (later milestones, do not build now):** the other 4 analog channels, MCP23017
status, 74HC165 activity, CKP/CMP capture + waveform, the real dashboard, LittleFS,
COMMAND/SUBSCRIBE, and the dual-core acq/net split. M1 is deliberately single-task.

## Assumptions (flagged — correct if wrong)

1. **Breadboard bring-up, no PCB/front-end yet.** A 10 kΩ pot (0–3.3 V) feeds AIN3
   directly. There is **no ÷8.5 divider**, so the firmware divider factor = **1.0** and
   `ecu_v` reads **0–3.3** during this test (not 0–25). The gauge is scaled `/3.3` for
   M1; on the real board it becomes `/25`. This is the one intentional M1 fib.
2. **EMBED_TXTFILES for the two tiny web files** (page + `protocol.js`) to keep LittleFS
   off the critical path. `CLAUDE.md` keeps LittleFS for the *real* dashboard — this is a
   called-out, M1-only shortcut, not a silent change to the asset strategy.
3. ADS1115 at **0x48** (ADDR→GND), FSR **±4.096 V** (1 LSB = 0.125 mV; 3.3 V has margin),
   **128 SPS** single-shot.
4. `sdkconfig.defaults` already has `CONFIG_HTTPD_WS_SUPPORT=y` — **no sdkconfig change
   needed.** Do not retune it.
5. The frozen contract is untouched: `protocol.h`/`protocol.js`/`PROTOCOL.md` stay
   byte-for-byte as-is. M1 only *uses* them.

---

## Files

```
firmware/main/
├── CMakeLists.txt      MODIFY — add sources + EMBED_TXTFILES + REQUIRES
├── main.c              REPLACE stub bodies — init chain + 10 Hz acq/broadcast task
├── telemetry.h/.c      NEW — pack an ecu_telemetry_t into a wire frame (drop-in below)
├── ads1115.h/.c        NEW — single-shot read of one channel → millivolts (drop-in)
├── wifi_ap.h/.c        NEW — SoftAP: ECU_TESTER / 00000000 / static 10.10.10.10
└── ws_server.h/.c      NEW — httpd: GET "/", GET "/protocol.js", WS "/ws", broadcast
web/
└── slice.html          NEW — M1 bring-up page (imports the real protocol.js)
```

Everything reads pins/addresses from `firmware/main/include/board_config.h` — no new
magic numbers.

---

## Drop-in code for the contract-critical parts

These three must be exact (they define/decode the wire). Copy verbatim.

### `telemetry.c` — frame assembly (uses the existing `protocol.h` helpers)
```c
#include <string.h>
#include "protocol.h"
#include "telemetry.h"   /* size_t telemetry_pack(uint8_t*,size_t,const ecu_telemetry_t*,uint16_t); */

size_t telemetry_pack(uint8_t *out, size_t cap, const ecu_telemetry_t *t, uint16_t seq)
{
    const size_t total = ecu_frame_size(sizeof(ecu_telemetry_t)); /* 8 + 22 + 2 = 32 */
    if (cap < total) return 0;

    ecu_frame_hdr_t *h = (ecu_frame_hdr_t *)out;
    h->magic0      = ECU_PROTO_MAGIC0;
    h->magic1      = ECU_PROTO_MAGIC1;
    h->version     = ECU_PROTO_VERSION;
    h->msg_type    = MSG_TELEMETRY;
    h->seq         = seq;
    h->payload_len = sizeof(ecu_telemetry_t);

    memcpy(out + sizeof(ecu_frame_hdr_t), t, sizeof(ecu_telemetry_t));

    uint16_t crc = ecu_crc16_ccitt(out, sizeof(ecu_frame_hdr_t) + sizeof(ecu_telemetry_t));
    memcpy(out + sizeof(ecu_frame_hdr_t) + sizeof(ecu_telemetry_t), &crc, sizeof crc);
    return total; /* 32 */
}
```

### `ads1115.c` — single-shot read → mV (the config word is the fiddly bit)
```c
/* single-ended AINx, single-shot, PGA ±4.096 V, 128 SPS, comparator off */
static uint16_t ads_cfg(uint8_t ch) {         /* ch = 0..3 */
    uint16_t mux = (uint16_t)(0x4 | (ch & 0x3)) << 12;  /* AIN0..3 -> 0x4000..0x7000 */
    return 0x8000 | mux | 0x0200 | 0x0100 | 0x0080 | 0x0003; /* AIN3 -> 0xF383 */
}

esp_err_t ads1115_read_mv(i2c_master_dev_handle_t dev, uint8_t ch, int32_t *mv)
{
    uint16_t cfg = ads_cfg(ch);
    uint8_t w[3] = { 0x01 /*CONFIG*/, (uint8_t)(cfg >> 8), (uint8_t)cfg };
    ESP_RETURN_ON_ERROR(i2c_master_transmit(dev, w, sizeof w, 100), "ADS", "cfg");
    vTaskDelay(pdMS_TO_TICKS(9));             /* 128 SPS -> ~7.8 ms conversion */
    uint8_t ptr = 0x00 /*CONV*/, r[2];
    ESP_RETURN_ON_ERROR(i2c_master_transmit_receive(dev, &ptr, 1, r, 2, 100), "ADS", "conv");
    int16_t raw = (int16_t)((r[0] << 8) | r[1]);
    if (raw < 0) raw = 0;                     /* single-ended: floor tiny negatives */
    *mv = (int32_t)raw * 4096 / 32768;        /* ±4.096 V FSR => value in mV, 0..3300 */
    return ESP_OK;
}
```
Init: `i2c_new_master_bus()` on `PIN_I2C_SDA/PIN_I2C_SCL` @ `I2C_CLK_HZ`, then
`i2c_master_bus_add_device()` at `ADS1115_ADDR`. Store the dev handle for the task.

### `web/slice.html` — imports the real decoder, renders one gauge
```html
<!doctype html><meta charset=utf-8><title>ECU_TESTER · M1</title>
<style>
 body{background:#0e1116;color:#e8e8e8;font:16px system-ui;display:grid;place-items:center;height:100vh;margin:0}
 #v{font:700 64px ui-monospace,monospace;color:#f4a93c}
 #bar{width:60vw;height:18px;background:#20242c;border-radius:9px;overflow:hidden;margin-top:16px}
 #fill{height:100%;width:0;background:#2dd4bf;transition:width .08s}
 #s{opacity:.55;margin-top:10px;font:13px ui-monospace}
</style>
<div><div id=v>--.-- V</div><div id=bar><div id=fill></div></div><div id=s>connecting…</div></div>
<script type="module">
 import { decodeFrame } from './protocol.js';
 const v=document.getElementById('v'),fill=document.getElementById('fill'),s=document.getElementById('s');
 (function connect(){
   const ws=new WebSocket(`ws://${location.host}/ws`); ws.binaryType='arraybuffer';
   ws.onopen =()=>s.textContent='connected';
   ws.onclose=()=>{s.textContent='reconnecting…';setTimeout(connect,700);};
   ws.onmessage=e=>{
     const f=decodeFrame(e.data);               // null on bad magic/length/CRC
     if(!f||f.type!=='telemetry') return;
     const volts=f.data.ecuV/1000;              // ecu_v is millivolts
     v.textContent=volts.toFixed(2)+' V';
     fill.style.width=Math.max(0,Math.min(100,volts/3.3*100))+'%';  // M1 scale 0..3.3 V
     s.textContent='seq '+f.seq;
   };
 })();
</script>
```

## `main.c` — acquisition + broadcast (single task for M1)

Boot sequence, in order: `nvs_flash_init()` → `wifi_ap_start()` → I²C bus +
`ads1115` init → `ws_server_start()` → create the task below on **core `ACQ_TASK_CORE`**.
(The dual-core acq/net split with the length-1 lock-free queue lands in M2; keeping it
one task here isolates variables during bring-up.)

```c
#define ADS_CH_ECU_V  3          /* AIN3 per board_config.h wiring */

static void acq_broadcast_task(void *arg)
{
    uint16_t seq = 0;
    uint8_t  frame[64];          /* 32 needed */
    for (;;) {
        int32_t mv = 0;
        ads1115_read_mv(s_ads_dev, ADS_CH_ECU_V, &mv);

        ecu_telemetry_t t = {0};                 /* all other fields intentionally 0 */
        t.t_us  = (uint32_t)esp_timer_get_time();
        t.ecu_v = (uint16_t)(mv < 0 ? 0 : (mv > 25000 ? 25000 : mv)); /* clamp future-safe */

        size_t n = telemetry_pack(frame, sizeof frame, &t, seq++);
        if (n) ws_server_broadcast(frame, n);

        vTaskDelay(pdMS_TO_TICKS(100));          /* 10 Hz */
    }
}
```
Task params: stack `ACQ_TASK_STACK`, prio `ACQ_TASK_PRIO`, core `ACQ_TASK_CORE`
(all in `board_config.h`).

## `ws_server.c` — the only non-boilerplate bit is the broadcast

Register three URIs on one `httpd`: `GET "/"` → embedded `slice.html`,
`GET "/protocol.js"` (MIME `application/javascript`) → embedded `protocol.js`, and
`"/ws"` with `.is_websocket = true`. The embedded symbols come from EMBED_TXTFILES
(`_binary_slice_html_start/_end`, `_binary_protocol_js_start/_end`). Broadcast to every
live WS client:
```c
esp_err_t ws_server_broadcast(const uint8_t *data, size_t len)
{
    int fds[CONFIG_LWIP_MAX_ACTIVE_TCP];
    size_t n = sizeof(fds) / sizeof(fds[0]);
    if (httpd_get_client_list(s_httpd, &n, fds) != ESP_OK) return ESP_FAIL;
    httpd_ws_frame_t f = { .type = HTTPD_WS_TYPE_BINARY,
                           .payload = (uint8_t *)data, .len = len };
    for (size_t i = 0; i < n; i++)
        if (httpd_ws_get_fd_info(s_httpd, fds[i]) == HTTPD_WS_CLIENT_WEBSOCKET)
            httpd_ws_send_frame_async(s_httpd, fds[i], &f);
    return ESP_OK;
}
```
The WS handler itself only needs to accept the handshake (return `ESP_OK` on
`HTTPD_WS_TYPE_*` open); M1 ignores inbound frames.

## `wifi_ap.c` — SoftAP with a static IP

Standard `esp_netif` SoftAP using `AP_SSID` / `AP_PASSWORD` / `AP_AUTHMODE` /
`AP_MAX_CONN` / `AP_CHANNEL`. **Set the static IP** (`AP_IP_ADDR` 10.10.10.10) — stop the
default DHCP server, `esp_netif_set_ip_info()` with IP=GW=10.10.10.10 /
netmask 255.255.255.0, restart DHCP so clients get leases in that subnet.

## `firmware/main/CMakeLists.txt`
```diff
-idf_component_register(SRCS "main.c"
-                       INCLUDE_DIRS "include")
+idf_component_register(
+    SRCS "main.c" "telemetry.c" "ads1115.c" "wifi_ap.c" "ws_server.c"
+    INCLUDE_DIRS "include"
+    EMBED_TXTFILES "../../web/slice.html" "../../web/js/protocol.js"
+    REQUIRES esp_http_server esp_wifi esp_netif nvs_flash esp_timer
+             driver esp_driver_i2c)
```

---

## Wiring (breadboard)

| ADS1115 | ESP32-S3 |
|---------|----------|
| VDD | 3V3 |
| GND | GND |
| SCL | GPIO21 |
| SDA | GPIO47 |
| ADDR | GND (→ 0x48) |
| AIN3 | pot wiper |

Pot: 10 kΩ, ends across 3V3 / GND, wiper → AIN3.

## Pass / fail (the gate)

1. `idf.py set-target esp32s3 && idf.py build` — clean, no warnings from the new files.
2. `idf.py -p <port> flash monitor`.
3. On the laptop: join Wi-Fi **ECU_TESTER** / **00000000**, open **http://10.10.10.10**.

**PASS** iff all of:
- Turning the pot sweeps the readout **0.00 ↔ ~3.30 V** and the bar **0 ↔ 100 %**,
  smoothly, updating ~10×/s.
- `seq N` increments continuously (no stall).
- **Browser console is clean** — no exceptions. (`decodeFrame` returning `null` on a bad
  frame is silent by design; a frozen needle = frames being rejected = a real failure.)
- `monitor` shows one WS client connect and a **stable heap high-watermark** over ~2 min
  (no leak from the send path).

**CRC sanity (optional, 30 s):** temporarily XOR one payload byte before the CRC in
`telemetry_pack` → the needle must **freeze** (browser drops every frame). Revert. This
proves CRC + magic gating end-to-end, not just the happy path.

If it passes, the entire wire contract is validated on hardware and M2 (dual-core split +
the remaining ADS1115 channels + internal-ADC Sensor-V) is a safe next step.
