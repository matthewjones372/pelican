import { writeFileSync } from 'node:fs';

const DIRS = {
  Silhouette: {
    letter: 'A', axis: 'Mass',
    lede: 'Two solid shapes with one hairline of space between them. The upper mandible runs past the pouch to a blunt tip; the crown is a true half-circle, so the whole mark sits on two radii and two curves.',
    note: 'Strongest presence at README-header size, and the shape that survives worst-case rendering — a 16px favicon on a dark tab strip.',
    mark: (c, w) => `<g fill="${c}"><path d="M4.5 24.75 A11 11 0 0 1 26.5 22.75 L59.5 28.75 L56.5 32.25 L4.5 28.75 Z"></path><path d="M10.5 32.25 C11.5 45.75 20.5 51.75 30.5 49.75 C41.5 47.25 50.5 39.75 55.5 34.25 Z"></path></g>`,
  },
  Monoline: {
    letter: 'B', axis: 'Line',
    lede: 'The same bill drawn as a single constant stroke width rather than filled mass. Butt caps, no rounding — the terminals stay cut rather than softened.',
    note: 'Lightest of the four on a page of text. Below about 20px the stroke needs stepping up a notch or the counter fills in; the ramp below shows that adjustment.',
    mark: (c, w) => `<g fill="none" stroke="${c}" stroke-width="${w <= 20 ? 7 : w <= 32 ? 6.2 : 5.5}"><path d="M8.5 28.5 A11 11 0 0 1 28 25 L58 32 L55 36"></path><path d="M10 34 C11 50 20 56 31 54 C43 51 51 43 55 36.5"></path></g>`,
  },
  Monogram: {
    letter: 'C', axis: 'Letterform',
    lede: 'A geometric P built on one stem width and one bowl radius, with the bill growing out of the bowl at its lower right. The bowl is the pouch.',
    note: 'Says "library" before it says "bird" — it reads as a mark in a dependency list rather than an illustration. Most legible of the four at 16px.',
    mark: (c, w) => `<g><g fill="none" stroke="${c}" stroke-width="${w <= 20 ? 10 : 9}"><path d="M16.5 8 V56"></path><path d="M16.5 12.5 H34 A11.5 11.5 0 0 1 34 35.5 H22"></path></g><path d="M43 24 L62 33 L43 34 Z" fill="${c}"></path></g>`,
  },
  Facet: {
    letter: 'D', axis: 'Straight lines only',
    lede: 'No curve anywhere. Seven vertices, one flat crown, one triangular pouch — the bill as it would come off a drawing board rather than out of a sketchbook.',
    note: 'Coldest and sharpest of the four. Scales without any optical correction because there is nothing curved to thin out.',
    mark: (c, w) => `<g fill="${c}"><path d="M6 14 L26 14 L61 30 L57 33 L6 27 Z"></path><path d="M8 31 L57 36 L24 55 Z"></path></g>`,
  },
};

const svg = (d, size, color) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 64 64" aria-label="${d} mark">${DIRS[d].mark(color, size)}</svg>`;

const INK = '#14171c', PAPER = '#f7f6f3', DARK = '#0d1117', LIGHT = '#e8eaed';
const RAMP = [64, 48, 32, 24, 16];

const tile = (bg, border, inner) =>
  `<div style="display: flex; align-items: center; justify-content: center; background: ${bg}; border: 1px solid ${border}; border-radius: 4px; padding: 28px 20px">${inner}</div>`;

const rampRow = (d, bg, border, color, label) => `
      <div style="display: flex; flex-direction: column; gap: 10px">
        <div style="font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 10.5px; letter-spacing: 0.14em; text-transform: uppercase; color: #6f7b8a">${label}</div>
        <div style="display: flex; align-items: flex-end; gap: 26px; background: ${bg}; border: 1px solid ${border}; border-radius: 4px; padding: 18px 24px; color: ${color}">
          ${RAMP.map((s) => `<div style="display: flex; flex-direction: column; align-items: center; gap: 9px">${svg(d, s, color)}<span style="font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 10px; color: #7b8794">${s}</span></div>`).join('\n          ')}
        </div>
      </div>`;

for (const [name, d] of Object.entries(DIRS)) {
  const html = `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap">
  <style>
    body { margin: 0; font-family: "IBM Plex Sans", ui-sans-serif, system-ui, sans-serif; }
    a { color: #6aa9c4; } a:hover { color: #8cc3da; }
  </style>
</helmet>
<div style="width: 940px; height: 760px; background: ${DARK}; color: ${LIGHT}; padding: 48px; box-sizing: border-box; display: flex; flex-direction: column; gap: 32px">

  <div style="display: flex; align-items: flex-start; gap: 20px">
    <div style="font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 13px; color: #6f7b8a; padding-top: 5px">${d.letter}</div>
    <div style="display: flex; flex-direction: column; gap: 8px; flex-grow: 1">
      <div style="display: flex; align-items: baseline; gap: 14px">
        <div style="font-size: 26px; font-weight: 600; letter-spacing: -0.015em">${name}</div>
        <div style="font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 11px; letter-spacing: 0.14em; text-transform: uppercase; color: #6f7b8a">${d.axis}</div>
      </div>
      <div style="font-size: 14.5px; line-height: 1.6; color: #98a3b2; max-width: 700px; text-wrap: pretty">${d.lede}</div>
    </div>
  </div>

  <div style="display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px">
    ${tile('#151a21', '#232a34', svg(name, 148, LIGHT))}
    ${tile(PAPER, '#232a34', svg(name, 148, INK))}
    ${tile('#151a21', '#232a34', `<span style="color: {{accent}}">${svg(name, 148, 'currentColor')}</span>`)}
  </div>

  <div style="display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px">
${rampRow(name, '#151a21', '#232a34', LIGHT, 'On dark')}
${rampRow(name, PAPER, '#232a34', INK, 'On paper')}
  </div>

  <div style="display: flex; flex-direction: column; gap: 10px">
    <div style="font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 10.5px; letter-spacing: 0.14em; text-transform: uppercase; color: #6f7b8a">README header</div>
    <div style="display: flex; align-items: center; gap: 18px; background: #151a21; border: 1px solid #232a34; border-radius: 4px; padding: 22px 26px">
      ${svg(name, 44, LIGHT)}
      <div style="display: flex; flex-direction: column; gap: 3px">
        <div style="font-size: 27px; font-weight: 500; letter-spacing: -0.02em; line-height: 1">Pelican</div>
        <div style="font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 12px; color: #7b8794; letter-spacing: 0.01em">Type-safe HTTP for Kotlin</div>
      </div>
    </div>
  </div>

  <div style="font-size: 13px; line-height: 1.6; color: #7b8794; max-width: 760px; text-wrap: pretty; margin-top: auto">${d.note}</div>
</div>
</x-dc>
<script data-dc-script data-props='{"accent":{"editor":"color","default":"#6aa9c4","options":["#6aa9c4","#c9a227","#d9694b","#8f9bb0"]}}'>
class Component extends DCLogic {
  renderVals() {
    return { accent: this.props.accent ?? '#6aa9c4' };
  }
}
</script>
</body>
</html>
`;
  writeFileSync(`${name}.dc.html`, html);
  console.log('wrote', name);
}
