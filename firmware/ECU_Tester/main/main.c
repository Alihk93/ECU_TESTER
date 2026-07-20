/* =============================================================================
 *  ECU_TESTER :: main.c
 *  ESP32-S3 acquisition engine + web/WebSocket server. ESP-IDF v5.5.x.
 *
 *  SKELETON. The task architecture, shared-state pattern, and boot sequence are
 *  laid out here; the marked TODOs are filled in once OPEN_DECISIONS.md is
 *  resolved. Nothing here commits a pinmap (see board_config.h).
 *
 *  Pipeline (see docs/ARCHITECTURE.md):
 *    acq_task (core 1): ADC1 + edge capture + waveform ring buffers
 *        -> length-1 queue (xQueueOverwrite)  [always-latest snapshot]
 *    net_task (core 0): pack TELEMETRY/WAVEFORM frames (binary+CRC),
 *        broadcast to every WS client via esp_http_server
 *
 *  Boot: nvs -> wifi SoftAP (IP 10.10.10.10) -> LittleFS mount -> httpd(+WS)
 *        -> spawn acq_task, net_task.
 * =============================================================================*/
#include <string.h>
#include <strings.h>
#include <stdio.h>
#include <stdlib.h>
#include <limits.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "esp_http_server.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "esp_event.h"
#include "esp_littlefs.h"
#include "lwip/sockets.h"

#include "board_config.h"
#include "protocol.h"

static const char *TAG = "ecu_tester";

/* ---- lock-free latest-state snapshot (carried from S-ECU) ------------------- */
static QueueHandle_t s_state_q;      /* length-1, xQueueOverwrite / xQueuePeek */
static httpd_handle_t s_httpd;

/* =============================================================================
 *  Runtime control state — set by the browser->server command channel
 *  (docs/PROTOCOL.md §5), read by acq_task (core 1) / net_task (core 0). Each is
 *  a naturally-aligned 32-bit scalar, so plain volatile reads/writes are atomic
 *  on the ESP32 (no mutex; same lock-free discipline as the state snapshot).
 *
 *  COMMAND overrides drive the SIMULATION (D2 = monitor-only; manual controls
 *  exist only in sim). A negative override releases that field back to AUTO — the
 *  free-running generator — since docs/PROTOCOL.md §5 has no explicit "release".
 * ===========================================================================*/
#define OVR_AUTO   INT32_MIN                 /* "no override — run the generator" */
static volatile int32_t  s_ovr_rpm   = OVR_AUTO;
static volatile int32_t  s_ovr_maf   = OVR_AUTO;   /* mV at the ECU pin (§3.1) */
static volatile int32_t  s_ovr_map   = OVR_AUTO;   /* mV */
static volatile int32_t  s_ovr_iat   = OVR_AUTO;   /* mV */
static volatile int32_t  s_ovr_ecu_v = OVR_AUTO;   /* mV */
static volatile int32_t  s_ovr_iac   = OVR_AUTO;   /* phase nibble, or AUTO */
static volatile uint32_t s_force_coil = 0;         /* bits OR-ed into fired coils */
static volatile uint32_t s_force_inj  = 0;         /* bits OR-ed into fired injectors */
static volatile uint32_t s_status_set = 0;         /* status bits forced ON */
static volatile uint32_t s_status_clr = 0;         /* status bits forced OFF */
static volatile int32_t  s_telemetry_hz = TELEMETRY_HZ_DEFAULT;  /* SUBSCRIBE rate */

/* WAVEFORM edge-list streaming — DEFAULT OFF (2026-07-12 FPS pass). The dashboard
 * scope is a parametric standing display and never plots the edge lists, so
 * streaming them is pure overhead (~60 extra WS frames/s + per-message parse work
 * on weak TV browsers). A SUBSCRIBE (0x81) with any wave_channels bit set flips
 * this on at runtime; wire format unchanged (docs/PROTOCOL.md §4). */
static volatile bool s_wave_stream = false;

/* =============================================================================
 *  Wi-Fi SoftAP (D1: SSID ECU_TESTER / WPA2 00000000 / IP 10.10.10.10).
 *  Also brings up esp_netif + the default event loop — this starts the lwIP
 *  TCP/IP task, which MUST exist before httpd_start() touches a socket
 *  (otherwise lwIP asserts "Invalid mbox" and the chip reboot-loops).
 * ===========================================================================*/
