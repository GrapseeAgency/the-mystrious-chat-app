import Foundation

/// Global admission is shared by draft/edit and scheduled-command replay. Read
/// the same compact hold as the outbox, but ignore message-room slow-mode holds.
/// Lock order is admission -> database; callers must not enter from a DB closure.
final class PulseComposerRetryAdmission: @unchecked Sendable {
    private let lock = NSLock()
    private let wallNow: @Sendable () -> TimeInterval
    private let monotonicNow: @Sendable () -> TimeInterval
    private var notBefore: TimeInterval = 0
    private var loaded: TimeInterval?
    private var unsaved: TimeInterval?

    init(wallNow: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSince1970 },
         monotonicNow: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.wallNow = wallNow; self.monotonicNow = monotonicNow
    }
    var isHeld: Bool {
        lock.lock(); defer { lock.unlock() }
        return notBefore > monotonicNow()
    }
    func canRun(store: PulseStore) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        try restore(store)
        return notBefore <= monotonicNow()
    }
    func hold(store: PulseStore, seconds: Int?) throws {
        lock.lock(); defer { lock.unlock() }
        let duration = TimeInterval(min(86_400, max(1, seconds ?? 60)))
        notBefore = max(notBefore, monotonicNow() + duration)
        unsaved = max(unsaved ?? 0, wallNow() + duration)
        try restore(store) // A failed commit retains the in-process hold and the unsaved deadline.
    }
    private func restore(_ store: PulseStore) throws {
        if let until = unsaved {
            _ = try store.holdOutboxRetry(scope: "", until: until)
            unsaved = nil
        }
        if let until = try store.outboxRetryDeadlines(now: wallNow())[""], loaded != until {
            loaded = until
            notBefore = max(notBefore, monotonicNow() + min(86_400, max(0, until - wallNow())))
        }
    }
}
