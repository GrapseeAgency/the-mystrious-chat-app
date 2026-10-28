import XCTest
import Combine
import GRDB
@testable import Pulse

/// Wave 0 - outbox engine semantics, mirroring src/lib/pulse-outbox.ts:
/// FIFO ≤50, stop-at-first-network-failure, temp→real swap on success,
/// Permanent rejection retained as failed + continue. The APIClient boundary is the ONLY mock seam.
@MainActor
final class PulseOutboxTests: XCTestCase {
    // stubbed sender (test-only)

    private final class StubSender: PulseOutboxSending, @unchecked Sendable {
        enum Behavior {
            case success
            case httpError(status: Int)
            case networkError
            case roomCooldown(seconds: Int)
        }

        var uploadCount = 0
        var beforeUploadReturn: (() throws -> Void)?
        var beforeUploadAsync: (() async -> Void)?
        func uploadMedia(dataUrl: String) async throws -> String {
            uploadCount += 1
            await beforeUploadAsync?()
            try beforeUploadReturn?()
            return "uploaded-file.pdf"
        }

        private let lock = NSLock()
        private var behaviors: [String: Behavior]
        private var attempted: [String] = []
        var beforeSend: ((String) throws -> Void)?
        private(set) var operationIds: [String] = []
        private(set) var metadata: [String] = []
        /// R1-W2B D28 - every sendMessage's forward-relevant body, recorded
        /// as "kind|imagePath|audioPath|durationMs|filePath|fileName|fileSize"
        /// so the flush pass-through is pinned in tests.
        private var attemptedBodies: [String] = []

        init(behaviors: [String: Behavior]) {
            self.behaviors = behaviors
        }

        func setBehavior(_ behavior: Behavior, for content: String) {
            lock.lock(); defer { lock.unlock() }
            behaviors[content] = behavior
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

        // W1-DATA-B - full PulseOutboxSending requirement (mirrors the
        // extended PulseAPIClient.sendMessage; the engine passes nil for
        // every media/thread field - queued sends stay text-only).
        func sendMessage(
            conversationId: String,
            clientId: String,
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
            operationIds.append(clientId)
            metadata.append("\(replyToId ?? "nil")|\(topicId ?? "nil")|\(anon == true)")
            attemptedBodies.append(
                "\(kind ?? "nil")|\(imagePath ?? "nil")|\(audioPath ?? "nil")|\(durationMs.map { String($0) } ?? "nil")|\(filePath ?? "nil")|\(fileName ?? "nil")|\(fileSize.map { String($0) } ?? "nil")",
            )
            lock.unlock()
            try beforeSend?(content)
            switch behavior(for: content) {
            case .success:
                return OutboxFixtures.makeMessage(id: "srv-\(content)", conversationId: conversationId, content: content)
            case .httpError(let status):
                throw PulseAPIClient.Failure(kind: PulseAPIClient.kind(for: status), message: "server said no", status: status)
            case .roomCooldown(let seconds):
                throw PulseAPIClient.Failure(kind: .rateLimited, message: "slow mode", code: "slow_mode", status: 429, retryAfter: seconds)
            case .networkError:
                throw PulseAPIClient.Failure(kind: .network, message: nil)
            }
        }
    }

    func testQuickReplyCommitsBeforeDispatchAndReplaysTheSameOperationWithoutTouchingDraft() async throws {
        let store = try PulseStore()
        try store.saveDraft(conversationId:"c1",text:"composer work")
        XCTAssertEqual(try PulseQuickReply.persist(text:" notification reply ",conversationId:"c1",clientId:"reply-op",locked:false,store:store), .queued)
        let sender = StubSender(behaviors:["notification reply":.networkError])
        sender.beforeSend = { _ in
            XCTAssertTrue(try store.isPendingOutbox(clientId:"reply-op"))
            XCTAssertEqual(store.draft(conversationId:"c1"),"composer work")
        }
        let engine = makeEngine(store,sender)
        defer { engine.stop() }
        _ = await engine.flush()
        XCTAssertEqual(try store.outboxPending().map(\.clientId),["reply-op"])
        sender.setBehavior(.success,for:"notification reply")
        _ = await engine.flush()
        XCTAssertEqual(sender.operationIds,["reply-op","reply-op"])
        XCTAssertTrue(try store.outboxPending().isEmpty)
        XCTAssertEqual(store.draft(conversationId:"c1"),"composer work")
    }

    func testLockedAndOversizedNotificationRepliesAreRetainedButNeverAutomaticallySent() async throws {
        let store = try PulseStore()
        let otherAccount = try PulseStore()
        try store.saveDraft(conversationId:"c1",text:"do not replace")
        let long = String(repeating:"x",count:1999) + "😀"
        for (id,text,locked) in [("locked","private reply",true),("oversize",long,false)] {
            guard case .held = try PulseQuickReply.persist(text:text,conversationId:"c1",clientId:id,locked:locked,store:store) else {
                XCTFail("Must retain for review, not queue");return
            }
        }
        let sender = StubSender(behaviors:[:])
        let engine = makeEngine(store,sender)
        defer { engine.stop() }
        _ = await engine.flush()
        XCTAssertTrue(sender.operationIds.isEmpty)
        XCTAssertTrue(try store.outboxPending().isEmpty)
        XCTAssertEqual(try store.outboxAll().map(\.content),["private reply",long])
        XCTAssertTrue(try otherAccount.outboxAll().isEmpty)
        XCTAssertEqual(store.draft(conversationId:"c1"),"do not replace")
    }

    func testNotificationReplySurvivesReopenAndWriteFailureDoesNotClaimAcceptance() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let path = directory.appendingPathComponent("quick-reply.sqlite").path
        do {
            let store = try PulseStore(path:path)
            XCTAssertEqual(try PulseQuickReply.persist(text:"durable",conversationId:"c1",clientId:"operation",locked:false,store:store),.queued)
        }
        let store = try PulseStore(path:path)
        XCTAssertEqual(try store.outboxPending().map(\.content),["durable"])
        let database = try DatabaseQueue(path:path)
        try await database.write { db in
            try db.execute(sql:"CREATE TRIGGER refuse_reply BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END")
        }
        XCTAssertThrowsError(try PulseQuickReply.persist(text:"not saved",conversationId:"c1",clientId:"failed",locked:false,store:store))
        XCTAssertThrowsError(try PulseQuickReply.persist(text:"not held",conversationId:"c1",clientId:"held-failed",locked:true,store:store))
        XCTAssertEqual(try store.outboxAll().map(\.clientId),["operation"])
    }