static void wifi_softap_start(void)
{
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_t *ap_netif = esp_netif_create_default_wifi_ap();

    /* Non-default AP IP: stop DHCP, set 10.10.10.10, restart DHCP. */
    esp_netif_ip_info_t ip = {0};
    esp_netif_str_to_ip4(AP_IP_ADDR,  &ip.ip);
    esp_netif_str_to_ip4(AP_GW_ADDR,  &ip.gw);
    esp_netif_str_to_ip4(AP_NETMASK,  &ip.netmask);
    ESP_ERROR_CHECK(esp_netif_dhcps_stop(ap_netif));
    ESP_ERROR_CHECK(esp_netif_set_ip_info(ap_netif, &ip));
    ESP_ERROR_CHECK(esp_netif_dhcps_start(ap_netif));

    wifi_init_config_t init_cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init_cfg));

    wifi_config_t wc = {
        .ap = {
            .ssid           = AP_SSID,
            .ssid_len       = strlen(AP_SSID),
            .channel        = AP_CHANNEL,
            .password       = AP_PASSWORD,
            .max_connection = AP_MAX_CONN,
            .authmode       = WIFI_AUTH_WPA2_PSK,   /* WPA2-PSK: password must be >= 8 chars */
        },
    };
    if (strlen(AP_PASSWORD) == 0) {
        wc.ap.authmode = WIFI_AUTH_OPEN;            /* fall back to open if key is empty */
    }

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &wc));
    ESP_ERROR_CHECK(esp_wifi_start());

    ESP_LOGI(TAG, "SoftAP '%s' up (auth %s), IP %s",
             AP_SSID, strlen(AP_PASSWORD) ? "WPA2-PSK" : "OPEN", AP_IP_ADDR);
}

/* =============================================================================
 *  LittleFS — mount the 'storage' partition (web assets) under WEB_BASE_PATH.
 *  The image is built from web/ at compile time and flashed with the app
 *  (littlefs_create_partition_image ... FLASH_IN_PROJECT in main/CMakeLists.txt).
 * ===========================================================================*/
#define WEB_BASE_PATH   "/web"

static void littlefs_mount(void)
{
    esp_vfs_littlefs_conf_t conf = {
        .base_path              = WEB_BASE_PATH,
        .partition_label        = "storage",
        .format_if_mount_failed = false,   /* a blank FS is a flashing bug, not a runtime one */
        .dont_mount             = false,
    };
    esp_err_t err = esp_vfs_littlefs_register(&conf);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "LittleFS mount failed (%s) — did you flash the 'storage' image?",
                 esp_err_to_name(err));
        return;
    }
    size_t total = 0, used = 0;
    if (esp_littlefs_info(conf.partition_label, &total, &used) == ESP_OK)
        ESP_LOGI(TAG, "LittleFS mounted at %s — %u/%u bytes used", WEB_BASE_PATH,
                 (unsigned)used, (unsigned)total);

    /* Sanity: confirm the dashboard entrypoint is present and readable. */
    FILE *f = fopen(WEB_BASE_PATH "/index.html", "r");
    if (f) {
        fseek(f, 0, SEEK_END);
        ESP_LOGI(TAG, "web root OK: index.html (%ld bytes)", ftell(f));
        fclose(f);
    } else {
        ESP_LOGW(TAG, "index.html not found — 'storage' image not flashed?");
    }
}

/* =============================================================================
 *  Browser -> server command channel (docs/PROTOCOL.md §5).
 *  ACK status codes are implementation-defined (§5: 0 = ok, non-zero = reason).
 * ===========================================================================*/
enum { ACK_OK = 0, ACK_ERR_LEN = 1, ACK_ERR_CRC = 2, ACK_ERR_TYPE = 3 };

/* Apply one COMMAND to the sim override state. Returns an ACK status. */
static uint8_t apply_command(const ecu_command_t *c)
{
    switch (c->cmd_id) {
        case 0x01: s_ovr_maf   = (c->value < 0) ? OVR_AUTO : c->value; break;   /* MAF mV */
        case 0x02: s_ovr_map   = (c->value < 0) ? OVR_AUTO : c->value; break;   /* MAP mV */
        case 0x03: s_ovr_iat   = (c->value < 0) ? OVR_AUTO : c->value; break;   /* IAT mV */
        case 0x04: s_ovr_ecu_v = (c->value < 0) ? OVR_AUTO : c->value; break;   /* ECU V mV */
        case 0x05: break;    /* ECU current: no telemetry v1 field — accepted, no effect */
        case 0x10: if (c->channel < 8) {                                        /* coil toggle */
                       uint32_t m = 1u << c->channel;
                       if (c->value) s_force_coil |= m; else s_force_coil &= ~m;
                   } break;
        case 0x11: if (c->channel < 8) {                                        /* injector toggle */
                       uint32_t m = 1u << c->channel;
                       if (c->value) s_force_inj |= m; else s_force_inj &= ~m;
                   } break;
        case 0x20: if (c->channel < 16) {                                       /* status bit */
                       uint32_t m = 1u << c->channel;
                       if (c->value) { s_status_set |= m; s_status_clr &= ~m; }
                       else          { s_status_clr |= m; s_status_set &= ~m; }
                   } break;
        case 0x30: s_ovr_iac = (c->value < 0) ? OVR_AUTO : (c->value & 0x0F); break;  /* IAC */
        case 0x40: s_ovr_rpm = (c->value < 0) ? OVR_AUTO : c->value; break;     /* RPM (sim) */
        default:   return ACK_ERR_TYPE;
    }
    return ACK_OK;
}

