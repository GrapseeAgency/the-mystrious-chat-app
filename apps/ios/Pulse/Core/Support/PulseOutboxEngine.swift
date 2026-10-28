import Foundation
import Combine
import Network

/// Sender boundary for the outbox engine - PulseAPIClient conforms in the
/// app; tests stub it HERE (this is the only mock seam Wave 0 allows).
/// W1-DATA-B - the requirement mirrors PulseAPIClient.sendMessage's FULL
/// parameter list (Swift witness matching ignores default arguments, so a
/// defaulted-param method would no longer witness a shorter requirement).
/// Upload bytes and the eventual message share one durable operation. A known
/// uploaded path is persisted before sending so a lost message ACK never reuploads.
public protocol PulseOutboxSending: Sendable {
    func uploadMedia(dataUrl: String) async throws -> String
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
    ) async throws -> WireChatMessage
    func sendMessageWithStreak(
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
    ) async throws -> PulseSendResult
}

public extension PulseOutboxSending {
    func sendMessageWithStreak(
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
    ) async throws -> PulseSendResult {
        let message = try await sendMessage(conversationId: conversationId, clientId: clientId, content: content,
            replyToId: replyToId, parentId: parentId, imagePath: imagePath, audioPath: audioPath,
            durationMs: durationMs, filePath: filePath, fileName: fileName, fileSize: fileSize,
            kind: kind, viewOnce: viewOnce, topicId: topicId, payload: payload, anon: anon)
        return PulseSendResult(message: message, streak: nil)
    }
}

extension PulseAPIClient: PulseOutboxSending {}

/// R1-W2B D28 - the queued forward envelope (F-MS-10 offline column).
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
    public var replyToId: String?
    public var parentId: String?
    public var topicId: String?
    public var payload: String?
    public var anon: Bool?
    public var viewOnce: Bool?
    public var uploadDataUrl: String?

    public init(kind: String? = nil, imagePath: String? = nil, audioPath: String? = nil, durationMs: Double? = nil,
                filePath: String? = nil, fileName: String? = nil, fileSize: Int? = nil,
                replyToId: String? = nil, parentId: String? = nil, topicId: String? = nil,
                payload: String? = nil, anon: Bool? = nil, viewOnce: Bool? = nil, uploadDataUrl: String? = nil) {
        self.kind = kind
        self.imagePath = imagePath
        self.audioPath = audioPath
        self.durationMs = durationMs
        self.filePath = filePath
        self.fileName = fileName
        self.fileSize = fileSize
        self.replyToId = replyToId
        self.parentId = parentId
        self.topicId = topicId
        self.payload = payload
        self.anon = anon
        self.viewOnce = viewOnce
        self.uploadDataUrl = uploadDataUrl
    }

    /// Serialized for the outbox row. Enqueue must reject encoding failure;
    /// it must never downgrade an attachment to a text-only message.
    public static func encode(_ forward: PulseOutboxForward) -> String? {
        guard let data = try? JSONEncoder().encode(forward) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public static func decode(_ json: String?) -> PulseOutboxForward? {
        guard let json, !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(PulseOutboxForward.self, from: data)
    }
}

/// Outbox engine - offline sends, mirroring src/lib/pulse-outbox.ts:
///   • Per-room FIFO durable queue, bounded drain batches (never evict pending sends)
///   • flush drains in order and STOPS at the first network-class failure
///     (temporal order - later sends must never overtake earlier ones)
///   • success → real row upserted, `local_<clientId>` temp row deleted,
///     outbox entry deleted, UI notified
///   • Permanent 4xx → failed intent + envelope retained, pending echo retracted;
///     no automatic retry and no overwrite of the user's current draft
///   • network failure → attempts++, retried on the next trigger
/// Triggers: session start with pending entries, socket (re)connect, app
/// foreground, BGAppRefreshTask, a 60s self-heal timer while entries exist
/// and - R1-W2B D41 - the NWPathMonitor satisfaction edge (spec F-OF-04:
/// "online" flush trigger; the path turning satisfied flushes immediately
/// instead of waiting up to 60 s for the heal sweep).
@MainActor
public final class PulseOutboxEngine: ObservableObject {
    public enum Event {
        case delivered(message: WireChatMessage, conversationId: String, clientId: String)
        case dropped(clientId: String, conversationId: String, reason: String)
    }

