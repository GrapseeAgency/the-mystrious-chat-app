// ─────────────────────────────────────────────────────────────
// /api/voice/transcribe — R48 live voice-room captions (ASR)
// ─────────────────────────────────────────────────────────────
// A speaker's client wraps ~4 s of its OWN downsampled mic PCM
// (16 kHz mono Int16) in a WAV container and posts it here while
// holding push-to-talk with captions enabled. The server runs the
// real speech-recognition service and returns the text; the client
// then relays it to the voice room as an ephemeral `voice:transcript`
// socket event. Nothing is stored on a message or on disk — captions
// are live-only, matching the voice room's "never recorded" contract.
import { NextResponse } from 'next/server'
import ZAI from 'z-ai-web-dev-sdk'
import { db } from '@/lib/db'
import { safeJson, strField } from '@/lib/serializers'

export const dynamic = 'force-dynamic'
export const maxDuration = 60

/** 4 s @16 kHz mono Int16 → 128 KB raw → ~171 KB base64. Allow a little headroom. */
const MAX_AUDIO_B64_CHARS = 512 * 1024

/**
 * POST /api/voice/transcribe  body { conversationId, requesterId, audioBase64 }
 * Participant-only. → { transcript }
 */
export async function POST(req: Request) {
  const body = await safeJson(req)
  const conversationId = strField(body.conversationId)
  const requesterId = strField(body.requesterId)
  const audio = typeof body.audioBase64 === 'string' ? body.audioBase64 : ''
  if (!conversationId || !requesterId) {
    return NextResponse.json(
      { error: 'conversationId and requesterId are required.' },
      { status: 400 },
    )
  }
  if (audio.length === 0) {
    return NextResponse.json({ error: 'audioBase64 is required.' }, { status: 400 })
  }
  if (audio.length > MAX_AUDIO_B64_CHARS) {
    return NextResponse.json({ error: 'Audio window is too large.' }, { status: 413 })
  }

  const participant = await db.conversationParticipant.findUnique({
    where: {
      userId_conversationId: { userId: requesterId, conversationId },
    },
    select: { id: true },
  })
  if (!participant) {
    return NextResponse.json(
      { error: 'You are not a participant of this conversation.' },
      { status: 403 },
    )
  }

  try {
    const zai = await ZAI.create()
    const result = await zai.audio.asr.create({ file_base64: audio })
    const text = (result?.text ?? '').trim()
    if (text.length === 0) {
      return NextResponse.json(
        { error: 'The transcription service returned no text for this audio.' },
        { status: 422 },
      )
    }
    return NextResponse.json({ transcript: text.slice(0, 280) })
  } catch (error) {
    console.error(
      '[voice-transcribe] ASR failed for',
      conversationId,
      error instanceof Error ? error.message : error,
    )
    return NextResponse.json(
      { error: 'Transcription failed — the speech service could not process this audio.' },
      { status: 502 },
    )
  }
}