/* Apply one SUBSCRIBE: telemetry rate + waveform streaming on/off. */
static uint8_t apply_subscribe(const ecu_subscribe_t *s)
{
    if (s->telemetry_hz) {
        int hz = s->telemetry_hz;
        s_telemetry_hz = (hz > 60) ? 60 : hz;      /* clamp; net_task floors at 1 */
    }
    s_wave_stream = (s->wave_channels != 0);        /* any channel selected -> stream edges */
    return ACK_OK;
}

/* Send an ACK (0x8F) on the requesting socket. */
static void send_ack(httpd_req_t *req, uint16_t acked_seq, uint8_t status)
{
    uint8_t buf[sizeof(ecu_frame_hdr_t) + sizeof(ecu_ack_t) + 2];
    ecu_frame_hdr_t *h = (ecu_frame_hdr_t *)buf;
    h->magic0 = ECU_PROTO_MAGIC0;  h->magic1 = ECU_PROTO_MAGIC1;
    h->version = ECU_PROTO_VERSION; h->msg_type = MSG_ACK;
    h->seq = 0;                     h->payload_len = sizeof(ecu_ack_t);
    ecu_ack_t *a = (ecu_ack_t *)(buf + sizeof(*h));
    a->acked_seq = acked_seq;  a->status = status;
    size_t body = sizeof(*h) + sizeof(ecu_ack_t);
    uint16_t crc = ecu_crc16_ccitt(buf, body);
    memcpy(buf + body, &crc, sizeof(crc));
    httpd_ws_frame_t f = { .type = HTTPD_WS_TYPE_BINARY, .payload = buf, .len = body + sizeof(crc) };
    httpd_ws_send_frame(req, &f);
}

/* =============================================================================
 *  WebSocket handler  — .is_websocket = true. On handshake, tunes the socket;
 *  afterwards receives COMMAND/SUBSCRIBE frames (docs/PROTOCOL.md §5), validates
 *  magic/version/length/CRC, dispatches to the sim, and replies with an ACK.
 * ===========================================================================*/
#define WS_RX_MAX   64   /* B->S frames are tiny: COMMAND=16 B, SUBSCRIBE=15 B total */

static esp_err_t ws_handler(httpd_req_t *req)
{
    if (req->method == HTTP_GET) {          /* WS handshake */
        int fd = httpd_req_to_sockfd(req);
        /* Telemetry sockets must never stall the bench: cap a blocking send at
         * 100 ms (default is 5 s — one dozing TV froze the stream for every
         * client), and disable Nagle so 30 Hz frames leave on time instead of
         * coalescing into bursts. */
        struct timeval tmo = { .tv_sec = 0, .tv_usec = 100 * 1000 };
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tmo, sizeof(tmo));
        int nodelay = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));
        ESP_LOGI(TAG, "WS client connected fd=%d", fd);
        return ESP_OK;
    }

    /* --- receive one B->S frame -------------------------------------------- */
    httpd_ws_frame_t ws = { .type = HTTPD_WS_TYPE_BINARY };
    if (httpd_ws_recv_frame(req, &ws, 0) != ESP_OK) return ESP_OK;   /* length probe */
    if (ws.len == 0) return ESP_OK;

    /* Always consume the payload (even if oversize) so the socket stays framed. */
    uint8_t  stackbuf[WS_RX_MAX];
    uint8_t *buf = stackbuf, *heap = NULL;
    if (ws.len > WS_RX_MAX) {
        heap = malloc(ws.len);
        if (!heap) return ESP_OK;
        buf = heap;
    }
    ws.payload = buf;
    esp_err_t rc = httpd_ws_recv_frame(req, &ws, ws.len);

    if (rc == ESP_OK && ws.len >= 10 && ws.len <= WS_RX_MAX &&
        buf[0] == ECU_PROTO_MAGIC0 && buf[1] == ECU_PROTO_MAGIC1 &&
        buf[2] == ECU_PROTO_VERSION) {
        ecu_frame_hdr_t hdr;
        memcpy(&hdr, buf, sizeof(hdr));
        size_t total = 8 + hdr.payload_len + 2;
        if (total <= ws.len) {                       /* not truncated */
            uint16_t rx_crc;
            memcpy(&rx_crc, buf + 8 + hdr.payload_len, sizeof(rx_crc));
            if (ecu_crc16_ccitt(buf, 8 + hdr.payload_len) != rx_crc) {
                send_ack(req, hdr.seq, ACK_ERR_CRC);
            } else {
                uint8_t status;
                switch (hdr.msg_type) {
                    case MSG_COMMAND:
                        status = (hdr.payload_len == sizeof(ecu_command_t))
                               ? apply_command((const ecu_command_t *)(buf + 8)) : ACK_ERR_LEN;
                        break;
                    case MSG_SUBSCRIBE:
                        status = (hdr.payload_len == sizeof(ecu_subscribe_t))
                               ? apply_subscribe((const ecu_subscribe_t *)(buf + 8)) : ACK_ERR_LEN;
                        break;
                    default:
                        status = ACK_ERR_TYPE;
                }
                send_ack(req, hdr.seq, status);
            }
        }
    }
    free(heap);
    return ESP_OK;
}

