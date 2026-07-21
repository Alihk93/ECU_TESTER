// ECU Tester — cinematic intro. Scenes for animations-v2 SceneStage.
const IMG = 'uploads/Gemini_Generated_Image_qj4ghoqj4ghoqj4g.png';
const CFG = { enterUrl: 'dashboard.html' };
const cl = (v, a, b) => Math.max(a, Math.min(b, v));
const seg = (t, t0, t1, e) => { const p = cl((t - t0) / (t1 - t0), 0, 1); return e ? e(p) : p; };
const outCubic = p => 1 - Math.pow(1 - p, 3);
const outQuint = p => 1 - Math.pow(1 - p, 5);

/* ---------------- PS2-style synth (placeholder until real files arrive) ---------------- */
const AUDIO = {
  enabled: true, ctx: null,
  ensure() {
    if (!this.ctx) { try { this.ctx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { return null; } }
    if (this.ctx.state === 'suspended') this.ctx.resume().catch(() => {});
    return this.ctx;
  },
  play(name) { if (!this.enabled) return; const c = this.ensure(); if (!c || c.state !== 'running') return; try { this['v_' + name](c); } catch (e) {} },
  _noise(c, dur) {
    const b = c.createBuffer(1, c.sampleRate * dur, c.sampleRate), d = b.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
    const s = c.createBufferSource(); s.buffer = b; return s;
  },
  _env(c, g0, dur, a) {
    const g = c.createGain(); const t = c.currentTime;
    g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(g0, t + (a || 0.02));
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur); g.connect(c.destination); return g;
  },
  v_ambient(c) {
    if (this._amb) return; this._amb = 1;
    const t = c.currentTime, g = c.createGain(), f = c.createBiquadFilter();
    f.type = 'lowpass'; f.frequency.value = 620; g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.045, t + 2.5); g.gain.setValueAtTime(0.045, t + 6.8);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 9.6); f.connect(g); g.connect(c.destination);
    [[110, 'sine', 1], [110.8, 'sine', 0.8], [221.5, 'triangle', 0.22], [1760, 'sine', 0.05]].forEach(([fr, ty, a]) => {
      const o = c.createOscillator(), og = c.createGain(); o.type = ty; o.frequency.value = fr; og.gain.value = a;
      o.connect(og); og.connect(f); o.start(t); o.stop(t + 10);
    });
    setTimeout(() => { this._amb = 0; }, 10500);
  },
  v_whoosh(c) {
    const s = this._noise(c, 1.3), f = c.createBiquadFilter(); f.type = 'bandpass'; f.Q.value = 1.1;
    const t = c.currentTime; f.frequency.setValueAtTime(160, t); f.frequency.exponentialRampToValueAtTime(1500, t + 1.1);
    s.connect(f); f.connect(this._env(c, 0.22, 1.3, 0.45)); s.start(t);
  },
  v_thump(c) {
    const o = c.createOscillator(), t = c.currentTime; o.type = 'sine';
    o.frequency.setValueAtTime(95, t); o.frequency.exponentialRampToValueAtTime(40, t + 0.4);
    o.connect(this._env(c, 0.4, 0.45)); o.start(t); o.stop(t + 0.5);
  },
  v_blip1(c) { this._blip(c, 620); }, v_blip2(c) { this._blip(c, 830); },
  _blip(c, fr) {
    const o = c.createOscillator(), t = c.currentTime; o.type = 'sine'; o.frequency.value = fr;
    o.connect(this._env(c, 0.09, 0.16)); o.start(t); o.stop(t + 0.2);
  },
  v_impact(c) {
    const t = c.currentTime, o = c.createOscillator(); o.type = 'sine';
    o.frequency.setValueAtTime(150, t); o.frequency.exponentialRampToValueAtTime(46, t + 0.55);
    o.connect(this._env(c, 0.45, 0.65)); o.start(t); o.stop(t + 0.7);
    const n = this._noise(c, 0.15), hp = c.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 1800;
    n.connect(hp); hp.connect(this._env(c, 0.15, 0.15)); n.start(t);
  },
  v_ripple(c) {
    const t = c.currentTime;
    for (let i = 0; i < 11; i++) {
      const o = c.createOscillator(), g = c.createGain(); o.type = 'sine'; o.frequency.value = 880 + i * 74;
      g.gain.setValueAtTime(0.0001, t + i * 0.09); g.gain.exponentialRampToValueAtTime(0.07, t + i * 0.09 + 0.015);
      g.gain.exponentialRampToValueAtTime(0.0001, t + i * 0.09 + 0.14);
      o.connect(g); g.connect(c.destination); o.start(t + i * 0.09); o.stop(t + i * 0.09 + 0.16);
    }
  },
  v_chime(c) {
    const t = c.currentTime;
    [1046.5, 1318.5, 1568, 2093].forEach((fr, i) => {
      [1, 1.004].forEach(d => {
        const o = c.createOscillator(), g = c.createGain(); o.type = 'sine'; o.frequency.value = fr * d;
        const t0 = t + i * 0.13; g.gain.setValueAtTime(0.0001, t0);
        g.gain.exponentialRampToValueAtTime(0.07, t0 + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t0 + 1.7);
        o.connect(g); g.connect(c.destination); o.start(t0); o.stop(t0 + 1.8);
      });
    });
  },
  v_click(c) {
    const o = c.createOscillator(), t = c.currentTime; o.type = 'triangle'; o.frequency.value = 1500;
    o.connect(this._env(c, 0.12, 0.06)); o.start(t); o.stop(t + 0.08);
  },
};
window.addEventListener('pointerdown', () => AUDIO.ensure());
window.addEventListener('keydown', () => AUDIO.ensure());

