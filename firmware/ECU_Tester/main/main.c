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
 *  LittleFS  — TODO: mount the 'storage' partition; assets served with a long
 *  Cache-Control so the browser caches heavy files (docs/ARCHITECTURE.md §4).
 * ===========================================================================*/
static void littlefs_mount(void)
{
    // TODO: esp_vfs_littlefs_register({ .partition_label="storage", ... });
    ESP_LOGW(TAG, "littlefs_mount: TODO (partition 'storage')");
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

/* Static-asset handler for everything else (index.html, css, js, svg, photo). */
static esp_err_t static_get_handler(httpd_req_t *req)
{
    // TODO: map req->uri to a LittleFS path, set Content-Type + Cache-Control,
    //       stream the file. Default "/" -> "/index.html".
    return ESP_OK;
}

static void httpd_start_all(void)
{
    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    cfg.max_open_sockets = 8;
    if (httpd_start(&s_httpd, &cfg) != ESP_OK) {
        ESP_LOGE(TAG, "httpd_start failed");
        return;
    }
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
