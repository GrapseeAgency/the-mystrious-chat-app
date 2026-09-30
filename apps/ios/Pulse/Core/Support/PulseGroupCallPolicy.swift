import Foundation

// Pulse - GROUP call decision logic (PURE Foundation - unit tested).
//
// Behavioral spec = the web useGroupCallSession hook
// (src/components/chat/group-call-overlay.tsx), mirrored verbatim:
//
//   • Mesh direction is DETERMINISTIC and stateless: between any two
//     members the one with the lexicographically SMALLER id creates the
//     offer (`me < member.id`, web applyRoster :287). No glare, no
//     join-order bookkeeping, safe under simultaneous joins.
//   • Roster diff (web applyRoster :259-277): peers whose id left the
//     roster get their connection closed; arrivals I must offer are the
//     roster members with NO live connection whose id sorts below mine.
//   • The wire member shape is { id, name, color, avatar } (join-ordered,
//     server gcallStatePayload); decode tolerates the web fallbacks
//     ('Someone' / 'emerald' / null avatar).
//
// Kept free of WebRTC/AVFoundation/UIKit types so PulseTests exercises
// the exact production decision path with no device hardware.

/// Pure mesh decisions for the group call engine.
public enum PulseGroupCallPolicy {

    /// Web-verbatim JS string `<`: lexicographic by UTF-16 code units.
    /// Swift's String `<` compares Unicode scalars with normalization
    /// semantics - for the ASCII ids Pulse issues (cuid/uuid) both agree,
    /// but the UTF-16 comparison is the EXACT web rule, so it is pinned here.
    public static func isLowerId(_ a: String, than b: String) -> Bool {
        var ai = a.utf16.makeIterator()
        var bi = b.utf16.makeIterator()
        while true {
            switch (ai.next(), bi.next()) {
            case let (lhs?, rhs?) where lhs != rhs:
                return lhs < rhs
            case (.some, .some):
                continue // equal code units - keep comparing
            case (.none, .some):
                return true // a is a strict prefix of b → a < b
            default:
                return false // b exhausted first or both equal → a >= b
            }
        }
    }

    /// The deterministic offer rule: I create the offer for this pair iff
    /// my id sorts STRICTLY below the remote id (equal ids never offer -
    /// the relay refuses self-targeted signaling anyway).
    public static func shouldOffer(myId: String, remoteId: String) -> Bool {
        guard !myId.isEmpty, !remoteId.isEmpty, myId != remoteId else { return false }
        return isLowerId(myId, than: remoteId)
    }

    /// The roster diff web applyRoster computes on every gcall:state:
    ///   departing - live peer connections whose id is no longer on the
    ///               roster (their pc is closed by the engine);
    ///   toOffer   - roster members (excluding me) with NO live connection
    ///               whose id sorts below mine (deterministic offer rule).
    public struct RosterPlan: Equatable {
        public let departing: [String]
        public let toOffer: [String]

        public init(departing: [String], toOffer: [String]) {
            self.departing = departing
            self.toOffer = toOffer
        }
    }

    /// Pure roster diff. `rosterIds` is the join-ordered roster INCLUDING
    /// me; `connectedPeerIds` is the live peer-connection key set. Order of
    /// the outputs follows the roster order (stable, test-pinned).
    public static func rosterPlan(
        myId: String,
        rosterIds: [String],
        connectedPeerIds: [String]
    ) -> RosterPlan {
        let others = rosterIds.filter { $0 != myId }
        let connected = Set(connectedPeerIds)
        let departing = connectedPeerIds.filter { !others.contains($0) }
        let toOffer = others.filter { memberId in
            !connected.contains(memberId) && shouldOffer(myId: myId, remoteId: memberId)
        }
        return RosterPlan(departing: departing, toOffer: toOffer)
    }

    /// Web leaveCall summary (group-call-overlay.tsx :392):
    /// `You left · M:SS` once a second elapsed, bare `You left` below it.
    public static func leaveSummary(elapsedSec: Int) -> String {
        elapsedSec >= 1 ? "You left · \(CallFormat.duration(elapsedSec))" : "You left"
    }

    /// The 45s local ring guard - group rings have NO server-side timeout
    /// (web banners sit until dismissed), but a stuck CallKit incoming
    /// screen is worse than a missed ring, so the iOS path self-clears.
    public static let ringTimeoutSec: TimeInterval = 45
}

// wire structs (tolerant decode, web fallback parity)

