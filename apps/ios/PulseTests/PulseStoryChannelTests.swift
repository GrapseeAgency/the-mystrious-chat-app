import XCTest
@testable import Pulse

/// R1-W2G — story tray + channel directory PURE logic (the audit gap:
/// "iOS has no story/channel tests"). Story side pins the offline expiry
/// filter (StoriesSessionModel.liveGroups) and the ring partition
/// (PulseStoryRing); channel side pins the directory kernels
/// (PulseChannelDirectory) — ChannelsView/ChatsView behaviour without
/// SwiftUI or transport, house style per Wave6LogicTests/StoriesWireTests.
@MainActor
final class PulseStoryChannelTests: XCTestCase {
    // ── story fixtures ───────────────────────────────────────

    private let now: Int64 = 1_700_000_000_000

    private func iso(_ ms: Int64) -> String {
        ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    private func story(id: String, expiresAt: String, viewed: Bool = false) -> WireStoryItem {
        WireStoryItem(
            id: id, kind: "text", imagePath: nil, caption: "story \(id)",
            background: "emerald", createdAt: nil, expiresAt: expiresAt,
            viewCount: 0, viewedByMe: viewed,
        )
    }

    private func group(userId: String, mine: Bool = false, allSeen: Bool? = nil, _ items: [WireStoryItem]) -> WireStoryGroup {
        WireStoryGroup(
            user: WireSender(id: userId, name: userId, username: nil, color: "emerald", avatar: nil),
            mine: mine,
            allSeen: allSeen,
            stories: items,
        )
    }

    // ── story expiry filtering (D2 tray parity, offline arithmetic) ──

    func testLiveGroupsDropExpiredStoriesButKeepLiveOnes() {
        let feed = [
            group(userId: "alice", [
                story(id: "dead", expiresAt: iso(now - 1)), // expired a millisecond ago
                story(id: "edge", expiresAt: iso(now)), // expiring exactly now ⇒ gone (strict >)
                story(id: "live", expiresAt: iso(now + 60_000)),
            ]),
        ]
        let live = StoriesSessionModel.liveGroups(feed, nowMs: now)
        XCTAssertEqual(live.count, 1)
        XCTAssertEqual(live.first?.stories?.compactMap { $0.id }, ["live"])
    }

    func testLiveGroupsDropGroupsLeftEmptyAndPreserveFeedOrder() {
        let feed = [
            group(userId: "alice", [story(id: "a-dead", expiresAt: iso(now - 1))]),
            group(userId: "bob", [story(id: "b-live", expiresAt: iso(now + 60_000))]),
            group(userId: "carol", [story(id: "c-live", expiresAt: iso(now + 120_000))]),
        ]
        let live = StoriesSessionModel.liveGroups(feed, nowMs: now)
        // alice's group vanished entirely; bob → carol keeps feed order.
        XCTAssertEqual(live.compactMap { $0.user?.id }, ["bob", "carol"])
    }

    func testLiveGroupsKeepSeenStateUntouchedOnSurvivors() {
        // Expiry filtering must not disturb the ring's seen-state flags.
        let feed = [
            group(userId: "alice", allSeen: false, [
                story(id: "dead", expiresAt: iso(now - 1), viewed: true),
                story(id: "unseen", expiresAt: iso(now + 60_000), viewed: false),
            ]),
        ]
        let live = StoriesSessionModel.liveGroups(feed, nowMs: now)
        XCTAssertEqual(live.first?.allSeen, false)
        XCTAssertEqual(live.first?.stories?.first?.viewedByMe, false)
    }

    // ── story ring partition (chats tray row) ────────────────

    func testRingPartitionSplitsOwnGroupAndKeepsFeedOrderForOthers() {
        let feed = [
            group(userId: "bob", [story(id: "b0", expiresAt: iso(now + 60_000))]),
            group(userId: "me", mine: true, [story(id: "m0", expiresAt: iso(now + 60_000))]),
            group(userId: "carol", [story(id: "c0", expiresAt: iso(now + 60_000))]),
        ]
        XCTAssertEqual(PulseStoryRing.mine(feed)?.user?.id, "me")
        XCTAssertEqual(PulseStoryRing.others(feed).compactMap { $0.user?.id }, ["bob", "carol"])
    }

    func testRingPartitionWithoutOwnStoryLeavesOthersUntouched() {
        let feed = [
            group(userId: "bob", [story(id: "b0", expiresAt: iso(now + 60_000))]),
            group(userId: "carol", [story(id: "c0", expiresAt: iso(now + 60_000))]),
        ]
        XCTAssertNil(PulseStoryRing.mine(feed))
        XCTAssertEqual(PulseStoryRing.others(feed).count, 2)
    }

    // ── channel directory kernels (ChannelsView parity) ─────

    private func channel(
        id: String,
        isSubscribed: Bool? = nil,
        unread: Bool? = nil,
        memberCount: Int? = nil,
        description: String? = nil,
        preview: String? = nil,
    ) -> WireChannelSummary {
        WireChannelSummary(
            id: id, conversationId: "conv-\(id)", name: "Channel \(id)",
            description: description, createdAt: nil, memberCount: memberCount,
            isSubscribed: isSubscribed, unread: unread, preview: preview, photo: nil,
        )
    }

    func testChannelPartitionPutsNilMembershipInDiscover() {
        let feed = [
            channel(id: "a", isSubscribed: true),
            channel(id: "b", isSubscribed: false),
            channel(id: "c", isSubscribed: nil), // unknown ⇒ Discover
        ]
        XCTAssertEqual(PulseChannelDirectory.subscribed(feed).map(\.id), ["a"])
        XCTAssertEqual(PulseChannelDirectory.discover(feed).map(\.id), ["b", "c"])
    }

    func testChannelUnreadCountOnlyCountsTrueFlags() {
        let feed = [
            channel(id: "a", unread: true),
            channel(id: "b", unread: false),
            channel(id: "c", unread: nil),
            channel(id: "d", unread: true),
        ]
        XCTAssertEqual(PulseChannelDirectory.unreadCount(feed), 2)
        XCTAssertEqual(PulseChannelDirectory.unreadCount([]), 0)
    }

    func testSubscriberCountIsSingularAtOneAndPluralOtherwise() {
        XCTAssertEqual(PulseChannelDirectory.subscriberCount(1), "1 subscriber")
        XCTAssertEqual(PulseChannelDirectory.subscriberCount(2), "2 subscribers")
        XCTAssertEqual(PulseChannelDirectory.subscriberCount(0), "0 subscribers")
        XCTAssertEqual(PulseChannelDirectory.subscriberCount(nil), "0 subscribers")
    }

    func testChannelBlurbPrefersDescriptionThenPreviewThenCopy() {
        XCTAssertEqual(
            PulseChannelDirectory.blurb(channel(id: "a", description: "  real  ", preview: "prev")),
            "  real  ", // a non-blank description wins verbatim
        )
        XCTAssertEqual(
            PulseChannelDirectory.blurb(channel(id: "b", description: "   ", preview: "preview text")),
            "preview text", // a blank description falls through to the preview
        )
        XCTAssertEqual(
            PulseChannelDirectory.blurb(channel(id: "c", description: nil, preview: nil)),
            "No description yet", // the verbatim empty copy
        )
    }
}
