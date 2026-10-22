import Foundation

/// Delayed HTTP/socket envelopes cannot revive a tombstone or an unopened gate.
/// These monotonic fields are not a total ordering for reactions/pins.
enum PulseMessageMerge {
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
        return result
    }
}
