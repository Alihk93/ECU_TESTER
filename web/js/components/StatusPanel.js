/* ECU_TESTER :: StatusPanel.js — grid of automotive tell-tales for the binary
   status lines. Uses ISO-style SVG glyphs (glyphs.js); IMMO uses the red photo.
   Each cell tints/glows when its status bit is active. */
import { statusTells } from "../theme.js";
import { glyph } from "./glyphs.js";

const IMMO_IMG = "assets/img/immo.png";

export class StatusPanel {
  constructor(mount) {
    this.cells = {};
    const grid = document.createElement("div");
    grid.className = "status-grid";
    for (const t of statusTells) {
      const cell = document.createElement("div");
      cell.className = "tell";
      cell.style.setProperty("--on", t.activeColor);
      const art =
        t.glyph === "immo"
          ? `<img class="tell-photo" src="${IMMO_IMG}" alt="${t.label}" draggable="false"/>`
          : `<span class="tell-glyph">${glyph(t.glyph)}</span>`;
      cell.innerHTML = `${art}<span class="tell-label">${t.label}</span>`;
      grid.appendChild(cell);
      this.cells[t.key] = cell;
    }
    mount.appendChild(grid);
  }
  update(status) {
    for (const key in this.cells) {
      this.cells[key].classList.toggle("on", !!status[key]);
    }
  }
}
