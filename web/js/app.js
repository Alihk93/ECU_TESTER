/* ECU_TESTER :: app.js — bootstrap. Builds the modules on the free-canvas stage,
   connects to the device over WebSocket, and routes decoded frames to the
   components. Real-time only: the dashboard consumes live device frames and
   shows the connection state (connecting / connected / disconnected). */
import { MODULES, curves } from "./theme.js";
import { Layout } from "./layout.js";
import { EcuSocket } from "./websocket.js";
import { Gauge } from "./components/Gauge.js";
import { VoltageMeter } from "./components/VoltageMeter.js";
import { StatusPanel } from "./components/StatusPanel.js";
import { CoilBank } from "./components/CoilBank.js";
import { InjectorBank } from "./components/InjectorBank.js";
import { IacPanel } from "./components/IacPanel.js";
import { WaveformScope } from "./components/WaveformScope.js";

const stage = document.getElementById("stage");
const layout = new Layout(stage);
const inst = new Map(); // id -> { kind, opts, comp }

function build() {
  for (const m of MODULES) {
    const body = layout.register(m);
    let comp = null;
    switch (m.kind) {
      case "gauge":  comp = new Gauge(body, m.opts); break;
      case "volts":  comp = new VoltageMeter(body); break;
      case "status": comp = new StatusPanel(body); break;
      case "coils":  comp = new CoilBank(body, m.opts); break;
      case "inj":    comp = new InjectorBank(body, m.opts); break;
      case "iac":    comp = new IacPanel(body); break;
      case "scope":  comp = new WaveformScope(body); break;
    }
    inst.set(m.id, { ...m, comp });
  }
}

function onFrame(frame) {
  if (frame.type === "telemetry") dispatchTelemetry(frame.data);
  else if (frame.type === "waveform") inst.get("scope").comp.pushWaveform(frame.data);
}

function dispatchTelemetry(d) {
  for (const { kind, opts, comp } of inst.values()) {
    switch (kind) {
      case "gauge": {
        let v = d[opts.field];
        if (opts.curve) v = curves[opts.curve](v);
        else if (opts.field === "rpm") v = v / 1000;
        comp.update(v);
        break;
      }
      case "volts":  comp.update(d); break;
      case "status": comp.update(d.status); break;
      case "coils":  comp.update(d.coils); break;
      case "inj":    comp.update(d[opts.field]); break;
      case "iac":    comp.update(d.iac); break;
      case "scope":  comp.setRpm(d.rpm); break;
    }
  }
}

// ---- live device connection ----------------------------------------------
// onStatus emits "connecting" | "connected" | "disconnected" (see websocket.js).
function setConn(state) {
  const el = document.getElementById("conn");
  el.className = "conn conn--" + state;
  el.textContent = {
    connecting: "connecting…",
    connected: "connected",
    disconnected: "disconnected",
  }[state] || state;
}

function connect() {
  // Served from the device, location.host is 10.10.10.10; for desktop preview
  // (localhost:8080) point the socket at the device AP.
  const host = location.host === "localhost:8080" ? "10.10.10.10" : location.host;
  const socket = new EcuSocket(`ws://${host}/ws`, { onFrame, onStatus: setConn });
  socket.connect();
}

build();
connect();
