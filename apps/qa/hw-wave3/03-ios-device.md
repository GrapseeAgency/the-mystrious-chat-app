# 03 — PHYSICAL IPHONE RUNBOOK

App: W3-HW build of `apps/ios` (Xcode → Pulse target → physical iPhone;
TestFlight also acceptable). NO simulator, ever. Record the exact iOS version.

## Device prep

- Install via Xcode (`xcodebuild -project … -destination 'id=<UDID>'` or IDE
  Run), trust the developer profile, sign in, set the server origin if the
  manifest hasn't propagated: Settings → Connection.
- Console capture (mandatory for every case):
  ```bash
  # Mac with the device connected:
  log stream --device --process Pulse --level debug --style compact \
      > pulse-ios-$(date +%H%M%S).log
  ```
  Plus Control-Center screen recording per case (or QuickTime device movie).
- Note the Pulse process logs to grep: state-machine transitions, ICE/PC
  state changes, `PulseEndpoints` adoption line, AVAudioSession
  configuration/restoration lines.

## Permission drills (cases F1/F2)

- F1: Settings → Pulse → Microphone → OFF → attempt call → expected: the
  honest mic-denied card, no phantom "connecting", no crash.
- F2: from that denied state, re-enable Microphone (or app-driven grant
  prompt) → call again → must connect. Evidence: Console log + screen video.

## AVAudioSession evidence (Wave 3 §6 item 2)

During a connected call, capture and record:
1. Category/mode at connect: `.playAndRecord` + `.voiceChat` (log line or
   `调试` print via Console — the engine logs its configuration).
2. Route switch: in-call speaker button → `overrideOutputAudioPort` path;
   verify with `adb`-equivalent: the audible output change (operator
   observation + video) and the session route log line.
3. Restoration after hangup: play Music → audio must come out of the SAME
   route as before the call (session restored, not left in playAndRecord).
4. Interruption (case F15): start Siri or a Timer alarm mid-call →
   interruption began/ended logged, call either resumes or ends honestly —
   record which.

## iOS-specific incoming-call honesty

Known Wave 3 limitation (documented, not a defect to hide): incoming ring
surfaces only while the app is foregrounded (no CallKit/VoIP push). For case
F13 record the ACTUAL behaviour on the device — if the ring does not surface
while backgrounded, that is a FAIL against the directive's expectation and a
call-path finding, not something to mark around.
