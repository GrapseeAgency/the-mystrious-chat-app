#!/usr/bin/env node
// Emoji audit scanner: every line containing an emoji codepoint,
// with file:line receipts. Skips generated/vendored/lock files.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.argv[2] || '/home/z/my-project';
const SKIP_DIRS = new Set([
  'node_modules', '.git', '.next', 'build', 'dist', 'generated-client',
  '.gradle', '.kotlin', 'Pods', 'DerivedData', '.turbo',
]);
const SKIP_EXT = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.keystore', '.jar',
  '.lock', '.woff', '.woff2', '.ttf', '.otf', '.so', '.node', '.wasm',
  '.mp3', '.mp4', '.wav', '.m4a', '.db',
]);
const EMOJI_RE = /[\u{1F000}-\u{1FAFF}]|[\u{2600}-\u{27BF}]|[\u{2B00}-\u{2BFF}]|[\u{FE00}-\u{FE0F}]|[\u{1F1E6}-\u{1F1FF}]|\u{303D}|\u{3030}|\u{303E}|\u{2049}|\u{203C}|\u{2122}|\u{2139}/u;
const results = [];

function walk(dir) {
  let entries;
  try { entries = readdirSync(dir); } catch { return; }
  for (const name of entries) {
    const p = join(dir, name);
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) {
      if (!SKIP_DIRS.has(name)) walk(p);
      continue;
    }
    if (SKIP_EXT.has(name.slice(name.lastIndexOf('.')))) continue;
    let text;
    try { text = readFileSync(p, 'utf8'); } catch { continue; }
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (EMOJI_RE.test(lines[i])) {
        results.push(`${p.replace(ROOT + '/', '')}:${i + 1}: ${lines[i].trim().slice(0, 160)}`);
      }
    }
  }
}

walk(ROOT);
console.log(results.join('\n'));
console.log(`\nTOTAL LINES WITH EMOJI: ${results.length}`);
