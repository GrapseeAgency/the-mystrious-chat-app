import XCTest
@testable import Pulse

/// Wave 0 — outbox engine semantics, mirroring src/lib/pulse-outbox.ts:
/// FIFO ≤50, stop-at-first-network-failure, temp→real swap on success,
/// 4xx drop + continue. The APIClient boundary is the ONLY mock seam.
@MainActor
final class PulseOutboxTests: XCTestCase {
    // ── stubbed sender (test-only) ───────────────────────────

    private final class StubSender: PulseOutboxSending, @unchecked Sendable {
        enum Behavior {
            case success
            case httpError(status: Int)
            case networkError
        }

        private let lock = NSLock()
        private var behaviors: [String: Behavior]
        private var attempted: [String] = []
        /// R1-W2B D28 — every sendMessage's forward-relevant body, recorded
        /// as "kind|imagePath|audioPath|durationMs|filePath|fileName|fileSize"
        /// so the flush pass-through is pinned in tests.
        private var attemptedBodies: [String] = []

        init(behaviors: [String: Behavior]) {
            self.behaviors = behaviors
        }

        private func behavior(for content: String) -> Behavior {
            lock.lock()
            defer { lock.unlock() }
            return behaviors[content] ?? .success
        }

        func attempts(of content: String) -> Int {
            lock.lock()
            defer { lock.unlock() }
            return attempted.filter { $0 == content }.count
        }

        /// The recorded body of the Nth attempt (nil when fewer ran).
        func recordedBody(at index: Int) -> String? {
            lock.lock()
            defer { lock.unlock() }
            guard index >= 0, index < attemptedBodies.count else { return nil }
            return attemptedBodies[index]
        }

        // W1-DATA-B — full PulseOutboxSending requirement (mirrors the
        // extended PulseAPIClient.sendMessage; the engine passes nil for
        // every media/thread field — queued sends stay text-only).
        func sendMessage(
            conversationId: String,
            content: String,
            replyToId: String?,
            parentId: String?,
            imagePath: String?,
            audioPath: String?,
            durationMs: Double?,
            filePath: String?,
            fileName: String?,
            fileSize: Int?,
            kind: String?,
            viewOnce: Bool?,
            topicId: String?,
            payload: [String: Any]?,
            anon: Bool?
        ) async throws -> WireChatMessage {
            lock.lock()
            attempted.append(content)
            attemptedBodies.append(
                "\(kind ?? "nil")|\(imagePath ?? "nil")|\(audioPath ?? "nil")|\(durationMs.map { String($0) } ?? "nil")|\(filePath ?? "nil")|\(fileName ?? "nil")|\(fileSize.map { String($0) } ?? "nil")",
            )
            lock.unlock()
            switch behavior(for: content) {
            case .success:
                return OutboxFixtures.makeMessage(id: "srv-\(content)", conversationId: conversationId, content: content)
            case .httpError(let status):
                throw PulseAPIClient.Failure(kind: PulseAPIClient.kind(for: status), message: "server said no", status: status)
            case .networkError:
                throw PulseAPIClient.Failure(kind: .network, message: nil)
            }
        }
    }

    // ── engine helper ────────────────────────────────────────

    private func makeEngine(_ store: PulseStore, _ sender: StubSender?) -> PulseOutboxEngine {
        PulseOutboxEngine(store: store) { sender }
    }

    // ── queue shape ──────────────────────────────────────────

    func testAppendPreservesFIFOOrder() throws {
        let store = try PulseStore()
        let engine = makeEngine(store, nil)
        for index in 0..<12 {
            engine.append(conversationId: "c1", clientId: "cid-\(index)", content: "msg \(index)")
        }
        let rows = try store.outboxAll()
        XCTAssertEqual(rows.map(\.clientId), (0..<12).map { "cid-\($0)" })
        XCTAssertEqual(engine.count(), 12)
    }

