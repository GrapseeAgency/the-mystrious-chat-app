// /api/uploads/[file] - serve stored message images
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { NextResponse } from 'next/server'
import { db } from '@/lib/db'
import { UPLOADS_DIR, UPLOAD_MIME } from '@/lib/serializers'
import { mediaAccessDecision } from '@/lib/media-access'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ file: string }>
}

/**
 * GET /api/uploads/<uuid>.<ext> → stored attachment bytes (no shared/public cache).
 * Strict filename whitelist blocks any path traversal.
 * R40: the whitelist also carries the document extensions (pdf/txt/csv/zip);
 * their Content-Type comes from UPLOAD_MIME (e.g. application/pdf) so an
 * <a download> anchor resolves the file natively.
 */
export async function GET(req: Request, { params }: RouteCtx) {
  const { file } = await params

  if (!/^[A-Za-z0-9-]+\.(jpg|jpeg|png|webp|webm|mp3|ogg|wav|m4a|aac|pdf|txt|csv|zip)$/.test(file)) {
    return NextResponse.json({ error: 'Not found.' }, { status: 404, headers: { 'Cache-Control': 'private, no-store' } })
  }

  // R44 section-5: an authenticated fetch re-proves live access on every
  // request. A previously reachable URL stops serving once the referencing
  // message is tombstoned/expired, the actor left the room, or the session
  // died. Headerless requests keep the documented migration window.
  const access = await mediaAccessDecision(req, file)
  if (access.kind === 'denied') {
    return NextResponse.json({ error: access.reason }, { status: access.status, headers: { 'Cache-Control': 'private, no-store' } })
  }

  const ext = file.split('.').pop() as string
  const mime = UPLOAD_MIME[ext] ?? 'image/jpeg'

  try {
    const bytes = await readFile(path.join(UPLOADS_DIR, path.basename(file)))
    return new NextResponse(new Uint8Array(bytes), {
      headers: {
        'Content-Type': mime,
        'Cache-Control': 'private, no-store',
        'X-Content-Type-Options': 'nosniff',
        'Content-Length': String(bytes.length),
      },
    })
  } catch {
    // R41 - durable fallback: the sandbox wipes disk files while DB rows
    // persist. Bytes uploaded since the UploadedFile store landed live in
    // SQLite and are served from there when the disk copy is gone.
    try {
      const row = await db.uploadedFile.findUnique({ where: { name: file } })
      if (row) {
        return new NextResponse(new Uint8Array(row.bytes), {
          headers: {
            'Content-Type': row.mime || mime,
            'Cache-Control': 'private, no-store',
            'X-Content-Type-Options': 'nosniff',
            'Content-Length': String(row.bytes.byteLength),
          },
        })
      }
    } catch {
      console.error('[uploads] Durable upload storage unavailable')
      return NextResponse.json({ error: 'Attachment storage is temporarily unavailable.' },
        { status: 503, headers: { 'Cache-Control': 'private, no-store', 'Retry-After': '3' } })
    }
    return NextResponse.json({ error: 'Not found.' }, { status: 404, headers: { 'Cache-Control': 'private, no-store' } })
  }
}
