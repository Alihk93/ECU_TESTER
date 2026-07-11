#!/usr/bin/env python3
"""ECU_TESTER :: sim_server.py — desktop stand-in for the device.

Serves web/ over HTTP and speaks the binary WebSocket protocol on /ws
(docs/PROTOCOL.md: envelope + CRC16-CCITT-FALSE, TELEMETRY 0x01 at 30 Hz,
WAVEFORM 0x02 edge-lists), generating the same signals as the firmware
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
import struct
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WEB_ROOT = Path(__file__).resolve().parent.parent / "web"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

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
    return body + struct.pack("<H", crc16_ccitt(body))

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

# cam events as (crank angle within the 720° cycle, level-after-edge)
CMP1_EVENTS = ((40.0, 1), (130.0, 0))
CMP2_EVENTS = ((200.0, 1), (260.0, 0), (500.0, 1), (560.0, 0))

def clamp_u16(v, hi):
    return max(0, min(int(v + 0.5), hi))

def rpm_at(ts: float) -> float:
    lfo = math.sin(ts * 0.25 * 2 * math.pi)
    rev = 0.5 - 0.5 * math.cos(ts * 0.08 * 2 * math.pi)
    return 820.0 + 120.0 * lfo + 2600.0 * rev * rev

class Sim:
    """One per WebSocket client: crank-angle accumulator + latched firing bits."""

    def __init__(self):
        self.t0 = time.monotonic()
        self.prev_t = 0.0
        self.deg = 0.0  # total crank degrees since start

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
        print(f"[ws] client {self.client_address} connected")

        sim, seq = Sim(), 0
        try:
            while True:  # 30 Hz: one TELEMETRY + one WAVEFORM per channel
                tele, waves = sim.tick()
                frames = [make_frame(0x01, seq, telemetry_payload(**tele))]; seq += 1
                for ch, edges in waves.items():
                    if edges:
                        frames.append(make_frame(0x02, seq, waveform_payload(ch, edges[0][0], edges))); seq += 1
                self.connection.sendall(b"".join(ws_encode_binary(f) for f in frames))
                time.sleep(1 / 30)
        except (BrokenPipeError, ConnectionResetError, OSError):
            print(f"[ws] client {self.client_address} disconnected")

def main():
    ap = argparse.ArgumentParser(description="ECU_TESTER dashboard sim server")
    ap.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8090)))
    args = ap.parse_args()
    print(f"serving {WEB_ROOT} + protocol sim on http://localhost:{args.port}")
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()

if __name__ == "__main__":
    main()