/* Map a file extension to a Content-Type. */
static const char *mime_for(const char *path)
{
    const char *d = strrchr(path, '.');
    if (!d)                        return "application/octet-stream";
    if (!strcasecmp(d, ".html"))   return "text/html";
    if (!strcasecmp(d, ".css"))    return "text/css";
    if (!strcasecmp(d, ".js"))     return "text/javascript";
    if (!strcasecmp(d, ".json"))   return "application/json";
    if (!strcasecmp(d, ".png"))    return "image/png";
    if (!strcasecmp(d, ".jpg") ||
        !strcasecmp(d, ".jpeg"))   return "image/jpeg";
    if (!strcasecmp(d, ".svg"))    return "image/svg+xml";
    if (!strcasecmp(d, ".ico"))    return "image/x-icon";
    if (!strcasecmp(d, ".woff2"))  return "font/woff2";
    return "application/octet-stream";
}

/* Static-asset handler: map req->uri to a LittleFS file and stream it.
 * "/" -> "/index.html". Heavy assets get a long Cache-Control so the browser
 * caches them (docs/ARCHITECTURE.md §4); HTML revalidates so UI updates show. */
#define WEB_URI_MAX     256
#define WEB_PATH_MAX    (sizeof(WEB_BASE_PATH) + WEB_URI_MAX)
#define WEB_CHUNK       2048

static esp_err_t static_get_handler(httpd_req_t *req)
{
    char uri[WEB_URI_MAX];
    if (strlcpy(uri, req->uri, sizeof(uri)) >= sizeof(uri)) {
        httpd_resp_send_err(req, HTTPD_414_URI_TOO_LONG, NULL);
        return ESP_FAIL;
    }
    char *qs = strchr(uri, '?');          /* drop any query string */
    if (qs) *qs = '\0';
    if (strstr(uri, "..")) {              /* refuse path traversal */
        httpd_resp_send_err(req, HTTPD_404_NOT_FOUND, NULL);
        return ESP_FAIL;
    }
    if (uri[0] == '\0' || !strcmp(uri, "/"))
        strlcpy(uri, "/index.html", sizeof(uri));

    char path[WEB_PATH_MAX];
    snprintf(path, sizeof(path), "%s%s", WEB_BASE_PATH, uri);

    FILE *f = fopen(path, "r");
    if (!f) {
        ESP_LOGW(TAG, "404 %s", path);
        httpd_resp_send_err(req, HTTPD_404_NOT_FOUND, "Not found");
        return ESP_FAIL;
    }

    /* Code (HTML/JS/CSS/JSON) revalidates so a reflashed UI shows immediately;
     * media (images/fonts) keeps the long lifetime. Without this, a kiosk
     * browser runs year-stale JS after a web-partition update. */
    const char *ext = strrchr(path, '.');
    int is_code = ext && (!strcasecmp(ext, ".html") || !strcasecmp(ext, ".js") ||
                          !strcasecmp(ext, ".css")  || !strcasecmp(ext, ".json"));
    httpd_resp_set_type(req, mime_for(path));
    httpd_resp_set_hdr(req, "Cache-Control",
                       is_code ? "no-cache" : "public, max-age=31536000");

    char *buf = malloc(WEB_CHUNK);
    if (!buf) { fclose(f); httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, NULL); return ESP_FAIL; }

    size_t n;
    esp_err_t rc = ESP_OK;
    while ((n = fread(buf, 1, WEB_CHUNK, f)) > 0) {
        if (httpd_resp_send_chunk(req, buf, n) != ESP_OK) { rc = ESP_FAIL; break; }
    }
    free(buf);
    fclose(f);
    if (rc == ESP_OK) httpd_resp_send_chunk(req, NULL, 0);   /* terminate response */
    return rc;
}

