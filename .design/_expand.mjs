import { readFileSync, writeFileSync } from 'node:fs';
let h = readFileSync('Main.dc.html', 'utf8');
const data = {
  ramp: [64,48,32,24,16].map(px => ({ px })),
  files: [
    { name:'pelican-mark.svg', note:'currentColor' },
    { name:'pelican-mark-light.svg', note:'ink, light bg' },
    { name:'pelican-mark-dark.svg', note:'paper, dark bg' },
    { name:'favicon.svg', note:'prefers-color-scheme' },
    { name:'favicon-32.png', note:'tab icon fallback' },
    { name:'favicon-180.png', note:'apple-touch-icon' },
  ],
  donts: [
    'Close the gap between mandible and pouch — it is the whole read.',
    'Add an eye. The mark is a bill, not a face.',
    'Outline it or apply a stroke; it is solid mass by design.',
    'Rotate, shear, or set it below 16px.',
  ],
};
const re = /<sc-for list="\{\{(\w+)\}\}" as="(\w+)"[^>]*>([\s\S]*?)<\/sc-for>/g;
h = h.replace(re, (_m, list, as, body) =>
  data[list].map(it => body.replace(new RegExp(`\\{\\{${as}(\\.\\w+)?\\}\\}`, 'g'),
    (_x, p) => p ? it[p.slice(1)] : it)).join(''));
writeFileSync('_p1.html', h);
console.log('expanded', (h.match(/sc-for/g) || []).length, 'loops remaining');
