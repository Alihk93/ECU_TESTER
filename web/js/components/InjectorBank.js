/* ECU_TESTER :: InjectorBank.js — injector cells (port or GDI photo) with an
   animated spray sprite on activity. Reused for injReg (injector.png) and
   injGdi (gdi.png) via opts.img; cylinder count via opts.count. */
const SPRAY = "assets/img/spray.png";

export class InjectorBank {
  constructor(mount, opts) {
    this.count = opts.count || 8;
    this.img = "assets/img/" + opts.img;
    this.cells = [];
    const grid = document.createElement("div");
    grid.className = "cell-grid inj-grid";
    for (let i = 0; i < this.count; i++) {
      const c = document.createElement("div");
      c.className = "cell";
      c.innerHTML = `
        <div class="cell-art">
          <img class="inj-body" src="${this.img}" alt="injector ${i + 1}" draggable="false"/>
          <img class="inj-spray" src="${SPRAY}" alt="" draggable="false"/>
        </div>
        <span class="cell-label">${i + 1}</span>`;
      grid.appendChild(c);
      this.cells.push(c);
    }
    mount.appendChild(grid);
  }
  update(bits) {
    for (let i = 0; i < this.count; i++) {
      this.cells[i].classList.toggle("spraying", !!bits[i]);
    }
  }
}
