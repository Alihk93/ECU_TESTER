/* =============================================================================
 *  ECU_TESTER :: theme.js
 *  Single source of truth for palette, sensor transfer curves, gauge geometry,
 *  and the default free-canvas layout. Everything visual/scaling lives here so
 *  new modules can be added without touching component internals.
 * =============================================================================*/

// ---- Cobalt palette (VS Code "Cobalt2"-flavoured, near-black base) ------------
export const C = {
  bg0: "#08101c",       // page base, near-black cobalt
  bg1: "#0d1a2b",       // stage
  surface: "#12263c",   // module body
  surface2: "#193549",  // module header / raised
  line: "#204461",      // borders
  text: "#e8f1fb",
  dim: "#8fa9c4",
  amber: "#ffc600",     // cobalt2 accent
  cyan: "#2dd4bf",
  blue: "#3aa0ff",
  needle: "#ff3b30",    // red needle, like the reference cluster
  ok: "#38e08a",
  off: "#33475f",
  warn: "#ff6a3d",
  spark: "#8fd3ff",
};

// ---- Sensor transfer curves: decoder gives raw mV; UI shows engineering units.
//      (PROTOCOL.md §3.1 — curve knowledge lives in the UI, editable per profile.)
export const curves = {
  maf: (mv) => (mv / 5000) * 400,          // 0..5000 mV -> 0..400 g/s
  map: (mv) => (mv / 5000) * 105,          // 0..5000 mV -> 0..105 kPa
  iat: (mv) => 120 - (mv / 5000) * 160,    // NTC: 0..5000 mV -> +120..-40 °C
  ecuV: (mv) => mv / 1000,                 // 0..25000 mV -> 0..25 V
  sensorV: (mv) => mv / 1000,              // 0..5000 mV -> 0..5 V
};

// ---- Circular gauge presets (clean-cluster style, like the attached photo) ----
// angles: sweep clockwise from startDeg through sweepDeg (screen deg, 0 = up).
export const gaugeCfg = {
  rpm:    { label: "RPM",  unit: "x1000 rpm", min: 0, max: 8,   ticks: 8,  redline: 6.5, dec: 1, big: true },
  maf:    { label: "MAF",  unit: "g/s",       min: 0, max: 400, ticks: 8,  redline: 360, dec: 0 },
  map:    { label: "MAP",  unit: "kPa",       min: 0, max: 105, ticks: 7,  redline: 100, dec: 0 },
  iat:    { label: "IAT",  unit: "°C",        min: -40, max: 120, ticks: 8, redline: 100, dec: 0 },
};

// ---- Status tell-tales: which signals, their glyph id, and active colour ------
// glyph ids resolve to inline SVGs in components/glyphs.js. IMMO uses the photo.
export const statusTells = [
  { key: "battery",  label: "Battery",   glyph: "battery",  color: C.ok,   activeColor: C.warn },
  { key: "switch",   label: "Switch",    glyph: "key",      color: C.dim,  activeColor: C.amber },
  { key: "start",    label: "Start",     glyph: "power",    color: C.dim,  activeColor: C.ok },
  { key: "etc",      label: "ETC",       glyph: "throttle", color: C.dim,  activeColor: C.amber },
  { key: "fan1",     label: "Fan 1",     glyph: "fan",      color: C.dim,  activeColor: C.cyan },
  { key: "fan2",     label: "Fan 2",     glyph: "fan",      color: C.dim,  activeColor: C.cyan },
  { key: "fuelPump", label: "Fuel Pump", glyph: "pump",     color: C.dim,  activeColor: C.ok },
  { key: "immoP",    label: "IMMO +",    glyph: "immo",     color: C.dim,  activeColor: C.warn },
  { key: "immoN",    label: "IMMO −",    glyph: "immo",     color: C.dim,  activeColor: C.warn },
  { key: "mrcP",     label: "MRC +",     glyph: "relay",    color: C.dim,  activeColor: C.ok },
  { key: "mrcN",     label: "MRC −",     glyph: "relay",    color: C.dim,  activeColor: C.ok },
];

// ---- Design canvas + default module layout (px in a 1920x1032 design space) ---
export const STAGE = { w: 1920, h: 1032 };

// Each module: id, kind, title, default rect, and kind-specific opts.
export const MODULES = [
  { id: "gauge-rpm", kind: "gauge", title: "Engine RPM",  rect: { x: 40,   y: 24,  w: 384, h: 384 }, opts: { cfg: "rpm", field: "rpm", curve: null } },
  { id: "gauge-maf", kind: "gauge", title: "MAF",         rect: { x: 440,  y: 24,  w: 384, h: 384 }, opts: { cfg: "maf", field: "maf", curve: "maf" } },
  { id: "gauge-map", kind: "gauge", title: "MAP",         rect: { x: 40,   y: 424, w: 250, h: 250 }, opts: { cfg: "map", field: "map", curve: "map" } },
  { id: "gauge-iat", kind: "gauge", title: "IAT",         rect: { x: 306,  y: 424, w: 250, h: 250 }, opts: { cfg: "iat", field: "iat", curve: "iat" } },
  { id: "voltages",  kind: "volts", title: "Voltage",     rect: { x: 572,  y: 424, w: 252, h: 250 }, opts: {} },
  { id: "status",    kind: "status",title: "Status Indicators", rect: { x: 40, y: 690, w: 784, h: 322 }, opts: {} },

  { id: "coils",     kind: "coils", title: "Ignition Coils",      rect: { x: 852, y: 24,  w: 566, h: 252 }, opts: { field: "coils", count: 8 } },
  { id: "inj-reg",   kind: "inj",   title: "Injectors — Port",    rect: { x: 852, y: 292, w: 566, h: 252 }, opts: { field: "injReg", img: "injector.png" } },
  { id: "inj-gdi",   kind: "inj",   title: "Injectors — GDI",     rect: { x: 852, y: 560, w: 566, h: 252 }, opts: { field: "injGdi", img: "gdi.png" } },
  { id: "iac",       kind: "iac",   title: "IAC Stepper",         rect: { x: 852, y: 828, w: 566, h: 184 }, opts: {} },

  { id: "scope",     kind: "scope", title: "CKP / CMP Oscilloscope", rect: { x: 1436, y: 24,  w: 468, h: 452 }, opts: {} },
  { id: "controls",  kind: "controls", title: "Simulation Controls", rect: { x: 1436, y: 492, w: 468, h: 520 }, opts: {} },
];
