# Pulse v0.5.2-native (versionCode 15) — Home connectivity fix

## Root cause
With no gateway configured (honest offline-first), the Android REST layer still fired requests. Ktor's URLBuilder resolved the relative path against its implicit `http://localhost` default, so OkHttp attempted a cleartext request at the phone itself and Android's network security policy (targetSdk 35) correctly blocked it — Home showed:
> Could not reach gateway — Network cleartext communication to localhost is not permitted by network security policy.

## Fix
- Gateway guard at every REST entry: zero requests fire while unconfigured; honest copy ("No gateway configured — set your server in Profile → Connection.").
- Deployment manifest now bootstraps from the HTTPS repo CDN when offline — a gateway published into `update-manifest.json` is adopted with zero rebuild.
- No network-security weakening (cleartext stays blocked).

## Evidence
- Data-module JVM tests 7/7 (MockEngine + real OkHttp engine vs loopback origin: 0 hits offline, 1 hit at the exact configured URL).
- APK: `app.pulse.chat` versionCode 15, minSdk 21, v1+v2+v3 signed, sha256 `94b142af8526476f3b3fc1a93ab5ff24bd711f1dcd4a2864feaee5fa1da52f03`.
- Backend verified: `GET /api/conversations` returns the exact wire shape the client parses.

## Install
Overwrite-install (signature-identical, versionCode 15 > 14). Still ships the Wave 3-HW TURN ICE plumbing.
