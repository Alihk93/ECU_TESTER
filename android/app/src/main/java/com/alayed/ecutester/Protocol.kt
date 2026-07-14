package com.alayed.ecutester

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ECU_TESTER binary protocol — Kotlin port of web/js/protocol.js.
 *
 * THIS FILE IMPLEMENTS docs/PROTOCOL.md. It is the FOURTH mirror of the contract
 * (docs/PROTOCOL.md <-> firmware protocol.h <-> web protocol.js <-> this). Change
 * all four together, in the same commit (CLAUDE.md §5 golden rule).
 *
 * All multi-byte fields are LITTLE-ENDIAN to match the ESP32-S3.
 */
object Protocol {

    const val VERSION = 0x01
    const val MAGIC0 = 0xA5
    const val MAGIC1 = 0x5A

    // message types (docs/PROTOCOL.md §2)
    const val TYPE_TELEMETRY = 0x01
    const val TYPE_WAVEFORM = 0x02
    const val TYPE_HELLO = 0x0F
    const val TYPE_COMMAND = 0x80
    const val TYPE_SUBSCRIBE = 0x81
    const val TYPE_ACK = 0x8F

    // status bit indices (docs/PROTOCOL.md §3.2)
    const val ST_BATTERY = 0
    const val ST_SWITCH = 1
    const val ST_START = 2
    const val ST_ETC = 3
    const val ST_FAN1 = 4
    const val ST_FAN2 = 5
    const val ST_FUEL_PUMP = 8
    const val ST_IMMO_P = 9
    const val ST_IMMO_N = 10
    const val ST_MRC_P = 11
    const val ST_MRC_N = 12

    /** Decoded TELEMETRY payload (docs/PROTOCOL.md §3). Analog fields are raw mV
     *  at the ECU pin — the UI applies each sensor's transfer curve. */
    data class Telemetry(
        val tUs: Long,
        val rpm: Int,
        val maf: Int,
        val map: Int,
        val iat: Int,        // signed (int16 on the wire)
        val ecuV: Int,
        val sensorV: Int,
        val coils: Int,      // 8 latched activity bits
        val injReg: Int,
        val injGdi: Int,
        val status: Int,     // uint16 bitfield, see ST_* above
        val iac: Int,        // stepper phase bits 0..3
    ) {
        fun status(bit: Int): Boolean = (status ushr bit) and 1 == 1
        /** activity bit for channel 1..8 in a latched byte (coils/injReg/injGdi) */
        fun bit(byteVal: Int, channel1to8: Int): Boolean =
            (byteVal ushr (channel1to8 - 1)) and 1 == 1
    }

    sealed class Frame {
        abstract val seq: Int
        data class TelemetryFrame(override val seq: Int, val data: Telemetry) : Frame()
        data class WaveformFrame(override val seq: Int, val mode: Int) : Frame()
        data class HelloFrame(override val seq: Int) : Frame()
        data class AckFrame(override val seq: Int, val ackedSeq: Int, val status: Int) : Frame()
        data class UnknownFrame(override val seq: Int, val msgType: Int) : Frame()
    }

    /** CRC16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflect, no final XOR. */
    fun crc16(bytes: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /**
     * Decode one WebSocket binary frame. Verifies magic, version, length and CRC;
     * returns null on any failure (caller drops the frame — mirrors protocol.js).
     */
    fun decodeFrame(bytes: ByteArray): Frame? {
        if (bytes.size < 10) return null
        if (u8(bytes, 0) != MAGIC0 || u8(bytes, 1) != MAGIC1) return null
        if (u8(bytes, 2) != VERSION) return null

        val msgType = u8(bytes, 3)

        // WAVEFORM isn't plotted (the scope is parametric) — bail out BEFORE the
        // CRC pass, exactly like protocol.js. A bitwise CRC over a ~1 KB edge list
        // per frame is the biggest per-message cost, and nothing reads the payload
        // but the mode byte, so a corrupt one is harmless.
        if (msgType == TYPE_WAVEFORM) {
            return Frame.WaveformFrame(seq = u16(bytes, 4), mode = u8(bytes, 9))
        }

        val seq = u16(bytes, 4)
        val payloadLen = u16(bytes, 6)
        val total = 8 + payloadLen + 2
        if (bytes.size < total) return null

        val rxCrc = u16(bytes, 8 + payloadLen)
        if (crc16(bytes, 8 + payloadLen) != rxCrc) return null

        return when (msgType) {
            TYPE_TELEMETRY -> Frame.TelemetryFrame(seq, decodeTelemetry(bytes, 8))
            TYPE_HELLO -> Frame.HelloFrame(seq)
            TYPE_ACK -> Frame.AckFrame(seq, ackedSeq = u16(bytes, 8), status = u8(bytes, 10))
            else -> Frame.UnknownFrame(seq, msgType)
        }
    }

    // docs/PROTOCOL.md §3 — field order identical to ecu_telemetry_t (22 bytes)
    private fun decodeTelemetry(b: ByteArray, o: Int): Telemetry {
        val tUs = (u16(b, o) or (u16(b, o + 2) shl 16)).toLong() and 0xFFFFFFFFL
        val iatRaw = u16(b, o + 10)
        val iat = if (iatRaw >= 0x8000) iatRaw - 0x10000 else iatRaw   // int16
        return Telemetry(
            tUs = tUs,
            rpm = u16(b, o + 4),
            maf = u16(b, o + 6),
            map = u16(b, o + 8),
            iat = iat,
            ecuV = u16(b, o + 12),
            sensorV = u16(b, o + 14),
            coils = u8(b, o + 16),
            injReg = u8(b, o + 17),
            injGdi = u8(b, o + 18),
            status = u16(b, o + 19),
            iac = u8(b, o + 21),
        )
    }

    /** Encode a COMMAND frame (browser->server), docs/PROTOCOL.md §5. */
    fun encodeCommand(cmdId: Int, channel: Int, value: Int, seq: Int): ByteArray {
        val payloadLen = 6
        val buf = ByteBuffer.allocate(8 + payloadLen + 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC0.toByte()); buf.put(MAGIC1.toByte())
        buf.put(VERSION.toByte()); buf.put(TYPE_COMMAND.toByte())
        buf.putShort((seq and 0xFFFF).toShort())
        buf.putShort(payloadLen.toShort())
        buf.put(cmdId.toByte()); buf.put(channel.toByte())
        buf.putInt(value)
        val bytes = buf.array()
        val crc = crc16(bytes, 8 + payloadLen)
        bytes[8 + payloadLen] = (crc and 0xFF).toByte()
        bytes[8 + payloadLen + 1] = ((crc ushr 8) and 0xFF).toByte()
        return bytes
    }
}
