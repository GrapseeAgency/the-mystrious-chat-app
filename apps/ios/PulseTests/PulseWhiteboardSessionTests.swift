import XCTest
import Combine
@testable import Pulse

@MainActor
final class PulseWhiteboardSessionTests: XCTestCase {
    private func page(_ strokes: [WireWhiteboardStroke] = [], epoch: Int64 = 0, version: Int? = 1) -> WireWhiteboardPage {
        WireWhiteboardPage(operationsVersion: version, strokes: strokes, serverTime: 1, resetAt: epoch)
    }
    private func model(defaults: UserDefaults,
                       load: @escaping () async throws -> WireWhiteboardPage,
                       post: @escaping ([WireWhiteboardStrokePost]) async throws -> WireWhiteboardPostResult) -> PulseWhiteboardSession {
        PulseWhiteboardSession(conversationId: "room", viewerId: "me", defaults: defaults, load: load, post: post,
            undo: {}, clear: { WireWhiteboardClearResult(success: true, reset: true, at: 1, cleared: 0, serverTime: 1) })
    }
    private func suite() -> UserDefaults { UserDefaults(suiteName: UUID().uuidString)! }
    private let key = "account:me:room:room"

    func testNormalizesLongStrokesAndKeepsBothEndpoints() {
        let raw = (0..<4000).map { [Double($0) / 4000, 0.5] }
        let actual = PulseWhiteboardSession.normalizedPoints(raw)
        XCTAssertEqual(actual.count, 500)
        XCTAssertEqual(actual.first, raw.first)
        XCTAssertEqual(actual.last, raw.last)
        XCTAssertEqual(PulseWhiteboardSession.normalizedPoints([[0.2, 0.3]]).count, 2)
        XCTAssertTrue(PulseWhiteboardSession.normalizedPoints([[.nan, 1], [1]]).isEmpty)
    }

    func testDraftIsStoredBeforePostAndCancelledAckCannotEraseIt() async {
        let defaults = suite()
        let loaded = expectation(description: "loaded")
        let posting = expectation(description: "posting")
        var continuation: CheckedContinuation<WireWhiteboardPostResult, Never>?
        let model = model(defaults: defaults, load: { return self.page() }, post: { batch in
            XCTAssertEqual(PulseWhiteboardDraft.strokes(conversationId: self.key, defaults: defaults), batch)
            posting.fulfill()
            return await withCheckedContinuation { continuation = $0 }
        })
        let observation = model.$ready.filter { $0 }.prefix(1).sink { _ in loaded.fulfill() }
        defer { observation.cancel() }
        let worker = Task { await model.run() }
        await fulfillment(of: [loaded], timeout: 2)
        model.draw(color: "#abcdef", width: 3, points: [[0, 0], [1, 1]])
        await fulfillment(of: [posting], timeout: 4)
        let saved = PulseWhiteboardDraft.strokes(conversationId: key, defaults: defaults)
        XCTAssertEqual(saved.count, 1)
        model.stop(); worker.cancel()
        continuation?.resume(returning: WireWhiteboardPostResult(ids: saved.map { "wb:me:\($0.clientId!)" }, created: 1, serverTime: 1))
        await worker.value
        XCTAssertEqual(PulseWhiteboardDraft.strokes(conversationId: key, defaults: defaults), saved)
    }

    func testLostAcknowledgementReconcilesByOperationIdentity() async {
        let defaults = suite()
        let stroke = WireWhiteboardStrokePost(color: "#abcdef", width: 3, points: [[0, 0], [1, 1]], clientId: "operation-00000001", baseResetAt: 0)
        PulseWhiteboardDraft.append(stroke, conversationId: key, defaults: defaults)
        let loaded = expectation(description: "loaded")
        let remote = WireWhiteboardStroke(id: "wb:me:operation-00000001", userId: "me", color: stroke.color, width: 3, points: stroke.points, createdAt: nil)
        let model = model(defaults: defaults, load: { return self.page([remote]) }, post: { _ in
            XCTFail("A delivered operation must not be uploaded again")
            throw URLError(.badServerResponse)
        })
        let observation = model.$ready.filter { $0 }.prefix(1).sink { _ in loaded.fulfill() }
        defer { observation.cancel() }
        let worker = Task { await model.run() }
        await fulfillment(of: [loaded], timeout: 2)
        XCTAssertTrue(model.pending.isEmpty)
        XCTAssertEqual(model.remote.map(\.id), [remote.id])
        model.stop(); worker.cancel(); await worker.value
    }

