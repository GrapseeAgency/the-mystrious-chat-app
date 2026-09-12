# 04 — VOICE ROOMS (VR-1…VR-9)

Fill ACTUAL / PASS-FAIL / EVIDENCE on the device. Expected values are
pre-filled from the shipped implementation (v0.7.0-native). Record format
per the directive: TEST → Android device → iPhone → topology → expected →
actual → PASS/FAIL → evidence.

---

### VR-1 — Join / leave
TEST: Open shared conversation → mic entry → Voice room; then Leave.
→ Android device (A): join; verify roster shows self + peer; Leave button ends membership ("Voice · N live" pill updates).
→ iPhone (B): join; verify roster; leave.
→ Topology: T1, then repeat join once on T2.
→ Expected: join → roster-confirmed JOINED (optimistic JOINING first); both see both; leave → voice:leave, roster updates on the other device, server forces `voice:ptt off` for the leaver. Closing the surface (cover) does NOT leave the room (explicit Leave is the leave path).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### VR-2 — PTT hold
TEST: Press-and-hold PTT ≥260 ms while speaking; release.
→ A: hold PTT, speak "one two three", release.
→ B: observe speaking glow on A; hear audio.
→ Topology: T1 then T2.
→ Expected: transmitting only while held; B sees A's glow via `voice:ptt on/off` echo within ~250 ms; release stops chunk flow; A never receives its own chunks.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### VR-3 — PTT latch
TEST: Tap PTT (<260 ms) to latch; tap again to unlatch.
→ A: tap PTT, wait 3 s (hands-free), tap again.
→ B: same drill in the other direction (B transmits, A observes).
→ Topology: T1.
→ Expected: tap latches ON (glow + audio persists without holding); second tap unlatches (glow clears, audio stops). Both devices, both directions.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### VR-4 — Real microphone capture
TEST: Live speech both directions; inspect cadence + OS mic indicators.
→ A: Android privacy dot ON while transmitting; logcat shows AudioRecord start + 250 ms chunk cadence.
→ B: iPhone orange mic dot; Console shows AVAudioEngine tap + converter (16 kHz mono Int16) + chunker blocks.
→ Topology: T2 (cross-network, real internet).
→ Expected: real speech (not tones) arrives intelligibly at the far end; chunks are 16 kHz/250 ms/4000-sample blocks, seq continuous from 1; server 96 KB cap never hit by normal speech.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### VR-5 — Real remote audio playback
TEST: A→B and B→A playback quality/latency.
→ A: speak; B rates audio (intelligible / clipped / robotic / silent).
→ B: speak; A rates audio.
→ Topology: T1 then T2.
→ Expected: playback via per-peer AudioTrack (Android, USAGE_VOICE_COMMUNICATION) / AVAudioPlayerNode (iOS) with 85 ms pre-roll; audio is intelligible speech, not noise; no cross-bleed between peers; latency noticeable but conversational (85 ms jitter pre-roll + network).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### VR-6 — Mute / unmute
TEST: Mute while transmitting (latched), then unmute.
→ A: latch PTT → engage mute → observe.
→ B: observes glow/audio stop.
→ Topology: T1.
→ Expected: mute force-stops PTT (latch drops, glow clears, chunks stop, OS mic dot may persist only if engine still runs capture but nothing transmits); unmute restores PTT ability; B hears A again after unmute + PTT.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### VR-7 — Reconnect
TEST: Kill network on A mid-session; restore.
→ A: join, start receiving B's audio; airplane ON 10 s; airplane OFF.
→ B: keeps talking after A's recovery.
→ Topology: T3.
→ Expected: A attempts reconnect automatically; on socket recovery re-joins the room and resyncs (2 s resync clamp — status line shows reconnect/syncing states honestly); B's audio resumes without app restart; no duplicate roster entries.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### VR-8 — Rejoin after speaker leaves
TEST: B transmits → B leaves → B rejoins → transmits again.
→ A: listening throughout.
→ B: latch, speak, Leave, rejoin, latch, speak.
→ Topology: T1.
→ Expected: after B's leave the roster is replaced wholesale and A's receiver state resets (seq lastSeq reset — the roster-drop reset), so B's second session transmits from seq 1 and A hears it (no seq blackhole — this was the fixed web defect #2).
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:

### VR-9 — Microphone release
TEST: Leave via every path; verify OS-level mic release.
→ A: leave via Leave button; repeat, then kill app from room; repeat after stage end (ST-7).
→ B: same on iPhone.
→ Topology: T1.
→ Expected: after each leave — Android: AudioRecord stopped (logcat), FGS-mic stopped, privacy dot clears, no input session in `dumpsys media.audio_flinger`; iPhone: engine stops, orange dot clears, Music returns to pre-session route (session restored). No hot mic on either platform.
→ Actual:
→ PASS/FAIL: NOT RUN
→ Evidence:
