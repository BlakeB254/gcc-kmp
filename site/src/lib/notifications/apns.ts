import http2 from 'node:http2'
import { importPKCS8, SignJWT } from 'jose'

/**
 * Native Apple Push Notification service (APNs) sender — token-based auth.
 *
 * The iOS app registers a real APNs device token (see
 * /api/workers/me/device-token). This module delivers native pushes to that
 * token over APNs' HTTP/2 endpoint. This is distinct from web-push/VAPID
 * (browsers/PWA) in lib/notifications/index.ts, which cannot reach a native
 * iOS device.
 *
 * Required env (all must be set for delivery; otherwise sends are skipped):
 *   APNS_KEY_P8    — contents of the AuthKey_XXXX.p8 (PKCS#8 PEM). Newlines may
 *                    be real or escaped as "\n" (Vercel env friendly).
 *   APNS_KEY_ID    — the 10-char Key ID from App Store Connect.
 *   APNS_TEAM_ID   — the 10-char Apple Team ID.
 *   APNS_BUNDLE_ID — defaults to com.globalcommunitycleaners.worker.
 *   APNS_ENV       — "production" → api.push.apple.com, else api.sandbox.push.apple.com.
 *                    Default "sandbox" to match the app's development entitlement.
 */

export interface ApnsPayload {
  title: string
  body: string
  /** Extra keys placed at the top level of the notification (siblings of `aps`). */
  data?: Record<string, string | number | boolean>
}

export interface ApnsResult {
  success: boolean
  status?: number
  /** APNs `reason` (e.g. BadDeviceToken, Unregistered) or a local error string. */
  error?: string
}

function apnsConfig() {
  const keyP8 = process.env.APNS_KEY_P8
  const keyId = process.env.APNS_KEY_ID
  const teamId = process.env.APNS_TEAM_ID
  if (!keyP8 || !keyId || !teamId) return null
  return {
    keyP8: keyP8.replace(/\\n/g, '\n'),
    keyId,
    teamId,
    bundleId: process.env.APNS_BUNDLE_ID || 'com.globalcommunitycleaners.worker',
    host:
      process.env.APNS_ENV === 'production'
        ? 'https://api.push.apple.com'
        : 'https://api.sandbox.push.apple.com',
  }
}

/** Whether APNs is configured. Lets callers skip cleanly with a clear reason. */
export function apnsConfigured(): boolean {
  return apnsConfig() !== null
}

// Provider tokens are valid 20–60 min; cache and refresh well within that.
let cachedJwt: { token: string; mintedAtMs: number } | null = null
const JWT_TTL_MS = 30 * 60 * 1000

async function providerToken(now: number): Promise<string> {
  const cfg = apnsConfig()!
  if (cachedJwt && now - cachedJwt.mintedAtMs < JWT_TTL_MS) return cachedJwt.token
  const key = await importPKCS8(cfg.keyP8, 'ES256')
  const token = await new SignJWT({})
    .setProtectedHeader({ alg: 'ES256', kid: cfg.keyId })
    .setIssuer(cfg.teamId)
    .setIssuedAt(Math.floor(now / 1000))
    .sign(key)
  cachedJwt = { token, mintedAtMs: now }
  return token
}

/**
 * Send one native APNs alert push to a device token.
 * Returns { success:false, error } rather than throwing, so callers can record
 * the failure against the outbox row and continue.
 */
export async function sendApns(
  deviceToken: string,
  payload: ApnsPayload,
  nowMs: number = Date.now()
): Promise<ApnsResult> {
  const cfg = apnsConfig()
  if (!cfg) return { success: false, error: 'APNs is not configured (APNS_KEY_P8/KEY_ID/TEAM_ID)' }

  let jwt: string
  try {
    jwt = await providerToken(nowMs)
  } catch (err) {
    return { success: false, error: `APNs key error: ${err instanceof Error ? err.message : String(err)}` }
  }

  const aps = {
    aps: { alert: { title: payload.title, body: payload.body }, sound: 'default' },
    ...(payload.data ?? {}),
  }
  const bodyStr = JSON.stringify(aps)

  return new Promise<ApnsResult>((resolve) => {
    let settled = false
    const done = (r: ApnsResult) => {
      if (settled) return
      settled = true
      resolve(r)
    }

    const client = http2.connect(cfg.host)
    client.on('error', (err) => {
      done({ success: false, error: `APNs connection error: ${err.message}` })
      client.close()
    })

    const req = client.request({
      ':method': 'POST',
      ':path': `/3/device/${deviceToken}`,
      authorization: `bearer ${jwt}`,
      'apns-topic': cfg.bundleId,
      'apns-push-type': 'alert',
      'apns-priority': '10',
      'content-type': 'application/json',
    })

    let status = 0
    let respBody = ''
    req.on('response', (headers) => {
      status = Number(headers[':status']) || 0
    })
    req.setEncoding('utf8')
    req.on('data', (chunk) => {
      respBody += chunk
    })
    req.on('end', () => {
      if (status === 200) {
        done({ success: true, status })
      } else {
        let reason = respBody
        try {
          reason = JSON.parse(respBody).reason || respBody
        } catch {
          /* keep raw body */
        }
        done({ success: false, status, error: reason || `APNs HTTP ${status}` })
      }
      client.close()
    })
    req.on('error', (err) => {
      done({ success: false, error: `APNs request error: ${err.message}` })
      client.close()
    })

    req.write(bodyStr)
    req.end()
  })
}
