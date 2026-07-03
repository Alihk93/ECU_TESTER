/* ECU_TESTER :: VoltageMeter.js — ECU (0–25 V) and Sensor (0–5 V) digital meters
   with a bar. Consumes telemetry {ecuV, sensorV} in mV; applies /1000 scaling. */
import { C, curves } from "../theme.js";

function meter(name, unit, max) {
  const row = document.createElement("div");
  row.className = "vm-row";
  row.innerHTML = `
    <div class="vm-head"><span class="vm-name">${name}</span>
      <span class="vm-val">--<small>${unit}</small></span></div>
    <div class="vm-bar"><i></i></div>`;
  return {
    root: row,
    val: row.querySelector(".vm-val"),
    fill: row.querySelector(".vm-bar > i"),
    max,
  };
}

export class VoltageMeter {
  constructor(mount) {
    this.ecu = meter("ECU", "V", 25);
    this.sen = meter("Sensor", "V", 5);
    mount.appendChild(this.ecu.root);
    mount.appendChild(this.sen.root);
  }
  _set(m, v) {
    m.val.innerHTML = `${v.toFixed(2)}<small>V</small>`;
    const pct = Math.max(0, Math.min(100, (v / m.max) * 100));
    m.fill.style.width = pct + "%";
    m.fill.style.background = v < m.max * 0.4 ? C.warn : C.cyan;
  }
  update(data) {
    this._set(this.ecu, curves.ecuV(data.ecuV));
    this._set(this.sen, curves.sensorV(data.sensorV));
  }
}
