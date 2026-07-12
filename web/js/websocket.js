/* ECU_TESTER :: websocket.js — ES5 classic script (old TV browsers). Connection
   management with auto-reconnect. Owns the socket; hands decoded frames to a
   callback. Uses window.ECUProto (protocol.js). Exposes window.EcuSocket. */
(function (global) {
  "use strict";

  function EcuSocket(url, opts) {
    opts = opts || {};
    this.url = url;
    this.onFrame = opts.onFrame || function () {};
    this.onStatus = opts.onStatus || function () {};
    this.ws = null;
    this._retry = 500; // ms, backs off to 5s
  }
  EcuSocket.prototype.connect = function () {
    var self = this;
    this.onStatus("connecting");
    this.ws = new WebSocket(this.url);
    this.ws.binaryType = "arraybuffer";
    this.ws.onopen = function () { self._retry = 500; self.onStatus("connected"); };
    this.ws.onclose = function () { self.onStatus("disconnected"); self._reconnect(); };
    this.ws.onerror = function () { if (self.ws) self.ws.close(); };
    this.ws.onmessage = function (ev) {
      var frame = global.ECUProto.decodeFrame(ev.data); // null => CRC/format failure, dropped
      if (frame) self.onFrame(frame);
    };
  };
  EcuSocket.prototype.send = function (arrayBuffer) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(arrayBuffer);
  };
  EcuSocket.prototype._reconnect = function () {
    var self = this;
    setTimeout(function () { self.connect(); }, this._retry);
    this._retry = Math.min(this._retry * 2, 5000);
  };

  global.EcuSocket = EcuSocket;
})(window);
