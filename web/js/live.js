/* ECU_TESTER :: live.js — ES5 classic script (old TV browsers). Wires the
   device's binary WebSocket stream into the dashboard: window.ECUProto decodes
   the frames (via window.EcuSocket) and this file maps the decoded fields onto
   the ECU display API exposed by app.js.

   Analog fields arrive as raw mV at the ECU pin (PROTOCOL.md §3.1); the mini
   gauges display volts, so the only scaling here is ÷1000. CTS/IGF have no
   telemetry field in protocol v1 — their needles stay at rest. Same for CURRENT
   and the CAN traces (decorative). */
(function () {
  "use strict";
  var EcuSocket = window.EcuSocket;

  function applyTelemetry(d) {
    ECU.setLive();
    ECU.setRpm(d.rpm);
    ECU.setVoltage(d.ecuV / 1000);
    ECU.setSensor("MAF", d.maf / 1000);
    ECU.setSensor("MAP", d.map / 1000);
    ECU.setSensor("IAT", d.iat / 1000);
    ECU.setSensor("5V", d.sensorV / 1000);
    ECU.setCoils(d.coils);
    ECU.setInjectors(d.injReg);
    ECU.setGdi(d.injGdi);
    ECU.setIacPhases([d.iac & 1, d.iac & 2, d.iac & 4, d.iac & 8]);

    // status bits (PROTOCOL.md §3.2) -> cells + indicator row.
    // HIP (high-pressure pump) and PFC both show the one Fuel Pump bit for now.
    var s = d.status;
    ECU.setStatusCells({ fan1: s.fan1, fan2: s.fan2, imo: s.immoP || s.immoN, hip: s.fuelPump });
    ECU.setIndicators({
      "BAT-ON": s.battery, "SW-ON": s.switch, "MRC+": s.mrcP, "MRC-": s.mrcN,
      "ETC-ON": s.etc, "ST-OFF": s.start, "PFC-OFF": s.fuelPump
    });
  }

  // Coalesce telemetry -> DOM: frames can arrive in bursts (Wi-Fi power save on
  // the display device), and applying each one janks weak renderers. Keep only
  // the latest frame, OR the latched firing bits across skipped frames so no
  // coil/injector pulse is lost, and apply on an 80 ms tick (~12.5 Hz).
  var pending = null;

  // live counters read by the diagnostic overlay (js/diag.js)
  var stats = (window.__ecuStats = { msgs: 0, telem: 0, lastTelemMs: 0, msgRate: 0 });

  function onFrame(frame) {
    stats.msgs++;
    if (frame.type === "telemetry") {
      stats.telem++;
      stats.lastTelemMs = performance.now();
      var d = frame.data;
      if (pending) {
        for (var i = 0; i < 8; i++) {
          d.coils[i] |= pending.coils[i];
          d.injReg[i] |= pending.injReg[i];
          d.injGdi[i] |= pending.injGdi[i];
        }
      }
      pending = d;
    }
    // WAVEFORM frames are counted (stats.msgs++) but NOT forwarded: the scope is
    // a parametric standing display and protocol.js deliberately bails out of a
    // WAVEFORM frame before decoding its payload (only the mode byte survives), so
    // there is no channel/edge data here to plot. To actually plot waveforms,
    // restore full decodeWaveform() in protocol.js AND a real ECU.feedWaveform() —
    // as a matched pair — then re-add the forwarding here.
  }

  // rAF-paced (web/README.md rule): rAF self-throttles to what the device can
  // paint, so a TV managing 9 FPS gets 9 DOM applies/s instead of a fixed 12.5
  // it can't paint. 80 ms floor keeps healthy displays at ~12.5 Hz.
  var lastApply = 0;
  function applyTick(t) {
    if (pending && t - lastApply >= 80) {
      lastApply = t;
      applyTelemetry(pending);
      pending = null;
    }
    requestAnimationFrame(applyTick);
  }
  requestAnimationFrame(applyTick);

  // Served from the device, location.host is 10.10.10.10. For desktop preview
  // against real hardware, override with ?device=10.10.10.10.
  var host = (window.ECUqs && window.ECUqs("device")) || location.host || "10.10.10.10";

  var socket = new EcuSocket("ws://" + host + "/ws", {
    onFrame: onFrame,
    onStatus: function (st) {
      ECU.setConnected(st === "connected");
      if (st === "connected") {
        ECU.resetUptime();
        // zero the readouts that have no protocol v1 source (demo defaults)
        ECU.setCurrent(0);
        ECU.setSensor("CTS", 0);
        ECU.setSensor("IGF", 0);
      }
    }
  });
  socket.connect();

  // Watchdog: TV/tablet Wi-Fi power save can kill the TCP stream with no close
  // event — the dashboard then freezes at the last frame. If telemetry goes
  // stale while the socket claims to be open, close it; EcuSocket reconnects.
  setInterval(function () {
    if (socket.ws && socket.ws.readyState === WebSocket.OPEN &&
        stats.lastTelemMs && performance.now() - stats.lastTelemMs > 2500) {
      stats.lastTelemMs = 0; // one shot per stall; resets on the next frame
      socket.ws.close();
    }
  }, 1000);
})();
