/* ECU_TESTER :: SimControls.js — manual sliders + toggles. In sim mode they drive
   the client simulator; in live mode app.js also forwards them as COMMAND frames.
   Contract: opts.set(key, value) for analog, opts.toggle(key, bool) for digital. */
import { statusTells } from "../theme.js";

const SLIDERS = [
  { key: "running", type: "toggle", label: "Engine Running", def: true },
  { key: "rpm",     label: "RPM",         min: 700, max: 7000, step: 10, def: 850,  unit: "" },
  { key: "maf",     label: "MAF",         min: 0,   max: 400,  step: 1,  def: 6,    unit: "g/s" },
  { key: "map",     label: "MAP",         min: 0,   max: 105,  step: 1,  def: 32,   unit: "kPa" },
  { key: "iat",     label: "IAT",         min: -40, max: 120,  step: 1,  def: 28,   unit: "°C" },
  { key: "ecuV",    label: "ECU Voltage", min: 0,   max: 25,   step: 0.1,def: 13.8, unit: "V" },
  { key: "current", label: "ECU Current", min: 0,   max: 60,   step: 0.5,def: 8,    unit: "A" },
];

export class SimControls {
  constructor(mount, opts) {
    this.set = opts.set || (() => {});
    this.toggle = opts.toggle || (() => {});
    const wrap = document.createElement("div");
    wrap.className = "controls";

    for (const s of SLIDERS) {
      if (s.type === "toggle") {
        const t = this._toggle(s.label, s.def, (v) => this.set(s.key, v));
        wrap.appendChild(t);
      } else {
        wrap.appendChild(this._slider(s));
      }
    }

    const tg = document.createElement("div");
    tg.className = "toggle-grid";
    for (const t of statusTells) {
      tg.appendChild(this._toggle(t.label, false, (v) => this.toggle(t.key, v ? 1 : 0)));
    }
    wrap.appendChild(this._sub("Digital Lines"));
    wrap.appendChild(tg);
    mount.appendChild(wrap);
  }

  _sub(txt) {
    const d = document.createElement("div");
    d.className = "ctl-sub";
    d.textContent = txt;
    return d;
  }

  _slider(s) {
    const row = document.createElement("label");
    row.className = "ctl-slider";
    row.innerHTML = `
      <span class="ctl-name">${s.label}</span>
      <input type="range" min="${s.min}" max="${s.max}" step="${s.step}" value="${s.def}"/>
      <span class="ctl-val">${s.def}<small>${s.unit}</small></span>`;
    const input = row.querySelector("input");
    const val = row.querySelector(".ctl-val");
    input.addEventListener("input", () => {
      val.innerHTML = `${input.value}<small>${s.unit}</small>`;
      this.set(s.key, parseFloat(input.value));
    });
    this.set(s.key, s.def);
    return row;
  }

  _toggle(label, def, cb) {
    const row = document.createElement("label");
    row.className = "ctl-toggle";
    row.innerHTML = `<input type="checkbox" ${def ? "checked" : ""}/>
      <span class="sw"></span><span class="ctl-name">${label}</span>`;
    const input = row.querySelector("input");
    input.addEventListener("change", () => cb(input.checked));
    if (def) cb(true);
    return row;
  }
}
