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
 * dataUrl: "data:image/jpeg;base64,...."
 * → 201 { imagePath: "<uuid>.<ext>" }
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const dataUrl = strField(body.dataUrl)

  const match = /^data:(image\/(?:jpeg|png|webp));base64,([A-Za-z0-9+/=]+)$/.exec(dataUrl)
  if (!match) {
    return NextResponse.json(
      { error: 'dataUrl must be a base64 image data URL (jpeg/png/webp).' },
      { status: 400 },
    )
  }
  if (dataUrl.length > MAX_DATA_URL_LENGTH) {
    return NextResponse.json({ error: 'Image is too large (max ~4.5 MB).' }, { status: 413 })
  }

  const mime = match[1]
  const ext = (Object.keys(UPLOAD_MIME) as string[]).find((k) => UPLOAD_MIME[k] === mime) ?? 'jpg'
  let buffer: Buffer
  try {
    buffer = Buffer.from(match[2], 'base64')
  } catch {
    return NextResponse.json({ error: 'Malformed base64 payload.' }, { status: 400 })
  }
  if (buffer.length === 0) {
    return NextResponse.json({ error: 'Empty image payload.' }, { status: 400 })
  }

  await mkdir(UPLOADS_DIR, { recursive: true })
  const imagePath = `${randomUUID()}.${ext}`
  await writeFile(path.join(UPLOADS_DIR, imagePath), buffer)

  return NextResponse.json({ imagePath }, { status: 201 })
}
