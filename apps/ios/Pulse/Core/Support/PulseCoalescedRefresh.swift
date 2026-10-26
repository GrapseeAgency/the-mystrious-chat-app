import Foundation

/// Coalesce a burst without repeatedly cancelling an in-flight canonical GET.
/// Continuous events must make progress too. A changed owner explicitly cancels
/// the generation; late completion cannot clear its replacement task.
@MainActor
final class PulseCoalescedRefresh {
    private var pending: (@MainActor () async -> Void)?
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private let initialDelay: UInt64

    init(initialDelay: UInt64 = 150_000_000) { self.initialDelay = initialDelay }

    @discardableResult
    func request(_ operation: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        pending = operation
        if let task { return task }
        let epoch = generation, delay = initialDelay
        let started = Task { [weak self] in
            do {
                if delay > 0 { try await Task.sleep(nanoseconds: delay) }
                while let self, !Task.isCancelled, self.generation == epoch,
                      let next = self.pending {
                    self.pending = nil
                    await next()
                    if self.pending != nil, delay > 0 { try await Task.sleep(nanoseconds: delay) }
                }
            } catch { /* cancellation before the first GET */ }
            if let self, self.generation == epoch { self.task = nil }
        }
        task = started
        return started
    }

    func cancel() {
        generation = UUID()
        pending = nil
        task?.cancel()
        task = nil
    }
}
