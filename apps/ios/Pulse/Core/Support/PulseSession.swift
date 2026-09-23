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
    /// Wave 6 deep links — user-route handoff consumed by the Contacts tab's
    /// NavigationStack (same bridge pattern as pendingOpenRoom). Consumers
    /// call `consumePendingUserRoute()` when received.
    @Published public private(set) var pendingUserRoute: UserRoute?
    /// Opened on session start (nil before onboarding) — @Published so late
    /// view models can rehydrate the offline cache the moment it exists.
    @Published public private(set) var store: PulseStore?
    /// Outbox engine (nil before identity exists).
    @Published public private(set) var outbox: PulseOutboxEngine?
    /// W3-b — Wave 3 call engine (nil before identity exists). RootView hosts
    /// the full-screen overlay; surfaces call startOutgoing/accept/decline.
    @Published public private(set) var callEngine: PulseCallEngine?
    /// W4 — Wave 4 stories feed owner (nil before identity exists). Tray,
    /// dock sheet, viewer and composer share this one instance.
    @Published public private(set) var stories: StoriesSessionModel?
    /// W5-f — Wave 5 voice rooms / stage / space owner (nil before identity
    /// exists). Room MEMBERSHIP SURVIVES surface close (VR-1); the
    /// ChatRoomView mic entry + fullScreenCover host share this instance.
    @Published public private(set) var voiceRooms: VoiceRoomSessionModel?
    /// Wave 8 — bumped when the server rejects our session token (API 401
    /// or join:error). The token is already cleared + re-login surfaced by
    /// `noteAuthFailure`; the tick lets deep surfaces react if they must.
    @Published public private(set) var authRejectedTick = 0
    /// Wave 8 — the conversation currently owning the screen (ChatRoomView
    /// writes it). The incoming-attention gate uses it as the "you are
    /// reading this room" check — no ping for the room in front of you.
    @Published public var activeRoomId: String?
    /// Wave 8 — weak handoff to the RootView-owned PulsePrefs (incoming
    /// attention gating + the settings PATCH funnel + quiet-gate refresh).
    public private(set) weak var prefs: PulsePrefs?

    /// Honest-toast center shared by every surface (not-yet-built features).
    public let toasts = ToastCenter()

    public private(set) var api: PulseAPIClient
    public let particles = ParticleBus()
    /// R1-W2I — PiP pane store (F-PI-01..03): the pop-out mini-chat panes
    /// (web usePipChat). One session-level owner, rendered by the
    /// RootView-level overlay + toggled from the room toolbar.
    public let pip = PulsePiPStore()

    /// Raw relay signals for feature view models.
    public let signals = PassthroughSubject<PulseSocketClient.Signal, Never>()
    /// Outbox deliver/drop events (queued-bubble swap in the open room).
    public let outboxEvents = PassthroughSubject<PulseOutboxEvent, Never>()

    private var socket: PulseSocketClient?
    private var cancellables: Set<AnyCancellable> = []

    public init() {
        // Pre-login client: no viewer yet (no token to attach). start(as:)
        // rebuilds it with the real userId + persisted Keychain token.
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL)
        // Timer publisher is not actor-isolated; hop back per tick.
        Timer.publish(every: 1, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.pruneTypers()
                self?.prefs?.refreshQuietGate() // Wave 8 — quiet window opens/closes on the minute
            }
            .store(in: &cancellables)
    }

    // ── Wave 8 — prefs handoff ───────────────────────────

    /// RootView owns PulsePrefs; the session needs it for the incoming
    /// attention gate, the quiet-gate refresh and the settings PATCH
    /// funnel. Idempotent — the closures attach exactly once.
    public func attach(prefs: PulsePrefs) {
        self.prefs = prefs
        guard prefs.patchRemote == nil else { return }
        prefs.patchRemote = { [weak self] patch in
            self?.sendPrefsPatch(patch)
        }
        prefs.authRejectionHandler = { [weak self] message in
            self?.noteAuthFailure(message)
        }
    }

    /// Optimistic settings PATCH: the local value already applied; this
    /// persists { userId, preferences } and echoes the server-merged blob
    /// back (server wins on the response). Failure → local value STAYS +
    /// an honest offline note for the settings surface.
    private func sendPrefsPatch(_ patch: WirePulsePrefs) {
        Task { [weak self] in
            guard let self else { return }
            do {
                let server = try await self.api.updateSettings(patch: patch)
                self.prefs?.applyServer(server)
                self.prefs?.notePatchSettled(successful: true, note: nil)
            } catch let failure as PulseAPIClient.Failure where failure.kind == .auth {
                self.noteAuthFailure(failure.message)
            } catch {
                self.prefs?.notePatchSettled(successful: false, note: "Saved on this device — the server didn't answer.")
            }
        }
    }

    /// Wave 8 — the server rejected our session token (API 401 with
    /// "Session token is invalid or has been rotated." or join:error).
    /// Reaction: clear the Keychain token, drop it from future requests
    /// AND socket joins (token-less is accepted — degraded honest mode),
    /// surface re-login. No forced onboarding (offline-first stays).
    public func noteAuthFailure(_ message: String? = nil) {
        PulseKeychain.shared.clearSessionToken()
        if let viewer {
            api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL, userId: viewer.id, authToken: nil)
        }
        socket?.updateToken(nil)
        toasts.show(message ?? "Session expired — log in again to stay in sync.")
        authRejectedTick += 1
    }

    /// R1-W2B D23 — identity teardown for "Forget this viewer": the viewer
    /// deliberately signed out of this device. Drops the socket, releases
    /// the viewer-bound live models (rebuilt by the next `start(as:)`) and
    /// rebinds a token-less pre-login client. The caller (identity switcher)
    /// then clears the viewer via prefs.setViewer(nil), which also wipes the
    /// identity-bound Keychain session token. The outbox queue itself STAYS
    /// (it belongs to the store, not the session) and flushes again once a
    /// new identity starts.
    public func stop() {
        viewer = nil
        socket?.disconnect()
        socket = nil
        outbox = nil
        PulseOutboxEngine.active = nil
        callEngine = nil
        stories = nil
        voiceRooms = nil
        connected = false
        onlineUserIds = []
        typers = [:]
        activeRoomId = nil
        roomVisible = false
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL)
    }

    // ── lifecycle ────────────────────────────────────────────
    public func start(as viewer: PulseViewer) {
        self.viewer = viewer
        socket?.disconnect()
        socket = nil

        // Wave 8 — the rebuilt client carries the Keychain session token.
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL, userId: viewer.id, authToken: PulseKeychain.shared.loadSessionToken())
        store = Self.openStore()
        startOutbox()
        startCalls(viewer: viewer)
        startStories(viewer: viewer)
        startVoiceRooms(viewer: viewer)
        startReminderDueLoop(viewer: viewer)

        // Realtime bootstraps asynchronously: the manifest override must land
        // BEFORE the socket (and API rebinding) — non-blocking for first paint.
        Task { await startRealtime(as: viewer) }
    }

    /// Wave 7 F-RO-06 — the reminder due-loop (web useReminderDueLoop parity,
