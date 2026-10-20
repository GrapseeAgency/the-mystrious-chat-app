import Foundation
import Combine

/// One screen-owned worker serializes snapshot, upload, Undo and Clear. Draft
/// writes are synchronous on MainActor, never queued behind the HTTP request.
@MainActor
final class PulseWhiteboardSession: ObservableObject {
    @Published private(set) var remote: [WireWhiteboardStroke] = []
    @Published private(set) var pending: [WireWhiteboardStrokePost] = []
    @Published private(set) var ready = false
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    private let key: String
    private let viewerId: String
    private let defaults: UserDefaults
    private let load: () async throws -> WireWhiteboardPage
    private let post: ([WireWhiteboardStrokePost]) async throws -> WireWhiteboardPostResult
    private let undo: () async throws -> Void
    private let clear: () async throws -> WireWhiteboardClearResult
    private var epoch: Int64 = 0
    private var runID: UUID?
    private enum Command { case undo, clear }
    private var command: Command?

    init(conversationId: String, viewerId: String, defaults: UserDefaults = .standard,
         load: @escaping () async throws -> WireWhiteboardPage,
         post: @escaping ([WireWhiteboardStrokePost]) async throws -> WireWhiteboardPostResult,
         undo: @escaping () async throws -> Void,
         clear: @escaping () async throws -> WireWhiteboardClearResult) {
        self.key = "account:\(viewerId):room:\(conversationId)"
        self.viewerId = viewerId; self.defaults = defaults
        self.load = load; self.post = post; self.undo = undo; self.clear = clear
    }

    private func current(_ id: UUID) throws {
        try Task.checkCancellation()
        guard runID == id else { throw CancellationError() }
    }
    private func drafts() -> [WireWhiteboardStrokePost] {
        PulseWhiteboardDraft.strokes(conversationId: key, defaults: defaults)
    }
    private func store(_ strokes: [WireWhiteboardStrokePost]) {
        PulseWhiteboardDraft.replaceAll(conversationId: key, strokes: strokes, defaults: defaults)
        pending = strokes
    }

    func run() async {
        let id = UUID()
        runID = id
        pending = PulseWhiteboardDraft.strokes(conversationId: key, defaults: defaults)
        defer { if runID == id { runID = nil; busy = false; command = nil } }
        while !Task.isCancelled && runID == id {
            do {
                if let action = command {
                    command = nil
                    switch action {
                    case .undo: try await undo(); try current(id)
                    case .clear:
                        let result = try await clear()
                        try current(id)
                        guard result.success == true, result.reset == true, let at = result.at else { throw BoardError.invalidAck }
                        epoch = at
                        store(drafts().filter { ($0.baseResetAt ?? -1) >= at })
                        remote = []
                    }
                    busy = false
                }
                let page = try await load()
                try current(id)
                if page.operationsVersion != 1 || page.complete == false { ready = false }
                guard page.operationsVersion == 1 else { throw BoardError.upgrade }
                guard page.complete != false else { throw BoardError.incomplete }
                epoch = page.resetAt ?? 0
                let snapshot = page.strokes ?? []
                let ids = Set(snapshot.map(\.id))
                // Only operation identity can prove delivery. Identical geometry
                // from another person (or another intentional stroke) cannot.
                pending = drafts()
                let retained = pending.filter { stroke in
                    guard let op = stroke.clientId, let base = stroke.baseResetAt else { return true }
                    return base >= epoch && !ids.contains("wb:\(viewerId):\(op)")
                }
                if retained != pending { store(retained) }
                var seen = Set<String>()
                remote = snapshot.filter { seen.insert($0.id).inserted }
                ready = true
                error = pending.contains { $0.clientId == nil || $0.baseResetAt == nil }
                    ? "Older drafts retained locally; their delivery state is unknown." : nil
                // A Clear/Undo requested during GET takes priority over new uploads.
                if command == nil {
                    let batch = Array(pending.filter { $0.clientId != nil && $0.baseResetAt == epoch }.prefix(40))
                    if !batch.isEmpty {
                        let ack = try await post(batch)
                        try current(id)
                        let expected = batch.map { "wb:\(viewerId):\($0.clientId!)" }
                        guard ack.ids == expected else { throw BoardError.invalidAck }
                        let sent = Set(batch.compactMap(\.clientId))
                        store(drafts().filter { $0.clientId.map { !sent.contains($0) } ?? true })
                        // Preserve acknowledged ink until the next full snapshot.
                        for (index, stroke) in batch.enumerated() where !remote.contains(where: { $0.id == expected[index] }) {
                            remote.append(WireWhiteboardStroke(id: expected[index], userId: viewerId, color: stroke.color,
                                width: stroke.width, points: stroke.points, createdAt: nil))
                        }
                    }
                }
            } catch is CancellationError { break }
            catch {
                guard runID == id, !Task.isCancelled else { break }
                busy = command != nil
                self.error = "Whiteboard operation failed; saved drafts were retained. \(error.localizedDescription)"
            }
            do { try await Task.sleep(nanoseconds: 1_500_000_000) } catch { break }
        }
    }

    func stop() { runID = nil; command = nil; busy = false; ready = false }

    func draw(color: String, width: Double, points: [[Double]]) {
        guard ready, !busy, runID != nil else { return }
        let normalized = Self.normalizedPoints(points)
        guard normalized.count >= 2 else { return }
        let stroke = WireWhiteboardStrokePost(color: color, width: width, points: normalized,
            clientId: UUID().uuidString, baseResetAt: epoch)
        store(drafts() + [stroke])
    }
    func undoMine() {
        guard ready, !busy, pending.isEmpty else { return }
        command = .undo; busy = true
    }
    func clearBoard() {
        guard ready, !busy else { return }
        command = .clear; busy = true
    }

    nonisolated static func normalizedPoints(_ raw: [[Double]]) -> [[Double]] {
        var points = raw.filter { $0.count >= 2 && $0[0].isFinite && $0[1].isFinite }
            .map { [min(max($0[0], 0), 1), min(max($0[1], 0), 1)] }
        if points.count == 1 { points.append(points[0]) } // stationary tap is a dot
        guard points.count > 500 else { return points }
        // Exactly 500 samples including both endpoints (never a 501st point).
        return (0..<500).map { points[$0 * (points.count - 1) / 499] }
    }
    private enum BoardError: LocalizedError {
        case invalidAck, incomplete, upgrade
        var errorDescription: String? {
            switch self {
            case .upgrade: return "Server upgrade required for safe drawing sync."
            case .invalidAck: return "Invalid drawing acknowledgement."
            case .incomplete: return "Board exceeds snapshot limit; no partial board was adopted."
            }
        }
    }
}
