// ─────────────────────────────────────────────────────────────
// Pulse — shared client upload chain (R33-b).
// Mirrors the profile-photo flow (components/profile/avatar-editor.tsx):
//   file → canvas center-crop square + ≤max-edge downscale →
//   JPEG(0.85) data URL → POST /api/uploads →
//   returns the stored "/api/uploads/<file>" path ready to attach to a
//   conversation PATCH / channel POST. No mocks — the real upload API.
// ─────────────────────────────────────────────────────────────

const DEFAULT_MAX_EDGE = 512
const JPEG_QUALITY = 0.85

/**
 * Center-crop the picked image to a square, downscale so the edge is
 * ≤maxEdge px (never upscale), and re-encode as a JPEG data URL.
 * PNG transparency is flattened onto white (JPEG has no alpha).
 */
export async function fileToSquareDataUrl(file: File, maxEdge = DEFAULT_MAX_EDGE): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('That file is not an image')
  }
  const bitmapUrl = URL.createObjectURL(file)
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image()
      el.onload = () => resolve(el)
      el.onerror = () => reject(new Error('Could not read that image'))
      el.src = bitmapUrl
    })
    const side = Math.min(img.width, img.height)
    if (side < 1) throw new Error('That image is empty')
    const out = Math.min(side, maxEdge)
    const canvas = document.createElement('canvas')
    canvas.width = out
    canvas.height = out
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('Canvas is unavailable here')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, out, out)
    ctx.drawImage(
      img,
      (img.width - side) / 2,
      (img.height - side) / 2,
      side,
      side,
      0,
      0,
      out,
      out,
    )
    return canvas.toDataURL('image/jpeg', JPEG_QUALITY)
  } finally {
    URL.revokeObjectURL(bitmapUrl)
  }
}

/**
 * Full upload chain: optimize the picked file, POST /api/uploads, and
 * resolve the stored path ("/api/uploads/<file>") that conversation
 * PATCHes and channel POSTs accept as `photo`.
 */
export async function uploadConversationPhoto(file: File): Promise<string> {
  const dataUrl = await fileToSquareDataUrl(file)
  return uploadDataUrlPhoto(dataUrl)
}

/** POST an already-optimized data URL to /api/uploads → "/api/uploads/<file>". */
export async function uploadDataUrlPhoto(dataUrl: string): Promise<string> {
  const up = await fetch('/api/uploads', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dataUrl }),
  })
  if (!up.ok) {
    const body: unknown = await up.json().catch(() => null)
    const message =
      body && typeof body === 'object' && 'error' in body && typeof (body as { error?: unknown }).error === 'string'
        ? (body as { error: string }).error
        : `Upload failed (${up.status})`
    throw new Error(message)
  }
  const res = (await up.json()) as { filePath?: string; imagePath?: string }
  const filePath = res.filePath ?? res.imagePath
  if (!filePath) throw new Error('Upload returned no file path')
  return `/api/uploads/${filePath}`
}
