import SwiftUI
import Combine

/// Wave 1 thread surface - the web ThreadSheet outcome natively: parent card
/// on top, replies asc below, composer that sends `parentId` (spec §1.1 -
/// the thread-ROOT id, NEVER the inline-quote replyToId). Optimistic temp
/// rows swap on send exactly like the river; realtime appends arrive via the
/// session signal stream; every landed row persists through the GRDB cache.
/// Replies use the same durable operation identity and outbox as the main room.
struct ThreadView: View {
    let conversation: WireConversationSummary
    let root: WireChatMessage
    @ObservedObject var session: PulseSession
    var initialJumpMessageId: String? = nil

    enum Phase: Equatable { case loading, loaded, failed(String) }

    @State private var parent: WireChatMessage?
    @State private var replies: [WireChatMessage] = []
    @State private var phase: Phase = .loading
    @State private var draft = ""
    @State private var draftJournal: PulseDraftJournal?
    @State private var draftRevision: UInt64 = 0
    @State private var draftSession: UUID?
    @State private var rootUnavailable = false
    @State private var loadGeneration = UUID()
    @State private var initialJumpResolved = false
    @State private var highlightedReplyId: String?
    @State private var nearTail = true
    @State private var visibleReadIDs: Set<String> = []
    @State private var scopeUnread: Int?
    @State private var scopeReadRevision = 0
    @State private var readOwner: PulseReadSurfaces.Owner?
    @Environment(\.scenePhase) private var readScenePhase
    @State private var tailScrollRevision = 0
    @State private var cachedCursor: PulseCachedThreadCursor?
    @State private var hasEarlierCachedReplies = false
    @State private var showingCachedHistory = true
    @State private var loadingCachedReplies = false
    @State private var cachedPrependAnchor: String?
    @State private var cachedPrependRevision = 0