function useCues(t, list) {
  const fired = React.useRef({});
  React.useEffect(() => {
    list.forEach(([at, name]) => { if (t >= at && !fired.current[name]) { fired.current[name] = 1; AUDIO.play(name); } });
  }, [t]);
}
function useLabel(scene, t) {
  React.useEffect(() => {
    const el = document.querySelector('[data-om-exportable-video-with-duration-secs]');
    if (el) el.setAttribute('data-screen-label', scene.name + ' +' + Math.floor(t) + 's');
  }, [scene.name, Math.floor(t)]);
}

/* ---------------- photo region layer ---------------- */
function Photo({ cx, cy, rx, ry, scale = 1, opacity = 1, bright = 1, blur = 0, glow = 0, dx = 0, dy = 0, z = 1, feather = 0.74 }) {
  if (opacity <= 0) return null;
  const m = rx ? `radial-gradient(${rx * 1920}px ${ry * 1080}px at ${cx * 100}% ${cy * 100}%, #000 ${feather * 100}%, transparent 100%)` : 'none';
  return <div style={{
    position: 'absolute', inset: 0, zIndex: z, pointerEvents: 'none',
    backgroundImage: `url("${IMG}")`, backgroundSize: '100% 100%',
    WebkitMaskImage: m === 'none' ? undefined : m, maskImage: m === 'none' ? undefined : m,
    transformOrigin: `${cx * 100}% ${cy * 100}%`,
    transform: `translate(${dx}px,${dy}px) scale(${scale})`, opacity,
    filter: `brightness(${bright}) blur(${blur}px)` + (glow ? ` drop-shadow(0 0 ${glow}px rgba(96,190,255,0.75))` : ''),
  }} />;
}

const L = { // regions (fractions of frame)
  circL: { cx: 0.11, cy: 0.22, rx: 0.15, ry: 0.27 },
  circR: { cx: 0.89, cy: 0.26, rx: 0.14, ry: 0.30 },
  cars: { cx: 0.5, cy: 0.48, rx: 0.37, ry: 0.17, feather: 0.78 },
  plate: { cx: 0.5, cy: 0.155, rx: 0.31, ry: 0.16, feather: 0.8 },
  text: { cx: 0.487, cy: 0.895, rx: 0.29, ry: 0.08 },
  setBtn: { cx: 0.116, cy: 0.926, rx: 0.10, ry: 0.062 },
  entBtn: { cx: 0.892, cy: 0.926, rx: 0.105, ry: 0.062 },
};
// No brand-logo row in the current intro art (2026 redesign uses the circuit pattern). Empty = no reveal.
const LOGOS = [];

