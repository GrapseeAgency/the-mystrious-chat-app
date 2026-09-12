# 01 — PUBLIC GATEWAY + MEDIA-PATH DETERMINATION (W5-HW)

Requirement from the directive: **public gateway** and **TURN where
cross-network media requires it**. This file sets both up — and records
the TURN determination honestly so the operator does not chase a
non-dependency.

## 1. What Wave 5 room media actually is

Wave 5 voice rooms / stage / space do **not** use peer-to-peer WebRTC.
Room audio is 16 kHz mono PCM-16, chunked into 250 ms blocks, base64
framed and fanned out **by the socket relay** (`mini-services/pulse-socket`,
`voice:chunk`, 96 KB per-chunk cap). Both devices connect outbound to the
public gateway over WSS; the server relays. There is no direct
device-to-device path, so **coturn/TURN is NOT on the Wave 5 room-media
path**. Cross-network (T2) works as soon as the gateway itself is public.

TURN remains a dependency of the **Wave 3-HW call matrix** (WebRTC 1:1
calls, still OPEN — separate gate). If the same deployment also hosts that
matrix, verify TURN allocation per `apps/qa/hw-wave3/01-infrastructure.md`;
it is optional for W5-HW and marked NOT TESTED (optional) in RESULTS.md.

## 2. Public gateway requirements

- REST over **HTTPS** and socket over **WSS** (cleartext `http://` to a
  phone's own IP is blocked by the Android network security policy — the
  W3 Home-fix lesson; iOS ATS likewise. Use TLS termination on a public
  host).
- The pulse-socket service (voice/stage/space events) and the web API
  (including `POST /api/voice/transcribe` for captions) reachable from
  both devices.
- The deployer runs the gateway; the sandbox cannot (no inbound ports —
  `RESULTS.md` §0).

## 3. Endpoint adoption — two supported paths

1. **CDN manifest bootstrap (recommended):** fill `gateway` and `socket`
   in `download/update-manifest.json` (currently blank) with the public
   origin/socket URL, push to `main`. The app probes the manifest at
   launch and adopts endpoints (logcat `ManifestEndpoints` / iOS Console
   adoption line = evidence).
2. **In-app:** Profile → Connection — set server + socket origin on each
   device. Acceptable if labelled in evidence.

## 4. Preflight checks (run before any device case)

```bash
# 4.1 REST over public HTTPS (expect 2xx)
curl -sS -o /dev/null -w '%{http_code}\n' https://<gateway-origin>/api/health

# 4.2 WSS handshake (expect HTTP/1.1 101 or socket.io polling 200)
curl -sS -o /dev/null -w '%{http_code}\n' 'https://<socket-origin>/socket.io/?EIO=4&transport=polling'

# 4.3 Captions route exists (participant-gated; expect non-404)
curl -sS -o /dev/null -w '%{http_code}\n' -X POST https://<gateway-origin>/api/voice/transcribe

# 4.4 CDN manifest carries the endpoints you filled
curl -sS 'https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main/download/update-manifest.json' \
  | python3 -c 'import json,sys; m=json.load(sys.stdin); print(m.get("gateway"), m.get("socket"), m.get("versionCode"), m.get("sha256")[:8])'
```

Record all four in RESULTS.md §I. On each device, confirm the app shows a
configured origin (NOT the offline copy *"No gateway configured — set your
server in Profile → Connection."*) and both accounts can open the same
conversation.

## 5. Accounts + rooms

- Acct-A signed in on Android, Acct-B on iPhone.
- One shared conversation containing both accounts is the room host
  surface for all cases (chat header mic entry → Voice room / Stage /
  Space).
- Note device model + OS version + app version (Profile shows
  0.7.0-native; or `adb shell dumpsys package app.pulse.chat | grep version`).

## 6. Record in RESULTS.md §I

| Check | Where |
|-------|-------|
| 5.1 REST over public HTTPS | §4.1 output |
| 5.2 WSS handshake | §4.2 output |
| 5.3 TURN UDP allocation (optional, W3-HW only) | coturn turnutils_uclient output — NOT required for W5 |
| 5.4 In-app endpoint+ICE adoption | launch log line both devices |
| 5.5 Install proof versionCode 17 | §4.4 manifest + device app version |
