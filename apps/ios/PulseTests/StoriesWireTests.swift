import XCTest
@testable import Pulse

/// Wave 4 — Stories wire-parity + pure-engine + store-cache tests.
/// The JSON here is the exact shape GET /api/stories emits (captured from
/// the live route), mirrored by Android's StoriesDtoParityTest.
final class StoriesWireTests: XCTestCase {
    // ── wire decode ──────────────────────────────────────────

    private let feedJSON = """
    {
      "groups": [
        {
          "user": {"id": "u-me", "name": "Me", "username": "me", "color": "emerald"},
          "mine": true,
          "allSeen": false,
          "stories": [
            {"id": "s1", "kind": "text", "imagePath": null, "caption": "first!",
             "background": "violet", "createdAt": "2026-09-12T05:00:00.000Z",
             "expiresAt": "2026-09-13T05:00:00.000Z", "viewCount": 3, "viewedByMe": false,
             "someFutureField": 42}
          ]
        },
        {
          "user": {"id": "u-alice", "name": "Alice", "username": null, "color": "rose"},
          "mine": false,
          "allSeen": false,
          "stories": [
            {"id": "s2", "kind": "image", "imagePath": "abc123.jpg", "caption": "look",
             "background": "emerald", "createdAt": "2026-09-12T04:00:00.000Z",
             "expiresAt": "2026-09-13T04:00:00.000Z", "viewCount": 1, "viewedByMe": false}
          ]
        }
      ]
    }
    """

    private func decodeFeed() throws -> WireStoriesPage {
        try JSONDecoder().decode(WireStoriesPage.self, from: Data(feedJSON.utf8))
    }

    func testFeedDecodeCarriesTheFullRichShape() throws {
        let page = try decodeFeed()
        XCTAssertEqual(page.groups?.count, 2)
        let mine = page.groups?[0]
        XCTAssertEqual(mine?.mine, true)
        let item = try XCTUnwrap(mine?.stories?.first)
        XCTAssertEqual(item.id, "s1")
        XCTAssertEqual(item.kind, "text")
        XCTAssertNil(item.imagePath)
        XCTAssertEqual(item.caption, "first!")
        XCTAssertEqual(item.background, "violet")
        XCTAssertEqual(item.viewCount, 3)
        XCTAssertEqual(item.viewedByMe, false)
        XCTAssertNotNil(item.createdAt)
        XCTAssertNotNil(item.expiresAt)
    }

    func testUnknownWireKeysAreTolerated() throws {
        // "someFutureField" above must not break decode (ignoreUnknownKeys).
        let page = try decodeFeed()
        XCTAssertEqual(page.groups?.first?.stories?.first?.id, "s1")
    }

    func testCreatedEnvelopeDecodes() throws {
        let json = #"{"story":{"id":"new1","kind":"text","caption":"hi","background":"emerald","createdAt":"2026-09-12T05:00:00.000Z","expiresAt":"2026-09-13T05:00:00.000Z","viewCount":0,"viewedByMe":false}}"#
        let created = try JSONDecoder().decode(WireStoryCreated.self, from: Data(json.utf8))
        XCTAssertEqual(created.story?.id, "new1")
        XCTAssertEqual(created.story?.viewCount, 0)
    }

