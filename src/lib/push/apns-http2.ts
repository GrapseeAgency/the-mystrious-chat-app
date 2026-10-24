import { connect, type ClientHttp2Stream, type OutgoingHttpHeaders } from 'node:http2'

/** APNs requires HTTP/2. A bounded one-request session avoids dangling streams
 * on timeout/GOAWAY; pooling and durable retries are separate concerns. */
export function postApns(origin: string, token: string, headers: OutgoingHttpHeaders, payload: unknown,
  timeoutMs = 8000): Promise<{ status: number; body: unknown }> {
  return new Promise(resolve => {
    let finished = false
    let stream: ClientHttp2Stream | undefined
    let status = 0
    let bytes = 0
    const chunks: Buffer[] = []
    const session = connect(origin)
    const timer = setTimeout(() => finish(0, null), timeoutMs)
    function finish(code: number, body: unknown) {
      if (finished) return
      finished = true
      clearTimeout(timer)
      stream?.close()
      session.destroy()
      resolve({ status: code, body })
    }
    session.on('error', () => finish(0, null))
    session.on('close', () => finish(0, null))
    try {
      stream = session.request({ ...headers, ':method': 'POST', ':path': `/3/device/${encodeURIComponent(token)}` })
      stream.on('error', () => finish(0, null))
      stream.on('aborted', () => finish(0, null))
      stream.on('close', () => finish(0, null))
      stream.on('response', response => { status = Number(response[':status']) || 0 })
      stream.on('data', (chunk: Buffer) => {
        if (finished) return
        bytes += chunk.length
        if (bytes > 16 * 1024) { finish(0, null); return }
        chunks.push(Buffer.from(chunk))
      })
      stream.on('end', () => {
        let body: unknown = null
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { /* empty success or invalid error */ }
        finish(status, body)
      })
      stream.end(JSON.stringify(payload))
    } catch { finish(0, null) }
  })
}

export function apnsOrigin(environment: string | undefined): string | null {
  if (environment === undefined || environment === '' || environment === 'production') return 'https://api.push.apple.com'
  if (environment === 'sandbox') return 'https://api.sandbox.push.apple.com'
  return null // a typo must not silently send development tokens to production
}
