import Foundation
import Combine

/// Outbox events relayed to feature view models (the open room swaps its
/// queued placeholder for the real row; drops remove it honestly).
public enum PulseOutboxEvent: Sendable {
    case delivered(message: WireChatMessage, conversationId: String, clientId: String)
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
    /// Wave 0 - bumped on every background mutation that should make live
    /// surfaces re-fetch (message:edited/pinned/viewed, poll:voted,
    /// link:preview, translation:added, conversation:updated, outbox
    /// deliveries). Features subscribe; later waves consume more cases.
    @Published public private(set) var realtimeRefreshTick = 0
    /// Live total unread feeding the dock badge (Chats view model writes it).
    @Published public var dockUnreadCount = 0
    /// True while a chat room owns the screen - the dock hides itself.
    @Published public var roomVisible = false
    /// Dock "More → Search" asks the Chats tab to enter search mode.
    @Published public private(set) var searchRequestTick = 0
    /// Dock compose / More → Saved hand a freshly-resolved conversation to the
    /// Chats tab's NavigationStack (the dock lives above the shell, the stack
    /// lives inside ChatsView - this is the bridge, same pattern as search).
    @Published public private(set) var pendingOpenRoom: WireConversationSummary?
    private var pendingRoomDelivery = UUID()
    /// User navigation intent, distinct from room visibility during foreground/
    /// sheet transitions. Async link resolution must not displace a newer intent.
    public private(set) var roomNavigationGeneration = UUID()

    public func noteRoomNavigationIntent() {
        roomNavigationGeneration = UUID()
    }

    /// Wave 2 saved library - jump-to-message handoff consumed by ChatsView
    /// together with pendingOpenRoom (the SAME path search hits already use).
    @Published public private(set) var pendingJumpMessageId: String?
    /// Wave 6 deep links - user-route handoff consumed by the Contacts tab's
    /// NavigationStack (same bridge pattern as pendingOpenRoom). Consumers
    /// call `consumePendingUserRoute()` when received.
    @Published public private(set) var pendingUserRoute: UserRoute?
    private var pendingUserDelivery = UUID()
    /// R2-D - the linked-room bridge (conversation ID only): reminder-
    /// notification taps (PulseReminderNotificationDelegate) and calls-
    /// history rows set this; RootView consumes it and follows the exact
    /// pulse://room path (detail fetch → Chats tab → requestOpenRoom).
    /// Writable by the feature surfaces; RootView clears it on consumption.
    @Published public var pendingLinkedRoomId: String?
    /// Opened on session start (nil before onboarding) - @Published so late
    /// view models can rehydrate the offline cache the moment it exists.
    @Published public private(set) var store: PulseStore?
    /// Outbox engine (nil before identity exists).
    @Published public private(set) var outbox: PulseOutboxEngine?
    /// W3-b - Wave 3 call engine (nil before identity exists). RootView hosts
    /// the full-screen overlay; surfaces call startOutgoing/accept/decline.
    @Published public private(set) var callEngine: PulseCallEngine?
    /// 3-d - the GROUP (mesh) call engine (nil before identity exists).
    /// RootView hosts the group overlay + ring/ongoing banners; group rooms
    /// dial through startCall(kind:conversationId:title:).
    @Published public private(set) var groupCallEngine: PulseGroupCallEngine?
    /// W4 - Wave 4 stories feed owner (nil before identity exists). Tray,
    /// dock sheet, viewer and composer share this one instance.
    @Published public private(set) var stories: StoriesSessionModel?
    /// W5-f - Wave 5 voice rooms / stage / space owner (nil before identity
    /// exists). Room MEMBERSHIP SURVIVES surface close (VR-1); the
    /// ChatRoomView mic entry + fullScreenCover host share this instance.
    @Published public private(set) var voiceRooms: VoiceRoomSessionModel?
    /// Wave 8 - bumped when the server rejects our session token (API 401
    /// or join:error). The token is already cleared + re-login surfaced by
    /// `noteAuthFailure`; the tick lets deep surfaces react if they must.
    @Published public private(set) var authRejectedTick = 0
    /// Wave 8 - the conversation currently owning the screen (ChatRoomView
    /// writes it). The incoming-attention gate uses it as the "you are
    /// reading this room" check - no ping for the room in front of you.
    @Published public var activeRoomId: String? {
        didSet {
            if oldValue != activeRoomId {
                readGate.reset()
                readSurfaces.reset()
                readFailureCount = 0
                readReceiptTask?.cancel()
            }
        }
    }
    private var readGate = PulseReadReceiptGate()
    private var readSurfaces = PulseReadSurfaces()
    private var readFailureCount = 0
    private var readReceiptTask: Task<Void, Never>?
    /// Wave 8 - weak handoff to the RootView-owned PulsePrefs (incoming
    /// attention gating + the settings PATCH funnel + quiet-gate refresh).
    public private(set) weak var prefs: PulsePrefs?

    /// Honest-toast center shared by every surface (not-yet-built features).
    public let toasts = ToastCenter()

