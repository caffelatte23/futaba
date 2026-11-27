// Fails if a vector drawable hard-codes a visible colour. Icons must use #FF000000 and be tinted at the
// use site from FutabaTheme; launcher icon layers (ic_launcher*) are the only exception.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const res = 'android/app/src/main/res';
const allowed = new Set(['#FF000000', '#000000']);
const bad = [];
for (const dir of readdirSync(res).filter((d) => d.startsWith('drawable'))) {
  for (const file of readdirSync(join(res, dir)).filter((f) => f.endsWith('.xml') && !f.startsWith('ic_launcher'))) {
    const text = readFileSync(join(res, dir, file), 'utf8');
    for (const m of text.matchAll(/android:(fillColor|strokeColor|tint)="(#[0-9A-Fa-f]+)"/g)) {
      if (!allowed.has(m[2].toUpperCase())) bad.push(`${dir}/${file}: ${m[1]}=${m[2]}`);
    }
  }
}
if (bad.length) {
  console.error('Hard-coded drawable colours (use #FF000000 and tint from FutabaTheme):\n' + bad.join('\n'));
  process.exit(1);
}
