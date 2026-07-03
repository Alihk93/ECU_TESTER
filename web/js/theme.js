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
// Layout mirrors the reference sketch: scope + sensor gauges + status down the
// left, the big RPM ("RBM") gauge with IAC in the centre, and the digital
// readout above the COIL / INJ / INJ-GDI stack on the right.
export const MODULES = [
  // ---- left column: oscilloscope, sensor gauges, status ----
  { id: "scope",     kind: "scope", title: "CKP / CMP Oscilloscope", rect: { x: 40, y: 24,  w: 600, h: 300 }, opts: {} },
  { id: "gauge-maf", kind: "gauge", title: "MAF", rect: { x: 40,  y: 340, w: 196, h: 200 }, opts: { cfg: "maf", field: "maf", curve: "maf" } },
  { id: "gauge-map", kind: "gauge", title: "MAP", rect: { x: 242, y: 340, w: 196, h: 200 }, opts: { cfg: "map", field: "map", curve: "map" } },
  { id: "gauge-iat", kind: "gauge", title: "IAT", rect: { x: 444, y: 340, w: 196, h: 200 }, opts: { cfg: "iat", field: "iat", curve: "iat" } },
  { id: "status",    kind: "status",title: "Status Indicators", rect: { x: 40, y: 556, w: 600, h: 452 }, opts: {} },

  // ---- centre: big RPM gauge + IAC ----
  { id: "gauge-rpm", kind: "gauge", title: "Engine RPM", rect: { x: 664, y: 90,  w: 420, h: 420 }, opts: { cfg: "rpm", field: "rpm", curve: null } },
  { id: "iac",       kind: "iac",   title: "IAC Stepper", rect: { x: 664, y: 540, w: 420, h: 220 }, opts: {} },

  // ---- right column: digital readout, then COIL / INJ / INJ-GDI ----
  { id: "voltages",  kind: "volts", title: "Voltage / Current",   rect: { x: 1120, y: 24,  w: 760, h: 150 }, opts: {} },
  { id: "coils",     kind: "coils", title: "Ignition Coils",      rect: { x: 1120, y: 190, w: 760, h: 236 }, opts: { field: "coils", count: 6 } },
  { id: "inj-reg",   kind: "inj",   title: "Injectors — Port",    rect: { x: 1120, y: 442, w: 760, h: 236 }, opts: { field: "injReg", img: "injector.png", count: 6 } },
  { id: "inj-gdi",   kind: "inj",   title: "Injectors — GDI",     rect: { x: 1120, y: 694, w: 760, h: 236 }, opts: { field: "injGdi", img: "gdi.png", count: 6 } },
];
