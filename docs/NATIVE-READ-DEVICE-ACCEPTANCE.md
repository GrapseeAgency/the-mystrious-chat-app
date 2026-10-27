# Paired-device acceptance: the nine Seen/unread items

## Status and authority

A founder/tester is available to run the physical-device checks. **This is a test plan, not a pass certificate or authorization to merge/deploy.**

Do not nominate the current build as accepted yet. At source checkpoint `3cf8cf4`, Android's whole workflow passed, but iOS reported a hosted-thread presentation guard failure and a call-signaling join timeout. The iOS archive compiled. Diagnostics in `6f3a112` subsequently showed the correct anchor/window/active scene, but UIKit still reported the non-animated hosting presentation as `isBeingPresented=true`, with a zero-duration coordinator at progress 0. The guard correctly blocks that unfinished transition. The fixture is being changed to exercise an animated sheet lifecycle, including blocked-during-transition and eligible-after-settlement assertions. This is not a runtime-gate relaxation or a physical-device pass. Neither the root-appearance barrier nor switching to an animated transition resolved the fixture failure: `9f4a9cc` also reported `isBeingPresented=true` with an animated 0.4-second coordinator after the wait. The next fixture submits pending implicit Core Animation transactions through the public flush API and fails immediately if readiness is not reached, rather than attempting nested presentation after a failed precondition. Validation of that change is pending, not a demonstrated historical root cause. Earlier passing runs do not explain these failures.

