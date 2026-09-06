// ─────────────────────────────────────────────────────────────
// /api/uploads — accept a base64 image/audio/document data URL,
// store to disk (files served via GET /api/uploads/[file])
// ─────────────────────────────────────────────────────────────
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { DOC_MAX_BYTES, UPLOADS_DIR, UPLOAD_MIME, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

/** ~4.5 MB data-URL ceiling (client compresses to ≤1280px JPEG first). */
const MAX_DATA_URL_LENGTH = 4_500_000

/**
 * R40 — documents ride base64 too: 10 MB hard cap on DECODED bytes.
 * Base64 inflates by 4/3 (+ the small data-URL prefix), so the encoded
 * ceiling is derived, never hand-waved.
 */
const MAX_DOC_DATA_URL_LENGTH = Math.ceil(DOC_MAX_BYTES / 3) * 4 + 128

const DATA_URL_REGEX =
  /^data:(image\/(?:jpeg|png|webp)|audio\/(?:webm|mpeg|ogg|wav|mp4|aac)|application\/(?:pdf|zip)|text\/(?:plain|csv));base64,([A-Za-z0-9+/=]+)$/

/** Mime families that carry the R40 10 MB document cap (vs the media cap). */
function isDocMime(mime: string): boolean {
  return mime === 'application/pdf' || mime === 'application/zip' || mime === 'text/plain' || mime === 'text/csv'
}

/**
 * POST /api/uploads  body { dataUrl }
 * dataUrl: "data:image/jpeg;base64,...." | "data:audio/webm;base64,...."
 *        | "data:application/pdf;base64,...." (R40; also zip / plain / csv)
 * → 201 { filePath: "<uuid>.<ext>", imagePath: same (legacy alias for image flow) }
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const dataUrl = strField(body.dataUrl)

  const match = DATA_URL_REGEX.exec(dataUrl)
  if (!match) {
    return NextResponse.json(
      {
        error:
          'dataUrl must be a base64 image (jpeg/png/webp), audio (webm/mpeg/ogg/wav/mp4/aac) or document (pdf/txt/csv/zip) data URL.',
      },
      { status: 400 },
    )
  }

  const mime = match[1]
  const doc = isDocMime(mime)

  // Size gates: documents get their own honest 10 MB ceiling; images/audio
  // keep the existing media ceiling. Checked on the ENCODED length first
  // (cheap reject before base64 decode) and re-checked on decoded bytes.
  if (doc && dataUrl.length > MAX_DOC_DATA_URL_LENGTH) {
    return NextResponse.json(
      { error: 'Document is too large — the limit is 10 MB.' },
      { status: 413 },
    )
  }
  if (!doc && dataUrl.length > MAX_DATA_URL_LENGTH) {
    return NextResponse.json({ error: 'Attachment is too large (max ~4.5 MB).' }, { status: 413 })
  }

  const ext =
    mime === 'audio/mp4'
      ? 'm4a'
      : mime === 'audio/mpeg'
        ? 'mp3'
        : mime === 'text/plain'
          ? 'txt'
          : mime === 'text/csv'
            ? 'csv'
            : mime === 'application/pdf'
              ? 'pdf'
              : mime === 'application/zip'
                ? 'zip'
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
  if (doc && buffer.length > DOC_MAX_BYTES) {
    return NextResponse.json(
      { error: 'Document is too large — the limit is 10 MB.' },
      { status: 413 },
    )
  }

  await mkdir(UPLOADS_DIR, { recursive: true })
  const filePath = `${randomUUID()}.${ext}`
  await writeFile(path.join(UPLOADS_DIR, filePath), buffer)

  // R41 — durable store: the sandbox has twice wiped files from disk while
  // DB rows persisted (avatars in R37, demo PDFs in R41). Bytes now live in
  // SQLite as the source of truth; the disk copy is a fast-path cache. A
  // store failure must never fail the upload itself.
  try {
    const storedBytes = new Uint8Array(buffer)
    await db.uploadedFile.upsert({
      where: { name: filePath },
      create: { name: filePath, mime, bytes: storedBytes, size: buffer.length },
      update: { mime, bytes: storedBytes, size: buffer.length },
    })
  } catch (storeError) {
    console.error('[uploads] durable store write failed:', storeError)
  }

  return NextResponse.json({ filePath, imagePath: filePath }, { status: 201 })
}
