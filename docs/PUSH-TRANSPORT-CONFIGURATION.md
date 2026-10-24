# Remote push transport configuration and boundaries

The backend sender is `src/lib/push/transport.ts`. Credentials are supplied by the deployment environment, never committed to this repository.

## APNs

- `PULSE_APNS_KEY_ID`, `PULSE_APNS_TEAM_ID`, `PULSE_APNS_PRIVATE_KEY`: token-signing credentials. Missing credentials skip delivery, not report success.
- `PULSE_APNS_BUNDLE_ID`: app topic. Default **`app.pulse.chat`**, matching `apps/ios/project.yml`. Override only when the signed target really uses another identifier.
- `PULSE_APNS_ENVIRONMENT`: **`production`** (also the unset/empty default) or **`sandbox`**. Development-signed devices use sandbox; distribution/TestFlight devices use production. Unknown nonempty values skip delivery rather than guessing.
- Requests use Node's HTTP/2 client, an eight-second request/session bound and a 16 KiB response-body limit. The implementation currently opens one connection per request; it does not provide pooling or a durable retry queue.
- The current registry does not record an APNs environment per token. One deployment cannot reliably mix development and production registrations with this configuration alone. Use separate environments; do not flip a shared production backend to sandbox to test a development device.

## FCM and Web Push

- FCM: `PULSE_FCM_PROJECT_ID`, `PULSE_FCM_CLIENT_EMAIL`, `PULSE_FCM_PRIVATE_KEY`.
- Web Push: `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, optional `VAPID_SUBJECT`.
- Missing credentials do not count as delivery. Legacy endpoint-only web registrations still receive a payload-free ping; encrypted content requires the subscription keys.

## Lifetime and token retirement

- Message provider queues are capped at one hour; call queues at one minute. An explicit source/root deadline shortens those limits, rounded down. Invalid, expired or less-than-one-second remaining deadlines skip dispatch.
- Queue TTL **cannot recall a notification already displayed by the OS**. Normal-message planning and native foreground previews use a generic disappearing-message caption instead of persisting its original text in notification history.
- FCM retirement requires HTTP 404 and the typed `google.firebase.fcm.v1.FcmError` code `UNREGISTERED`. Generic 400/404, authentication, project, payload, quota and transient failures retain registrations.
- APNs retirement requires HTTP 410, reason `Unregistered`, and a valid millisecond invalidation timestamp later than the registration snapshot. Credential, topic and environment failures retain registrations.
- Each dispatch rechecks its registration binding, and removal is conditional on that same binding and `updatedAt`. This protects a refresh/rebind during an earlier send or before the delete. It is **not** atomic with the external provider: in-flight delivery and same-millisecond registration revisions remain boundaries.

## Not yet cleared

User-wide online suppression can still starve another background device. Removing it requires coordinated native/socket attention deduplication, not simply deleting the gate. Android's mixed notification/data FCM messages bypass `onMessageReceived` in the background; the new native expiry guard applies only when that handler runs. iOS expiry suppression applies to foreground presentation, not OS-managed background banners. There is no notification recall or durable push outbox, and non-normal-message producer coverage remains incomplete.

Local tests exercise actual HTTP/2 framing/session cleanup and production fanout against SQLite with simulated provider responses. They do not authenticate against Apple/Firebase or verify physical-device delivery, lockscreen behavior, permissions, signing, or background quick reply.

### Group-call admission dependency

Group-call admission now asks Next's `/api/internal/call-access` for a fresh room-membership verdict. Next and the socket relay must share a nonempty `CRON_SECRET`; `PULSE_ORIGIN` on the relay must reach the Next deployment. Deploy the endpoint together with the relay. Missing credentials, malformed replies or authority outages refuse new group-call joins rather than bypassing membership. This does not revoke already-active calls instantaneously and does not replace the existing session-registration policy.

Active group calls also refresh membership: each successful admission/check grants a 30-second lease measured from the request's start. Conversation updates trigger refresh; a five-second sweep covers quiet calls and expires old leases. Explicit membership refusal removes that participant. Temporary authority failure does not immediately revoke a still-valid lease, but cannot renew it; sustained outages therefore terminate calls. The refresh queue permits up to eight concurrent membership checks. Signaling cannot recall media already sent or force non-cooperating P2P clients to close connections.

The direct relay `/gcall` roster probe now requires `x-pulse-key: <CRON_SECRET>` and must not be used as a public client API. Native/web clients call `/api/group-call-state` with their viewer and conversation IDs; that route performs membership checks and forwards the key internally. Deploy the updated Next route, relay and clients together: the new client probe requires a versioned viewer/room receipt, and unavailable/malformed relay responses are HTTP 503 rather than false empty calls. This does not change the deferred optional-token migration policy.