    /// Per-pass batch size, NOT a destructive storage limit.
    public static let maxEntries = 50
    /// BGAppRefreshTask identifier (registered in PulseApp, permitted in
    /// project.yml → BGTaskSchedulerPermittedIdentifiers).
    public static let backgroundTaskIdentifier = "app.pulse.chat.outbox"
    /// Self-heal cadence while entries remain queued.
    static let healIntervalNanos: UInt64 = 60_000_000_000

    /// BGTaskScheduler bridge - the launch handler has no view graph, so the
    /// live engine registers itself here (weak-style swap on each session).
    public static var active: PulseOutboxEngine?

    @Published public private(set) var pendingCount = 0
    @Published public private(set) var storageUnavailable = false
    @Published public private(set) var flushing = false

    /// Engine → session pipe (delivered/dropped events feed the UI).
    public var onEvent: ((Event) -> Void)?
    public var onAcknowledgement: ((PulseSendResult) -> Void)?
    /// The background task awaits this pass, rather than returning success with
    /// an unowned fire-and-forget read delivery still in flight.
    public var onComposerJournalFlush: (() async -> Bool)?
    public var onSavedJournalFlush: (() async -> Bool)?
    public var onReadJournalFlush: (() async -> Bool)?
    public var onBadgeRefresh: (() async -> Bool)?
    var onReadStateInvalidation: ((String) -> Void)?
    func invalidateReadProjections(_ room: String) {
        guard canWork, !room.isEmpty, room.count <= 128 else { return }
        onReadStateInvalidation?(room)
    }


    public func flushBackgroundQueues() async -> Bool {
        guard !Task.isCancelled, canWork else { return false }
        // A cold-process wake has a short budget. Neither a large upload nor a
        // read-state outage may hold the other lane hostage. Structured children
        // are still cancelled/awaited with the owning background task.
        async let messages = flush()
        async let attention = flushBackgroundReadState()
        async let bookmarks = onSavedJournalFlush?() ?? true
        async let composer = onComposerJournalFlush?() ?? true
        let (sent, reconciled, saved, composed) = await (messages, attention, bookmarks, composer)
        return sent && reconciled && saved && composed && !Task.isCancelled && canWork
    }

    private func flushBackgroundReadState() async -> Bool {
        guard !Task.isCancelled, canWork else { return false }
        let reads = await onReadJournalFlush?() ?? false
        guard !Task.isCancelled, canWork else { return false }
        // The adapter treats optional OS badge permission as best effort, but
        // failed server snapshots / exact-alert proofs must remain incomplete.
        let projections = await onBadgeRefresh?() ?? true
        return reads && projections && !Task.isCancelled && canWork
    }
    /// Session fallback still blocks dispatch if recording a denial on disk failed.
    public var additionalThreadAccessDenied: ((String) -> Bool)?

    private let store: PulseStore
    private let senderProvider: () -> (any PulseOutboxSending)?
    private var healTask: Task<Void, Never>?
    /// Diagnostics: unknown queue state must retain a recovery trigger.
    var recoveryScheduled: Bool { healTask != nil }
    private var healGeneration = UUID()
    private var healSleeping = false
    private var working = false
    private var stopped = false
    private let isCurrent: () -> Bool
    private var canWork: Bool { !stopped && isCurrent() }
    private var retryNotBefore: TimeInterval = 0
    private var roomRetryNotBefore: [String: TimeInterval] = [:]
    private let monotonicNow: () -> TimeInterval
    private let wallNow: () -> TimeInterval
    private var loadedRetryDeadlines: [String: TimeInterval] = [:]
    private var unsavedRetryDeadlines: [String: TimeInterval] = [:]
    // R1-W2B D41 - NWPathMonitor flush trigger (spec F-OF-04). The monitor
    // lives as long as the engine (its handler only weakly references self,
    // so engine teardown releases it) and its satisfaction EDGE calls the
    // same flush every other trigger uses.
    private let pathMonitor = NWPathMonitor()
    private var pathMonitorStarted = false
    private var lastPathSatisfied = false

