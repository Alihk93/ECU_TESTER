/* ECU_TESTER :: IacPanel.js — IAC stepper photo with 4 phase LEDs (A–D) driven by
   iac byte bits 0-3. Lit phases glow; the sequence visualises stepper motion. */
const IMG = "assets/img/iac.png";

export class IacPanel {
  constructor(mount) {
    const el = document.createElement("div");
    el.className = "iac";
    el.innerHTML = `
      <img class="iac-photo" src="${IMG}" alt="IAC stepper" draggable="false"/>
      <div class="iac-phases">
        ${["A", "B", "C", "D"].map((p) => `<span class="phase"><i></i>${p}</span>`).join("")}
      </div>`;
    mount.appendChild(el);
    this.dots = [...el.querySelectorAll(".phase")];
  }
  update(iac) {
    for (let i = 0; i < 4; i++) this.dots[i].classList.toggle("lit", !!((iac >> i) & 1));
  }
}
