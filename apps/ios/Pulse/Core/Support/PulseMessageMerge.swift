import Foundation

/// Delayed HTTP/socket envelopes cannot revive a tombstone or an unopened gate.
/// These monotonic fields are not a total ordering for reactions/pins.
enum PulseMessageMerge {
    /// A broadcast is not an owner-scoped negative receipt: it intentionally
    /// omits private readers. Only retain the current owner's existing proof.
    static func retainOwnSocketReadState(_ current: WireChatMessage?, incoming: WireChatMessage,
                                         membership: String?) -> WireChatMessage {
        guard let membership, !membership.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let current, current.id == incoming.id, current.conversationId == incoming.conversationId,
              !incoming.id.hasPrefix("local_"), current.deletedAt == nil, incoming.deletedAt == nil,
              (current.readByMembershipIds ?? []).contains(membership),
              !(incoming.readByMembershipIds ?? []).contains(membership) else { return incoming }
        var result = incoming
        result.readByMembershipIds = (incoming.readByMembershipIds ?? []) + [membership]
        return result
    }

    /// An action ACK is not a read observation. Keep the current exact proof,
    /// including a canonical negative; never apply this rule to GETs/read ACKs.
    static func retainAcknowledgedReadState(_ current: WireChatMessage?, incoming: WireChatMessage) -> WireChatMessage {
        guard let current, current.id == incoming.id, current.conversationId == incoming.conversationId,
              !incoming.id.hasPrefix("local_") else { return incoming }
        var result = incoming
        result.readByMembershipIds = current.readByMembershipIds
        return result
    }

    /// The edit ACK may advance text without rolling unrelated events backward.
    static func acceptEdit(_ current: WireChatMessage, incoming: WireChatMessage) -> WireChatMessage {
        guard current.id == incoming.id, current.conversationId == incoming.conversationId, current.deletedAt == nil else { return current }
        var patched = current
        patched.content = incoming.content
        patched.editedAt = incoming.editedAt
        return merge(current, incoming: patched)
    }

    static func merge(_ current: WireChatMessage?, incoming: WireChatMessage) -> WireChatMessage {
        if let current, current.id == incoming.id, current.conversationId != incoming.conversationId { return merge(nil, incoming: current) }
        let prior = current.flatMap { $0.id == incoming.id ? $0 : nil }
        var result = incoming
        if let prior, prior.deletedAt != nil, incoming.deletedAt == nil { result = prior }
        if prior?.viewOnce == true { result.viewOnce = true }
        if let expires = prior?.expiresAt, let deadline = PulseFormat.date(expires),
           result.expiresAt == nil || result.expiresAt.flatMap(PulseFormat.date).map({ $0 > deadline }) == true {
            result.expiresAt = expires
        }
        if let viewedAt = prior?.viewedAt {
            result.viewOnce = true
            result.viewedAt = viewedAt
            result.viewedBy = prior?.viewedBy ?? result.viewedBy
        }
        if result.viewedAt != nil { result.viewOnce = true }
        if result.deletedAt != nil { return result.deletedCopy() }
        if let poll = prior?.poll, poll.closed == true {
            result.poll = poll
        }
        if let prior, let oldStamp = prior.editedAt.flatMap(PulseFormat.date),
           oldStamp > (result.editedAt.flatMap(PulseFormat.date) ?? .distantPast) {
            result.content = prior.content
            result.editedAt = prior.editedAt
        }
        if let oldQuote = prior?.replyTo, let quote = result.replyTo {
            result.replyTo = quote.mergingBounds(from: oldQuote)
        }
        return result
    }
}
