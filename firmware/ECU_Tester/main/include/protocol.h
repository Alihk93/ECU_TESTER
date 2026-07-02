/* =============================================================================
 *  ECU_TESTER :: protocol.h
 *  Firmware side of the WebSocket binary protocol.
 *
 *  THIS FILE IMPLEMENTS docs/PROTOCOL.md. It MUST stay byte-for-byte in lockstep
 *  with web/js/protocol.js. Change all three together, in the same commit.
 *
 *  Endianness: little-endian (native ESP32-S3). Structs are __packed so a frame
 *  can be assembled/parsed by memcpy against these layouts directly.
 *  Status: DRAFT v1 — fields marked (pending D-x) firm up per OPEN_DECISIONS.md.
 * =============================================================================*/
#pragma once
#include <stdint.h>
#include <stddef.h>

#define ECU_PROTO_VERSION   0x01
#define ECU_PROTO_MAGIC0    0xA5
#define ECU_PROTO_MAGIC1    0x5A

/* ---- message types (docs/PROTOCOL.md §2) ------------------------------------ */
typedef enum {
    MSG_TELEMETRY = 0x01,  /* S->B */
    MSG_WAVEFORM  = 0x02,  /* S->B */
    MSG_HELLO     = 0x0F,  /* S->B, once on connect */
    MSG_COMMAND   = 0x80,  /* B->S */
    MSG_SUBSCRIBE = 0x81,  /* B->S */
    MSG_ACK       = 0x8F,  /* S->B */
} ecu_msg_type_t;

/* ---- frame envelope (docs/PROTOCOL.md §1) ----------------------------------- */
/* On the wire: [ envelope header ][ payload (payload_len bytes) ][ crc16 ]      */
typedef struct __attribute__((packed)) {
    uint8_t  magic0;       /* 0xA5 */
    uint8_t  magic1;       /* 0x5A */
    uint8_t  version;      /* ECU_PROTO_VERSION */
    uint8_t  msg_type;     /* ecu_msg_type_t */
    uint16_t seq;          /* increments per sent frame, wraps */
    uint16_t payload_len;  /* bytes of payload following this header */
} ecu_frame_hdr_t;         /* 8 bytes; crc16 (uint16 LE) trails the payload */

/* ---- TELEMETRY payload (docs/PROTOCOL.md §3) -------------------------------- */
typedef struct __attribute__((packed)) {
    uint32_t t_us;         /* esp_timer timestamp (microseconds) */
    uint16_t rpm;          /* derived from CKP */
    uint16_t maf;          /* scaled fixed-point — see §3.1 (TODO scales) */
    uint16_t map;
    int16_t  iat;          /* signed: intake air temp can be < 0 */
    uint16_t ecu_v;        /* 0..25 V full-scale */
    uint16_t sensor_v;     /* 0..5 V full-scale */
    uint8_t  coils;        /* bit i = coil i fired since last frame (latched) */
    uint8_t  inj_reg;      /* bit i = injector i (regular) */
    uint8_t  inj_gdi;      /* bit i = injector i (GDI) */
    uint16_t status;       /* status bits — see §3.2 map below */
    uint8_t  iac;          /* IAC stepper phase bits (pending D-?) */
} ecu_telemetry_t;         /* 22 bytes (v1 draft) */

/* status bitfield indices (docs/PROTOCOL.md §3.2) */
enum {
    ST_BATTERY = 0, ST_SWITCH, ST_START, ST_ETC, ST_FAN1, ST_FAN2,
    ST_RSV6, ST_RSV7, ST_FUEL_PUMP, ST_IMMO_P, ST_IMMO_N,
    ST_MRC_P, ST_MRC_N, ST_RSV13, ST_RSV14, ST_RSV15,
};

/* ---- WAVEFORM payload header (docs/PROTOCOL.md §4) -------------------------- */
typedef enum { WAVE_CH_CKP = 0, WAVE_CH_CMP1 = 1, WAVE_CH_CMP2 = 2 } ecu_wave_ch_t;
typedef enum { WAVE_MODE_SAMPLES = 0, WAVE_MODE_EDGES = 1 } ecu_wave_mode_t;

typedef struct __attribute__((packed)) {
    uint8_t  channel;      /* ecu_wave_ch_t */
    uint8_t  mode;         /* ecu_wave_mode_t */
    uint32_t t0_us;        /* timestamp of first sample/edge */
    uint32_t dt_us;        /* decimated sample interval (mode 0) */
    uint16_t count;        /* # samples (mode 0) or # edges (mode 1) */
    /* data follows: mode 0 -> int16 samples[count]
       mode 1 -> struct{ uint32 edge_t_us; uint8 level; } edges[count] */
} ecu_wave_hdr_t;

typedef struct __attribute__((packed)) {
    uint32_t edge_t_us;
    uint8_t  level;        /* 0 or 1 */
} ecu_wave_edge_t;

/* ---- COMMAND / SUBSCRIBE / ACK (docs/PROTOCOL.md §5) ------------------------- */
typedef struct __attribute__((packed)) {
    uint8_t  cmd_id;       /* see §5 table */
    uint8_t  channel;      /* channel index where applicable */
    int32_t  value;        /* fixed-point analog value, or 0/1 toggle */
} ecu_command_t;

typedef struct __attribute__((packed)) {
    uint8_t  telemetry_hz; /* requested telemetry rate */
    uint8_t  wave_channels;/* bitmask: b0 CKP, b1 CMP1, b2 CMP2 */
    uint8_t  wave_mode;    /* 0 samples / 1 edges */
    uint16_t wave_window_ms;
} ecu_subscribe_t;

typedef struct __attribute__((packed)) {
    uint16_t acked_seq;
    uint8_t  status;       /* 0 = ok, non-zero = rejected */
} ecu_ack_t;

/* ---- CRC16/CCITT-FALSE (docs/PROTOCOL.md §1) --------------------------------
 * poly 0x1021, init 0xFFFF, no reflect, no final XOR.
 * Computed over the whole frame EXCEPT the trailing crc16 field.
 * Kept inline/static so it's a leaf call usable from either task; move to a
 * protocol.c if it grows. */
static inline uint16_t ecu_crc16_ccitt(const uint8_t *data, size_t len)
{
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (int b = 0; b < 8; b++)
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
    return crc;
}

/* Helper: total wire size of a frame given its payload length. */
static inline size_t ecu_frame_size(uint16_t payload_len)
{
    return sizeof(ecu_frame_hdr_t) + payload_len + sizeof(uint16_t);
}
