# Native draft and voice lifecycle follow-up

## iOS composer

- Each room captures its original store in a finite asynchronous journal. A synchronous main-actor front buffer lets a rapid reopen recover text even before SQLite commits. Local writes start on every observed draft change; only the best-effort server mirror waits 600 ms.
- A shared writer orders writes by storage path, room, journal-owner birth and monotonic revision. An old room's delayed write cannot overwrite a newer room's submitted snapshot. A clear cannot be resurrected by an older queued save.
- Empty local snapshots are explicit clears, distinct from never having a local draft. Room hydration and chat-list previews therefore suppress stale server text after a local clear. Empty snapshots do not appear in the drafts manager.
- Disappearance flushes the current local value and cancels the server debounce. The captured network client/viewer and session generation prevent a delayed debounce from switching owners.
- Text enqueue clears the local composer snapshot only after successful durable enqueue. A late server acknowledgement does not clear the user's next draft.

Boundaries: an asynchronous write not yet committed can still be lost to abrupt process termination. Server mirroring is not a versioned/durable synchronization protocol. Scheduled sends, rejected-message recovery, settings and quick-reply draft writers still need unified journal semantics; this is not closure of every draft path.

## Android voice notes

- Stop and release complete before the M4A is read. Stop failure still releases the recorder and removes unusable data; empty captures cannot upload.
- Durations use elapsed realtime rather than the wall clock.
- `ON_STOP` finalizes a sufficiently long capture without automatically sending it. On returning to the still-mounted room, the user can send or discard it.
- Upload/send now belongs to a composition scope and a cancellable room job instead of detached IO/Main scopes. Disposing the room cancels its job and discards its unsent local capture. This is explicit discard behavior, not navigation restoration.
- A retry in that room reuses both the operation ID and any already-returned upload path. The server receipt contract can replay a lost send acknowledgement rather than create a second message.

Boundaries: the file/operation/upload-path tuple is not a durable media outbox. Navigation and process death do not restore it. Cancellation cannot revoke an HTTP operation already accepted by the server. An upload whose response was lost can leave an orphaned server file. Long-capture memory limits, interruptions/device permissions, native playback and iOS media parity still require implementation/physical-device validation.

## Tests added (CI execution required)

- `DraftJournalTests.swift`: stale-write rejection after clear, explicit clear vs server fallback, captured-store isolation, ordering, and older room ownership and rapid reopen before SQLite completion (five test methods).
- `MediaOperationApiTest.kt`: two retry requests retain the same operation ID, uploaded path and duration in their wire payload; this is not an end-to-end lost-ack test.
- `VoiceNoteFinalizerTest.kt`: stop/release/file-read ordering, release after stop failure, empty-file rejection and retry without another recorder (four tests).

These tests exercise storage/control contracts; they do not simulate OS microphone interruption, process death or physical-device audio quality.
