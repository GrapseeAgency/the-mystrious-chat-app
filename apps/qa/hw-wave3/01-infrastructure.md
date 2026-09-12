# 01 — INFRASTRUCTURE FIRST (gateway + TURN)

Nothing below can be produced from the sandbox (no inbound ports, no public
IP for UDP relay). This is the operator runbook. Do not run call tests before
every check in §5 is ✅.

## 1. Host

Any VPS with a public IPv4 (Hetzner/DigitalOcean/Lightsail). Open inbound:
`80,443/tcp` (edge/WSS), `3478/tcp+udp`, `5349/tcp` (TURN), `49160-49200/udp`
(TURN relay range). DNS A record, e.g. `pulse.example.com` → the IP.

## 2. Gateway (REST + socket.io over WSS)

Compose (Caddy auto-TLS → relay):

```yaml
services:
  caddy:
    image: caddy:2
    ports: ["80:80", "443:443"]
    volumes: ["./Caddyfile:/etc/caddy/Caddyfile", "caddy_data:/data"]
  relay:
    image: node:22-slim
    working_dir: /srv
    command: sh -c "npm i --omit=dev && node index.js"
    env: { PORT: "3003" }
    volumes: ["./relay:/srv"]      # copy of mini-services/pulse-socket
  web:
    image: node:22-slim            # REST /api/* (apps/web production build)
    volumes: ["./web:/srv"]
    command: sh -c "npm ci && npm run build && npm start"
    env: { PORT: "3000" }
volumes: { caddy_data: {} }
```

`Caddyfile` (devices must reach REST and relay on the SAME origin — that is
what the native `gateway` semantics expect):

```
pulse.example.com {
    handle /socket.io/* { reverse_proxy relay:3003 }
    handle /api/*       { reverse_proxy web:3000 }
    handle              { reverse_proxy web:3000 }
}
```

## 3. TURN (coturn) — STUN-only is NOT acceptable

```conf
# /etc/turnserver.conf
listening-port=3478
tls-listening-port=5349
fingerprint
lt-cred-mech
realm=pulse
user=pulse:CHANGE-ME-long-password
external-ip=<PUBLIC_IP>
min-port=49160
max-port=49200
no-cli
```

`systemctl enable --now coturn` · verify listening: `ss -lunp | grep 3478`.
Long-term credentials are the right trade for the validation phase; rotate
the password after the phase and move to `use-auth-secret` for production.

## 4. Manifest values (what the devices actually adopt)

Commit to `download/update-manifest.json` (the CDN manifest both platforms
probe — Android `ManifestEndpoints.kt`, iOS `PulseEndpoints.fetchManifestOverride`):

```json
{
  "gateway": "https://pulse.example.com",
  "socket":  "https://pulse.example.com",
  "ice": [ { "urls": ["turn:pulse.example.com:3478?transport=udp",
                      "turn:pulse.example.com:3478?transport=tcp",
                      "turns:pulse.example.com:5349?transport=tcp"],
             "username": "pulse", "credential": "CHANGE-ME-long-password" } ]
}
```

- `ice` is new in the W3-HW plumbing commit: Android `PulseEndpoints.applyIceOverride`
  → `CallEngine.iceServers()`; iOS `PulseEndpoints.applyIceOverride` →
  `PulseRTCMediaProvider`. Without it, TURN would be deployed but unused.
- Alternative to the manifest: rebuild Android with
  `-PpulseGateway=https://pulse.example.com -PpulseSocket=https://pulse.example.com`
  (BuildConfig `PULSE_GATEWAY`/`PULSE_SOCKET`). iOS: Settings → Connection,
  or the manifest path above.
- **CDN channel consistency (do together with the manifest commit):** the
  manifest still advertises versionCode 13 / `Pulse-v0.5.0-native.apk` —
  update `versionCode` → 14, `apkUrl` → the `v0.5.1-native` release asset,
  and refresh `download/Pulse.apk` with the v0.5.1 APK so devices updating
  through the CDN channel get the TURN-capable build, not just devices
  side-loaded from the GitHub Release. Keep `gateway`/`socket`/`ice` EMPTY
  until the real host is live — empty preserves offline-first; a placeholder
  host would be adopted and break connectivity.
- Devices adopt overrides within one launch (3–6 s probe, persisted in
  vault/Keychain). Cold-start the apps after committing the manifest.

## 5. Infrastructure verification (all must be ✅ before device tests)

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 5.1 | REST over public HTTPS | `curl -s https://pulse.example.com/api/health` (or any /api route) | 200 |
| 5.2 | WSS reachable | `curl -si "https://pulse.example.com/socket.io/?EIO=4&transport=polling"` | `HTTP/1.1 200`, `0{"sid":…}` |
| 5.3 | TURN UDP allocation | `turnutils_uclient -e <client-ip> -p 3478 -u pulse -w 'CHANGE-ME' pulse.example.com` | allocations + data echoed |
| 5.4 | TURN TLS allocation | same with `-T -p 5349` | allocations + data echoed |
| 5.5 | Relay media range open | `nc -vzu pulse.example.com 49160` from a second host | reachable |
| 5.6 | In-app endpoint adoption | Android logcat filter `ManifestEndpoints` on device; iOS Console filter `PulseEndpoints` | `endpoint override adopted — gateway=… ice=true` |

Record every result in `RESULTS.md` §I. 5.3/5.4 are the TURN-allocation
evidence the directive demands — a call that connects on the same Wi-Fi proves
nothing about TURN.

## 6. Network topology matrix (run the mandatory call in each)

| Run | Device A (caller) | Device B (callee) |
|-----|-------------------|-------------------|
| 1 | Android — mobile data | iPhone — Wi-Fi |
| 2 | iPhone — Wi-Fi | Android — mobile data |
| 3 (reversed) | Android — Wi-Fi | iPhone — mobile data |

Different carriers/NATs on the two legs is the point. Same-network runs are
supplementary only and must be labeled as such.
