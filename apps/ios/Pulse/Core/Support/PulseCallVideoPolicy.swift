import Foundation

// ─────────────────────────────────────────────────────────────
// Pulse — Wave R1-W2D video decision logic (PURE Foundation — unit tested).
//
// Behavioral spec = the web `useCallSession` hook:
//   • the wire video flag is the call's `kind` field ("voice" | "video",
//     src/lib/call-types.ts:15) — not a separate boolean;
//   • a wanted VIDEO call degrades to VOICE BEFORE the offer when no usable
//     camera exists (`acquireMedia` fallback, call-overlay.tsx:262-282) —
//     the offer kind always carries the ACTUAL kind;
//   • the callee additionally requires the offer SDP to actually declare a
//     usable `m=video` line (a voice offer must never open the camera).
//
// Kept free of WebRTC/AVFoundation types so PulseTests exercises the exact
// production decision path with no device hardware (the capture itself
// stays a hardware-gated Wave 3-HW item).
// ─────────────────────────────────────────────────────────────

/// SDP media-line inspection for the video decision (pure string logic).
public enum PulseCallSdp {
    /// True when the SDP declares at least one USABLE video m-section:
    /// `m=video` with a non-zero port and no `a=inactive` inside the section.
    /// Port 0 = the peer rejected the m-line; `a=inactive` = the peer paused
    /// it. Handles CRLF and LF line endings; malformed SDP degrades honestly
    /// to "no video".
    public static func hasVideo(_ sdp: String?) -> Bool {
        guard let sdp, !sdp.isEmpty else { return false }
        var sectionIsVideo = false
        var sectionRejected = false
        var found = false

        func flushSection() {
            if sectionIsVideo && !sectionRejected { found = true }
            sectionIsVideo = false
            sectionRejected = false
        }

        // NSString-bridge line splitting (components over closure-based split —
        // the CI runner's toolchain miscompiled the Character==literal closure
        // predicates here, and the bridge path is equivalent + faster anyway).
        // components(separatedBy:) with BOTH chars treats every CR and LF as a
        // boundary, so CRLF boundaries produce empty pieces we filter below.
        for rawLine in sdp.components(separatedBy: CharacterSet(charactersIn: "\r\n")) where !rawLine.isEmpty {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("m=") {
                flushSection()
                let tokens = line.components(separatedBy: " ").filter { !$0.isEmpty }
                // m=<media> <port> <proto> <fmt> ... — tokens[0] is the full
                // "m=video" token; tokens[1] is the port (0 = rejected).
                if tokens.count >= 3, tokens[0] == "m=video" {
                    sectionIsVideo = true
                    sectionRejected = (Int(tokens[1]) ?? -1) == 0
                }
            } else if sectionIsVideo, line == "a=inactive" {
                sectionRejected = true
            }
        }
        flushSection()
        return found
    }
}

/// Local capture profile shared by the engine + adapter (web getUserMedia
/// ideal: `{ width: { ideal: 1280 }, height: { ideal: 720 } }` at 30fps).
public enum CallVideoConstants {
    public static let targetWidth = 1280
    public static let targetHeight = 720
    public static let targetFps = 30
}

/// Camera gating for outgoing/incoming video calls (web acquireMedia parity).
public enum PulseCallVideoPolicy {
    /// The kind the OFFER/WIRE actually carries: a wanted VIDEO call with no
    /// usable camera degrades to VOICE before the ring opens (the engine's
    /// call kind is immutable per call, so this runs pre-dispatch).
    public static func resolveOutgoingKind(_ wanted: CallKind, cameraCapable: Bool) -> CallKind {
        wanted == .video && !cameraCapable ? .voice : wanted
    }

    /// Should THIS device attach its local camera?
    ///   • CALLER — the engine resolved the wire kind at startOutgoing, so
    ///     attach iff kind==video (offerSdp may be nil pre-offer).
    ///   • CALLEE — additionally require the offer SDP to declare m=video:
    ///     a wire 'video' kind with a rejected/absent video m-line (caller
    ///     fell back mid-prompt) must not open the camera.
    /// [cameraCapable] gates both: permission-denied / no-device ⇒ audio-only.
    public static func shouldAttachVideo(
        kind: CallKind,
        offerSdp: String?,
        cameraCapable: Bool
    ) -> Bool {
        kind == .video && PulseCallSdp.hasVideo(offerSdp) && cameraCapable
    }

    /// Callee-side convenience when the SDP check already ran (kind + camera).
    public static func shouldAttachVideo(kind: CallKind, cameraCapable: Bool) -> Bool {
        kind == .video && cameraCapable
    }
}
