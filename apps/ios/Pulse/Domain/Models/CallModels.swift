import Foundation

// ─────────────────────────────────────────────────────────────
// Pulse — Wave 3 native calls: shared domain model.
//
// Mirrors the authoritative wire contract (src/lib/call-types.ts +
// packages/protocol/src/contracts.ts). The relay (mini-services/
// pulse-socket/index.ts) is the enforcement point; where contracts.ts
// drifted from the wire (CallHangupPayload.durationMs vs the actual
// durationSec field the relay emits) we FOLLOW THE WIRE — durationSec.
// ─────────────────────────────────────────────────────────────

/// Audio-only vs camera call (wire CallKind). Native ships the full voice
/// path; kind 'video' is accepted/relayed on the wire but the native UI is
/// an audio-call UI (documented Wave 3 limitation — no camera capture).
public enum CallKind: String, Equatable, Sendable, CaseIterable {
    case voice
    case video

    /// Tolerant wire parse — unknown values fall back to voice (web parity:
    /// `payload.kind === 'video' ? 'video' : 'voice'`).
    public init(wireValue: String?) {
        self = wireValue == CallKind.video.rawValue ? .video : .voice
    }
}

/// Why a ringing call ended without an answer (wire CallCancelReason).
public enum CallCancelReason: String, Equatable, Sendable, CaseIterable {
    case timeout   // 30s server ring timer elapsed (authoritative)
    case cancel    // caller hung up before the callee answered
    case busy      // callee was already ringing / in another call
    case offline   // callee had no live socket when the offer arrived

    /// Tolerant wire parse — unknown strings fall back to .cancel (web parity).
    public init(wireValue: String?) {
        self = CallCancelReason(rawValue: wireValue ?? "") ?? .cancel
    }
}

/// Terminal call-log outcome (wire CallStatus).
public enum CallOutcome: String, Equatable, Sendable, CaseIterable {
    case completed
    case missed
    case declined
}

/// Call direction as seen by THIS viewer.
public enum CallDirection: String, Equatable, Sendable {
    case outgoing
    case incoming
}

/// The person on the other end — rendered from the offer payload (incoming)
/// or the DM conversation/contacts (outgoing). Web CallPeer parity.
public struct CallPeer: Equatable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var color: String?
    public var avatar: String?

    public init(id: String, name: String, color: String? = nil, avatar: String? = nil) {
        self.id = id
        self.name = name
        self.color = color
        self.avatar = avatar
    }
}

/// Everything the state machine + UI need to know about the live call.
public struct CallInfo: Equatable, Sendable {
    public var callId: String
    public var conversationId: String
    public var kind: CallKind
    public var direction: CallDirection
    public var peer: CallPeer

    public init(
        callId: String,
        conversationId: String,
        kind: CallKind,
        direction: CallDirection,
        peer: CallPeer
    ) {
        self.callId = callId
        self.conversationId = conversationId
        self.kind = kind
        self.direction = direction
        self.peer = peer
    }
}

// ── call:* wire envelopes (S→C, tolerant decode from [String: Any]) ──

/// Fields every call:* payload carries (wire CallSignalBase).
public struct CallSignalEnvelope: Equatable, Sendable {
    public let callId: String
    public let conversationId: String
    public let from: String
    public let to: String
    public let kind: CallKind

    public init(
        callId: String,
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind
    ) {
        self.callId = callId
        self.conversationId = conversationId
        self.from = from
        self.to = to
        self.kind = kind
    }

    /// Tolerant decode of the shared base fields from a raw relay payload.
    public static func decode(_ raw: [String: Any]) -> CallSignalEnvelope? {
        let callId = raw["callId"] as? String ?? ""
        let from = raw["from"] as? String ?? ""
        guard !callId.isEmpty, !from.isEmpty else { return nil }
        return CallSignalEnvelope(
            callId: callId,
            conversationId: raw["conversationId"] as? String ?? "",
            from: from,
            to: raw["to"] as? String ?? "",
            kind: CallKind(wireValue: raw["kind"] as? String),
        )
    }
}

/// call:offer — caller → callee. Caller profile fields let the incoming UI
/// render before any REST fetch (wire CallOfferPayload).
public struct CallOfferEnvelope: Equatable, Sendable {
    public let base: CallSignalEnvelope
    public let sdp: String
    public let caller: CallPeer