    func testViewCountAndViewersPagesDecode() throws {
        let count = try JSONDecoder().decode(
            WireStoryViewCount.self,
            from: Data(#"{"viewCount":7,"owner":true}"#.utf8),
        )
        XCTAssertEqual(count.viewCount, 7)
        let viewers = try JSONDecoder().decode(
            WireStoryViewersPage.self,
            from: Data(#"{"viewers":[{"userId":"u1","name":"Ada","username":"ada","color":"rose","viewedAt":"2026-09-12T05:01:00.000Z"}]}"#.utf8),
        )
        XCTAssertEqual(viewers.viewers?.first?.name, "Ada")
        XCTAssertEqual(viewers.viewers?.first?.username, "ada")
    }

    // ── relative time (web storyRelativeTime parity) ─────────

    func testRelativeTimeStamps() {
        let now: Int64 = 1_700_000_000_000
        XCTAssertEqual(storyRelativeTime(now - 30_000, now: now), "now")
        XCTAssertEqual(storyRelativeTime(now - 5 * 60_000, now: now), "5m")
        XCTAssertEqual(storyRelativeTime(now - 3 * 3_600_000, now: now), "3h")
        XCTAssertEqual(storyRelativeTime(now - 2 * 86_400_000, now: now), "2d")
        XCTAssertEqual(storyRelativeTime(0, now: now), "")
    }

    func testIsoStampParsing() {
        let ms = storyEpochMs("2026-09-12T05:00:00.000Z")
        XCTAssertGreaterThan(ms, 1_700_000_000_000)
        XCTAssertEqual(storyEpochMs(nil), 0)
        XCTAssertEqual(storyEpochMs("garbage"), 0)
    }
}

/// The pure viewer machine — web-truth semantics + the D2/D3/D6 defect fixes.
final class StoryViewerMachineTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000

    private func story(
        id: String,
        mine: Bool = false,
        viewed: Bool = false,
        expiresAt: String = "2026-09-13T05:00:00.000Z",
        views: Int = 0,
    ) -> WireStoryItem {
        WireStoryItem(
            id: id, kind: "text", imagePath: nil, caption: "story \(id)",
            background: "emerald",
            createdAt: "2026-09-12T04:59:00.000Z", expiresAt: expiresAt,
            viewCount: views, viewedByMe: viewed,
        )
    }

    private func group(userId: String, mine: Bool = false, _ items: [WireStoryItem]) -> WireStoryGroup {
        WireStoryGroup(
            user: WireSender(id: userId, name: userId, username: nil, color: "emerald", avatar: nil),
            mine: mine,
            allSeen: !mine && items.allSatisfy { $0.viewedByMe == true },
            stories: items,
        )
    }

    private func makeMachine(_ groups: [WireStoryGroup], start: String? = nil) -> StoryViewerMachine {
        let m = StoryViewerMachine(startUserId: start)
        m.groupsUpdated(groups, nowMs: now)
        return m
    }

    private func advance(_ m: StoryViewerMachine, byMs: Int64, from base: Int64) {
        m.tick(nowMs: base) // arm the clock
        m.tick(nowMs: base + byMs)
    }

    func testStartsAtRequestedAuthorAndMarksPendingView() {
        let m = makeMachine([
            group(userId: "me", mine: true, [story(id: "s0", mine: true)]),
            group(userId: "alice", [story(id: "a0"), story(id: "a1")]),
            group(userId: "bob", [story(id: "b0")]),
        ], start: "bob")
        XCTAssertEqual(m.state.current?.story.id, "b0")
        XCTAssertEqual(m.state.pendingMark?.storyId, "b0")
    }

    func testOwnStoriesNeverProduceAViewMark() {
        let m = makeMachine([group(userId: "me", mine: true, [story(id: "s0", mine: true)])])
        XCTAssertNil(m.state.pendingMark)
    }

    func testEmptyFirstFeedAutoCloses() {
        let m = makeMachine([])
        XCTAssertTrue(m.state.dismissed)
    }

    func testTicksAccrueAndAutoAdvanceAt5000ms() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0"), story(id: "a1")])])
        advance(m, byMs: 2_500, from: now + 10)
        XCTAssertEqual(m.state.elapsedMs, 2_500)
        advance(m, byMs: 2_500, from: now + 2_510)
        XCTAssertEqual(m.state.current?.story.id, "a1")
        XCTAssertEqual(m.state.elapsedMs, 0)
    }

    func testHoldPausesAndResumesFromElapsed() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0"), story(id: "a1")])])
        advance(m, byMs: 2_000, from: now + 10)
        m.holdStart(nowMs: now + 2_100)
        XCTAssertTrue(m.state.paused)
        m.tick(nowMs: now + 4_100)
        m.tick(nowMs: now + 6_100)
        XCTAssertEqual(m.state.elapsedMs, 2_000) // frozen during hold
        m.holdEnd(nowMs: now + 6_200)
        advance(m, byMs: 2_000, from: now + 6_200)
        XCTAssertEqual(m.state.elapsedMs, 4_000) // resumed, not restarted
        m.tick(nowMs: now + 9_200) // +1000 → ≥5000 → advance
        XCTAssertEqual(m.state.current?.story.id, "a1")
    }

    func testCrossesGroupsAndClosesAfterLast() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0")]), group(userId: "bob", [story(id: "b0")])])
        m.tapNext()
        XCTAssertEqual(m.state.current?.story.id, "b0")
        m.tapNext()
        XCTAssertTrue(m.state.dismissed) // past the end → close
    }

    func testTapPrevNoOpsAtFirst() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0")]), group(userId: "bob", [story(id: "b0")])])
        m.tapPrev()
        XCTAssertEqual(m.state.current?.story.id, "a0")
        XCTAssertFalse(m.state.dismissed)
    }

    func testDragDismissIsTerminal() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0")]), group(userId: "bob", [story(id: "b0")])])
        m.dragDismiss()
        m.tapNext()
        XCTAssertTrue(m.state.dismissed)
    }

    func testD2ExpiredStoriesFilteredOffline() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0", expiresAt: "2026-09-12T05:00:30.000Z"), story(id: "a1")])])
        // 10 minutes later: a0's expiry (now+30s) has passed.
        m.groupsUpdated(
            [group(userId: "alice", [story(id: "a0", expiresAt: "2026-09-12T05:00:30.000Z"), story(id: "a1")])],
            nowMs: now + 600_000,
        )
        XCTAssertEqual(m.state.flat.map(\.id), ["a1"])
        XCTAssertEqual(m.state.current?.story.id, "a1")
    }

    func testD3VanishedStoryAutoAdvancesToNearestSurvivor() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0"), story(id: "a1"), story(id: "a2")])])
        m.tapNext() // a1
        m.groupsUpdated([group(userId: "alice", [story(id: "a0"), story(id: "a2")])], nowMs: now)
        XCTAssertEqual(m.state.current?.story.id, "a2")
        XCTAssertEqual(m.state.elapsedMs, 0)
    }

    func testRefreshKeepsPositionAndElapsedForSameStory() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0"), story(id: "a1")])])
        advance(m, byMs: 1_500, from: now + 10)
        m.viewMarkOk(storyId: "a0", viewCount: 3)
        m.groupsUpdated(
            [group(userId: "alice", [story(id: "a0", viewed: true, views: 3), story(id: "a1")])],
            nowMs: now + 20_000,
        )
        XCTAssertEqual(m.state.current?.story.id, "a0")
        XCTAssertEqual(m.state.elapsedMs, 1_500)
        XCTAssertNil(m.state.pendingMark)
    }

    func testD6ViewMarkRetriesOnceThenGivesUp() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0")])])
        XCTAssertEqual(m.state.pendingMark?.attempt, 0)
        m.viewMarkFailed(storyId: "a0")
        XCTAssertEqual(m.state.pendingMark?.attempt, 1)
        m.viewMarkFailed(storyId: "a0")
        XCTAssertNil(m.state.pendingMark)
    }

    func testD6ViewMarkOkRecordsCountAndClearsPending() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0")])])
        m.viewMarkOk(storyId: "a0", viewCount: 7)
        XCTAssertEqual(m.state.current?.story.viewedByMe, true)
        XCTAssertEqual(m.state.current?.story.viewCount, 7)
        XCTAssertNil(m.state.pendingMark)
        XCTAssertEqual(m.state.flat.first?.story.viewCount, 7)
    }

    func testHalfWatchedStackStaysUnseen() {
        let m = makeMachine([group(userId: "alice", [story(id: "a0"), story(id: "a1")])])
        m.viewMarkOk(storyId: "a0", viewCount: 1)
        // group.allSeen is server truth in the wire copy; the FLAT view flips
        // only the marked story — the ring semantics stay per-author.
        XCTAssertEqual(m.state.flat.filter { $0.story.viewedByMe == true }.count, 1)
    }
}