static void httpd_start_all(void)
{
    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    cfg.max_open_sockets = 8;
    cfg.stack_size       = 8192;                    /* headroom for file streaming */
    cfg.uri_match_fn     = httpd_uri_match_wildcard;/* required for the wildcard route */
    cfg.lru_purge_enable = true;                    /* evict idle sockets under pressure */
    if (httpd_start(&s_httpd, &cfg) != ESP_OK) {
        ESP_LOGE(TAG, "httpd_start failed");
        return;
    }
    /* Register the exact "/ws" route BEFORE the wildcard catch-all so it wins. */
    httpd_uri_t ws = { .uri="/ws", .method=HTTP_GET, .handler=ws_handler, .is_websocket=true };
    httpd_uri_t st = { .uri="/*",  .method=HTTP_GET, .handler=static_get_handler };
    httpd_register_uri_handler(s_httpd, &ws);
    httpd_register_uri_handler(s_httpd, &st);
}

/* =============================================================================
 *  Broadcast one already-built binary frame to every connected WS client.
 *  (Pattern from S-ECU: httpd_get_client_list + httpd_ws_get_fd_info filter +
 *   httpd_ws_send_frame_async.)
 *
 *  A client in Wi-Fi power save (TVs and tablets doze constantly) stops ACKing
 *  and its TCP send buffer fills; a blocking send would then stall this task —
 *  and therefore every other client's stream. So: send only when the socket is
 *  writable (zero-timeout select), otherwise drop the frame for that client
 *  (telemetry is latest-state; a dropped frame is invisible). ~2 s of
 *  continuous back-pressure or a send error evicts the client — the browser
 *  auto-reconnects (websocket.js).
 * ===========================================================================*/
#define WS_EVICT_STRIKES 240   /* consecutive undeliverable frames ≈ 2 s */

static void ws_broadcast(const uint8_t *frame, size_t len)
{
    static uint8_t strikes[FD_SETSIZE];
    if (!s_httpd) return;
    size_t n = 8;                                  /* cfg.max_open_sockets */
    int fds[8];
    if (httpd_get_client_list(s_httpd, &n, fds) != ESP_OK) return;

    httpd_ws_frame_t f = {
        .type    = HTTPD_WS_TYPE_BINARY,
        .payload = (uint8_t *)frame,
        .len     = len,
    };
    for (size_t i = 0; i < n; i++) {
        int fd = fds[i];
        if (fd < 0 || fd >= FD_SETSIZE) continue;
        if (httpd_ws_get_fd_info(s_httpd, fd) != HTTPD_WS_CLIENT_WEBSOCKET) continue;

        fd_set wset;
        FD_ZERO(&wset);
        FD_SET(fd, &wset);
        struct timeval poll0 = { 0, 0 };
        bool ok = select(fd + 1, NULL, &wset, NULL, &poll0) > 0 &&
                  FD_ISSET(fd, &wset) &&
                  httpd_ws_send_frame_async(s_httpd, fd, &f) == ESP_OK;
        if (ok) {
            strikes[fd] = 0;
            /* WS clients never send after the handshake, so httpd's LRU counter
             * (advanced on RECEIVED traffic) marks them the eviction victim.
             * Touch it on every delivered frame, or a second viewer's burst of
             * asset requests purges the first viewer's live stream. */
            httpd_sess_update_lru_counter(s_httpd, fd);
        } else if (++strikes[fd] >= WS_EVICT_STRIKES) {
            ESP_LOGW(TAG, "WS fd=%d stalled >2s, evicting", fd);
            strikes[fd] = 0;
            httpd_sess_trigger_close(s_httpd, fd);
        }
    }
}

