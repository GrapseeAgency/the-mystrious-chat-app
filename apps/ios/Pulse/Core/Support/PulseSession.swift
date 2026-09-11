import Foundation
import Combine

/// Outbox events relayed to feature view models (the open room swaps its
/// queued placeholder for the real row; drops remove it honestly).
public enum PulseOutboxEvent: Sendable {
    case delivered(message: WireChatMessage, conversationId: String)
    case dropped(clientId: String, conversationId: String)
}

/// App-wide realtime session: owns the viewer-bound API client, the Socket.IO
/// client, presence, typing state and the local store. View models subscribe
/// to `signals` (UDF: session = transport, features = state).
@MainActor
public final class PulseSession: ObservableObject {
    public struct Typer: Equatable {
        public let userId: String
        public let userName: String
        let expiresAt: Date
    }

    // Identity + transport
    @Published public private(set) var viewer: PulseViewer?
    @Published public private(set) var onlineUserIds: Set<String> = []
    @Published public private(set) var typers: [String: [Typer]] = [:]
    @Published public private(set) var connected = false
    /// Bumped whenever a surface mutates the inbox (e.g. a fresh DM create)
    /// so the Chats view model can re-fetch without polling.
    @Published public private(set) var inboxRefreshTick = 0
    /// Wave 0 — bumped on every background mutation that should make live
    /// surfaces re-fetch (message:edited/pinned/viewed, poll:voted,
    /// link:preview, translation:added, conversation:updated, outbox
    /// deliveries). Features subscribe; later waves consume more cases.
    @Published public private(set) var realtimeRefreshTick = 0
    /// Live total unread feeding the dock badge (Chats view model writes it).
    @Published public var dockUnreadCount = 0
    /// True while a chat room owns the screen — the dock hides itself.
    @Published public var roomVisible = false
    /// Dock "More → Search" asks the Chats tab to enter search mode.
    @Published public private(set) var searchRequestTick = 0
    /// Dock compose / More → Saved hand a freshly-resolved conversation to the
    /// Chats tab's NavigationStack (the dock lives above the shell, the stack
    /// lives inside ChatsView — this is the bridge, same pattern as search).
    @Published public private(set) var pendingOpenRoom: WireConversationSummary?
    /// Wave 2 saved library — jump-to-message handoff consumed by ChatsView
    /// together with pendingOpenRoom (the SAME path search hits already use).
    @Published public private(set) var pendingJumpMessageId: String?
    /// Opened on session start (nil before onboarding) — @Published so late
    /// view models can rehydrate the offline cache the moment it exists.
    @Published public private(set) var store: PulseStore?
    /// Outbox engine (nil before identity exists).
    @Published public private(set) var outbox: PulseOutboxEngine?

    /// Honest-toast center shared by every surface (not-yet-built features).
    public let toasts = ToastCenter()

    public private(set) var api: PulseAPIClient
    public let particles = ParticleBus()

    /// Raw relay signals for feature view models.
    public let signals = PassthroughSubject<PulseSocketClient.Signal, Never>()
    /// Outbox deliver/drop events (queued-bubble swap in the open room).
    public let outboxEvents = PassthroughSubject<PulseOutboxEvent, Never>()

    private var socket: PulseSocketClient?
    private var cancellables: Set<AnyCancellable> = []

