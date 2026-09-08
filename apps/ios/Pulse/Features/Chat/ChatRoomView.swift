import SwiftUI
import UIKit
import Combine

/// Chat room — native rebuild of the web conversation surface. Same outcome:
/// emerald gradient bubbles for the viewer, material bubbles for others,
/// reply quotes, grouped reaction chips, voice waveform chips, read state,
/// live typing with drifting dots. Apple mechanics: ScrollViewReader anchor,
/// contextMenu reactions, FocusState composer, socket-driven updates.
struct ChatRoomView: View {
    let conversation: WireConversationSummary
    @ObservedObject var session: PulseSession

    @State private var viewModel: RoomViewModel?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let viewModel {
                RoomContent(
                    conversation: conversation,
                    session: session,
                    viewModel: viewModel,
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(roomTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .onAppear {
            if viewModel == nil {
                viewModel = RoomViewModel(conversation: conversation, session: session)
            }
        }
    }

    private var roomTitle: String {
        conversation.name
            ?? conversation.members.first(where: { $0.id != session.viewer?.id })?.name
            ?? conversation.members.first?.name
            ?? "Conversation"
    }
}

private struct RoomContent: View {
    let conversation: WireConversationSummary
    @ObservedObject var session: PulseSession
    @ObservedObject var viewModel: RoomViewModel

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var composerFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            messagesList
            if let error = viewModel.errorText {
                errorStrip(error)
            }
            composer
        }
    }

