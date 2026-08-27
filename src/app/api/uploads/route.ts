// ─────────────────────────────────────────────────────────────
// /api/uploads — accept a base64 image data URL, store to disk
// ─────────────────────────────────────────────────────────────
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { NextResponse } from 'next/server'
import { UPLOADS_DIR, UPLOAD_MIME, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** ~4.5 MB data-URL ceiling (client compresses to ≤1280px JPEG first). */
const MAX_DATA_URL_LENGTH = 4_500_000

/**
 * POST /api/uploads  body { dataUrl }
 * dataUrl: "data:image/jpeg;base64,...." | "data:audio/webm;base64,...."
 * → 201 { filePath: "<uuid>.<ext>", imagePath: same (legacy alias for image flow) }
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const dataUrl = strField(body.dataUrl)

  const match = /^data:(image\/(?:jpeg|png|webp)|audio\/(?:webm|mpeg|ogg|wav|mp4|aac));base64,([A-Za-z0-9+/=]+)$/.exec(
    dataUrl,
  )
  if (!match) {
    return NextResponse.json(
      { error: 'dataUrl must be a base64 image (jpeg/png/webp) or audio (webm/mpeg/ogg/wav/mp4/aac) data URL.' },
      { status: 400 },
    )
  }
  if (dataUrl.length > MAX_DATA_URL_LENGTH) {
    return NextResponse.json({ error: 'Attachment is too large (max ~4.5 MB).' }, { status: 413 })
  }

  const mime = match[1]
  const ext =
    mime === 'audio/mp4'
      ? 'm4a'
      : mime === 'audio/mpeg'
        ? 'mp3'
        : ((Object.keys(UPLOAD_MIME) as string[]).find((k) => UPLOAD_MIME[k] === mime) ?? 'jpg')
  let buffer: Buffer
  try {
    buffer = Buffer.from(match[2], 'base64')
  } catch {
    return NextResponse.json({ error: 'Malformed base64 payload.' }, { status: 400 })
  }
  if (buffer.length === 0) {
    return NextResponse.json({ error: 'Empty attachment payload.' }, { status: 400 })
  }

  await mkdir(UPLOADS_DIR, { recursive: true })
  const filePath = `${randomUUID()}.${ext}`
  await writeFile(path.join(UPLOADS_DIR, filePath), buffer)

  return NextResponse.json({ filePath, imagePath: filePath }, { status: 201 })
}
