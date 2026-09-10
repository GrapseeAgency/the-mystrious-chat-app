import Foundation
import Combine

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

    /// Honest-toast center shared by every surface (not-yet-built features).
    public let toasts = ToastCenter()

    public private(set) var api: PulseAPIClient
    public private(set) var store: PulseStore?
    public let particles = ParticleBus()

    /// Raw relay signals for feature view models.
    public let signals = PassthroughSubject<PulseSocketClient.Signal, Never>()

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

        // Realtime only when a relay base is configured (nil → offline-first,
        // no reconnect spam against a dead address).
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

    /// Dock "More → Search" — the Chats tab listens for this tick and opens
    /// its search mode (real search, not a fake).
    public func requestChatsSearch() {
        searchRequestTick += 1
    }

    /// Dock compose / More → Saved — hand a conversation to the Chats tab to
    /// push onto its NavigationStack. Consumers must call `consumePendingOpenRoom()`
    /// when received (the published value also replays on re-subscription).
    public func requestOpenRoom(_ conversation: WireConversationSummary) {
        pendingOpenRoom = conversation
    }

    public func consumePendingOpenRoom() {
        pendingOpenRoom = nil
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
        case .typing(let conversationId, let userId, let userName, let isTyping):
            registerTyping(conversationId: conversationId, userId: userId, userName: userName, isTyping: isTyping)
        default:
            break
        }
        signals.send(signal)
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
    public static func decodeMessage(from raw: [String: Any]) -> WireChatMessage? {
        let payload = raw["message"] as? [String: Any] ?? raw
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        return try? WireMessageEnvelope.extract(from: data)
    }
}
