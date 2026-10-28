# Section 4 — Attachments, Recording, Import, and Sharing (R42)

Authoritative checklist mapping for the twelve section-4 items, with the
implementation, verification and honest limits per item. The scope follows
the standing rule: implementation and regression verification here;
deployment and physical-device acceptance remain deferred (the four VERIFY
items at the bottom).

## Implemented items

### 1. Selection-consumption receipts — DONE
Every attachment selection's consumption is a durable, atomic transaction:
- Android (Room v20): `attachment_consumption` rows are written in the SAME
  transaction that consumes a selection — `queueAttachmentDraft` /
  `queuePreparedAttachment` write `outcome='queued'`, `discardAttachmentDraft`
  writes `outcome='discarded'`; the queued receipt retires with the upload
  acknowledgement (`attemptOutboxSend` → `finishUpload` tx) and with user
  discards (`discardOutboxEntry*`/`clearOutbox` drop the receipt and any
  still-local bytes). `AttachmentDraftDao.references()` includes queued
  receipts so consumed files survive prune while in flight.
- iOS (GRDB v16): the `stagedAttachment` row and its `attachmentReceipt`
  successor swap inside the `enqueueMessage` transaction
  (`consumingStaged:`), `discardStagedAttachment` writes the discard receipt,
  and `resolveDelivery`/`deleteOutbox` retire the queued receipt.

### 2. Unsent mounted-sheet restoration — DONE
A process death between picking and sending restores the selection:
- Android: the Mirror caption sheet now stages through the account journal
  (`saveAttachmentDraft` on prepare; restore via `loadAttachmentDraft` at
  mount; caption/view-once edits persist debounced through
  `editAttachmentDraft`), and Send consumes through `queueAttachmentDraft`
  (the atomic journal-to-outbox swap). feature-chat already restored
  (`ChatRoomViewModel.restoreAttachmentDraft`); the Mirror now rides the
  same journal.
- iOS: `stagedAttachment` persists the prepared selection at stage time
  (both the room bar and recovered recordings); `rehydrateStagedAttachment`
  restores it (with caption, view-once, reply target) at room init.

### 3. External-share pre-upload gap — DONE
- Android: shared content is prepared EAGERLY at arrival while the URI grant
  is fresh (`MainActivity.handleIntent` runs the image/document preparation
  immediately) and lands in the durable `filesDir/share-in` inbox
  (`ShareInInbox`); the picker sheet works from inbox bytes only. Document
  shares (pdf/zip/csv) are now accepted (manifest + `ShareInActivity`).
  Only a durable queue success or the explicit "Remove this share" consumes
  an entry; "Later" keeps it for the next launch.

### 4. Full iOS system share extension — DONE
New `PulseShareExtension` target (project.yml, `app.pulse.chat.ShareExtension`,
`com.apple.share-services`, images ≤10 + text): `ShareViewController` reads
the shared item, applies the same ≤1280px JPEG q0.82 preparation, and writes
one JSON entry into the App Group container `group.app.pulse.share/share-in`
(atomic tmp+rename). The main app (`ShareInInbox` + `ShareInSheet` in
RootView) scans on launch and every foreground, delivers through the same
durable outbox as in-app sends (text via `PulseTextDelivery`, images via
`PulseAttachmentDelivery`), and consumes entries exactly like Android.
Unsigned simulator builds skip entitlement validation; device provisioning
stays deferred with the device-acceptance work.

### 5. Upload lost-ACK deduplication — DONE
- Server: `POST /api/uploads` accepts an optional stable `clientKey`;
  `UploadOperation` receipts (`src/lib/upload-operation.ts`, prisma patch
  `20261028_upload_operation.sql`) replay the SAME stored filename on a
  lost-ack retry, refuse a key reused for different bytes (409), refuse
  before storing anything on a malformed key (400), and stay retryable (503
  + Retry-After) when the receipt upgrade is missing. Duplicate-key commit
  races resolve to the winner without a second file.
