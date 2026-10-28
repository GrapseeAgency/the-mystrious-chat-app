# Section 3 — native message producer inventory

Source audit on the original eleven-item reconciliation, not a claim about all
sixteen sections. The three Android direct `api.sendMessage` invocations live in
`PulseRepositoryImpl`: immediate text, immediate rich, and replay. The iOS message
HTTP implementation is `PulseAPIClient.sendMessage`, called through
`PulseOutboxSending` in `PulseOutboxEngine`. No native screen uses the legacy poll
HTTP creator. Architectural source tripwires supplement runtime tests.

| Producer | Android path / ownership | iOS path / ownership |
| --- | --- | --- |
| Main text, quote, topic, anonymous, effects | ChatRoomViewModel → SendMessageUseCase or bound rich send → persistComposerSend → sendPersistedNowOrQueue. Draft token captured before coroutine; rich account receiver now captured before launch, including no-draft sticker/location sends. | ChatRoomView → PulseTextDelivery → PulseStore.enqueueMessage. Session/store/viewer checked synchronously on MainActor; journal token consumption is transactional. |
| Alternate room / alternate list composer | MirrorRoom/MirrorRoot → captured account + draft token → same repository transaction. | MirrorRootView → PulseTextDelivery and same store transaction. |
| Thread | Thread composer → root-scoped token and parentId; same durable queue, never main draft clear. | ThreadView → PulseTextDelivery(parentId, draftJournal); root scope and access checks. |
| Mini-chat / PiP | **Gap found and repaired:** input previously memory-only and cleared before send. Now stages through account-owned draft checkpoint/journal, restores on reopen, captures account/token before launch, catches persistence failure and clears only matching text/revision after queued/delivered result. New typing survives older completion. | PipPaneHostView already uses a session-generation-checked PulseDraftJournal and synchronous durable enqueue before clearing. |
| Sticker / location | Standard room uses captured rich-send receiver. **Alternate picker gap repaired:** MirrorRoom now binds before launch, prevents duplicate taps and keeps picker open on storage/error instead of silently dismissing and swallowing failure. | Main and mirror rich producers → PulseTextDelivery.enqueueRich; local validation and durable envelope before acceptance. |
| Poll | ChatRoom poll dialog captures an account-bound submission closure at presentation. Mirror poll captures receiver/epoch before launch. Both → repository.createPoll → sendRichMessage(kind=poll); picker closes only on accepted local result. | Main/mirror polls → enqueueRich(kind=poll), no separate non-durable poll HTTP send. Backend message transaction creates poll/options and message together. |
| Forward | Standard/Mirror forward picker captures receiver → forwardMessage transaction, source/target membership and source policy checked, stable envelope before background flush. | ForwardSheet → PulseForwardDelivery → enqueueMessage with source validation in the same transaction. |
| Attachment / voice / image / file message handoff | Durable attachment selection/files → queueAttachmentDraft / queuePreparedAttachment → outbox. Upload receipt is persisted before message HTTP replay. | PulseAttachmentDelivery → enqueueMessage with durable attachment envelope; engine persists upload receipt then replays the same client ID. Capture/import mechanics remain section 4. |
| Notification reply | PulseReplyReceiver → sendNotificationReply: original notification owner/session proof, durable standard text queue, no unrelated draft consumption. | PulseQuickReplyCoordinator validates notification owner and active API/store; PulseQuickReply.persist records pending/held reply before reporting queued. Missing store/storage failure is explicitly not saved. |
| Foreground reconnect, worker, background refresh, explicit retry | Same account-owned outbox dispatch gate and stored envelopes, not a second producer. Account-gated retry deadlines and per-room heads survive restart. | Same PulseOutboxEngine and stored envelopes, including background work. Owner/store validation remains before dispatch. |
| Scheduled creation / cancellation | Both presenters → repository schedule methods → ScheduledJournal before HTTP; accepted receipt/head fencing before UI acknowledgment. | Both presenters → PulseSession → PulseScheduledJournal before HTTP. Exact original body/time/client identity survives uncertainty. |

The local demo companion is intentionally on-device, not an HTTP sender. Domain
operations that may create server-side event/gift/game/system notices are not
ordinary composer sends; their full behavior remains in their later sections.
This audit does not turn untested UI behavior into a runtime pass. New PiP,
checkpoint and picker changes still require hosted compilation and native tests.