    @State private var errorText: String?
    @State private var failedReplies: [OutboxRow] = []
    @State private var recoveryObservation: AnyCancellable?
    @State private var discardReply: OutboxRow?
    // Wave 2 view-once - threads reuse the same gate/burn bubble; the reveal
    // opens the lightbox right here (the sheet owns its cover).
    @State private var lightbox: MediaLightboxTarget?
    @State private var lightboxMessageId: String?
    @State private var viewOnceTask: Task<Void, Never>?
    @FocusState private var composerFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var viewerId: String { session.viewer?.id ?? "" }
    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !rootUnavailable &&
        draftJournal?.isCurrentOwner == true && draftSession == session.sessionGeneration }

    private var readCandidate: [String] {
        guard phase == .loaded, !rootUnavailable, lightbox == nil, discardReply == nil,
              readScenePhase == .active, !session.threadAccessDenied(rootId: root.id),
              PulseRemediationLogic.threadRootAvailable(parent ?? root, roomId: conversation.id, rootId: root.id) else { return [] }
        return ([parent ?? root] + replies).filter {
            visibleReadIDs.contains($0.id) && $0.conversationId == conversation.id &&
                ($0.id == root.id || $0.parentId == root.id) && !$0.id.hasPrefix("local_") && $0.deletedAt == nil &&
                PulseRemediationLogic.isAlive($0.expiresAt)
        }.map(\.id)
    }

    private func updateReadBoundary() {
        guard let readOwner else { return }
        session.setVisibleReadBoundaries(owner: readOwner, messageIds: readCandidate)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    GeometryReader { viewport in
                        ScrollView {
                            LazyVStack(spacing: 6) {
                                threadBody
                                failedReplyRecovery
                                Color.clear.frame(height: 4).id("thread-tail")
                                    .background(GeometryReader { tail in
                                        Color.clear.preference(key: ThreadTailPositionKey.self,
                                            value: tail.frame(in: .named("thread-viewport")).maxY)
                                    })
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                        }
                        .coordinateSpace(name: "thread-viewport")
                        .onPreferenceChange(PulseRoomViewportKey.self) { layout in
                            visibleReadIDs = PulseReadViewport.visibleIDs(layout.messages, height: viewport.size.height)
                            updateReadBoundary()
                        }
                        .defaultScrollAnchor(.bottom)
                        .onPreferenceChange(ThreadTailPositionKey.self) { tailY in
                            nearTail = tailY >= 0 && tailY <= viewport.size.height + 80
                        }
                        .task(id: anchorCandidate?.id) {
                            guard let id = anchorCandidate?.id else { return }
                            await Task.yield() // Let the lazy reply row's stable ID enter the layout.
                            guard !Task.isCancelled, anchorCandidate?.id == id else { return }
                            proxy.scrollTo(id, anchor: .center)
                            highlightedReplyId = id
                            initialJumpResolved = true
                        }
                        .onChange(of: replies.last?.id) { _, _ in
                            guard PulseThreadNavigation.shouldFollowTail(nearTail: nearTail,
                                pendingTarget: initialJumpMessageId != nil && !initialJumpResolved) else { return }
                            withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion)) {
                                proxy.scrollTo("thread-tail", anchor: .bottom)
                            }
                        }
                        .task(id: cachedPrependRevision) {
                            guard let id = cachedPrependAnchor, !rootUnavailable else { return }
                            await Task.yield()
                            guard !Task.isCancelled, !rootUnavailable, replies.contains(where: { $0.id == id }) else { return }
                            proxy.scrollTo(id, anchor: .top)
                        }
                        .onChange(of: tailScrollRevision) { _, _ in proxy.scrollTo("thread-tail", anchor: .bottom) }
                    }
                }
                if let error = errorText {
                    errorStrip(error)
                }
                if rootUnavailable {
                    Text("This thread is no longer available. Your draft is kept.").font(.footnote).padding()
                } else { composer }
            }
            .background(PulseTheme.pageWash.ignoresSafeArea())
            .navigationTitle(scopeUnread.map { $0 > 0 ? "Thread · \($0) unread" : "Thread" } ?? "Thread")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .fullScreenCover(item: $lightbox) { target in
            MediaLightboxView(url: target.url, caption: target.caption, expiresAt: target.expiresAt, singleUse: target.singleUse)
        }
        .onAppear {
            readOwner = session.beginReadSurface(conversationId: conversation.id, rootId: root.id)
            updateReadBoundary()
        }
        .onChange(of: readCandidate, initial: true) { _, _ in updateReadBoundary() }
        .task(id: ThreadLoadKey(connected: session.connected, target: initialJumpMessageId)) { await load() }
        .onChange(of: initialJumpMessageId) { _, _ in
            initialJumpResolved = false
            highlightedReplyId = nil
        }
        .onReceive(session.signals) { handle(signal: $0) }
        .task(id: scopeReadRevision) {
            let epoch = session.sessionGeneration
            let count = try? await session.api.unreadCount(conversationId: conversation.id, threadId: root.id)
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            scopeUnread = count
        }
        .onReceive(session.messageVisibilityClock) { now in pruneExpired(now: now); updateReadBoundary() }
        .onReceive(session.outboxEvents) { event in
            switch event {
            case .delivered(let message, let room, let clientId):
                guard room == conversation.id else { return }
        scopeReadRevision &+= 1
                replies.removeAll { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }
                if message.parentId == root.id { appendReply(message) }
            case .dropped(let clientId, let room):
                guard room == conversation.id,
                      replies.contains(where: { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }) else { return }
                replies.removeAll { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }
                errorText = "Reply not sent. Your original reply is saved below for recovery."
            }
        }
        .task(id: highlightedReplyId) {
            guard highlightedReplyId != nil else { return }
            do { try await Task.sleep(nanoseconds: 1_700_000_000) } catch { return }
            highlightedReplyId = nil
        }
        .onChange(of: session.sessionGeneration) { _, _ in
            _ = prepareDraft()
            dismiss()
        }
        .alert("Discard this failed reply?", isPresented: Binding(get: { discardReply != nil }, set: { if !$0 { discardReply = nil } })) {
            Button("Cancel", role: .cancel) { discardReply = nil }
            Button("Discard", role: .destructive) {
                guard let row = discardReply, draftSession == session.sessionGeneration else { discardReply = nil; return }
                if !session.discardFailedThreadReply(clientId: row.clientId, conversationId: conversation.id, rootId: root.id) {
                    errorText = "The saved reply changed or couldn't be discarded. Nothing else was removed."
                }
                discardReply = nil
            }
        } message: { Text("This removes the saved outgoing reply from this device. It does not erase your current draft.") }
        .onDisappear {
            if let readOwner { session.endReadSurface(readOwner) }
            readOwner = nil
            visibleReadIDs = []
            viewOnceTask?.cancel()
            loadGeneration = UUID()
            recoveryObservation?.cancel()
            recoveryObservation = nil
        }
    }

    private var anchorCandidate: WireChatMessage? {
        guard !rootUnavailable, !initialJumpResolved, let id = initialJumpMessageId else { return nil }
        return PulseThreadNavigation.target(in: replies, roomId: conversation.id, rootId: root.id, targetId: id)
    }

    // content

    @ViewBuilder
    private var threadBody: some View {
        switch phase {
        case .loading:
            if replies.isEmpty {
                ProgressView("Loading thread…")
                    .padding(.top, 40)
            }
        case .failed(let message):
            VStack(spacing: 8) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(.secondary)
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Retry") { Task { await load() } }
                    .font(.footnote.weight(.semibold))
            }
            .padding(.top, 40)
            .frame(maxWidth: .infinity)
        case .loaded:
            // The parent card - plain bubble, no thread chip, no menus.
            let parentMessage = parent ?? root
            if !rootUnavailable {
                BubbleView(
                    message: parentMessage,
                    mine: parentMessage.senderId == viewerId,
                    groupChat: conversation.isGroup,
                    viewerColor: { colorOf(senderId: parentMessage.senderId) },
                    // Wave 8 - thread bubbles honor the prefs corner token too.
                    bubbleRadius: session.prefs?.bubbleRadius ?? .lg,
                    viewerId: viewerId,
                    onViewOnceOpen: { message in revealViewOnce(message) },
                    // R3-A item 7 - thread bubbles format + mention-chip too.
                    memberNames: conversation.members.map(\.name),
                )
                .padding(.bottom, 4)
                .background(GeometryReader { geometry in
                    Color.clear.preference(key: PulseRoomViewportKey.self,
                        value: PulseRoomViewport(messages: [parentMessage.id: geometry.frame(in: .named("thread-viewport"))]))
                })
            }
            if !rootUnavailable {
                if showingCachedHistory && hasEarlierCachedReplies {
                    Button(loadingCachedReplies ? "Loading saved replies…" : "Load earlier saved replies") {
                        loadEarlierCachedReplies(preserveAnchor: true)
                    }
                    .font(.footnote.weight(.semibold))
                    .disabled(loadingCachedReplies)
                    .padding(.vertical, 8)
                }
                ForEach(Array(replies.enumerated()), id: \.element.id) { index, reply in
                    // Day chip when the calendar day changes between rows - the
                    // first reply compares against the parent's day.
                    let previous = index == 0 ? parentMessage : replies[index - 1]
                    if PulseFormat.dayLabel(previous.createdAt) != PulseFormat.dayLabel(reply.createdAt) {
                        CapsuleLabel(PulseFormat.dayLabel(reply.createdAt))
                            .padding(.vertical, 4)
                    }
                    BubbleView(
                        message: reply,
                        mine: reply.senderId == viewerId,
                        groupChat: conversation.isGroup,
                        viewerColor: { colorOf(senderId: reply.senderId) },
                        // Wave 8 - prefs corner token (session handoff).
                        bubbleRadius: session.prefs?.bubbleRadius ?? .lg,
                        viewerId: viewerId,
                        onViewOnceOpen: { message in revealViewOnce(message) },
                        // R3-A item 7 - thread bubbles format + mention-chip too.
                        memberNames: conversation.members.map(\.name),
                    )
                    .id(reply.id)
                    .background(GeometryReader { geometry in
                        Color.clear.preference(key: PulseRoomViewportKey.self,
                            value: PulseRoomViewport(messages: [reply.id: geometry.frame(in: .named("thread-viewport"))]))
                    })
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(
                        highlightedReplyId == reply.id ? PulseTheme.color(named: "orange") : Color.clear, lineWidth: 2))
                }
            }
        }
    }

    private func revealViewOnce(_ message: WireChatMessage) {
        guard !rootUnavailable, message.conversationId == conversation.id,
              message.id == root.id || message.parentId == root.id else { return }
        guard viewOnceTask == nil, lightbox == nil else { return }
        viewOnceTask = Task {
            defer { viewOnceTask = nil }
            guard let granted = await session.consumeViewOnce(message), !Task.isCancelled, !rootUnavailable,
                  let url = PulseMediaOpener.url(for: granted) else { return }
            lightboxMessageId = granted.id
            lightbox = MediaLightboxTarget(url: url, caption: granted.content, expiresAt: granted.expiresAt, singleUse: true)
            if granted.id == root.id { parent = granted } else { appendReply(granted) }
        }
    }

    private func colorOf(senderId: String) -> Color {
        let member = conversation.members.first { $0.id == senderId }
        return PulseTheme.color(named: member?.color)
    }

    // data

    private func load() async {
        guard prepareDraft() else { return }
        observeRecovery()
        let ticket = UUID()
        loadGeneration = ticket
        let epoch = session.sessionGeneration
        let store = session.store
        rootUnavailable = session.threadAccessDenied(rootId: root.id)
        // The sheet's input is a snapshot; a cached tombstone/edit is newer.
        if let cachedRoot = try? store?.message(id: root.id, now: .distantPast) { parent = cachedRoot }
        if let page = try? store?.cachedThreadPage(roomId: conversation.id, rootId: root.id, limit: 300) {
            replies = PulseTextDelivery.timeline(replies, pending: page.messages)
            // Reconnect refresh must not rewind pages already expanded offline.
            if cachedCursor == nil {
                cachedCursor = page.cursor
                hasEarlierCachedReplies = page.hasMore
            }
        }
        phase = .loaded
        pruneExpired()
        guard PulseRemediationLogic.threadRootAvailable(parent ?? root, roomId: conversation.id, rootId: root.id) else { return }
        let revision = session.messageRevision
        do {
            let page = try await session.validatedThread(rootId: root.id, roomId: conversation.id)
            guard !Task.isCancelled, loadGeneration == ticket, session.sessionGeneration == epoch else { return }
            // A slower HTTP snapshot must not undo a newer edit/deletion relay.
            let eligible = ([page.parent] + page.replies).filter { session.acceptsSyncMessage($0.id, startedAt: revision) }
            let fresh = try store?.mergeMessages(eligible) ?? eligible
            parent = fresh.first(where: { $0.id == root.id }) ?? (try? store?.message(id: root.id, now: .distantPast)) ?? parent
            guard PulseRemediationLogic.threadRootAvailable(parent ?? root, roomId: conversation.id, rootId: root.id) else {
                pruneExpired()
                return
            }
            replies = PulseTextDelivery.timeline(replies, pending: fresh.filter { $0.parentId == root.id })
            phase = .loaded
            errorText = nil
            showingCachedHistory = false // current wire endpoint returns the complete thread
            rootUnavailable = false
            pruneExpired()
            if let target = initialJumpMessageId, !initialJumpResolved,
               PulseThreadNavigation.target(in: replies, roomId: conversation.id, rootId: root.id, targetId: target) == nil {
                errorText = "That reply is no longer available in this thread."
                initialJumpResolved = true
            }
        } catch {
            guard !Task.isCancelled, loadGeneration == ticket, session.sessionGeneration == epoch else { return }
            if let failure = error as? PulseAPIClient.Failure, [403, 404, 410].contains(failure.status ?? 0) {
                rootUnavailable = true
                replies = []
                cachedCursor = nil
                hasEarlierCachedReplies = false
                lightbox = nil
            }
            phase = .loaded
            showingCachedHistory = true
            errorText = RoomViewModel.describe(error)
            // A cached deep-link target is not "missing" just because it fell
            // outside the first window. Yield between pages and honor cancellation.
            while !rootUnavailable, let target = initialJumpMessageId, !initialJumpResolved,
                  !replies.contains(where: { $0.id == target }), hasEarlierCachedReplies {
                guard !Task.isCancelled, loadGeneration == ticket, session.sessionGeneration == epoch else { return }
                guard loadEarlierCachedReplies(preserveAnchor: false) else { break }
                await Task.yield()
            }
            guard !Task.isCancelled, loadGeneration == ticket, session.sessionGeneration == epoch else { return }
            if !rootUnavailable, let target = initialJumpMessageId, !initialJumpResolved,
               PulseThreadNavigation.target(in: replies, roomId: conversation.id, rootId: root.id, targetId: target) == nil {
                if replies.contains(where: { $0.id == target && $0.deletedAt != nil }) {
                    errorText = "That reply was deleted."
                    initialJumpResolved = true
                } else if !hasEarlierCachedReplies {
                    // Cache absence is not proof of deletion. Keep the jump pending for reconnect.
                    errorText = "That reply isn't saved offline. Reconnect to load it."
                }
            }
        }
    }

    @discardableResult
    private func loadEarlierCachedReplies(preserveAnchor: Bool) -> Bool {
        guard !loadingCachedReplies, !rootUnavailable, hasEarlierCachedReplies,
              draftSession == session.sessionGeneration, let cursor = cachedCursor,
              let store = session.store, !session.threadAccessDenied(rootId: root.id) else { return false }
        loadingCachedReplies = true
        defer { loadingCachedReplies = false }
        do {
            let page = try store.cachedThreadPage(roomId: conversation.id, rootId: root.id, before: cursor)
            let anchor = replies.first?.id
            replies = PulseTextDelivery.timeline(replies, pending: page.messages)
            cachedCursor = page.cursor ?? cursor
            hasEarlierCachedReplies = page.hasMore
            if preserveAnchor, let anchor {
                cachedPrependAnchor = anchor
                cachedPrependRevision += 1
            }
            return true
        } catch {
            errorText = "Couldn't read earlier saved replies. They have not been removed. Try again."
            return false
        }
    }

    private func pruneExpired(now: Date = Date()) {
        if let cachedRoot = try? session.store?.message(id: root.id, now: .distantPast) { parent = cachedRoot }
        let parentMessage = parent ?? root
        if session.threadAccessDenied(rootId: root.id) || !PulseRemediationLogic.threadRootAvailable(parentMessage, roomId: conversation.id, rootId: root.id, now: now) {
            rootUnavailable = true
            replies = []
            cachedCursor = nil
            hasEarlierCachedReplies = false
            lightbox = nil
        } else {
            if let selected = lightboxMessageId {
                let current = selected == parentMessage.id ? parentMessage : replies.first(where: { $0.id == selected })
                if current == nil || current?.deletedAt != nil || !PulseRemediationLogic.isAlive(current?.expiresAt, now: now) {
                    lightbox = nil
                    lightboxMessageId = nil
                }
            }
            let visible = replies.filter { PulseRemediationLogic.isAlive($0.expiresAt, now: now) }
            if visible.count != replies.count {
                replies = visible
                lightbox = nil
            }
        }
    }

    private func handle(signal: PulseSocketClient.Signal) {
        let room: String
        let message: WireChatMessage
        switch signal {
        case .messageRead(let id, _, _, _, _, _):
            if id == conversation.id { scopeReadRevision &+= 1 }
            return
        case .connectionState:
            scopeReadRevision &+= 1
            return
        case .messageNew(let id, let raw), .messageReact(let id, let raw), .messageEnvelope(_, let id, let raw):
            guard let decoded = PulseSession.decodeMessage(from: raw) else { return }
            room = id; message = decoded
        case .messageDeleted(let id, let raw):
            guard let decoded = PulseSession.decodeMessage(from: raw) else { return }
            room = id; message = decoded.deletedCopy()
        default: return
        }
        guard room == conversation.id else { return }
        let cached = try? session.store?.message(id: message.id, now: .distantPast)
        let canonical = PulseMessageMerge.merge(cached, incoming: message)
        if canonical.id == root.id { parent = PulseMessageMerge.merge(parent, incoming: canonical) }
        else if canonical.parentId == root.id { appendReply(canonical) }
        // An unrelated source can still be quoted INSIDE this thread.
        let local = PulseQuotePrivacy.project([parent ?? root] + replies, known: [canonical])
        let projected = (try? session.store?.projectCachedQuotes(local)) ?? local
        parent = projected.first
        replies = Array(projected.dropFirst())
        pruneExpired()
    }

    private func appendReply(_ message: WireChatMessage) {
        guard !rootUnavailable, message.parentId == root.id else { return }
        replies = PulseTextDelivery.timeline(replies, pending: [message])
    }

    private var retryAllowed: Bool {
        session.ready && draftSession == session.sessionGeneration && !rootUnavailable &&
            PulseRemediationLogic.threadRootAvailable(parent ?? root, roomId: conversation.id, rootId: root.id)
    }

    @ViewBuilder private var failedReplyRecovery: some View {
        if !failedReplies.isEmpty {
            Text("NOT SENT · SAVED ON THIS DEVICE").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(failedReplies, id: \.clientId) { row in
                VStack(alignment: .leading, spacing: 6) {
                    Text(row.content.isEmpty ? "Saved attachment" : row.content).font(.footnote).lineLimit(3)
                    Text(row.failureReason ?? "Delivery wasn't confirmed").font(.caption).foregroundStyle(.secondary)
                    HStack {
                        Button("Retry original") {
                            guard retryAllowed else { return }
                            if session.retryOutbox(clientId: row.clientId, conversationId: conversation.id, threadRootId: root.id) {
                                if let echo = try? session.store?.message(id: PulseOutboxEngine.tempMessageId(clientId: row.clientId)) { appendReply(echo) }
                            } else { errorText = "Couldn't retry this saved reply. It has not been discarded." }
                        }.disabled(!retryAllowed)
                        Button("Copy text") {
                            guard draftSession == session.sessionGeneration else { return }
                            UIPasteboard.general.string = row.content
                        }.disabled(row.content.isEmpty)
                        Button("Discard", role: .destructive) { discardReply = row }
                    }.font(.caption.weight(.semibold))
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                .background(RoundedRectangle(cornerRadius: 12).fill(.regularMaterial))
                .id("failed-" + row.clientId)
            }
        }
    }

    private func observeRecovery() {
        guard recoveryObservation == nil, let store = session.store else { return }
        let epoch = session.sessionGeneration
        recoveryObservation = store.observeFailedThreadReplies(conversationId: conversation.id, rootId: root.id)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { completion in
                guard epoch == session.sessionGeneration else { return }
                if case .failure = completion { errorText = "Couldn't read saved replies. Reopen this thread to try again." }
            }, receiveValue: { rows in
                guard epoch == session.sessionGeneration else { return }
                failedReplies = rows
            })
    }

    private func prepareDraft() -> Bool {
        if let epoch = draftSession, epoch != session.sessionGeneration {
            draft = ""
            replies = []
            failedReplies = []
            discardReply = nil
            recoveryObservation?.cancel()
            recoveryObservation = nil
            rootUnavailable = true
            return false
        }
        guard draftJournal == nil else { return true }
        guard let store = session.store else { return false }
        let journal = PulseDraftJournal(store: store, conversationId: conversation.id, parentId: root.id)
        draftSession = session.sessionGeneration
        draftJournal = journal
        draft = journal.snapshot ?? ""
        return true
    }

    private func editDraft(_ text: String) {
        guard draftSession == session.sessionGeneration, let journal = draftJournal, journal.isCurrentOwner else { return }
        draft = text
        draftRevision &+= 1
        let revision = draftRevision, epoch = session.sessionGeneration
        let write = journal.submit(text, revision: revision)
        Task {
            do { try await write.value }
            catch {
                guard epoch == session.sessionGeneration, draftRevision == revision else { return }
                errorText = "Couldn't save this draft on the device. Keep this thread open and try again."
            }
        }
    }

    // composer

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Reply in thread", text: Binding(get: { draft }, set: editDraft), axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Capsule().fill(Color(.secondarySystemBackground)))
                .focused($composerFocused)
                .disabled(draftJournal == nil || draftSession != session.sessionGeneration)

            Button {
                sendReply()
                PulseHaptics.tap()
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(canSend ? AnyShapeStyle(PulseTheme.gradient(named: "emerald")) : AnyShapeStyle(Color.secondary.opacity(0.4)))
            }
            .buttonStyle(PulseButtonStyle())
            .disabled(!canSend)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    /// Commit locally before clearing the composer. Recovery always reuses
    /// the persisted clientId; a lost response never creates a replacement send.
    private func sendReply() {
        pruneExpired()
        guard canSend, let viewer = session.viewer, let store = session.store, session.ready else { return }
        do {
            let message = try PulseTextDelivery.enqueue(store: store, viewer: viewer,
                conversationId: conversation.id, content: draft, parentId: root.id, draftJournal: draftJournal)
            appendReply(message)
            initialJumpResolved = true // Sending intentionally returns to the new reply.
            tailScrollRevision += 1
            draft = ""
            errorText = nil
            session.outbox?.reloadPending()
            session.flushOutbox()
        } catch { errorText = RoomViewModel.describe(error) }
    }

    private func errorStrip(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(text).font(.footnote).lineLimit(2)
            Spacer()
            Button("Retry") { Task { await load() } }
                .font(.footnote.weight(.semibold))
            Button("Dismiss") { errorText = nil }
                .font(.footnote.weight(.semibold))
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(.regularMaterial))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }
}


private struct ThreadTailPositionKey: PreferenceKey {
    static let defaultValue: CGFloat = .infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}


private struct ThreadLoadKey: Hashable {
    let connected: Bool
    let target: String?
}