/* =============================================================================
 *  acq_task (core 1) — SIMULATION MODE (CLAUDE.md §1: full sim with no hardware).
 *  Generates the slow signals — RPM, the four analog sensors (as raw mV matching
 *  the web dashboard's display scaling in live.js), status lines and the IAC
 *  stepper phase —
 *  and publishes them as the latest snapshot. The fast, angle-synced signals
 *  (coil/injector firing + CKP/CMP waveform) are produced in net_task from the
 *  same RPM, so a coil flash lines up with the crank trace on the scope.
 *
 *  Replace field-by-field with real driver reads at the bench: ads1115_read_mv()
 *  for maf/map/iat/ecu_v, internal ADC1 for sensor_v, mcp23017 for status.
 * ===========================================================================*/
static inline uint16_t clamp_u16(double v, uint16_t hi)
{
    if (v < 0) return 0;
    if (v > hi) return hi;
    return (uint16_t)(v + 0.5);
}

static void acq_task(void *arg)
{
    const TickType_t period = pdMS_TO_TICKS(10);   /* 100 Hz analog/status */
    for (;;) {
        uint32_t now = (uint32_t)esp_timer_get_time();
        double   ts  = now / 1e6;

        /* RPM: idle breathing (~0.25 Hz) + a slow rev ramp (~0.08 Hz). */
        double lfo = sin(ts * 0.25 * 2 * M_PI);
        double rev = 0.5 - 0.5 * cos(ts * 0.08 * 2 * M_PI);   /* 0..1 load */
        double rpm = 820.0 + 120.0 * lfo + 2600.0 * rev * rev;

        /* Analog engineering values, then back to raw mV (live.js shows V = mV/1000). */
        double map_kpa = 30.0 + rev * 68.0;                   /* vacuum -> load  */
        double maf_gs  = 4.0 + rev * 190.0 + (rpm - 820) * 0.01;
        double iat_c   = 28.0 + 4.0 * sin(ts * 0.05 * 2 * M_PI);
        double ecu_mv  = 13800.0 + 150.0 * sin(ts * 1.3 * 2 * M_PI);
        double sen_mv  = 2500.0 + 1800.0 * sin(ts * 0.5 * 2 * M_PI);

        ecu_telemetry_t snap = {0};
        snap.t_us     = now;
        snap.rpm      = clamp_u16(rpm, 8000);
        snap.maf      = clamp_u16(maf_gs / 400.0 * 5000.0, 5000);
        snap.map      = clamp_u16(map_kpa / 105.0 * 5000.0, 5000);
        snap.iat      = (int16_t)clamp_u16((120.0 - iat_c) / 160.0 * 5000.0, 5000);
        snap.ecu_v    = clamp_u16(ecu_mv, 25000);
        snap.sensor_v = clamp_u16(sen_mv, 5000);
        /* coils/inj_reg/inj_gdi filled by net_task (angle-synced, latched). */

        uint16_t st = (1 << ST_BATTERY) | (1 << ST_SWITCH) | (1 << ST_ETC) |
                      (1 << ST_FUEL_PUMP) | (1 << ST_MRC_P) | (1 << ST_MRC_N);
        if (rev > 0.30) st |= (1 << ST_FAN1);                 /* temp-driven fans */
        if (rev > 0.62) st |= (1 << ST_FAN2);
        if (ts < 1.5)   st |= (1 << ST_START);                /* crank blip at boot */
        snap.status = st;

        snap.iac = (uint8_t)(1u << ((now / 150000u) % 4u));   /* walking A-B-C-D */

        /* COMMAND overrides (docs/PROTOCOL.md §5): a set field replaces the
         * generator; AUTO leaves it free-running. Read each volatile once. */
        int32_t o;
        if ((o = s_ovr_rpm)   != OVR_AUTO) snap.rpm   = clamp_u16(o, 8000);
        if ((o = s_ovr_maf)   != OVR_AUTO) snap.maf   = clamp_u16(o, 5000);
        if ((o = s_ovr_map)   != OVR_AUTO) snap.map   = clamp_u16(o, 5000);
        if ((o = s_ovr_iat)   != OVR_AUTO) snap.iat   = (int16_t)clamp_u16(o, 5000);
        if ((o = s_ovr_ecu_v) != OVR_AUTO) snap.ecu_v = clamp_u16(o, 25000);
        if ((o = s_ovr_iac)   != OVR_AUTO) snap.iac   = (uint8_t)(o & 0x0F);
        snap.status = (snap.status | (uint16_t)s_status_set) & ~(uint16_t)s_status_clr;

        xQueueOverwrite(s_state_q, &snap);        /* always-latest, no mutex */
        vTaskDelay(period);
    }
}