    func testQueueCapsAtFiftyDroppingOldest() throws {
        let store = try PulseStore()
        let engine = makeEngine(store, nil)
        for index in 0..<55 {
            engine.append(conversationId: "c1", clientId: "cap-\(index)", content: "msg \(index)")
        }
        XCTAssertEqual(engine.count(), 50)
        let rows = try store.outboxAll()
        XCTAssertEqual(rows.first?.clientId, "cap-5")
        XCTAssertEqual(rows.last?.clientId, "cap-54")
    }

    // ── flush semantics ──────────────────────────────────────

    func testSuccessSwapsTempRowForRealAndEmptiesOutbox() async throws {
        let store = try PulseStore()
        let clientId = "cid-1"
        try store.upsert(messages: [OutboxFixtures.makeMessage(
            id: PulseOutboxEngine.tempMessageId(clientId: clientId),
            conversationId: "c1",
            content: "queued one",
        )])
        try store.appendOutbox(conversationId: "c1", clientId: clientId, content: "queued one", kind: "text")

        let sender = StubSender(behaviors: [:])
        var events: [PulseOutboxEngine.Event] = []
        let engine = makeEngine(store, sender)
        engine.onEvent = { events.append($0) }

        await engine.flush()

        let messages = try store.messages(conversationId: "c1")
        XCTAssertTrue(messages.contains { $0.id == "srv-queued one" })
        XCTAssertFalse(messages.contains { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) })
        XCTAssertEqual(store.countOutbox(), 0)
        XCTAssertEqual(events.count, 1)
        guard let recorded = events.first,
              case .delivered(let message, let conversationId, let deliveredClientId) = recorded else {
            return XCTFail("expected a delivered event")
        }
        XCTAssertEqual(message.id, "srv-queued one")
        XCTAssertEqual(conversationId, "c1")
        XCTAssertEqual(deliveredClientId, clientId)
    }

    func testNetworkFailureStopsFlushAndBumpsAttempts() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "c1", clientId: "cid-a", content: "first", kind: "text")
        try store.appendOutbox(conversationId: "c1", clientId: "cid-b", content: "second", kind: "text")
        try store.appendOutbox(conversationId: "c1", clientId: "cid-c", content: "third", kind: "text")

        let sender = StubSender(behaviors: [
            "second": .networkError,
        ])
        let engine = makeEngine(store, sender)

        await engine.flush()

        // Stop at the first failure — "third" was never attempted.
        XCTAssertEqual(sender.attempts(of: "first"), 1)
        XCTAssertEqual(sender.attempts(of: "second"), 1)
        XCTAssertEqual(sender.attempts(of: "third"), 0)

        // Failed + not-yet-attempted entries stay queued; the failure
        // bumped its attempts counter.
        let rows = try store.outboxAll()
        XCTAssertEqual(rows.map(\.clientId), ["cid-b", "cid-c"])
        XCTAssertEqual(rows.first?.attempts, 1)
        XCTAssertEqual(rows.last?.attempts, 0)
    }

    func testHTTP4xxDropsEntryDeletesTempRowAndContinues() async throws {
        let store = try PulseStore()
        try store.upsert(messages: [OutboxFixtures.makeMessage(
            id: PulseOutboxEngine.tempMessageId(clientId: "cid-bad"),
            conversationId: "c1",
            content: "bad",
        )])
        try store.appendOutbox(conversationId: "c1", clientId: "cid-bad", content: "bad", kind: "text")
        try store.appendOutbox(conversationId: "c1", clientId: "cid-good", content: "good", kind: "text")

        let sender = StubSender(behaviors: [
            "bad": .httpError(status: 422),
        ])
        var drops = 0
        let engine = makeEngine(store, sender)
        engine.onEvent = { event in
            if case .dropped = event { drops += 1 }
        }

        await engine.flush()

        // The 4xx entry was dropped (temp row deleted) and the drain
        // CONTINUED — the next entry was still delivered.
        XCTAssertEqual(sender.attempts(of: "bad"), 1)
        XCTAssertEqual(sender.attempts(of: "good"), 1)
        XCTAssertEqual(store.countOutbox(), 0)
        XCTAssertEqual(drops, 1)
        let messages = try store.messages(conversationId: "c1")
        XCTAssertTrue(messages.contains { $0.id == "srv-good" })
        XCTAssertFalse(messages.contains { $0.id == PulseOutboxEngine.tempMessageId(clientId: "cid-bad") })
    }

    func testDroppableClassificationMatchesWave0Semantics() {
        // 4xx → drop; network/5xx/unknown transport → retry.
        XCTAssertTrue(PulseOutboxEngine.isDroppable(PulseAPIClient.Failure(kind: .validation, message: nil, status: 422)))
        XCTAssertTrue(PulseOutboxEngine.isDroppable(PulseAPIClient.Failure(kind: .forbidden, message: nil, status: 403)))
        XCTAssertTrue(PulseOutboxEngine.isDroppable(PulseAPIClient.Failure(kind: .notFound, message: nil, status: 404)))
        XCTAssertFalse(PulseOutboxEngine.isDroppable(PulseAPIClient.Failure(kind: .network, message: nil)))
        XCTAssertFalse(PulseOutboxEngine.isDroppable(PulseAPIClient.Failure(kind: .server, message: nil, status: 502)))
        XCTAssertFalse(PulseOutboxEngine.isDroppable(PulseAPIClient.Failure(kind: .unknown, message: nil)))
    }

    // ── R1-W2B D28 — queued forwards flush with their stored body ──

    func testQueuedForwardFlushesWithKindAndMediaPaths() async throws {
        let store = try PulseStore()
        let forward = PulseOutboxForward(
            kind: "file",
            imagePath: nil,
            audioPath: "uploads/voice.m4a",
            durationMs: 1_400,
            filePath: "uploads/doc.pdf",
            fileName: "doc.pdf",
            fileSize: 2048,
        )
        try store.appendOutbox(
            conversationId: "c2",
            clientId: "fwd-1",
            content: "look at this",
            kind: "file",
            payloadJson: PulseOutboxForward.encode(forward),
        )

        let sender = StubSender(behaviors: [:])
        let engine = makeEngine(store, sender)
        await engine.flush()

        XCTAssertEqual(store.countOutbox(), 0)
        XCTAssertEqual(sender.attempts(of: "look at this"), 1)
        XCTAssertEqual(
            sender.recordedBody(at: 0),
            "file|nil|uploads/voice.m4a|1400.0|uploads/doc.pdf|doc.pdf|2048",
        )
    }

    func testPlainQueuedTextStillFlushesTextOnly() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "c1", clientId: "cid-1", content: "plain", kind: "text")

        let sender = StubSender(behaviors: [:])
        let engine = makeEngine(store, sender)
        await engine.flush()

        XCTAssertEqual(store.countOutbox(), 0)
        XCTAssertEqual(
            sender.recordedBody(at: 0),
            "nil|nil|nil|nil|nil|nil|nil",
        )
    }
}

/// Shared fixture builder — WireChatMessage's memberwise init is internal,
/// which @testable exposes to both test classes here.
enum OutboxFixtures {
    static func makeMessage(id: String, conversationId: String, content: String) -> WireChatMessage {
        WireChatMessage(
            id: id,
            conversationId: conversationId,
            senderId: "viewer-1",
            content: content,
            kind: "text",
            createdAt: PulseOutboxClock.now(),
            editedAt: nil,
            deletedAt: nil,
            sender: WireSender(id: "viewer-1", name: "Viewer", username: nil, color: nil, avatar: nil),
            reactions: nil,
            replyTo: nil,
            parentId: nil,
            imagePath: nil,
            audioPath: nil,
            durationMs: nil,
            filePath: nil,
            fileName: nil,
            fileSize: nil,
            pinnedAt: nil,
            viewOnce: nil,
            anon: nil,
            anonAlias: nil,
            viewedAt: nil,
            viewedBy: nil,
            transcript: nil,
            transcribedAt: nil,
            topicId: nil,
            linkUrl: nil,
            linkPreview: nil,
            poll: nil,
        )
    }
}
