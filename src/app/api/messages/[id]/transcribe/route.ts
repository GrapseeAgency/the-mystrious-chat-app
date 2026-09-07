// ─────────────────────────────────────────────────────────────
// /api/messages/[id]/transcribe — real voice-note transcription (ASR)
// ─────────────────────────────────────────────────────────────
// R43 — "Voice notes you can read." Any conversation participant can run a
// voice note through the real speech-recognition service; the transcript is
// CACHED on the message row (transcript + transcribedAt + transcribedById) so
// every member sees it without paying for the call again. Bytes are read with
// the R41 durability contract: disk first, UploadedFile store on ENOENT.
import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import ZAI from 'z-ai-web-dev-sdk'
import { db } from '@/lib/db'
import { UPLOADS_DIR, safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'
export const maxDuration = 60

interface RouteCtx {
  params: Promise<{ id: string }>
}

/**
 * Read stored attachment bytes through the durability fallback.
 * Mirrors GET /api/uploads/[file]: disk fast path, SQLite store on ENOENT.
 */
async function readStoredBytes(name: string): Promise<Buffer | null> {
  try {
    return await fs.readFile(path.join(UPLOADS_DIR, name))
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    const row = await db.uploadedFile.findUnique({ where: { name }, select: { bytes: true } })
    return row ? Buffer.from(row.bytes) : null
  }
}

/**
 * POST /api/messages/[id]/transcribe  body { requesterId }
 * Voice notes only (kind 'audio' with audioPath, not deleted). Participant-only.
 * Cached: a second call returns the stored transcript without re-running ASR.
 * → { transcript, transcribedAt, cached }
 */
export async function POST(req: Request, { params }: RouteCtx) {
  const { id } = await params

  const body = await safeJson(req)
  const requesterId = strField(body.requesterId)
  if (!requesterId) {
    return NextResponse.json({ error: 'requesterId is required.' }, { status: 400 })
  }

  const message = await db.message.findUnique({
    where: { id },
    select: {
      conversationId: true,
      deletedAt: true,
      kind: true,
      audioPath: true,
      transcript: true,
      transcribedAt: true,
    },
  })
  if (!message) {
    return NextResponse.json({ error: 'Message not found.' }, { status: 404 })
  }
  if (message.deletedAt) {
    return NextResponse.json({ error: 'Deleted messages cannot be transcribed.' }, { status: 400 })
  }
  if (message.kind !== 'audio' || !message.audioPath) {
    return NextResponse.json(
      { error: 'Only voice notes can be transcribed.' },
      { status: 400 },
    )
  }

  const participant = await db.conversationParticipant.findUnique({
    where: {
      userId_conversationId: { userId: requesterId, conversationId: message.conversationId },
    },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  // Cache hit — the transcript is already on the row, never re-bill the ASR call.
  if (message.transcript !== null && message.transcribedAt !== null) {
    return NextResponse.json({
      transcript: message.transcript,
      transcribedAt: message.transcribedAt.toISOString(),
      cached: true,
    })
  }

  let bytes: Buffer | null
  try {
    bytes = await readStoredBytes(message.audioPath)
  } catch {
    bytes = null
  }
  if (!bytes) {
    return NextResponse.json(
      { error: 'The audio file for this voice note is missing.' },
      { status: 404 },
    )
  }

  try {
    const zai = await ZAI.create()
    const result = await zai.audio.asr.create({
      file_base64: bytes.toString('base64'),
    })
    const text = (result?.text ?? '').trim()
    if (text.length === 0) {
      return NextResponse.json(
        { error: 'The transcription service returned no text for this audio.' },
        { status: 422 },
      )
    }

    const transcribedAt = new Date()
    await db.message.update({
      where: { id },
      data: { transcript: text, transcribedAt, transcribedById: requesterId },
    })
    return NextResponse.json({
      transcript: text,
      transcribedAt: transcribedAt.toISOString(),
      cached: false,
    })
  } catch (error) {
    console.error(
      '[transcribe] ASR failed for',
      id,
      error instanceof Error ? error.message : error,
    )
    return NextResponse.json(
      { error: 'Transcription failed — the speech service could not process this audio.' },
      { status: 502 },
    )
  }
}
