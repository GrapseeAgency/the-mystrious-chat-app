// ─────────────────────────────────────────────────────────────
// /api/uploads/[file] — serve stored message images
// ─────────────────────────────────────────────────────────────
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { NextResponse } from 'next/server'
import { UPLOADS_DIR, UPLOAD_MIME } from '@/lib/serializers'

export const dynamic = 'force-dynamic'

interface RouteCtx {
  params: Promise<{ file: string }>
}

/**
 * GET /api/uploads/<uuid>.<ext> → stored attachment bytes (immutable cache).
 * Strict filename whitelist blocks any path traversal.
 * R40: the whitelist also carries the document extensions (pdf/txt/csv/zip);
 * their Content-Type comes from UPLOAD_MIME (e.g. application/pdf) so an
 * <a download> anchor resolves the file natively.
 */
export async function GET(_req: Request, { params }: RouteCtx) {
  const { file } = await params

  if (!/^[A-Za-z0-9-]+\.(jpg|jpeg|png|webp|webm|mp3|ogg|wav|m4a|aac|pdf|txt|csv|zip)$/.test(file)) {
    return NextResponse.json({ error: 'Not found.' }, { status: 404 })
  }
  const ext = file.split('.').pop() as string
  const mime = UPLOAD_MIME[ext] ?? 'image/jpeg'

  try {
    const bytes = await readFile(path.join(UPLOADS_DIR, path.basename(file)))
    return new NextResponse(new Uint8Array(bytes), {
      headers: {
        'Content-Type': mime,
        'Cache-Control': 'public, max-age=31536000, immutable',
        'Content-Length': String(bytes.length),
      },
    })
  } catch {
    return NextResponse.json({ error: 'Not found.' }, { status: 404 })
  }
}
