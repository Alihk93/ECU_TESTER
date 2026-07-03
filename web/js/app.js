/* ECU_TESTER :: app.js — bootstrap. Builds modules on the free-canvas stage,
   selects a frame source (sim by default; live EcuSocket on demand), and routes
   decoded frames to components. Sim frames share protocol.js's output shape. */
import { MODULES, curves } from "./theme.js";
import { Layout } from "./layout.js";
import { Sim } from "./sim.js";
import { EcuSocket } from "./websocket.js";
import { encodeCommand } from "./protocol.js";
import { Gauge } from "./components/Gauge.js";
import { VoltageMeter } from "./components/VoltageMeter.js";
import { StatusPanel } from "./components/StatusPanel.js";
import { CoilBank } from "./components/CoilBank.js";
import { InjectorBank } from "./components/InjectorBank.js";
import { IacPanel } from "./components/IacPanel.js";
import { WaveformScope } from "./components/WaveformScope.js";
import { SimControls } from "./components/SimControls.js";

const stage = document.getElementById("stage");
const layout = new Layout(stage);
const inst = new Map(); // id -> { kind, opts, comp }

let sim = null, socket = null, source = "sim";

// COMMAND cmd_id map (PROTOCOL.md §5) for the live path
const CMD = { maf: 0x01, map: 0x02, iat: 0x03, ecuV: 0x04, current: 0x05, rpm: 0x40 };
const STATUS_BIT = { battery: 0, switch: 1, start: 2, etc: 3, fan1: 4, fan2: 5,
  fuelPump: 8, immoP: 9, immoN: 10, mrcP: 11, mrcN: 12 };

function build() {
  for (const m of MODULES) {
    const body = layout.register(m);
    let comp = null;
    switch (m.kind) {
      case "gauge":    comp = new Gauge(body, m.opts); break;
      case "volts":    comp = new VoltageMeter(body); break;
      case "status":   comp = new StatusPanel(body); break;
      case "coils":    comp = new CoilBank(body, m.opts); break;
      case "inj":      comp = new InjectorBank(body, m.opts); break;
      case "iac":      comp = new IacPanel(body); break;
      case "scope":    comp = new WaveformScope(body); break;
      case "controls": comp = new SimControls(body, controlHooks()); break;
    }
    inst.set(m.id, { ...m, comp });
  }
}

function controlHooks() {
  return {
    set: (key, value) => {
      if (sim) sim.set(key, value);
      if (source === "live" && CMD[key] != null) {
        socket?.send(encodeCommand(CMD[key], 0, Math.round(toRaw(key, value))));
      }
    },
    toggle: (key, v) => {
      if (sim) sim.toggle(key, v);
      if (source === "live" && STATUS_BIT[key] != null) {
        socket?.send(encodeCommand(0x20, STATUS_BIT[key], v));
      }
    },
  };
}
// engineering value -> wire raw for the live COMMAND path
function toRaw(key, v) {
  if (key === "maf") return v / 400 * 5000;
  if (key === "map") return v / 105 * 5000;
  if (key === "iat") return (120 - v) / 160 * 5000;
  if (key === "ecuV") return v * 1000;
  return v;
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

// ---- source switching ----------------------------------------------------
function startSim() {
  source = "sim";
  socket = null;
  sim = new Sim(onFrame).start();
  setConn("sim");
}
function startLive() {
  source = "live";
  if (sim) { sim.stop(); sim = null; }
  const host = location.host === "localhost:8080" ? "10.10.10.10" : location.host;
  socket = new EcuSocket(`ws://${host}/ws`, {
    onFrame, onStatus: (up) => setConn(up ? "live" : "down"),
  });
  socket.connect();
  setConn("down");
}
function setConn(state) {
  const el = document.getElementById("conn");
  el.className = "conn conn--" + state;
  el.textContent = { sim: "simulation", live: "connected", down: "connecting…" }[state];
}

// ---- topbar --------------------------------------------------------------
document.getElementById("edit").addEventListener("click", (e) => {
  const on = !layout.editing;
  layout.setEditing(on);
  e.currentTarget.classList.toggle("active", on);
  e.currentTarget.textContent = on ? "Lock" : "Edit";
});
document.getElementById("reset").addEventListener("click", () => layout.reset(MODULES));
document.getElementById("src").addEventListener("click", (e) => {
  if (source === "sim") { startLive(); e.currentTarget.textContent = "Sim"; }
  else { startSim(); e.currentTarget.textContent = "Live"; }
});

build();
startSim();
