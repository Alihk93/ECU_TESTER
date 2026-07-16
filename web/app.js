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

  // ES5 query-string reader (URLSearchParams is unavailable on old TV browsers)
  window.ECUqs = function (name) {
    var q = location.search;
    if (!q) return null;
    var pairs = q.replace(/^\?/, '').split('&');
    for (var i = 0; i < pairs.length; i++) {
      var eq = pairs[i].indexOf('=');
      var k = decodeURIComponent(eq < 0 ? pairs[i] : pairs[i].slice(0, eq));
      if (k === name) return eq < 0 ? '' : decodeURIComponent(pairs[i].slice(eq + 1));
    }
    return null;
  };

  // thousands separator without toLocaleString (locale formatting is spotty on old TVs)
  function commafy(n) {
    var s = String(n), out = '', c = 0;
    for (var i = s.length - 1; i >= 0; i--) {
      out = s.charAt(i) + out;
      if (++c % 3 === 0 && i > 0) out = ',' + out;
    }
    return out;
  }

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
      // element-level CSS rotation on the wrapper div — composited; the
      // needle steps at the coalesced data rate (no glide transition)
      $('rpm-needle').style.transform = 'rotate(' + angle + 'deg)';
    }
    var text = commafy(Math.round(state.rpm));
    if (text !== rpmDrawn.text) {
      rpmDrawn.text = text;
      $('rpm-reading').textContent = text;
    }
    updateTempo();
    scopeTick(); // redraw the scope only when the RPM frequency changes
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
    css.setProperty('--spark-dur', cycle.toFixed(2) + 's');
    css.setProperty('--spray-dur', (cycle * 0.92).toFixed(2) + 's');
    css.setProperty('--gdi-dur', (cycle * 0.88).toFixed(2) + 's');
  }

  /* ================= Indicators ================= */
  var INDICATORS = [
    { name: 'BAT-ON',  color: 'red',   img: 'assets/img/bat.png',      cls: 'indicator--bat',  alt: 'Battery warning' },
    { name: 'SW-ON',   color: 'red',   img: 'assets/img/swon2.png',    cls: 'indicator--swon', alt: 'Switch on / key' },
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
      // needle baked pointing at 0 V: the wrapper DIV takes the value rotation
      // and the inner svg ELEMENT takes the demo wobble — both compositor-only
      // (a transform inside the svg would repaint the gauge per frame on TVs)
      el.innerHTML =
        '<div class="mg-inner">' +
          '<div class="mg-name">' + name + '</div>' +
          '<div class="mg-value">' + val.toFixed(2) + '</div>' +
          '<svg viewBox="0 0 140 106">' +
            '<path d="' + ticks + '" stroke="#9298a0" stroke-width="1.2" stroke-linecap="round"></path>' +
          '</svg>' +
          '<div class="mg-needle-rot" style="transform: rotate(' + mgDeg(val).toFixed(1) + 'deg)">' +
            '<svg viewBox="0 0 140 106" style="animation-delay:' + (-i * 0.55).toFixed(2) + 's">' +
              '<polygon class="mg-needle" points="' + mgNeedlePoints(0) + '" fill="url(#mgNeedle)"></polygon>' +
            '</svg>' +
          '</div>' +
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

  /* ================= Oscilloscope — standing square waves =================
     Real-scope look: each trace is a clean square wave that STANDS in place
     (no scrolling — far cheaper on TV renderers). Only amplitude, frequency
     and duty change; frequency tracks RPM, amp/duty are per-signal. The canvas
     redraws only when the RPM-frequency bucket changes, so it's near-static. */
  // freqBase = cycles across the plot at idle; freqRpm adds cycles with RPM.
  var SCOPE_LANES = [
    { duty: 0.50, amp: 0.84, freqBase: 9, freqRpm: 20 }, // CKP  — fast, even
    { duty: 0.30, amp: 0.62, freqBase: 3, freqRpm: 7 },  // CMP1 — slow, narrow highs
    { duty: 0.46, amp: 0.74, freqBase: 5, freqRpm: 11 }, // CMP2 — medium
  ];
  var CAN_LANES = [['hi', 12, 0.5], ['lo', 16, 0.4]]; // [suffix, cycles, duty] (decorative)

  var scopeCv = { el: null, ctx: null, w: 0, h: 0 };

  function sizeScopeCanvas() {
    if (!scopeCv.el) return;
    // backing store at on-screen resolution (stage scale included), capped at 1.5× CSS px
    var r = scopeCv.el.getBoundingClientRect();
    var s = Math.min(window.devicePixelRatio || 1, 1.5);
    var w = Math.max(1, Math.round(r.width * s));
    var h = Math.max(1, Math.round(r.height * s));
    if (w === scopeCv.w && h === scopeCv.h) return;
    scopeCv.w = scopeCv.el.width = w;
    scopeCv.h = scopeCv.el.height = h;
    drawScope();
  }

  function scopeRpmFactor() { return Math.max(0, Math.min(1, state.rpm / 8000)); }

  /* Stroke one clean standing square wave centred on yMid. */
  function strokeSquareWave(ctx, W, cycles, duty, ampFrac, yMid, halfMax) {
    var half = ampFrac * halfMax, yHi = yMid - half, yLo = yMid + half;
    var period = W / Math.max(0.5, cycles);
    ctx.beginPath();
    ctx.moveTo(0, yLo);
    for (var x = 0; x < W; x += period) {
      var xRise = x + period * (1 - duty), xFall = x + period;
      ctx.lineTo(Math.min(xRise, W), yLo);
      if (xRise < W) ctx.lineTo(xRise, yHi);
      ctx.lineTo(Math.min(xFall, W), yHi);
      if (xFall < W) ctx.lineTo(xFall, yLo);
    }
    ctx.stroke();
  }

  /* Redraw the 3 CKP/CMP lanes (standing waves at the current RPM frequency). */
  function drawScope() {
    var ctx = scopeCv.ctx, W = scopeCv.w, H = scopeCv.h;
    if (!ctx) return;
    ctx.clearRect(0, 0, W, H);
    ctx.strokeStyle = state.scopeColor;
    ctx.lineWidth = 2;
    ctx.lineJoin = 'miter';
    var laneH = H / 3, f = scopeRpmFactor();
    for (var i = 0; i < 3; i++) {
      var L = SCOPE_LANES[i];
      strokeSquareWave(ctx, W, L.freqBase + f * L.freqRpm, L.duty, L.amp,
                       i * laneH + laneH / 2, laneH * 0.36);
    }
  }

  /* The CKP/CMP canvas redraws ONLY when the RPM frequency bucket changes — a
     continuous per-frame scope redraw was the single biggest cost on software-
     composited TVs. (Motion is kept on the CAN traces via cheap CSS scroll.) */
  var scopeFreqBucket = -1;
  function scopeTick() {
    var bucket = Math.round(scopeRpmFactor() * 24);
    if (bucket === scopeFreqBucket) return;
    scopeFreqBucket = bucket;
    drawScope();
  }

  /* SVG-path clean square wave for the CAN background images. */
  function squareWavePath(cycles, duty, w, h) {
    var hi = 4, lo = h - 4, period = w / cycles, d = 'M0 ' + lo;
    for (var x = 0; x < w; x += period) {
      var xr = (x + period * (1 - duty)).toFixed(1), xf = Math.min(x + period, w).toFixed(1);
      d += ' L' + xr + ' ' + lo + ' L' + xr + ' ' + hi + ' L' + xf + ' ' + hi + ' L' + xf + ' ' + lo;
    }
    return d;
  }

  function buildScope() {
    scopeCv.el = $('scope-canvas');
    scopeCv.ctx = scopeCv.el.getContext('2d');
    sizeScopeCanvas();
    // CAN (decorative): clean standing square waves as static background images —
    // no scroll (all 5 traces stand still).
    CAN_LANES.forEach(function (c) {
      var svg =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1000 34' preserveAspectRatio='none'>" +
        "<path d='" + squareWavePath(c[1], c[2], 1000, 34) + "' fill='none' stroke='#83cf92' stroke-width='2'/></svg>";
      document.querySelector('.can-scroll--' + c[0]).style.backgroundImage =
        'url("data:image/svg+xml,' + encodeURIComponent(svg) + '")';
    });
  }

  function updateScopeColor() { drawScope(); }

  /* WAVEFORM frames arrive but are not plotted — the scope is a clean parametric
     standing display now, so js/live.js no longer forwards them here. Kept as a
     no-op API hook for when waveform plotting is restored (see js/live.js). */
  function feedWaveform() {}

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
  /* Fan spin control: CSS does the ease-in spin-up and steady clockwise spin;
     on stop we freeze at the live angle and a CSS transition coasts it down.
     Fires only on on/off transitions — no per-frame JS (TV-friendly). */
  function fanAngle(img) {
    var m = getComputedStyle(img).transform;
    if (!m || m === 'none') return 0;
    var p = m.slice(m.indexOf('(') + 1).split(',');
    return Math.atan2(parseFloat(p[1]), parseFloat(p[0])) * 180 / Math.PI;
  }
  function setFanRunning(cell, on) {
    var img = cell.querySelector('img');
    var running = cell.classList.contains('fan-run');
    if (on && !running) {
      cell.classList.remove('fan-coast');
      img.style.transform = '';
      cell.classList.add('fan-run');           // CSS: ease-in spin-up -> steady spin
    } else if (!on && running) {
      var deg = fanAngle(img);
      cell.classList.remove('fan-run');
      img.style.transform = 'rotate(' + deg.toFixed(1) + 'deg)';
      void img.offsetWidth;                     // reflow so the coast starts at deg
      cell.classList.add('fan-coast');
      img.style.transform = 'rotate(' + (deg + 150).toFixed(1) + 'deg)';
    }
  }

  var statusCellEls = null;
  function setStatusCells(map) {
    if (!statusCellEls) {
      statusCellEls = {};
      // plain loop: NodeList.forEach needs Chromium 51+ (old TV browsers)
      var cells = document.querySelectorAll('.status-cell[data-cell]');
      for (var i = 0; i < cells.length; i++) {
        statusCellEls[cells[i].dataset.cell] = cells[i];
      }
    }
    for (var key in map) {
      var el = statusCellEls[key];
      if (!el) continue;
      if (key === 'fan1' || key === 'fan2') setFanRunning(el, !!map[key]);
      else el.classList.toggle('is-active', !!map[key]);
    }
  }

  /* The live scrolling scope was removed: the oscilloscope is now a standing
     parametric display (see the Oscilloscope section above). WAVEFORM frames
     still arrive via js/live.js but are not plotted. */

  /* ================= Uptime clock ================= */
  var uptimeSeconds = 0;
  setInterval(function () {
    uptimeSeconds++;
    var h = Math.floor(uptimeSeconds / 3600) % 100;
    var m = Math.floor(uptimeSeconds / 60) % 60;
    var s = uptimeSeconds % 60;
    $('uptime').textContent = [h, m, s].map(function (x) {
      return (x < 10 ? '0' : '') + x;
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
  var FIT_MODE = ECUqs('fit') ||
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
  // demo: fans spin (clockwise, eased) until live telemetry gates them
  // (plain loop: NodeList.forEach needs Chromium 51+ — old TV browsers)
  var fanCells = document.querySelectorAll('.status-cell--fan');
  for (var fi = 0; fi < fanCells.length; fi++) fanCells[fi].classList.add('fan-run');
  fitStage();
})();