/// One roster member - the wire shape is { id, name, color, avatar }
/// (server gcallStatePayload :655; the ring payload's caller uses the same
/// four keys). Codable so the REST probe decodes it straight from JSON.
public struct PulseGroupCallMember: Codable, Equatable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var color: String
    public var avatar: String?

    public init(id: String, name: String, color: String, avatar: String?) {
        self.id = id
        self.name = name
        self.color = color
        self.avatar = avatar
    }

    /// Tolerant decode from a raw relay payload - web fallbacks
    /// ('Someone' / 'emerald' / null avatar, overlay.tsx :477-482).
    public static func decode(_ raw: [String: Any]) -> PulseGroupCallMember? {
        let id = (raw["id"] as? String) ?? ""
        guard !id.isEmpty else { return nil }
        let name = (raw["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Someone"
        let color = (raw["color"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "emerald"
        let avatar = (raw["avatar"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return PulseGroupCallMember(id: id, name: name, color: color, avatar: avatar)
    }

    /// Roster decode - skips entries without a usable id (web :473-483).
    public static func decodeList(_ raw: Any?) -> [PulseGroupCallMember] {
        guard let array = raw as? [[String: Any]] else { return [] }
        return array.compactMap { decode($0) }
    }
}

/// GET /api/group-call-state response - { callId, kind, startedAt, members }
/// or { members: [] } (honest empty). kind falls back to 'voice' (the route
/// always emits a valid one; the tolerant parse mirrors CallKind).
public struct PulseGroupCallStateSnapshot: Decodable, Equatable, Sendable {
    public var callId: String?
    public var kind: String?
    public var startedAtMs: Int64?
    public var members: [PulseGroupCallMember]

    private enum CodingKeys: String, CodingKey {
        case callId, kind, members
        case startedAt = "startedAt"
    }

    public init(callId: String?, kind: String?, startedAtMs: Int64?, members: [PulseGroupCallMember]) {
        self.callId = callId
        self.kind = kind
        self.startedAtMs = startedAtMs
        self.members = members
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        callId = try? container.decodeIfPresent(String.self, forKey: .callId)
        kind = try? container.decodeIfPresent(String.self, forKey: .kind)
        if let ms = try? container.decodeIfPresent(Int64.self, forKey: .startedAt) {
            startedAtMs = ms
        } else if let secs = try? container.decodeIfPresent(Double.self, forKey: .startedAt) {
            startedAtMs = Int64(secs)
        }
        members = (try? container.decodeIfPresent([PulseGroupCallMember].self, forKey: .members)) ?? []
    }

    /// Tolerant wire kind (unknown/absent → voice, CallKind parity).
    public var resolvedKind: CallKind { CallKind(wireValue: kind) }
}

// gcall:* wire payload builders (C→S, exact relay-validated shapes)

/// Builders for the C→S group-call events. NOTE: unlike call:*, the
/// targeted gcall events carry NO callId from the client - the relay
/// injects the conversation call's id server-side (mini-services
/// pulse-socket gcall:offer/answer/ice handlers). Shapes mirror the web
/// emit payloads verbatim; pinned by PulseGroupCallPolicyTests.
public enum GroupCallWire {
    /// gcall:join { conversationId, kind, user: {id,name,color,avatar} }
    public static func joinPayload(
        conversationId: String,
        kind: CallKind,
        userId: String,
        userName: String,
        userColor: String?,
        userAvatar: String?
    ) -> [String: Any] {
        [
            "conversationId": conversationId,
            "kind": kind.rawValue,
            "user": [
                "id": userId,
                "name": userName,
                "color": userColor ?? "emerald",
                "avatar": userAvatar ?? "",
            ],
        ]
    }

    /// gcall:offer { conversationId, from, to, kind, sdp }
    public static func offerPayload(
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind,
        sdp: String
    ) -> [String: Any] {
        [
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "kind": kind.rawValue,
            "sdp": sdp,
        ]
    }

    /// gcall:answer { conversationId, from, to, sdp }
    public static func answerPayload(
        conversationId: String,
        from: String,
        to: String,
        sdp: String
    ) -> [String: Any] {
        [
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "sdp": sdp,
        ]
    }

    /// gcall:ice { conversationId, from, to, kind, candidate, sdpMid, sdpMLineIndex }
    /// (the relay tolerates the extra `kind`; web sends it).
    public static func icePayload(
        conversationId: String,
        from: String,
        to: String,
        kind: CallKind,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int32?
    ) -> [String: Any] {
        var payload: [String: Any] = [
            "conversationId": conversationId,
            "from": from,
            "to": to,
            "kind": kind.rawValue,
            "candidate": candidate,
        ]
        payload["sdpMid"] = sdpMid ?? NSNull()
        payload["sdpMLineIndex"] = sdpMLineIndex.map { Int($0) } ?? NSNull()
        return payload
    }

    /// gcall:leave { conversationId, from }
    public static func leavePayload(conversationId: String, from: String) -> [String: Any] {
        ["conversationId": conversationId, "from": from]
    }
}
