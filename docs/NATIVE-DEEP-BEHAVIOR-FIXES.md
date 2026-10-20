# Deeper native behavior fixes

This pass follows actual interaction/state transitions rather than treating build results as product correctness. Account sign-in/recovery remains deferred.

## Concrete failures addressed

| Reproduction / failure | Repair |
|---|---|
| Android: send A, type B before A completes; A's acknowledgement erased B. | A revisioned composer clears only the submitted text ownership. A → B → A is also a new intent, not an equal-string match. Disposed room callbacks cannot clear another room's composer. |
| Android: a delayed local draft read could replace text already typed on entry. | Restoration checks that no composer edit happened during the read. |
| Rejected foreground sends overwrote the current draft; background refusals deleted the content. | Both stores retain failed intent separately, preserve its envelope, stop automatic retries and expose copy/discard recovery. The current draft is untouched. |
| Failed rows at the front could starve pending rows or repeatedly schedule retry work. | Failure filtering occurs before batch limiting; pending counts and worker triggers exclude failed intent. Android recovery lists no longer silently hide entries beyond 50. |
| Discard removed the iOS queue record but left an optimistic bubble; a running drain could still send a later discarded snapshot entry. | Transactional queue+echo deletion, immediate iOS UI removal event and a pending-state recheck before each queued attempt. Discard cannot revoke an HTTP request already accepted by the server. |
| Android rich persistence overwrote internal anonymity metadata; empty JSON payloads could change to null during retry. | Preserve privacy metadata in the echo and original rich payload separately. Retry uses the saved kind, quote, topic, privacy and media fields. Invalid rich payloads fail before persistence; corrupt queued envelopes are retained as failed rather than blocking forever. |
| A payload using internal `pulse:*` keys could masquerade as cache privacy metadata. | Storage ignores those supplied keys when writing its internal metadata and derives privacy from the message's actual fields. The original outbound payload remains separate. |
| Entering edit mode replaced an unsent composer; cancel/success discarded it. iOS failed edits had already erased their edited text. | Primary Kotlin/Swift rooms preserve and restore pre-edit draft/quote state. Edit text is not saved over the original draft. iOS retains a failed edit and guards late edit completion against newer typing. |
| Delayed scheduling/document completion cleared newly typed text. | Revision ownership checks on Android scheduled/document completion and Swift scheduling; Swift scheduling uses a captured client/generation. |
| Android topic selection changed the send destination but not the displayed timeline; thread replies leaked into the main river. | Main timeline filters root messages by selected topic; tail/read-display/effect selection follows visible rows. Quote and effect sends retain their captured topic. |
| Hidden-reply history pages repeated because pagination anchored to the oldest rendered bubble. | Primary rooms advance using raw response rows (timestamp + ID), retain progress across hidden pages, bound sparse scans and expose a Load earlier messages control. Swift cached reopen does not incorrectly assume older history is exhausted. Android jump opens a thread when the target is a reply. |
| A late microphone permission result could act on a different room; voice retries could inherit a changed topic. | Gate the permission callback by the requesting room, key recording state to that room, and keep the topic captured at recording start. Durable voice restoration is still open. |
| Expired Android messages stayed visible until another event rebuilt the timeline. | Visibility includes expiry and a lifecycle-owned timer wakes at the next cached expiry; foreground re-entry reevaluates it. |

## Storage and tests

- Android Room: additive v10 → v11 `failureReason` / `wirePayload` columns, existing rich-kind repair, updated production and instrumentation migration registration. Failed metadata remains stored but is excluded from normal message/thread observations.
- Swift GRDB: additive v8 → v9 failure reason; transactional rejection/discard and separate pending/recovery queries.
- `python3 tests/native/test_outbox_sql_contract.py`: **4 passed locally**. Executes actual Kotlin migration/DAO SQL against the checked-in Room v10 schema, checking preservation, failure filtering, starvation and discarded snapshots. This does not substitute for Room-generated-schema/runtime validation.
- `node --experimental-transform-types --test tests/server/*.test.mjs`: **10 passed locally**, unchanged server delivery/sync contracts.
- Native regression additions: revisioned composer, topic/thread/expiry filtering, raw history cursor, privacy/payload round-trip, failed envelope preservation, no draft overwrite, failed backlog, reopen recovery, discard/echo consistency and discard-during-drain. Native execution/device verification is not claimed here.

## Still open

Durable media upload/send restoration across process death; remaining detached media/ancillary tasks; alternate feature-chat composer/pagination parity; complete cross-device draft synchronization; interrupted recording/audio playback on physical devices; broader UI/OS acceptance scenarios. Some task cancellations cannot undo already accepted server side effects. These fixes are not a guarantee that every abnormal behavior has been eliminated.