    public private(set) var api: PulseAPIClient
    public let particles = ParticleBus()
    /// R1-W2I - PiP pane store (F-PI-01..03): the pop-out mini-chat panes
    /// (web usePipChat). One session-level owner, rendered by the
    /// RootView-level overlay + toggled from the room toolbar.
    public let pip = PulsePiPStore()

    /// Raw relay signals for feature view models.
    public let signals = PassthroughSubject<PulseSocketClient.Signal, Never>()
    /// Outbox deliver/drop events (queued-bubble swap in the open room).
    public let outboxEvents = PassthroughSubject<PulseOutboxEvent, Never>()

    @Published public private(set) var ready = false {
        didSet {
            guard oldValue != ready else { return }
            readReceiptTask?.cancel()
            readFailureCount = 0
            readGate.restartDwell(now: ProcessInfo.processInfo.systemUptime)
            if ready && isForeground { scheduleVisibleRead(after: PulseReadReceiptGate.dwell) }
        }
    }
    @Published public private(set) var startupError: String?
    @Published public private(set) var isForeground = false
    public private(set) var messageRevision: UInt64 = 0
    public private(set) var savedRevision: UInt64 = 0
    public func noteSavedMutation() { savedRevision &+= 1 }
    private var messageRevisions: [String: UInt64] = [:]

    public func acceptsSyncMessage(_ id: String, startedAt revision: UInt64) -> Bool {
        (messageRevisions[id] ?? 0) <= revision
    }

    private var threadValidationFence = PulseThreadValidationFence()
    private var deniedThreadRoots: Set<String> = []

    public func threadAccessDenied(rootId: String) -> Bool {
        deniedThreadRoots.contains(rootId) || ((try? store?.threadAccessDenied(rootId: rootId)) ?? true)
    }

