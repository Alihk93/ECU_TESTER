/* ECU_TESTER :: layout.js — static free-canvas placement.
   Modules are absolutely positioned in a fixed design-space stage (theme.STAGE);
   the whole stage is scaled to fit the viewport (FHD 1:1, 4K 2x). Positions are
   fixed for the kiosk — no drag/edit. */
import { STAGE } from "./theme.js";

export class Layout {
  constructor(stageEl) {
    this.stage = stageEl;
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
      <div class="mod-body"></div>`;
    const r = mod.rect;
    el.style.left = r.x + "px";
    el.style.top = r.y + "px";
    el.style.width = r.w + "px";
    el.style.height = r.h + "px";
    this.stage.appendChild(el);
    return el.querySelector(".mod-body");
  }
}
