/* ECU_TESTER :: diag.js — on-screen performance meter.
   Measures smoothness ON THE ACTUAL DISPLAY DEVICE (a desktop dev browser
   never reproduces a weak TV/tablet renderer) without devtools.

   FPS  = actual redraw rate (60 ≈ butter, <30 = janky)
   WS/s = protocol frames received per second (~90 healthy)
   age  = ms since the last telemetry frame (climbing = the stream stalled,
          not the renderer)

   Tap/click the meter or press D to hide/show it. */
(function () {
  "use strict";

  // On by default so the meter is always there on the plain kiosk URL — you need
  // it to measure. For the final production kiosk only, disable it with ?diag=0.
  if (window.ECUqs && window.ECUqs("diag") === "0") return;

  var box = document.createElement("div");
  box.id = "diag";
  box.innerHTML =
    'FPS <b id="d-fps">--</b> · WS <b id="d-ws">--</b>/s · age <b id="d-age">--</b>ms' +
    ' · <b id="d-res">--</b> <span class="d-hint">tap to hide</span>';
  document.body.appendChild(box);

  var fpsEl = document.getElementById("d-fps");
  var wsEl = document.getElementById("d-ws");
  var ageEl = document.getElementById("d-age");
  var resEl = document.getElementById("d-res");

  function toggle() { box.style.display = box.style.display === "none" ? "" : "none"; }
  box.addEventListener("click", toggle);
  addEventListener("keydown", function (e) {
    if ((e.key || "").toLowerCase() === "d") toggle();
  });

  // FPS from the actual rAF cadence (drops below 60 exactly when it stutters).
  var frames = 0, last = performance.now();
  function tick(t) {
    frames++;
    if (t - last >= 500) {
      fpsEl.textContent = Math.round((frames * 1000) / (t - last));
      frames = 0; last = t;
    }
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);

  // WS rate + telemetry age on their own timer, so they keep updating even if
  // rAF is throttled (e.g. a backgrounded tab); FPS legitimately reads low then.
  var prev = 0;
  setInterval(function () {
    // composite resolution: tells us if the TV is blending a 4K framebuffer
    // (the biggest remaining lever) vs 1080p. innerWidth×innerHeight @ dpr.
    resEl.textContent = innerWidth + "×" + innerHeight + "@" + (window.devicePixelRatio || 1);
    var s = window.__ecuStats;
    if (!s) return;
    s.msgRate = s.msgs - prev;
    prev = s.msgs;
    wsEl.textContent = s.msgRate;
    ageEl.textContent = s.lastTelemMs ? Math.round(performance.now() - s.lastTelemMs) : "--";
  }, 1000);
})();
