/* ==========================================================================
   ECU TESTER Dashboard — AL-AYED
   Display logic + public ECU API for hooking up live data.
   ========================================================================== */
(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';

  /* ---------------- State (defaults match the approved design) ------------- */
  var state = {
    rpm: 5000,
    connected: true,
    voltage: 30.0,
    current: 0.12,
    sensors: { CTS: 1.24, MAF: 0.86, MAP: 2.42, IAT: 1.98, '5V': 5.01, IGF: 0.52 },
    scopeColor: '#3fe7e1',
    showCursor: true,
    iacPhases: [true, true, true, true],
    live: false, // set on the first real device frame; gates the demo loops
  };

  var SENSOR_ORDER = ['CTS', 'MAF', 'MAP', 'IAT', '5V', 'IGF'];

  /* ---------------- Helpers ---------------- */
  function $(id) { return document.getElementById(id); }

  function polar(cx, cy, r, aDeg) {
    var a = (aDeg * Math.PI) / 180;
    return { x: cx + r * Math.sin(a), y: cy - r * Math.cos(a) };
  }

  /* ================= RPM gauge ================= */
  // 0 rpm at lower-left (225° clockwise from top), 270° sweep to 8000 rpm.
  var rpmAngle = function (v) { return 225 + (v / 8000) * 270; };

  function buildRpmDial() {
    var g = $('rpm-ticks');
    for (var v = 0; v <= 8000; v += 250) {
      var isMajor = v % 1000 === 0;
      var a = rpmAngle(v);
      var o = polar(200, 200, 184, a);
      var i = polar(200, 200, isMajor ? 158 : 171, a);
      var line = document.createElementNS(SVG_NS, 'line');
      line.setAttribute('x1', o.x.toFixed(1));
      line.setAttribute('y1', o.y.toFixed(1));
      line.setAttribute('x2', i.x.toFixed(1));
      line.setAttribute('y2', i.y.toFixed(1));
      line.setAttribute('stroke', isMajor ? '#e6ecf2' : '#5c6773');
      line.setAttribute('stroke-width', isMajor ? '3' : '1.5');
      g.appendChild(line);
    }
    var rs = polar(200, 200, 176, rpmAngle(6500));
    var re = polar(200, 200, 176, rpmAngle(8000));
    $('rpm-redline').setAttribute('d',
      'M' + rs.x.toFixed(1) + ' ' + rs.y.toFixed(1) +
      ' A176 176 0 0 1 ' + re.x.toFixed(1) + ' ' + re.y.toFixed(1));
  }

  var rpmDrawn = { angle: -999, text: '' };

  function updateRpm() {
    var rpm = Math.max(0, Math.min(8000, state.rpm));
    var angle = Math.round(rpmAngle(rpm) * 10) / 10; // 0.1° steps: skip no-op redraws
    if (angle !== rpmDrawn.angle) {
      rpmDrawn.angle = angle;
      // CSS transform (not the SVG attribute) so the transition in style.css
      // glides the needle between the throttled live updates
      $('rpm-needle').style.transform = 'rotate(' + angle + 'deg)';
    }
    var text = Math.round(state.rpm).toLocaleString('en-US');
    if (text !== rpmDrawn.text) {
      rpmDrawn.text = text;
      $('rpm-reading').textContent = text;
    }
    updateTempo();
  }

  /* Animation tempo scales with RPM: fans, sparks, sprays, crank-derived traces.
     Quantized to 250-rpm buckets: writing :root custom properties invalidates
     style for the whole document, so with 30 Hz live telemetry it must not run
     per frame — TV/kiosk browsers visibly jank on every CSS animation. */
  var tempoBucket = -1;
  function updateTempo() {
    var bucket = Math.round(Math.max(0, Math.min(8000, state.rpm)) / 250);
    if (bucket === tempoBucket) return;
    tempoBucket = bucket;
    var n = Math.max(0, Math.min(1, (bucket * 250) / 8000));
    var cycle = 1.7 - n * 0.95; // ~1.7s at idle, ~0.75s at redline
    var css = document.documentElement.style;
    // floor the period so a redline engine can't spin the fans absurdly fast:
    // 0.55 s/rev caps the rotated-bitmap resamples the weak TV must do (no visible
    // change below ~5000 rpm; the sim never reaches the cap)
    css.setProperty('--fan-dur-a', Math.max(0.55, 1.8 - n * 1.55).toFixed(2) + 's');
    css.setProperty('--fan-dur-b', Math.max(0.55, 2.0 - n * 1.65).toFixed(2) + 's');
    css.setProperty('--spark-dur', cycle.toFixed(2) + 's');
    css.setProperty('--spray-dur', (cycle * 0.92).toFixed(2) + 's');
    css.setProperty('--gdi-dur', (cycle * 0.88).toFixed(2) + 's');
  }

  /* ================= Indicators ================= */
  var INDICATORS = [
    { name: 'BAT-ON',  color: 'red',   img: 'assets/img/bat.png',      cls: 'indicator--bat',  alt: 'Battery warning' },
    { name: 'SW-ON',   color: 'red',   img: 'assets/img/swon.png',     cls: 'indicator--swon', alt: 'Switch on / key' },
    { name: 'MRC+',    color: 'green', img: 'assets/img/relay-nc.png', cls: '',                alt: 'Relay MRC+' },
    { name: 'MRC-',    color: 'red',   img: 'assets/img/relay-no.png', cls: '',                alt: 'Relay MRC-' },
    { name: 'ETC-ON',  color: 'red',   img: 'assets/img/relay-no.png', cls: '',                alt: 'Relay ETC-ON' },
    { name: 'ST-OFF',  color: 'green', img: 'assets/img/relay-nc.png', cls: '',                alt: 'Relay ST-OFF' },
    { name: 'PFC-OFF', color: 'green', img: 'assets/img/relay-nc.png', cls: '',                alt: 'Relay PFC-OFF' },
  ];

  var indicatorEls = {}; // name -> element, for live status updates

  function buildIndicators() {
    var host = $('indicators');
    INDICATORS.forEach(function (ind) {
      var el = document.createElement('div');
      el.className = 'indicator indicator--' + ind.color + (ind.cls ? ' ' + ind.cls : '');
      el.dataset.indicator = ind.name;
      el.innerHTML =
        '<div class="indicator-label">' + ind.name + '</div>' +
        '<div class="indicator-circle"><img src="' + ind.img + '" alt="' + ind.alt + '"></div>';
      host.appendChild(el);
      indicatorEls[ind.name] = el;
    });
  }

  /* ================= Mini sensor gauges (0–5 V) ================= */
  var MG = { cx: 70, cy: 60, r: 46, start: 200, span: 220 };

  function mgPoint(r, deg) {
    var a = (deg * Math.PI) / 180;
    return [MG.cx + r * Math.cos(a), MG.cy - r * Math.sin(a)];
  }

  function mgTicksPath() {
    var d = '';
    for (var i = 0; i <= 20; i++) {
      var deg = MG.start - (i / 20) * MG.span;
      var p1 = mgPoint(MG.r - 1.5, deg);
      var p2 = mgPoint(MG.r - (i % 5 === 0 ? 8 : 4.5), deg);
      d += 'M' + p1[0].toFixed(1) + ' ' + p1[1].toFixed(1) +
           ' L' + p2[0].toFixed(1) + ' ' + p2[1].toFixed(1) + ' ';
    }
    return d;
  }

  function mgNeedlePoints(volts) {
    var v = Math.max(0, Math.min(5, volts));
    var a = ((MG.start - (v / 5) * MG.span) * Math.PI) / 180;
    var dx = Math.cos(a), dy = -Math.sin(a);
    var L = 37, w = 1.9, tail = 5;
    var px = -dy, py = dx;
    var tip = [MG.cx + L * dx, MG.cy + L * dy];
    var b1 = [MG.cx + w * px - tail * dx, MG.cy + w * py - tail * dy];
    var b2 = [MG.cx - w * px - tail * dx, MG.cy - w * py - tail * dy];
    return b1[0].toFixed(1) + ',' + b1[1].toFixed(1) + ' ' +
           tip[0].toFixed(1) + ',' + tip[1].toFixed(1) + ' ' +
           b2[0].toFixed(1) + ',' + b2[1].toFixed(1);
  }

  var sensorEls = {}; // name -> { value, rot }, cached for 30 Hz updates

  /** clockwise rotation (deg) from the baked 0 V needle for a 0–5 V value */
  function mgDeg(volts) {
    var v = Math.max(0, Math.min(5, volts));
    return (v / 5) * MG.span;
  }

  function buildMiniGauges() {
    var host = $('mini-gauges');
    var ticks = mgTicksPath();
    SENSOR_ORDER.forEach(function (name, i) {
      var val = state.sensors[name];
      var el = document.createElement('div');
      el.className = 'mini-gauge';
      el.dataset.sensor = name;
      // needle in its own overlay svg, baked pointing at 0 V: value updates
      // rotate the ELEMENT (compositor-only) instead of rewriting the polygon
      el.innerHTML =
        '<div class="mg-inner">' +
          '<div class="mg-name">' + name + '</div>' +
          '<div class="mg-value">' + val.toFixed(2) + '</div>' +
          '<svg viewBox="0 0 140 106">' +
            '<path d="' + ticks + '" stroke="#9298a0" stroke-width="1.2" stroke-linecap="round"></path>' +
          '</svg>' +
          '<svg class="mg-needle-rot" viewBox="0 0 140 106" style="transform: rotate(' + mgDeg(val).toFixed(1) + 'deg)">' +
            '<polygon class="mg-needle" points="' + mgNeedlePoints(0) + '" fill="url(#mgNeedle)" style="animation-delay:' + (-i * 0.55).toFixed(2) + 's"></polygon>' +
          '</svg>' +
        '</div>';
      host.appendChild(el);
      sensorEls[name] = {
        value: el.querySelector('.mg-value'),
        rot: el.querySelector('.mg-needle-rot'),
      };
    });
  }

  function updateSensor(name) {
    var c = sensorEls[name];
    if (!c) return;
    var val = state.sensors[name];
    var text = val.toFixed(2);
    if (c.value.textContent === text) return; // below display resolution: no-op
    c.value.textContent = text;
    c.rot.style.transform = 'rotate(' + mgDeg(val).toFixed(1) + 'deg)';
  }

  /* ================= Oscilloscope ================= */
  // All three CKP/CMP lanes render into ONE canvas: SVG path mutation is the
  // priciest repeated paint on single-threaded TV renderers. CAN stays a CSS
  // background scrolled on the compositor.
  var TRACES = {
    ckp:  '01011010110101101011011010110101101011010110101101',
    cmp1: '00011110000111100011110000111100011110000111100011',
    cmp2: '01101010000110001100001101000011010000110100001101',
    canh: '00110010001100100011001000110010001100100011001000',
    canl: '11001101110011011100110111001101110011011100110111',
  };

  function wavePath(bits, w, h) {
    var n = bits.length, step = w / n, hi = 4, lo = h - 4;
    var d = '', x = 0;
    for (var i = 0; i < n; i++) {
      var y = bits[i] === '1' ? hi : lo;
      d += (i === 0 ? 'M0 ' + y : ' L' + x.toFixed(1) + ' ' + y);
      x += step;
      d += ' L' + x.toFixed(1) + ' ' + y;
    }
    return d;
  }

  var scopeCv = { el: null, ctx: null, w: 0, h: 0 };

  function sizeScopeCanvas() {
    if (!scopeCv.el) return;
    // backing store at on-screen resolution (stage scale included), capped at
    // 1.5× CSS px — crisp enough at 4K without quadrupling the fill cost
    var r = scopeCv.el.getBoundingClientRect();
    var s = Math.min(window.devicePixelRatio || 1, 1.5);
    var w = Math.max(1, Math.round(r.width * s));
    var h = Math.max(1, Math.round(r.height * s));
    if (w === scopeCv.w && h === scopeCv.h) return;
    scopeCv.w = scopeCv.el.width = w;
    scopeCv.h = scopeCv.el.height = h;
    if (!liveScope.on) drawScopeDemo(); // live redraws on its own tick
  }

  /* Static demo pattern (pre-connect only — real edges replace it). */
  function drawScopeDemo() {
    var ctx = scopeCv.ctx, W = scopeCv.w, H = scopeCv.h;
    if (!ctx) return;
    ctx.clearRect(0, 0, W, H);
    ctx.strokeStyle = state.scopeColor;
    ctx.lineWidth = 2;
    ['ckp', 'cmp1', 'cmp2'].forEach(function (key, lane) {
      var bits = TRACES[key];
      var laneH = H / 3;
      var yHi = lane * laneH + laneH * 0.16, yLo = lane * laneH + laneH * 0.84;
      var step = W / bits.length;
      ctx.beginPath();
      var y = bits[0] === '1' ? yHi : yLo;
      ctx.moveTo(0, y);
      for (var i = 1; i < bits.length; i++) {
        var ny = bits[i] === '1' ? yHi : yLo;
        if (ny !== y) { ctx.lineTo(i * step, y); ctx.lineTo(i * step, ny); y = ny; }
      }
      ctx.lineTo(W, y);
      ctx.stroke();
    });
  }

  function buildScope() {
    scopeCv.el = $('scope-canvas');
    scopeCv.ctx = scopeCv.el.getContext('2d');
    sizeScopeCanvas();
    // CAN (decorative): static pattern as background-image on a scrolling div —
    // the transform stays on the compositor; an animated transform inside an
    // SVG repaints per frame on TV browsers.
    [['canh', '.can-scroll--hi'], ['canl', '.can-scroll--lo']].forEach(function (p) {
      var svg =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1000 34' preserveAspectRatio='none'>" +
        "<path d='" + wavePath(TRACES[p[0]], 1000, 34) + "' fill='none' stroke='#83cf92' stroke-width='2'/></svg>";
      document.querySelector(p[1]).style.backgroundImage =
        'url("data:image/svg+xml,' + encodeURIComponent(svg) + '")';
    });
  }

  function updateScopeColor() {
    if (!liveScope.on) drawScopeDemo(); // live picks the color up next tick
  }

  /* ================= Output driver banks ================= */
  var bankCells = { coil: [], inj: [], gdi: [] }; // 1-based cell elements

  function buildBanks() {
    var coil = $('bank-coil'), inj = $('bank-inj'), gdi = $('bank-gdi');
    for (var n = 1; n <= 8; n++) {
      coil.insertAdjacentHTML('beforeend',
        '<div class="bank-cell" data-cell="coil-' + n + '">' +
          '<div class="num-badge">' + n + '</div>' +
          '<img src="assets/img/coil.png" alt="Ignition coil ' + n + '">' +
          '<div class="anim-slot">' +
            '<div class="spark-holder">' +
              '<img class="anim-layer" src="assets/img/spark_lit.png" alt="" style="animation-delay:' + ((n - 1) * 0.19).toFixed(2) + 's">' +
            '</div>' +
          '</div>' +
        '</div>');
      inj.insertAdjacentHTML('beforeend',
        '<div class="bank-cell" data-cell="inj-' + n + '">' +
          '<div class="num-badge">' + n + '</div>' +
          '<img src="assets/img/injector.png" alt="Injector ' + n + '">' +
          '<div class="anim-slot">' +
            '<div class="spray-holder">' +
              '<img class="anim-layer" src="assets/img/spray.png" alt="" style="animation-delay:' + ((n - 1) * 0.14).toFixed(2) + 's">' +
            '</div>' +
          '</div>' +
        '</div>');
      gdi.insertAdjacentHTML('beforeend',
        '<div class="bank-cell" data-cell="gdi-' + n + '">' +
          '<div class="num-badge">' + n + '</div>' +
          '<img src="assets/img/gdi.png" alt="GDI injector ' + n + '">' +
          '<div class="anim-slot">' +
            '<div class="spray-holder">' +
              '<img class="anim-layer" src="assets/img/spray_gdi.png" alt="" style="animation-delay:' + ((n - 1) * 0.17).toFixed(2) + 's">' +
            '</div>' +
          '</div>' +
        '</div>');
    }
    ['coil', 'inj', 'gdi'].forEach(function (kind) {
      var host = $('bank-' + kind);
      for (var n = 1; n <= 8; n++) {
        bankCells[kind][n] = host.querySelector('[data-cell="' + kind + '-' + n + '"]');
      }
    });
  }

  /* Latched-activity bits (one per channel) gate each cell's animation. */
  function setBankActivity(kind, bits) {
    for (var n = 1; n <= 8; n++) {
      bankCells[kind][n].classList.toggle('is-active', !!(bits && bits[n - 1]));
    }
  }

  /* ================= Top-bar readouts ================= */
  function updateDevice() {
    $('device-panel').classList.toggle('is-disconnected', !state.connected);
    $('device-status').textContent = state.connected ? 'CONNECTED' : 'DISCONNECTED';
  }

  var readoutsDrawn = { v: '', a: '' };
  function updateReadouts() {
    var v = state.voltage.toFixed(2), a = state.current.toFixed(2);
    if (v !== readoutsDrawn.v) { readoutsDrawn.v = v; $('voltage').textContent = v; }
    if (a !== readoutsDrawn.a) { readoutsDrawn.a = a; $('current').textContent = a; }
  }

  var iacLeds = null;
  function updateIac() {
    if (!iacLeds) iacLeds = document.querySelectorAll('#iac-cell .iac-led');
    for (var i = 0; i < iacLeds.length; i++) {
      iacLeds[i].classList.toggle('is-off', !state.iacPhases[i]);
    }
  }

  function updateCursor() {
    $('stage').classList.toggle('no-cursor', !state.showCursor);
  }

  /* ================= Live mode (real device frames) =================
     Until the first frame arrives the dashboard free-runs as a demo. Once
     live, .is-live gates every demo loop behind per-channel telemetry
     (see the "Live mode" rules in style.css). */
  function setLive() {
    if (state.live) return;
    state.live = true;
    $('stage').classList.add('is-live');
  }

  /** map: indicator name -> truthy = lit (name ∈ INDICATORS[].name). */
  function setIndicators(map) {
    for (var name in map) {
      if (indicatorEls[name]) indicatorEls[name].classList.toggle('is-off', !map[name]);
    }
  }

  /** map: fan1|fan2|imo|hip -> truthy = active (spinning / lit). */
  var statusCellEls = null;
  function setStatusCells(map) {
    if (!statusCellEls) {
      statusCellEls = {};
      document.querySelectorAll('.status-cell[data-cell]').forEach(function (el) {
        statusCellEls[el.dataset.cell] = el;
      });
    }
    for (var key in map) {
      if (statusCellEls[key]) statusCellEls[key].classList.toggle('is-active', !!map[key]);
    }
  }

  /* ================= Live oscilloscope =================
     Renders WAVEFORM edge-list frames (docs/PROTOCOL.md §4, mode 1) as real
     square waves over a rolling window, replacing the static demo pattern —
     the CKP 60-2 missing tooth and cam sync are the actual device timing.
     Canvas strokes at ~25 fps with a wall-clock-advanced window, so the trace
     scrolls smoothly instead of jumping once per data batch. */
  var SCOPE_WINDOW_US = 90000;  // ~90 ms on screen (~1 crank rev at idle)
  var SCOPE_KEEP_US = 400000;
  var SCOPE_MAX_EDGES = 4096;   // hard cap if rendering stalls (throttled tab)
  var scopeDrawMs = 67;         // ~15 fps: fluid enough, fewer full-screen composites
  // per-channel parallel arrays of numbers (no per-edge objects — GC-friendly)
  var liveScope = {
    on: false, tEnd: 0, dispEnd: 0, lastDraw: 0,
    t: { 0: [], 1: [], 2: [] }, l: { 0: [], 1: [], 2: [] },
  };

  /** wave: decoded WAVEFORM frame with parallel tUs/level arrays (protocol.js). */
  function feedWaveform(channel, wave) {
    if (!(channel in liveScope.t) || !wave || !wave.count) return;
    var T = liveScope.t[channel], L = liveScope.l[channel];
    for (var i = 0; i < wave.count; i++) {
      T.push(wave.tUs[i]);
      L.push(wave.level[i]);
    }
    var tLast = wave.tUs[wave.count - 1];
    if (tLast > liveScope.tEnd) liveScope.tEnd = tLast;
    if (T.length > SCOPE_MAX_EDGES) {
      var cut = T.length - SCOPE_MAX_EDGES;
      T.splice(0, cut);
      L.splice(0, cut);
    }
    if (!liveScope.on) {
      liveScope.on = true;
      liveScope.dispEnd = liveScope.tEnd;
      liveScope.lastDraw = performance.now();
      requestAnimationFrame(drawLiveScope);
    }
  }

  function drawLiveScope() {
    renderScope();
    // paced by setTimeout, not a 60 Hz rAF that early-returns 2/3 of its wakeups —
    // fewer main-thread wakeups is a direct win on single-threaded TV renderers
    setTimeout(drawLiveScope, scopeDrawMs);
  }

  // TVs suspend rAF behind OSD overlays / "inactive" states; keep the trace
  // alive on a slow fallback tick whenever rAF stalls.
  setInterval(function () {
    if (liveScope.on && performance.now() - liveScope.lastDraw > 500) renderScope();
  }, 500);

  function renderScope() {
    var nowMs = performance.now();
    var dt = nowMs - liveScope.lastDraw;   // paced by the setTimeout loop above
    liveScope.lastDraw = nowMs;
    // advance the display window by wall time, gently pulled toward the newest
    // data; never ahead of it (a stalled stream freezes cleanly, no jitter)
    liveScope.dispEnd = Math.min(
      liveScope.tEnd,
      liveScope.dispEnd + dt * 1000 + (liveScope.tEnd - liveScope.dispEnd) * 0.1
    );
    if (liveScope.tEnd - liveScope.dispEnd > 150000) {
      liveScope.dispEnd = liveScope.tEnd - 50000; // fell too far behind: catch up
    }
    var tEnd = liveScope.dispEnd;
    var t0 = tEnd - SCOPE_WINDOW_US;
    var cutoff = liveScope.tEnd - SCOPE_KEEP_US;
    var ctx = scopeCv.ctx, W = scopeCv.w, H = scopeCv.h, laneH = H / 3;
    ctx.clearRect(0, 0, W, H);
    ctx.strokeStyle = state.scopeColor;
    ctx.lineWidth = 2;
    for (var ch = 0; ch < 3; ch++) {
      var T = liveScope.t[ch], L = liveScope.l[ch];
      var k = 0;
      while (k < T.length - 1 && T[k + 1] < cutoff) k++;
      if (k > 0) { T.splice(0, k); L.splice(0, k); }
      var yHi = ch * laneH + laneH * 0.16, yLo = ch * laneH + laneH * 0.84;
      var start = 0;
      for (var i = 0; i < T.length; i++) { if (T[i] <= t0) start = i; else break; }
      ctx.beginPath();
      var started = false, prevY = yLo;
      for (i = start; i < T.length; i++) {
        if (T[i] > tEnd) break;
        var x = Math.max(0, ((T[i] - t0) / SCOPE_WINDOW_US) * W);
        var y = L[i] ? yHi : yLo;
        if (!started) { ctx.moveTo(0, y); started = true; }
        else { ctx.lineTo(x, prevY); ctx.lineTo(x, y); }
        prevY = y;
      }
      if (started) { ctx.lineTo(W, prevY); ctx.stroke(); }
    }
  }

  /* ================= Uptime clock ================= */
  var uptimeSeconds = 0;
  setInterval(function () {
    uptimeSeconds++;
    var h = Math.floor(uptimeSeconds / 3600) % 100;
    var m = Math.floor(uptimeSeconds / 60) % 60;
    var s = uptimeSeconds % 60;
    $('uptime').textContent = [h, m, s].map(function (x) {
      return String(x).padStart(2, '0');
    }).join(':');
  }, 1000);

  /* ================= Scale 1920×1080 canvas to viewport =================
     A transform scale makes a software compositor (TV browsers) bilinearly
     resample the ENTIRE stage every frame — single-digit FPS. So: snap to
     identity when the viewport is within 2% of 1080p (integer offsets only),
     and offer ?fit=zoom, which scales by LAYOUT (Blink `zoom`): everything
     paints once at native size, nothing is resampled per frame. */
  // default to layout zoom when Blink supports it (every TV browser we target);
  // ?fit=transform opts back into the transform path
  var FIT_MODE = new URLSearchParams(location.search).get('fit') ||
    ('zoom' in document.documentElement.style ? 'zoom' : 'transform');

  function fitStage() {
    var w = window.innerWidth, h = window.innerHeight;
    var scale = Math.min(w / 1920, h / 1080);
    var stage = $('stage');
    // within 2% of native 1080p: render pixel-exact in BOTH modes — a 0.99× zoom
    // (e.g. a 1920×1070 TV) softens everything and never pixel-aligns
    if (Math.abs(scale - 1) < 0.02) scale = 1;
    if (FIT_MODE === 'zoom' && 'zoom' in stage.style) {
      stage.style.transform = '';
      stage.style.zoom = scale;
      stage.style.left = Math.round((w - 1920 * scale) / 2 / scale) + 'px';
      stage.style.top = Math.round((h - 1080 * scale) / 2 / scale) + 'px';
    } else {
      var tx = Math.round((w - 1920 * scale) / 2);
      var ty = Math.round((h - 1080 * scale) / 2);
      stage.style.transform = scale === 1
        ? 'translate(' + tx + 'px,' + ty + 'px)'
        : 'translate(' + tx + 'px,' + ty + 'px) scale(' + scale.toFixed(4) + ')';
    }
    sizeScopeCanvas(); // backing store follows the on-screen size
  }
  window.addEventListener('resize', fitStage);

  /* ================= Public API — hook live ECU data here ================= */
  window.ECU = {
    /** Engine speed, 0–8000 rpm. Drives the needle, readout and every animation tempo. */
    setRpm: function (rpm) { state.rpm = Number(rpm) || 0; updateRpm(); },

    /** Device link status: true = CONNECTED (green), false = DISCONNECTED (red). */
    setConnected: function (on) { state.connected = !!on; updateDevice(); },

    /** Supply readouts in the top bar. */
    setVoltage: function (v) { state.voltage = Number(v) || 0; updateReadouts(); },
    setCurrent: function (a) { state.current = Number(a) || 0; updateReadouts(); },

    /** Sensor voltage, 0–5 V. name ∈ CTS | MAF | MAP | IAT | 5V | IGF. */
    setSensor: function (name, volts) {
      if (!(name in state.sensors)) return;
      state.sensors[name] = Number(volts) || 0;
      updateSensor(name);
    },

    /** IAC stepper phase LEDs, e.g. [true, false, true, false]. */
    setIacPhases: function (phases) {
      state.iacPhases = [!!phases[0], !!phases[1], !!phases[2], !!phases[3]];
      updateIac();
    },

    /** CKP/CMP trace color (CAN traces stay green). */
    setScopeColor: function (color) { state.scopeColor = color; updateScopeColor(); },

    /** Show or hide the dashed scope cursors. */
    showCursor: function (on) { state.showCursor = !!on; updateCursor(); },

    /* ---- live-device extensions (driven by js/live.js) ---- */

    /** Enter live mode: demo loops now need per-channel telemetry (is-active). */
    setLive: setLive,

    /** Latched activity bits, arrays of 8 × 0/1 (index 0 = channel 1). */
    setCoils: function (bits) { setBankActivity('coil', bits); },
    setInjectors: function (bits) { setBankActivity('inj', bits); },
    setGdi: function (bits) { setBankActivity('gdi', bits); },

    /** Status-grid cells: { fan1, fan2, imo, hip } -> truthy = active. */
    setStatusCells: setStatusCells,

    /** Relay/tell-tale row: { 'BAT-ON': 1, 'MRC+': 0, … } -> truthy = lit. */
    setIndicators: setIndicators,

    /** One decoded WAVEFORM block: channel 0=CKP 1=CMP1 2=CMP2, {count, tUs[], level[]}. */
    feedWaveform: feedWaveform,

    /** Restart the uptime clock (e.g. when the device link comes up). */
    resetUptime: function () { uptimeSeconds = 0; },

    /** Apply several values at once: ECU.update({rpm, connected, voltage, current, sensors:{CTS:…}}) */
    update: function (data) {
      if (!data) return;
      if ('rpm' in data) this.setRpm(data.rpm);
      if ('connected' in data) this.setConnected(data.connected);
      if ('voltage' in data) this.setVoltage(data.voltage);
      if ('current' in data) this.setCurrent(data.current);
      if (data.sensors) {
        for (var k in data.sensors) this.setSensor(k, data.sensors[k]);
      }
      if (data.iacPhases) this.setIacPhases(data.iacPhases);
    },
  };

  /* ================= Boot ================= */
  buildRpmDial();
  buildIndicators();
  buildMiniGauges();
  buildScope();
  buildBanks();
  updateRpm();
  updateDevice();
  updateReadouts();
  updateIac();
  updateCursor();
  fitStage();
})();