/* Shared composite: every scene renders the frame from one params object */
function Frame(p) {
  return <div style={{ position: 'absolute', inset: 0, background: '#020407', overflow: 'hidden' }}>
    <Photo cx={0.5} cy={0.5} rx={0} ry={0} bright={p.bg} z={1} />
    {p.circuits > 0 && <React.Fragment>
      <Photo {...L.circL} opacity={p.circuits} bright={1.35} glow={7} z={2} />
      <Photo {...L.circR} opacity={p.circuits} bright={1.35} glow={7} z={2} />
    </React.Fragment>}
    {p.groundGlow > 0 && <div style={{
      position: 'absolute', left: '18%', right: '18%', top: '52%', height: '18%', zIndex: 2, pointerEvents: 'none',
      background: 'radial-gradient(50% 60% at 50% 55%, rgba(120,200,255,0.30), transparent 70%)', opacity: p.groundGlow,
    }} />}
    {p.cars && <Photo {...L.cars} {...p.cars} z={3} />}
    {p.plate && <Photo {...L.plate} {...p.plate} z={4} />}
    {p.streak > 0 && p.streak < 1 && <div style={{
      position: 'absolute', top: '15%', left: (-14 + 128 * p.streak) + '%', width: 240, height: 4, zIndex: 5, pointerEvents: 'none',
      background: 'linear-gradient(90deg, transparent, rgba(140,220,255,0.9))', borderRadius: 4,
      boxShadow: '0 0 18px rgba(120,210,255,0.9), 0 0 46px rgba(80,170,255,0.55)',
    }} />}
    {p.logos && LOGOS.map((g, i) => {
      const s = p.logos(i); if (s.opacity <= 0) return null;
      return <Photo key={i} {...g} {...s} z={6} />;
    })}
    {p.text && <Photo {...L.text} {...p.text} z={7} />}
    {p.setBtn && <Photo {...L.setBtn} {...p.setBtn} z={8} />}
    {p.entBtn && <Photo {...L.entBtn} {...p.entBtn} z={8} />}
    {p.full > 0 && <Photo cx={0.5} cy={0.5} rx={0} ry={0} opacity={p.full} z={9} />}
    {p.gleam > 0 && p.gleam < 1 && <div style={{
      position: 'absolute', top: 0, height: '31%', left: (18 + 54 * p.gleam) + '%', width: '9%', zIndex: 10, pointerEvents: 'none',
      background: 'linear-gradient(100deg, transparent, rgba(210,240,255,0.35), transparent)', mixBlendMode: 'screen',
    }} />}
    {p.flash > 0 && <div style={{
      position: 'absolute', inset: 0, zIndex: 20, pointerEvents: 'none', mixBlendMode: 'screen', opacity: p.flash,
      background: 'radial-gradient(60% 45% at 50% 16%, rgba(200,235,255,0.95), rgba(90,160,255,0.25) 55%, transparent 75%)',
    }} />}
    {p.vig > 0 && <div style={{
      position: 'absolute', inset: 0, zIndex: 30, pointerEvents: 'none', opacity: p.vig,
      background: 'radial-gradient(125% 115% at 50% 42%, transparent 46%, rgba(0,0,0,0.88) 100%)',
    }} />}
    <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: p.bars * 1080, background: '#000', zIndex: 40 }} />
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: p.bars * 1080, background: '#000', zIndex: 40 }} />
    {p.children}
  </div>;
}