    // ── messages ─────────────────────────────────────────────
    private var messagesList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    if viewModel.messages.isEmpty && viewModel.phase == .loading {
                        ProgressView("Loading messages…")
                            .padding(.top, 40)
                    }
                    ForEach(Array(viewModel.messages.enumerated()), id: \.element.id) { index, message in
                        // Day chip when the calendar day changes between rows.
                        if index == 0 || PulseFormat.dayLabel(viewModel.messages[index - 1].createdAt) != PulseFormat.dayLabel(message.createdAt) {
                            CapsuleLabel(PulseFormat.dayLabel(message.createdAt))
                                .padding(.vertical, 4)
                        }
                        BubbleView(
                            message: message,
                            mine: message.senderId == session.viewer?.id,
                            groupChat: conversation.isGroup,
                            viewerColor: { colorOf(senderId: message.senderId) },
                            seen: viewModel.isSeen(message),
                            onLongPressActions: true,
                        )
                        .contextMenu { contextMenu(for: message) }
                    }
                    if !viewModel.typers.isEmpty {
                        HStack(spacing: 6) {
                            TypingDotsView()
                            Text(typersText)
                                .font(.footnote)
                                .foregroundStyle(PulseTheme.emerald)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                    }
                    Color.clear.frame(height: 4).id("tail")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: viewModel.messages.count) { _, _ in
                withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion)) {
                    proxy.scrollTo("tail", anchor: .bottom)
                }
            }
            .onAppear {
                proxy.scrollTo("tail", anchor: .bottom)
            }
        }
    }

    @ViewBuilder
    private func contextMenu(for message: WireChatMessage) -> some View {
        ForEach(ReactionPalette.emojis, id: \.self) { emoji in
            Button {
                viewModel.react(message, emoji: emoji, session: session)
            } label: {
                Text(emoji)
            }
        }
        Button {
            viewModel.beginReply(to: message)
        } label: {
            Label("Reply", systemImage: "arrowshape.turn.up.left.fill")
        }
        Button {
            UIPasteboard.general.string = message.content
        } label: {
            Label("Copy", systemImage: "doc.on.doc.fill")
        }
    }

    private var typersText: String {
        let names = viewModel.typers.map(\.userName).filter { !$0.isEmpty }
        if names.isEmpty { return "typing…" }
        if names.count == 1 { return "\(names[0]) is typing" }
        return "\(names.count) people are typing"
    }

    private func colorOf(senderId: String) -> Color {
        let member = conversation.members.first { $0.id == senderId }
        return PulseTheme.color(named: member?.color)
    }

    private func errorStrip(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(text).font(.footnote).lineLimit(2)
            Spacer()
            Button("Retry") { Task { await viewModel.retry(session: session) } }
                .font(.footnote.weight(.semibold))
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(.regularMaterial))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    // ── composer ─────────────────────────────────────────────
    private var composer: some View {
        VStack(spacing: 0) {
            if let reply = viewModel.replyTarget {
                replyBar(reply)
            }
            HStack(alignment: .bottom, spacing: 10) {
                Button {
                    // Attachments land in the next native wave (photo/voice/file).
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(PulseButtonStyle())

                TextField("Message", text: $viewModel.draft, axis: .vertical)
                    .lineLimit(1...5)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(Capsule().fill(Color(.secondarySystemBackground)))
                    .focused($composerFocused)
                    .onChange(of: viewModel.draft) { _, _ in viewModel.draftChanged(session: session) }

                Button {
                    viewModel.send(session: session)
                    PulseHaptics.tap()
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(viewModel.canSend ? AnyShapeStyle(PulseTheme.gradient(named: "emerald")) : AnyShapeStyle(Color.secondary.opacity(0.4)))
                }
                .buttonStyle(PulseButtonStyle())
                .disabled(!viewModel.canSend)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
        }
    }

    private func replyBar(_ reply: WireChatMessage) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "arrowshape.turn.up.left.fill")
                .font(.footnote)
                .foregroundStyle(PulseTheme.emerald)
            VStack(alignment: .leading, spacing: 1) {
                Text(reply.sender?.name ?? "Reply")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(PulseTheme.emerald)
                Text(reply.content)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                viewModel.cancelReply()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(.thinMaterial)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

/// One message bubble — the web outcome with an iOS accent: asymmetric
/// corner radius, gradient fill for the viewer, quote block, reaction chips,
/// voice/image/file/system renderings, "Seen" under the viewer's tail.
struct BubbleView: View {
    let message: WireChatMessage
    let mine: Bool
    let groupChat: Bool
    let viewerColor: () -> Color
    let seen: Bool
    var onLongPressActions: Bool = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isDeleted: Bool { message.deletedAt != nil }
    private var isSystem: Bool { message.kind == "system" }

    private var bubbleShape: UnevenRoundedRectangle {
        mine
            ? UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 18, bottomTrailingRadius: 6, topTrailingRadius: 18)
            : UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 6, bottomTrailingRadius: 18, topTrailingRadius: 18)
    }

    var body: some View {
        VStack(alignment: mine ? .trailing : .leading, spacing: 2) {
            if isSystem {
                CapsuleLabel(message.content)
                    .padding(.vertical, 4)
            } else {
                if groupChat && !mine, let sender = message.sender {
                    HStack(spacing: 5) {
                        Circle().fill(PulseTheme.color(named: sender.color)).frame(width: 6, height: 6)
                        Text(sender.name)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 4)
                }

                HStack(alignment: .bottom, spacing: 4) {
                    if mine { Spacer(minLength: 44) }
                    bubble
                    if !mine { Spacer(minLength: 44) }
                }

                if mine && seen {
                    Text("Seen")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(PulseTheme.emerald)
                        .padding(.trailing, 6)
                }
            }
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let reply = message.replyTo {
                replyQuote(reply)
            }

            if isDeleted {
                Label("Message deleted", systemImage: "trash.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                content
            }

            HStack(spacing: 5) {
                Text(PulseFormat.clockTime(message.createdAt))
                    .font(.caption2)
                    .foregroundStyle(mine ? Color.white.opacity(0.8) : .secondary)
                if message.pinnedAt != nil {
                    Image(systemName: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(mine ? Color.white.opacity(0.85) : PulseTheme.amber)
                }
            }
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 9)
        .background(bubbleShape.fill(bubbleFill))
        .overlay(reactionChips, alignment: .bottom)
        .padding(.bottom, reactionChipsHeight())
    }

    @ViewBuilder
    private var content: some View {
        switch message.kind {
        case "image":
            Label("Photo", systemImage: "photo.fill")
                .font(.subheadline)
        case "voice":
            voiceChip
        case "file":
            Label(message.fileName ?? "File", systemImage: "paperclip.fill")
                .font(.subheadline)
        case "video":
            Label("Video", systemImage: "video.fill")
                .font(.subheadline)
        default:
            Text(message.content)
                .font(.body)
                .textSelection(.enabled)
        }
    }

    private var voiceChip: some View {
        HStack(spacing: 6) {
            Image(systemName: "waveform")
                .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
            // Deterministic waveform bars from the message id (native mirror
            // of the web voice bubble, no emoji chrome).
            HStack(spacing: 2) {
                ForEach(0..<14, id: \.self) { index in
                    let seed = abs(message.id.hashValue)
                    let height = CGFloat(5 + (seed * (index + 7)) % 15)
                    Capsule()
                        .fill(mine ? Color.white.opacity(0.85) : PulseTheme.emerald.opacity(0.7))
                        .frame(width: 2.5, height: height)
                }
            }
            if let duration = PulseFormat.duration(message.durationMs), !duration.isEmpty {
                Text(duration)
                    .font(.caption2)
                    .foregroundStyle(mine ? Color.white.opacity(0.85) : .secondary)
            }
        }
    }

    private var bubbleFill: some ShapeStyle {
        if mine {
            return AnyShapeStyle(LinearGradient(
                colors: [PulseTheme.emerald, PulseTheme.emeraldDeep],
                startPoint: .topLeading, endPoint: .bottomTrailing,
            ))
        }
        return AnyShapeStyle(Color(.secondarySystemBackground))
    }

    @ViewBuilder
    private func replyQuote(_ reply: WireReplySnippet) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(reply.senderName)
                .font(.caption.weight(.semibold))
                .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
            Text(reply.deleted == true ? "Deleted message" : reply.content)
                .font(.caption)
                .foregroundStyle(mine ? Color.white.opacity(0.85) : .secondary)
                .lineLimit(2)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 9).fill(mine ? Color.white.opacity(0.16) : Color.primary.opacity(0.05)))
    }

    private var reactions: [WireReactionGroup] { message.reactions ?? [] }

    private var reactionChips: some View {
        HStack(spacing: 4) {
            ForEach(reactions, id: \.emoji) { group in
                HStack(spacing: 3) {
                    Text(group.emoji).font(.caption)
                    if group.count > 1 {
                        Text("\(group.count)")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(Capsule().fill(.thinMaterial))
            }
        }
        .offset(y: 12)
    }

    private func reactionChipsHeight() -> CGFloat {
        reactions.isEmpty ? 0 : 14
    }
}

enum ReactionPalette {
    static let emojis = ["👍", "❤️", "😂", "😮", "😢", "🎉"]
}

/// Room state holder — transport lives in PulseSession; this owns messages,
/// drafts, replies, receipts and the live signal subscriptions.
@MainActor
final class RoomViewModel: ObservableObject {
    enum Phase: Equatable { case idle, loading, loaded, failed(String) }

    struct Typer: Equatable {
        let userId: String
        let userName: String
        let expiresAt: Date
    }

    @Published private(set) var messages: [WireChatMessage] = []
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var errorText: String?
    @Published private(set) var typers: [Typer] = []
    @Published private(set) var partnerLastReadAt: Date?
    @Published var draft = ""
    @Published var replyTarget: WireChatMessage?

    private let conversationId: String
    private weak var session: PulseSession?
    private var cancellables: Set<AnyCancellable> = []
    private var typingStopTask: Task<Void, Never>?

    var canSend: Bool { !draft.trimmingCharacters(in: .whitespaces).isEmpty }

    init(conversation: WireConversationSummary, session: PulseSession) {
        self.conversationId = conversation.id
        self.session = session
        observe(session: session)
        Task { await refresh(session: session) }
    }

    func observe(session: PulseSession) {
        self.session = session
        cancellables.removeAll()
        session.signals
            .receive(on: DispatchQueue.main)
            .sink { [weak self] signal in
                guard let self else { return }
                switch signal {
                case .messageNew(let convId, let raw):
                    guard convId == self.conversationId,
                          let message = PulseSession.decodeMessage(from: raw) else { return }
                    upsert(message)
                    if message.senderId != session.viewer?.id {
                        Task { try? await session.api.markRead(conversationId: self.conversationId) }
                    }
                case .messageDeleted(let convId, let raw):
                    guard convId == self.conversationId else { return }
                    if let message = PulseSession.decodeMessage(from: raw) {
                        upsert(message.deletedCopy())
                    }
                case .messageReact(let convId, let raw):
                    guard convId == self.conversationId,
                          let message = PulseSession.decodeMessage(from: raw) else { return }
                    upsert(message)
                case .messageRead(let convId, let userId):
                    guard convId == self.conversationId, userId != session.viewer?.id else { return }
                    partnerLastReadAt = Date()
                case .typing(let convId, let userId, let userName, let isTyping):
                    guard convId == self.conversationId, userId != session.viewer?.id else { return }
                    var bucket = typers.filter { $0.userId != userId }
                    if isTyping {
                        bucket.append(Typer(userId: userId, userName: userName, expiresAt: Date().addingTimeInterval(4)))
                    }
                    typers = bucket
                default:
                    break
                }
            }
            .store(in: &cancellables)

        // Typer expiry sweep — keeps the dots honest without server stop events.
        Timer.publish(every: 1.2, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                let now = Date()
                let alive = typers.filter { $0.expiresAt > now }
                if alive.count != typers.count { typers = alive }
            }
            .store(in: &cancellables)
    }

    func refresh(session: PulseSession) async {
        phase = messages.isEmpty ? .loading : phase
        do {
            let page = try await session.api.messages(conversationId: conversationId)
            messages = page.messages
            phase = .loaded
            errorText = nil
            try? session.store?.upsert(messages: page.messages)
            try? await session.api.markRead(conversationId: conversationId)
        } catch {
            phase = .failed(RoomViewModel.describe(error))
            if messages.isEmpty { errorText = RoomViewModel.describe(error) }
        }
    }

    func retry(session: PulseSession) async {
        errorText = nil
        await refresh(session: session)
    }

    func upsert(_ message: WireChatMessage) {
        guard let index = messages.firstIndex(where: { $0.id == message.id }) else {
            messages.append(message)
            messages.sort { PulseFormat.date($0.createdAt) ?? .distantPast < PulseFormat.date($1.createdAt) ?? .distantPast }
            return
        }
        messages[index] = message
    }

    func draftChanged(session: PulseSession) {
        let text = draft
        if !text.isEmpty && typingStopTask == nil {
            session.emitTyping(conversationId: conversationId, recipients: [], isTyping: true)
        }
        typingStopTask?.cancel()
        typingStopTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard let self, !Task.isCancelled else { return }
            self.typingStopTask = nil
            if self.draft.isEmpty {
                session.emitTyping(conversationId: self.conversationId, recipients: [], isTyping: false)
            }
        }
    }

    func send(session: PulseSession) {
        let body = draft.trimmingCharacters(in: .whitespaces)
        let replyId = replyTarget?.id
        guard !body.isEmpty else { return }
        draft = ""
        replyTarget = nil
        typingStopTask?.cancel()
        typingStopTask = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let message = try await session.api.sendMessage(conversationId: conversationId, content: body, replyToId: replyId)
                upsert(message)
                session.particles.fire(kind: .burst, count: 22)
                session.emitTyping(conversationId: conversationId, recipients: [], isTyping: false)
            } catch {
                errorText = Self.describe(error)
            }
        }
    }

    func react(_ message: WireChatMessage, emoji: String, session: PulseSession) {
        PulseHaptics.tap()
        Task { [weak self] in
            guard let self else { return }
            do {
                let fresh = try await session.api.react(messageId: message.id, emoji: emoji)
                upsert(fresh)
                if emoji == "❤️" { session.particles.fire(kind: .hearts, count: 24) }
            } catch {
                errorText = Self.describe(error)
            }
        }
    }

    func beginReply(to message: WireChatMessage) {
        replyTarget = message
    }

    func cancelReply() {
        replyTarget = nil
    }

    func isSeen(_ message: WireChatMessage) -> Bool {
        guard message.senderId == session?.viewer?.id,
              let last = messages.last(where: { $0.senderId == session?.viewer?.id }) else { return false }
        return last.id == message.id && partnerLastReadAt != nil
    }

    static func describe(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message, !message.isEmpty {
            return message
        }
        return "The gateway is unreachable."
    }
}
