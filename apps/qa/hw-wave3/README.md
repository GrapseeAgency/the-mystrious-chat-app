# PULSE — WAVE 3-HW · REAL DEVICE CALL VALIDATION KIT

**Status ledger:** every case in [`RESULTS.md`](RESULTS.md) starts as
`NOT TESTED — BLOCKED: NO HARDWARE`. Nothing becomes PASS without device
evidence. An untested case is never converted to PASS (directive rule).

Code under test: **`v0.5.1-native` (versionCode 14)** — the W3-HW release
with the TURN ICE plumbing in both call engines (badging + `apksigner verify`
checked against the published release asset). No feature work happens in
this phase — hardware proof only.

## Why this kit exists

The Wave 3-HW directive requires physical Android + physical iPhone
validation. The sandbox has **zero** physical capability (proof in
`RESULTS.md` §0): no adb, no USB subsystem, no iOS toolchain, no audio
devices, no public ingress, no TURN-capable host. The kit therefore makes the
hardware phase executable by any operator with two devices, with zero
ambiguity, and pre-fills EXPECTED from the actual Wave 3 implementation.

## Execution order

1. [`01-infrastructure.md`](01-infrastructure.md) — deploy public gateway +
   coturn TURN; fill manifest `gateway`/`socket`/`ice`; verify TURN allocation.
2. [`02-android-device.md`](02-android-device.md) — install release APK,
   grant/revoke mic, start `android-capture.sh`.
3. [`03-ios-device.md`](03-ios-device.md) — install to physical iPhone, log
   capture, permission/interruption drills.
4. [`04-test-matrix.md`](04-test-matrix.md) — mandatory Android→iPhone and
   iPhone→Android calls + all 16 failure-matrix cases.
5. [`05-audio-quality.md`](05-audio-quality.md) — two-way audio evidence,
   defect taxonomy (one-way / zero / echo / latency / leak).
6. [`06-security-identity.md`](06-security-identity.md) — identity, callId,
   anti-spoof, terminal idempotency.
7. [`07-call-history.md`](07-call-history.md) — 4 history states × both
   devices × server persistence.
8. [`08-report-template.md`](08-report-template.md) — the completion report
   skeleton; fill every field, then the acceptance gate below.

## FINAL ACCEPTANCE GATE (verbatim from the directive)

- [ ] Android ↔ iPhone audio works
- [ ] Two-way audio verified
- [ ] Mute/unmute verified
- [ ] Audio routing verified
- [ ] Hangup verified
- [ ] Permissions verified
- [ ] Background/foreground verified
- [ ] Network interruption verified
- [ ] Cross-network TURN call verified
- [ ] Call history verified
- [ ] No microphone remains active after termination

Only when all 11 are ✅ **with device evidence** may Wave 3 be declared
COMPLETE. Any failure: fix only the failing call-path issue, rebuild both
platforms, repeat the hardware gate. No Wave 4 regardless of outcome.
