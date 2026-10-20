import XCTest
@testable import Pulse

@MainActor
final class AttachmentDeliveryTests: XCTestCase {
    func testVoiceBytesAndIdentitySurviveStoreReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("outbox.sqlite").path
        var operation: String?
        do {
            let store = try PulseStore(path: path)
            let message = try PulseAttachmentDelivery.enqueue(store: store, viewer: PulseViewer(id: "me", name: "Me"),
                conversationId: "room", content: "", dataUrl: "data:audio/mp4;base64,aGVsbG8=",
                envelope: PulseOutboxForward(kind: "audio", durationMs: 2300, fileName: "recording.m4a", fileSize: 5, topicId: "original-topic", anon: true))
            XCTAssertEqual(PulseAttachmentDelivery.pendingLabel(message), "Voice note queued")
            XCTAssertNil(message.fileSize)
            XCTAssertNil(message.fileName)
            operation = try store.outboxPending().first?.clientId
        }
        let store = try PulseStore(path: path)
        let row = try XCTUnwrap(store.outboxPending().first)
        let envelope = try XCTUnwrap(PulseOutboxForward.decode(row.payloadJson))
        XCTAssertEqual(row.clientId, operation)
        XCTAssertEqual(envelope.uploadDataUrl, "data:audio/mp4;base64,aGVsbG8=")
        XCTAssertEqual(envelope.durationMs, 2300)
        XCTAssertEqual(envelope.topicId, "original-topic")
        XCTAssertEqual(envelope.anon, true)
        XCTAssertNil(envelope.fileSize)
    }

    func testInvalidUploadsDoNotConsumeLocalQueueSpace() throws {
        let store = try PulseStore()
        let viewer = PulseViewer(id: "me", name: "Me")
        for url in ["data:application/octet-stream;base64,aGVsbG8=", "data:image/jpeg;base64,", "invalid"] {
            XCTAssertThrowsError(try PulseAttachmentDelivery.enqueue(store: store, viewer: viewer,
                conversationId: "room", content: "", dataUrl: url, envelope: PulseOutboxForward(kind: "image")))
        }
        XCTAssertThrowsError(try PulseAttachmentDelivery.enqueue(store: store, viewer: viewer,
            conversationId: "room", content: "", dataUrl: "data:audio/mp4;base64,aGVsbG8=",
            envelope: PulseOutboxForward(kind: "audio", durationMs: 600_100)))
        XCTAssertEqual(store.countOutbox(), 0)
    }

    func testRemotePreviewNamesCannotAddressParentOrSharedPath() {
        for name in ["..", ".", "", " \n"] { XCTAssertEqual(PulseMediaOpener.safePreviewName(name), "attachment") }
        XCTAssertEqual(PulseMediaOpener.safePreviewName("../notes.pdf"), "..-notes.pdf")
        XCTAssertFalse(PulseMediaOpener.safePreviewName("folder/notes.pdf").contains("/"))
        XCTAssertEqual(PulseMediaOpener.safePreviewName("report.pdf"), "report.pdf")
    }
}
