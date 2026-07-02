/* =============================================================================
 *  ECU_TESTER :: protocol.js
 *  Web side of the WebSocket binary protocol.
 *
 *  THIS FILE IMPLEMENTS docs/PROTOCOL.md. It MUST stay byte-for-byte in lockstep
 *  with firmware/main/include/protocol.h. Change all three together.
 *
 *  All reads/writes are LITTLE-ENDIAN (DataView(..., true)) to match the ESP32-S3.
 *  Status: DRAFT v1.
 * =============================================================================*/

export const PROTO = {
  VERSION: 0x01,
  MAGIC0: 0xa5,
  MAGIC1: 0x5a,
  TYPE: {
    TELEMETRY: 0x01,
    WAVEFORM: 0x02,
    HELLO: 0x0f,
    COMMAND: 0x80,
    SUBSCRIBE: 0x81,
    ACK: 0x8f,
  },
  WAVE_CH: { CKP: 0, CMP1: 1, CMP2: 2 },
  WAVE_MODE: { SAMPLES: 0, EDGES: 1 },
};

// status bit indices (docs/PROTOCOL.md §3.2)
export const STATUS_BITS = [
  "battery", "switch", "start", "etc", "fan1", "fan2",
  "rsv6", "rsv7", "fuelPump", "immoP", "immoN",
  "mrcP", "mrcN", "rsv13", "rsv14", "rsv15",
];

// ---- CRC16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflect, no final XOR ----
export function crc16ccitt(bytes, len = bytes.length) {
  let crc = 0xffff;
  for (let i = 0; i < len; i++) {
    crc ^= bytes[i] << 8;
    for (let b = 0; b < 8; b++) {
      crc = crc & 0x8000 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
    }
  }
  return crc & 0xffff;
}

// unpack the little-endian status bitfield into a named object
function unpackStatus(bits) {
  const out = {};
  STATUS_BITS.forEach((name, i) => (out[name] = (bits >> i) & 1));
  return out;
}
function unpackByteBits(byte) {
  return Array.from({ length: 8 }, (_, i) => (byte >> i) & 1);
}

/**
 * Decode one incoming WebSocket binary frame (ArrayBuffer).
 * Verifies magic, version, length, and CRC. Returns null on any failure
 * (caller drops the frame — see docs/PROTOCOL.md §1).
 */
export function decodeFrame(arrayBuffer) {
  const dv = new DataView(arrayBuffer);
  const bytes = new Uint8Array(arrayBuffer);
  if (bytes.length < 10) return null;
  if (bytes[0] !== PROTO.MAGIC0 || bytes[1] !== PROTO.MAGIC1) return null;
  if (bytes[2] !== PROTO.VERSION) return null;

  const msgType = bytes[3];
  const seq = dv.getUint16(4, true);
  const payloadLen = dv.getUint16(6, true);
  const total = 8 + payloadLen + 2;
  if (bytes.length < total) return null;

  const rxCrc = dv.getUint16(8 + payloadLen, true);
  if (crc16ccitt(bytes, 8 + payloadLen) !== rxCrc) return null;

  const p = 8; // payload start
  switch (msgType) {
    case PROTO.TYPE.TELEMETRY:
      return { type: "telemetry", seq, data: decodeTelemetry(dv, p) };
    case PROTO.TYPE.WAVEFORM:
      return { type: "waveform", seq, data: decodeWaveform(dv, bytes, p) };
    case PROTO.TYPE.HELLO:
      return { type: "hello", seq, raw: bytes.slice(p, p + payloadLen) };
    case PROTO.TYPE.ACK:
      return {
        type: "ack",
        seq,
        data: { ackedSeq: dv.getUint16(p, true), status: bytes[p + 2] },
      };
    default:
      return { type: "unknown", seq, msgType };
  }
}

// docs/PROTOCOL.md §3 — keep field order identical to ecu_telemetry_t
function decodeTelemetry(dv, o) {
  return {
    tUs: dv.getUint32(o + 0, true),
    rpm: dv.getUint16(o + 4, true),
    maf: dv.getUint16(o + 6, true),
    map: dv.getUint16(o + 8, true),
    iat: dv.getInt16(o + 10, true),
    ecuV: dv.getUint16(o + 12, true),
    sensorV: dv.getUint16(o + 14, true),
    coils: unpackByteBits(dv.getUint8(o + 16)),
    injReg: unpackByteBits(dv.getUint8(o + 17)),
    injGdi: unpackByteBits(dv.getUint8(o + 18)),
    status: unpackStatus(dv.getUint16(o + 19, true)),
    iac: dv.getUint8(o + 21),
    // NOTE: apply §3.1 scaling here (raw -> engineering units) once scales are set.
  };
}

// docs/PROTOCOL.md §4
function decodeWaveform(dv, bytes, o) {
  const channel = dv.getUint8(o + 0);
  const mode = dv.getUint8(o + 1);
  const t0Us = dv.getUint32(o + 2, true);
  const dtUs = dv.getUint32(o + 6, true);
  const count = dv.getUint16(o + 10, true);
  let d = o + 12;
  if (mode === PROTO.WAVE_MODE.SAMPLES) {
    const samples = new Array(count);
    for (let i = 0; i < count; i++, d += 2) samples[i] = dv.getInt16(d, true);
    return { channel, mode, t0Us, dtUs, samples };
  } else {
    const edges = new Array(count);
    for (let i = 0; i < count; i++, d += 5) {
      edges[i] = { tUs: dv.getUint32(d, true), level: dv.getUint8(d + 4) };
    }
    return { channel, mode, t0Us, edges };
  }
}

// ---- encode a COMMAND frame (browser -> server), docs/PROTOCOL.md §5 ----------
export function encodeCommand(cmdId, channel, value) {
  const payloadLen = 6; // uint8 + uint8 + int32
  const buf = new ArrayBuffer(8 + payloadLen + 2);
  const dv = new DataView(buf);
  dv.setUint8(0, PROTO.MAGIC0);
  dv.setUint8(1, PROTO.MAGIC1);
  dv.setUint8(2, PROTO.VERSION);
  dv.setUint8(3, PROTO.TYPE.COMMAND);
  dv.setUint16(4, nextSeq(), true);
  dv.setUint16(6, payloadLen, true);
  dv.setUint8(8, cmdId);
  dv.setUint8(9, channel);
  dv.setInt32(10, value | 0, true);
  const bytes = new Uint8Array(buf);
  dv.setUint16(8 + payloadLen, crc16ccitt(bytes, 8 + payloadLen), true);
  return buf;
}

let _seq = 0;
function nextSeq() {
  _seq = (_seq + 1) & 0xffff;
  return _seq;
}
