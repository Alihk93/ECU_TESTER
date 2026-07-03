/* ECU_TESTER :: Gauge.js — clean-cluster circular gauge (SVG).
   Static face (ticks/numerals/redline) is drawn once; update(value) only rotates
   the needle and refreshes the digital readout. Style mirrors the reference photo:
   thin white ticks, crisp numerals, red needle, subtle cobalt glow. */
import { C, gaugeCfg } from "../theme.js";

const SVGNS = "http://www.w3.org/2000/svg";
const START = 135;   // screen degrees (CW from east): lower-left
const SWEEP = 270;   // 270° sweep, 90° gap at bottom
const R = 90, CX = 100, CY = 100;

const pol = (deg, r) => {
  const a = (deg * Math.PI) / 180;
  return [CX + r * Math.cos(a), CY + r * Math.sin(a)];
};
const el = (n, a = {}) => {
  const e = document.createElementNS(SVGNS, n);
  for (const k in a) e.setAttribute(k, a[k]);
  return e;
};

export class Gauge {
  constructor(mount, opts) {
    this.cfg = gaugeCfg[opts.cfg];
    this.value = this.cfg.min;
    this.target = this.cfg.min;

    const svg = el("svg", { viewBox: "0 0 200 200", class: "gauge" });
    // outer ring
    svg.appendChild(el("circle", { cx: CX, cy: CY, r: R + 6, class: "gauge-ring" }));

    // redline arc
    if (this.cfg.redline != null) {
      const a0 = this.valToDeg(this.cfg.redline), a1 = this.valToDeg(this.cfg.max);
      const [x0, y0] = pol(a0, R), [x1, y1] = pol(a1, R);
      const large = a1 - a0 > 180 ? 1 : 0;
      svg.appendChild(el("path", {
        d: `M ${x0} ${y0} A ${R} ${R} 0 ${large} 1 ${x1} ${y1}`,
        class: "gauge-redline",
      }));
    }

    // ticks + numerals
    const n = this.cfg.ticks, minor = 5;
    for (let i = 0; i <= n; i++) {
      const v = this.cfg.min + (i / n) * (this.cfg.max - this.cfg.min);
      const deg = this.valToDeg(v);
      const [xa, ya] = pol(deg, R), [xb, yb] = pol(deg, R - 11);
      svg.appendChild(el("line", { x1: xa, y1: ya, x2: xb, y2: yb, class: "gauge-major" }));
      const [xt, yt] = pol(deg, R - 24);
      const t = el("text", { x: xt, y: yt, class: "gauge-num" });
      t.textContent = this.cfg.dec ? v.toFixed(0) : String(Math.round(v));
      svg.appendChild(t);
      if (i < n) {
        for (let m = 1; m < minor; m++) {
          const dv = this.valToDeg(v + (m / minor) * (this.cfg.max - this.cfg.min) / n);
          const [pa, qa] = pol(dv, R), [pb, qb] = pol(dv, R - 6);
          svg.appendChild(el("line", { x1: pa, y1: qa, x2: pb, y2: qb, class: "gauge-minor" }));
        }
      }
    }

    // needle + hub
    this.needle = el("line", { x1: CX, y1: CY, x2: CX, y2: CY - (R - 16), class: "gauge-needle" });
    this.needleG = el("g");
    this.needleG.appendChild(this.needle);
    svg.appendChild(this.needleG);
    svg.appendChild(el("circle", { cx: CX, cy: CY, r: 8, class: "gauge-hub" }));

    // unit label
    const u = el("text", { x: CX, y: CY + 46, class: "gauge-unit" });
    u.textContent = this.cfg.unit;
    svg.appendChild(u);

    mount.appendChild(svg);
    const read = document.createElement("div");
    read.className = "gauge-readout";
    read.innerHTML = `<span class="gv">--</span>`;
    mount.appendChild(read);
    this.readEl = read.querySelector(".gv");

    this._raf();
  }

  valToDeg(v) {
    const t = (v - this.cfg.min) / (this.cfg.max - this.cfg.min);
    return START + Math.max(0, Math.min(1, t)) * SWEEP;
  }

  update(value) {
    this.target = value;
    // data-driven readout so the number stays live even if rAF is throttled
    const over = this.cfg.redline != null && value >= this.cfg.redline;
    this.readEl.textContent = value.toFixed(this.cfg.dec);
    this.readEl.style.color = over ? C.warn : C.text;
  }

  _raf() {
    const step = () => {
      this.value += (this.target - this.value) * 0.18; // ease toward target
      const deg = this.valToDeg(this.value);
      this.needleG.setAttribute("transform", `rotate(${deg - 270} ${CX} ${CY})`);
      const over = this.cfg.redline != null && this.value >= this.cfg.redline;
      this.needle.style.stroke = over ? C.warn : C.needle;
      this.readEl.textContent = this.value.toFixed(this.cfg.dec);
      this.readEl.style.color = over ? C.warn : C.text;
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }
}
