/* ECU_TESTER :: CoilBank.js — 8 ignition-coil cells (coil.png) that spark-flash
   on activity. `coils` bits are latched-since-last-frame, so a set bit = flash. */
const IMG = "assets/img/coil.png";

export class CoilBank {
  constructor(mount, opts) {
    this.count = opts.count || 8;
    this.cells = [];
    const grid = document.createElement("div");
    grid.className = "cell-grid coil-grid";
    for (let i = 0; i < this.count; i++) {
      const c = document.createElement("div");
      c.className = "cell";
      c.innerHTML = `
        <div class="cell-art">
          <img src="${IMG}" alt="coil ${i + 1}" draggable="false"/>
          <span class="spark"></span>
        </div>
        <span class="cell-label">C${i + 1}</span>`;
      grid.appendChild(c);
      this.cells.push(c);
    }
    mount.appendChild(grid);
  }
  update(bits) {
    for (let i = 0; i < this.count; i++) {
      const on = !!bits[i];
      const c = this.cells[i];
      c.classList.toggle("active", on);
      if (on) {
        c.classList.remove("fire");
        void c.offsetWidth; // restart animation
        c.classList.add("fire");
      }
    }
  }
}
