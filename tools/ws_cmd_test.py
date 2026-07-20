#!/usr/bin/env python3
"""ECU_TESTER :: ws_cmd_test.py — exercise the COMMAND/SUBSCRIBE channel end-to-end.

Drives the browser->server command path (docs/PROTOCOL.md §5) against a running
server and checks the ACKs + the telemetry response. Works against the dev sim or
a real ESP32 — no hardware-specific assumptions.

    python3 tools/sim_server.py &                 # or point --host at the device
    python3 tools/ws_cmd_test.py                  # localhost:8090
    python3 tools/ws_cmd_test.py --host 10.10.10.10 --port 80

Checks: SUBSCRIBE ACK ok · COMMAND ACK ok · bad-CRC rejected (ACK status 2) ·
WAVEFORM streaming turns on after SUBSCRIBE · RPM override forces the value ·
releasing the override (value < 0) hands the field back to the generator.

Exit code 0 = all checks passed. Stdlib only.
"""
import argparse
import base64
import os
import socket
import struct
import sys
import time

# ---- ECU protocol framing (docs/PROTOCOL.md §1/§5) --------------------------

def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc

def frame(msg_type: int, seq: int, payload: bytes) -> bytes:
    head = struct.pack("<BBBBHH", 0xA5, 0x5A, 0x01, msg_type, seq & 0xFFFF, len(payload))
    body = head + payload
    return body + struct.pack("<H", crc16(body))

def command(cmd_id, channel, value, seq):
    return frame(0x80, seq, struct.pack("<BBi", cmd_id, channel, value))

def subscribe(hz, wave_channels, seq, wave_mode=1, window_ms=200):
    return frame(0x81, seq, struct.pack("<BBBH", hz, wave_channels, wave_mode, window_ms))

def decode_ecu(payload: bytes):
    if len(payload) < 10 or payload[0] != 0xA5 or payload[1] != 0x5A:
        return None
    mtype = payload[3]
    plen = payload[6] | (payload[7] << 8)
    return mtype, payload[8:8 + plen]

# ---- minimal WebSocket client (stdlib) --------------------------------------

def ws_send(sock, payload: bytes):
    """One masked binary frame — client->server frames MUST be masked (RFC 6455)."""
    n = len(payload)
    hdr = bytearray([0x82])
    if n <= 125:
        hdr.append(0x80 | n)
    elif n < 65536:
        hdr.append(0x80 | 126); hdr += struct.pack(">H", n)
    else:
        hdr.append(0x80 | 127); hdr += struct.pack(">Q", n)
    mask = os.urandom(4)
    hdr += mask
    sock.sendall(bytes(hdr) + bytes(payload[i] ^ mask[i % 4] for i in range(n)))

def _decode_one(buf: bytes):
    if len(buf) < 2:
        return None, buf
    ln = buf[1] & 0x7F
    opcode = buf[0] & 0x0F
    j = 2
    if ln == 126:
        if len(buf) < 4:
            return None, buf
        ln = struct.unpack(">H", buf[2:4])[0]; j = 4
    elif ln == 127:
        if len(buf) < 10:
            return None, buf
        ln = struct.unpack(">Q", buf[2:10])[0]; j = 10
    if len(buf) < j + ln:
        return None, buf
    return (opcode, buf[j:j + ln]), buf[j + ln:]

class Ws:
    def __init__(self, sock, leftover=b""):
        self.sock = sock
        self.buf = leftover

    def recv_frame(self, timeout=2.0):
        self.sock.settimeout(timeout)
        while True:
            f, self.buf = _decode_one(self.buf)
            if f is not None:
                return f
            data = self.sock.recv(4096)
            if not data:
                return None
            self.buf += data

def connect(host, port):
    s = socket.create_connection((host, port), timeout=5)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET /ws HTTP/1.1\r\nHost: {host}:{port}\r\n"
               "Upgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
    s.settimeout(5)
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = s.recv(1024)
        if not chunk:
            raise ConnectionError("server closed during handshake")
        resp += chunk
    if b"101" not in resp.split(b"\r\n", 1)[0]:
        raise ConnectionError(f"no 101 Switching Protocols: {resp[:80]!r}")
    _, _, rest = resp.partition(b"\r\n\r\n")
    return Ws(s, rest), s

# ---- the test ---------------------------------------------------------------

def rpm_of(body: bytes) -> int:
    return struct.unpack_from("<H", body, 4)[0]   # rpm at telemetry payload offset 4

def collect(ws, seconds):
    """Drain frames for a window; return (acks{acked_seq:status}, [rpm...], saw_wave)."""
    acks, rpms, saw_wave = {}, [], False
    end = time.time() + seconds
    while time.time() < end:
        try:
            f = ws.recv_frame(timeout=max(0.05, end - time.time()))
        except socket.timeout:
            break
        if f is None:
            break
        opcode, payload = f
        if opcode == 0x8:
            break
        if opcode != 0x2:
            continue
        dec = decode_ecu(payload)
        if not dec:
            continue
        mtype, body = dec
        if mtype == 0x8F and len(body) >= 3:
            acked, status = struct.unpack("<HB", body[:3])
            acks[acked] = status
        elif mtype == 0x01:
            rpms.append(rpm_of(body))
        elif mtype == 0x02:
            saw_wave = True
    return acks, rpms, saw_wave

def main():
    ap = argparse.ArgumentParser(description="ECU_TESTER COMMAND/SUBSCRIBE channel test")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8090)
    args = ap.parse_args()

    print(f"connecting to ws://{args.host}:{args.port}/ws")
    ws, s = connect(args.host, args.port)

    sub_seq, cmd_seq, bad_seq = 1, 2, 3
    ws_send(s, subscribe(10, 0x07, sub_seq))         # 10 Hz + all 3 waveform channels
    ws_send(s, command(0x40, 0, 3000, cmd_seq))      # force RPM = 3000
    bad = bytearray(command(0x40, 0, 1234, bad_seq)) # deliberately corrupt CRC
    bad[8] ^= 0xFF
    ws_send(s, bytes(bad))

    acks, rpms, saw_wave = collect(ws, 3.0)

    results = []
    def check(name, cond):
        results.append(cond)
        print(("  PASS  " if cond else "  FAIL  ") + name)

    print("checks:")
    check("SUBSCRIBE ACK status 0", acks.get(sub_seq) == 0)
    check("COMMAND ACK status 0", acks.get(cmd_seq) == 0)
    check("bad-CRC COMMAND rejected (ACK status 2)", acks.get(bad_seq) == 2)
    check("WAVEFORM streams after SUBSCRIBE", saw_wave)
    check(f"RPM forced to 3000 (last={rpms[-1] if rpms else 'none'})",
          bool(rpms) and abs(rpms[-1] - 3000) <= 50)

    # release the override; the generator should resume (values start varying again)
    ws_send(s, command(0x40, 0, -1, 4))
    _, rpms2, _ = collect(ws, 2.0)
    check(f"RPM override released ({len(set(rpms2))} distinct values seen)",
          len(set(rpms2)) > 1)

    s.close()
    ok = all(results)
    print(f"\n{'ALL CHECKS PASSED' if ok else 'SOME CHECKS FAILED'}")
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
