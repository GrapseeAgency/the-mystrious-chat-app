# 02 — PHYSICAL ANDROID DEVICE RUNBOOK

App: release APK from the GitHub Release (`Pulse-v0.5.1-native.apk`,
versionCode 14 — the W3-HW build with TURN ICE plumbing; verified
`apksigner verify` + badging). Debug suffix
(`app.pulse.chat.debug`) is acceptable if consistent for the whole phase —
label it in evidence.

## Device prep

```bash
# Physical device: enable Developer options → USB debugging. NO emulator.
adb devices -l                       # exactly one device, state: device
adb install -r Pulse-v0.5.1-native.apk
adb shell settings put global window_animation_scale 0   # optional, stability
```

## Log capture (mandatory for every test case)

```bash
./android-capture.sh start           # wraps: logcat -c + pid-filtered logcat
./android-capture.sh mark "T1 offer sent"   # drops a timestamped marker
./android-capture.sh stop            # writes logs/android-<ts>.log
adb shell screenrecord /sdcard/t1.mp4     # per-case screen video (pull after)
```

What proves what (grep the capture):
- `ManifestEndpoints` — endpoint/ICE adoption at launch.
- `CallEngine` — state-machine transitions, offer/answer/ICE, mute toggles,
  `manifest ice parse failed` (must be ABSENT on manifest devices).
- `AudioRecord|AudioTrack` — capture/playback start/stop (mic release proof:
  AudioRecord start after hangup must NOT exist; stop must).
- `AudioFocus|requestAudioFocus` — focus gain on connect, abandon on hangup.
- `ForegroundService` (microphone type) — started on connect, stopped on end.

## Permission drills (cases F1/F2)

```bash
adb shell pm revoke app.pulse.chat android.permission.RECORD_AUDIO   # F1
adb shell pm grant app.pulse.chat android.permission.RECORD_AUDIO    # F2
# F2 "grant after denial": revoke → attempt call → see honest denied card →
# grant via the app's own flow or the command → call again → must work.
```

## Interruption / lifecycle drills

| Case | Drill |
|------|-------|
| F11 background→foreground | `adb shell input keyevent KEYCODE_HOME` then relaunch |
| F12 screen lock/unlock | `adb shell input keyevent KEYCODE_POWER` ×2 |
| F7/F8 network drop | toggle Airplane mode (or `adb shell svc wifi disable` / `svc data disable`) |
| F13 incoming while backgrounded | put app in background, call from the other device |
| F15 mic interruption | start a native voice-recorder recording during the call (audio-focus arbitration evidence) |
| Audio route | toggle speaker button in-call; plug/unplug wired headset or pair/unpair BT |

## Microphone release proof (acceptance gate item 11)

After every ended call:
```bash
adb shell dumpsys media.audio_flinger | grep -A3 "Input\|record" | head -20
adb shell dumpsys audio | grep -i "focus.*pulse\|players.*pulse" | head
```
Expected: no active input session owned by `app.pulse.chat`; focus abandoned;
FGS stopped (logcat). A screenshot of `dumpsys` output is valid evidence.