/* ---------------- scenes ---------------- */
function PowerOn({ localTime: t, scene }) {
  useLabel(scene, t); useCues(t, [[0.05, 'ambient']]);
  return <Frame bg={0.04 + 0.08 * seg(t, 0.15, 1.3)} circuits={0.9 * seg(t, 0.3, 1.2)} bars={0.12} vig={0.85} />;
}
function Cars({ localTime: t, scene }) {
  useLabel(scene, t); useCues(t, [[0.02, 'whoosh'], [1.55, 'thump']]);
  const pc = seg(t, 0, 1.9, outCubic);
  return <Frame bg={0.12} circuits={0.9} bars={0.12} vig={0.85}
    groundGlow={0.5 * seg(t, 1.5, 2.1)}
    cars={{ scale: 0.5 + 0.5 * pc, blur: 18 * (1 - pc), bright: 0.25 + 0.75 * pc, opacity: seg(t, 0, 0.55) }} />;
}
function Emblem({ localTime: t, scene }) {
  useLabel(scene, t); useCues(t, [[0.25, 'blip1'], [0.62, 'blip2'], [1.38, 'impact']]);
  const pp = seg(t, 0.35, 1.5, outQuint);
  const flash = 0.5 * seg(t, 1.3, 1.48) * (1 - seg(t, 1.48, 2.1));
  return <Frame bg={0.12} circuits={0.9} bars={0.12} vig={0.85} groundGlow={0.5}
    cars={{}} streak={seg(t, 0.05, 1.05)}
    plate={{ opacity: seg(t, 0.35, 0.95), scale: 1.16 - 0.16 * pp, bright: 0.7 + 0.3 * pp, glow: 10 * (1 - pp) }}
    flash={flash} gleam={seg(t, 1.5, 2.25)} />;
}
function Marques({ localTime: t, scene }) {
  useLabel(scene, t); useCues(t, [[0.05, 'ripple']]);
  return <Frame bg={0.12} circuits={0.9} bars={0.12} vig={0.85} groundGlow={0.5}
    cars={{}} plate={{}}
    logos={i => {
      const st = 0.08 + i * 0.09, pi = seg(t, st, st + 0.42, outCubic);
      return { opacity: seg(t, st, st + 0.16), scale: 0.35 + 0.65 * pi, bright: 1 + 0.6 * (1 - pi), glow: 9 * pi * (1 - pi) * 4 };
    }} />;
}
function Showtime({ localTime: t, scene }) {
  useLabel(scene, t); useCues(t, [[0.45, 'chime']]);
  const ot = seg(t, 0.05, 0.6, outCubic), ob = seg(t, 0.25, 0.9, outCubic);
  const full = seg(t, 0.55, 1.2);
  return <Frame bg={0.12} circuits={0.9} groundGlow={0.5 * (1 - full)}
    bars={0.12 * (1 - seg(t, 0.1, 0.95))} vig={0.85 * (1 - seg(t, 0.2, 1.0))}
    cars={{}} plate={{}} logos={() => ({ opacity: 1 })}
    text={{ opacity: ot, dy: 26 * (1 - ot) }}
    setBtn={{ opacity: ob, dx: -90 * (1 - ob) }} entBtn={{ opacity: ob, dx: 90 * (1 - ob) }}
    full={full}>
    <HomeUI visible={full} />
  </Frame>;
}

