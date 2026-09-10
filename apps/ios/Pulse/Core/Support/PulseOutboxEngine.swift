import Foundation
import Combine

/// Sender boundary for the outbox engine — PulseAPIClient conforms in the
/// app; tests stub it HERE (this is the only mock seam Wave 0 allows).
public protocol PulseOutboxSending: Sendable {
    func sendMessage(conversationId: String, content: String, replyToId: String?) async throws -> WireChatMessage
}

extension PulseAPIClient: PulseOutboxSending {}

/// Outbox engine — offline sends, mirroring src/lib/pulse-outbox.ts:
///   • FIFO queue, hard cap 50 (oldest dropped beyond it)
///   • flush drains in order and STOPS at the first network-class failure
///     (temporal order — later sends must never overtake earlier ones)
///   • success → real row upserted, `local_<clientId>` temp row deleted,
///     outbox entry deleted, UI notified
///   • 4xx (validation/forbidden/404/…) → entry dropped + temp row deleted +
///     honest toast (retrying could never succeed)
///   • network failure → attempts++, retried on the next trigger
/// Triggers: session start with pending entries, socket (re)connect, app
/// foreground, BGAppRefreshTask and a 60s self-heal timer while entries exist.
@MainActor
public final class PulseOutboxEngine: ObservableObject {
    public enum Event {
        case delivered(message: WireChatMessage, conversationId: String, clientId: String)
        case dropped(clientId: String, conversationId: String, reason: String)
    }

    /// Web MAX_QUEUE parity.
    public static let maxEntries = 50
    /// BGAppRefreshTask identifier (registered in PulseApp, permitted in
    /// project.yml → BGTaskSchedulerPermittedIdentifiers).
    public static let backgroundTaskIdentifier = "app.pulse.chat.outbox"
    /// Self-heal cadence while entries remain queued.
    static let healIntervalNanos: UInt64 = 60_000_000_000

    /// BGTaskScheduler bridge — the launch handler has no view graph, so the
    /// live engine registers itself here (weak-style swap on each session).
    public static var active: PulseOutboxEngine?

    @Published public private(set) var pendingCount = 0
    @Published public private(set) var flushing = false

    /// Engine → session pipe (delivered/dropped events feed the UI).
    public var onEvent: ((Event) -> Void)?

    private let store: PulseStore
    private let senderProvider: () -> (any PulseOutboxSending)?
    private var healTask: Task<Void, Never>?
    private var working = false

    public init(store: PulseStore, senderProvider: @escaping () -> (any PulseOutboxSending)?) {
        self.store = store
        self.senderProvider = senderProvider
        pendingCount = store.countOutbox()
        if pendingCount > 0 {
            startHealTimer()
        }
    }

    // The heal task holds `weak self`; after deallocation the loop exits on
    // the next tick (no explicit cancel needed in deinit — keeps this class
    // free of nonisolated deinit actor-isolation questions).

    // ── queue management ─────────────────────────────────────

    /// Enqueues one outgoing message (clientId dedupes via UNIQUE).
    public func append(conversationId: String, clientId: String, content: String, kind: String = "text") {
        do {
            try store.appendOutbox(conversationId: conversationId, clientId: clientId, content: content, kind: kind)
        } catch {
            // Duplicate clientId (double-tap) — already queued, nothing to do.
        }
        trimToLimit()
        pendingCount = store.countOutbox()
        startHealTimer()
    }

    /// Web parity: `next.length > MAX_QUEUE ? next.slice(-MAX_QUEUE)`.
    private func trimToLimit() {
        guard let entries = try? store.outboxAll(), entries.count > Self.maxEntries else { return }
        let excess = entries.dropLast(Self.maxEntries)
        for entry in excess {
            guard let id = entry.id else { continue }
            try? store.deleteOutbox(id: id)
        }
    }

    /// Total held messages across conversations.
    public func count() -> Int {
        pendingCount
    }

    // ── flushing ─────────────────────────────────────────────

    /// Drains the queue in FIFO order. Safe to call from every trigger —
    /// re-entrant calls are ignored while a flush is running.
    public func flush() async {
        guard !working else { return }
        guard let sender = senderProvider() else { return }
        let entries = (try? store.outboxAll()) ?? []
        guard !entries.isEmpty else {
            pendingCount = 0
            stopHealTimer()
            return
        }

        working = true
        flushing = true
        defer {
            working = false
            flushing = false
            pendingCount = store.countOutbox()
            if pendingCount == 0 { stopHealTimer() }
        }

        for entry in entries {
            if Task.isCancelled { break }
            do {
                let real = try await sender.sendMessage(
                    conversationId: entry.conversationId,
                    content: entry.content,
                    replyToId: nil,
                )
                try? store.upsert(messages: [real])
                try? store.deleteMessage(id: Self.tempMessageId(clientId: entry.clientId))
                if let id = entry.id { try? store.deleteOutbox(id: id) }
                pendingCount = store.countOutbox()
                onEvent?(.delivered(message: real, conversationId: entry.conversationId, clientId: entry.clientId))
            } catch {
                if Self.isDroppable(error) {
                    try? store.deleteMessage(id: Self.tempMessageId(clientId: entry.clientId))
                    if let id = entry.id { try? store.deleteOutbox(id: id) }
                    pendingCount = store.countOutbox()
                    onEvent?(.dropped(clientId: entry.clientId, conversationId: entry.conversationId, reason: Self.describe(error)))
                    continue
                }
                // Network-class failure — keep the entry, stop the drain so
                // later sends never overtake this one (web break parity).
                try? store.bumpOutboxAttempts(id: entry.id ?? 0)
                break
            }
        }
    }

    // ── self-heal timer ──────────────────────────────────────

    /// 60s sweep while entries exist — recovers from triggers the app missed
    /// (killed mid-flush, missed reconnect, …).
    private func startHealTimer() {
        guard healTask == nil else { return }
        healTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.healIntervalNanos)
                guard !Task.isCancelled, let self else { return }
                await self.flush()
                if self.pendingCount == 0 { break }
            }
        }
    }

    private func stopHealTimer() {
        healTask?.cancel()
        healTask = nil
    }

    // ── helpers ──────────────────────────────────────────────

    /// Optimistic bubble id for a queued send (web `temp-<clientId>` parity).
    public static func tempMessageId(clientId: String) -> String {
        "local_\(clientId)"
    }

    /// 4xx-class failures can never succeed by retrying — drop them.
    /// Everything else (network / 5xx / unknown transport errors) retries.
    public static func isDroppable(_ error: Error) -> Bool {
        guard let failure = error as? PulseAPIClient.Failure else { return false }
        if let status = failure.status { return (400 ..< 500).contains(status) }
        switch failure.kind {
        case .validation, .forbidden, .notFound, .auth, .rateLimited: return true
        case .network, .server, .unknown: return false
        }
    }

    public static func describe(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message, !message.isEmpty {
            return message
        }
        return "The gateway is unreachable."
    }
}
