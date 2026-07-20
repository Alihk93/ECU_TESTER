# Design references

Source art the client provided as a visual reference during design/implementation
discussions — not used directly by any app at runtime. Kept here for provenance.

- `coil-spark-bolt.png` — reference for the coil spark effect's shape/color (red
  lightning bolt). The Android app uses a vector recreation of this,
  `android/app/src/main/res/drawable/spark_bolt.xml`, not this file.
- `ecu-intro/` — the Claude Design handoff the cinematic intro was ported from
  (`ecu-intro.jsx` = scene/timing/audio spec, `ECU Tester Intro.dc.html` = shell).
  The Android app is a native Canvas port (`IntroActivity` + `ui/IntroView.kt` +
  `ui/IntroAudio.kt`), not these files. Source composite PNG lives in the handoff
  zip; the app ships a 183 KB WebP re-encode at `res/drawable-nodpi/intro_bg.webp`.