/// 30 s foreground): GET ?due=1 → local notification → PATCH firedAt so the
/// web sheet and other devices converge. Failures are honest silence (the
/// next tick retries); local fires (offline) ride the scheduled triggers.
    private var reminderDueTask: Task<Void, Never>?

    private func startReminderDueLoop(viewer: PulseViewer) {
        reminderDueTask?.cancel()
        reminderDueTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                let api = self.api
                if let due = try? await api.reminders(dueOnly: true).items, !due.isEmpty {
                    for item in due {
                        let note = (item.note?.isEmpty == false) ? item.note! : "Reminder"
                        let body = item.snippet ?? item.conversation?.name ?? ""
                        PulseReminderNotifications.showNow(reminderId: item.id, note: note, body: body)
                        _ = try? await api.resolveReminder(item.id)
                    }
                }
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    private func startRealtime(as viewer: PulseViewer) async {
        // 1. Manifest-driven endpoint override (cached in the Keychain from a
        //    previous launch is applied first, then a fresh fetch may refine;
        //    the probe targets the distribution CDN — the manifest lives there
        //    — and the user's Settings → Connection field always wins over it).
        PulseEndpoints.loadPersistedOverride()
        await PulseEndpoints.fetchManifestOverride()

        // 2. Rebind the API client — the override may have moved the gateway.
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL, userId: viewer.id, authToken: PulseKeychain.shared.loadSessionToken())

        // 3. Drain anything queued offline (flush trigger: session start).
        await flushOutboxNow()

        // 4. Wave 8 — pull the server-merged prefs blob (server value wins;
        //    optimistic local values keep working when this fails).
        await prefs?.syncFromServer(api: api)

        // 5. Realtime only when a relay base is configured (nil → offline-first,
        //    no reconnect spam against a dead address). The join payload
        //    carries the session token when one exists (verified server-side).
        guard let socketBase = PulseEndpoints.socketURL else { return }
        let client = PulseSocketClient(socketURL: socketBase)
        client.signals = { [weak self] signal in
            Task { @MainActor in self?.handle(signal) }
        }
        socket = client
        client.connect(userId: viewer.id, token: PulseKeychain.shared.loadSessionToken())
    }

    private static func openStore() -> PulseStore? {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        if let path = docs?.appendingPathComponent("pulse.sqlite").path,
           let store = try? PulseStore(path: path) {
            return store
        }
        return try? PulseStore() // in-memory fallback
    }

    // ── calls (W3-b — Wave 3 native calls) ───────────────

    /// Builds the call engine (real WebRTC provider + session-bound signaling)
    /// and drains any queued single-writer call-log rows from a previous
    /// offline session (app-start flush trigger).
    private func startCalls(viewer: PulseViewer) {
        guard let store else { return }
        let engine = PulseCallEngine(
            store: store,
            viewer: viewer,
            signaling: PulseSessionCallSignaling(session: self),
            media: PulseRTCMediaProvider(),
            apiProvider: { [weak self] in self?.api },
            toasts: toasts,
        )
        callEngine = engine
        Task { await engine.flushCallLogQueue() }
    }

    // ── stories (W4 — Wave 4 native stories) ─────────────────

    /// Builds the shared feed owner (REST + 60s poll parity; GRDB snapshot
    /// cache) once identity exists.
    private func startStories(viewer: PulseViewer) {
        stories = StoriesSessionModel(session: self)
    }

    // ── voice rooms / stage / space (W5-f) ───────────────

    /// Builds the rooms owner once identity exists — pure models + the
    /// audio engine live here; the socket stays session-owned.
    private func startVoiceRooms(viewer: PulseViewer) {
        voiceRooms = VoiceRoomSessionModel(session: self)
    }

    /// The rooms owner's emit funnel — the socket is session-owned
    /// (mirrors emitCallSignal). Payloads come from the pure
    /// VoiceRoomWire builders (unit-tested).
    public func emitRoomSignal(event: String, payload: [String: Any]) {
        socket?.emitRoomSignal(event: event, payload: payload)
    }

    /// The engine's signaling sender funnels here (the socket is session-owned).
    public func emitCallSignal(event: String, payload: [String: Any]) {
        socket?.emitCallSignal(event: event, payload: payload)
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
    /// R1-W2B D28 — `payloadJson` carries the queued forward envelope
    /// (PulseOutboxForward); plain sends leave it nil.
    public func enqueueOutbox(conversationId: String, clientId: String, content: String, kind: String = "text", payloadJson: String? = nil) {
        if let outbox {
            if let forward = PulseOutboxForward.decode(payloadJson) {
                outbox.appendForward(conversationId: conversationId, clientId: clientId, content: content, forward: forward)
            } else {
                outbox.append(conversationId: conversationId, clientId: clientId, content: content, kind: kind)
            }
        } else {
            try? store?.appendOutbox(conversationId: conversationId, clientId: clientId, content: content, kind: kind, payloadJson: payloadJson)
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

    /// Deep links / avatars — hand a user id to the Contacts tab so it pushes
    /// the full user page (F-CP-03). `name` seeds the nav title while the
    /// page fetches (nil-safe).
    public func requestOpenUser(_ userId: String, name: String?) {
        pendingUserRoute = UserRoute(userId: userId, name: name ?? "Profile")
    }

    public func consumePendingUserRoute() {
        pendingUserRoute = nil
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
        case .joinError(let message):
            // Wave 8 — the relay verified our presented join token and
            // refused it (rotated elsewhere / invalid). Clear + degrade to
            // token-less so the reconnect lands instead of looping.
            noteAuthFailure(message)
            return
        case .connectionState(let isOn):
            connected = isOn
            // Flush trigger: socket connect / reconnect.
            if isOn {
                flushOutbox()
                // W3-b — same trigger for the queued single-writer call rows.
                callEngine?.flushCallLogQueueOnReconnect()
            }
            // W5-f — the rooms owner consumes connect/reconnect too (voice
            // re-join VR-8, stage resync ST-8, space attempts FIX #5).
            voiceRooms?.handle(signal)
        case .typing(let conversationId, let userId, let userName, let isTyping):
            registerTyping(conversationId: conversationId, userId: userId, userName: userName, isTyping: isTyping)
        case .messageNew(let conversationId, let raw):
            cacheMessage(from: raw)
            noteIncomingAttention(conversationId: conversationId, raw: raw)
        case .messageDeleted(_, let raw), .messageReact(_, let raw):
            cacheMessage(from: raw)
        case .messageEnvelope(_, _, let raw):
            // message:edited/pinned/viewed, poll:voted, link:preview,
            // translation:added — live surfaces re-fetch on this tick.
            realtimeRefreshTick += 1
            cacheMessage(from: raw)
        case .conversationUpdated:
            realtimeRefreshTick += 1
        case .callSignal(let event, let raw):
            // W3-b — offer/answer/ice/reject/cancel/hangup → the call engine
            // (machine + WebRTC + single-writer log). Also relayed to feature
            // subscribers below.
            callEngine?.handleCallSignal(event: event, raw: raw)
        case .voiceRoster, .voicePtt, .voiceChunk, .voiceTranscript,
             .stageState, .stageEnded, .spaceState:
            // W5-f — the rooms owner consumes the 7 rooms signals (they
            // still reach every other subscriber via signals.send below).
            voiceRooms?.handle(signal)
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

    /// Wave 8 — incoming attention (web pulse-realtime-provider parity):
    /// a message from SOMEONE ELSE, not a thread reply, in a room you are
    /// NOT reading → soft ping + buzz + preview toast, each behind its own
    /// toggle, all behind the LOCAL quiet-hours window.
    private func noteIncomingAttention(conversationId: String, raw: [String: Any]) {
        guard let message = Self.decodeMessage(from: raw), let viewer else { return }
        guard message.senderId != viewer.id,
              message.deletedAt == nil,
              message.parentId == nil,
              activeRoomId != conversationId else { return }
        guard !(prefs?.isQuietHoursNow ?? false) else { return } // quiet — stay silent
        if prefs?.notifSound == true {
            PulseSounds.incoming()
        }
        if prefs?.notifVibrate == true {
            PulseHaptics.incoming()
        }
        if prefs?.notifPreviews == true {
            let sender = message.sender?.name ?? "Message"
            let body = message.content.isEmpty ? "Sent an attachment" : message.content
            toasts.show("\(sender): \(body)")
        }
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

/// Real call-signaling sender — the engine emits through the session-owned
/// socket (PulseSocketClient.emitCallSignal). @MainActor to match the engine.
@MainActor
private final class PulseSessionCallSignaling: PulseCallSignalingSending {
    private weak var session: PulseSession?

    init(session: PulseSession) {
        self.session = session
    }

    func send(event: String, payload: [String: Any]) {
        session?.emitCallSignal(event: event, payload: payload)
    }
}
