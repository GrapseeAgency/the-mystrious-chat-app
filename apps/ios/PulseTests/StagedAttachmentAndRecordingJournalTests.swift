import XCTest
@testable import Pulse

/// R42 - staged attachment selections, consumption receipts, the recording
/// journal and the share-in inbox codec: process death between pick and send
/// restores the selection; consumption (queue or discard) is durable and
/// atomic; interrupted recordings reconstruct or drop honestly; share-in
/// entries decode exactly the bytes the extension writes.
@MainActor
final class StagedAttachmentAndRecordingJournalTests: XCTestCase {

    private func viewer() -> PulseViewer { PulseViewer(id: "me", name: "Me") }

    private func attachment(_ store: PulseStore, kind: String = "image",
                            content: String = "", dataUrl: String = "data:image/jpeg;base64,aGVsbG8=") throws -> WireChatMessage {
        let message = TempMessages.make(conversationId: "room", viewer: viewer(), clientId: "stage-\(kind)-01",
            content: content, parentId: nil, attachment: PulseOutboxForward(kind: kind, uploadDataUrl: dataUrl))
        try store.upsert(messages: [message])
        return message
    }

    // MARK: - staged selections + receipts

    func testStageLoadEditAndDiscardWithDurableConsumptionReceipts() throws {
        let store = try PulseStore()
        try store.stageAttachment(PulseStore.StagedAttachmentRow(
            selectionId: "stage-image-01", conversationId: "room", kind: "image",
            dataUrl: "data:image/jpeg;base64,aGVsbG8=", caption: "kept", viewOnce: true))
        let loaded = try store.loadStagedAttachment(conversationId: "room")
        XCTAssertEqual(loaded?.selectionId, "stage-image-01")
        XCTAssertEqual(loaded?.caption, "kept")
        XCTAssertEqual(loaded?.viewOnce, true)
        // A newer pick replaces an older, never-sent selection for the room.
        try store.stageAttachment(PulseStore.StagedAttachmentRow(
            selectionId: "stage-image-02", conversationId: "room", kind: "image",
            dataUrl: "data:image/jpeg;base64,aGVsbG8="))
        XCTAssertEqual(try store.loadStagedAttachment(conversationId: "room")?.selectionId, "stage-image-02")
        // Edits persist (caption + view-once) with a monotonic revision.
        try store.editStagedAttachment(selectionId: "stage-image-02", caption: "typed later", viewOnce: false)
        let edited = try store.loadStagedAttachment(conversationId: "room")
        XCTAssertEqual(edited?.caption, "typed later")
        XCTAssertEqual(edited?.viewOnce, false)
        XCTAssertEqual(edited?.revision, 1)
        // The user's discard is a durable consumption receipt.
        try store.discardStagedAttachment(conversationId: "room", selectionId: "stage-image-02")
        XCTAssertNil(try store.loadStagedAttachment(conversationId: "room"))
        XCTAssertEqual(try store.attachmentReceiptOutcome(selectionId: "stage-image-02"), "discarded")
    }

    func testEnqueueConsumesStagedSelectionAndWritesQueuedReceiptAtomically() throws {
        let store = try PulseStore()
        try store.stageAttachment(PulseStore.StagedAttachmentRow(
            selectionId: "stage-image-01", conversationId: "room", kind: "image",
            dataUrl: "data:image/jpeg;base64,aGVsbG8=", caption: "typed"))
        let message = TempMessages.make(conversationId: "room", viewer: viewer(), clientId: "queued-op-01",
            content: "typed", parentId: nil, attachment: PulseOutboxForward(kind: "image", uploadDataUrl: "data:image/jpeg;base64,aGVsbG8="))
        try store.enqueueMessage(message, clientId: "queued-op-01",
            envelope: PulseOutboxForward(kind: "image", uploadDataUrl: "data:image/jpeg;base64,aGVsbG8="),
            consumingStaged: "stage-image-01")
        // The staged row is gone in the SAME transaction that queued the send.
        XCTAssertNil(try store.loadStagedAttachment(conversationId: "room"))
        XCTAssertEqual(try store.attachmentReceiptOutcome(selectionId: "queued-op-01"), "queued")
        // Delivery retires the queued receipt with the acknowledgement.
        let delivered = message
        try store.resolveDelivery(clientId: "queued-op-01", message: delivered)
        XCTAssertNil(try store.attachmentReceiptOutcome(selectionId: "queued-op-01"))
    }

    // MARK: - recording journal

    func testRecordingJournalLifecycleSurvivesReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("journal.sqlite").path
        let file = directory.appendingPathComponent("voice-take.m4a")
        try Data([1, 2, 3]).write(to: file)
        do {
            let store = try PulseStore(path: path)
            try store.recordingJournalStart(id: "take-01", conversationId: "room", filePath: file.path)
            try store.recordingJournalStopped(id: "take-01")
            XCTAssertEqual(try store.recordingJournalRows().count, 1)
        }
        // Process death + reopen: the journal row survives with its stop mark.
        let store = try PulseStore(path: path)
        let rows = try store.recordingJournalRows()
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.id, "take-01")
        XCTAssertNotNil(rows.first?.stoppedAt)
        // A cancelled take retires; a consume retires.
        try store.recordingJournalCancelled(id: "take-01")
        try store.recordingJournalConsumed(id: "take-01")
        XCTAssertTrue(try store.recordingJournalRows().isEmpty)
    }

    // MARK: - share-in inbox codec

    func testShareInInboxDecodesExactlyWhatTheExtensionWrites() throws {
        let file = ShareInInbox.InboxFile(kind: "image", text: nil,
            dataUrl: "data:image/jpeg;base64,aGVsbG8=", fileName: nil, fileSize: 5,
            receivedAt: 1_800_000_000)
        let data = try JSONEncoder().encode(file)
        let entry = ShareInInbox.entry(id: "share-01", fileData: data)
        XCTAssertEqual(entry?.id, "share-01")
        XCTAssertEqual(entry?.kind, "image")
        XCTAssertEqual(entry?.dataUrl, "data:image/jpeg;base64,aGVsbG8=")
        XCTAssertEqual(entry?.fileSize, 5)
        let textFile = ShareInInbox.InboxFile(kind: "text", text: "shared words",
            dataUrl: nil, fileName: nil, fileSize: nil, receivedAt: 1_800_000_001)
        let textEntry = ShareInInbox.entry(id: "share-02", fileData: try JSONEncoder().encode(textFile))
        XCTAssertEqual(textEntry?.kind, "text")
        XCTAssertEqual(textEntry?.text, "shared words")
        // Unsupported / empty / corrupt entries are dropped by the decode,
        // never crash the scan.
        let badKind = ShareInInbox.InboxFile(kind: "video", text: nil, dataUrl: "data:video/mp4;base64,aG8=",
            fileName: nil, fileSize: nil, receivedAt: 1_800_000_002)
        XCTAssertNil(ShareInInbox.entry(id: "x1", fileData: try JSONEncoder().encode(badKind)))
        let emptyText = ShareInInbox.InboxFile(kind: "text", text: "", dataUrl: nil,
            fileName: nil, fileSize: nil, receivedAt: 1_800_000_003)
        XCTAssertNil(ShareInInbox.entry(id: "x2", fileData: try JSONEncoder().encode(emptyText)))
        XCTAssertNil(ShareInInbox.entry(id: "x3", fileData: Data("{not json".utf8)))
    }
}