    private actor BackgroundBarrier {
        private var open = false
        private var waiter: CheckedContinuation<Void, Never>?
        func wait() async {
            if open { return }
            await withCheckedContinuation { waiter = $0 }
        }
        func release() {
            open = true
            waiter?.resume()
            waiter = nil
        }
    }

    func testBackgroundReadsAndBadgesFinishWhileMediaUploadIsSuspended() async throws {
        let store = try PulseStore(path: ":memory:")
        let sender = StubSender(behaviors: [:]), barrier = BackgroundBarrier()
        let upload = expectation(description: "upload suspended")
        let projected = expectation(description: "read replay and badge reconciliation completed")
        sender.beforeUploadAsync = { upload.fulfill(); await barrier.wait() }
        let engine = makeEngine(store, sender)
        XCTAssertTrue(engine.appendForward(conversationId: "c1", clientId: "large-file", content: "attachment",
            forward: PulseOutboxForward(kind: "file", fileName: "large.pdf", uploadDataUrl: "data:application/pdf;base64,cGRm")))
        var replayed = false, finished = false
        engine.onReadJournalFlush = { replayed = true; return true }
        engine.onBadgeRefresh = { XCTAssertTrue(replayed); projected.fulfill(); return true }
        let work = Task { let result = await engine.flushBackgroundQueues(); finished = true; return result }
        defer { work.cancel(); engine.stop(); Task { await barrier.release() } }
        await fulfillment(of: [upload, projected], timeout: 5)
        XCTAssertFalse(finished, "The background task must still own the upload")
        XCTAssertEqual(try store.countPendingOutbox(), 1)
        await barrier.release()
        let complete = await work.value
        XCTAssertTrue(complete)
        XCTAssertEqual(try store.countPendingOutbox(), 0)
    }

    func testSlowBackgroundReadReplayDoesNotBlockOutgoingMessagesOrRunBadgesBeforeReplay() async throws {
        let store = try PulseStore(path: ":memory:")
        let engine = makeEngine(store, StubSender(behaviors: [:])), barrier = BackgroundBarrier()
        XCTAssertTrue(engine.append(conversationId: "c1", clientId: "send", content: "hello"))
        let read = expectation(description: "read replay suspended")
        let sent = expectation(description: "outgoing message committed")
        var projected = false, finished = false
        engine.onReadJournalFlush = { read.fulfill(); await barrier.wait(); return false }
        engine.onAcknowledgement = { _ in sent.fulfill() }
        engine.onBadgeRefresh = { projected = true; return true }
        let work = Task { let result = await engine.flushBackgroundQueues(); finished = true; return result }
        defer { work.cancel(); engine.stop(); Task { await barrier.release() } }
        await fulfillment(of: [read, sent], timeout: 5)
        XCTAssertFalse(projected)
        XCTAssertFalse(finished)
        XCTAssertEqual(try store.countPendingOutbox(), 0)
        await barrier.release()
        let complete = await work.value
        XCTAssertFalse(complete, "An incomplete read journal keeps the wake retryable")
        XCTAssertTrue(projected, "Current canonical state still reconciles after a failed replay")
    }

    func testBackgroundExpirationCancelsUploadAndReadReplayWithoutPublishingBadges() async throws {
        let store = try PulseStore(path: ":memory:")
        let sender = StubSender(behaviors: [:])
        let upload = expectation(description: "upload started"), read = expectation(description: "read replay started")
        let uploadCancelled = expectation(description: "upload cancelled"), readCancelled = expectation(description: "read cancelled")
        sender.beforeUploadAsync = {
            upload.fulfill()
            do { try await Task.sleep(nanoseconds: 60_000_000_000) }
            catch { uploadCancelled.fulfill() }
        }
        let engine = makeEngine(store, sender)
        XCTAssertTrue(engine.appendForward(conversationId: "c1", clientId: "original", content: "attachment",
            forward: PulseOutboxForward(kind: "file", fileName: "large.pdf", uploadDataUrl: "data:application/pdf;base64,cGRm")))
        var projected = false
        engine.onReadJournalFlush = {
            read.fulfill()
            do { try await Task.sleep(nanoseconds: 60_000_000_000) }
            catch { readCancelled.fulfill() }
            return false
        }
        engine.onBadgeRefresh = { projected = true; return true }
        let work = Task { await engine.flushBackgroundQueues() }
        defer { work.cancel(); engine.stop() }
        await fulfillment(of: [upload, read], timeout: 5)
        work.cancel()
        await fulfillment(of: [uploadCancelled, readCancelled], timeout: 5)
        let complete = await work.value
        XCTAssertFalse(complete)
        XCTAssertFalse(projected)
        XCTAssertEqual(try store.countPendingOutbox(), 1)
        XCTAssertEqual(sender.attempts(of: "attachment"), 0)
    }

    func testColdWakeLosingOwnershipDuringUploadCannotPersistOrPostThroughTheLiveAccount() async throws {
        let store = try PulseStore(path: ":memory:")
        let sender = StubSender(behaviors: [:]), barrier = BackgroundBarrier()
        let started = expectation(description: "cold upload suspended")
        var current = true
        sender.beforeUploadAsync = { started.fulfill(); await barrier.wait() }
        let engine = PulseOutboxEngine(store: store, monitorNetwork: false, isCurrent: { current }) { sender }
        XCTAssertTrue(engine.appendForward(conversationId: "c1", clientId: "original", content: "attachment",
            forward: PulseOutboxForward(kind: "file", fileName: "large.pdf", uploadDataUrl: "data:application/pdf;base64,cGRm")))
        let work = Task { await engine.flush() }
        defer { work.cancel(); engine.stop(); Task { await barrier.release() } }
        await fulfillment(of: [started], timeout: 5)
        current = false // foreground takeover, credential change, or gateway switch
        await barrier.release()
        let complete = await work.value
        XCTAssertFalse(complete)
        XCTAssertEqual(sender.attempts(of: "attachment"), 0)
        let entry = try XCTUnwrap(store.outboxPending().first)
        XCTAssertEqual(entry.clientId, "original")
        XCTAssertNil(PulseOutboxForward.decode(entry.payloadJson)?.filePath)
        XCTAssertNotNil(PulseOutboxForward.decode(entry.payloadJson)?.uploadDataUrl)
    }