- Both clients send the outbox `clientId` as the upload `clientKey`
  (Android `PulseApi.uploadMedia(dataUrl, clientKey)` from
  `attemptOutboxSend`; iOS `PulseAPIClient.uploadMedia(dataUrl:clientKey:)`
  from the outbox engine flush).

### 6. Orphaned uploads — DONE
- Server: `/api/maintenance/upload-sweep` (CRON_SECRET-gated, hourly from
  the socket service ticker) deletes unreferenced uploads past a 24h grace
  (rows older than the grace that no Message image/audio/file path, User
  avatar/cover, Conversation photo or StatusStory image references — both
  the durable row and the disk cache) and republishes missing disk caches
  for referenced rows from the durable BLOB.

### 7. Interrupted recordings — DONE
- Both recorders journal each take BEFORE it starts (Android Room v20
  `recording_journal`; iOS GRDB v16 `recordingJournal`), mark graceful stops,
  and retire the row only after durable staging; user cancels write durable
  discard receipts. At account start the sweep reconstructs: a gracefully
  stopped take (or one whose container still parses) stages back into its
  room as a ready-to-send voice selection; a hard-killed take whose
  container was never finalized (the recorder writes the container index
  only at stop()) is dropped honestly — never silent disk garbage. The
  Android Mirror voice path now rides the durable outbox
  (`queuePreparedAttachment`) instead of an in-memory upload memo, and its
  bitrate dropped to 32 kbps mono so a full ten-minute take stays under the
  voice ceiling (96 kbps overflowed at ~4.2 minutes).

### 8. Upload policy parity — DONE
- Server: `imagePath`/`audioPath` existence now accepts the durable store
  OR the disk cache (same contract `filePath` and GET already had) — a wiped
  disk cache no longer breaks image/audio sends; images/audio gained the
  explicit decoded ceiling (3,375,000 bytes) the 4.5M encoded cap implies.
- Android: `AttachmentValidation` enforces the same decoded media ceiling;
  the Mirror document picker narrows to `DOCUMENT_MIME_ARRAY` (was `*/*`);
  captions enforce the 2000-char limit (was 500 in feature-chat).
- iOS: `PulseMediaSupport.maxCaptionLength` 500 → 2000 (enforced in the
  staged caption field); `PulseAttachmentDelivery` enforces the decoded
  media ceiling.

## Deferred (VERIFY — physical-device acceptance, per the standing rule)

1. Real document/photo providers: cloud libraries, revoked URI permissions,
   changing provider content, provider failures — need device coverage.
2. Blocked OS reads: cancellation below the bounded reader cannot be assumed
   to interrupt a provider/disk operation — needs device coverage.
3. Peak memory: older-device memory use, large camera allocations,
   simultaneous image preparation — needs measurement.
4. Actual media playback: recording finalization, codec/container
   compatibility, short/corrupt files, peer playback — needs device
   acceptance.

## Verification record

- Server: 549/549 `node --test tests/server/*.test.mjs` (13 new upload
  receipt/sweep/parity tests) + live endpoint smoke (replay returns the same
  file; same-key different bytes returns 409).
- Native contracts: 21/21 python contracts pass; Android Room schema pin
  updated to v20 (`native-thread-window-sql.test.mjs`).
- iOS: brace gate balanced on all 14 changed Swift files; new
  `StagedAttachmentAndRecordingJournalTests` added to the CI selector list
  (`scripts/ci/ios-composer-results.py`).
- Android/iOS builds + device/instrumented suites run in CI on push
  (Maven Central rate-limited this sandbox's IP, blocking local Gradle
  resolution; local JVM runs were network-blocked, not code-blocked).

## Honest limits

- Durability begins at journal/receipt commit; a process death before the
  first durable write still loses the un-journaled remainder (the share
  intent itself, a take whose journal row write lost the race with the
  kill). Hard-killed recordings whose container index was never written are
  unrecoverable by construction; they are swept honestly.
- Uploads remain unauthenticated (pre-existing, unchanged); the clientKey
  receipt is integrity-by-content-hash, not identity.
- The iOS share extension's App Group entitlement needs real provisioning
  on devices; simulator builds run unsigned as before.
