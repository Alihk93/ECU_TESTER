/* =============================================================================
 *  ECU_TESTER :: board_config.h
 *  Pin map + hardware budget for ESP32-S3-WROOM-1 N16R8.
 *
 *  Pinmap COMMITTED (D2/D3/D4/D5/D7 all resolved 2026-07-02). This is the
 *  firmware mirror of the "GPIO map" in `hardware/README.md` — keep the two in
 *  lockstep (they feed the same KiCad front-end). Change both together.
 *
 *  Hard constraints (honoured below):
 *    - ADC1 ONLY for analog. ADC2 is unusable while Wi-Fi is active. Only the
 *      coarse Sensor-V uses ADC1; precision analog comes over I2C (ADS1115).
 *    - WROOM-1 N16R8: octal flash+PSRAM consume GPIO26-37 — never used here.
 *    - Strapping pins (0,3,45,46), USB (19,20) and UART0 console (43,44) avoided.
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

/* ---- Analog input (D4) ------------------------------------------------------
 * Precision analog (MAP/MAF/IAT/ECU-V) is read from the ADS1115 over I2C — it
 * uses NO ESP32 ADC pin. Only the coarse generic Sensor-V uses the internal
 * ADC1 (oversampled). GPIO4 = ADC1 channel 3 on the ESP32-S3. */
#define PIN_SENSOR_V           GPIO_NUM_4
#define SENSOR_V_ADC_UNIT      ADC_UNIT_1
#define SENSOR_V_ADC_CHANNEL   ADC_CHANNEL_3        /* GPIO4 */

/* ---- High-speed capture: CKP / CMP1 / CMP2 (D5/D6) --------------------------
 * Clean 3.3 V edges from the front-end (CKP via MAX9926 VR interface; CMP via
 * Hall divider+clamp). Each input is jumperable VR<->Hall on the board. Capture
 * with RMT RX or GPTimer/MCPWM input capture -> RPM + scope edge-lists. */
#define PIN_CKP                GPIO_NUM_5
#define PIN_CMP1               GPIO_NUM_6
#define PIN_CMP2               GPIO_NUM_7

/* ---- I2C bus (D7): ADS1115 precision analog + MCP23017 status expander ------ */
#define PIN_I2C_SDA            GPIO_NUM_47
#define PIN_I2C_SCL            GPIO_NUM_21
#define I2C_PORT_NUM           I2C_NUM_0
#define I2C_FREQ_HZ            400000
#define ADS1115_I2C_ADDR       0x48                 /* precision analog (4 ch) */
#define MCP23017_I2C_ADDR      0x20                 /* ~14 slow status lines   */

/* ---- 74HC165 coil/injector activity chain (D3/D7) --------------------------
 * 3x 8-bit parallel-in shift registers = 24 activity channels, read over SPI.
 * Input-only (no MOSI): pulse LOAD low to latch parallel inputs, then clock QH
 * in. Firmware latches "fired since last frame" per channel (activity-only). */
#define PIN_HC165_QH           GPIO_NUM_16          /* serial out of the chain (MISO) */
#define PIN_HC165_CLK          GPIO_NUM_17          /* shift clock                    */
#define PIN_HC165_LOAD         GPIO_NUM_18          /* SH/LD, parallel load (active low) */
#define HC165_SPI_HOST         SPI2_HOST
#define HC165_CHAIN_BYTES      3                    /* 24 channels: 8 coil + 8 inj + 8 GDI */

/* ---- Misc ------------------------------------------------------------------- */
#define PIN_STATUS_LED         GPIO_NUM_48          /* onboard RGB on WROOM-1 devkits */

/* ---- Telemetry timing ------------------------------------------------------- */
#define TELEMETRY_HZ_DEFAULT   30
#define ACQ_TASK_CORE          1
#define NET_TASK_CORE          0
#define ACQ_TASK_STACK         4096
#define NET_TASK_STACK         6144
#define ACQ_TASK_PRIO          6
#define NET_TASK_PRIO          5
