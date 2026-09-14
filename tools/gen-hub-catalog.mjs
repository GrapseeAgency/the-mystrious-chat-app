// Wave 7 — generates the native hub catalog asset from the single web source
// (src/lib/hub-catalog.ts). Run: node tools/gen-hub-catalog.mjs
// Emits apps/android/app/src/main/assets/hub_catalog.json + apps/ios/Pulse/Resources/hub_catalog.json
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
const src = readFileSync('src/lib/hub-catalog.ts', 'utf8')

// Slice the top-level [...] or {...} that follows `export const NAME`
function grab(name) {
  const start = src.indexOf(`export const ${name}`.replace('MATRIX', 'MATRIX:'))
  if (start < 0) throw new Error(`${name} not found`)
  const eq = src.indexOf('=', start)
  const openRel = src.slice(eq).search(/[[{]/)
  const open = eq - start + openRel
  const openChar = src[start + open]
  const closeChar = openChar === '[' ? ']' : '}'
  let depth = 0
  for (let i = start + open; i < src.length; i++) {
    if (src[i] === openChar) depth++
    else if (src[i] === closeChar) { depth--; if (depth === 0) return src.slice(start + open, i + 1) }
  }
  throw new Error(`${name} braces unbalanced`)
}

const apps = []
const matrixSrc = grab('MATRIX')
const entryRe = /n:\s*(\d+),\s*name:\s*'([^']*)',\s*nav:\s*'([^']*)',\s*input:\s*'([^']*)',\s*category:\s*'([^']*)',\s*secret:\s*'([^']*)'/g
let m
while ((m = entryRe.exec(matrixSrc)) !== null) {
  apps.push({ n: Number(m[1]), name: m[2], nav: m[3], input: m[4], category: m[5], secret: m[6] })
}
if (apps.length !== 100) throw new Error(`expected 100 apps, got ${apps.length}`)

const tagSrc = grab('APP_TAGLINES')
const tagRe = /(\d+):\s*'([^']*)'/g
const taglines = {}
while ((m = tagRe.exec(tagSrc)) !== null) taglines[m[1]] = m[2]

const metaSrc = grab('CATEGORY_META')
const metaRe = /slug:\s*'([^']*)',\s*label:\s*'([^']*)',\s*blurb:\s*'([^']*)',\s*accent:\s*\['([^']*)',\s*'([^']*)'\]/g
const categories = []
while ((m = metaRe.exec(metaSrc)) !== null) {
  categories.push({ slug: m[1], label: m[2], blurb: m[3], from: m[4], to: m[5] })
}

const out = { apps, categories, taglines }
mkdirSync('apps/android/app/src/main/assets', { recursive: true })
mkdirSync('apps/ios/Pulse/Resources', { recursive: true })
const json = JSON.stringify(out, null, 1)
writeFileSync('apps/android/app/src/main/assets/hub_catalog.json', json)
writeFileSync('apps/ios/Pulse/Resources/hub_catalog.json', json)
console.log(`apps=${apps.length} categories=${categories.length} taglines=${Object.keys(taglines).length}`)