    /// Only the newest validation may change persistent access state.
    public func validatedThread(rootId: String, roomId: String) async throws -> WireThreadPage {
        guard let store, let viewer, ready else { throw CancellationError() }
        let ticket = threadValidationFence.begin(rootId), epoch = sessionGeneration, client = api, revision = messageRevision
        defer { threadValidationFence.finish(rootId, ticket) }
        do {
            let page = try await client.thread(rootId: rootId, userId: viewer.id)
            try Task.checkCancellation()
            guard epoch == sessionGeneration else { throw CancellationError() }
            guard threadValidationFence.isCurrent(rootId, ticket) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "A newer thread check superseded this one. Try again.", status: 409)
            }
            guard page.parent.id == rootId, page.parent.conversationId == roomId, page.parent.parentId == nil else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Unexpected thread response.", status: 409)
            }
            let eligible = ([page.parent] + page.replies).filter { acceptsSyncMessage($0.id, startedAt: revision) }
            let saved = try store.acceptThreadSnapshot(rootId: rootId, roomId: roomId, messages: eligible)
            deniedThreadRoots.remove(rootId)
            messageVisibilityClock.send(Date())
            // Rows rejected by the revision fence may still be live in newer storage.
            let replies = page.replies.compactMap { incoming -> WireChatMessage? in
                guard incoming.parentId == rootId, incoming.conversationId == roomId else { return nil }
                return try? store.message(id: incoming.id)
            }
            return WireThreadPage(parent: saved.parent, replies: replies)
        } catch {
            let unavailable = error is PulseStore.ThreadAccessError
            if epoch == sessionGeneration, threadValidationFence.isCurrent(rootId, ticket),
               unavailable || [403, 404, 410].contains((error as? PulseAPIClient.Failure)?.status ?? 0) {
                deniedThreadRoots.insert(rootId)
                try? store.denyThread(rootId: rootId) // memory still blocks this session if disk is unwritable
                messageVisibilityClock.send(Date())
            }
            if unavailable { throw PulseAPIClient.Failure(kind: .notFound, message: "This thread is no longer available.", status: 410) }
            throw error
        }
    }

    private var viewOnceActions: Set<String> = []

    /// A tap is not permission to reveal. Only this request's successful consumption is.
    public func consumeViewOnce(_ requested: WireChatMessage) async -> WireChatMessage? {
        guard let viewer, let store, ready, isForeground, activeRoomId == requested.conversationId,
              PulseViewOnceAccess.canRequest(requested, viewerId: viewer.id),
              viewOnceActions.insert(requested.id).inserted else { return nil }
        if let rootId = requested.parentId, threadAccessDenied(rootId: rootId) {
            viewOnceActions.remove(requested.id)
            toasts.show("Reopen this thread to check access before opening the photo.")
            return nil
        }
        let epoch = sessionGeneration, client = api
        defer { viewOnceActions.remove(requested.id) }
        do {
            let receipt = try await client.markViewed(messageId: requested.id, userId: viewer.id)
            try Task.checkCancellation()
            guard sessionGeneration == epoch else { return nil }
            guard receipt.message.id == requested.id, receipt.message.conversationId == requested.conversationId else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Unexpected photo acknowledgement.")
            }
            let retained = try store.mergeMessages([receipt.message]).first ?? receipt.message
            noteMessageRevision(retained.id)
            messageVisibilityClock.send(Date())
            let rootLive: Bool
            if let rootId = retained.parentId, let root = try store.message(id: rootId) {
                rootLive = !threadAccessDenied(rootId: rootId) && PulseRemediationLogic.threadRootAvailable(root, roomId: retained.conversationId, rootId: rootId)
            } else { rootLive = retained.parentId == nil }
            guard rootLive, PulseViewOnceAccess.granted(receipt.consumed, requested: requested, returned: retained, viewerId: viewer.id) else {
                toasts.show("One-time access wasn't granted. The photo may already have been opened.")
                return nil
            }
            guard isForeground, activeRoomId == requested.conversationId else { return nil }
            return retained
        } catch is CancellationError {
            return nil
        } catch {
            guard sessionGeneration == epoch else { return nil }
            toasts.show("Couldn't confirm one-time access. The photo wasn't opened here.")
            return nil
        }
    }

    public func recordMessageMutation(_ message: WireChatMessage) throws {
        try store?.upsert(messages: [message])
        noteMessageRevision(message.id)
        messageVisibilityClock.send(Date())
    }

    public func applyPinRemoval(_ id: String, startedAt revision: UInt64) throws {
        guard acceptsSyncMessage(id, startedAt: revision) else { return }
        try store?.clearMessagePin(id: id)
        noteMessageRevision(id)
        messageVisibilityClock.send(Date())
    }

    private func noteMessageRevision(_ id: String) {
        messageRevision &+= 1
        messageRevisions[id] = messageRevision
    }

    private var generation = UUID()
    public var sessionGeneration: UUID { generation }
    private var startupTask: Task<Void, Never>?

    /// Visibility-only clock: no network work or whole-session re-render per tick.
    public let messageVisibilityClock = PassthroughSubject<Date, Never>()

    public func setForeground(_ active: Bool) {
        let changed = isForeground != active
        isForeground = active
        if changed {
            readReceiptTask?.cancel()
            readGate.restartDwell(now: ProcessInfo.processInfo.systemUptime)
        }
        if active {
            messageVisibilityClock.send(Date())
            if changed {
                readFailureCount = 0
                scheduleVisibleRead(after: PulseReadReceiptGate.dwell)
            }
        }
    }

    public func setVisibleReadBoundary(conversationId: String, messageId: String?) {
        guard activeRoomId == conversationId else { return }
        readSurfaces.setPrimary(roomId: conversationId, messageId: messageId)
        applyVisibleReadBoundary(conversationId: conversationId)
    }

    func beginReadSurface(conversationId: String, rootId: String) -> PulseReadSurfaces.Owner? {
        guard activeRoomId == conversationId else { return nil }
        let owner = readSurfaces.begin(roomId: conversationId, rootId: rootId)
        applyVisibleReadBoundary(conversationId: conversationId)
        return owner
    }

    func setVisibleReadBoundary(owner: PulseReadSurfaces.Owner, messageId: String?) {
        guard activeRoomId == owner.roomId else { return }
        readSurfaces.setOverlay(owner, messageId: messageId)
        applyVisibleReadBoundary(conversationId: owner.roomId)
    }

    func endReadSurface(_ owner: PulseReadSurfaces.Owner) {
        guard readSurfaces.end(owner), activeRoomId == owner.roomId else { return }
        applyVisibleReadBoundary(conversationId: owner.roomId)
    }

    private func applyVisibleReadBoundary(conversationId: String) {
        let messageId = readSurfaces.boundary?.messageId
        guard readGate.setBoundary(roomId: conversationId, messageId: messageId,
            now: ProcessInfo.processInfo.systemUptime) else { return }
        readFailureCount = 0
        readReceiptTask?.cancel()
        if messageId != nil && isForeground { scheduleVisibleRead(after: PulseReadReceiptGate.dwell) }
    }

    private func scheduleVisibleRead(after delay: TimeInterval) {
        guard let boundary = readGate.boundary else { return }
        let epoch = generation, revision = readGate.revision
        readReceiptTask?.cancel()
        readReceiptTask = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
            guard let self, self.generation == epoch, self.readGate.revision == revision else { return }
            await self.markReadIfVisible(conversationId: boundary.roomId)
        }
    }

    public func markReadIfVisible(conversationId: String?) async {
        guard let conversationId, ready, isForeground,
              activeRoomId == conversationId, !PulseAppLock.shared.needsLock,
              let request = readGate.begin(roomId: conversationId, now: ProcessInfo.processInfo.systemUptime) else { return }
        if let owner = readSurfaces.owner, let rootId = owner.rootId {
            // The expiry timer can tick AFTER the 600ms dwell. Recheck the
            // canonical cache immediately before sending a thread boundary.
            let available: Bool
            if threadAccessDenied(rootId: rootId) {
                available = false
            } else if request.boundary.messageId == rootId {
                let root = try? store?.message(id: rootId)
                available = root.map { PulseRemediationLogic.threadRootAvailable($0, roomId: conversationId, rootId: rootId) } ?? false
            } else {
                available = (try? store?.cachedThreadTarget(roomId: conversationId, rootId: rootId,
                    targetId: request.boundary.messageId)) != nil
            }
            if !available {
                readGate.finish(request, succeeded: false)
                setVisibleReadBoundary(owner: owner, messageId: nil)
                return
            }
        }
        let client = api
        let epoch = generation
        do {
            try await client.markRead(conversationId: conversationId, messageId: request.boundary.messageId)
            guard generation == epoch else { return }
            if readGate.finish(request, succeeded: true) { readFailureCount = 0 }
            // An older visible boundary can still have been accepted by the server;
            // refresh the inbox, but never let its completion acknowledge the new screen.
            noteInboxChanged()
        } catch {
            guard generation == epoch, readGate.finish(request, succeeded: false),
                  !Task.isCancelled, isForeground, activeRoomId == conversationId else { return }
            readFailureCount = min(7, readFailureCount + 1)
            guard let wait = PulseVisibleReadRetry.delay(after: error, failures: readFailureCount) else {
                readGate.deferAttempt(until: .infinity)
                return
            }
            readGate.deferAttempt(until: ProcessInfo.processInfo.systemUptime + wait)
            scheduleVisibleRead(after: wait)
        }
    }

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
                if self?.isForeground == true { self?.messageVisibilityClock.send(Date()) }
                self?.pruneTypers()
                self?.prefs?.refreshQuietGate() // Wave 8 - quiet window opens/closes on the minute
            }
            .store(in: &cancellables)
    }

    // Wave 8 - prefs handoff

    /// RootView owns PulsePrefs; the session needs it for the incoming
    /// attention gate, the quiet-gate refresh and the settings PATCH
    /// funnel. Idempotent - the closures attach exactly once.
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
                self.prefs?.notePatchSettled(successful: false, note: "Saved on this device - the server didn't answer.")
            }
        }
    }

    /// Wave 8 - the server rejected our session token (API 401 with
    /// "Session token is invalid or has been rotated." or join:error).
    /// Reaction: clear the Keychain token, drop it from future requests
    /// AND socket joins (token-less is accepted - degraded honest mode),
    /// surface re-login. No forced onboarding (offline-first stays).
    public func noteAuthFailure(_ message: String? = nil) {
        // Keep the rejected credential on the bound client (fail closed).
        // Clearing it and retrying header-less bypasses legacy server auth.
        socket?.disconnect()
        outbox?.stop()
        connected = false
        toasts.show(message ?? "Session expired. Pending messages are kept until you sign in again.")
        authRejectedTick += 1
    }

    /// R1-W2B D23 - identity teardown for "Forget this viewer": the viewer
    /// deliberately signed out of this device. Drops the socket, releases
    /// the viewer-bound live models (rebuilt by the next `start(as:)`) and
    /// rebinds a token-less pre-login client. The caller (identity switcher)
    /// then clears the viewer via prefs.setViewer(nil), which also wipes the
    /// identity-bound Keychain session token. The outbox queue itself STAYS
    /// (it belongs to the store, not the session) and flushes again once a
    /// new identity starts.
    public func stop() {
        // Task 5-d - the viewer becomes nil here (the ONLY identity-teardown
        // path): the push registry row (token → THIS viewer) must not
        // outlive it. Fire-and-forget DELETE /api/push/register { token } +
        // the stored token cleared synchronously - sign-out never blocks.
        callEngine?.endCall()
        groupCallEngine?.leaveCall()
        voiceRooms?.leaveStage()
        voiceRooms?.leaveVoice()
        voiceRooms?.leaveSpace()
        PulsePushNotifications.deactivate()
        generation = UUID()
        startupTask?.cancel()
        startupTask = nil
        reminderDueTask?.cancel()
        reminderDueTask = nil
        outbox?.stop()
        ready = false
        startupError = nil
        viewer = nil
        store = nil
        messageRevisions.removeAll()
        threadValidationFence = PulseThreadValidationFence()
        deniedThreadRoots.removeAll()
        pendingOpenRoom = nil
        pendingJumpMessageId = nil
        pendingLinkedRoomId = nil
        pendingUserRoute = nil
        dockUnreadCount = 0
        socket?.disconnect()
        socket = nil
        outbox = nil
        PulseOutboxEngine.active = nil
        callEngine = nil
        groupCallEngine = nil
        stories = nil
        voiceRooms = nil
        connected = false
        onlineUserIds = []
        typers = [:]
        activeRoomId = nil
        roomVisible = false
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL)
    }

    /// R14 5-b - boot identity probe (web app-root.tsx:88-105 parity): a
    /// restored session is validated against GET /api/users/{id}; a 404
    /// means the identity no longer exists on this Pulse → stop the live
    /// layer and hand the wipe to the caller (prefs.setViewer(nil) lands on
    /// onboarding). Network flakes and every other error proceed
    /// optimistically - same contract as the web BootGate. Offline-created
    /// `local_` identities never probe (they are honest offline accounts,
    /// not stale server rows).
    public func validateStoredIdentity(onWiped: @escaping (String) -> Void) {
        guard let viewer, !viewer.id.hasPrefix("local_") else { return }
        let epoch = generation
        let client = api
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await client.user(viewer.id)
            } catch let failure as PulseAPIClient.Failure where failure.kind == .notFound {
                guard self.generation == epoch, self.viewer?.id == viewer.id else { return }
                self.stop()
                onWiped(viewer.name)
            } catch {
                // Offline / unknown gateway - keep the session (web parity:
                // validation.isError && !404 → status .ready).
            }
        }
    }

    // lifecycle
    public func start(as viewer: PulseViewer) {
        stop()
        self.viewer = viewer
        let epoch = generation
        startupTask = Task { [weak self] in
            guard let self else { return }
            await self.startRealtime(as: viewer, epoch: epoch)
        }
    }

    /// Wave 7 F-RO-06 - the reminder due-loop (web useReminderDueLoop parity,
