#!/usr/bin/env python3
"""ECU_TESTER :: sim_server.py — desktop stand-in for the device.

Serves web/ over HTTP and speaks the binary WebSocket protocol on /ws
(docs/PROTOCOL.md: envelope + CRC16-CCITT-FALSE, TELEMETRY 0x01 at 30 Hz,
WAVEFORM 0x02 edge-lists — off by default like the device, see --waveforms),
generating the same signals as the firmware
simulation in main.c — RPM breathing, mV analog values, status bits, walking
IAC phase, 1-5-3-6-2-4 firing order and the CKP 60-2 / CMP crank-synced edges.

Lets the dashboard be developed and tested with no hardware attached:

    python3 tools/sim_server.py [--port 8090]
    -> http://localhost:8090

This is a dev tool, NOT part of the contract. If docs/PROTOCOL.md changes,
update protocol.h + protocol.js first (the invariant), then this file.
Stdlib only — no pip dependencies.
"""
import argparse
import base64
import hashlib
import math
import os
import select
import struct
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WEB_ROOT = Path(__file__).resolve().parent.parent / "web"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# Mirrors the firmware default (s_wave_stream in main.c): WAVEFORM frames are
# not streamed — the dashboard's scope is parametric and never plots them.
# Run with --waveforms to exercise the browser's cheap-discard path.
SEND_WAVEFORMS = False

# Test flag (--corrupt): break every frame's CRC to verify the client rejects them.
CORRUPT_CRC = False

# ---- protocol framing (docs/PROTOCOL.md §1) --------------------------------

