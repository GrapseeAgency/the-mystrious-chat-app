import UIKit
import Foundation
import Combine

/// Outbox events relayed to feature view models (the open room swaps its
/// queued placeholder for the real row; drops remove it honestly).
public enum PulseOutboxEvent: Sendable {
    case delivered(message: WireChatMessage, conversationId: String, clientId: String)
    case dropped(clientId: String, conversationId: String)
}

public struct PulseLinkedRoomRequest: Equatable, Sendable {
    public let id: UUID
    public let conversationId: String
    public let jumpMessageId: String?
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
    @Published public var dockUnreadCount = 0 {
        didSet { PulsePushNotifications.setUnreadBadge(dockUnreadCount) }
    }
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
    /// Linked-room bridge shared by reminder anchors and room-only callers.
    /// Root owns cancellable resolution; delivery IDs protect deferred cleanup
    /// even when the next request targets the same conversation or message.
    @Published public private(set) var pendingLinkedRoom: PulseLinkedRoomRequest?
    /// Compatibility for room-only callers; every assignment gets its own delivery.
    public var pendingLinkedRoomId: String? {
        get { pendingLinkedRoom?.conversationId }
        set {
            if let newValue { requestLinkedRoom(newValue) }
            else { pendingLinkedRoom = nil }
        }
    }

    @discardableResult
    public func requestLinkedRoom(_ conversationId: String, jumpMessageId: String? = nil) -> Bool {
        let room = conversationId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !room.isEmpty else { return false }
        let jump = jumpMessageId?.trimmingCharacters(in: .whitespacesAndNewlines)
        noteRoomNavigationIntent()
        pendingLinkedRoom = PulseLinkedRoomRequest(id: UUID(), conversationId: room,
            jumpMessageId: jump?.isEmpty == false ? jump : nil)
        return true
    }

    @discardableResult
    public func consumePendingLinkedRoom(_ request: PulseLinkedRoomRequest) -> Task<Void, Never> {
        let epoch = generation
        return Task { @MainActor [weak self] in
            guard let self, generation == epoch, pendingLinkedRoom?.id == request.id else { return }
            pendingLinkedRoom = nil
        }
    }
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
    @Published public private(set) var activeRoomId: String? {
        didSet {
            if oldValue != activeRoomId {
                // Only showRoomReadVisit can create fresh read authority.
                readGate.restartDwell(now: ProcessInfo.processInfo.systemUptime)
                readFailureCount = 0
                readReceiptTask?.cancel()
            }
        }
    }
    private var roomReadVisit = PulseRoomReadVisit()
    private var readAuthorityEpoch = UUID()
    private let primaryReadAuthority = PulseReadAuthority()
    private var readSurfaces = PulseReadSurfaces()
    private var activeReadAuthority: PulseReadAuthority { readSurfaces.owner?.authority ?? primaryReadAuthority }
    private var readGate: PulseReadReceiptGate {
        get { activeReadAuthority.gate }
        set { activeReadAuthority.gate = newValue }
    }
    private var readFailureCount = 0
    private var readReceiptTask: Task<Void, Never>?
    @Published private(set) var readStateRevisions: [String: UInt64] = [:]
    func readStateRevision(_ room: String) -> UInt64 { readStateRevisions[room] ?? 0 }
    func noteReadStateChanged(_ room: String) {
        readStateRevisions[room, default: 0] &+= 1
        noteInboxChanged()
    }

    func noteReadAllChanged(_ room: String) {
        noteReadStateChanged(room)
        guard let owner = viewer?.id else { return }
        // Ask live rooms to fetch the server's exact frozen set. Do not mark
        // every cached row read: newer arrivals may be outside that set.
        signals.send(.messageRead(conversationId: room, userId: owner, lastReadAt: nil, all: true))
    }

    private func publishConfirmedRead(_ read: PulseQueuedRead) {
        noteMessageRevision(read.message)
        // Exact proof is already committed; nil does not invent a read time.
        signals.send(.messageRead(conversationId: read.room, userId: read.owner, lastReadAt: nil,
            messageIds: [read.message], readerMembershipId: read.intent.split(separator: ".").last.map(String.init), all: false))
    }

    private var readReplayTask: Task<Void, Never>?
    private var readReplayID = UUID()

    private func replayReadJournal() {
        guard readReplayTask == nil, let store, let owner = viewer?.id, ready else { return }
        let client = api, epoch = generation, job = UUID()
        readReplayID = job
        readReplayTask = Task { [weak self] in
            defer { if self?.readReplayID == job { self?.readReplayTask = nil } }
            var failures = 0
            while let self, self.generation == epoch, !Task.isCancelled {
                guard let reads = try? store.pendingReads(owner: owner), !reads.isEmpty else { return }
                var retry: TimeInterval?
                var settledRooms = Set<String>()
                let publish: () -> Void = {
                    if self.generation == epoch { settledRooms.forEach { self.noteReadStateChanged($0) } }
                    settledRooms.removeAll()
                }
                defer { publish() }
                for read in reads {
                    guard self.generation == epoch, !Task.isCancelled, read.owner == owner else { return }
                    do {
                        try await client.markRead(conversationId: read.room, messageId: read.message,
                            minimumReadAt: read.minimumReadAt, readIntent: read.intent)
                        guard self.generation == epoch, self.viewer?.id == owner, !Task.isCancelled else { return }
                        settledRooms.insert(read.room)
                        if try store.recordConfirmedRead(read) { self.publishConfirmedRead(read) }
                        try store.acknowledgeRead(read)
                        failures = 0
                    } catch {
                        guard self.generation == epoch, self.viewer?.id == owner, !Task.isCancelled else { return }
                        if (error as? PulseAPIClient.Failure)?.retiresRead(owner: read.owner, room: read.room,
                            message: read.message, intent: read.intent) == true {
                            settledRooms.insert(read.room)
                            do { try store.acknowledgeRead(read) } catch { return }
                        } else {
                            failures += 1
                            guard let wait = PulseVisibleReadRetry.delay(after: error, failures: failures) else { return }
                            retry = wait
                            break
                        }
                    }
                }
                publish()
                if let retry {
                    do { try await Task.sleep(nanoseconds: UInt64(retry * 1_000_000_000)) } catch { return }
                }
            }
        }
    }

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
    private(set) var nativeReadInactive = UIApplication.shared.applicationState != .active
    var readAttentionRevision: UInt64 { readGate.revision }
    public private(set) var messageRevision: UInt64 = 0
    public private(set) var savedRevision: UInt64 = 0
    public func noteSavedMutation() { savedRevision &+= 1 }
    private var messageRevisions: [String: UInt64] = [:]

