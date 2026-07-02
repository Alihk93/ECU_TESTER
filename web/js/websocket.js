/* ECU_TESTER :: websocket.js — connection management with auto-reconnect.
   Owns the socket; hands decoded frames to a callback. Uses protocol.js. */
import { decodeFrame } from "./protocol.js";

export class EcuSocket {
  constructor(url, { onFrame, onStatus } = {}) {
    this.url = url;
    this.onFrame = onFrame || (() => {});
    this.onStatus = onStatus || (() => {});
    this.ws = null;
    this._retry = 500; // ms, backs off to 5s
  }
  connect() {
    this.ws = new WebSocket(this.url);
    this.ws.binaryType = "arraybuffer";
    this.ws.onopen = () => { this._retry = 500; this.onStatus(true); };
    this.ws.onclose = () => { this.onStatus(false); this._reconnect(); };
    this.ws.onerror = () => this.ws && this.ws.close();
    this.ws.onmessage = (ev) => {
      const frame = decodeFrame(ev.data); // null => CRC/format failure, dropped
      if (frame) this.onFrame(frame);
    };
  }
  send(arrayBuffer) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(arrayBuffer);
  }
  _reconnect() {
    setTimeout(() => this.connect(), this._retry);
    this._retry = Math.min(this._retry * 2, 5000);
  }
}
