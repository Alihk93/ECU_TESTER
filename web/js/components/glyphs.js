/* ECU_TESTER :: glyphs.js — inline SVG tell-tale symbols for status indicators.
   Each returns an <svg> string sized to a 48x48 viewBox; `currentColor` is driven
   by CSS so the same glyph tints on/off. Photo-based indicators (IMMO) bypass this. */

const wrap = (body) =>
  `<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="2.4"
        stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;

export const GLYPHS = {
  battery: wrap(`
    <rect x="7" y="16" width="34" height="20" rx="2.5"/>
    <path d="M14 16v-4h7v4M27 16v-4h7v4"/>
    <path d="M15 26h6M18 23v6M28 26h6"/>`),
  key: wrap(`
    <circle cx="16" cy="24" r="7"/>
    <path d="M23 24h18M35 24v6M41 24v5"/>
    <circle cx="16" cy="24" r="2.4" fill="currentColor" stroke="none"/>`),
  power: wrap(`
    <path d="M24 8v16"/>
    <path d="M15 14a13 13 0 1 0 18 0"/>`),
  throttle: wrap(`
    <circle cx="24" cy="24" r="15"/>
    <path d="M24 24l9-6"/>
    <path d="M14 30c3 3 7 4 10 4"/>`),
  fan: wrap(`
    <circle cx="24" cy="24" r="3.2" fill="currentColor" stroke="none"/>
    <path d="M24 21c-2-6 1-12 5-13 2 4 0 9-5 13z"/>
    <path d="M27 24c6-2 12 1 13 5-4 2-9 0-13-5z"/>
    <path d="M24 27c2 6-1 12-5 13-2-4 0-9 5-13z"/>
    <path d="M21 24c-6 2-12-1-13-5 4-2 9 0 13 5z"/>`),
  pump: wrap(`
    <path d="M12 40V20l12-8 12 8v20z"/>
    <path d="M12 40h24"/>
    <path d="M24 40V27"/>
    <circle cx="24" cy="22" r="3.2"/>`),
  relay: wrap(`
    <rect x="9" y="14" width="30" height="20" rx="2"/>
    <path d="M14 24h6l3-4 3 8 3-4h6"/>`),
  immo: wrap(`
    <path d="M24 7l13 5v9c0 9-6 15-13 20-7-5-13-11-13-20v-9z"/>
    <circle cx="24" cy="21" r="4"/>
    <path d="M24 25v7"/>`),
};

export function glyph(id) {
  return GLYPHS[id] || GLYPHS.relay;
}