    public func acceptsSyncMessage(_ id: String, startedAt revision: UInt64) -> Bool {
        (messageRevisions[id] ?? 0) <= revision
    }

    private var threadValidationFence = PulseThreadValidationFence()
    private(set) var deniedThreadRoots: Set<String> = []

    public func threadAccessDenied(rootId: String) -> Bool {
        deniedThreadRoots.contains(rootId) || ((try? store?.threadAccessDenied(rootId: rootId)) ?? true)
    }

    /// Only the newest validation may change persistent access state.
    public func validatedThread(rootId: String, roomId: String) async throws -> WireThreadPage {
        guard let store, let viewer, ready else { throw CancellationError() }
        let ticket = threadValidationFence.begin(rootId), epoch = sessionGeneration, client = api, revision = messageRevision
        let snapshotRequest = store.beginMessageSnapshot()
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
            let saved = try store.acceptThreadSnapshot(rootId: rootId, roomId: roomId, messages: eligible, snapshotRequest: snapshotRequest)
            deniedThreadRoots.remove(rootId)
            messageVisibilityClock.send(Date())
            // Rows rejected by the revision fence may still be live in newer storage.
            let replies = try page.replies.compactMap { incoming -> WireChatMessage? in
                guard incoming.parentId == rootId, incoming.conversationId == roomId else { return nil }
                return try store.message(id: incoming.id)
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
            let retained = try store.mergeMessageAcknowledgement(receipt.message)
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

    private var savedActions: [String: UUID] = [:]

    /// Shared by room Save and library Unsave. A receipt is not UI success until
    /// the original account's marker has committed; never rewrite a tapped body.
    public func setMessageSaved(_ requested: WireChatMessage, saved: Bool) async throws {
        try Task.checkCancellation()
        guard ready, let viewer, let store, savedActions[requested.id] == nil else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Saved messages are unavailable or still updating.")
        }
        if saved {
            guard let cached = try store.message(id: requested.id), cached.conversationId == requested.conversationId,
                  !cached.id.hasPrefix("local_"), cached.deletedAt == nil, cached.viewOnce != true,
                  PulseRemediationLogic.isAlive(cached.expiresAt) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Refresh this message before saving it.")
            }
            if let root = cached.parentId, threadAccessDenied(rootId: root) {
                throw PulseAPIClient.Failure(kind: .forbidden, message: "Reopen this thread to check access before saving.")
            }
        }
        let epoch = sessionGeneration, client = api, ticket = UUID()
        savedActions[requested.id] = ticket
        noteSavedMutation()
        defer {
            if savedActions[requested.id] == ticket { savedActions.removeValue(forKey: requested.id) }
            if sessionGeneration == epoch { noteSavedMutation() }
        }
        let receipt = try await client.setMessageSaved(id: requested.id, userId: viewer.id, saved: saved)
        try Task.checkCancellation()
        guard sessionGeneration == epoch, self.viewer?.id == viewer.id else { throw CancellationError() }
        if saved {
            guard let savedAt = receipt.savedAt, let current = try store.message(id: requested.id),
                  current.conversationId == requested.conversationId else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Saved message is no longer cached. Refresh the library.")
            }
            let item = WireSavedItem(savedAt: savedAt,
                conversation: WireSavedConversation(id: current.conversationId, isGroup: nil, name: nil), message: current)
            try store.upsert(savedItems: [item], preservingMessageIds: [current.id])
        } else { try store.deleteSaved(messageId: requested.id) }
    }

    public enum MessageAction { case edit(String), pin, unpin, delete }
    private var messageActions: [String: UUID] = [:]

    /// One account-owned action per message, persisted before any surface accepts it.
    public func mutateMessage(_ requested: WireChatMessage, action: MessageAction) async throws -> WireChatMessage {
        try Task.checkCancellation()
        guard ready, let viewer, let store, !requested.id.hasPrefix("local_"),
              let cached = try store.message(id: requested.id), cached.conversationId == requested.conversationId,
              messageActions[requested.id] == nil else {
            throw PulseAPIClient.Failure(kind: .validation, message: "This message is unavailable or still updating.")
        }
        let ticket = UUID(), epoch = sessionGeneration, client = api, revision = messageRevision
        messageActions[requested.id] = ticket
        defer { if messageActions[requested.id] == ticket { messageActions.removeValue(forKey: requested.id) } }
        let incoming: WireChatMessage?
        switch action {
        case .edit(let content): incoming = try await client.editMessage(id: requested.id, userId: viewer.id, content: content)
        case .pin: incoming = try await client.pinMessage(id: requested.id, userId: viewer.id)
        case .delete: incoming = try await client.deleteOwnMessage(id: requested.id)
        case .unpin:
            try await client.unpinMessage(id: requested.id, userId: viewer.id)
            incoming = nil
        }
        try Task.checkCancellation()
        guard sessionGeneration == epoch, self.viewer?.id == viewer.id else { throw CancellationError() }
        if let incoming {
            guard incoming.id == requested.id, incoming.conversationId == requested.conversationId else {
                throw PulseAPIClient.Failure(kind: .validation, message: "The action returned an unrelated message.")
            }
            if acceptsSyncMessage(incoming.id, startedAt: revision) {
                try recordMessageMutation(incoming)
            } else {
                switch action {
                case .delete: try recordMessageMutation(incoming) // deletion is monotonic
                case .edit:
                    if let current = try store.message(id: requested.id) {
                        let merged = PulseMessageMerge.acceptEdit(current, incoming: incoming)
                        try recordMessageMutation(merged)
                    }
                case .pin, .unpin: break // never rewind a later relayed pin/reaction
                }
            }
        } else { try applyPinRemoval(requested.id, startedAt: revision) }
        guard let retained = try store.message(id: requested.id) else {
            throw PulseAPIClient.Failure(kind: .notFound, message: "This message is no longer available.")
        }
        return retained
    }

    private var pollActions: [String: UUID] = [:]

    public func updatePoll(conversationId: String, pollId: String, optionId: String?) async throws -> WireChatMessage {
        try Task.checkCancellation()
        guard ready, let viewer, let store, !pollId.isEmpty, pollActions[pollId] == nil else {
            throw PulseAPIClient.Failure(kind: .validation, message: "This poll is unavailable or still updating.")
        }
        let ticket = UUID(), epoch = sessionGeneration, client = api, revision = messageRevision
        pollActions[pollId] = ticket
        defer { if pollActions[pollId] == ticket { pollActions.removeValue(forKey: pollId) } }
        let incoming: WireChatMessage
        if let optionId { incoming = try await client.votePoll(pollId: pollId, userId: viewer.id, optionId: optionId) }
        else { incoming = try await client.closePoll(pollId: pollId, userId: viewer.id) }
        try Task.checkCancellation()
        guard sessionGeneration == epoch, self.viewer?.id == viewer.id else { throw CancellationError() }
        guard incoming.conversationId == conversationId else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Poll returned another conversation.")
        }
        if let current = try store.message(id: incoming.id), current.conversationId != incoming.conversationId {
            throw PulseAPIClient.Failure(kind: .validation, message: "Poll returned another conversation.")
        }
        if acceptsSyncMessage(incoming.id, startedAt: revision) {
            try recordMessageMutation(incoming)
        } else if incoming.poll?.closed == true, var current = try store.message(id: incoming.id), current.deletedAt == nil {
            // Closure is monotonic, but do not rewind other fields from a newer event.
            current.poll = incoming.poll
            try recordMessageMutation(current)
        }
        guard let retained = try store.message(id: incoming.id) else {
            throw PulseAPIClient.Failure(kind: .notFound, message: "This poll is no longer available.")
        }
        return retained
    }

    private var reactionActions: [String: UUID] = [:]

    /// Shared by the river, reaction drawer and alternate room. Persist before UI acceptance;
    /// a late HTTP row must not overwrite a newer relayed edit/reaction/tombstone.
    public func setReaction(_ requested: WireChatMessage, emoji: String, present: Bool) async throws {
        try Task.checkCancellation()
        guard ready, let store, let viewer, !requested.id.hasPrefix("local_"),
              let cached = try store.message(id: requested.id), cached.conversationId == requested.conversationId,
              cached.deletedAt == nil, PulseRemediationLogic.isAlive(cached.expiresAt) else {
            throw PulseAPIClient.Failure(kind: .validation, message: "This message is no longer available. Refresh before reacting.")
        }
        if let rootId = cached.parentId, threadAccessDenied(rootId: rootId) {
            throw PulseAPIClient.Failure(kind: .forbidden, message: "Reopen this thread to check access before reacting.")
        }
        guard reactionActions[requested.id] == nil else {
            throw PulseAPIClient.Failure(kind: .validation, message: "A reaction is still updating.")
        }
        let ticket = UUID(), epoch = sessionGeneration, revision = messageRevision, client = api
        reactionActions[requested.id] = ticket
        defer { if reactionActions[requested.id] == ticket { reactionActions.removeValue(forKey: requested.id) } }
        let fresh = try await client.react(messageId: requested.id, emoji: emoji, present: present)
        try Task.checkCancellation()
        guard sessionGeneration == epoch, self.viewer?.id == viewer.id else { throw CancellationError() }
        guard fresh.id == requested.id, fresh.conversationId == requested.conversationId else {
            throw PulseAPIClient.Failure(kind: .validation, message: "Reaction returned another message.")
        }
        if acceptsSyncMessage(fresh.id, startedAt: revision) { try recordMessageMutation(fresh) }
    }

    @discardableResult
    public func recordMessageMutation(_ message: WireChatMessage) throws -> WireChatMessage {
        let retained = try store?.mergeMessageAcknowledgement(message) ?? message
        noteMessageRevision(message.id)
        messageVisibilityClock.send(Date())
        return retained
    }

    public func applyPinRemoval(_ id: String, startedAt revision: UInt64) throws {
        guard acceptsSyncMessage(id, startedAt: revision) else { return }
        try store?.clearMessagePin(id: id)
        noteMessageRevision(id)
        messageVisibilityClock.send(Date())
    }

    /// Read-only Info reconciliation. nil is unknown/superseded, not "unread".
    func refreshMessageReadState(conversationId: String, messageId: String) async -> (available: Bool, memberships: [String], revision: UInt64, snapshotVersion: UInt64)? {
        guard ready, isForeground, let store, !messageId.hasPrefix("local_") else { return nil }
        let epoch = sessionGeneration, revision = messageRevision, client = api
        let snapshotVersion = store.messageSnapshotVersion(messageId)
        let available: Bool
        let memberships: [String]
        do {
            let proof = try await client.messageReadState(conversationId: conversationId, messageId: messageId)
            memberships = proof.memberships; available = proof.available
        } catch { return nil }
        guard !Task.isCancelled, epoch == sessionGeneration, isForeground,
              acceptsSyncMessage(messageId, startedAt: revision) else { return nil }
        do {
            let changed = try store.replaceMessageReadState(conversationId: conversationId, messageId: messageId, memberships: memberships, snapshotVersion: snapshotVersion)
            // The live room can retain a carrier absent from storage. Fence older
            // page responses even when there was no stored row to update.
            noteMessageRevision(messageId)
            if changed { messageVisibilityClock.send(Date()) }
            return (available, memberships, messageRevision, snapshotVersion)
        } catch { return nil }
    }

    private func noteMessageRevision(_ id: String) {
        messageRevision &+= 1
        messageRevisions[id] = messageRevision
    }

    private var generation = UUID()
    public var sessionGeneration: UUID { generation }
    private var startupTask: Task<Void, Never>?
    private var notificationRefreshTask: Task<Void, Never>?
    private var notificationRefreshRevision: UInt64 = 0

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
            reconcileMessageNotifications()
            messageVisibilityClock.send(Date())
            if changed {
                readFailureCount = 0
                scheduleVisibleRead(after: PulseReadReceiptGate.dwell)
            }
        }
    }

    func showRoomReadVisit(conversationId: String, entryID: UUID) {
        if roomReadVisit.activate(entryID: entryID, roomID: conversationId) {
            readAuthorityEpoch = UUID()
            primaryReadAuthority.bind(to: readAuthorityEpoch)
            readSurfaces.reset()
        }
        readFailureCount = 0
        activeRoomId = conversationId
        roomVisible = true
        restartReadAttention()
    }

    func ownsRoomReadVisit(conversationId: String, entryID: UUID) -> Bool {
        roomReadVisit.owns(entryID: entryID, roomID: conversationId)
    }

    @discardableResult
    func hideRoomReadVisit(conversationId: String, entryID: UUID) -> Bool {
        guard roomReadVisit.suspend(entryID: entryID, roomID: conversationId) else { return false }
        readSurfaces.setPrimary(roomId: conversationId, messageIds: [])
        readReceiptTask?.cancel()
        activeRoomId = nil
        roomVisible = false
        return true
    }

    public func setVisibleReadBoundary(conversationId: String, messageId: String?) {
        guard activeRoomId == conversationId else { return }
        setVisibleReadBoundaries(conversationId: conversationId, messageIds: messageId.map { [$0] } ?? [])
    }

    public func setVisibleReadBoundaries(conversationId: String, messageIds: [String]) {
        guard activeRoomId == conversationId else { return }
        readSurfaces.setPrimary(roomId: conversationId, messageIds: messageIds)
        applyVisibleReadBoundary(conversationId: conversationId)
    }

    func beginReadSurface(conversationId: String, rootId: String, authority: PulseReadAuthority? = nil) -> PulseReadSurfaces.Owner? {
        guard activeRoomId == conversationId else { return nil }
        let authority = authority ?? PulseReadAuthority()
        authority.bind(to: readAuthorityEpoch)
        let owner = readSurfaces.begin(roomId: conversationId, rootId: rootId, authority: authority)
        applyVisibleReadBoundary(conversationId: conversationId)
        restartReadAttention() // same message in a different surface still needs fresh dwell
        return owner
    }

    func setVisibleReadBoundary(owner: PulseReadSurfaces.Owner, messageId: String?) {
        guard activeRoomId == owner.roomId else { return }
        setVisibleReadBoundaries(owner: owner, messageIds: messageId.map { [$0] } ?? [])
    }

    func setVisibleReadBoundaries(owner: PulseReadSurfaces.Owner, messageIds: [String]) {
        guard activeRoomId == owner.roomId else { return }
        readSurfaces.setOverlay(owner, messageIds: messageIds)
        applyVisibleReadBoundary(conversationId: owner.roomId)
    }

    func endReadSurface(_ owner: PulseReadSurfaces.Owner) {
        guard readSurfaces.end(owner), activeRoomId == owner.roomId else { return }
        applyVisibleReadBoundary(conversationId: owner.roomId)
        restartReadAttention()
    }

    private func applyVisibleReadBoundary(conversationId: String) {
        let messageIds = readSurfaces.boundaries.map(\.messageId)
        guard readGate.setBoundaries(roomId: conversationId, messageIds: messageIds,
            now: ProcessInfo.processInfo.systemUptime) else { return }
        readFailureCount = 0
        readReceiptTask?.cancel()
        if !messageIds.isEmpty && isForeground { scheduleVisibleRead(after: 0) }
    }

    private func scheduleVisibleRead(after delay: TimeInterval) {
        guard let boundary = readGate.boundary,
              let remaining = readGate.nextDelay(now: ProcessInfo.processInfo.systemUptime) else { return }
        let delay = max(delay, remaining)
        let epoch = generation, revision = readGate.revision
        readReceiptTask?.cancel()
        readReceiptTask = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
            guard let self, self.generation == epoch, self.readGate.revision == revision else { return }
            await self.markReadIfVisible(conversationId: boundary.roomId)
        }
    }

    private let nativeReadAnchors = NSMapTable<NSUUID, UIView>(keyOptions: .strongMemory, valueOptions: .weakMemory)
    private let nativeInactiveScenes = NSHashTable<UIScene>.weakObjects()
    private var nativePresentationBlocked = true

    private var activeNativeReadView: UIView? {
        guard let owner = readSurfaces.owner?.id ?? roomReadVisit.owner?.entryID else { return nil }
        return nativeReadAnchors.object(forKey: owner as NSUUID)
    }

    var nativePresentationBlockedNow: Bool {
        guard let view = activeNativeReadView, let window = view.window,
              !window.isHidden, let scene = window.windowScene,
              scene.activationState == .foregroundActive, !nativeInactiveScenes.contains(scene) else { return true }
        return PulseReadPresentation.hasPresentationAbove(view)
    }

    /// Diagnostics only: include the actual selected anchor and scene, rather
    /// than inferring the cause of a failed check from a timeout alone.
    func nativeReadPresentationDiagnostics() -> String {
        let owner = readSurfaces.owner?.id ?? roomReadVisit.owner?.entryID
        guard let view = activeNativeReadView else {
            return "missing active anchor; owner=\(String(describing: owner)); registered=\(nativeReadAnchors.count)"
        }
        let scene = view.window?.windowScene
        return "owner=\(String(describing: owner)); attached=\(view.window != nil); hidden=\(String(describing: view.window?.isHidden)); scene=\(String(describing: scene?.activationState.rawValue)); notifiedInactive=\(scene.map { nativeInactiveScenes.contains($0) } ?? false); " + PulseReadPresentation.diagnostics(view)
    }

    func setNativeReadAnchor(_ view: UIView, owner: UUID) {
        guard nativeReadAnchors.object(forKey: owner as NSUUID) !== view else { return }
        nativeReadAnchors.setObject(view, forKey: owner as NSUUID)
        if activeNativeReadView === view { restartReadAttention() }
        else { refreshNativeReadPresentation() }
    }

    func nativeReadAnchorWindowChanged(_ view: UIView, owner: UUID) {
        guard nativeReadAnchors.object(forKey: owner as NSUUID) === view,
              activeNativeReadView === view else { return }
        // Moving directly between two active windows need not pass through a
        // detached state. Preserve that interruption even if the probe is still false.
        restartReadAttention()
    }

    func removeNativeReadAnchor(_ view: UIView, owner: UUID) {
        guard nativeReadAnchors.object(forKey: owner as NSUUID) === view else { return }
        nativeReadAnchors.removeObject(forKey: owner as NSUUID)
        refreshNativeReadPresentation()
    }

    func refreshNativeReadPresentation() {
        guard nativePresentationBlockedNow != nativePresentationBlocked else { return }
        restartReadAttention()
    }

    private var transientReadObscurations: Set<UUID> = []
    func setTransientReadObscured(_ id: UUID, _ obscured: Bool) {
        let changed = obscured ? transientReadObscurations.insert(id).inserted : transientReadObscurations.remove(id) != nil
        if changed { restartReadAttention() }
    }

    private var shellObscuresReading = false
    public func setShellReadObscured(_ obscured: Bool) {
        guard shellObscuresReading != obscured else { return }
        shellObscuresReading = obscured
        restartReadAttention()
    }

    private var readAttentionBlocked: Bool {
        nativeReadInactive || nativePresentationBlockedNow || !transientReadObscurations.isEmpty || (callEngine?.state ?? .idle) != .idle || (groupCallEngine?.uiState ?? .idle) != .idle ||
            groupCallEngine?.ring != nil || voiceRooms?.surface != nil || PulseAppLock.shared.needsLock ||
            readSurfaces.isObscured(shellPresented: shellObscuresReading, pipOpen: pip.isOpen)
    }

    private func restartReadAttention() {
        nativePresentationBlocked = nativePresentationBlockedNow
        readReceiptTask?.cancel()
        readGate.restartDwell(now: ProcessInfo.processInfo.systemUptime)
        if isForeground && !readAttentionBlocked { scheduleVisibleRead(after: PulseReadReceiptGate.dwell) }
    }

    public func markReadIfVisible(conversationId: String?) async {
        refreshNativeReadPresentation()
        guard let conversationId, ready, isForeground,
              activeRoomId == conversationId, !readAttentionBlocked,
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
        let epoch = generation
        do {
            guard let anchor = try store?.message(id: request.boundary.messageId), anchor.conversationId == conversationId,
                  anchor.deletedAt == nil, PulseRemediationLogic.isAlive(anchor.expiresAt), !anchor.id.hasPrefix("local_") else {
                throw PulseAPIClient.Failure(kind: .validation, message: "The displayed read boundary is no longer available.")
            }
            try Task.checkCancellation()
            let intent: String
            if let retained = readGate.intent(for: request) { intent = retained }
            else {
                let member: WireConversationMember?
                do {
                    let cached = try store?.cachedConversation(id: conversationId)
                    member = cached.flatMap {
                        WireConversationSummary(cached: $0).members.first(where: { $0.id == viewer?.id })
                    }
                } catch {
                    readGate.noteMissingAuthority(for: request)
                    throw error
                }
                guard let member, let membership = member.readMembershipId, !membership.isEmpty else {
                    readGate.noteMissingAuthority(for: request)
                    throw PulseAPIClient.Failure(kind: .network, message: "Waiting for current room membership")
                }
                intent = readGate.candidateIntent(current: member.readIntent, membership: membership)
                guard generation == epoch, readGate.retainIntent(intent, for: request) else { return }
            }
            try Task.checkCancellation()
            guard let store, let owner = viewer?.id else { return }
            _ = try store.enqueueRead(PulseQueuedRead(owner: owner, room: conversationId,
                message: request.boundary.messageId, minimumReadAt: anchor.createdAt, intent: intent))
            replayReadJournal()
            guard generation == epoch else { return }
            if readGate.finish(request, succeeded: true) {
                readFailureCount = 0
                if readGate.boundary != nil { scheduleVisibleRead(after: 0) }
            }
            // The observation is durable, not necessarily delivered. The replay
            // owner refreshes badges only after the server's exact acknowledgement.
        } catch {
            guard generation == epoch, readGate.finish(request, succeeded: false),
                  !Task.isCancelled, isForeground, activeRoomId == conversationId else { return }
            readFailureCount = min(7, readFailureCount + 1)
            guard let wait = PulseVisibleReadRetry.delay(after: error, failures: readFailureCount) else {
                readGate.deferAttempt(until: .infinity)
                scheduleVisibleRead(after: 0) // a refused row must not starve independent visible identities
                return
            }
            readGate.deferAttempt(until: ProcessInfo.processInfo.systemUptime + wait)
            scheduleVisibleRead(after: wait)
        }
    }

    private var socket: PulseSocketClient?
    private var cancellables: Set<AnyCancellable> = []

    public init(notificationCenter: NotificationCenter = .default) {
        // Pre-login client: no viewer yet (no token to attach). start(as:)
        // rebuilds it with the real userId + persisted Keychain token.
        api = PulseAPIClient(baseURL: PulseEndpoints.gatewayURL)
        for (name, inactive) in [(UIApplication.willResignActiveNotification, true),
                                 (UIApplication.didBecomeActiveNotification, false)] {
            let observer = notificationCenter.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                // Preserve both events synchronously even if SwiftUI coalesces
                // inactive -> active into one unchanged scenePhase frame.
                MainActor.assumeIsolated {
                    self?.nativeReadInactive = inactive
                    self?.restartReadAttention()
                }
            }
            AnyCancellable { notificationCenter.removeObserver(observer) }.store(in: &cancellables)
        }
        for (name, inactive) in [(UIScene.willDeactivateNotification, true),
                                 (UIScene.didActivateNotification, false)] {
            let observer = notificationCenter.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                MainActor.assumeIsolated {
                    guard let self, let scene = note.object as? UIScene else { return }
                    if inactive { self.nativeInactiveScenes.add(scene) }
                    else { self.nativeInactiveScenes.remove(scene) }
                    if self.activeNativeReadView?.window?.windowScene === scene { self.restartReadAttention() }
                }
            }
            AnyCancellable { notificationCenter.removeObserver(observer) }.store(in: &cancellables)
        }
        // Timer publisher is not actor-isolated; hop back per tick.
        Timer.publish(every: 1, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                if self?.isForeground == true {
                    // Reuse the visibility tick for UIKit dismissal recovery;
                    // qualification itself checks the presentation synchronously.
                    // A newly unobscured surface always starts a full new dwell.
                    self?.refreshNativeReadPresentation()
                    self?.messageVisibilityClock.send(Date())
                }
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
        prefs.refreshSettingsOwner()
        prefs.notePatchSettled(successful: true, note: nil)
        prefs.patchScreenPrivacy = { [weak self] ticket in self?.sendScreenPrivacy(ticket) }
        guard prefs.patchRemote == nil else { return }
        prefs.patchRemote = { [weak self] patch in
            self?.sendPrefsPatch(patch)
        }
        prefs.authRejectionHandler = { [weak self] message in
            self?.noteAuthFailure(message)
        }
    }

    /// Optimistic settings PATCH: the local value already applied; this
    /// queues { userId, preferences } in order and applies only its current,
    /// explicitly confirmed fields. Failure retains intent for explicit Retry;
    /// unrelated replies and older GETs must not reset the local choice.
    private let settingsWrites = PulseSettingsWriteQueue()
    private func sendScreenPrivacy(_ ticket: PulseScreenPrivacyTicket) {
        guard let owner = viewer?.id, prefs?.viewer?.id == owner, api.userId == owner else { return }
        let client = api, epoch = generation
        settingsWrites.enqueue { [weak self] in
            guard let self, !Task.isCancelled, self.generation == epoch,
                  self.viewer?.id == owner, let prefs = self.prefs, prefs.viewer?.id == owner else { return }
            do {
                guard try prefs.currentScreenPrivacy(ticket, api: client) else { return }
                let confirmed = try await client.setMyScreenPrivacy(ticket.room, on: ticket.on)
                guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == owner else { return }
                try prefs.acknowledgeScreenPrivacy(ticket, on: confirmed)
            } catch {
                guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == owner else { return }
                prefs.noteScreenPrivacyFailure(ticket)
            }
        }
    }
    private func sendPrefsPatch(_ ticket: PulseSettingsTicket) {
        guard let owner = viewer?.id, prefs?.viewer?.id == owner, api.userId == owner else { return }
        let client = api, epoch = generation
        settingsWrites.enqueue { [weak self] in
            guard let self, !Task.isCancelled, self.generation == epoch,
                  self.viewer?.id == owner, let prefs = self.prefs, prefs.viewer?.id == owner else { return }
            do {
                let patch = try prefs.currentSettingsPatch(ticket, api: client)
                if patch.settingsKeys.isEmpty { return } // superseded before dispatch
                let server = try await client.updateSettings(patch: patch)
                guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == owner,
                      prefs.viewer?.id == owner else { return }
                try prefs.acknowledgeSettings(ticket, sent: patch, server: server)
            } catch let failure as PulseAPIClient.Failure where failure.kind == .auth {
                guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == owner,
                      prefs.viewer?.id == owner else { return }
                prefs.notePatchSettled(successful: false, note: "Settings were not confirmed. Sign in to the original account before retrying.")
                self.noteAuthFailure(failure.message)
            } catch {
                guard !Task.isCancelled, self.generation == epoch, self.viewer?.id == owner,
                      prefs.viewer?.id == owner else { return }
                prefs.notePatchSettled(successful: false, note: "Settings saved on this device were not confirmed by the server. Tap Retry.")
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
        notificationRefreshTask?.cancel(); notificationRefreshTask = nil
        readReplayID = UUID()
        readReplayTask?.cancel()
        readReplayTask = nil
        readStateRevisions = [:]
        settingsWrites.cancelAll()
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
        reactionActions.removeAll()
        pollActions.removeAll()
        messageActions.removeAll()
        savedActions.removeAll()
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
        roomReadVisit.reset()
        readAuthorityEpoch = UUID()
        primaryReadAuthority.bind(to: readAuthorityEpoch)
        readSurfaces.reset()
        readReceiptTask?.cancel()
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
        prefs?.refreshSettingsOwner()
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
                            let accepted = await PulseReminderNotifications.showNow(reminderId: item.id, note: note, body: body, conversationId: item.conversationId, ownerId: deliveryOwner, messageId: item.messageId)
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
        await prefs?.syncFromServer(api: api, isCurrent: { [weak self] in self?.generation == epoch })
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
        engine.$state.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.restartReadAttention() }.store(in: &cancellables)
        groupEngine.$uiState.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.restartReadAttention() }.store(in: &cancellables)
        groupEngine.$ring.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.restartReadAttention() }.store(in: &cancellables)

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
        voiceRooms?.$surface.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.restartReadAttention() }.store(in: &cancellables)
        PulseAppLock.shared.$needsLock.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.restartReadAttention() }.store(in: &cancellables)
        pip.$panes.receive(on: DispatchQueue.main).sink { [weak self] _ in self?.restartReadAttention() }.store(in: &cancellables)
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
        let readHintEpoch = generation
        engine.onReadStateInvalidation = { [weak self] room in
            guard let self, self.ready, self.generation == readHintEpoch else { return }
            self.noteReadStateChanged(room)
        }
        engine.onReadJournalFlush = { [weak self] in
            guard let self, self.ready, self.generation == readHintEpoch,
                  let owner = self.viewer?.id, let store = self.store else { return false }
            let client = self.api, epoch = self.generation
            var settledRooms = Set<String>()
            var confirmed: [PulseQueuedRead] = []
            let succeeded = await PulseReadJournalReplay.flush(store: store, owner: owner,
                isCurrent: { self.generation == epoch && self.viewer?.id == owner },
                onConfirmed: { confirmed.append($0) },
                onSettled: { settledRooms.insert($0.room) },
                send: { read in
                    try await client.markRead(conversationId: read.room, messageId: read.message,
                        minimumReadAt: read.minimumReadAt, readIntent: read.intent)
                })
            if self.generation == epoch {
                confirmed.forEach { self.publishConfirmedRead($0) }
                settledRooms.forEach { self.noteReadStateChanged($0) }
            }
            return succeeded
        }
        engine.onBadgeRefresh = { [weak self] in
            guard let self, self.ready, self.generation == readHintEpoch, let store = self.store else { return false }
            let epoch = self.generation, inboxRevision = self.inboxRefreshTick, liveRevision = self.realtimeRefreshTick
            return await PulsePushNotifications.refreshUnreadBadge(using: self.api, store: store, isCurrent: {
                self.generation == epoch && self.inboxRefreshTick == inboxRevision && self.realtimeRefreshTick == liveRevision
            })
        }
        engine.onEvent = { [weak self] event in
            self?.routeOutboxEvent(event)
        }
        outbox = engine
        PulseOutboxEngine.active = engine
        reconcileMessageNotifications()
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
        replayReadJournal()
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

    /// Discard the manager's displayed state, never a send retried since display.
    @discardableResult
    func discardOutbox(_ expected: OutboxRow) throws -> Bool {
        guard let store else { throw CocoaError(.fileReadUnknown) }
        let epoch = generation, engine = outbox
        guard try store.discardOutboxIfUnchanged(expected) else { return false }
        engine?.reloadPending()
        if generation == epoch { outboxEvents.send(.dropped(clientId: expected.clientId, conversationId: expected.conversationId)) }
        return true
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
        replayReadJournal()
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
        reconcileMessageNotifications()
    }

    private func reconcileMessageNotifications() {
        guard ready, isForeground else { return }
        notificationRefreshRevision &+= 1
        guard notificationRefreshTask == nil else { return }
        let epoch = generation, client = api
        notificationRefreshTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.generation == epoch { self.notificationRefreshTask = nil } }
            var failures = 0
            repeat {
                guard !Task.isCancelled, self.ready, self.isForeground, self.generation == epoch else { return }
                let revision = self.notificationRefreshRevision
                let result = await PulsePushNotifications.reconcileDeliveredMessages(using: client, isCurrent: {
                    self.ready && self.isForeground && self.generation == epoch && self.viewer?.id == client.userId
                }, failures: failures + 1)
                guard !Task.isCancelled, self.ready, self.isForeground, self.generation == epoch else { return }
                if !result.complete, let delay = result.retryDelay {
                    failures = min(failures + 1, 7)
                    // A concurrent invalidation stays coalesced, but must not
                    // bypass a server Retry-After deadline.
                    do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
                    catch { return }
                    continue
                }
                guard revision != self.notificationRefreshRevision else { return }
                // A read arriving during the previous proof cannot be lost to coalescing.
                failures = 0
            } while true
        }
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
    private func handle(_ incomingSignal: PulseSocketClient.Signal) {
        // Normalize before storage AND fanout: room/thread/Mirror models keep
        // their own arrays and must see the same preserved owner-only proof.
        let signal = preservingOwnSocketReadState(incomingSignal)
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
            cacheMessage(from: raw, conversationId: conversationId)
            noteIncomingAttention(conversationId: conversationId, raw: raw)
        case .messageDeleted(let room, let raw), .messageReact(let room, let raw):
            cacheMessage(from: raw, conversationId: room)
        case .messageEnvelope(_, let room, let raw):
            cacheMessage(from: raw, conversationId: room)
            // message:edited/pinned/viewed, poll:voted, link:preview,
            // translation:added - live surfaces re-fetch on this tick.
            realtimeRefreshTick += 1
        case .messageRead(let room, _, _, let ids, let membership, _):
            if let membership, !membership.isEmpty {
                for id in Set(ids) {
                    guard var message = try? store?.message(id: id), message.conversationId == room else { continue }
                    message.readByMembershipIds = Array(Set((message.readByMembershipIds ?? []) + [membership]))
                    noteMessageRevision(id)
                    try? store?.upsert(messages: [message])
                }
            }
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

    private func preservingOwnSocketReadState(_ signal: PulseSocketClient.Signal) -> PulseSocketClient.Signal {
        func patched(_ room: String, _ raw: [String: Any]) -> [String: Any] {
            guard let viewer, let store, let incoming = Self.decodeMessage(from: raw), incoming.conversationId == room,
                  let cached = try? store.cachedConversation(id: room) else { return raw }
            // The storage domain row carries membership in its wire snapshot,
            // not as a PulseConversation property. Legacy caches have no proof.
            let summary = WireConversationSummary(cached: cached)
            guard summary.id == room,
                  let membership = summary.members.first(where: { $0.id == viewer.id })?.readMembershipId else { return raw }
            let current = try? store.message(id: incoming.id)
            let retained = PulseMessageMerge.retainOwnSocketReadState(current, incoming: incoming, membership: membership)
            guard retained.readByMembershipIds != incoming.readByMembershipIds else { return raw }
            var result = raw
            if var message = raw["message"] as? [String: Any] {
                message["readByMembershipIds"] = retained.readByMembershipIds ?? []
                result["message"] = message
            } else { result["readByMembershipIds"] = retained.readByMembershipIds ?? [] }
            return result
        }
        switch signal {
        case .messageNew(let room, let raw): return .messageNew(conversationId: room, raw: patched(room, raw))
        case .messageDeleted(let room, let raw): return .messageDeleted(conversationId: room, raw: patched(room, raw))
        case .messageReact(let room, let raw): return .messageReact(conversationId: room, raw: patched(room, raw))
        case .messageEnvelope(let event, let room, let raw): return .messageEnvelope(event: event, conversationId: room, raw: patched(room, raw))
        default: return signal
        }
    }

    /// Wave 0 offline hardening - every relayed message row lands in the
    /// cache so relaunch reads it back even when the surface is closed.
    private func cacheMessage(from raw: [String: Any], conversationId: String) {
        guard let store, let message = Self.decodeMessage(from: raw), message.conversationId == conversationId else { return }
        noteMessageRevision(message.id)
        try? store.upsert(messages: [message])
    }

    /// Wave 8 - incoming attention (web pulse-realtime-provider parity):
    /// a message from SOMEONE ELSE, not a thread reply, in a room you are
    /// NOT reading → soft ping + buzz + preview toast, each behind its own
    /// toggle, all behind the LOCAL quiet-hours window.
    private func noteIncomingAttention(conversationId: String, raw: [String: Any]) {
        guard ready, isForeground, let store, let viewer,
              let message = Self.decodeMessage(from: raw),
              let preview = message.incomingPreview(viewerId: viewer.id, roomId: conversationId),
              activeRoomId != conversationId else { return }
        do {
            if try store.cachedConversation(id: conversationId)?.isMuted == true { return }
        } catch { return } // A cache failure is not permission to ignore mute policy.
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
            toasts.show("\(preview.senderName): \(preview.body)")
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