/// 30 s foreground): GET ?due=1 → local notification → PATCH firedAt so the
/// web sheet and other devices converge. Failures are honest silence (the
/// next tick retries); local fires (offline) ride the scheduled triggers.
    private var reminderDueTask: Task<Void, Never>?

    private func startReminderDueLoop(viewer: PulseViewer) {
        reminderDueTask?.cancel()
        let epoch = generation
        reminderDueTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                guard self.isForeground, self.ready else {
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    continue
                }
                let api = self.api
                if let due = try? await api.reminders(dueOnly: true).items, !due.isEmpty {
                    guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == viewer.id else { return }
                    for item in due {
                        let note = (item.note?.isEmpty == false) ? item.note! : "Reminder"
                        let body = item.snippet ?? item.conversation?.name ?? ""
                        // R2-D - the conversationId rides the userInfo so the
                        // tap deep-links into the room (web reminder parity).
                        guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == viewer.id else { return }
                        let deliveryOwner = PulseReminderDelivery.ownerKey(api)
                        let queued = await PulseReminderDelivery.queueOnce(ownerId: deliveryOwner, reminderId: item.id,
                            retract: { PulseReminderNotifications.cancel(reminderId: item.id, ownerId: deliveryOwner) }) {
                            let accepted = await PulseReminderNotifications.showNow(reminderId: item.id, note: note, body: body, conversationId: item.conversationId, ownerId: deliveryOwner)
                            guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == viewer.id else {
                                if accepted { PulseReminderNotifications.cancel(reminderId: item.id, ownerId: deliveryOwner) }
                                return false
                            }
                            return accepted
                        }
                        guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == viewer.id else { return }
                        if queued { _ = try? await api.resolveReminder(item.id) }
                    }
                }
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    private func startRealtime(as viewer: PulseViewer, epoch: UUID) async {
        // 1. Manifest-driven endpoint override (cached in the Keychain from a
        //    previous launch is applied first, then a fresh fetch may refine;
        //    the probe targets the distribution CDN - the manifest lives there
        //    - and the user's Settings → Connection field always wins over it).
        PulseEndpoints.loadPersistedOverride()
        await PulseEndpoints.fetchManifestOverride()
        guard !Task.isCancelled, generation == epoch, self.viewer?.id == viewer.id else { return }

        // 2. Rebind the API client - the override may have moved the gateway.
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL, userId: viewer.id, authToken: PulseKeychain.shared.loadSessionToken())

        do {
            store = try PulseStore.openAccount(gateway: PulseEndpoints.gatewayURL.absoluteString, viewerId: viewer.id)
        } catch {
            // Never claim an in-memory queue is durable.
            startupError = "Local storage could not be opened. Free some device storage and retry."
            return
        }
        startOutbox()
        startCalls(viewer: viewer)
        startStories(viewer: viewer)
        startVoiceRooms(viewer: viewer)
        startReminderDueLoop(viewer: viewer)
        ready = true
        PulsePushRegistrationCenter.shared.noteViewerChanged()
        PulsePushNotifications.activate()

        // 3. Drain anything queued offline (flush trigger: session start).
        await flushOutboxNow()
        guard !Task.isCancelled, generation == epoch else { return }

        // 4. Wave 8 - pull the server-merged prefs blob (server value wins;
        //    optimistic local values keep working when this fails).
        await prefs?.syncFromServer(api: api)
        guard !Task.isCancelled, generation == epoch else { return }

        // 5. Realtime only when a relay base is configured (nil → offline-first,
        //    no reconnect spam against a dead address). The join payload
        //    carries the session token when one exists (verified server-side).
        guard let socketBase = PulseEndpoints.socketURL else { return }
        let client = PulseSocketClient(socketURL: socketBase)
        client.signals = { [weak self] signal in
            Task { @MainActor in
                guard let self, self.generation == epoch else { return }
                self.handle(signal)
            }
        }
        socket = client
        client.connect(userId: viewer.id, token: PulseKeychain.shared.loadSessionToken())
    }

    // calls (W3-b - Wave 3 native calls)

    /// Builds the call engines (real WebRTC provider + session-bound
    /// signaling) and drains any queued single-writer call-log rows from a
    /// previous offline session (app-start flush trigger).
    ///
    /// 3-d - BOTH engines share ONE PulseRTCMediaProvider (one WebRTC peer
    /// connection factory for the whole process) and the same signaling
    /// funnel. Cross-engine exclusion: the 1:1 engine checks
    /// PulseGroupCallEngine.active?.isBusy before dialing; the group engine
    /// gets a voiceCallBusy closure over this session's 1:1 engine.
    private func startCalls(viewer: PulseViewer) {
        guard let store else { return }
        let media = PulseRTCMediaProvider()
        let engine = PulseCallEngine(
            store: store,
            viewer: viewer,
            signaling: PulseSessionCallSignaling(session: self),
            media: media,
            apiProvider: { [weak self] in self?.api },
            toasts: toasts,
        )
        callEngine = engine
        Task { await engine.flushCallLogQueue() }

        let groupEngine = PulseGroupCallEngine(
            viewer: viewer,
            signaling: PulseSessionCallSignaling(session: self),
            media: media,
            apiProvider: { [weak self] in self?.api },
            connectedProvider: { [weak self] in self?.connected ?? false },
            voiceCallBusy: { [weak self] in self?.callEngine?.isBusy ?? false },
            toasts: toasts,
        )
        groupCallEngine = groupEngine

        // 3-d - CallKit OS-call integration: the coordinator observes BOTH
        // engines and reports every call to the system CXProvider (incoming
        // ring, dial, answer/end/mute actions drive the engines back).
        // attach/attachGroup are idempotent per instance - identity restarts
        // build fresh engines and re-attach here.
        PulseCallKitCoordinator.shared.attach(engine: engine)
        PulseCallKitCoordinator.shared.attachGroup(engine: groupEngine)
    }

    // stories (W4 - Wave 4 native stories)

    /// Builds the shared feed owner (REST + 60s poll parity; GRDB snapshot
    /// cache) once identity exists.
    private func startStories(viewer: PulseViewer) {
        stories = StoriesSessionModel(session: self)
    }

    // voice rooms / stage / space (W5-f)

    /// Builds the rooms owner once identity exists - pure models + the
    /// audio engine live here; the socket stays session-owned.
    private func startVoiceRooms(viewer: PulseViewer) {
        voiceRooms = VoiceRoomSessionModel(session: self)
    }

    /// The rooms owner's emit funnel - the socket is session-owned
    /// (mirrors emitCallSignal). Payloads come from the pure
    /// VoiceRoomWire builders (unit-tested).
    public func emitRoomSignal(event: String, payload: [String: Any]) {
        socket?.emitRoomSignal(event: event, payload: payload)
    }

    /// The engine's signaling sender funnels here (the socket is session-owned).
    public var callSignalingReady: Bool { ready && socket?.isJoined == true }

    @discardableResult
    public func emitCallSignal(event: String, payload: [String: Any]) -> Bool {
        guard callSignalingReady else { return false }
        return socket?.emitCallSignal(event: event, payload: payload) ?? false
    }

    // outbox

    private func startOutbox() {
        guard let store, let viewer else { return }
        let client = api
        let engine = PulseOutboxEngine(store: store) { client }
        engine.additionalThreadAccessDenied = { [weak self] rootId in
            self?.threadAccessDenied(rootId: rootId) ?? true
        }
        engine.onAcknowledgement = { [weak self] receipt in
            if let line = PulseStreakVerdict.toastLine(count: receipt.streak?.count, continued: receipt.streak?.continued) {
                self?.toasts.show(line)
            }
        }
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
        case .delivered(let message, let conversationId, let clientId):
            noteMessageRevision(message.id)
            realtimeRefreshTick += 1
            outboxEvents.send(.delivered(message: message, conversationId: conversationId, clientId: clientId))
        case .dropped(let clientId, let conversationId, let reason):
            outboxEvents.send(.dropped(clientId: clientId, conversationId: conversationId))
            toasts.show(reason.isEmpty ? "A queued message could not be delivered" : "Message not sent - \(reason)")
        }
    }

    /// Flush trigger for surfaces: app foreground, socket reconnect, enqueue.
    public func flushOutbox() {
        guard let outbox else { return }
        Task { await outbox.flush() }
    }

    @discardableResult
    public func retryOutbox(clientId: String, conversationId: String? = nil, threadRootId: String? = nil) -> Bool {
        guard ready, let viewer, let store else { return false }
        do {
            guard let row = try store.outboxAll().first(where: { $0.clientId == clientId && $0.failureReason != nil }) else { return false }
            if let conversationId, row.conversationId != conversationId { return false }
            let envelope = PulseOutboxForward.decode(row.payloadJson)
            if row.payloadJson != nil && envelope == nil { throw CocoaError(.coderReadCorrupt) }
            if let threadRootId, envelope?.parentId != threadRootId { return false }
            if let rootId = envelope?.parentId, threadAccessDenied(rootId: rootId) {
                toasts.show("Reopen this thread to check access. Your saved reply is kept.")
                return false
            }
            let anonymous = envelope?.anon == true
            var message = TempMessages.make(conversationId: row.conversationId, viewer: viewer, clientId: row.clientId,
                content: row.content, parentId: envelope?.parentId, anon: anonymous,
                anonAlias: anonymous ? PulseRemediationLogic.anonAlias(viewerId: viewer.id, conversationId: row.conversationId) : nil,
                topicId: envelope?.topicId, attachment: envelope)
            message.payload = envelope?.payload
            if try store.retryOutbox(clientId: clientId, message: message, threadRootId: threadRootId) {
                outbox?.reloadPending()
                flushOutbox()
                return true
            }
        } catch { toasts.show("The saved message could not be retried. It has not been discarded.") }
        return false
    }

    @discardableResult
    public func discardFailedThreadReply(clientId: String, conversationId: String, rootId: String) -> Bool {
        guard let store else { return false }
        do {
            guard try store.discardFailedThreadReply(clientId: clientId, conversationId: conversationId, rootId: rootId) else { return false }
            outbox?.reloadPending()
            outboxEvents.send(.dropped(clientId: clientId, conversationId: conversationId))
            return true
        } catch { toasts.show("Couldn't discard this reply. It is still saved.") }
        return false
    }

    public func discardOutbox(clientId: String, conversationId: String) {
        guard let store else { return }
        do {
            try store.deleteOutbox(clientId: clientId)
            outbox?.reloadPending()
            outboxEvents.send(.dropped(clientId: clientId, conversationId: conversationId))
        } catch {
            toasts.show("Could not discard this message: local storage is unavailable")
        }
    }

    private func flushOutboxNow() async {
        guard let outbox else { return }
        await outbox.flush()
    }

    // outbound
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
    /// R1-W2B D28 - `payloadJson` carries the queued forward envelope
    /// (PulseOutboxForward); plain sends leave it nil.
    @discardableResult
    public func enqueueOutbox(conversationId: String, clientId: String, content: String, kind: String = "text", payloadJson: String? = nil) -> Bool {
        let saved: Bool
        if let outbox {
            if let payloadJson {
                guard let forward = PulseOutboxForward.decode(payloadJson) else { return false }
                saved = outbox.appendForward(conversationId: conversationId, clientId: clientId, content: content, forward: forward)
            } else {
                saved = outbox.append(conversationId: conversationId, clientId: clientId, content: content, kind: kind)
            }
        } else {
            guard let store else { return false }
            do {
                try store.appendOutbox(conversationId: conversationId, clientId: clientId, content: content, kind: kind, payloadJson: payloadJson)
                saved = true
            } catch { return false }
        }
        if saved { flushOutbox() }
        return saved
    }

    /// Dock "More → Search" - the Chats tab listens for this tick and opens
    /// its search mode (real search, not a fake).
    public func requestChatsSearch() {
        searchRequestTick += 1
    }

    /// Dock compose / More → Saved - hand a conversation to the Chats tab to
    /// push onto its NavigationStack. Consumers must call `consumePendingOpenRoom()`
    /// when received (the published value also replays on re-subscription).
    /// Wave 2 - `jumpMessageId` rides along so the room scrolls to + flashes
    /// that message (saved-library "open original", spec §1 row 14).
    public func requestOpenRoom(_ conversation: WireConversationSummary, jumpMessageId: String? = nil) {
        noteRoomNavigationIntent()
        pendingRoomDelivery = UUID()
        pendingJumpMessageId = jumpMessageId
        pendingOpenRoom = conversation
    }

    /// @Published emits from willSet. Clearing inline from its subscriber can
    /// be overwritten by the outer setter and replay the already-opened room.
    /// Defer until that setter completes, without erasing a newer delivery.
    @discardableResult
    public func consumePendingOpenRoom() -> Task<Void, Never> {
        let delivery = pendingRoomDelivery, epoch = generation
        return Task { @MainActor [weak self] in
            guard let self, generation == epoch, pendingRoomDelivery == delivery else { return }
            pendingOpenRoom = nil
        }
    }

    public func consumePendingJumpMessageId() {
        pendingJumpMessageId = nil
    }

    /// Deep links / avatars - hand a user id to the Contacts tab so it pushes
    /// the full user page (F-CP-03). `name` seeds the nav title while the
    /// page fetches (nil-safe).
    public func requestOpenUser(_ userId: String, name: String?) {
        pendingUserDelivery = UUID()
        pendingUserRoute = UserRoute(userId: userId, name: name ?? "Profile")
    }

    @discardableResult
    public func consumePendingUserRoute() -> Task<Void, Never> {
        let delivery = pendingUserDelivery, epoch = generation
        return Task { @MainActor [weak self] in
            guard let self, generation == epoch, pendingUserDelivery == delivery else { return }
            pendingUserRoute = nil
        }
    }

    public func typers(in conversationId: String, excluding userId: String?) -> [Typer] {
        guard let all = typers[conversationId] else { return [] }
        return all.filter { $0.userId != userId }
    }

    // inbound
    private func handle(_ signal: PulseSocketClient.Signal) {
        switch signal {
        case .joined(let ids), .presenceSnapshot(let ids):
            // Presence broadcasts can precede our own accepted join. Only the
            // socket's post-ack readiness signal can enable realtime actions.
            onlineUserIds = Set(ids)
        case .joinError(let message):
            // Wave 8 - the relay verified our presented join token and
            // refused it (rotated elsewhere / invalid). Clear + degrade to
            // token-less so the reconnect lands instead of looping.
            noteAuthFailure(message)
            return
        case .connectionState(let isOn):
            connected = isOn
            // Flush trigger: socket connect / reconnect.
            if isOn {
                readFailureCount = 0
                readGate.restartDwell(now: ProcessInfo.processInfo.systemUptime)
                scheduleVisibleRead(after: PulseReadReceiptGate.dwell)
                flushOutbox()
                // W3-b - same trigger for the queued single-writer call rows.
                callEngine?.flushCallLogQueueOnReconnect()
            } else {
                // 3-d - a live mesh is dead once the relay drops us (media
                // flowing to a socket that stopped routing). Honest teardown
                // instead of ghosting audio after a suspension.
                callEngine?.handleSocketDisconnected()
                groupCallEngine?.handleSocketDisconnected()
            }
            // W5-f - the rooms owner consumes connect/reconnect too (voice
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
            // translation:added - live surfaces re-fetch on this tick.
            realtimeRefreshTick += 1
            cacheMessage(from: raw)
        case .messageRead:
            // Includes the viewer's other devices, even with receipt privacy on.
            noteInboxChanged()
            realtimeRefreshTick += 1
        case .conversationUpdated:
            realtimeRefreshTick += 1
        case .callSignal(let event, let raw):
            // W3-b - offer/answer/ice/reject/cancel/hangup → the call engine
            // (machine + WebRTC + single-writer log). Also relayed to feature
            // subscribers below.
            callEngine?.handleCallSignal(event: event, raw: raw)
        case .groupCallSignal(let event, let raw):
            // 3-d - gcall:ring/state/offer/answer/ice/ended/full → the mesh
            // group call engine (roster, deterministic offers, probe state).
            groupCallEngine?.handleGroupCallSignal(event: event, raw: raw)
        case .voiceRoster, .voicePtt, .voiceChunk, .voiceTranscript,
             .stageState, .stageEnded, .spaceState:
            // W5-f - the rooms owner consumes the 7 rooms signals (they
            // still reach every other subscriber via signals.send below).
            voiceRooms?.handle(signal)
        default:
            break
        }
        signals.send(signal)
    }

    /// Wave 0 offline hardening - every relayed message row lands in the
    /// cache so relaunch reads it back even when the surface is closed.
    private func cacheMessage(from raw: [String: Any]) {
        guard let store, let message = Self.decodeMessage(from: raw) else { return }
        noteMessageRevision(message.id)
        try? store.upsert(messages: [message])
    }

    /// Wave 8 - incoming attention (web pulse-realtime-provider parity):
    /// a message from SOMEONE ELSE, not a thread reply, in a room you are
    /// NOT reading → soft ping + buzz + preview toast, each behind its own
    /// toggle, all behind the LOCAL quiet-hours window.
    private func noteIncomingAttention(conversationId: String, raw: [String: Any]) {
        guard let message = Self.decodeMessage(from: raw), let viewer else { return }
        guard message.senderId != viewer.id,
              message.deletedAt == nil,
              message.parentId == nil,
              activeRoomId != conversationId else { return }
        guard !(prefs?.isQuietHoursNow ?? false) else { return } // quiet - stay silent
        // R14 5-b - the web gate chain (pulse-realtime-provider.tsx :602-605):
        // device soundOn AND quiet hours AND the per-account notifSound all
        // pass before the pop plays.
        if prefs?.soundOn == true, prefs?.notifSound == true {
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

    // shared socket payload decoding
    /// message:new / message:deleted / message:react all carry `message`.
    /// nonisolated: a pure decoder - safe from any queue (socket handlers,
    /// tests) with no session state touched.
    public nonisolated static func decodeMessage(from raw: [String: Any]) -> WireChatMessage? {
        let payload = raw["message"] as? [String: Any] ?? raw
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        return try? WireMessageEnvelope.extract(from: data)
    }
}

/// Real call-signaling sender - the engine emits through the session-owned
/// socket (PulseSocketClient.emitCallSignal). @MainActor to match the engine.
@MainActor
private final class PulseSessionCallSignaling: PulseCallSignalingSending {
    private weak var session: PulseSession?

    init(session: PulseSession) {
        self.session = session
    }

    var isReady: Bool { session?.callSignalingReady ?? false }

    @discardableResult
    func send(event: String, payload: [String: Any]) -> Bool {
        session?.emitCallSignal(event: event, payload: payload) ?? false
    }
}
