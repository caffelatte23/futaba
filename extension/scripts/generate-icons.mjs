import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

import sharp from 'sharp';

const source = fileURLToPath(
  new URL('../assets/futaba-icon.svg', import.meta.url),
);
const output = new URL('../public/icon/', import.meta.url);
const sizes = [16, 32, 48, 128];

await mkdir(output, { recursive: true });
await Promise.all(
  sizes.map((size) =>
    sharp(source, { density: 288 })
      .resize(size, size)
      .png()
      .toFile(fileURLToPath(new URL(`${size}.png`, output))),
  ),
);
