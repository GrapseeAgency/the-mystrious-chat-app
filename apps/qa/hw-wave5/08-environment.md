# 08 — ENVIRONMENT BATTERY (ENV-1…ENV-7)

Cross-cutting drills. Run each on BOTH devices unless the case says
otherwise. Expected values from the shipped implementation.

---

### ENV-1 — Permission denial / re-grant
TEST: Deny mic; attempt voice; re-grant.
→ A: `adb shell pm revoke app.pulse.chat android.permission.RECORD_AUDIO` → join room / PTT.
→ B: Settings → Pulse → Microphone OFF → join room / PTT.
→ Topology: T1.
→ Expected: honest denied state (clear copy, no phantom "transmitting", no crash); after re-grant (app flow or OS settings, no reinstall) PTT works. First-run prompt path also verified (fresh install on B).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ENV-2 — Audio route changes
TEST: Change routes mid-session.
→ A: plug/unplug wired headset; pair/unpair BT; toggle speaker (RoomAudioRouter).
→ B: BT pair/unpair; speaker toggle; record route changes in Console (AVAudioSession route line).
→ Topology: T1.
→ Expected: audio continues on the new route without app restart; no duplicated audio (both routes simultaneously); after leave, session restored to the pre-session route (Music test).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ENV-3 — Background / foreground
TEST: Background mid-voice-session; return.
→ A (Android): Home during transmission (FGS-mic should keep capture — privacy dot stays; receiver keeps playing); return.
→ B (iPhone): Home during transmission — EXPECTED (documented limitation): engine suspends by OS policy (no UIBackgroundModes audio); on return honest re-sync; if B keeps transmitting in background, record as finding.
→ Topology: T1.
→ Expected: per-platform behaviour above; on return both show correct roster, no ghost transmitting, no crash.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ENV-4 — Screen lock / unlock
TEST: Lock mid-session; unlock.
→ A: power button 30 s → unlock.
→ B: lock 30 s → unlock.
→ Topology: T1.
→ Expected: Android — FGS keeps the session alive (mic dot persists while transmitting); iPhone — lock suspends per OS policy (same class of behaviour as ENV-3; record ACTUAL); on unlock both recover to correct state; screen-off does not kill the Android receiver.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ENV-5 — Network interruption / recovery
TEST: Hard network cut during: voice session, stage session, space session.
→ A: airplane ON 10 s / OFF during each room type.
→ B: witness device each time.
→ Topology: T3.
→ Expected: voice — auto rejoin + 2 s resync; stage — 2500 ms missing-me resync restores role/membership; space — reconnect attempts → honest error after 6 failures, Retry re-joins; no duplicate roster entries anywhere after recovery.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ENV-6 — Duplicate socket / session behaviour
TEST: Force-kill the app mid-session; relaunch and rejoin quickly.
→ A: force-stop (`adb shell am force-stop app.pulse.chat`) while transmitting latched; relaunch; rejoin same room.
→ B: kill app from app-switcher while latched; relaunch; rejoin.
→ Topology: T1.
→ Expected: server tears the dead socket down (forced `voice:ptt off`, roster prune — relay-verified behaviour) so B never sees a ghost transmitting A; the relaunched client joins fresh (seq from 1) and audio works; identity gates reject anything spoofed (relay-verified: chunks with wrong userId are dropped).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### ENV-7 — Microphone release after leaving (all paths)
TEST: Leave via every known path; verify OS-level release.
→ Paths: Leave button · stage end (ST-7) · surface close (iOS cover close — voice survives close, so ALSO verify the explicit leave afterwards) · force-kill from the room.
→ A + B both devices, every path.
→ Topology: T1.
→ Expected: after each path — Android: FGS stopped, AudioRecord stopped, privacy dot clears, dumpsys shows no pulse input session; iPhone: engine stopped, orange dot clears, session restored. NO hot mic anywhere. (Duplicate of VR-9 with the full path matrix — keep both records.)
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:
