# 02 — PHYSICAL ANDROID DEVICE RUNBOOK (W5-HW)

App: release APK `Pulse-v0.7.0-native.apk` (versionCode 17, sha256
`28067e88…82f5`) from the GitHub Release. Debug suffix
(`app.pulse.chat.debug`) acceptable if consistent for the whole phase —
label it in evidence. NO emulator, ever.

## Device prep

```bash
adb devices -l                       # exactly one device, state: device
adb install -r Pulse-v0.7.0-native.apk
adb shell dumpsys package app.pulse.chat | grep versionName   # 0.7.0-native
```

- Set the public origin via manifest bootstrap or Profile → Connection
  (see `01-infrastructure.md`).
- For FGS reliability during background cases: Settings → Battery → Pulse
  → Unrestricted. Record whether you did this — it is part of the
  environment, and if the FGS is killed without it, that is REAL evidence,
  not a rig problem to hide.

## Log capture (mandatory for every test case)

Reuse the W3-HW capture script from the sibling kit:

```bash
../hw-wave3/android-capture.sh start              # logcat -c + pid-filtered
../hw-wave3/android-capture.sh mark "VR-2 hold"   # timestamped marker
../hw-wave3/android-capture.sh stop               # writes logs/android-<ts>.log
adb shell screenrecord /sdcard/vr2.mp4            # per-case screen video
```

What proves what (grep the capture):
- `AudioRecord|AudioTrack` — capture/playback start/stop. Mic-release
  proof: AudioRecord `startRecording` after leave must NOT exist; a stop
  must.
- `ForegroundService` (microphone type) — `VoiceForegroundService` starts
  on room join with mic, stops on leave.
- Chunker/rx lines — 250 ms chunk cadence while transmitting; silence
  while muted.
- `ManifestEndpoints` — endpoint adoption at launch.

## OS-level mic evidence

- Privacy indicator (mic dot in status bar) during transmitting.
- Settings → Privacy → Privacy dashboard → Microphone — shows
  `app.pulse.chat` timeline; screenshot after leave = mic-release evidence.
- Deep proof after any leave:

```bash
adb shell dumpsys media.audio_flinger | grep -A3 "Input\|record" | head -20
adb shell dumpsys audio | grep -i "players.*pulse\|focus.*pulse" | head
```

Expected: no active input session owned by `app.pulse.chat`; FGS stopped
(logcat).

## Permission drills (ENV-1)

```bash
adb shell pm revoke app.pulse.chat android.permission.RECORD_AUDIO
adb shell pm grant  app.pulse.chat android.permission.RECORD_AUDIO
```

Revoke → attempt PTT → expect the honest mic-denied state (no phantom
"transmitting"), then re-grant (app flow or command) → PTT must work
without reinstall.

## Voice-specific drills

| Drill | How |
|-------|-----|
| PTT hold | press-and-hold the PTT button ≥260 ms, speak, release |
| PTT latch | tap (<260 ms) to latch, tap again to unlatch |
| Mute | mute toggle mid-session — latch must force-drop |
| Background | Home key during voice session — FGS-mic keeps capture (privacy dot stays) |
| Screen lock | power button during session |
| Network drop | Airplane toggle / `adb shell svc wifi disable` |
| Audio route | plug/unplug wired headset, pair/unpair BT, in-surface speaker toggle |
| Captions | captions toggle in the voice surface (persisted pref `voice.captions`) |
