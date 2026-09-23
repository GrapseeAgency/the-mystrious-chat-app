import Foundation
import Combine
import Network

/// Sender boundary for the outbox engine — PulseAPIClient conforms in the
/// app; tests stub it HERE (this is the only mock seam Wave 0 allows).
/// W1-DATA-B — the requirement mirrors PulseAPIClient.sendMessage's FULL
/// parameter list (Swift witness matching ignores default arguments, so a
/// defaulted-param method would no longer witness a shorter requirement).
/// The engine only ever fills the text fields — media/thread sends are
/// online-only and never queued (spec §1.2). R1-W2B D28 relaxes exactly one
/// case: FORWARDS (F-MS-10 "queued if offline") queue with their stored
/// kind + media paths via PulseOutboxForward.
public protocol PulseOutboxSending: Sendable {
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
    ) async throws -> WireChatMessage
}

extension PulseAPIClient: PulseOutboxSending {}

/// R1-W2B D28 — the queued forward envelope (F-MS-10 offline column).
/// Mirrors web forward-sheet.tsx ForwardPayload (L23-33): the copy re-POSTs
/// with the ORIGINAL stored media paths (no re-upload) and the matching
/// kind (documents ride kind "file" with their name/size). Encoded as the
/// outbox row's payloadJson; the engine decodes it on flush.
public struct PulseOutboxForward: Codable, Equatable, Sendable {
    public var kind: String?
    public var imagePath: String?
    public var audioPath: String?
    public var durationMs: Double?
    public var filePath: String?
    public var fileName: String?
    public var fileSize: Int?

    public init(kind: String?, imagePath: String?, audioPath: String?, durationMs: Double?,
                filePath: String?, fileName: String?, fileSize: Int?) {
        self.kind = kind
        self.imagePath = imagePath
        self.audioPath = audioPath
        self.durationMs = durationMs
        self.filePath = filePath
        self.fileName = fileName
        self.fileSize = fileSize
    }

    /// Serialized for the outbox row (nil-safe: a failed encode → nil payload
    /// → the flush degrades to a plain text send with the forward's content).
    public static func encode(_ forward: PulseOutboxForward) -> String? {
        guard let data = try? JSONEncoder().encode(forward) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public static func decode(_ json: String?) -> PulseOutboxForward? {
        guard let json, !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(PulseOutboxForward.self, from: data)
    }
}

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
/// foreground, BGAppRefreshTask, a 60s self-heal timer while entries exist
/// and — R1-W2B D41 — the NWPathMonitor satisfaction edge (spec F-OF-04:
/// "online" flush trigger; the path turning satisfied flushes immediately
/// instead of waiting up to 60 s for the heal sweep).
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
    // R1-W2B D41 — NWPathMonitor flush trigger (spec F-OF-04). The monitor
    // lives as long as the engine (its handler only weakly references self,
    // so engine teardown releases it) and its satisfaction EDGE calls the
    // same flush every other trigger uses.
    private let pathMonitor = NWPathMonitor()
    private var pathMonitorStarted = false
    private var lastPathSatisfied = false

    public init(store: PulseStore, senderProvider: @escaping () -> (any PulseOutboxSending)?) {
        self.store = store
        self.senderProvider = senderProvider
        pendingCount = store.countOutbox()
        if pendingCount > 0 {
            startHealTimer()
        }
        startPathMonitor()
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

    /// R1-W2B D28 — enqueues one queued FORWARD. `forward` carries the
    /// stored kind + media paths; the flush re-POSTs the exact body.
    public func appendForward(conversationId: String, clientId: String, content: String, forward: PulseOutboxForward) {
        do {
            try store.appendOutbox(
                conversationId: conversationId,
                clientId: clientId,
                content: content,
                kind: forward.kind ?? "text",
                payloadJson: PulseOutboxForward.encode(forward),
            )
        } catch {
            // Duplicate clientId — already queued.
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
                // R1-W2B D28 — a forward entry re-POSTs its stored kind +
                // media paths (web ForwardPayload parity); plain sends keep
                // the text-only body.
                let forward = PulseOutboxForward.decode(entry.payloadJson)
                let real = try await sender.sendMessage(
                    conversationId: entry.conversationId,
                    content: entry.content,
                    replyToId: nil,
                    parentId: nil,
                    imagePath: forward?.imagePath,
                    audioPath: forward?.audioPath,
                    durationMs: forward?.durationMs,
                    filePath: forward?.filePath,
                    fileName: forward?.fileName,
                    fileSize: forward?.fileSize,
                    kind: forward?.kind,
                    viewOnce: nil,
                    topicId: nil,
                    payload: nil,
                    anon: nil
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

    // ── connectivity trigger (R1-W2B D41 — NWPathMonitor, F-OF-04) ──

    /// Watches the network path; the moment it turns satisfied (wifi/cell
    /// comes back) with entries still queued, the SAME flush runs — no
    /// waiting for the 60 s heal sweep. Idempotent: re-entrant flushes are
    /// already ignored while one is draining.
    private func startPathMonitor() {
        guard !pathMonitorStarted else { return }
        pathMonitorStarted = true
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == NWPath.Status.satisfied
            // The handler fires on the monitor's private queue — hop to the
            // engine's MainActor before touching state.
            Task { @MainActor in
                self?.handlePathUpdate(satisfied: satisfied)
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "app.pulse.chat.outbox.path"))
    }

    private func handlePathUpdate(satisfied: Bool) {
        let wasSatisfied = lastPathSatisfied
        lastPathSatisfied = satisfied
        guard satisfied, !wasSatisfied, pendingCount > 0 else { return }
        Task { [weak self] in
            await self?.flush()
        }
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
