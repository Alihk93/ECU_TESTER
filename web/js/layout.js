/* ECU_TESTER :: layout.js — free-canvas engine.
   Modules are absolutely positioned in a fixed design-space stage (theme.STAGE);
   the whole stage is scaled to fit the viewport (FHD 1:1, 4K 2x). Modules are
   drag/resize-able in Edit mode; per-id {x,y,w,h} persists to localStorage. */
import { STAGE } from "./theme.js";

const KEY = "ecu_layout_v1";
const MINW = 140, MINH = 110;

export class Layout {
  constructor(stageEl) {
    this.stage = stageEl;
    this.mods = new Map();
    this.saved = this._read();
    this.editing = false;
    this.scale = 1;
    window.addEventListener("resize", () => this.fit());
    this.fit();
  }

  fit() {
    const vw = window.innerWidth, vh = window.innerHeight;
    const top = this.stage.parentElement.getBoundingClientRect().top;
    const avail = vh - top;
    this.scale = Math.min(vw / STAGE.w, avail / STAGE.h);
    this.stage.style.width = STAGE.w + "px";
    this.stage.style.height = STAGE.h + "px";
    this.stage.style.transform = `translate(${(vw - STAGE.w * this.scale) / 2}px, 0) scale(${this.scale})`;
  }

  register(mod) {
    const el = document.createElement("section");
    el.className = "module";
    el.dataset.id = mod.id;
    el.innerHTML = `
      <header class="mod-head"><span class="mod-title">${mod.title}</span></header>
      <div class="mod-body"></div>
      <span class="mod-resize" title="resize"></span>`;
    const rect = this.saved[mod.id] || mod.rect;
    this._place(el, rect);
    this.stage.appendChild(el);
    this._wire(el);
    this.mods.set(mod.id, el);
    return el.querySelector(".mod-body");
  }

  _place(el, r) {
    el.style.left = r.x + "px";
    el.style.top = r.y + "px";
    el.style.width = r.w + "px";
    el.style.height = r.h + "px";
  }

  _wire(el) {
    const head = el.querySelector(".mod-head");
    const handle = el.querySelector(".mod-resize");

    const drag = (startEvt, mode) => {
      if (!this.editing) return;
      startEvt.preventDefault();
      const sx = startEvt.clientX, sy = startEvt.clientY;
      const x0 = parseFloat(el.style.left), y0 = parseFloat(el.style.top);
      const w0 = parseFloat(el.style.width), h0 = parseFloat(el.style.height);
      el.classList.add("dragging");
      const move = (e) => {
        const dx = (e.clientX - sx) / this.scale, dy = (e.clientY - sy) / this.scale;
        if (mode === "move") {
          el.style.left = Math.max(0, Math.min(STAGE.w - w0, x0 + dx)) + "px";
          el.style.top = Math.max(0, Math.min(STAGE.h - h0, y0 + dy)) + "px";
        } else {
          el.style.width = Math.max(MINW, w0 + dx) + "px";
          el.style.height = Math.max(MINH, h0 + dy) + "px";
        }
      };
      const up = () => {
        el.classList.remove("dragging");
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
        this._commit(el);
      };
      window.addEventListener("pointermove", move);
      window.addEventListener("pointerup", up);
    };

    head.addEventListener("pointerdown", (e) => drag(e, "move"));
    handle.addEventListener("pointerdown", (e) => drag(e, "resize"));
  }

  _commit(el) {
    this.saved[el.dataset.id] = {
      x: Math.round(parseFloat(el.style.left)),
      y: Math.round(parseFloat(el.style.top)),
      w: Math.round(parseFloat(el.style.width)),
      h: Math.round(parseFloat(el.style.height)),
    };
    localStorage.setItem(KEY, JSON.stringify(this.saved));
  }

  setEditing(on) {
    this.editing = on;
    document.body.classList.toggle("editing", on);
  }

  reset(modules) {
    localStorage.removeItem(KEY);
    this.saved = {};
    for (const m of modules) this._place(this.mods.get(m.id), m.rect);
  }

  _read() {
    try { return JSON.parse(localStorage.getItem(KEY)) || {}; }
    catch { return {}; }
  }
}