def crc16_ccitt(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc

assert crc16_ccitt(b"123456789") == 0x29B1  # standard check vector

def make_frame(msg_type: int, seq: int, payload: bytes) -> bytes:
    head = struct.pack("<BBBBHH", 0xA5, 0x5A, 0x01, msg_type, seq & 0xFFFF, len(payload))
    body = head + payload
    frame = body + struct.pack("<H", crc16_ccitt(body))
    if CORRUPT_CRC and payload:
        # flip one payload bit AFTER the CRC is computed -> every frame must fail
        # the receiver's CRC check. A correct client drops all of them (needle
        # freezes, WS/telemetry counter stops). A test tool, not the contract.
        f = bytearray(frame)
        f[8] ^= 0x01
        frame = bytes(f)
    return frame

def telemetry_payload(t_us, rpm, maf, map_, iat, ecu_v, sensor_v,
                      coils, inj_reg, inj_gdi, status, iac) -> bytes:
    return struct.pack("<IHHHHHHBBBHB", t_us & 0xFFFFFFFF, rpm, maf, map_, iat,
                       ecu_v, sensor_v, coils, inj_reg, inj_gdi, status, iac)

def waveform_payload(channel: int, t0_us: int, edges) -> bytes:
    head = struct.pack("<BBIIH", channel, 1, t0_us & 0xFFFFFFFF, 0, len(edges))
    return head + b"".join(struct.pack("<IB", t & 0xFFFFFFFF, lvl) for t, lvl in edges)

# ---- signal generation (mirrors acq_task / net_task sim in main.c) ---------

ST_BATTERY, ST_SWITCH, ST_START, ST_ETC, ST_FAN1, ST_FAN2 = 0, 1, 2, 3, 4, 5
ST_FUEL_PUMP, ST_IMMO_P, ST_IMMO_N, ST_MRC_P, ST_MRC_N = 8, 9, 10, 11, 12
FIRE_ORDER = (0, 4, 2, 5, 1, 3)  # 1-5-3-6-2-4

# cam events as (crank angle within the 720° cycle, level-after-edge). Mirrors the
# firmware net_task (main.c): CMP1 high on the first crank rev [0,360), CMP2 high on
# the second [360,720). (WAVEFORM is default-off and unplotted, but keep the two
# reference generators identical for when scope plotting is restored.)
CMP1_EVENTS = ((0.0, 1), (360.0, 0))
CMP2_EVENTS = ((0.0, 0), (360.0, 1))

def clamp_u16(v, hi):
    return max(0, min(int(v + 0.5), hi))

def rpm_at(ts: float) -> float:
    lfo = math.sin(ts * 0.25 * 2 * math.pi)
    rev = 0.5 - 0.5 * math.cos(ts * 0.08 * 2 * math.pi)
    return 820.0 + 120.0 * lfo + 2600.0 * rev * rev

class Sim:
    """One per WebSocket client: crank-angle accumulator + latched firing bits."""

    # cmd_id -> (telemetry field, clamp ceiling) for the analog/RPM overrides
    OVR_FIELDS = {0x01: ("maf", 5000), 0x02: ("map_", 5000), 0x03: ("iat", 5000),
                  0x04: ("ecu_v", 25000), 0x40: ("rpm", 8000)}

    def __init__(self):
        self.t0 = time.monotonic()
        self.prev_t = 0.0
        self.deg = 0.0  # total crank degrees since start
        # COMMAND/SUBSCRIBE state (docs/PROTOCOL.md §5), mirrors main.c
        self.ovr = {}                # field -> forced value; absent = generator runs
        self.force_coil = 0
        self.force_inj = 0
        self.status_set = 0
        self.status_clr = 0
        self.hz = 30
        self.waveforms = SEND_WAVEFORMS

    def apply_command(self, payload: bytes) -> int:
        if len(payload) != 6:
            return ACK_ERR_LEN
        cmd_id, channel, value = struct.unpack("<BBi", payload)
        if cmd_id in self.OVR_FIELDS:
            field, hi = self.OVR_FIELDS[cmd_id]
            if value < 0:
                self.ovr.pop(field, None)      # release back to AUTO
            else:
                self.ovr[field] = min(value, hi)
        elif cmd_id == 0x05:
            pass                                # ECU current: no telemetry v1 field
        elif cmd_id == 0x10 and channel < 8:
            self.force_coil = (self.force_coil | (1 << channel)) if value else (self.force_coil & ~(1 << channel))
        elif cmd_id == 0x11 and channel < 8:
            self.force_inj = (self.force_inj | (1 << channel)) if value else (self.force_inj & ~(1 << channel))
        elif cmd_id == 0x20 and channel < 16:
            if value:
                self.status_set |= 1 << channel; self.status_clr &= ~(1 << channel)
            else:
                self.status_clr |= 1 << channel; self.status_set &= ~(1 << channel)
        elif cmd_id == 0x30:                    # IAC: raw phase nibble (matches main.c)
            if value < 0:
                self.ovr.pop("iac", None)
            else:
                self.ovr["iac"] = value & 0x0F
        else:
            return ACK_ERR_TYPE
        return ACK_OK

    def apply_subscribe(self, payload: bytes) -> int:
        if len(payload) != 5:
            return ACK_ERR_LEN
        telemetry_hz, wave_channels, wave_mode, wave_window_ms = struct.unpack("<BBBH", payload)
        if telemetry_hz:
            self.hz = min(telemetry_hz, 60)
        self.waveforms = wave_channels != 0
        return ACK_OK

    def tick(self):
        """Advance to now; return (telemetry_fields, {ch: edge_list})."""
        now = time.monotonic() - self.t0
        dt = max(1e-4, now - self.prev_t)
        rpm = rpm_at(now)
        deg0, deg1 = self.deg, self.deg + rpm / 60.0 * 360.0 * dt
        t_of = lambda a: self.prev_t + (a - deg0) / (deg1 - deg0) * dt  # angle -> seconds
        us = lambda s: int(s * 1e6)

        # CKP 60-2: 6° per tooth, teeth 58/59 missing; edges rise at the tooth
        # start, fall half a tooth later.
        ckp = []
        k = math.floor(deg0 / 6.0) + 1
        while k * 6.0 <= deg1:
            if (k % 60) < 58:
                ckp.append((us(t_of(k * 6.0)), 1))
                ckp.append((us(t_of(k * 6.0 + 3.0)), 0))
            k += 1

        # CMP1 / CMP2: fixed events on the 720° cycle
        def cam(events):
            out = []
            m = math.floor(deg0 / 720.0)
            while m * 720.0 <= deg1:
                for ang, lvl in events:
                    a = m * 720.0 + ang
                    if deg0 < a <= deg1:
                        out.append((us(t_of(a)), lvl))
                m += 1
            return out

        # firing events: one cylinder every 120°, latched into this frame's bits
        fire = 0
        e = math.floor(deg0 / 120.0) + 1
        while e * 120.0 <= deg1:
            fire |= 1 << FIRE_ORDER[e % 6]
            e += 1

        # slow analog + status (same curves as acq_task)
        rev = 0.5 - 0.5 * math.cos(now * 0.08 * 2 * math.pi)
        map_kpa = 30.0 + rev * 68.0
        maf_gs = 4.0 + rev * 190.0 + (rpm - 820) * 0.01
        iat_c = 28.0 + 4.0 * math.sin(now * 0.05 * 2 * math.pi)
        status = ((1 << ST_BATTERY) | (1 << ST_SWITCH) | (1 << ST_ETC) |
                  (1 << ST_FUEL_PUMP) | (1 << ST_MRC_P) | (1 << ST_MRC_N))
        if rev > 0.30:
            status |= 1 << ST_FAN1
        if rev > 0.62:
            status |= 1 << ST_FAN2
        if now < 1.5:
            status |= 1 << ST_START

        tele = dict(
            t_us=us(now),
            rpm=clamp_u16(rpm, 8000),
            maf=clamp_u16(maf_gs / 400.0 * 5000.0, 5000),
            map_=clamp_u16(map_kpa / 105.0 * 5000.0, 5000),
            iat=clamp_u16((120.0 - iat_c) / 160.0 * 5000.0, 5000),
            ecu_v=clamp_u16(13800.0 + 150.0 * math.sin(now * 1.3 * 2 * math.pi), 25000),
            sensor_v=clamp_u16(2500.0 + 1800.0 * math.sin(now * 0.5 * 2 * math.pi), 5000),
            coils=fire, inj_reg=fire, inj_gdi=fire,
            status=status,
            iac=1 << ((us(now) // 150000) % 4),
        )
        # COMMAND overrides (docs/PROTOCOL.md §5) replace the generator field-by-field
        for field, val in self.ovr.items():
            tele[field] = val
        tele["coils"] |= self.force_coil
        tele["inj_reg"] |= self.force_inj
        tele["inj_gdi"] |= self.force_inj
        tele["status"] = (tele["status"] | self.status_set) & ~self.status_clr & 0xFFFF
        waves = {0: ckp, 1: cam(CMP1_EVENTS), 2: cam(CMP2_EVENTS)}
        self.prev_t, self.deg = now, deg1
        return tele, waves

# ---- HTTP + hand-rolled WebSocket server (stdlib only) ----------------------

def ws_encode_binary(payload: bytes) -> bytes:
    n = len(payload)
    if n <= 125:
        return bytes([0x82, n]) + payload
    if n < 65536:
        return bytes([0x82, 126]) + struct.pack(">H", n) + payload
    return bytes([0x82, 127]) + struct.pack(">Q", n) + payload

def ws_decode_frames(buf: bytes):
    """Parse complete client->server WS frames out of buf (client frames are always
    masked, RFC 6455 §5.3). Returns (frames, rest) where frames = [(opcode, payload)]
    and rest is the unconsumed tail (a partial frame carried to the next read)."""
    frames, i, n = [], 0, len(buf)
    while True:
        if n - i < 2:
            break
        b1 = buf[i + 1]
        opcode = buf[i] & 0x0F
        masked = b1 & 0x80
        ln = b1 & 0x7F
        j = i + 2
        if ln == 126:
            if n - j < 2:
                break
            ln = struct.unpack(">H", buf[j:j + 2])[0]; j += 2
        elif ln == 127:
            if n - j < 8:
                break
            ln = struct.unpack(">Q", buf[j:j + 8])[0]; j += 8
        mask = b""
        if masked:
            if n - j < 4:
                break
            mask = buf[j:j + 4]; j += 4
        if n - j < ln:
            break
        payload = bytearray(buf[j:j + ln])
        if masked:
            for k in range(ln):
                payload[k] ^= mask[k % 4]
        frames.append((opcode, bytes(payload)))
        i = j + ln
    return frames, buf[i:]

# ACK status codes — mirror main.c (docs/PROTOCOL.md §5: 0 = ok, non-zero = reason)
ACK_OK, ACK_ERR_LEN, ACK_ERR_CRC, ACK_ERR_TYPE = 0, 1, 2, 3

def decode_client_frame(data: bytes):
    """Validate an ECU envelope (magic/version/CRC). Returns (msg_type, seq, payload)
    or (None, seq, None) on a bad CRC, or None if malformed."""
    if len(data) < 10 or data[0] != 0xA5 or data[1] != 0x5A or data[2] != 0x01:
        return None
    seq = data[4] | (data[5] << 8)
    plen = data[6] | (data[7] << 8)
    if 8 + plen + 2 > len(data):
        return None
    rx_crc = data[8 + plen] | (data[8 + plen + 1] << 8)
    if crc16_ccitt(data[:8 + plen]) != rx_crc:
        return (None, seq, None)
    return (data[3], seq, data[8:8 + plen])

def ack_frame(seq: int, acked_seq: int, status: int) -> bytes:
    return make_frame(0x8F, seq, struct.pack("<HB", acked_seq & 0xFFFF, status & 0xFF))

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(WEB_ROOT), **kw)

    extensions_map = {**SimpleHTTPRequestHandler.extensions_map,
                      ".woff2": "font/woff2", ".js": "text/javascript"}

    def end_headers(self):  # dev server: never let the browser run stale code
        self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def log_message(self, fmt, *args):  # quieter: one line per request
        print(f"[http] {self.address_string()} {fmt % args}")

    def do_GET(self):
        if self.path == "/ws" and "websocket" in self.headers.get("Upgrade", "").lower():
            return self.serve_websocket()
        return super().do_GET()

    def serve_websocket(self):
        key = self.headers["Sec-WebSocket-Key"]
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.wfile.write(
            b"HTTP/1.1 101 Switching Protocols\r\n"
            b"Upgrade: websocket\r\nConnection: Upgrade\r\n"
            b"Sec-WebSocket-Accept: " + accept.encode() + b"\r\n\r\n")
        self.wfile.flush()
        print(f"[ws] client {self.client_address} connected")

        conn = self.connection
        sim, seq = Sim(), 0
        rxbuf = b""
        next_send = time.monotonic()
        try:
            while True:
                # Block until the socket is readable OR it's time for the next frame,
                # so client COMMAND/SUBSCRIBE frames are serviced between sends.
                r, _, _ = select.select([conn], [], [], max(0.0, next_send - time.monotonic()))
                if r:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break                              # client closed
                    rxbuf += chunk
                    msgs, rxbuf = ws_decode_frames(rxbuf)
                    out = b""
                    closing = False
                    for opcode, payload in msgs:
                        if opcode == 0x8:                  # WS close
                            closing = True; break
                        if opcode != 0x2:                  # only binary carries our protocol
                            continue
                        dec = decode_client_frame(payload)
                        if dec is None:
                            continue
                        mtype, cseq, body = dec
                        if mtype is None:
                            status = ACK_ERR_CRC
                        elif mtype == 0x80:
                            status = sim.apply_command(body)
                        elif mtype == 0x81:
                            status = sim.apply_subscribe(body)
                        else:
                            status = ACK_ERR_TYPE
                        out += ws_encode_binary(ack_frame(seq, cseq, status)); seq += 1
                        print(f"[ws] {'COMMAND' if mtype == 0x80 else 'SUBSCRIBE' if mtype == 0x81 else 'msg'} "
                              f"seq={cseq} -> ACK status={status}")
                    if out:
                        conn.sendall(out)
                    if closing:
                        break

                # TELEMETRY (+ WAVEFORM per channel if subscribed) at the current rate
                now = time.monotonic()
                if now >= next_send:
                    period = 1.0 / max(1, sim.hz)
                    next_send = max(next_send + period, now)   # no burst catch-up after a stall
                    tele, waves = sim.tick()
                    frames = [make_frame(0x01, seq, telemetry_payload(**tele))]; seq += 1
                    if sim.waveforms:
                        for ch, edges in waves.items():
                            if edges:
                                frames.append(make_frame(0x02, seq, waveform_payload(ch, edges[0][0], edges))); seq += 1
                    conn.sendall(b"".join(ws_encode_binary(f) for f in frames))
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        print(f"[ws] client {self.client_address} disconnected")

def main():
    global SEND_WAVEFORMS, CORRUPT_CRC
    ap = argparse.ArgumentParser(description="ECU_TESTER dashboard sim server")
    ap.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8090)))
    ap.add_argument("--host", default="127.0.0.1",
                    help="bind address — use 0.0.0.0 to accept LAN clients "
                         "(e.g. an Android TV / box on the same network)")
    ap.add_argument("--waveforms", action="store_true",
                    help="also stream WAVEFORM edge frames (device default is off)")
    ap.add_argument("--corrupt", action="store_true",
                    help="break every frame's CRC — a correct client must drop them all")
    args = ap.parse_args()
    SEND_WAVEFORMS = args.waveforms
    CORRUPT_CRC = args.corrupt
    if CORRUPT_CRC:
        print("!! --corrupt: sending BAD CRCs; a correct client freezes (drops all frames)")
    shown = "localhost" if args.host in ("127.0.0.1", "localhost") else args.host
    print(f"serving {WEB_ROOT} + protocol sim on http://{shown}:{args.port}")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()

if __name__ == "__main__":
    main()
