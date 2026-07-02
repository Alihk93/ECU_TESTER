/* =============================================================================
 *  ECU_TESTER :: board_config.h
 *  Pin map + hardware budget. INTENTIONALLY NOT FINALIZED.
 *
 *  Do not commit a real pinmap until OPEN_DECISIONS.md D2/D3/D4/D7 are answered:
 *    - D2 monitor-only vs stimulator (adds output pins)
 *    - D3 coil/injector timing vs activity (changes capture strategy)
 *    - D4 conditioning front-end (sets what each pin actually sees)
 *    - D7 GPIO expansion (I2C expander / shift registers for the many inputs)
 *
 *  Hard constraints (do not violate):
 *    - ADC1 ONLY for analog. ADC2 is unusable while Wi-Fi is active.
 *    - On WROOM-1 N16R8, octal PSRAM consumes GPIO35-37 (approx) — do not use.
 *    - Strapping pins (0,3,45,46) and USB (19,20) need care.
 * =============================================================================*/
#pragma once

/* ---- Wi-Fi SoftAP (OPEN_DECISIONS.md D1 — RESOLVED 2026-07-02) --------------- */
#define AP_SSID        "ECU_TESTER"
/* WPA2-PSK requires >= 8 chars. "00000000" is the WPA2 minimum-length key (D1). */
#define AP_PASSWORD    "00000000"    /* WPA2-PSK */
#define AP_CHANNEL     1
#define AP_MAX_CONN    4
/* Non-default AP IP (default SoftAP is 192.168.4.1) — set via esp_netif. */
#define AP_IP_ADDR     "10.10.10.10"
#define AP_GW_ADDR     "10.10.10.10"
#define AP_NETMASK     "255.255.255.0"

/* ---- Analog inputs on ADC1 (5 channels, fit ADC1) --------------------------- */
/* TODO: assign ADC1 channels after D4 sets the conditioned ranges. */
// #define PIN_MAF        ADC1_CHANNEL_x
// #define PIN_MAP        ADC1_CHANNEL_x
// #define PIN_IAT        ADC1_CHANNEL_x
// #define PIN_ECU_V      ADC1_CHANNEL_x
// #define PIN_SENSOR_V   ADC1_CHANNEL_x

/* ---- High-speed capture: CKP / CMP1 / CMP2 (D5/D6) -------------------------- */
/* TODO: pick capture peripheral (MCPWM capture or GPIO ISR + esp_timer) per D5. */
// #define PIN_CKP        GPIO_NUM_x
// #define PIN_CMP1       GPIO_NUM_x
// #define PIN_CMP2       GPIO_NUM_x

/* ---- Digital inputs (coils/injectors/status) — via expander/shift reg (D7) -- */
/* TODO: I2C pins for MCP23017, or shift-register pins for 74HC165 chain. */
// #define PIN_I2C_SDA    GPIO_NUM_x
// #define PIN_I2C_SCL    GPIO_NUM_x

/* ---- Telemetry timing ------------------------------------------------------- */
#define TELEMETRY_HZ_DEFAULT   30
#define ACQ_TASK_CORE          1
#define NET_TASK_CORE          0
#define ACQ_TASK_STACK         4096
#define NET_TASK_STACK         6144
#define ACQ_TASK_PRIO          6
#define NET_TASK_PRIO          5
