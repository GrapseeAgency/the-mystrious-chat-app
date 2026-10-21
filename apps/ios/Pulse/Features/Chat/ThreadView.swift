import SwiftUI

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
    @State private var tailScrollRevision = 0

    @State private var errorText: String?
    // Wave 2 view-once - threads reuse the same gate/burn bubble; the reveal
    // opens the lightbox right here (the sheet owns its cover).
    @State private var lightbox: MediaLightboxTarget?
    @State private var lightboxMessageId: String?
    @FocusState private var composerFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var viewerId: String { session.viewer?.id ?? "" }
    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !rootUnavailable &&
        draftJournal?.isCurrentOwner == true && draftSession == session.sessionGeneration }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    GeometryReader { viewport in
                        ScrollView {
                            LazyVStack(spacing: 6) {
                                threadBody
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
            .navigationTitle("Thread")
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
            MediaLightboxView(url: target.url, caption: target.caption, expiresAt: target.expiresAt)
        }
        .task(id: ThreadLoadKey(connected: session.connected, target: initialJumpMessageId)) { await load() }
        .onChange(of: initialJumpMessageId) { _, _ in
            initialJumpResolved = false
            highlightedReplyId = nil
        }
        .onReceive(session.signals) { handle(signal: $0) }
        .onReceive(session.messageVisibilityClock) { now in pruneExpired(now: now) }
        .onReceive(session.outboxEvents) { event in
            switch event {
            case .delivered(let message, let room, let clientId):
                guard room == conversation.id else { return }
                replies.removeAll { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }
                if message.parentId == root.id { appendReply(message) }
            case .dropped(let clientId, let room):
                guard room == conversation.id,
                      replies.contains(where: { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }) else { return }
                replies.removeAll { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }
                errorText = "Reply not sent. It is kept in Outbox for recovery."
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
        .onDisappear { loadGeneration = UUID() }
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
            }
            if !rootUnavailable {
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
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(
                        highlightedReplyId == reply.id ? PulseTheme.color(named: "orange") : Color.clear, lineWidth: 2))
                }
            }
        }
    }

    /// Wave 2 view-once in threads - instant reveal (web parity), then the
    /// idempotent burn stamp; the relayed envelope reconciles the rest.
    private func revealViewOnce(_ message: WireChatMessage) {
        guard !rootUnavailable, message.deletedAt == nil, PulseRemediationLogic.isAlive(message.expiresAt),
              message.conversationId == conversation.id, message.id == root.id || message.parentId == root.id,
              message.viewOnce == true,
              message.senderId != viewerId,
              message.viewedAt == nil,
              let url = PulseMediaOpener.url(for: message) else { return }
        lightboxMessageId = message.id
        lightbox = MediaLightboxTarget(url: url, caption: message.content, expiresAt: message.expiresAt)
        guard let viewer = session.viewer else { return }
        let api = session.api
        let store = session.store
        let epoch = session.sessionGeneration
        Task {
            if let fresh = try? await api.markViewed(messageId: message.id, userId: viewer.id) {
                guard session.sessionGeneration == epoch, !Task.isCancelled else { return }
                try? store?.upsert(messages: [fresh])
                if let index = replies.firstIndex(where: { $0.id == fresh.id }) {
                    replies[index] = fresh
                }
                if parent?.id == fresh.id {
                    parent = fresh
                }
            }
        }
    }

    private func colorOf(senderId: String) -> Color {
        let member = conversation.members.first { $0.id == senderId }
        return PulseTheme.color(named: member?.color)
    }

    // data

    private func load() async {
        guard prepareDraft() else { return }
        let ticket = UUID()
        loadGeneration = ticket
        let epoch = session.sessionGeneration
        let api = session.api
        let store = session.store
        // The sheet's input is a snapshot; a cached tombstone/edit is newer.
        if let cachedRoot = try? store?.message(id: root.id, now: .distantPast) { parent = cachedRoot }
        if let cached = try? store?.messages(threadRootId: root.id) {
            replies = PulseTextDelivery.timeline(replies, pending: cached)
        }
        phase = .loaded
        pruneExpired()
        guard PulseRemediationLogic.threadRootAvailable(parent ?? root, roomId: conversation.id, rootId: root.id) else { return }
        let revision = session.messageRevision
        do {
            let page = try await api.thread(rootId: root.id, userId: viewerId)
            guard !Task.isCancelled, loadGeneration == ticket, session.sessionGeneration == epoch else { return }
            // A slower HTTP snapshot must not undo a newer edit/deletion relay.
            let fresh = ([page.parent] + page.replies).filter { session.acceptsSyncMessage($0.id, startedAt: revision) }
            try store?.upsert(messages: fresh)
            parent = fresh.first(where: { $0.id == root.id }) ?? (try? store?.message(id: root.id, now: .distantPast)) ?? parent
            guard PulseRemediationLogic.threadRootAvailable(parent ?? root, roomId: conversation.id, rootId: root.id) else {
                pruneExpired()
                return
            }
            replies = PulseTextDelivery.timeline(replies, pending: fresh.filter { $0.parentId == root.id })
            phase = .loaded
            errorText = nil
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
                lightbox = nil
            }
            phase = .loaded
            errorText = RoomViewModel.describe(error)
        }
    }

    private func pruneExpired(now: Date = Date()) {
        if let cachedRoot = try? session.store?.message(id: root.id, now: .distantPast) { parent = cachedRoot }
        let parentMessage = parent ?? root
        if !PulseRemediationLogic.threadRootAvailable(parentMessage, roomId: conversation.id, rootId: root.id, now: now) {
            rootUnavailable = true
            replies = []
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
        case .messageNew(let id, let raw), .messageReact(let id, let raw), .messageEnvelope(_, let id, let raw):
            guard let decoded = PulseSession.decodeMessage(from: raw) else { return }
            room = id; message = decoded
        case .messageDeleted(let id, let raw):
            guard let decoded = PulseSession.decodeMessage(from: raw) else { return }
            room = id; message = decoded.deletedCopy()
        default: return
        }
        guard room == conversation.id, message.id == root.id || message.parentId == root.id else { return }
        if message.id == root.id { parent = message }
        else { appendReply(message) }
        try? session.store?.upsert(messages: [message])
        pruneExpired()
    }

    private func appendReply(_ message: WireChatMessage) {
        guard !rootUnavailable, message.parentId == root.id else { return }
        replies = PulseTextDelivery.timeline(replies, pending: [message])
    }

    private func prepareDraft() -> Bool {
        if let epoch = draftSession, epoch != session.sessionGeneration {
            draft = ""
            replies = []
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
