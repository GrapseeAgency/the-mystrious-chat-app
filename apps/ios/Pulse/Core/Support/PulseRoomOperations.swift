import Combine
import Foundation

/// Sheet-owned mutation tasks and read ordering. A poll cannot restore a row
/// deleted/moved after it began; teardown invalidates even cancellation-ignoring replies.
@MainActor
final class PulseRoomOperations: ObservableObject {
    struct ReadTicket { fileprivate let epoch: UUID; fileprivate let revision: UInt64 }
    @Published private(set) var pending: Set<String> = []
    @Published private(set) var settledRevision: UInt64 = 0
    private var epoch = UUID()
    private var revision: UInt64 = 0
    private var tasks: [String: Task<Void, Never>] = [:]

    func beginRead() -> ReadTicket? {
        guard pending.isEmpty else { return nil }
        revision &+= 1
        return ReadTicket(epoch: epoch, revision: revision)
    }
    func accepts(_ ticket: ReadTicket) -> Bool {
        !Task.isCancelled && pending.isEmpty && ticket.epoch == epoch && ticket.revision == revision
    }
    @discardableResult
    func run(key: String, operation: @escaping () async -> Bool, onSuccess: @escaping () -> Void = {}) -> Task<Void, Never>? {
        guard pending.insert(key).inserted else { return nil }
        revision &+= 1
        let generation = epoch
        let task = Task { @MainActor in
            defer {
                if epoch == generation {
                    pending.remove(key); tasks[key] = nil; revision &+= 1
                    if pending.isEmpty { settledRevision &+= 1 }
                }
            }
            guard !Task.isCancelled, epoch == generation else { return }
            let accepted = await operation()
            guard accepted, !Task.isCancelled, epoch == generation else { return }
            onSuccess()
        }
        tasks[key] = task
        return task
    }
    func invalidateReads() { revision &+= 1 }

    func stop() {
        epoch = UUID(); revision &+= 1
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll(); pending.removeAll()
    }
}
