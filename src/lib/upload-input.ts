import { DOC_MAX_BYTES } from '@/lib/serializers'

export const MAX_UPLOAD_JSON_BYTES = Math.ceil(DOC_MAX_BYTES / 3) * 4 + 4096
export class UploadInputError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

/** Bound the request before JSON/base64 decoding, including chunked requests. */
export async function readUploadBody(req: Request, maximum = MAX_UPLOAD_JSON_BYTES, timeoutMs = 15_000): Promise<Record<string, unknown>> {
  const declared = req.headers.get('content-length')
  if (declared && /^\d+$/.test(declared) && Number(declared) > maximum) {
    throw new UploadInputError('Upload request is too large.', 413)
  }
  if (!req.body) throw new UploadInputError('An upload body is required.', 400)
  const reader = req.body.getReader()
  let timedOut = false
  const timeout = setTimeout(() => { timedOut = true; void reader.cancel().catch(() => {}) }, timeoutMs)
  const bytes = Buffer.allocUnsafe(maximum)
  const deadline = Date.now() + timeoutMs
  let size = 0
  let emptyChunks = 0
  try {
    while (true) {
      const {done, value} = await reader.read()
      if (timedOut || Date.now() >= deadline) throw new UploadInputError('Upload body timed out. Retry when connected.', 408)
      if (done) break
      if (value.byteLength === 0) {
        if (++emptyChunks > 16) throw new UploadInputError('Upload body made no progress.', 400)
        continue
      }
      emptyChunks = 0
      if (size + value.byteLength > maximum) throw new UploadInputError('Upload request is too large.', 413)
      bytes.set(value, size)
      size += value.byteLength
    }
    const value: unknown = JSON.parse(bytes.toString('utf8', 0, size))
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object')
    return value as Record<string, unknown>
  } catch (error) {
    void reader.cancel().catch(() => {})
    if (error instanceof UploadInputError) throw error
    throw new UploadInputError('The upload body is not valid JSON.', 400)
  } finally {
    clearTimeout(timeout)
    reader.releaseLock()
  }
}

/** Node's Buffer decoder accepts malformed padding and nonzero trailing bits. */
export function decodeUploadBase64(value: string): Buffer | null {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value) || value.length % 4 === 1 ||
      (value.includes('=') && value.length % 4 !== 0)) return null
  const bytes = Buffer.from(value, 'base64')
  return bytes.length > 0 && bytes.toString('base64').replace(/=+$/, '') === value.replace(/=+$/, '') ? bytes : null
}
