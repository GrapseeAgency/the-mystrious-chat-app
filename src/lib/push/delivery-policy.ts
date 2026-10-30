/** Provider queues are bounded even for permanent messages. An explicit invalid
 * or expired deadline never becomes the provider's default multi-week lifetime.
 * Retractions (banner cancels) get a short window: they are only useful while
 * the tray entry they cancel can still be showing. */
export function pushDeliveryWindow(payload: { kind: string; expiresAt?: string }, now = Date.now()):
  { ttlSeconds: number; expiresAtSeconds: number } | null {
  const cap = payload.kind === 'message' || payload.kind === 'read-state' ? 3600 : payload.kind === 'retract' ? 300 : 60
  let ttlSeconds = cap
  if (payload.expiresAt !== undefined) {
    const deadline = typeof payload.expiresAt === 'string' ? Date.parse(payload.expiresAt) : NaN
    if (!Number.isFinite(deadline)) return null
    ttlSeconds = Math.min(cap, Math.floor((deadline - now) / 1000))
  }
  if (ttlSeconds <= 0) return null
  return { ttlSeconds, expiresAtSeconds: Math.floor((now + ttlSeconds * 1000) / 1000) }
}

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}

/** Credentials, project/topic/environment mismatch, malformed payloads, quota
 * and transient errors are NOT evidence that a device registration is dead. */
export function shouldRetirePushToken(platform: 'android' | 'ios', status: number, body: unknown): boolean {
  const row = object(body)
  if (platform === 'ios') return status === 410 && row?.reason === 'Unregistered' && apnsInvalidatedAt(body) !== undefined
  const error = object(row?.error)
  if (status !== 404 || !Array.isArray(error?.details)) return false
  return error.details.some(detail => {
    const value = object(detail)
    return value?.['@type'] === 'type.googleapis.com/google.firebase.fcm.v1.FcmError' && value.errorCode === 'UNREGISTERED'
  })
}

/** APNs timestamps describe when this registration was invalidated, not when
 * the response arrived. A later client re-registration must not be erased. */
export function apnsInvalidatedAt(body: unknown): number | undefined {
  const timestamp = object(body)?.timestamp
  return typeof timestamp === 'number' && Number.isSafeInteger(timestamp) && timestamp >= 0 ? timestamp : undefined
}
