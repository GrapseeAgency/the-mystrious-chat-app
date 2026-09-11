# 05 — AUDIO QUALITY EVIDENCE

"Do not merely verify connected." Fill one row per mandatory call (M1/M2)
and per recovery case (F8/F9/F11/F12/F14/F15).

## Per-call recording table

| Field | Value (fill on device) |
|-------|------------------------|
| callId | from logs (must match both sides) |
| time-to-ring | offer sent → remote ring |
| ICE completion | gathering start → pc connected (log timestamps) |
| selected candidate pair | relay (TURN) / host / srflx — from logs |
| audio route at connect | speaker / earpiece / BT |
| route changes | what + when |
| mute events | who + when + counterpart confirmation |
| duration | UI ticker vs server durationSec |
| disconnect reason | hangup / timeout / network / failure |
| two-way audio verdict | both heard / one-way / zero |
| echo | none / audible (describe) |
| latency (subjective) | <300ms / 300-800ms / >800ms |
| reconnects during call | count + log refs |

## Defect taxonomy — explicitly check each, mark NONE or describe

- [ ] One-way audio (A hears B, B hears nothing — or reverse)
- [ ] Zero audio both ways while UI says connected
- [ ] Echo (operator hears their own voice returned)
- [ ] Severe latency (conversation impossible)
- [ ] Repeated reconnect churn mid-call
- [ ] Audio continuing after hangup (either side)
- [ ] Microphone remaining active after termination (Android dumpsys input
      table / iOS other-app recording test)
- [ ] Incorrect speaker routing (button says speaker, sound comes from earpiece)

Any tick = FAIL → call-path fix → rebuild → hardware gate repeats.