/* =============================================================================
 *  Waveform generation (SIM). A single crank-angle accumulator, integrated from
 *  the latest RPM, feeds three edge-list channels (CKP 60-2, CMP1, CMP2) and the
 *  coil/injector firing events — so the flashing coil matches the CKP trace.
 * ===========================================================================*/
#define WAVE_MAX_EDGES   256

/* s_wave_stream (SUBSCRIBE-controlled) is declared with the runtime-control state
 * near the top of the file. */

static const uint8_t s_fire_order[6] = { 0, 4, 2, 5, 1, 3 };  /* 1-5-3-6-2-4 */

static ecu_wave_edge_t s_ckp[WAVE_MAX_EDGES], s_cmp1[32], s_cmp2[32];
static uint8_t s_ckp_lvl, s_cmp1_lvl, s_cmp2_lvl;
static uint8_t s_wavebuf[sizeof(ecu_frame_hdr_t) + sizeof(ecu_wave_hdr_t) +
                         WAVE_MAX_EDGES * sizeof(ecu_wave_edge_t) + 2];

/* Assemble + broadcast one WAVEFORM edge frame for a channel. */
static void wave_broadcast(uint8_t ch, const ecu_wave_edge_t *edges, uint16_t count,
                           uint16_t *seq)
{
    if (count == 0) return;
    uint16_t payload_len = sizeof(ecu_wave_hdr_t) + count * sizeof(ecu_wave_edge_t);

    ecu_frame_hdr_t *h = (ecu_frame_hdr_t *)s_wavebuf;
    h->magic0 = ECU_PROTO_MAGIC0;  h->magic1 = ECU_PROTO_MAGIC1;
    h->version = ECU_PROTO_VERSION; h->msg_type = MSG_WAVEFORM;
    h->seq = (*seq)++;              h->payload_len = payload_len;

    ecu_wave_hdr_t *w = (ecu_wave_hdr_t *)(s_wavebuf + sizeof(*h));
    w->channel = ch;  w->mode = WAVE_MODE_EDGES;
    w->t0_us = edges[0].edge_t_us;  w->dt_us = 0;  w->count = count;

    memcpy(s_wavebuf + sizeof(*h) + sizeof(*w), edges,
           count * sizeof(ecu_wave_edge_t));

    size_t body = sizeof(*h) + payload_len;
    uint16_t crc = ecu_crc16_ccitt(s_wavebuf, body);
    memcpy(s_wavebuf + body, &crc, sizeof(crc));
    ws_broadcast(s_wavebuf, body + sizeof(crc));
}

/* =============================================================================
 *  net_task (core 0) — build TELEMETRY + WAVEFORM frames and broadcast.
 * ===========================================================================*/