No production migration or deployment has been executed in this workstream. The matching backend must be ready in an **authorized test environment** before these scenarios are meaningful. See [deployment order and contracts](NATIVE-READ-ORDERING.md#deployment-order-not-executed). Do not apply migrations or change production data just to run this checklist.

## Later hosted checkpoint (not device acceptance)

Candidate `02976a6` completed the Android build/API 30/API 34 workflow and the iOS archive/build-test workflow successfully. This later passing run does not by itself establish the cause of earlier intermittent failures or certify physical OS behavior. The strengthened runtime gates and both-platform permission ownership changes are included. No matching deployed test environment or paired-device results have yet been verified.

If backend readiness is unknown, give the operator [the backend-readiness guide and receipt](NATIVE-READ-BACKEND-READINESS.md) first. Do not guess readiness from a successful app build.

## Setup

- Record the exact backend and installed native commit/build identifiers. Use one nominated, matching candidate, not an old `main` backend with a new APK/archive.
- Reader **R** has an authorized session on **Android A** and **iPhone B** simultaneously. Sender **S** uses a separate authorized client/test harness. This requires three sessions, not necessarily three physical phones.
- Use the team's existing installation/signing process. Do not share signing keys, session tokens, authorization headers, or recovery credentials in reports.
- Use synthetic messages and an isolated test room. Prepare two threads and two topics; distinguish messages with labels such as `GAP`, `VISIBLE`, `NEWER`, `THREAD-1`, and `THREAD-2`.
- Record OS versions, Android launcher, notification permissions, battery restrictions, app lock, and receipt-privacy settings. Confirm both reader sessions remain authorized; if one invalidates the other, report that prerequisite failure instead of bypassing authentication.
- Use at least two seconds of stable, unobscured viewing for deliberate reads. Scrolling, transitions, backgrounding, and covers must interrupt dwell.
- Use the native Message Info/exact read-state response to verify Seen. A legacy web watermark or two displayed times rounded to the same minute is not exact proof.
- Enable read receipts for baseline Seen assertions. In the privacy-off variant, verify R's own counts/proof and that S does **not** receive hidden receipt evidence; do not expect public Seen.
- Keep the other R session outside the tested room/thread unless a step deliberately asks it to read. Inspect Seen from S or an authorized read-only proof query. Opening a control message on R's other device would itself legitimately read it and invalidate the unseen-gap control.
- Run the Android-first direction, then reverse the roles so iOS originates the offline reads, interruptions, and actions.

For each case report **PASS**, **FAIL**, or **BLOCKED**, with the exact step and observed state. Missing tooling, unsupported launcher behavior, or an unobserved OS background wake is not a pass. Record permission/OS limitations separately from app behavior.

## 1. Delayed reads must not erase a later manual reminder

1. Keep A's room mounted with a sheet covering its messages. Have S send messages while the cover is present so the content can arrive without qualifying as read.
2. Take A offline. Dismiss the cover and deliberately view a message, then leave the room. This should capture a durable observation, not claim server acceptance.
3. On B, explicitly mark the room unread. Keep B outside that room afterward.
4. Reconnect A without reopening the room. Allow replay and canonical inbox refresh.
5. **Expected:** the observed identity can become Seen, but B's newer manual unread reminder survives on both readers. Actual unread progress and a manual reminder are distinct states.
6. Repeat with A retaining its original mounted visit: after B marks unread, S sends a newer message and A reconnects. Old mounted authority must not silently renew just because new content or metadata arrives.
7. As a separate deliberate action, use a genuinely fresh reading visit or explicit mark-all. Confirm the reminder can then clear with current authority.

## 2. Equal timestamps must not merge message identities

1. An authorized staging fixture must create two distinct message IDs with exactly the same canonical `createdAt`, including milliseconds. Do not change phone clocks or edit production rows.
2. Expose only one message long enough to qualify. Keep the other offscreen.
3. **Expected:** only the visible identity receives exact proof; the unseen identity still contributes to actual unread count.
4. Explicitly mark all read, keep both R sessions outside the room, then create another identity with the same timestamp after that operation commits.
5. **Expected:** the later identity remains unread. Mark-all must not create an open-ended timestamp range.
6. If an exact-time fixture is unavailable, mark this case BLOCKED; visual timestamp formatting is not a substitute.

## 3. Later visible messages must not sweep earlier unseen gaps

1. Seed enough messages for a genuine offscreen gap. Keep `GAP` outside the viewport; use a jump/search target or controlled scroll to display `VISIBLE` later in the stream.
2. Let `VISIBLE` dwell unobscured, then leave the room without displaying `GAP`.
3. **Expected:** `VISIBLE` becomes Seen, `GAP` does not, and the canonical actual-unread count still includes `GAP`.
4. Repeat across pagination, a long message, and return from a jump. Record which identities actually entered the viewport; do not count a briefly visible gap as an offscreen control.
5. The entry-time unread divider can remain as a navigation landmark. Test live counts and exact proof separately from that frozen divider.

## 4. Thread and topic unread state must remain independent

1. Put unread messages in thread 1, thread 2, topic 1, and topic 2.
2. Read only thread 1. Close it, then inspect the other scopes and room aggregate.
3. **Expected:** only observed identities are excluded from unread totals; thread 2 and unrelated topics do not clear.
4. Read topic 1 without opening topic 2. Repeat the independence check.
5. Background with a thread retained. Add a reply from S, then return without forcing a disconnect/reconnect.
6. **Expected:** the retained thread refreshes content and its count. Its enclosing sheet must not block its own reader; an additional sheet above it must block reading beneath that sheet.
7. A conversation-wide manual reminder must not invent unseen message identities inside every thread/topic.

## 5. Offline observations must survive navigation and process death

1. Cache incoming content behind an obscuring surface, then go offline.
2. Dismiss the cover, deliberately read selected identities, and navigate away.
3. Terminate the app process without clearing app data. Reopen and reconnect while staying outside the original room/thread.
4. **Expected:** original captured identities replay; no new visit or fresh intent is needed. Unseen neighbors remain unread.
5. With an authorized fault proxy, return a generic 403/404 for the read POST, without a bound retirement envelope. Leave the room, remove the fault, and trigger a normal foreground/reconnect retry.
6. **Expected:** the original observation was not silently discarded. A wrong-owner, wrong-room, wrong-message, or wrong-intent retirement envelope must also be rejected.
7. Remove/rejoin R's membership while an old observation is queued. A matching authenticated retirement may remove that old journal entry, but must not mark the new membership read.
8. Test both ordinary foreground replay and a background opportunity. A refusal is never Seen. If fault injection is unavailable, report that portion BLOCKED; automated transport/storage coverage remains separate evidence.

## 6. Cross-device operations must respect ordering

1. Use A and B as the same reader account. Race deliberate mark-all against manual unread, preserving the actual request/response ordering in a redacted trace.
2. **Expected:** an explicit action using stale authority is refused rather than overwriting a newer accepted action. Neither device may silently prepare new authority merely to make the retry succeed.
3. Simulate a lost successful response and retry the original operation. Check convergence without duplicate side effects or a cleared newer reminder.
4. Repeat the delayed visible-read case from item 1 in the opposite platform direction.
5. Fresh user intent is a separate action; label it separately from replay in the evidence.

## 7. New-member history must be consistent, not invented Seen

1. Seed messages before R joins the group/channel. Join through the supported product flow.
2. **Expected policy:** existing history is exempt from new unread counts without being advertised as actual Seen by R. Verify before R opens the room, or use an offscreen historical control. If a join flow automatically opens visible history, that history can legitimately qualify after dwell; do not misreport that real observation as invented Seen.
3. Send a new message after membership creation. It must be unread until observed or deliberately included in mark-all.
4. Use the exact-time staging fixture to cover messages present at join versus identities inserted later with the same timestamp.
5. Read selected historical messages. Their proof may now reflect a real observation.
6. Remove and rejoin R. **Expected:** old membership proof/queued authority is not attributed to the new membership. Repeat relevant invite/channel/group entry paths used by the product.

## 8. Covers and interruptions must prevent unintended Seen

Start with a fresh unseen message that arrives while the cover/interruption is already active. This avoids mistaking a legitimate pre-cover read for a failure.

Run each supported surface independently:

- Incoming call ring, active call UI, group-call ring, and live-room overlay.
- Room sheet, root-shell sheet, nested sheet above a thread, share/invite panel, photo picker, document picker, and media lightbox.
- Composer edit menu and supported Writing Tools/lookup panels.
- Android notification shade/window-focus loss; iOS scene deactivation/system interruptions.
- App lock, device lock, background, navigation away, and supported PiP/multiwindow configurations.

**Expected:** messages hidden beneath the surface do not become Seen. Dismissing the cover or returning to foreground starts a fresh full dwell, not the remainder of an interrupted dwell. Navigating away must not allow a delayed UI task to create a new observation.

A legitimate thread sheet must still allow reading *inside that thread* when unobscured. The current hosted iOS failure concerns this distinction and must be investigated before this case can be signed off.

Record geometry, accessibility settings, interruption timing, and a short screen recording. Do not mark OS-specific surfaces passed on a device that cannot exercise them.

## 9. Counts and delivered alerts must converge without losing gaps

1. Establish a known unread set across rooms, threads, and topics. Record room/inbox/in-app badge counts on A and B.
2. Read only one exact message on A. **Expected:** B's canonical projections converge; a separate unseen gap remains counted.
3. Deliver alerts for `READ` and `GAP` and verify both are present before cleanup. Do not manually dismiss either. While exact cleanup for `READ` is in flight, deliver `NEWER`.
4. **Expected:** only the alert proven no longer unread is removed. `GAP` and `NEWER` remain. Repeat with read receipts private; R's other device must still reconcile its own unread state.
5. Hold one outgoing upload while read replay/badge reconciliation is otherwise online. **Expected:** outgoing work does not serially prevent read-state work from running.
6. Repeat background/cold-start scenarios when the OS grants execution, then return to foreground to verify catch-up. Record whether a wake actually occurred; force-quit/background throttling is not evidence that application work executed.
7. Check supported app-icon behavior separately. Android launchers may expose dots rather than numeric counts, and permissions may suppress badges. Do not claim universal numeric launcher parity.
8. A historical Android gap-alert assertion failed once and has not been assigned a root cause. Passing current runs do not explain it; include exact alert identities and timing if reproduced.

## Additional acknowledgement-ordering checks

- Hold a successful **send/action response**, not its request, so the server can commit the message and another reader can observe it. Deliver that exact read evidence before releasing the old response. **Expected:** the late ACK does not erase current proof; the delivery event and cached row agree.
- Accept a newer canonical negative before releasing an older action response containing positive proof. **Expected:** the old action response does not resurrect that proof. Canonical GETs and dedicated read receipts remain authoritative; preservation is not a generic union.

## Results template

```text
Candidate/native builds:
Backend build + migration checkpoint:
Android device / OS / launcher:
iPhone model / iOS:
Reader sessions concurrently authorized: yes / no
Notification permissions and battery restrictions:
Receipt privacy settings:

Case and platform direction:
Result: PASS / FAIL / BLOCKED
Steps actually run:
Expected:
Observed (exact identities + counts, not rounded timestamps):
Network / foreground / interruption sequence:
Evidence: redacted trace, synthetic message labels, recording timestamps
Was a background execution opportunity observed?:
```

Do not submit credentials or real private conversations. A failing or blocked case stays open. Source implementation, native compilation, automated regression results, deployment, and paired-device acceptance are separate checkpoints.
