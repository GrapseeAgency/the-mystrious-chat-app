# RESULTS — WAVE 3-HW (the honest ledger)

> Rule in force: **an untested case is never converted to PASS.**
> Current verdict: **CODE/CI VERIFIED + HARDWARE VERIFICATION REQUIRED —
> NOT COMPLETE.**

## §0 — Why every device case below is NOT TESTED (sandbox proof, 2026-09-11)

Probe run in the execution sandbox (Debian 13 container, kernel 5.10):

```
$ which adb fastboot          → not found / not found
$ adb devices -l              → (adb absent — cannot ever see a device)
$ which xcodebuild xcrun simctl idevice_id ideviceinfo
                              → (none exist; host is Linux — iPhone-over-cable
                                 is categorically impossible without macOS)
$ lsusb                       → not found
$ ls /sys/bus/usb/devices     → No such file or directory   ← NO USB SUBSYSTEM AT ALL
$ arecord -l                  → not found
$ ls /dev/snd /dev/video*     → No such file or directory   ← NO capture hardware
$ ip -brief addr              → lo + eth0 (private /32), egress-only NAT
                                 (public egress IP observed; no inbound ports)
```

Consequences, each a hard blocker:
1. No physical Android device can be attached (no adb, no USB subsystem).
2. No physical iPhone can be attached (no macOS, no USB subsystem).
3. No synthetic or real microphone/speaker exists (no /dev/snd).
4. A publicly reachable production gateway cannot be deployed from the
   sandbox (no inbound port exposure).
5. TURN cannot be hosted from the sandbox (needs a public IP + UDP relay
   range 49160-49200).

Therefore: 0 of the 11 acceptance-gate items are claimable here. The kit in
this directory makes every gate executable by an operator with two devices.

## §I — Infrastructure verification (fill from 01-infrastructure §5)

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 5.1 | REST over public HTTPS | NOT TESTED — BLOCKED: NO HARDWARE/HOST | |
| 5.2 | WSS handshake | NOT TESTED | |
| 5.3 | TURN UDP allocation | NOT TESTED | |
| 5.4 | TURN TLS allocation | NOT TESTED | |
| 5.5 | Relay media range | NOT TESTED | |
| 5.6 | In-app endpoint+ICE adoption | NOT TESTED | |

## §M — Mandatory calls

| ID | Result | Notes |
|----|--------|-------|
| M1 Android → iPhone (topology 1) | NOT TESTED — BLOCKED: NO HARDWARE | |
| M2 iPhone → Android (topology 2) | NOT TESTED — BLOCKED: NO HARDWARE | |

## §F — Failure matrix

| ID | Case | Result |
|----|------|--------|
| F1 | Mic permission denied | NOT TESTED — BLOCKED |
| F2 | Permission granted after denial | NOT TESTED — BLOCKED |
| F3 | Caller cancels while ringing | NOT TESTED — BLOCKED |
| F4 | Callee declines | NOT TESTED — BLOCKED |
| F5 | Callee busy | NOT TESTED — BLOCKED |
| F6 | Ringing timeout | NOT TESTED — BLOCKED |
| F7 | Network interruption during ringing | NOT TESTED — BLOCKED |
| F8 | Network interruption during active call | NOT TESTED — BLOCKED |
| F9 | Temporary Wi-Fi loss | NOT TESTED — BLOCKED |
| F10 | Mobile-data transition | NOT TESTED — BLOCKED |
| F11 | Background → foreground | NOT TESTED — BLOCKED |
| F12 | Screen lock → unlock | NOT TESTED — BLOCKED |
| F13 | Incoming while backgrounded | NOT TESTED — BLOCKED |
| F14 | Audio route change | NOT TESTED — BLOCKED |
| F15 | Mic interruption | NOT TESTED — BLOCKED |
| F16 | Termination from either side | NOT TESTED — BLOCKED |

## §S — Security/identity: S1–S7 → all NOT TESTED — BLOCKED
## §H — Call history (5 scenarios × 2 devices × server) → all NOT TESTED — BLOCKED

## What WAS verified from this sandbox (code-side, this phase)

| Item | Result |
|------|--------|
| TURN ICE plumbing (manifest `ice` → engines), Android | core/data/feature-calls compile ✅; core+protocol+domain tests 95/95 ✅ (local, Temurin 21) |
| TURN ICE plumbing, iOS | PulseIceOverrideTests added; CI (macOS) result recorded in the W3-HW push |
| Manifest schema doc | `ice` documented in 01-infrastructure §4 |
| Kit executability | capture script syntax-checked (`bash -n`) |

Update this file only with real device evidence; every "NOT TESTED — BLOCKED"
stays until a physical run replaces it.
