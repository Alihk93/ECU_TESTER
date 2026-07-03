/* ECU_TESTER :: WaveformScope.js — canvas oscilloscope for CKP / CMP1 / CMP2.
   Consumes WAVEFORM edge-list frames (mode 1) and draws three scrolling square-wave
   lanes over a rolling time window, so the 60-2 missing tooth and cam sync are
   visible. RPM (from telemetry) is shown in the corner. */
import { C } from "../theme.js";

const LANES = [
  { ch: 0, name: "CKP", color: C.amber },
  { ch: 1, name: "CMP1", color: C.cyan },
  { ch: 2, name: "CMP2", color: C.blue },
];
const WINDOW_US = 90000; // ~90 ms rolling window (~1 crank rev at idle)
const KEEP_US = 400000;

export class WaveformScope {
  constructor(mount) {
    this.rpm = 0;
    this.tEnd = 0;
    this.edges = { 0: [], 1: [], 2: [] };
    this.canvas = document.createElement("canvas");
    this.canvas.className = "scope-canvas";
    mount.appendChild(this.canvas);
    this.ctx = this.canvas.getContext("2d");
    this._resize();
    new ResizeObserver(() => this._resize()).observe(mount);
    this._raf();
  }

  _resize() {
    const r = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.max(1, r.width * dpr);
    this.canvas.height = Math.max(1, r.height * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.W = r.width;
    this.H = r.height;
  }

  pushWaveform(data) {
    if (data.mode !== 1 || !data.edges) return;
    const buf = this.edges[data.channel];
    for (const e of data.edges) {
      buf.push(e);
      if (e.tUs > this.tEnd) this.tEnd = e.tUs;
    }
  }
  setRpm(rpm) { this.rpm = rpm; }

  _prune() {
    const cutoff = this.tEnd - KEEP_US;
    for (const k of [0, 1, 2]) {
      const b = this.edges[k];
      let i = 0;
      while (i < b.length - 1 && b[i + 1].tUs < cutoff) i++;
      if (i > 0) b.splice(0, i);
    }
  }

  _raf() {
    const draw = () => {
      const { ctx, W, H } = this;
      ctx.clearRect(0, 0, W, H);
      ctx.fillStyle = "#0a1626";
      ctx.fillRect(0, 0, W, H);
      this._prune();

      const t0 = this.tEnd - WINDOW_US;
      const laneH = H / LANES.length;
      // grid
      ctx.strokeStyle = "rgba(90,140,190,.14)";
      ctx.lineWidth = 1;
      for (let x = 0; x <= W; x += W / 10) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, H); ctx.stroke(); }
      for (let i = 1; i < LANES.length; i++) { ctx.beginPath(); ctx.moveTo(0, i * laneH); ctx.lineTo(W, i * laneH); ctx.stroke(); }

      LANES.forEach((lane, li) => {
        const b = this.edges[lane.ch];
        const yHi = li * laneH + laneH * 0.28;
        const yLo = li * laneH + laneH * 0.82;
        const xOf = (t) => ((t - t0) / WINDOW_US) * W;
        ctx.strokeStyle = lane.color;
        ctx.lineWidth = lane.ch === 0 ? 1.5 : 2;
        ctx.shadowColor = lane.color;
        ctx.shadowBlur = lane.ch === 0 ? 2 : 5;
        ctx.beginPath();
        let started = false, prevY = yLo;
        // find first edge at/just before window start
        let start = 0;
        for (let i = 0; i < b.length; i++) { if (b[i].tUs <= t0) start = i; else break; }
        for (let i = start; i < b.length; i++) {
          const e = b[i];
          const x = Math.max(0, xOf(e.tUs));
          const y = e.level ? yHi : yLo;
          if (!started) { ctx.moveTo(0, y); prevY = y; started = true; }
          ctx.lineTo(x, prevY);
          ctx.lineTo(x, y);
          prevY = y;
        }
        if (started) ctx.lineTo(W, prevY);
        ctx.stroke();
        ctx.shadowBlur = 0;
        // label
        ctx.fillStyle = lane.color;
        ctx.font = "600 13px ui-monospace, monospace";
        ctx.fillText(lane.name, 8, li * laneH + 16);
      });

      // rpm badge
      ctx.fillStyle = C.text;
      ctx.font = "700 20px ui-monospace, monospace";
      ctx.textAlign = "right";
      ctx.fillText(`${Math.round(this.rpm)} rpm`, W - 10, 22);
      ctx.textAlign = "left";
      requestAnimationFrame(draw);
    };
    requestAnimationFrame(draw);
  }
}
