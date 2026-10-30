# Remote push transport configuration and boundaries

The backend sender is `src/lib/push/transport.ts`. Credentials are supplied by the deployment environment, never committed to this repository.

## APNs

- `PULSE_APNS_KEY_ID`, `PULSE_APNS_TEAM_ID`, `PULSE_APNS_PRIVATE_KEY`: token-signing credentials. Missing credentials skip delivery, not report success.
- `PULSE_APNS_BUNDLE_ID`: app topic. Default **`app.pulse.chat`**, matching `apps/ios/project.yml`. Override only when the signed target really uses another identifier.
- `PULSE_APNS_ENVIRONMENT`: **`production`** (also the unset/empty default) or **`sandbox`**. Development-signed devices use sandbox; distribution/TestFlight devices use production. Unknown nonempty values skip delivery rather than guessing.
- **Per-token environment routing (Section 6):** the registry records `PushToken.apnsEnvironment` from the registration (`iOS clients declare it from build configuration: DEBUG builds declare `sandbox`, release archives declare `production`; the value is advisory). `sendIos` routes each token to the matching APNs origin and falls back to `PULSE_APNS_ENVIRONMENT` only when a registration carries no declaration, so one deployment can serve a mixed sandbox/production fleet. A deliberately mis-declaring client only breaks its own token's routing.
- Requests use Node's HTTP/2 client, an eight-second request/session bound and a 16 KiB response-body limit. One connection per request; no pooling. Live-send failures ARE retried durably (see the outbox section below).
- Message pushes carry a per-message `apns-collapse-id` (`pulse-msg-v1/<recipient>/<conv>/<msg>`, byte-identical with the Android notification tag), so a re-delivered push replaces its tray entry and a retraction can remove the delivered alert by identifier.

## FCM and Web Push

- FCM: `PULSE_FCM_PROJECT_ID`, `PULSE_FCM_CLIENT_EMAIL`, `PULSE_FCM_PRIVATE_KEY`.
- Web Push: `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, optional `VAPID_SUBJECT`.
- Missing credentials do not count as delivery. Legacy endpoint-only web registrations still receive a payload-free ping; encrypted content requires the subscription keys.

## Lifetime and token retirement

- Message provider queues are capped at one hour; call queues at one minute; retraction queues at five minutes. An explicit source/root deadline shortens those limits, rounded down. Invalid, expired or less-than-one-second remaining deadlines skip dispatch.
- Queue TTL cannot recall a notification already displayed by the OS by itself. **Retraction (Section 6):** deleting a fresh message (within the push TTL window) fans out a data-only `retract` push; Android cancels the tray entry by its per-message tag, iOS removes the delivered alert by its `apns-collapse-id`, and the web service worker closes matching shown entries. Legacy mixed FCM notification payloads that the OS auto-displayed in the background cannot be retracted this way (the handler never ran, so no tag identity exists).
- FCM retirement requires HTTP 404 and the typed `google.firebase.fcm.v1.FcmError` code `UNREGISTERED`. Generic 400/404, authentication, project, payload, quota and transient failures retain registrations.
- APNs retirement requires HTTP 410, reason `Unregistered`, and a valid millisecond invalidation timestamp later than the registration snapshot. Credential, topic and environment failures retain registrations.
- Each dispatch rechecks its registration binding **including the monotonic `revision` counter** (every re-registration/rebind increments it), and removal is conditional on that same binding. This closes the same-millisecond rebind boundary: a rebind landing in the same millisecond still increments `revision`, so an in-flight stale prune cannot erase it. It remains non-atomic with the external provider.

## Durable live-push outbox and delivery-time policy (Section 6)

- The inline first attempt stays fire-and-forget. A **failed** report (transient provider/network error) or a planning error enqueues a `LivePushOutbox` row; the maintenance dispatch ticker (the same 20 s drain that flushes scheduled notices) retries with bounded leases, exponential backoff, a 24-hour staleness cutoff and per-binding `LivePushReceipt` idempotence.
- **Policy changes after planning are honored at delivery time:** the fanout re-checks each recipient's current mute/membership per device before dispatch, and every outbox retry re-runs the full privacy planner (mute/block/membership/previews) with current state - a mute that landed between planning and delivery suppresses that device.
- **Producer coverage:** human sends, scheduled dispatch, slash bot, AI companion, webhooks, keyword automations, game/tournament carriers and red-packet carriers all ride the same `deliverLivePush` privacy-planned path.
- **Per-device suppression:** message and call (`gcall`/`call`) pushes keep online users as fanout candidates; opted-in native devices (read-state capability) bypass online-user suppression, so a backgrounded phone still receives while the user is online on a desktop. Legacy/web devices of online users stay suppressed (legacy builds have no in-client dedup). The suppression set is the relay's UNFILTERED presence (`/online?all=1`, shared-secret gated) so a presence-hiding online user still suppresses their own legacy/web pushes instead of receiving duplicate attention.
- **Cross-gateway provenance:** registrations record the gateway `origin` they were created against (stamped from request headers); every payload echoes it, and the native clients drop pushes stamped with a foreign gateway's origin (banner, background wake and quick reply). Absent origin = legacy row (accepted until the next re-registration stamps it).

## Web Push service worker

- The service worker suppresses a banner while a Pulse window is focused (the live socket already delivered the in-app attention), closes shown notifications matching a retraction's `messageId`, and carries `messageId` in the notification data for that matching.
- `pushsubscriptionchange` re-subscription posts without credentials (the SW holds none); the server refuses it until the next focused re-registration rebinds the row.

## Not yet cleared

User-wide online suppression can still starve a backgrounded device for **legacy** installs (pre-capability builds have no in-client dedup, so their devices stay suppressed while any socket for the user is online). Android's mixed notification/data FCM messages bypass `onMessageReceived` in the background; those OS-rendered banners now land on the pinned Messages channel, but the custom owner/expiry/retraction guards still apply only when that handler runs. iOS expiry suppression applies to foreground presentation, not OS-managed background banners (the retraction path closes deleted-message banners; expired-message banners rely on client-side reconciliation). Retraction covers deletes of messages within the push TTL window; expirations have no retraction event. Credential-missing push failures enqueue outbox retries that retry until the 24-hour staleness cutoff (bounded, but noisy under a sustained outage). The web frontend's headerless media/`<img>` migration window is unchanged.

Local tests exercise actual HTTP/2 framing/session cleanup and production fanout against SQLite with simulated provider responses. They do not authenticate against Apple/Firebase or verify physical-device delivery, lockscreen behavior, permissions, signing, or background quick reply.

### Group-call admission dependency

Group-call admission now asks Next's `/api/internal/call-access` for a fresh room-membership verdict. Next and the socket relay must share a nonempty `CRON_SECRET`; `PULSE_ORIGIN` on the relay must reach the Next deployment. Deploy the endpoint together with the relay. Missing credentials, malformed replies or authority outages refuse new group-call joins rather than bypassing membership. This does not revoke already-active calls instantaneously and does not replace the existing session-registration policy.

Active group calls also refresh membership: each successful admission/check grants a 30-second lease measured from the request's start. Conversation updates trigger refresh; a five-second sweep covers quiet calls and expires old leases. Explicit membership refusal removes that participant. Temporary authority failure does not immediately revoke a still-valid lease, but cannot renew it; sustained outages therefore terminate calls. The refresh queue permits up to eight concurrent membership checks. Signaling cannot recall media already sent or force non-cooperating P2P clients to close connections.

The direct relay `/gcall` roster probe now requires `x-pulse-key: <CRON_SECRET>` and must not be used as a public client API. Native/web clients call `/api/group-call-state` with their viewer and conversation IDs; that route performs membership checks and forwards the key internally. Deploy the updated Next route, relay and clients together: the new client probe requires a versioned viewer/room receipt, and unavailable/malformed relay responses are HTTP 503 rather than false empty calls. This does not change the deferred optional-token migration policy.

**All internal relay HTTP endpoints require the shared key:** `/notify`, `/typing`, `/online`, and `/gcall`. Updated Next backend callers send `x-pulse-key`; never distribute this key to native or browser clients. Missing/wrong keys return 401 before event handling; the relay logs a startup warning if `CRON_SECRET` is missing. Coordinate the Next/relay deployment and verify matching nonempty secrets. Without configuration, best-effort live events/typing are skipped, group rings report unavailable, and push online suppression falls back to treating recipients as offline. Socket.IO and health requests do not require this server-only HTTP key; their existing session policy is unchanged.
