// RFC 6238 Appendix B test vectors (SHA1, ASCII secret "12345678901234567890"),
// truncated to 6 digits. Run: node scripts/verify-totp-vectors.mjs
import { createHmac } from 'crypto'

// Inline copy of the hotp/base32 logic from src/lib/totp.ts (route files are TS;
// this script exists so the algorithm can be sanity-checked without a TS runner).
const ALPHA = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
function base32Encode(buf) {
  let bits = 0, value = 0, out = ''
  for (const b of buf) { value = (value << 8) | b; bits += 8
    while (bits >= 5) { out += ALPHA[(value >>> (bits - 5)) & 31]; bits -= 5 } }
  if (bits > 0) out += ALPHA[(value << (5 - bits)) & 31]
  return out
}
function base32Decode(s) {
  const clean = s.toUpperCase().replace(/[^A-Z2-7]/g, '')
  let bits = 0, value = 0; const out = []
  for (const c of clean) { value = (value << 5) | ALPHA.indexOf(c); bits += 5
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8 } }
  return Buffer.from(out)
}
function hotp(secret, counter) {
  const buf = Buffer.alloc(8); buf.writeBigUInt64BE(BigInt(counter))
  const d = createHmac('sha1', base32Decode(secret)).update(buf).digest()
  const o = d[d.length - 1] & 0x0f
  const code = ((d[o] & 0x7f) << 24) | (d[o+1] << 16) | (d[o+2] << 8) | d[o+3]
  return String(code % 1_000_000).padStart(6, '0')
}

const secret = base32Encode(Buffer.from('12345678901234567890', 'ascii'))
const vectors = [[59, '287082'], [1111111109, '081804'], [1111111111, '050471'],
  [1234567890, '005924'], [2000000000, '279037'], [20000000000, '353130']]
let failed = 0
for (const [t, expected] of vectors) {
  const got = hotp(secret, Math.floor(t / 30))
  const ok = got === expected
  if (!ok) failed++
  console.log(`T=${t}: got ${got}, expected ${expected} ${ok ? 'OK' : 'FAIL'}`)
}
process.exit(failed ? 1 : 0)
