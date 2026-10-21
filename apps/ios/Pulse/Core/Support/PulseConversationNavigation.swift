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
}
