import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

// Run with: npx tsx --test src/lib/env.test.ts

const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'env.ts'), 'utf8')
// Comments may mention the forbidden form; only code counts.
const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')

test('NEXT_PUBLIC_ variables are read by their literal name, never by a computed key', () => {
  // Next.js replaces `process.env.NEXT_PUBLIC_X` with its value when it builds
  // the browser bundle, but only when it is written out literally. A computed
  // lookup (`process.env[name]`) is left alone and is undefined in the browser,
  // so the "must be set in production" guard threw on every visitor's machine
  // and took the page down (the /donate "Sponsor a Block" map, 2026-10-05).
  assert.ok(!/process\.env\[/.test(code), 'env.ts must not index process.env with a computed key')
  assert.match(code, /process\.env\.NEXT_PUBLIC_APP_URL\b/)
  assert.match(code, /process\.env\.NEXT_PUBLIC_TILE_SERVER_URL\b/)
})

test('the accessors return what is configured', async () => {
  process.env.NEXT_PUBLIC_APP_URL = 'https://example.test'
  process.env.NEXT_PUBLIC_TILE_SERVER_URL = 'https://tiles.example.test'
  const { appUrl, tileServerUrl } = await import('./env')
  assert.equal(appUrl(), 'https://example.test')
  assert.equal(tileServerUrl(), 'https://tiles.example.test')
})