static void net_task(void *arg)
{
    uint16_t seq = 0;
    ecu_telemetry_t snap;
    uint8_t frame[sizeof(ecu_frame_hdr_t) + sizeof(ecu_telemetry_t) + 2];

    double   crank_deg = 0;              /* absolute integrated crank angle */
    uint32_t last_us   = (uint32_t)esp_timer_get_time();
    long     last_fire = 0;

    for (;;) {
        /* Telemetry rate is SUBSCRIBE-adjustable (docs/PROTOCOL.md §5); floor at 1 Hz. */
        int hz = s_telemetry_hz;
        vTaskDelay(pdMS_TO_TICKS(1000 / (hz < 1 ? 1 : hz)));
        if (xQueuePeek(s_state_q, &snap, portMAX_DELAY) != pdTRUE) continue;

        /* --- integrate crank angle over the elapsed interval --------------- */
        uint32_t now = (uint32_t)esp_timer_get_time();
        double   dt_us = (double)(now - last_us);          /* wraps ~71 min: ok */
        double   dps_us = snap.rpm * 6.0 / 1e6;            /* deg per microsec  */
        double   a0 = crank_deg;
        double   a1 = crank_deg + dps_us * dt_us;

        uint16_t nckp = 0, ncmp1 = 0, ncmp2 = 0;
        if (s_wave_stream && dps_us > 0) {
            /* CKP 60-2: high in the first half of each present tooth (6°/tooth,
               58 present then 2 missing). Emit only on level change. */
            double b = ceil((a0 + 1e-6) / 3.0) * 3.0;      /* half-tooth grid */
            for (; b <= a1 && nckp < WAVE_MAX_EDGES; b += 3.0) {
                long idx = (long)floor(b / 6.0);
                int  pos = (int)llround(b - idx * 6.0);    /* 0 or 3 */
                int  tooth = (int)(((idx % 60) + 60) % 60);
                uint8_t lvl = (pos == 0 && tooth < 58) ? 1 : 0;
                if (lvl != s_ckp_lvl) {
                    s_ckp[nckp].edge_t_us = last_us + (uint32_t)((b - a0) / dps_us);
                    s_ckp[nckp].level = lvl;  nckp++;  s_ckp_lvl = lvl;
                }
            }
            /* CMP1 high [0,360), CMP2 high [360,720) of the 720° cam cycle. */
            double c = ceil((a0 + 1e-6) / 360.0) * 360.0;
            for (; c <= a1 && ncmp1 < 32 && ncmp2 < 32; c += 360.0) {
                long cyc = (long)floor(c / 360.0);
                uint32_t t = last_us + (uint32_t)((c - a0) / dps_us);
                uint8_t l1 = ((((cyc % 2) + 2) % 2) == 0) ? 1 : 0;
                uint8_t l2 = l1 ? 0 : 1;
                if (l1 != s_cmp1_lvl) { s_cmp1[ncmp1].edge_t_us = t; s_cmp1[ncmp1].level = l1; ncmp1++; s_cmp1_lvl = l1; }
                if (l2 != s_cmp2_lvl) { s_cmp2[ncmp2].edge_t_us = t; s_cmp2[ncmp2].level = l2; ncmp2++; s_cmp2_lvl = l2; }
            }
        }
        if (dps_us > 0) {
            /* Firing events: one cylinder every 120° crank, in firing order.
               Latched into the telemetry bits "since last frame". */
            long f1 = (long)floor(a1 / 120.0);
            for (long e = last_fire + 1; e <= f1; e++) {
                uint8_t cyl = s_fire_order[((e % 6) + 6) % 6];
                snap.coils   |= (1u << cyl);
                snap.inj_reg |= (1u << cyl);
                snap.inj_gdi |= (1u << cyl);
            }
            last_fire = f1;
        }
        crank_deg = a1;
        last_us   = now;

        /* COMMAND coil/injector toggles (docs/PROTOCOL.md §5): held ON regardless
         * of firing phase, OR-ed onto the latched activity bits. */
        snap.coils   |= (uint8_t)s_force_coil;
        snap.inj_reg |= (uint8_t)s_force_inj;
        snap.inj_gdi |= (uint8_t)s_force_inj;

        /* --- TELEMETRY ---------------------------------------------------- */
        ecu_frame_hdr_t *h = (ecu_frame_hdr_t *)frame;
        h->magic0 = ECU_PROTO_MAGIC0;  h->magic1 = ECU_PROTO_MAGIC1;
        h->version = ECU_PROTO_VERSION; h->msg_type = MSG_TELEMETRY;
        h->seq = seq++;                 h->payload_len = sizeof(ecu_telemetry_t);
        memcpy(frame + sizeof(*h), &snap, sizeof(snap));
        size_t body = sizeof(*h) + sizeof(snap);
        uint16_t crc = ecu_crc16_ccitt(frame, body);
        memcpy(frame + body, &crc, sizeof(crc));   /* little-endian native */
        ws_broadcast(frame, body + sizeof(crc));

        /* --- WAVEFORM (CKP / CMP1 / CMP2) — off by default, see s_wave_stream */
        if (s_wave_stream) {
            wave_broadcast(WAVE_CH_CKP,  s_ckp,  nckp,  &seq);
            wave_broadcast(WAVE_CH_CMP1, s_cmp1, ncmp1, &seq);
            wave_broadcast(WAVE_CH_CMP2, s_cmp2, ncmp2, &seq);
        }
    }
}

void app_main(void)
{
    esp_err_t nvs = nvs_flash_init();
    if (nvs == ESP_ERR_NVS_NO_FREE_PAGES || nvs == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        /* corrupt page or NVS-layout bump: erase once and retry, else boot-loop */
        ESP_ERROR_CHECK(nvs_flash_erase());
        nvs = nvs_flash_init();
    }
    ESP_ERROR_CHECK(nvs);
    s_state_q = xQueueCreate(1, sizeof(ecu_telemetry_t));

    wifi_softap_start();
    littlefs_mount();
    httpd_start_all();

    xTaskCreatePinnedToCore(acq_task, "acq", ACQ_TASK_STACK, NULL, ACQ_TASK_PRIO, NULL, ACQ_TASK_CORE);
    xTaskCreatePinnedToCore(net_task, "net", NET_TASK_STACK, NULL, NET_TASK_PRIO, NULL, NET_TASK_CORE);

    ESP_LOGI(TAG, "ECU_TESTER up. Join Wi-Fi '%s', open http://%s", AP_SSID, AP_IP_ADDR);
}
