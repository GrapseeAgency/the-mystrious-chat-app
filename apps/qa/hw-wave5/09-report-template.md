# 09 — PULSE — WAVE 5-HW VALIDATION REPORT TEMPLATE

Fill every field with device evidence. An empty field means the phase is
not done. Do not convert any NOT TESTED row into PASS without evidence.

```markdown
# PULSE — WAVE 5-HW REAL DEVICE VALIDATION REPORT

Date: ____
Operator: ____
Code under test: v0.7.0-native (versionCode 17) — verified on device: Android ____, iPhone ____
Gateway: ____ (REST HTTPS ☐ / WSS ☐ / transcribe route ☐)   TURN: N/A for W5 room media ☐ (see 01 §1)

## 1. Infrastructure
| Check | Result | Evidence |
|-------|--------|----------|
| REST over public HTTPS | | |
| WSS handshake | | |
| In-app endpoint adoption (both devices) | | |
| Install proof versionCode 17 (both devices) | | |
| TURN allocation (optional — W3-HW only) | | |

## 2. Voice rooms (VR-1…VR-9)
| ID | Case | A (Android model/OS) | B (iPhone/iOS) | Topology | Expected ✔ | Actual | PASS/FAIL | Evidence |
|----|------|----------------------|----------------|----------|------------|--------|-----------|----------|
| VR-1 | join/leave | | | | | | | |
| VR-2 | PTT hold | | | | | | | |
| VR-3 | PTT latch | | | | | | | |
| VR-4 | real mic capture | | | | | | | |
| VR-5 | real remote playback | | | | | | | |
| VR-6 | mute/unmute | | | | | | | |
| VR-7 | reconnect | | | | | | | |
| VR-8 | rejoin after speaker leaves | | | | | | | |
| VR-9 | mic release | | | | | | | |

## 3. Stage (ST-1…ST-9)
| ID | Case | A | B | Topology | Actual | PASS/FAIL | Evidence |
|----|------|---|---|----------|--------|-----------|----------|
| ST-1 | host | | | | | | |
| ST-2 | listener | | | | | | |
| ST-3 | raise hand | | | | | | |
| ST-4 | approve (host-only gate) | | | | | | |
| ST-5 | speaker promotion | | | | | | |
| ST-6 | host mute | | | | | | |
| ST-7 | end stage | | | | | | |
| ST-8 | remote state propagation | | | | | | |
| ST-9 | real audio both directions | | | | | | |

## 4. Space (SP-1…SP-6)
| ID | Case | A | B | Topology | Actual | PASS/FAIL | Evidence |
|----|------|---|---|----------|--------|-----------|----------|
| SP-1 | join/leave | | | | | | |
| SP-2 | avatar movement | | | | | | |
| SP-3 | realtime position propagation | | | | | | |
| SP-4 | proximity behaviour | | | | | | |
| SP-5 | reconnect | | | | | | |
| SP-6 | background/foreground | | | | | | |

## 5. Captions (CAP-1…CAP-5)
| ID | Case | A | B | Topology | Actual | PASS/FAIL | Evidence |
|----|------|---|---|----------|--------|-----------|----------|
| CAP-1 | mic capture | | | | | | |
| CAP-2 | WAV window generation | | | | | | |
| CAP-3 | transcription request | | | | | | |
| CAP-4 | caption display + persistence | | | | | | |
| CAP-5 | failure behaviour | | | | | | |

## 6. Environment (ENV-1…ENV-7)
| ID | Case | A | B | Topology | Actual | PASS/FAIL | Evidence |
|----|------|---|---|----------|--------|-----------|----------|
| ENV-1 | permission denial/re-grant | | | | | | |
| ENV-2 | audio route changes | | | | | | |
| ENV-3 | background/foreground | | | | | | |
| ENV-4 | screen lock/unlock | | | | | | |
| ENV-5 | network interruption/recovery | | | | | | |
| ENV-6 | duplicate socket/session | | | | | | |
| ENV-7 | mic release after leaving (all paths) | | | | | | |

## 7. Final status (four tiers — mandatory)
| Area | CODE/CI VERIFIED | RELAY VERIFIED | HARDWARE VERIFIED | HARDWARE BLOCKED |
|------|------------------|----------------|-------------------|------------------|
| Voice rooms | ☐ pre-existing | ☐ pre-existing (20/20 E2E) | ☐ | ☐ |
| Stage | ☐ pre-existing | ☐ pre-existing | ☐ | ☐ |
| Space | ☐ pre-existing | ☐ pre-existing | ☐ | ☐ |
| Captions | ☐ pre-existing | ☐ (transcribe route E2E) | ☐ | ☐ |
| Environment battery | n/a | partial | ☐ | ☐ |

## 8. Defects found (fix ONLY the failing voice-path issue, rebuild, re-run gate)
| # | Case | Platform | Description | Evidence | Fix commit | Re-verified |
|---|------|----------|-------------|----------|------------|-------------|

## 9. Gate verdict
Wave 5 hardware validation: PASS / FAIL (circle)
Wave 5 COMPLETE: ☐ (only when real microphone/audio behaviour is proven
on physical devices) · Wave 3-HW remains OPEN · Wave 6 NOT started.
```
