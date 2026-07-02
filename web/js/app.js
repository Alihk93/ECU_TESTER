/* ECU_TESTER :: app.js — bootstrap. Connects, fans telemetry/waveform frames out
   to components, and sends COMMAND/SUBSCRIBE. Component modules are added under
   web/js/ per docs/ADDING_COMPONENTS.md (Gauge, VoltageMeter, Indicator,
   WaveformView, InjectorAnimation, CoilIndicator). */
import { EcuSocket } from "./websocket.js";
// import { encodeCommand } from "./protocol.js"; // used by the controls panel

const WS_URL = `ws://${location.host}/ws`; // served from the device at 10.10.10.10
const connEl = document.getElementById("conn");

const sock = new EcuSocket(WS_URL, {
  onStatus(up) {
    connEl.textContent = up ? "connected" : "disconnected";
    connEl.className = "conn " + (up ? "conn--up" : "conn--down");
  },
  onFrame(frame) {
    switch (frame.type) {
      case "telemetry":
        // TODO: dispatch frame.data to gauges / voltages / coils / injectors / status
        break;
      case "waveform":
        // TODO: push frame.data into the scope renderer (canvas)
        break;
      case "hello":
        // TODO: read channel counts / capabilities, build the UI accordingly
        break;
    }
  },
});
sock.connect();

// SIMULATION fallback: if no device is reachable, a JS-only generator can drive
// the same component handlers so the UI demos with no hardware (docs/SIMULATION.md).