    public init() {
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL)
        // Timer publisher is not actor-isolated; hop back per tick.
        Timer.publish(every: 1, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.pruneTypers() }
            .store(in: &cancellables)
    }

    // ── lifecycle ────────────────────────────────────────────
    public func start(as viewer: PulseViewer) {
        self.viewer = viewer
        socket?.disconnect()
        socket = nil

        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL, userId: viewer.id)
        store = Self.openStore()
        startOutbox()

        // Realtime bootstraps asynchronously: the manifest override must land
        // BEFORE the socket (and API rebinding) — non-blocking for first paint.
        Task { await startRealtime(as: viewer) }
    }

    private func startRealtime(as viewer: PulseViewer) async {
        // 1. Manifest-driven endpoint override (cached in the Keychain from a
        //    previous launch is applied first, then a fresh fetch may refine;
        //    the probe targets the distribution CDN — the manifest lives there
        //    — and the user's Settings → Connection field always wins over it).
        PulseEndpoints.loadPersistedOverride()
        await PulseEndpoints.fetchManifestOverride()

        // 2. Rebind the API client — the override may have moved the gateway.
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL, userId: viewer.id)

        // 3. Drain anything queued offline (flush trigger: session start).
        await flushOutboxNow()

        // 4. Realtime only when a relay base is configured (nil → offline-first,
        //    no reconnect spam against a dead address).
        guard let socketBase = PulseEndpoints.socketURL else { return }
        let client = PulseSocketClient(socketURL: socketBase)
        client.signals = { [weak self] signal in
            Task { @MainActor in self?.handle(signal) }
        }
        socket = client
        client.connect(userId: viewer.id)
    }

    private static func openStore() -> PulseStore? {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        if let path = docs?.appendingPathComponent("pulse.sqlite").path,
           let store = try? PulseStore(path: path) {
            return store
        }
        return try? PulseStore() // in-memory fallback
    }

    // ── outbox ───────────────────────────────────────────────

    private func startOutbox() {
        guard let store, let viewer else { return }
        let engine = PulseOutboxEngine(store: store) { [weak self] in self?.api }
        engine.onEvent = { [weak self] event in
            self?.routeOutboxEvent(event)
        }
        outbox = engine
        PulseOutboxEngine.active = engine
        PulseKeychain.shared.saveViewer(viewer)
        if engine.count() > 0 {
            flushOutbox()
        }
    }

    private func routeOutboxEvent(_ event: PulseOutboxEngine.Event) {
        switch event {
        case .delivered(let message, let conversationId, _):
            realtimeRefreshTick += 1
            outboxEvents.send(.delivered(message: message, conversationId: conversationId))
        case .dropped(let clientId, let conversationId, let reason):
            outboxEvents.send(.dropped(clientId: clientId, conversationId: conversationId))
            toasts.show(reason.isEmpty ? "A queued message could not be delivered" : "Message not sent — \(reason)")
        }
    }

    /// Flush trigger for surfaces: app foreground, socket reconnect, enqueue.
    public func flushOutbox() {
        guard let outbox else { return }
        Task { await outbox.flush() }
    }

    private func flushOutboxNow() async {
        guard let outbox else { return }
        await outbox.flush()
    }

    // ── outbound ─────────────────────────────────────────────
    public func emitTyping(conversationId: String, recipients: [String], isTyping: Bool) {
        guard let viewer else { return }
        socket?.emitTyping(
            recipients: recipients,
            conversationId: conversationId,
            userId: viewer.id,
            userName: viewer.name,
            isTyping: isTyping,
        )
    }

    public func isOnline(_ userId: String) -> Bool {
        onlineUserIds.contains(userId)
    }

    /// Surfaces call this after inbox mutations (DM create, archive, …).
    public func noteInboxChanged() {
        inboxRefreshTick += 1
    }

    /// Single enqueue entry point for queued sends (room send-path failures).
    /// The engine owns trimming + the heal timer; without one yet (no session)
    /// the store row still lands and the engine picks it up on start.
    public func enqueueOutbox(conversationId: String, clientId: String, content: String, kind: String = "text") {
        if let outbox {
            outbox.append(conversationId: conversationId, clientId: clientId, content: content, kind: kind)
        } else {
            try? store?.appendOutbox(conversationId: conversationId, clientId: clientId, content: content, kind: kind)
        }
        flushOutbox()
    }

    /// Dock "More → Search" — the Chats tab listens for this tick and opens
    /// its search mode (real search, not a fake).
    public func requestChatsSearch() {
        searchRequestTick += 1
    }

    /// Dock compose / More → Saved — hand a conversation to the Chats tab to
    /// push onto its NavigationStack. Consumers must call `consumePendingOpenRoom()`
    /// when received (the published value also replays on re-subscription).
    /// Wave 2 — `jumpMessageId` rides along so the room scrolls to + flashes
    /// that message (saved-library "open original", spec §1 row 14).
    public func requestOpenRoom(_ conversation: WireConversationSummary, jumpMessageId: String? = nil) {
        pendingJumpMessageId = jumpMessageId
        pendingOpenRoom = conversation
    }

    public func consumePendingOpenRoom() {
        pendingOpenRoom = nil
    }

    public func consumePendingJumpMessageId() {
        pendingJumpMessageId = nil
    }

    public func typers(in conversationId: String, excluding userId: String?) -> [Typer] {
        guard let all = typers[conversationId] else { return [] }
        return all.filter { $0.userId != userId }
    }

    // ── inbound ──────────────────────────────────────────────
    private func handle(_ signal: PulseSocketClient.Signal) {
        switch signal {
        case .joined(let ids), .presenceSnapshot(let ids):
            connected = true
            onlineUserIds = Set(ids)
        case .connectionState(let isOn):
            connected = isOn
            // Flush trigger: socket connect / reconnect.
            if isOn { flushOutbox() }
        case .typing(let conversationId, let userId, let userName, let isTyping):
            registerTyping(conversationId: conversationId, userId: userId, userName: userName, isTyping: isTyping)
        case .messageNew(_, let raw), .messageDeleted(_, let raw), .messageReact(_, let raw):
            cacheMessage(from: raw)
        case .messageEnvelope(_, _, let raw):
            // message:edited/pinned/viewed, poll:voted, link:preview,
            // translation:added — live surfaces re-fetch on this tick.
            realtimeRefreshTick += 1
            cacheMessage(from: raw)
        case .conversationUpdated:
            realtimeRefreshTick += 1
        default:
            break
        }
        signals.send(signal)
    }

    /// Wave 0 offline hardening — every relayed message row lands in the
    /// cache so relaunch reads it back even when the surface is closed.
    private func cacheMessage(from raw: [String: Any]) {
        guard let store, let message = Self.decodeMessage(from: raw) else { return }
        try? store.upsert(messages: [message])
    }

    private func registerTyping(conversationId: String, userId: String, userName: String, isTyping: Bool) {
        var bucket = typers[conversationId] ?? []
        bucket.removeAll { $0.userId == userId }
        if isTyping {
            bucket.append(Typer(userId: userId, userName: userName, expiresAt: Date().addingTimeInterval(4)))
        }
        if bucket.isEmpty {
            typers[conversationId] = nil
        } else {
            typers[conversationId] = bucket
        }
    }

    private func pruneTypers() {
        let now = Date()
        var changed = false
        var next = typers
        for (key, bucket) in next {
            let alive = bucket.filter { $0.expiresAt > now }
            if alive.count != bucket.count {
                changed = true
                if alive.isEmpty { next[key] = nil } else { next[key] = alive }
            }
        }
        if changed { typers = next }
    }

    // ── shared socket payload decoding ───────────────────────
    /// message:new / message:deleted / message:react all carry `message`.
    /// nonisolated: a pure decoder — safe from any queue (socket handlers,
    /// tests) with no session state touched.
    public nonisolated static func decodeMessage(from raw: [String: Any]) -> WireChatMessage? {
        let payload = raw["message"] as? [String: Any] ?? raw
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        return try? WireMessageEnvelope.extract(from: data)
    }
}