/// Composer state — the exact client-side mirror of POST /api/stories validation.
final class StoryComposerStateTests: XCTestCase {
    func testTextModeCanPostRequiresNonBlankCaption() {
        var cs = StoryComposerState()
        XCTAssertFalse(cs.canPost)
        cs = cs.withCaption("   ")
        XCTAssertFalse(cs.canPost)
        cs = cs.withCaption("hello")
        XCTAssertTrue(cs.canPost)
    }

    func testCaptionHardTruncationAt280() {
        let cs = StoryComposerState().withCaption(String(repeating: "x", count: 400))
        XCTAssertEqual(cs.caption.count, 280)
        XCTAssertEqual(StoryComposerState.captionMax, 280)
    }

    func testPhotoModeCanPostRequiresUploadedImageAndIdleWork() {
        var cs = StoryComposerState(mode: .photo)
        XCTAssertFalse(cs.canPost)
        cs = cs.withImage("abc123.jpg")
        XCTAssertTrue(cs.canPost)
        cs = cs.withUploading(true)
        XCTAssertFalse(cs.canPost)
        cs = cs.withUploading(false).withPosting(true)
        XCTAssertFalse(cs.canPost)
    }

    func testImageFlipsModeBothWays() {
        var cs = StoryComposerState()
        cs = cs.withImage("a.png")
        XCTAssertEqual(cs.mode, .photo)
        cs = cs.withImage(nil)
        XCTAssertEqual(cs.mode, .text)
    }

    func testBackgroundIsTextOnlyAndValidated() {
        let photo = StoryComposerState(mode: .photo, background: "emerald", imagePath: "a.jpg")
        XCTAssertEqual(photo.withBackground("rose"), photo)
        let text = StoryComposerState().withBackground("violet")
        XCTAssertEqual(text.background, "violet")
        let bad = StoryComposerState().withBackground("chartreuse")
        XCTAssertEqual(bad.background, "emerald")
        XCTAssertEqual(StoryPalette.keys, ["emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan"])
    }
}

/// GRDB v6 storyCache snapshot round-trip (mirrors Android Room v8).
final class StoryStoreCacheTests: XCTestCase {
    func testStoryCacheUpsertGetClear() throws {
        let store = try PulseStore() // in-memory
        XCTAssertNil(try store.loadStoryCache(key: "stories:me"))
        try store.saveStoryCache(key: "stories:me", groupsJson: "{\"groups\":[]}", updatedAt: 1_700_000_000_000)
        let loaded = try XCTUnwrap(try store.loadStoryCache(key: "stories:me"))
        XCTAssertEqual(loaded.groupsJson, "{\"groups\":[]}")
        XCTAssertEqual(loaded.updatedAt, 1_700_000_000_000)
        try store.saveStoryCache(key: "stories:me", groupsJson: "{\"groups\":[{\"mine\":true}]}", updatedAt: 1_700_000_060_000)
        let updated = try XCTUnwrap(try store.loadStoryCache(key: "stories:me"))
        XCTAssertEqual(updated.groupsJson, "{\"groups\":[{\"mine\":true}]}")
        XCTAssertEqual(updated.updatedAt, 1_700_000_060_000)
    }
}
