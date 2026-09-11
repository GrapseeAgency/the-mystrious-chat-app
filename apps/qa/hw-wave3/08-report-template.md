# PULSE — WAVE 3-HW COMPLETION REPORT (template — fill on device)

Status when unfilled: **NOT EXECUTABLE — NO HARDWARE IN SANDBOX.** This file
is the exact skeleton the operator fills; every field is mandatory.

---

## 1. Devices & topology
- Android: <exact model>, Android <version>, build <APK versionCode/sha256>
- iPhone: <exact model>, iOS <version>, app build <commit/tag>
- Network topology: <per-run matrix from 01-infrastructure §6>
- TURN verification: <5.3/5.4 outputs + selected-candidate-pair proof>

## 2. Mandatory call tests
- M1 Android → iPhone: <per-step table incl. two-way audio verdict>
- M2 iPhone → Android: <same>

## 3. Audio evidence
- <05-audio-quality table, one per call>
- ICE completion times, selected pairs, route changes, mute confirmations

## 4. Failure matrix F1–F16
- <one template block per case from 04-test-matrix>

## 5. Security / identity (S1–S7)
- <log + server JSON excerpts>

## 6. Call history (per scenario × both devices × server)
- <07 table filled with real callIds>

## 7. Fixes performed during the phase
- <each: symptom → root cause → fix → rebuild → re-run result>

## 8. Final release versions
- Android: <tag, versionCode, sha256>
- iOS: <tag/build>

## 9. Remaining blockers / known limitations carried into the record
- <honest list; known so far: no CallKit/VoIP push, iOS ring foreground-only>

## 10. FINAL ACCEPTANCE GATE
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

**Only when all 11 are checked with evidence: Wave 3 may be declared
COMPLETE. Otherwise the verdict line stays: CODE/CI VERIFIED + HARDWARE
VERIFICATION REQUIRED.**
