import { mkdir, writeFile, rename, unlink, stat } from 'node:fs/promises'
import path from 'node:path'
import type { PrismaClient } from '../../prisma/generated-client'

/** Unreferenced uploads survive this long before sweeping, matching the
 * native apps' local attachment-prune grace (24h): an in-flight native
 * outbox entry can still reference its server file minutes-to-hours after
 * upload, so a shorter grace would pull bytes out from under it. */
export const UPLOAD_SWEEP_GRACE_MS = 24 * 60 * 60 * 1000

export interface UploadSweepReport {
  scanned: number
  removed: number
  republished: number
  retained: number
}

/** An upload is referenced while ANY durable row still points at it:
 * messages (image/audio/file), profile avatars/covers, conversation photos,
 * status stories. Reference strings arrive as bare names (messages, stories)
 * or as full /api/uploads/<name> paths (avatars, covers, conversation
 * photos) - only the trailing filename matters. */
async function referencedUploadNames(db: PrismaClient): Promise<Set<string>> {
  const names = new Set<string>()
  const add = (value: unknown) => {
    if (typeof value !== 'string' || value.length === 0) return
    const match = /([A-Za-z0-9-]+\.[A-Za-z0-9]{1,8})$/.exec(value)
    if (match) names.add(match[1])
  }
  for (const row of await db.message.findMany({ select: { imagePath: true, audioPath: true, filePath: true } })) {
    add(row.imagePath); add(row.audioPath); add(row.filePath)
  }
  for (const row of await db.user.findMany({ select: { avatar: true, coverImage: true } })) {
    add(row.avatar); add(row.coverImage)
  }
  for (const row of await db.conversation.findMany({ select: { photo: true } })) add(row.photo)
  for (const row of await db.statusStory.findMany({ select: { imagePath: true } })) add(row.imagePath)
  return names
}

/**
 * R42 orphan reconciliation, run from the CRON_SECRET-gated maintenance
 * endpoint:
 *   • referenced rows whose disk cache went missing are REPUBLISHED from the
 *     durable BLOB (self-heal after the sandbox wipes uploads/), and
 *   • unreferenced rows past the grace window are deleted from the durable
 *     store AND the disk cache - these are the leftovers of lost upload
 *     acks, discarded drafts and expired stories.
 * Referenced rows are never deleted; fresh unreferenced rows keep their
 * grace window so a slow message send after upload is not punished.
 */
export async function sweepUploads(
  db: PrismaClient,
  uploadsDir: string,
  now: number = Date.now(),
): Promise<UploadSweepReport> {
  const referenced = await referencedUploadNames(db)
  const rows = await db.uploadedFile.findMany({ select: { name: true, bytes: true, createdAt: true } })
  const report: UploadSweepReport = { scanned: rows.length, removed: 0, republished: 0, retained: 0 }
  for (const row of rows) {
    const diskPath = path.join(uploadsDir, row.name)
    if (referenced.has(row.name)) {
      report.retained += 1
      let present = true
      try { await stat(diskPath) } catch { present = false }
      if (!present) {
        // Republish the disk cache from the durable BLOB (same atomic
        // tmp+rename contract as the upload route). A cache write failure is
        // non-fatal: the BLOB stays authoritative.
        try {
          await mkdir(uploadsDir, { recursive: true })
          const temporary = `${diskPath}.sweep-${now}`
          await writeFile(temporary, row.bytes)
          await rename(temporary, diskPath)
          report.republished += 1
        } catch {
          await unlink(`${diskPath}.sweep-${now}`).catch(() => {})
        }
      }
      continue
    }
    if (now - row.createdAt.getTime() < UPLOAD_SWEEP_GRACE_MS) {
      report.retained += 1
      continue
    }
    await db.uploadedFile.delete({ where: { name: row.name } }).catch(() => {})
    await unlink(diskPath).catch(() => {})
    report.removed += 1
  }
  return report
}
