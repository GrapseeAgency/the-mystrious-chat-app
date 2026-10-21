import Foundation

/// Display names and titles are not identities. A cached direct chat must have
/// exactly the selected pair, not a similarly named person or a group.
enum PulseConversationNavigation {
    static func directConversation(in rows: [WireConversationSummary], viewerId: String, peerId: String) -> WireConversationSummary? {
        guard !viewerId.isEmpty, !peerId.isEmpty, viewerId != peerId else { return nil }
        let expected: Set<String> = [viewerId, peerId]
        return rows.first {
            !$0.isGroup && $0.isSelf != true && !$0.id.isEmpty && $0.members.count == 2 &&
                Set($0.members.map(\.id)) == expected
        }
    }

    /// Validate the acknowledged identity before a screen navigates or starts a call.
    static func create(viewerId: String, memberIds: [String], isGroup: Bool,
                       request: () async throws -> WireConversationSummary) async throws -> WireConversationSummary {
        try Task.checkCancellation()
        let expected = Set(memberIds + [viewerId])
        guard !viewerId.isEmpty, memberIds.allSatisfy({ !$0.isEmpty }),
              isGroup ? expected.count >= 3 : expected.count == 2 else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Choose the people for this chat again.")
        }
        let created = try await request()
        try Task.checkCancellation()
        guard !created.id.isEmpty, created.isGroup == isGroup, created.isSelf != true,
              created.members.count == expected.count, Set(created.members.map(\.id)) == expected else {
            throw PulseAPIClient.Failure(kind: .validation,
                message: "The returned chat didn't match your selection. Refresh Chats before retrying.")
        }
        return created
    }


    static func selfConversation(in rows: [WireConversationSummary], viewerId: String) -> WireConversationSummary? {
        guard !viewerId.isEmpty else { return nil }
        return rows.first {
            !$0.id.isEmpty && !$0.isGroup && $0.isSelf == true &&
                $0.members.count == 1 && $0.members.first?.id == viewerId
        }
    }

    static func openSelf(viewerId: String, cached: [WireConversationSummary],
                         request: () async throws -> WireConversationSummary) async throws -> WireConversationSummary {
        try Task.checkCancellation()
        guard !viewerId.isEmpty else {
            throw PulseAPIClient.Failure(kind: .validation, message: "A notebook owner is required.")
        }
        if let saved = selfConversation(in: cached, viewerId: viewerId) { return saved }
        let created = try await request()
        try Task.checkCancellation()
        guard selfConversation(in: [created], viewerId: viewerId) != nil else {
            throw PulseAPIClient.Failure(kind: .validation, message: "The returned notebook didn't match your account.")
        }
        return created
    }

}
