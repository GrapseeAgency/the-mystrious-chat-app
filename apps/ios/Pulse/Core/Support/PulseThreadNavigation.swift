import Foundation

/// Navigation selects a live reply in this exact thread, not an index in partial history.
enum PulseThreadNavigation {
    static func target(in rows: [WireChatMessage], roomId: String, rootId: String, targetId: String, now: Date = Date()) -> WireChatMessage? {
        rows.first {
            guard $0.id == targetId, $0.conversationId == roomId, $0.parentId == rootId, $0.deletedAt == nil else { return false }
            if let expires = $0.expiresAt { return PulseFormat.date(expires).map { $0 > now } ?? false }
            return true
        }
    }

    /// Offline history is not an alternate route around a server refusal or a
    /// superseded validation. Only an actual transport failure permits this path.
    static func mayUseOfflineCache(after error: Error) -> Bool {
        if let network = error as? URLError {
            return [.notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotFindHost,
                    .cannotConnectToHost, .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff].contains(network.code)
        }
        if let failure = error as? PulseAPIClient.Failure { return failure.kind == .network && failure.status == nil }
        return false
    }

    static func shouldFollowTail(nearTail: Bool, pendingTarget: Bool) -> Bool {
        nearTail && !pendingTarget
    }
}

/// MainActor owns this value in PulseSession. A superseded completion changes no access state.
struct PulseThreadValidationFence {
    private var tickets: [String: UUID] = [:]
    mutating func begin(_ rootId: String) -> UUID {
        let ticket = UUID()
        tickets[rootId] = ticket
        return ticket
    }
    func isCurrent(_ rootId: String, _ ticket: UUID) -> Bool { tickets[rootId] == ticket }
    mutating func finish(_ rootId: String, _ ticket: UUID) {
        if isCurrent(rootId, ticket) { tickets.removeValue(forKey: rootId) }
    }
}

/// A local SQLite cursor includes pending local_ IDs too. It is not a server history cursor.
struct PulseCachedThreadCursor: Equatable {
    let roomId: String
    let rootId: String
    let createdAt: String
    let id: String
}

struct PulseCachedThreadPage {
    let messages: [WireChatMessage]
    let cursor: PulseCachedThreadCursor?
    let hasMore: Bool
}