    func testExpiredBackgroundOwnerCannotStartAProjectionAfterSuspendedReadReplay() async throws {
        let store = try PulseStore(path: ":memory:")
        let sender = StubSender(behaviors: [:]), barrier = BackgroundBarrier()
        var current = true, projected = false
        let started = expectation(description: "read replay suspended")
        let engine = PulseOutboxEngine(store: store, monitorNetwork: false, isCurrent: { current }) { sender }
        engine.onReadJournalFlush = { started.fulfill(); await barrier.wait(); return true }
        engine.onBadgeRefresh = { projected = true; return true }
        let work = Task { await engine.flushBackgroundQueues() }
        defer { work.cancel(); engine.stop(); Task { await barrier.release() } }
        await fulfillment(of: [started], timeout: 5)
        current = false
        await barrier.release()
        let complete = await work.value
        XCTAssertFalse(complete)
        XCTAssertFalse(projected)
    }

    func testDeliveredEventPublishesTheCommittedReadProofNotTheOldSendResponse() async throws {
        let store = try PulseStore(), sender = StubSender(behaviors: [:])
        sender.beforeSend = { content in
            var alreadySeen = OutboxFixtures.makeMessage(id: "srv-\(content)", conversationId: "c1", content: content)
            alreadySeen.readByMembershipIds = ["peer-membership"]
            try store.upsert(messages: [alreadySeen]) // socket read before the HTTP send ACK returns
        }
        let engine = makeEngine(store, sender)
        defer { engine.stop() }
        XCTAssertTrue(engine.append(conversationId: "c1", clientId: "operation", content: "hello"))
        var delivered: WireChatMessage?
        engine.onEvent = { event in
            if case .delivered(let message, _, _) = event { delivered = message }
        }
        let complete = await engine.flush()
        XCTAssertTrue(complete)
        XCTAssertEqual(delivered?.readByMembershipIds, ["peer-membership"])
        XCTAssertEqual(try store.message(id: "srv-hello")?.readByMembershipIds, ["peer-membership"])
        XCTAssertTrue(try store.outboxAll().isEmpty)
    }

    func testBackgroundCompletionRequiresUnreadProjectionAsWellAsDurableQueues() async throws {
        let store = try PulseStore(path: ":memory:")
        let engine = makeEngine(store, StubSender(behaviors: [:]))
        defer { engine.stop() }
        engine.onReadJournalFlush = { true }
        engine.onBadgeRefresh = { false }
        let incomplete = await engine.flushBackgroundQueues()
        XCTAssertFalse(incomplete)
        engine.onBadgeRefresh = { true }
        let complete = await engine.flushBackgroundQueues()
        XCTAssertTrue(complete)
    }

    // engine helper

    private func makeEngine(_ store: PulseStore, _ sender: StubSender?) -> PulseOutboxEngine {
        PulseOutboxEngine(store: store, monitorNetwork: false) { sender }
    }

