// /api/uploads - accept a base64 image/audio/document data URL,
// store to disk (files served via GET /api/uploads/[file])
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile, rename, unlink } from 'node:fs/promises'
import path from 'node:path'
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { DOC_MAX_BYTES, UPLOADS_DIR, UPLOAD_MIME, strField } from '@/lib/serializers'

import { readUploadBody, decodeUploadBase64, UploadInputError } from '@/lib/upload-input'

export const dynamic = 'force-dynamic'

/** ~4.5 MB data-URL ceiling (client compresses to ≤1280px JPEG first). */
const MAX_DATA_URL_LENGTH = 4_500_000

/**
 * R40 - documents ride base64 too: 10 MB hard cap on DECODED bytes.
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
let activeUploads = 0
const privateHeaders = { 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' }

export async function POST(req: Request) {
  // Bound concurrent JSON/base64/blob allocations in this server process.
  if (activeUploads >= 2) return NextResponse.json({ error: 'Uploads are busy. Retry shortly.' },
    { status: 429, headers: { ...privateHeaders, 'Retry-After': '2' } })
  activeUploads++
  try {
    const response = await upload(req)
    for (const [name, value] of Object.entries(privateHeaders)) response.headers.set(name, value)
    return response
  } catch (error) {
    if (error instanceof UploadInputError) return NextResponse.json({ error: error.message }, { status: error.status, headers: privateHeaders })
    console.error('[uploads] Upload processing failed')
    return NextResponse.json({ error: 'Upload was not saved. Please retry.' }, { status: 503, headers: privateHeaders })
  } finally { activeUploads-- }
}

async function upload(req: Request) {
  const body = await readUploadBody(req)
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
      { error: 'Document is too large - the limit is 10 MB.' },
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
  const buffer = decodeUploadBase64(match[2])
  if (!buffer) return NextResponse.json({ error: 'Malformed or empty base64 payload.' }, { status: 400 })
  if (doc && buffer.length > DOC_MAX_BYTES) {
    return NextResponse.json(
      { error: 'Document is too large - the limit is 10 MB.' },
      { status: 413 },
    )
  }

  const filePath = `${randomUUID()}.${ext}`
  // The durable store is authoritative. Never acknowledge an ephemeral-only
  // upload: both native outboxes would discard their upload bytes on that ack.
  try {
    const storedBytes = new Uint8Array(buffer)
    await db.uploadedFile.upsert({
      where: { name: filePath },
      create: { name: filePath, mime, bytes: storedBytes, size: buffer.length },
      update: { mime, bytes: storedBytes, size: buffer.length },
    })
  } catch {
    console.error('[uploads] Durable upload storage unavailable')
    return NextResponse.json({ error: 'Upload storage is temporarily unavailable. Your upload was not confirmed.' },
      { status: 503, headers: { 'Retry-After': '3' } })
  }

  // Disk is only a cache. Publish it atomically so a concurrent GET cannot see
  // partial bytes; a full/read-only cache must not invalidate the durable ack.
  const temporary = path.join(UPLOADS_DIR, `${filePath}.tmp-${randomUUID()}`)
  try {
    await mkdir(UPLOADS_DIR, { recursive: true })
    await writeFile(temporary, buffer)
    await rename(temporary, path.join(UPLOADS_DIR, filePath))
  } catch {
    await unlink(temporary).catch(() => {})
    console.error('[uploads] Disk cache unavailable; durable bytes retained')
  }

  return NextResponse.json({ filePath, imagePath: filePath }, { status: 201 })
}
