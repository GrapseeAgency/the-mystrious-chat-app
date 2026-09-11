import SwiftUI

/// Wave 1 thread surface — the web ThreadSheet outcome natively: parent card
/// on top, replies asc below, composer that sends `parentId` (spec §1.1 —
/// the thread-ROOT id, NEVER the inline-quote replyToId). Optimistic temp
/// rows swap on send exactly like the river; realtime appends arrive via the
/// session signal stream; every landed row persists through the GRDB cache.
/// Thread replies are NEVER queued offline (spec §2 row 5 — web parity).
struct ThreadView: View {
    let conversation: WireConversationSummary
    let root: WireChatMessage
    @ObservedObject var session: PulseSession

    enum Phase: Equatable { case loading, loaded, failed(String) }

    @State private var parent: WireChatMessage?
    @State private var replies: [WireChatMessage] = []
    @State private var phase: Phase = .loading
    @State private var draft = ""
    @State private var sending = false
    @State private var errorText: String?
    @FocusState private var composerFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var viewerId: String { session.viewer?.id ?? "" }
    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespaces).isEmpty && !sending }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            threadBody
                            Color.clear.frame(height: 4).id("thread-tail")
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                    }
                    .defaultScrollAnchor(.bottom)
                    .onChange(of: replies.count) { _, _ in
                        withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion)) {
                            proxy.scrollTo("thread-tail", anchor: .bottom)
                        }
                    }
                }
                if let error = errorText {
                    errorStrip(error)
                }
                composer
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
        .task { await load() }
        .onReceive(session.signals) { handle(signal: $0) }
    }

    // ── content ──────────────────────────────────────────────

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
            // The parent card — plain bubble, no thread chip, no menus.
            let parentMessage = parent ?? root
            BubbleView(
                message: parentMessage,
                mine: parentMessage.senderId == viewerId,
                groupChat: conversation.isGroup,
                viewerColor: { colorOf(senderId: parentMessage.senderId) },
            )
            .padding(.bottom, 4)
            ForEach(Array(replies.enumerated()), id: \.element.id) { index, reply in
                // Day chip when the calendar day changes between rows — the
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
                )
            }
        }
    }

    private func colorOf(senderId: String) -> Color {
        let member = conversation.members.first { $0.id == senderId }
        return PulseTheme.color(named: member?.color)
    }

    // ── data ─────────────────────────────────────────────────

    private func load() async {
        // Cache seed first — the sheet paints instantly, offline too.
        if let store = session.store {
            let cached = (try? store.messages(threadRootId: root.id)) ?? []
            if !cached.isEmpty {
                replies = cached
                phase = .loaded
            }
        }
        do {
            let page = try await session.api.thread(rootId: root.id, userId: viewerId)
            parent = page.parent
            replies = page.replies
            phase = .loaded
            errorText = nil
            try? session.store?.upsert(messages: [page.parent] + page.replies)
        } catch {
            if replies.isEmpty {
                phase = .failed(RoomViewModel.describe(error))
            } else {
                phase = .loaded
                errorText = RoomViewModel.describe(error)
            }
        }
    }

    /// Realtime — a relayed reply for THIS thread appends (id-dedupe) and
    /// lands in the GRDB cache; everything else is ignored here.
    private func handle(signal: PulseSocketClient.Signal) {
        switch signal {
        case .messageNew(let conversationId, let raw):
            guard conversationId == conversation.id,
                  let message = PulseSession.decodeMessage(from: raw),
                  message.parentId == root.id else { return }
            appendReply(message)
            try? session.store?.upsert(messages: [message])
        default:
            break
        }
    }

    private func appendReply(_ message: WireChatMessage) {
        guard !replies.contains(where: { $0.id == message.id }) else { return }
        replies.append(message)
        replies.sort {
            (PulseFormat.date($0.createdAt) ?? .distantPast) < (PulseFormat.date($1.createdAt) ?? .distantPast)
        }
    }

    // ── composer ─────────────────────────────────────────────

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Reply in thread", text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Capsule().fill(Color(.secondarySystemBackground)))
                .focused($composerFocused)

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

    /// Optimistic thread send — temp `local_<clientId>` row swaps for the
    /// real one on success. Any failure removes the temp and surfaces an
    /// honest error; the outbox NEVER queues thread replies (spec §2 row 5).
    private func sendReply() {
        guard let viewer = session.viewer else {
            errorText = "The gateway is unreachable."
            return
        }
        let body = draft.trimmingCharacters(in: .whitespaces)
        guard !body.isEmpty, !sending else { return }
        draft = ""
        let clientId = UUID().uuidString
        let temp = TempMessages.make(
            conversationId: conversation.id,
            viewer: viewer,
            clientId: clientId,
            content: body,
            parentId: root.id
        )
        appendReply(temp)
        sending = true
        Task {
            defer { sending = false }
            do {
                let real = try await session.api.sendMessage(
                    conversationId: conversation.id,
                    content: body,
                    parentId: root.id,
                )
                replies.removeAll {
                    $0.id == temp.id
                        || ($0.id.hasPrefix("local_") && $0.content == real.content && $0.senderId == real.senderId)
                }
                appendReply(real)
                try? session.store?.upsert(messages: [real])
                try? session.store?.deleteMessage(id: temp.id)
                session.particles.fire(kind: .burst, count: 18)
            } catch {
                replies.removeAll { $0.id == temp.id }
                try? session.store?.deleteMessage(id: temp.id)
                errorText = RoomViewModel.describe(error)
            }
        }
    }

    private func errorStrip(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(text).font(.footnote).lineLimit(2)
            Spacer()
            Button("Dismiss") { errorText = nil }
                .font(.footnote.weight(.semibold))
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(.regularMaterial))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }
}