/* ---------------- interactive end state ---------------- */
function HomeUI({ visible }) {
  const [focus, setFocus] = React.useState('enter');
  const [modal, setModal] = React.useState(false);
  const go = () => { AUDIO.play('click'); window.location.href = CFG.enterUrl || 'dashboard.html'; };
  const openSet = () => { AUDIO.play('click'); setModal(true); };
  React.useEffect(() => {
    if (visible < 0.9) return;
    const onKey = e => {
      if (modal) { if (e.key === 'Escape') setModal(false); return; }
      if (e.key === 'ArrowLeft') setFocus('setting');
      else if (e.key === 'ArrowRight') setFocus('enter');
      else if (e.key === 'Enter') { focus === 'enter' ? go() : openSet(); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [visible, modal, focus]);
  if (visible <= 0) return null;
  const hot = (r, id, fn) => <div onClick={fn} onMouseEnter={() => setFocus(id)} style={{
    position: 'absolute', zIndex: 50, cursor: 'pointer', borderRadius: 26,
    left: (r.cx - r.rx * 0.82) * 1920, top: (r.cy - r.ry * 0.68) * 1080,
    width: r.rx * 1.64 * 1920, height: r.ry * 1.36 * 1080, opacity: visible,
    animation: focus === id ? 'ecuFocusPulse 1.6s ease-in-out infinite' : 'none',
  }} />;
  return <React.Fragment>
    {hot(L.setBtn, 'setting', openSet)}
    {hot(L.entBtn, 'enter', go)}
    {modal && <SettingsModal onClose={() => setModal(false)} />}
  </React.Fragment>;
}

function SettingsModal({ onClose }) {
  const [oldP, setOldP] = React.useState(''); const [newP, setNewP] = React.useState('');
  const [conf, setConf] = React.useState(''); const [msg, setMsg] = React.useState(null);
  const stored = () => { try { return localStorage.getItem('ecu_tester_password') || '0000'; } catch (e) { return '0000'; } };
  const save = () => {
    if (oldP !== stored()) return setMsg({ bad: 1, text: 'Old password is incorrect.' });
    if (newP.length < 4) return setMsg({ bad: 1, text: 'New password must be at least 4 characters.' });
    if (newP !== conf) return setMsg({ bad: 1, text: 'Confirmation does not match.' });
    try { localStorage.setItem('ecu_tester_password', newP); } catch (e) {}
    AUDIO.play('chime'); setMsg({ bad: 0, text: 'Password updated.' });
    setTimeout(onClose, 950);
  };
  const field = (label, val, set) => <label style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
    <span style={{ font: '500 24px "Segoe UI", Roboto, Arial, sans-serif', color: '#9fc3dd', letterSpacing: 0.5 }}>{label}</span>
    <input type="password" value={val} onChange={e => set(e.target.value)} style={{
      background: 'rgba(10,20,30,0.85)', border: '1px solid rgba(90,180,255,0.35)', borderRadius: 12,
      padding: '16px 20px', font: '400 28px "Segoe UI", Roboto, Arial, sans-serif', color: '#e8f4ff',
    }} />
  </label>;
  const btn = (label, fn, primary) => <button onClick={fn} style={{
    flex: 1, padding: '16px 0', borderRadius: 14, cursor: 'pointer',
    font: '600 26px "Segoe UI", Roboto, Arial, sans-serif',
    background: primary ? 'linear-gradient(180deg, #1fd98b, #0f9e62)' : 'rgba(20,34,48,0.9)',
    color: primary ? '#04240f' : '#bcd8ec',
    border: primary ? '1px solid rgba(120,255,190,0.6)' : '1px solid rgba(90,180,255,0.3)',
  }}>{label}</button>;
  return <div onClick={onClose} style={{
    position: 'absolute', inset: 0, zIndex: 60, display: 'flex', alignItems: 'center', justifyContent: 'center',
    background: 'rgba(2,6,10,0.72)', backdropFilter: 'blur(5px)',
  }}>
    <div onClick={e => e.stopPropagation()} style={{
      width: 620, padding: '44px 48px', borderRadius: 22, display: 'flex', flexDirection: 'column', gap: 26,
      background: 'linear-gradient(180deg, rgba(14,24,36,0.97), rgba(8,14,22,0.97))',
      border: '1px solid rgba(90,180,255,0.4)', boxShadow: '0 0 60px rgba(40,140,255,0.25), 0 30px 80px rgba(0,0,0,0.7)',
    }}>
      <div style={{ font: '700 34px "Segoe UI", Roboto, Arial, sans-serif', color: '#e8f4ff', letterSpacing: 1 }}>Change Password</div>
      {field('Old password', oldP, setOldP)}
      {field('New password', newP, setNewP)}
      {field('Confirm new password', conf, setConf)}
      {msg && <div style={{ font: '500 24px "Segoe UI", Roboto, Arial, sans-serif', color: msg.bad ? '#ff7a7a' : '#3ce89a' }}>{msg.text}</div>}
      <div style={{ display: 'flex', gap: 18 }}>{btn('Cancel', onClose, false)}{btn('Save', save, true)}</div>
      <div style={{ font: '400 19px "Segoe UI", Roboto, Arial, sans-serif', color: '#5f7d95' }}>Default password: 0000</div>
    </div>
  </div>;
}

/* ---------------- root ---------------- */
function ECUIntro() {
  const [t, setTweak] = useTweaks(window.TWEAK_DEFAULTS);
  AUDIO.enabled = t.sound !== false;
  CFG.enterUrl = t.enterUrl || 'dashboard.html';
  return <div style={{ position: 'fixed', inset: 0, background: '#000' }}>
    <SceneStage width={1920} height={1080} scenes={window.OM_SCENES} playback={window.OM_PLAYBACK} bg="#020407">
      {{ 'Power On': PowerOn, 'Cars': Cars, 'Emblem': Emblem, 'Marques': Marques, 'Showtime': Showtime }}
    </SceneStage>
    <TweaksPanel>
      <TweakSection label="Intro" />
      <TweakToggle label="Sound (PS2-style synth)" value={t.sound !== false} onChange={v => setTweak('sound', v)} />
      <TweakText label="Enter button target" value={t.enterUrl} onChange={v => setTweak('enterUrl', v)} />
      <TweakSection label="Editing" />
      <TweakToggle label="Motion editor" value={t.motionEditor !== false} onChange={v => setTweak('motionEditor', v)} />
    </TweaksPanel>
  </div>;
}
window.ECUIntro = ECUIntro;