    public init(store: PulseStore, monitorNetwork: Bool = true,
                monotonicNow: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
                wallNow: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 },
                isCurrent: @escaping () -> Bool = { true }, senderProvider: @escaping () -> (any PulseOutboxSending)?) {
        self.store = store
        self.monotonicNow = monotonicNow
        self.wallNow = wallNow
        self.isCurrent = isCurrent
        self.senderProvider = senderProvider
        reloadPending()
        if monitorNetwork { startPathMonitor() }
    }

    // The heal task holds `weak self`; after deallocation the loop exits on
    // the next tick (no explicit cancel needed in deinit - keeps this class
    // free of nonisolated deinit actor-isolation questions).

    // queue management

    /// Enqueues one outgoing message (clientId dedupes via UNIQUE).
    @discardableResult
    public func append(conversationId: String, clientId: String, content: String, kind: String = "text") -> Bool {
        do {
            try store.appendOutbox(conversationId: conversationId, clientId: clientId, content: content, kind: kind)
        } catch { return false }
        reloadPending()
        return true
    }

    /// R1-W2B D28 - enqueues one queued FORWARD. `forward` carries the
    /// stored kind + media paths; the flush re-POSTs the exact body.
    @discardableResult
    public func appendForward(conversationId: String, clientId: String, content: String, forward: PulseOutboxForward) -> Bool {
        guard let encoded = PulseOutboxForward.encode(forward) else { return false }
        do {
            try store.appendOutbox(
                conversationId: conversationId,
                clientId: clientId,
                content: content,
                kind: forward.kind ?? "text",
                payloadJson: encoded,
            )
        } catch { return false }
        reloadPending()
        return true
    }

    /// Stop the account-owned engine before replacing its transport or store.
    public func stop() {
        stopped = true
        stopHealTimer()
        pathMonitor.cancel()
        onEvent = nil
        onAcknowledgement = nil
        additionalThreadAccessDenied = nil
    }

    /// Only explicit message-endpoint slow_mode failures are room scoped.
    /// Missing/unknown codes and upload throttles remain global.
    public func holdRetries(seconds: Int, conversationId: String? = nil) {
        let deadline = monotonicNow() + TimeInterval(min(86_400, max(1, seconds)))
        if let conversationId {
            roomRetryNotBefore[conversationId] = max(roomRetryNotBefore[conversationId] ?? 0, deadline)
        } else { retryNotBefore = max(retryNotBefore, deadline) }
        let scope = conversationId ?? ""
        let until = wallNow() + TimeInterval(min(86_400, max(1, seconds)))
        unsavedRetryDeadlines[scope] = max(unsavedRetryDeadlines[scope] ?? 0, until)
        do { try restoreRetryHolds() }
        catch { storageUnavailable = true }
        // A short server cooldown must not wait behind the old 60s sweep.
        if healSleeping { stopHealTimer() }
        if canWork { startHealTimer() }
    }

    private func restoreRetryHolds() throws {
        _ = try? store.pruneConsumedDraftCheckpoints()
        for (scope, until) in unsavedRetryDeadlines {
            _ = try store.holdOutboxRetry(scope: scope, until: until)
            unsavedRetryDeadlines.removeValue(forKey: scope)
        }
        for (scope, until) in try store.outboxRetryDeadlines(now: wallNow()) {
            guard loadedRetryDeadlines[scope] != until else { continue }
            loadedRetryDeadlines[scope] = until
            let remaining = min(86_400, max(0, until - wallNow()))
            guard remaining > 0 else { continue }
            let deadline = monotonicNow() + remaining
            if scope.isEmpty { retryNotBefore = max(retryNotBefore, deadline) }
            else { roomRetryNotBefore[scope] = max(roomRetryNotBefore[scope] ?? 0, deadline) }
        }
    }

    private func blockedConversations() -> Set<String> {
        let now = monotonicNow()
        roomRetryNotBefore = roomRetryNotBefore.filter { $0.value > now }
        return Set(roomRetryNotBefore.keys)
    }

    private var recoveryDelay: TimeInterval {
        let now = monotonicNow()
        if retryNotBefore > now { return retryNotBefore - now }
        return min(60, roomRetryNotBefore.values.filter { $0 > now }.map { $0 - now }.min() ?? 60)
    }

    private func refreshPendingCount() -> Bool {
        do {
            pendingCount = try store.countPendingOutbox()
            storageUnavailable = false
            return true
        } catch {
            // Keep the last known count, not a made-up empty or nonempty queue.
            storageUnavailable = true
            return false
        }
    }

    public func reloadPending() {
        let known = refreshPendingCount()
        if !known || pendingCount > 0 {
            if canWork { startHealTimer() }
        } else { stopHealTimer() }
    }

    public func count() -> Int {
        pendingCount
    }

    private func requireThreadAccess(_ rootId: String?) throws {
        guard let rootId else { return }
        if additionalThreadAccessDenied?(rootId) == true { throw PulseStore.ThreadAccessError() }
        if try store.threadAccessDenied(rootId: rootId) { throw PulseStore.ThreadAccessError() }
    }

    // flushing

    /// Per-room FIFO; only explicit room cooldowns allow bypass. Safe from every trigger -
    /// re-entrant calls are ignored while a flush is running.
    @discardableResult
    public func flush() async -> Bool {
        guard canWork, !working, !Task.isCancelled else { return false }
        do { try restoreRetryHolds() }
        catch { storageUnavailable = true; startHealTimer(); return false }
        guard monotonicNow() >= retryNotBefore else { startHealTimer(); return false }
        guard let sender = senderProvider() else { return false }
        guard refreshPendingCount() else { startHealTimer(); return false }
        guard pendingCount > 0 else {
            pendingCount = 0
            stopHealTimer()
            return true
        }

        working = true
        flushing = true
        defer {
            working = false
            flushing = false
            reloadPending()
        }

        var servedRooms = Set<String>()
        for _ in 0..<Self.maxEntries {
            if Task.isCancelled || !canWork { break }
            let entry: OutboxRow
            do {
                var next = try store.nextPendingOutbox(excludingConversations: blockedConversations().union(servedRooms))
                if next == nil && !servedRooms.isEmpty {
                    servedRooms.removeAll()
                    next = try store.nextPendingOutbox(excludingConversations: blockedConversations())
                }
                guard let next else { break }
                entry = next
                servedRooms.insert(entry.conversationId)
            } catch { return false }
            var postingMessage = false
            do {
                guard try store.isPendingOutbox(clientId: entry.clientId) else { continue }
                // R1-W2B D28 - a forward entry re-POSTs its stored kind +
                // media paths (web ForwardPayload parity); plain sends keep
                // the text-only body.
                var forward = PulseOutboxForward.decode(entry.payloadJson)
                if entry.payloadJson != nil && forward == nil { throw CocoaError(.coderReadCorrupt) }
                try requireThreadAccess(forward?.parentId)
                if let dataUrl = forward?.uploadDataUrl {
                    guard var uploaded = forward, ["image", "file", "audio"].contains(uploaded.kind ?? "") else {
                        throw CocoaError(.coderReadCorrupt)
                    }
                    let path = try await sender.uploadMedia(dataUrl: dataUrl)
                    guard canWork, !Task.isCancelled else { return false }
                    guard !path.isEmpty else { throw CocoaError(.coderReadCorrupt) }
                    switch uploaded.kind {
                    case "image": uploaded.imagePath = path
                    case "audio": uploaded.audioPath = path
                    default: uploaded.filePath = path
                    }
                    uploaded.uploadDataUrl = nil
                    // A user may discard this operation while its upload is in flight.
                    guard try store.recordOutboxUpload(clientId: entry.clientId, envelope: uploaded) else { continue }
                    forward = uploaded
                }
                // Denial/discard can arrive during upload. Keep its durable receipt, but do not POST.
                guard try store.isPendingOutbox(clientId: entry.clientId) else { continue }
                try requireThreadAccess(forward?.parentId)
                postingMessage = true
                let receipt = try await sender.sendMessageWithStreak(
                    conversationId: entry.conversationId,
                    clientId: entry.clientId,
                    content: entry.content,
                    replyToId: forward?.replyToId,
                    parentId: forward?.parentId,
                    imagePath: forward?.imagePath,
                    audioPath: forward?.audioPath,
                    durationMs: forward?.durationMs,
                    filePath: forward?.filePath,
                    fileName: forward?.fileName,
                    fileSize: forward?.fileSize,
                    kind: forward?.kind ?? entry.kind,
                    viewOnce: forward?.viewOnce,
                    topicId: forward?.topicId,
                    payload: try forward?.payload.map { raw in
                        guard let data = raw.data(using: .utf8),
                              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                            throw CocoaError(.coderReadCorrupt)
                        }
                        return object
                    },
                    anon: forward?.anon
                )
                guard canWork, !Task.isCancelled else { return false }
                let real = try store.resolveDelivery(clientId: entry.clientId, message: receipt.message)
                _ = refreshPendingCount()
                onEvent?(.delivered(message: real, conversationId: entry.conversationId, clientId: entry.clientId))
                onAcknowledgement?(receipt)
            } catch {
                if Task.isCancelled || !canWork { return false }
                if let failure = error as? PulseAPIClient.Failure,
                   failure.status == 429 || failure.kind == .rateLimited || (failure.status == 503 && failure.retryAfter != nil) {
                    let roomOnly = postingMessage && failure.status == 429 && failure.code == "slow_mode"
                    holdRetries(seconds: failure.retryAfter ?? 60, conversationId: roomOnly ? entry.conversationId : nil)
                    if roomOnly {
                        try? store.bumpOutboxAttempts(id: entry.id ?? 0)
                        continue
                    }
                }
                let damagedEnvelope = (error as? CocoaError)?.code == .coderReadCorrupt
                let refusedThread = error is PulseStore.ThreadAccessError
                if Self.isDroppable(error) || damagedEnvelope || refusedThread {
                    let reason = refusedThread ? "Thread access was refused. Reopen the thread before retrying this saved reply." :
                        (damagedEnvelope ? "Saved message metadata is damaged" : Self.describe(error))
                    do {
                        if try store.rejectOutbox(clientId: entry.clientId, reason: reason) {
                            onEvent?(.dropped(clientId: entry.clientId, conversationId: entry.conversationId,
                                reason: "Saved in Drafts & outbox: " + reason))
                        }
                    } catch { return false } // keep intent if the local state transition failed
                    _ = refreshPendingCount()
                    continue
                }
                // Network-class failure - keep the entry, stop the drain so
                // later sends never overtake this one (web break parity).
                try? store.bumpOutboxAttempts(id: entry.id ?? 0)
                break
            }
        }
        return !Task.isCancelled && canWork && refreshPendingCount() && pendingCount == 0
    }

    // self-heal timer

    /// 60s sweep while entries exist - recovers from triggers the app missed
    /// (killed mid-flush, missed reconnect, …).
    private func startHealTimer() {
        guard canWork else { return }
        guard healTask == nil else { return }
        let generation = UUID()
        healGeneration = generation
        healTask = Task { [weak self] in
            defer { if self?.healGeneration == generation { self?.healTask = nil } }
            while !Task.isCancelled {
                let seconds = self?.recoveryDelay ?? 60
                self?.healSleeping = true
                do { try await Task.sleep(nanoseconds: UInt64(max(0.001, seconds) * 1_000_000_000)) }
                catch { return }
                guard !Task.isCancelled, let self else { return }
                self.healSleeping = false
                await self.flush()
                if self.pendingCount == 0 && !self.storageUnavailable { break }
            }
        }
    }

    private func stopHealTimer() {
        healSleeping = false
        healGeneration = UUID()
        healTask?.cancel()
        healTask = nil
    }

    // connectivity trigger (R1-W2B D41 - NWPathMonitor, F-OF-04)

    /// Watches the network path; the moment it turns satisfied (wifi/cell
    /// comes back) with entries still queued, the SAME flush runs - no
    /// waiting for the 60 s heal sweep. Idempotent: re-entrant flushes are
    /// already ignored while one is draining.
    private func startPathMonitor() {
        guard !pathMonitorStarted else { return }
        pathMonitorStarted = true
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == NWPath.Status.satisfied
            // The handler fires on the monitor's private queue - hop to the
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
        guard satisfied, !wasSatisfied, pendingCount > 0 || storageUnavailable else { return }
        Task { [weak self] in
            await self?.flush()
        }
    }

    // helpers

    /// Optimistic bubble id for a queued send (web `temp-<clientId>` parity).
    public static func tempMessageId(clientId: String) -> String {
        "local_\(clientId)"
    }

    /// 4xx-class failures can never succeed by retrying - drop them.
    /// Everything else (network / 5xx / unknown transport errors) retries.
    public static func isDroppable(_ error: Error) -> Bool {
        guard let failure = error as? PulseAPIClient.Failure else { return false }
        if let status = failure.status { return (400 ..< 500).contains(status) && ![401, 408, 425, 429].contains(status) }
        switch failure.kind {
        case .validation, .forbidden, .notFound: return true
        case .auth, .rateLimited, .network, .server, .unknown: return false
        }
    }

    public static func describe(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message, !message.isEmpty {
            return message
        }
        return "The gateway is unreachable."
    }
}
