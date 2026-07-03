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

#include "board_config.h"
#include "protocol.h"

static const char *TAG = "ecu_tester";

/* ---- lock-free latest-state snapshot (carried from S-ECU) ------------------- */
static QueueHandle_t s_state_q;      /* length-1, xQueueOverwrite / xQueuePeek */
static httpd_handle_t s_httpd;

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
 *  WebSocket handler  — .is_websocket = true. Receives COMMAND/SUBSCRIBE frames
 *  (docs/PROTOCOL.md §5), validates CRC via ecu_crc16_ccitt, applies to sim.
 * ===========================================================================*/
static esp_err_t ws_handler(httpd_req_t *req)
{
    if (req->method == HTTP_GET) {          /* WS handshake */
        ESP_LOGI(TAG, "WS client connected fd=%d", httpd_req_to_sockfd(req));
        return ESP_OK;
    }
    // TODO: httpd_ws_recv_frame() -> verify magic/version/CRC (protocol.h)
    //       -> dispatch COMMAND (0x80) / SUBSCRIBE (0x81) -> reply ACK (0x8F).
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

    const char *ext = strrchr(path, '.');
    int is_html = (ext && !strcasecmp(ext, ".html"));
    httpd_resp_set_type(req, mime_for(path));
    httpd_resp_set_hdr(req, "Cache-Control",
                       is_html ? "no-cache" : "public, max-age=31536000");

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
 *   httpd_queue_work -> httpd_ws_send_frame_async.)
 * ===========================================================================*/
static void ws_broadcast(const uint8_t *frame, size_t len)
{
    // TODO: enumerate clients, send binary frame to each websocket fd.
    (void)frame; (void)len;
}

/* =============================================================================
 *  acq_task (core 1) — sample everything, publish latest snapshot.
 *  TODO: ADC1 oneshot + averaging (D4 scales), edge capture for coils/injectors
 *        with latched activity (D3), CKP/CMP into ring buffers (D5/D6).
 * ===========================================================================*/
static void acq_task(void *arg)
{
    ecu_telemetry_t snap = {0};
    const TickType_t period = pdMS_TO_TICKS(1);   /* fast inner loop */
    for (;;) {
        snap.t_us = (uint32_t)esp_timer_get_time();
        // TODO: fill snap.* from ADC + captured edges (or from sim generators).
        xQueueOverwrite(s_state_q, &snap);        /* always-latest, no mutex */
        vTaskDelay(period);
    }
}

/* =============================================================================
 *  net_task (core 0) — build TELEMETRY/WAVEFORM frames and broadcast.
 * ===========================================================================*/
static void net_task(void *arg)
{
    uint16_t seq = 0;
    ecu_telemetry_t snap;
    uint8_t frame[sizeof(ecu_frame_hdr_t) + sizeof(ecu_telemetry_t) + 2];
    const TickType_t period = pdMS_TO_TICKS(1000 / TELEMETRY_HZ_DEFAULT);

    for (;;) {
        if (xQueuePeek(s_state_q, &snap, portMAX_DELAY) == pdTRUE) {
            ecu_frame_hdr_t *h = (ecu_frame_hdr_t *)frame;
            h->magic0 = ECU_PROTO_MAGIC0;  h->magic1 = ECU_PROTO_MAGIC1;
            h->version = ECU_PROTO_VERSION; h->msg_type = MSG_TELEMETRY;
            h->seq = seq++;                 h->payload_len = sizeof(ecu_telemetry_t);
            memcpy(frame + sizeof(*h), &snap, sizeof(snap));
            size_t body = sizeof(*h) + sizeof(snap);
            uint16_t crc = ecu_crc16_ccitt(frame, body);
            memcpy(frame + body, &crc, sizeof(crc));   /* little-endian native */
            ws_broadcast(frame, body + sizeof(crc));
            // TODO: also emit WAVEFORM blocks per active SUBSCRIBE (D6).
        }
        vTaskDelay(period);
    }
}

void app_main(void)
{
    ESP_ERROR_CHECK(nvs_flash_init());
    s_state_q = xQueueCreate(1, sizeof(ecu_telemetry_t));

    wifi_softap_start();
    littlefs_mount();
    httpd_start_all();

    xTaskCreatePinnedToCore(acq_task, "acq", ACQ_TASK_STACK, NULL, ACQ_TASK_PRIO, NULL, ACQ_TASK_CORE);
    xTaskCreatePinnedToCore(net_task, "net", NET_TASK_STACK, NULL, NET_TASK_PRIO, NULL, NET_TASK_CORE);

    ESP_LOGI(TAG, "ECU_TESTER up. Join Wi-Fi '%s', open http://%s", AP_SSID, AP_IP_ADDR);
}
