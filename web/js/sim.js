/* ECU_TESTER :: sim.js — client-side engine simulator.
   Emits objects with the SAME shape as protocol.js decodeFrame() output, so the
   dashboard runs with no hardware and swapping to a live EcuSocket is a one-liner.
   Models a 60-2 CKP wheel, two cam pulses, and an 8-cylinder fire sequence. */

const IDLE = 850;
const CKP_TEETH = 60, CKP_MISSING = 2, TOOTH_DEG = 360 / CKP_TEETH;

export class Sim {
  constructor(onFrame) {
    this.onFrame = onFrame;
    this.seq = 0;
    this.tUs = 0;
    this.crank = 0;           // absolute crank angle (deg)
    this.rpm = IDLE;
    this.level = { 0: 0, 1: 0, 2: 0 };
    this.fireAngle = { 0: -1, 1: -1 }; // last crank cycle a coil/inj was scheduled
    this.p = {
      running: true, rpm: IDLE, maf: 6, map: 32, iat: 28, ecuV: 13.8, current: 8,
    };
    this.status = { battery: 1, switch: 1, start: 0, etc: 0, fan1: 0, fan2: 0,
      fuelPump: 1, immoP: 0, immoN: 0, mrcP: 1, mrcN: 0 };
    this._last = performance.now();
  }

  start() {
    this._timer = setInterval(() => this._tick(), 20); // 50 Hz telemetry
    return this;
  }
  stop() { clearInterval(this._timer); }

  set(key, value) {
    if (key === "running") { this.p.running = !!value; return; }
    this.p[key] = value;
  }
  toggle(key, v) { this.status[key] = v ? 1 : 0; }

  _tick() {
    const now = performance.now();
    let dt = (now - this._last) / 1000;
    this._last = now;
    if (dt > 0.1) dt = 0.1;

    // rpm easing (idle when running, spins down when off)
    const targetRpm = this.p.running ? Math.max(IDLE, this.p.rpm) : 0;
    this.rpm += (targetRpm - this.rpm) * Math.min(1, dt * 3);
    if (this.rpm < 30) this.rpm = 0;

    const dtUs = Math.round(dt * 1e6);
    const t0 = this.tUs;
    this.tUs += dtUs;

    const fromA = this.crank;
    const dDeg = (this.rpm / 60) * 360 * dt;
    const toA = fromA + dDeg;
    this.crank = toA;

    const angToUs = (a) => t0 + ((a - fromA) / (dDeg || 1)) * dtUs;

    // ---- CKP / CMP edges over this interval ----
    if (dDeg > 0) {
      this._emitCKP(fromA, toA, angToUs);
      this._emitCMP(1, 0, 40, fromA, toA, angToUs);      // CMP1: pulse 0–40° each 720
      this._emitCMP(2, 360, 400, fromA, toA, angToUs);   // CMP2: pulse 360–400°
    }

    // ---- coil / injector activity latched over the interval ----
    const coils = new Array(8).fill(0);
    const injReg = new Array(8).fill(0);
    const injGdi = new Array(8).fill(0);
    if (this.rpm > 0) {
      // 8 events per 720° crank cycle, 90° apart
      const c0 = Math.ceil(fromA / 90), c1 = Math.floor(toA / 90);
      for (let k = c0; k <= c1; k++) {
        const cyl = ((k % 8) + 8) % 8;
        coils[cyl] = 1;
        injReg[cyl] = 1;
        injGdi[cyl] = 1;
      }
    }

    // ---- analog -> mV (inverse of theme.curves) ----
    const jitter = (x, a) => x + (Math.random() - 0.5) * a;
    const data = {
      tUs: this.tUs,
      rpm: Math.round(this.rpm),
      maf: this._mv(jitter(this.p.maf, 3) / 400 * 5000),
      map: this._mv(jitter(this.p.map, 1.5) / 105 * 5000),
      iat: this._mv((120 - this.p.iat) / 160 * 5000),
      ecuV: this._mv(jitter(this.p.ecuV, 0.05) * 1000, 25000),
      sensorV: this._mv(jitter(5.0, 0.02) * 1000, 5000),
      coils, injReg, injGdi,
      status: { ...this.status },
      iac: this.rpm > 0 ? (1 << (Math.floor(this.tUs / 60000) & 3)) : 0,
    };
    this._send({ type: "telemetry", seq: this._seq(), data });
  }

  _mv(v, max = 5000) { return Math.max(0, Math.min(max, Math.round(v))); }

  _emitCKP(fromA, toA, angToUs) {
    const edges = [];
    // events every 3° (rise at slot start, fall at slot mid), skip 2 missing teeth
    const k0 = Math.ceil(fromA / 3), k1 = Math.floor(toA / 3);
    for (let k = k0; k <= k1; k++) {
      const ang = k * 3;
      const slot = Math.floor(ang / TOOTH_DEG) % CKP_TEETH;
      const present = slot < CKP_TEETH - CKP_MISSING;
      const atStart = (ang % TOOTH_DEG) === 0;
      let lvl = null;
      if (present && atStart) lvl = 1;
      else if (present && (ang % TOOTH_DEG) === TOOTH_DEG / 2) lvl = 0;
      if (lvl !== null && lvl !== this.level[0]) {
        this.level[0] = lvl;
        edges.push({ tUs: Math.round(angToUs(ang)), level: lvl });
      }
    }
    if (edges.length) this._sendWave(0, edges);
  }

  _emitCMP(ch, hiStart, hiEnd, fromA, toA, angToUs) {
    const edges = [];
    // check transitions at cycle-relative boundaries within [fromA,toA)
    const cyc = 720;
    const marks = [];
    const base = Math.floor(fromA / cyc) * cyc;
    for (let c = base; c <= toA + cyc; c += cyc) {
      marks.push({ a: c + hiStart, lvl: 1 }, { a: c + hiEnd, lvl: 0 });
    }
    marks.sort((m, n) => m.a - n.a);
    for (const m of marks) {
      if (m.a > fromA && m.a <= toA && m.lvl !== this.level[ch]) {
        this.level[ch] = m.lvl;
        edges.push({ tUs: Math.round(angToUs(m.a)), level: m.lvl });
      }
    }
    if (edges.length) this._sendWave(ch, edges);
  }

  _sendWave(channel, edges) {
    this._send({ type: "waveform", seq: this._seq(),
      data: { channel, mode: 1, t0Us: edges[0].tUs, edges } });
  }

  _seq() { this.seq = (this.seq + 1) & 0xffff; return this.seq; }
  _send(frame) { this.onFrame(frame); }
}