    /// Decode with the web's fallbacks ('Someone' / 'emerald' / nil avatar).
    public static func decode(_ raw: [String: Any]) -> CallOfferEnvelope? {
        guard let base = CallSignalEnvelope.decode(raw) else { return nil }
        let sdp = raw["sdp"] as? String ?? ""
        guard !sdp.isEmpty else { return nil }
        let name = (raw["callerName"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Someone"
        let color = (raw["callerColor"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "emerald"
        let avatar = (raw["callerAvatar"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return CallOfferEnvelope(
            base: base,
            sdp: sdp,
            caller: CallPeer(id: base.from, name: name, color: color, avatar: avatar),
        )
    }
}

/// call:answer — callee → caller (wire CallAnswerPayload).
public struct CallAnswerEnvelope: Equatable, Sendable {
    public let base: CallSignalEnvelope
    public let sdp: String

    public static func decode(_ raw: [String: Any]) -> CallAnswerEnvelope? {
        guard let base = CallSignalEnvelope.decode(raw) else { return nil }
        let sdp = raw["sdp"] as? String ?? ""
        guard !sdp.isEmpty else { return nil }
        return CallAnswerEnvelope(base: base, sdp: sdp)
    }
}

/// call:ice — thin ICE candidate triple (JSON-safe everywhere; the relay
/// validates `candidate` as a STRING — contracts.ts drift says object, the
/// wire says string, we follow the wire).
public struct CallIceEnvelope: Equatable, Sendable {
    public let base: CallSignalEnvelope
    public let candidate: String
    public let sdpMid: String?
    public let sdpMLineIndex: Int32?

    public static func decode(_ raw: [String: Any]) -> CallIceEnvelope? {
        guard let base = CallSignalEnvelope.decode(raw) else { return nil }
        let candidate = raw["candidate"] as? String ?? ""
        guard !candidate.isEmpty else { return nil }
        let mid = raw["sdpMid"] as? String
        var lineIndex: Int32?
        if let number = raw["sdpMLineIndex"] as? NSNumber {
            lineIndex = Int32(truncating: number)
        } else if let number = raw["sdpMLineIndex"] as? Int {
            lineIndex = Int32(number)
        }
        return CallIceEnvelope(
            base: base,
            candidate: candidate,
            sdpMid: mid,
            sdpMLineIndex: lineIndex,
        )
    }
}

// ── call:* wire payload builders (C→S, exact relay-validated shapes) ──

/// Builders for every C→S call:* event the relay accepts. Kept pure so the
/// wire shape is pinned by unit tests, not just by the live relay.
public enum CallWire {
    /// Correlation id — web parity shape: `call-<base36 ms>-<8 base36 chars>`
    /// (the relay truncates to 64 chars).
    public static func newCallId() -> String {
        let ts = String(Int(Date().timeIntervalSince1970 * 1000), radix: 36)
        let randomness = String(UUID().uuidString.prefix(8)).lowercased()
        return "call-\(ts)-\(randomness)"
    }

    public static func offerPayload(
        callId: String,
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind,
        sdp: String,
        callerName: String,
        callerColor: String,
        callerAvatar: String?
    ) -> [String: Any] {
        var payload: [String: Any] = [
            "callId": callId,
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "kind": kind.rawValue,
            "sdp": sdp,
            "callerName": callerName,
            "callerColor": callerColor,
        ]
        // Relay: non-empty string ≤256 chars → passed through, else null.
        payload["callerAvatar"] = callerAvatar ?? ""
        return payload
    }

    public static func answerPayload(
        callId: String,
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind,
        sdp: String
    ) -> [String: Any] {
        [
            "callId": callId,
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "kind": kind.rawValue,
            "sdp": sdp,
        ]
    }

    public static func icePayload(
        callId: String,
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int32?
    ) -> [String: Any] {
        var payload: [String: Any] = [
            "callId": callId,
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "kind": kind.rawValue,
            "candidate": candidate,
        ]
        if let sdpMid {
            payload["sdpMid"] = sdpMid
        } else {
            payload["sdpMid"] = NSNull()
        }
        if let sdpMLineIndex {
            payload["sdpMLineIndex"] = Int(sdpMLineIndex)
        } else {
            payload["sdpMLineIndex"] = NSNull()
        }
        return payload
    }

    public static func rejectPayload(
        callId: String,
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind
    ) -> [String: Any] {
        [
            "callId": callId,
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "kind": kind.rawValue,
        ]
    }

    /// Caller aborts while ringing (self-cancel → reason 'cancel').
    public static func cancelPayload(
        callId: String,
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind
    ) -> [String: Any] {
        [
            "callId": callId,
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "kind": kind.rawValue,
            "reason": CallCancelReason.cancel.rawValue,
        ]
    }

    /// Either party ends an ACTIVE call. durationSec — the wire field
    /// (relay emits durationSec; contracts.ts durationMs is documented
    /// drift — advisory only, the relay computes its own value anyway).
    public static func hangupPayload(
        callId: String,
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind,
        durationSec: Int
    ) -> [String: Any] {
        [
            "callId": callId,
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "kind": kind.rawValue,
            "durationSec": durationSec,
        ]
    }
}

// ── formatting helpers (web overlay parity) ──────────────────

public enum CallFormat {
    /// Web formatCallDuration — 65 → "1:05", 3675 → "1:01:15".
    public static func duration(_ totalSec: Int) -> String {
        let sec = max(0, totalSec)
        let h = sec / 3600
        let m = (sec % 3600) / 60
        let s = sec % 60
        func pad(_ v: Int) -> String { String(v).count < 2 ? "0\(v)" : String(v) }
        return h > 0 ? "\(h):\(pad(m)):\(pad(s))" : "\(m):\(pad(s))"
    }

    /// Web calls-page formatCallDuration — 45 → "45 sec", 750 → "12 min 30 sec",
    /// 3900 → "1 h 05 min". Empty string for 0 (row renders no duration).
    public static func historyDuration(_ totalSec: Int) -> String {
        let sec = max(0, totalSec)
        guard sec > 0 else { return "" }
        if sec < 60 { return "\(sec) sec" }
        let minutes = sec / 60
        if minutes < 60 {
            let rest = sec % 60
            return rest > 0 ? "\(minutes) min \(rest) sec" : "\(minutes) min"
        }
        let paddedRest = String(minutes % 60).count < 2 ? "0\(minutes % 60)" : "\(minutes % 60)"
        return "\(minutes / 60) h \(paddedRest) min"
    }
}
