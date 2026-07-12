/* =============================================================================
 *  ECU_TESTER :: protocol.js  —  ES5 classic script (runs on old TV browsers;
 *  no ES modules / ES6 syntax). Exposes window.ECUProto.
 *
 *  THIS FILE IMPLEMENTS docs/PROTOCOL.md. It MUST stay byte-for-byte in lockstep
 *  with firmware/main/include/protocol.h. Change all three together.
 *  All reads/writes are LITTLE-ENDIAN (DataView(..., true)) to match the ESP32-S3.
 * =============================================================================*/
(function (global) {
  "use strict";

  var PROTO = {
    VERSION: 0x01,
    MAGIC0: 0xa5,
    MAGIC1: 0x5a,
    TYPE: { TELEMETRY: 0x01, WAVEFORM: 0x02, HELLO: 0x0f, COMMAND: 0x80, SUBSCRIBE: 0x81, ACK: 0x8f },
    WAVE_CH: { CKP: 0, CMP1: 1, CMP2: 2 },
    WAVE_MODE: { SAMPLES: 0, EDGES: 1 }
  };

  // status bit indices (docs/PROTOCOL.md §3.2)
  var STATUS_BITS = [
    "battery", "switch", "start", "etc", "fan1", "fan2",
    "rsv6", "rsv7", "fuelPump", "immoP", "immoN",
    "mrcP", "mrcN", "rsv13", "rsv14", "rsv15"
  ];

  // ---- CRC16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflect, no final XOR ----
  function crc16ccitt(bytes, len) {
    if (len === undefined) len = bytes.length;
    var crc = 0xffff;
    for (var i = 0; i < len; i++) {
      crc ^= bytes[i] << 8;
      for (var b = 0; b < 8; b++) {
        crc = crc & 0x8000 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
      }
    }
    return crc & 0xffff;
  }

  // unpack the little-endian status bitfield into a named object
  function unpackStatus(bits) {
    var out = {};
    for (var i = 0; i < STATUS_BITS.length; i++) out[STATUS_BITS[i]] = (bits >> i) & 1;
    return out;
  }
  function unpackByteBits(byte) {
    var a = new Array(8);
    for (var i = 0; i < 8; i++) a[i] = (byte >> i) & 1;
    return a;
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
      iac: dv.getUint8(o + 21)
    };
  }

  // docs/PROTOCOL.md §4 — edge lists into parallel typed arrays (GC-friendly)
  function decodeWaveform(dv, bytes, o) {
    var channel = dv.getUint8(o + 0);
    var mode = dv.getUint8(o + 1);
    var t0Us = dv.getUint32(o + 2, true);
    var dtUs = dv.getUint32(o + 6, true);
    var count = dv.getUint16(o + 10, true);
    var d = o + 12, i;
    if (mode === PROTO.WAVE_MODE.SAMPLES) {
      var samples = new Array(count);
      for (i = 0; i < count; i++, d += 2) samples[i] = dv.getInt16(d, true);
      return { channel: channel, mode: mode, t0Us: t0Us, dtUs: dtUs, samples: samples };
    }
    var tUs = new Uint32Array(count);
    var level = new Uint8Array(count);
    for (i = 0; i < count; i++, d += 5) {
      tUs[i] = dv.getUint32(d, true);
      level[i] = dv.getUint8(d + 4);
    }
    return { channel: channel, mode: mode, t0Us: t0Us, count: count, tUs: tUs, level: level };
  }

  /**
   * Decode one incoming WebSocket binary frame (ArrayBuffer). Verifies magic,
   * version, length, CRC. Returns null on any failure (caller drops the frame).
   */
  function decodeFrame(arrayBuffer) {
    var dv = new DataView(arrayBuffer);
    var bytes = new Uint8Array(arrayBuffer);
    if (bytes.length < 10) return null;
    if (bytes[0] !== PROTO.MAGIC0 || bytes[1] !== PROTO.MAGIC1) return null;
    if (bytes[2] !== PROTO.VERSION) return null;

    var msgType = bytes[3];
    var seq = dv.getUint16(4, true);
    var payloadLen = dv.getUint16(6, true);
    var total = 8 + payloadLen + 2;
    if (bytes.length < total) return null;

    var rxCrc = dv.getUint16(8 + payloadLen, true);
    if (crc16ccitt(bytes, 8 + payloadLen) !== rxCrc) return null;

    var p = 8; // payload start
    switch (msgType) {
      case PROTO.TYPE.TELEMETRY:
        return { type: "telemetry", seq: seq, data: decodeTelemetry(dv, p) };
      case PROTO.TYPE.WAVEFORM:
        // scope is parametric now — skip decoding the (unused) edge list to avoid
        // per-frame typed-array allocation + GC churn on weak TV browsers
        return { type: "waveform", seq: seq, data: { mode: bytes[p + 1] } };
      case PROTO.TYPE.HELLO:
        return { type: "hello", seq: seq, raw: bytes.subarray(p, p + payloadLen) };
      case PROTO.TYPE.ACK:
        return { type: "ack", seq: seq, data: { ackedSeq: dv.getUint16(p, true), status: bytes[p + 2] } };
      default:
        return { type: "unknown", seq: seq, msgType: msgType };
    }
  }

  var _seq = 0;
  function nextSeq() { _seq = (_seq + 1) & 0xffff; return _seq; }

  // ---- encode a COMMAND frame (browser -> server), docs/PROTOCOL.md §5 ----------
  function encodeCommand(cmdId, channel, value) {
    var payloadLen = 6; // uint8 + uint8 + int32
    var buf = new ArrayBuffer(8 + payloadLen + 2);
    var dv = new DataView(buf);
    dv.setUint8(0, PROTO.MAGIC0);
    dv.setUint8(1, PROTO.MAGIC1);
    dv.setUint8(2, PROTO.VERSION);
    dv.setUint8(3, PROTO.TYPE.COMMAND);
    dv.setUint16(4, nextSeq(), true);
    dv.setUint16(6, payloadLen, true);
    dv.setUint8(8, cmdId);
    dv.setUint8(9, channel);
    dv.setInt32(10, value | 0, true);
    var bytes = new Uint8Array(buf);
    dv.setUint16(8 + payloadLen, crc16ccitt(bytes, 8 + payloadLen), true);
    return buf;
  }

  global.ECUProto = {
    PROTO: PROTO,
    STATUS_BITS: STATUS_BITS,
    crc16ccitt: crc16ccitt,
    decodeFrame: decodeFrame,
    encodeCommand: encodeCommand
  };
})(window);