    func testUnreadableQueueIsNotReportedEmptyAndRecoversWithoutLosingIntent() async throws {
        for failBeforeEngineCreation in [false, true] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: directory) }
            let path = directory.appendingPathComponent("outbox.sqlite").path
            let store = try PulseStore(path: path)
            let database = try DatabaseQueue(path: path)
            try store.appendOutbox(conversationId: "c1", clientId: "durable", content: "keep")
            if failBeforeEngineCreation {
                try await database.write { try $0.execute(sql: "ALTER TABLE outbox RENAME TO unavailable_outbox") }
            }
            let sender = StubSender(behaviors: [:])
            let engine = makeEngine(store, sender)
            defer { engine.stop() }
            if !failBeforeEngineCreation {
                XCTAssertEqual(engine.pendingCount, 1)
                try await database.write { try $0.execute(sql: "ALTER TABLE outbox RENAME TO unavailable_outbox") }
            }
            XCTAssertThrowsError(try store.countPendingOutbox())
            XCTAssertThrowsError(try store.countOutbox())
            let unknown = await engine.flush()
            XCTAssertFalse(unknown)
            XCTAssertTrue(engine.storageUnavailable)
            XCTAssertTrue(engine.recoveryScheduled)
            XCTAssertEqual(engine.pendingCount, failBeforeEngineCreation ? 0 : 1)
            XCTAssertEqual(sender.attempts(of: "keep"), 0)
            try await database.write { try $0.execute(sql: "ALTER TABLE unavailable_outbox RENAME TO outbox") }
            let recovered = await engine.flush()
            XCTAssertTrue(recovered)
            XCTAssertFalse(engine.storageUnavailable)
            XCTAssertFalse(engine.recoveryScheduled)
            XCTAssertEqual(sender.operationIds, ["durable"])
            XCTAssertEqual(try store.countPendingOutbox(), 0)
        }
    }

    func testFailedLocalAcknowledgementRetainsIdentityAndRecoveryTrigger() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("ack.sqlite").path
        let store = try PulseStore(path: path)
        let database = try DatabaseQueue(path: path)
        try store.appendOutbox(conversationId: "c1", clientId: "same-operation", content: "ack")
        let sender = StubSender(behaviors: [:])
        sender.beforeSend = { _ in
            try database.write { try $0.execute(sql: "ALTER TABLE message RENAME TO unavailable_message") }
        }
        let engine = makeEngine(store, sender)
        defer { engine.stop() }
        let incomplete = await engine.flush()
        XCTAssertFalse(incomplete)
        XCTAssertEqual(try store.outboxAll().map(\.clientId), ["same-operation"])
        XCTAssertTrue(engine.recoveryScheduled)
        sender.beforeSend = nil
        try await database.write { try $0.execute(sql: "ALTER TABLE unavailable_message RENAME TO message") }
        let recovered = await engine.flush()
        XCTAssertTrue(recovered)
        XCTAssertEqual(sender.operationIds, ["same-operation", "same-operation"])
        XCTAssertEqual(try store.countPendingOutbox(), 0)
    }

    // queue shape

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

    func testQueueRetainsEveryAcceptedMessageBeyondOneBatch() throws {
        let store = try PulseStore()
        let engine = makeEngine(store, nil)
        for index in 0..<55 {
            engine.append(conversationId: "c1", clientId: "cap-\(index)", content: "msg \(index)")
        }
        XCTAssertEqual(engine.count(), 55)
        let rows = try store.outboxAll()
        XCTAssertEqual(rows.first?.clientId, "cap-0")
        XCTAssertEqual(rows.last?.clientId, "cap-54")
    }

    // flush semantics

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
        XCTAssertEqual(try store.countOutbox(), 0)
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

        // Stop at the first failure - "third" was never attempted.
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

    func testHTTP4xxRetainsFailedIntentRetractsEchoAndContinues() async throws {
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

        // The rejected intent is retained (temp row retracted) and the drain
        // CONTINUED - the next entry was still delivered.
        XCTAssertEqual(sender.attempts(of: "bad"), 1)
        XCTAssertEqual(sender.attempts(of: "good"), 1)
        XCTAssertEqual(try store.countOutbox(), 1)
        XCTAssertEqual(try store.countPendingOutbox(), 0)
        XCTAssertEqual(try store.outboxAll().first?.content, "bad")
        XCTAssertNotNil(try store.outboxAll().first?.failureReason)
        await engine.flush()
        XCTAssertEqual(sender.attempts(of: "bad"), 1) // retained is not auto-retried
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

    func testTransientHTTPFailuresRetainIntent() async throws {
        for status in [401, 408, 425, 429, 500, 503] {
            let store = try PulseStore()
            try store.appendOutbox(conversationId: "c1", clientId: "a", content: "keep")
            let sender = StubSender(behaviors: ["keep": .httpError(status: status)])
            let engine = makeEngine(store, sender)
            defer { engine.stop() }
            XCTAssertFalse(isDroppableHTTPStatus(status))
            let complete = await engine.flush()
            XCTAssertFalse(complete)
            XCTAssertEqual(try store.outboxAll().map(\.content), ["keep"])
        }
    }

    private func isDroppableHTTPStatus(_ status: Int) -> Bool {
        PulseOutboxEngine.isDroppable(PulseAPIClient.Failure(kind: PulseAPIClient.kind(for: status), message: nil, status: status))
    }

    func testStoppedEngineCannotSend() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "c1", clientId: "a", content: "keep")
        let sender = StubSender(behaviors: [:])
        let engine = makeEngine(store, sender)
        engine.stop()
        let complete = await engine.flush()
        XCTAssertFalse(complete)
        XCTAssertEqual(sender.attempts(of: "keep"), 0)
        XCTAssertEqual(try store.countOutbox(), 1)
    }

    // R1-W2B D28 - queued forwards flush with their stored body

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

        XCTAssertEqual(try store.countOutbox(), 0)
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

        XCTAssertEqual(try store.countOutbox(), 0)
        XCTAssertEqual(
            sender.recordedBody(at: 0),
            "text|nil|nil|nil|nil|nil|nil",
        )
    }
    func testRetriesKeepTheOperationIdAndQuotedTopicMetadata() async throws {
        let store = try PulseStore()
        let sender = StubSender(behaviors: ["pending": .networkError])
        let envelope = PulseOutboxForward(replyToId: "quote", topicId: "topic", anon: true)
        try store.appendOutbox(conversationId: "c1", clientId: "operation-stable-id", content: "pending",
                               payloadJson: PulseOutboxForward.encode(envelope))
        let engine = makeEngine(store, sender)
        await engine.flush()
        await engine.flush()
        XCTAssertEqual(sender.operationIds, ["operation-stable-id", "operation-stable-id"])
        XCTAssertEqual(sender.metadata, ["quote|topic|true", "quote|topic|true"])
        XCTAssertEqual(try store.countOutbox(), 1)
        engine.stop()
    }

    func testExactAcknowledgementRetainsAnotherIdenticalPendingSend() throws {
        let store = try PulseStore()
        let a = OutboxFixtures.makeMessage(id: "local_operation-a", conversationId: "c1", content: "same")
        let b = OutboxFixtures.makeMessage(id: "local_operation-b", conversationId: "c1", content: "same")
        try store.enqueueMessage(a, clientId: "operation-a", envelope: PulseOutboxForward())
        try store.enqueueMessage(b, clientId: "operation-b", envelope: PulseOutboxForward())
        try store.resolveDelivery(clientId: "operation-a", message: OutboxFixtures.makeMessage(id: "real-a", conversationId: "c1", content: "same"))
        XCTAssertEqual(try store.outboxAll().map(\.clientId), ["operation-b"])
        // Replay the same acknowledgement: still no effect on operation B.
        try store.resolveDelivery(clientId: "operation-a", message: OutboxFixtures.makeMessage(id: "real-a", conversationId: "c1", content: "same"))
        XCTAssertEqual(try store.countOutbox(), 1)
    }

    func testConflictingLocalKeyIsReportedNotPretendedQueued() throws {
        let store = try PulseStore()
        let engine = makeEngine(store, nil)
        XCTAssertTrue(engine.append(conversationId: "c1", clientId: "same-key", content: "first"))
        XCTAssertTrue(engine.append(conversationId: "c1", clientId: "same-key", content: "first"))
        XCTAssertFalse(engine.append(conversationId: "c1", clientId: "same-key", content: "changed"))
        XCTAssertEqual(try store.outboxAll().map(\.content), ["first"])
        engine.stop()
    }

    func testMalformedEnvelopeIsRetainedRatherThanSendingAlteredContent() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "c1", clientId: "operation-corrupt", content: "private", payloadJson: "not-json")
        let sender = StubSender(behaviors: [:])
        let engine = makeEngine(store, sender)
        await engine.flush()
        XCTAssertEqual(sender.attempts(of: "private"), 0)
        XCTAssertEqual(try store.countOutbox(), 1)
        XCTAssertEqual(try store.countPendingOutbox(), 0)
        XCTAssertNotNil(try store.outboxAll().first?.failureReason)
        engine.stop()
    }

    func testRejectionPreservesEnvelopeWithoutOverwritingNewComposer() throws {
        let store = try PulseStore()
        let message = OutboxFixtures.makeMessage(id: "local_failed", conversationId: "c1", content: "old send")
        let envelope = PulseOutboxForward(replyToId: "quote", topicId: "topic", anon: true)
        try store.enqueueMessage(message, clientId: "failed", envelope: envelope)
        try store.saveDraft(conversationId: "c1", text: "new unsent thought")
        XCTAssertTrue(try store.rejectOutbox(clientId: "failed", reason: "No longer a member"))
        XCTAssertEqual(store.draft(conversationId: "c1"), "new unsent thought")
        let row = try XCTUnwrap(store.outboxAll().first)
        XCTAssertEqual(row.content, "old send")
        let storedEnvelope = try XCTUnwrap(row.payloadJson).data(using: .utf8)!
        let expectedEnvelope = try XCTUnwrap(PulseOutboxForward.encode(envelope)).data(using: .utf8)!
        XCTAssertEqual(try JSONSerialization.jsonObject(with: storedEnvelope) as? NSDictionary,
                       try JSONSerialization.jsonObject(with: expectedEnvelope) as? NSDictionary)
        XCTAssertEqual(row.failureReason, "No longer a member")
        XCTAssertTrue(try store.outboxPending().isEmpty)
    }

    func testLateRefusalCannotUndoAnAlreadyResolvedDelivery() throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "c1", clientId: "operation", content: "sent")
        try store.resolveDelivery(clientId: "operation",
            message: OutboxFixtures.makeMessage(id: "server", conversationId: "c1", content: "sent"))
        XCTAssertFalse(try store.rejectOutbox(clientId: "operation", reason: "late competing request"))
        XCTAssertTrue(try store.outboxAll().isEmpty)
        XCTAssertTrue(try store.messages(conversationId: "c1").contains { $0.id == "server" })
    }

    func testFailedRowsDoNotStarvePendingBatch() async throws {
        let store = try PulseStore()
        for index in 0..<55 {
            let id = "failed-\(index)"
            try store.appendOutbox(conversationId: "c1", clientId: id, content: "bad")
            try store.rejectOutbox(clientId: id, reason: "refused")
        }
        try store.appendOutbox(conversationId: "c1", clientId: "good", content: "good")
        let sender = StubSender(behaviors: [:])
        let engine = makeEngine(store, sender)
        defer { engine.stop() }
        await engine.flush()
        XCTAssertEqual(sender.attempts(of: "bad"), 0)
        XCTAssertEqual(sender.attempts(of: "good"), 1)
        XCTAssertEqual(try store.countOutbox(), 55)
        XCTAssertEqual(try store.countPendingOutbox(), 0)
    }

    func testDiscardRemovesPendingEchoAndIntentTogether() throws {
        let store = try PulseStore()
        try store.enqueueMessage(OutboxFixtures.makeMessage(id: "local_discard", conversationId: "c1", content: "unsent"),
            clientId: "discard", envelope: PulseOutboxForward())
        XCTAssertTrue(try store.isPendingOutbox(clientId: "discard"))
        try store.deleteOutbox(clientId: "discard")
        XCTAssertFalse(try store.isPendingOutbox(clientId: "discard"))
        XCTAssertTrue(try store.messages(conversationId: "c1").isEmpty)
        XCTAssertTrue(try store.outboxAll().isEmpty)
    }

    func testDiscardDuringDrainSkipsLaterSnapshotEntry() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "c1", clientId: "first", content: "first")
        try store.appendOutbox(conversationId: "c1", clientId: "second", content: "second")
        let sender = StubSender(behaviors: [:])
        sender.beforeSend = { content in
            if content == "first" { try store.deleteOutbox(clientId: "second") }
        }
        let engine = makeEngine(store, sender)
        defer { engine.stop() }
        await engine.flush()
        XCTAssertEqual(sender.attempts(of: "first"), 1)
        XCTAssertEqual(sender.attempts(of: "second"), 0)
    }

    func testFailedIntentSurvivesDatabaseReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("recovery.sqlite").path
        do {
            let store = try PulseStore(path: path)
            try store.appendOutbox(conversationId: "c1", clientId: "held", content: "keep me", payloadJson: "{\"anon\":true}")
            try store.rejectOutbox(clientId: "held", reason: "forbidden")
        }
        let reopened = try PulseStore(path: path)
        let row = try XCTUnwrap(reopened.outboxAll().first)
        XCTAssertEqual(row.content, "keep me")
        XCTAssertEqual(row.payloadJson, "{\"anon\":true}")
        XCTAssertEqual(row.failureReason, "forbidden")
        XCTAssertEqual(try reopened.countPendingOutbox(), 0)
    }


    func testUploadReceiptSurvivesLostMessageAckWithoutSecondUpload() async throws {
        let store = try PulseStore()
        let message = try PulseAttachmentDelivery.enqueue(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            conversationId: "c1", content: "attachment", dataUrl: "data:application/pdf;base64,JVBERg==",
            envelope: PulseOutboxForward(kind: "file", fileName: "notes.pdf", topicId: "topic", anon: true))
        let before = try XCTUnwrap(store.outboxPending().first)
        let lost = StubSender(behaviors: ["attachment": .networkError])
        let first = makeEngine(store, lost)
        await first.flush(); first.stop()
        let saved = try XCTUnwrap(PulseOutboxForward.decode(store.outboxPending().first?.payloadJson))
        XCTAssertNil(saved.uploadDataUrl)
        XCTAssertEqual(saved.filePath, "uploaded-file.pdf")
        XCTAssertEqual(lost.uploadCount, 1)
        XCTAssertNotNil(try store.message(id: message.id))
        let retry = StubSender(behaviors: [:])
        let reopened = makeEngine(store, retry)
        defer { reopened.stop() }
        await reopened.flush()
        XCTAssertEqual(retry.uploadCount, 0)
        XCTAssertEqual(lost.operationIds, [before.clientId])
        XCTAssertEqual(retry.operationIds, [before.clientId])
        XCTAssertEqual(retry.metadata, ["nil|topic|true"])
        XCTAssertEqual(retry.recordedBody(at: 0), "file|nil|nil|nil|uploaded-file.pdf|notes.pdf|4")
        XCTAssertNil(try store.message(id: message.id))
        XCTAssertEqual(try store.countOutbox(), 0)
    }

    func testKnownThreadRefusalStopsUploadAndPostWithoutBlockingOtherMessages() async throws {
        let store = try PulseStore()
        let envelope = PulseOutboxForward(kind: "file", fileName: "notes.pdf", parentId: "root",
            uploadDataUrl: "data:application/pdf;base64,JVBERg==")
        try store.appendOutbox(conversationId: "room", clientId: "held", content: "held", kind: "file",
            payloadJson: PulseOutboxForward.encode(envelope))
        try store.appendOutbox(conversationId: "room", clientId: "next", content: "next")
        try store.denyThread(rootId: "root")
        let sender = StubSender(behaviors: [:]), engine = makeEngine(store, sender)
        defer { engine.stop() }
        await engine.flush()
        XCTAssertEqual(sender.uploadCount, 0)
        XCTAssertEqual(sender.operationIds, ["next"])
        let retained = try XCTUnwrap(store.outboxAll().first)
        XCTAssertEqual(retained.clientId, "held")
        XCTAssertNotNil(retained.failureReason)
        XCTAssertEqual(PulseOutboxForward.decode(retained.payloadJson), envelope)
        XCTAssertEqual(try store.countPendingOutbox(), 0)
    }

    func testSessionOnlyRefusalAlsoBlocksBackgroundDrain() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "room", clientId: "held", content: "held",
            payloadJson: PulseOutboxForward.encode(PulseOutboxForward(kind: "text", parentId: "root")))
        let sender = StubSender(behaviors: [:]), engine = makeEngine(store, sender)
        defer { engine.stop() }
        engine.additionalThreadAccessDenied = { $0 == "root" }
        await engine.flush()
        XCTAssertTrue(sender.operationIds.isEmpty)
        XCTAssertNotNil(try store.outboxAll().first?.failureReason)
    }

    func testThreadRefusalDuringUploadKeepsReceiptAndRequiresExplicitRetry() async throws {
        let store = try PulseStore()
        let root = OutboxFixtures.makeMessage(id: "root", conversationId: "room", content: "root")
        try store.upsert(messages: [root])
        let envelope = PulseOutboxForward(kind: "file", fileName: "notes.pdf", parentId: "root",
            uploadDataUrl: "data:application/pdf;base64,JVBERg==")
        let echo = TempMessages.make(conversationId: "room", viewer: PulseViewer(id: "me", name: "Me"),
            clientId: "held", content: "held", parentId: "root", attachment: envelope)
        try store.enqueueMessage(echo, clientId: "held", envelope: envelope)
        let sender = StubSender(behaviors: [:]), engine = makeEngine(store, sender)
        defer { engine.stop() }
        sender.beforeUploadReturn = { try store.denyThread(rootId: "root") }
        await engine.flush()
        XCTAssertEqual(sender.uploadCount, 1)
        XCTAssertTrue(sender.operationIds.isEmpty)
        let retained = try XCTUnwrap(store.outboxAll().first)
        let uploaded = try XCTUnwrap(PulseOutboxForward.decode(retained.payloadJson))
        XCTAssertNotNil(retained.failureReason)
        XCTAssertNil(uploaded.uploadDataUrl)
        XCTAssertEqual(uploaded.filePath, "uploaded-file.pdf")
        _ = try store.acceptThreadSnapshot(rootId: "root", roomId: "room", messages: [root])
        await engine.flush() // revalidation alone does not auto-send a previously refused operation
        XCTAssertTrue(sender.operationIds.isEmpty)
        XCTAssertTrue(try store.retryOutbox(clientId: "held", message: echo))
        await engine.flush()
        XCTAssertEqual(sender.uploadCount, 1)
        XCTAssertEqual(sender.operationIds, ["held"])
        XCTAssertEqual(try store.countOutbox(), 0)
    }

    func testUnknownUploadKeepsBytesAndDiscardDuringUploadDoesNotSend() async throws {
        let store = try PulseStore()
        _ = try PulseAttachmentDelivery.enqueue(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            conversationId: "c1", content: "held", dataUrl: "data:image/jpeg;base64,aGVsbG8=",
            envelope: PulseOutboxForward(kind: "image", viewOnce: true))
        let entry = try XCTUnwrap(store.outboxPending().first)
        let offline = StubSender(behaviors: [:])
        offline.beforeUploadReturn = { throw URLError(.networkConnectionLost) }
        let first = makeEngine(store, offline)
        await first.flush(); first.stop()
        XCTAssertNotNil(PulseOutboxForward.decode(try store.outboxPending().first?.payloadJson)?.uploadDataUrl)
        XCTAssertTrue(offline.operationIds.isEmpty)
        let discarded = StubSender(behaviors: [:])
        discarded.beforeUploadReturn = { try store.deleteOutbox(clientId: entry.clientId) }
        let second = makeEngine(store, discarded)
        defer { second.stop() }
        await second.flush()
        XCTAssertTrue(discarded.operationIds.isEmpty)
        XCTAssertEqual(try store.countOutbox(), 0)
    }

    func testRejectedAttachmentIsManuallyRetryableWithOriginalBytesAndIdentity() throws {
        let store = try PulseStore()
        let message = try PulseAttachmentDelivery.enqueue(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            conversationId: "c1", content: "", dataUrl: "data:audio/mp4;base64,aGVsbG8=",
            envelope: PulseOutboxForward(kind: "audio", durationMs: 1800))
        let entry = try XCTUnwrap(store.outboxPending().first)
        XCTAssertTrue(try store.rejectOutbox(clientId: entry.clientId, reason: "forbidden"))
        XCTAssertTrue(try store.outboxPending().isEmpty)
        XCTAssertTrue(try store.retryOutbox(clientId: entry.clientId, message: message))
        XCTAssertEqual(try store.outboxPending().first?.payloadJson, entry.payloadJson)
        XCTAssertEqual(try store.outboxPending().first?.clientId, entry.clientId)
        try store.deleteOutbox(clientId: entry.clientId)
        XCTAssertFalse(try store.retryOutbox(clientId: entry.clientId, message: message))
        XCTAssertNil(try store.message(id: message.id))
    }

    func testMonotonicRetryHoldSurvivesRepeatedFlushAndShorterHold() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "c1", clientId: "held", content: "held")
        var now: TimeInterval = 100
        let sender = StubSender(behaviors: [:])
        let engine = PulseOutboxEngine(store: store, monitorNetwork: false, monotonicNow: { now }, senderProvider: { sender })
        defer { engine.stop() }
        engine.holdRetries(seconds: 60)
        now = 110
        engine.holdRetries(seconds: 1)
        _ = await engine.flush()
        XCTAssertEqual(sender.attempts(of: "held"), 0)
        now = 160
        _ = await engine.flush()
        XCTAssertEqual(sender.attempts(of: "held"), 1)
        XCTAssertEqual(try store.countPendingOutbox(), 0)
    }

    func testRetryDeadlinesSurviveStoreAndEngineReopenWithoutBlockingHealthyRooms() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let store = try PulseStore(path: path, composerOwner: "me")
        let sender = StubSender(behaviors: [:])
        var wall: TimeInterval = 1_000_000, monotonic: TimeInterval = 100
        let first = PulseOutboxEngine(store: store, monitorNetwork: false, monotonicNow: { monotonic }, wallNow: { wall }, senderProvider: { sender })
        first.holdRetries(seconds: 60, conversationId: "slow")
        first.holdRetries(seconds: 20)
        first.stop()
        wall += 10; monotonic = 0
        let reopened = try PulseStore(path: path, composerOwner: "me")
        try reopened.appendOutbox(conversationId: "slow", clientId: "slow", content: "slow")
        try reopened.appendOutbox(conversationId: "healthy", clientId: "healthy", content: "healthy")
        let next = PulseOutboxEngine(store: reopened, monitorNetwork: false, monotonicNow: { monotonic }, wallNow: { wall }, senderProvider: { sender })
        defer { next.stop() }
        _ = await next.flush()
        XCTAssertEqual(sender.attempts(of: "healthy"), 0, "Global cooldown survives restart")
        monotonic = 10
        _ = await next.flush()
        XCTAssertEqual(sender.attempts(of: "healthy"), 1)
        XCTAssertEqual(sender.attempts(of: "slow"), 0)
        next.holdRetries(seconds: 1, conversationId: "slow")
        monotonic = 49
        _ = await next.flush()
        XCTAssertEqual(sender.attempts(of: "slow"), 0)
        monotonic = 50
        _ = await next.flush()
        XCTAssertEqual(sender.attempts(of: "slow"), 1, "Repeated reads do not restart the cooldown")
        XCTAssertTrue(try reopened.outboxRetryDeadlines(now: wall + 86_401).isEmpty)
        XCTAssertTrue(try PulseStore().outboxRetryDeadlines(now: wall).isEmpty)
    }

    func testHealthyBacklogSharesEachDrainRoundWithOtherRooms() async throws {
        let store = try PulseStore()
        for i in 0..<80 { try store.appendOutbox(conversationId: "a", clientId: "a-\(i)", content: "a-\(i)") }
        try store.appendOutbox(conversationId: "b", clientId: "b", content: "b")
        let sender = StubSender(behaviors: [:])
        let draining = makeEngine(store, sender)
        defer { draining.stop() }
        _ = await draining.flush()
        XCTAssertEqual(Array(sender.operationIds.prefix(3)), ["a-0", "b", "a-1"])
    }

    func testFailedHeadRotatesOnNextPassWithoutOvertakingItsOwnRoom() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "a", clientId: "a", content: "a")
        try store.appendOutbox(conversationId: "a", clientId: "a-next", content: "a-next")
        try store.appendOutbox(conversationId: "b", clientId: "b", content: "b")
        let sender = StubSender(behaviors: ["a": .networkError])
        let engine = makeEngine(store, sender)
        defer { engine.stop() }
        _ = await engine.flush(); _ = await engine.flush()
        XCTAssertEqual(sender.operationIds, ["a", "b", "a"])
        XCTAssertEqual(try store.outboxPending().map(\.clientId), ["a", "a-next"])
    }

    func testRoomCooldownBypassesLongPrefixButPreservesThatRoomsFIFOAndIdentity() async throws {
        let store = try PulseStore()
        for i in 0..<75 { try store.appendOutbox(conversationId: "slow", clientId: "slow-\(i)", content: "slow-\(i)") }
        try store.appendOutbox(conversationId: "direct", clientId: "direct", content: "direct")
        var now: TimeInterval = 100
        let sender = StubSender(behaviors: ["slow-0": .roomCooldown(seconds: 60)])
        let engine = PulseOutboxEngine(store: store, monitorNetwork: false, monotonicNow: { now }) { sender }
        defer { engine.stop() }
        _ = await engine.flush()
        XCTAssertEqual(sender.operationIds, ["slow-0", "direct"])
        XCTAssertEqual(try store.countPendingOutbox(), 75)
        _ = await engine.flush()
        XCTAssertEqual(sender.operationIds, ["slow-0", "direct"])
        sender.setBehavior(.success, for: "slow-0")
        now += 60
        _ = await engine.flush()
        XCTAssertEqual(Array(sender.operationIds.dropFirst(2)), (0..<50).map { "slow-\($0)" })
        XCTAssertEqual(try store.countPendingOutbox(), 25)
    }

    func testUnknown429StillHoldsEveryRoom() async throws {
        let store = try PulseStore()
        try store.appendOutbox(conversationId: "a", clientId: "a", content: "a")
        try store.appendOutbox(conversationId: "b", clientId: "b", content: "b")
        let sender = StubSender(behaviors: ["a": .httpError(status: 429)])
        let engine = makeEngine(store, sender)
        defer { engine.stop() }
        _ = await engine.flush()
        _ = await engine.flush()
        XCTAssertEqual(sender.operationIds, ["a"])
        XCTAssertEqual(try store.countPendingOutbox(), 2)
    }

    func testUpload429CannotPretendToBeRoomSlowMode() async throws {
        let store = try PulseStore()
        _ = try PulseAttachmentDelivery.enqueue(store: store, viewer: PulseViewer(id: "me", name: "Me"),
            conversationId: "a", content: "", dataUrl: "data:audio/mp4;base64,aGVsbG8=", envelope: PulseOutboxForward(kind: "audio", durationMs: 1800))
        try store.appendOutbox(conversationId: "b", clientId: "b", content: "b")
        let sender = StubSender(behaviors: [:])
        sender.beforeUploadReturn = { throw PulseAPIClient.Failure(kind: .rateLimited, message: "upload throttle", code: "slow_mode", status: 429, retryAfter: 60) }
        let engine = makeEngine(store, sender)
        defer { engine.stop() }
        _ = await engine.flush()
        _ = await engine.flush()
        XCTAssertEqual(sender.uploadCount, 1)
        XCTAssertTrue(sender.operationIds.isEmpty)
        XCTAssertEqual(try store.countPendingOutbox(), 2)
    }

    func testRetryAfterHTTPDateUsesGatewayClockAndMalformedHeaderIsNotAZeroDelay() {
        XCTAssertEqual(PulseAPIClient.retryAfterSeconds("120", serverDate: nil), 120)
        XCTAssertEqual(PulseAPIClient.retryAfterSeconds("Tue, 20 Oct 2026 12:02:00 GMT",
            serverDate: "Tue, 20 Oct 2026 12:00:00 GMT", now: Date(timeIntervalSince1970: 0)), 120)
        XCTAssertNil(PulseAPIClient.retryAfterSeconds("broken", serverDate: nil))
        XCTAssertNil(PulseAPIClient.retryAfterSeconds("-3", serverDate: nil))
    }

    func testThreadRetryKeepsIdentityEnvelopeAndDraftAndStaleDiscardCannotDeleteQueuedSend() throws {
        let store = try PulseStore()
        let viewer = PulseViewer(id: "viewer-1", name: "Viewer")
        try store.upsert(messages: [OutboxFixtures.makeMessage(id: "root", conversationId: "room", content: "root")])
        let envelope = PulseOutboxForward(kind: "text", replyToId: "quoted", parentId: "root", topicId: "topic")
        let message = TempMessages.make(conversationId: "room", viewer: viewer, clientId: "recover", content: "original", parentId: "root", topicId: "topic", attachment: envelope)
        try store.enqueueMessage(message, clientId: "recover", envelope: envelope)
        try store.rejectOutbox(clientId: "recover", reason: "forbidden")
        let retained = try XCTUnwrap(store.outboxAll().first)
        let draftKey = PulseThreadDraft.key("room", parentId: "root")
        try store.saveDraft(conversationId: draftKey, text: "newer typing")
        XCTAssertFalse(try store.discardFailedThreadReply(clientId: "recover", conversationId: "other", rootId: "root"))
        XCTAssertFalse(try store.discardFailedThreadReply(clientId: "recover", conversationId: "room", rootId: "other"))
        XCTAssertFalse(try store.retryOutbox(clientId: "recover", message: message, threadRootId: "other"))
        XCTAssertTrue(try store.retryOutbox(clientId: "recover", message: message, threadRootId: "root"))
        XCTAssertFalse(try store.discardFailedThreadReply(clientId: "recover", conversationId: "room", rootId: "root"))
        XCTAssertFalse(try store.retryOutbox(clientId: "recover", message: message, threadRootId: "root"))
        let retried = try XCTUnwrap(store.outboxAll().first)
        XCTAssertEqual(retried.id, retained.id)
        XCTAssertEqual(retried.clientId, retained.clientId)
        XCTAssertEqual(retried.payloadJson, retained.payloadJson)
        XCTAssertNil(retried.failureReason)
        XCTAssertEqual(try store.message(id: "local_recover")?.parentId, "root")
        XCTAssertEqual(store.draftSnapshot(conversationId: draftKey), "newer typing")
    }

    func testUnavailableRootCannotRetryButOwnRejectedTextRemainsDiscardable() throws {
        let store = try PulseStore()
        let message = TempMessages.make(conversationId: "room", viewer: PulseViewer(id: "viewer-1", name: "Viewer"),
            clientId: "recover", content: "original", parentId: "root")
        try store.enqueueMessage(message, clientId: "recover", envelope: PulseOutboxForward(kind: "text", parentId: "root"))
        try store.rejectOutbox(clientId: "recover", reason: "forbidden")
        XCTAssertFalse(try store.retryOutbox(clientId: "recover", message: message, threadRootId: "root"))
        var root = OutboxFixtures.makeMessage(id: "root", conversationId: "room", content: "root", deletedAt: PulseOutboxClock.now())
        try store.upsert(messages: [root])
        XCTAssertFalse(try store.retryOutbox(clientId: "recover", message: message, threadRootId: "root"))
        root = OutboxFixtures.makeMessage(id: "root", conversationId: "room", content: "root")
        root.expiresAt = "2000-01-01T00:00:00Z"
        try store.upsert(messages: [root])
        XCTAssertFalse(try store.retryOutbox(clientId: "recover", message: message, threadRootId: "root"))
        let otherAccount = try PulseStore()
        XCTAssertFalse(try otherAccount.discardFailedThreadReply(clientId: "recover", conversationId: "room", rootId: "root"))
        XCTAssertTrue(try store.discardFailedThreadReply(clientId: "recover", conversationId: "room", rootId: "root"))
        XCTAssertEqual(try store.countOutbox(), 0)
    }

    func testFailedThreadObservationFiltersRoomRootPendingAndMalformedEnvelope() async throws {
        let store = try PulseStore()
        for (id, room, root) in [("mine", "room", "root"), ("pending", "room", "root"), ("other-root", "room", "other"), ("other-room", "other", "root")] {
            try store.appendOutbox(conversationId: room, clientId: id, content: id,
                payloadJson: PulseOutboxForward.encode(PulseOutboxForward(kind: "text", parentId: root)))
            if id != "pending" { try store.rejectOutbox(clientId: id, reason: "rejected") }
        }
        try store.appendOutbox(conversationId: "room", clientId: "broken", content: "recover in Settings", payloadJson: "not JSON")
        try store.rejectOutbox(clientId: "broken", reason: "invalid")
        let observed = expectation(description: "Only this thread's rejected reply is observed")
        let observation = store.observeFailedThreadReplies(conversationId: "room", rootId: "root").prefix(1)
            .sink(receiveCompletion: { if case .failure(let error) = $0 { XCTFail("\(error)") } }, receiveValue: { rows in
                XCTAssertEqual(rows.map(\.clientId), ["mine"])
                observed.fulfill()
            })
        await fulfillment(of: [observed], timeout: 3)
        observation.cancel()
    }

}

/// Shared fixture builder - WireChatMessage's memberwise init is internal,
/// which @testable exposes to both test classes here.
enum OutboxFixtures {
    static func makeMessage(id: String, conversationId: String, content: String, deletedAt: String? = nil) -> WireChatMessage {
        WireChatMessage(
            id: id,
            conversationId: conversationId,
            senderId: "viewer-1",
            content: content,
            kind: "text",
            createdAt: PulseOutboxClock.now(),
            editedAt: nil,
            deletedAt: deletedAt,
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