    func testIdenticalGeometryFromAnotherSenderDoesNotConsumeMyDraft() async {
        let defaults = suite()
        let stroke = WireWhiteboardStrokePost(color: "#abcdef", width: 3, points: [[0, 0], [1, 1]], clientId: "operation-00000001", baseResetAt: 0)
        PulseWhiteboardDraft.append(stroke, conversationId: key, defaults: defaults)
        let posting = expectation(description: "posting")
        let remote = WireWhiteboardStroke(id: "someone-elses-stroke", userId: "other", color: stroke.color, width: 3, points: stroke.points, createdAt: nil)
        let model = model(defaults: defaults, load: { self.page([remote]) }, post: { batch in
            XCTAssertEqual(batch, [stroke]); posting.fulfill()
            // Malformed ACK must also retain the operation.
            return WireWhiteboardPostResult(ids: [], created: 0, serverTime: 1)
        })
        let failed = expectation(description: "invalid ACK rejected")
        let observation = model.$error.compactMap { $0 }.prefix(1).sink { _ in failed.fulfill() }
        defer { observation.cancel() }
        let worker = Task { await model.run() }
        await fulfillment(of: [posting, failed], timeout: 4)
        XCTAssertEqual(model.pending, [stroke])
        XCTAssertNotNil(model.error)
        model.stop(); worker.cancel(); await worker.value
    }

    func testOldResetAndUnknownEpochAreNeverUploaded() async {
        let defaults = suite()
        let old = WireWhiteboardStrokePost(color: "#abcdef", width: 3, points: [[0, 0], [1, 1]], clientId: "operation-00000001", baseResetAt: 0)
        let legacy = WireWhiteboardStrokePost(color: "#123456", width: 3, points: [[0, 0], [1, 1]])
        PulseWhiteboardDraft.replaceAll(conversationId: key, strokes: [old, legacy], defaults: defaults)
        let loaded = expectation(description: "loaded")
        let model = model(defaults: defaults, load: { return self.page(epoch: 7) }, post: { _ in
            XCTFail("Must not resurrect old/unknown drawings after Clear")
            throw URLError(.badServerResponse)
        })
        let observation = model.$ready.filter { $0 }.prefix(1).sink { _ in loaded.fulfill() }
        defer { observation.cancel() }
        let worker = Task { await model.run() }
        await fulfillment(of: [loaded], timeout: 2)
        XCTAssertEqual(model.pending, [legacy])
        model.stop(); worker.cancel(); await worker.value
    }

    func testLegacyServerDisablesUnsafeReplay() async {
        let defaults = suite()
        let loaded = expectation(description: "loaded")
        let model = model(defaults: defaults, load: { return self.page(version: nil) }, post: { _ in
            XCTFail("Old endpoint cannot deduplicate retries")
            throw URLError(.badServerResponse)
        })
        let observation = model.$error.compactMap { $0 }.prefix(1).sink { _ in loaded.fulfill() }
        defer { observation.cancel() }
        let worker = Task { await model.run() }
        await fulfillment(of: [loaded], timeout: 2)
        XCTAssertFalse(model.ready)
        model.draw(color: "#abcdef", width: 3, points: [[0, 0], [1, 1]])
        XCTAssertTrue(model.pending.isEmpty)
        model.stop(); worker.cancel(); await worker.value
    }
}
