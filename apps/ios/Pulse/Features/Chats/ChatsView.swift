import SwiftUI
import Combine

/// Navigation payload pushed onto any tab's NavigationStack.
struct RoomRoute: Hashable {
    let conversation: WireConversationSummary

    static func == (lhs: RoomRoute, rhs: RoomRoute) -> Bool { lhs.conversation.id == rhs.conversation.id }
    func hash(into hasher: inout Hasher) { hasher.combine(conversation.id) }
}

/// Chats — list of the viewer's conversations with live presence, typing
/// previews, streak flames, drafts, unread capsules, pin/mute/archive
/// swipes and pull-to-refresh. Mirror of the web chats tab IA.
struct ChatsView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs
    @State private var viewModel = ChatsViewModel()

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Chats")
                .navigationBarTitleDisplayMode(.large)
                .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
                .searchable(text: $viewModel.query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search chats")
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button {
                                viewModel.markAllRead(session: session)
                            } label: {
                                Label("Mark all read", systemImage: "checkmark.double")
                            }
                            .disabled(viewModel.unreadTotal == 0)
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
                .navigationDestination(for: RoomRoute.self) { route in
                    ChatRoomView(conversation: route.conversation, session: session)
                }
                .refreshable { await viewModel.refresh(session: session) }
                .task { await viewModel.refresh(session: session, force: viewModel.summaries.isEmpty) }
        }
        .onAppear { viewModel.observe(session: session) }
    }

    @ViewBuilder
    private var content: some View {
        ZStack(alignment: .top) {
            if let actionError = viewModel.actionError {
                errorBanner(actionError)
            } else if case .failed(let message) = viewModel.phase {
                errorBanner(message)
            }

            switch viewModel.phase {
            case .loading:
                placeholderList
            case .failed(let message) where viewModel.summaries.isEmpty:
                ContentUnavailableCompat(
                    title: "Chats unavailable",
                    systemImage: "wifi.exclamationmark",
                    note: message,
                )
            case .idle, .loaded, .failed:
                if viewModel.visible.isEmpty {
                    ContentUnavailableCompat(
                        title: viewModel.query.isEmpty ? "No conversations yet" : "No matches",
                        systemImage: viewModel.query.isEmpty ? "bubble.left.and.bubble.right" : "magnifyingglass",
                        note: viewModel.query.isEmpty
                            ? "Say hello from the Contacts tab — every identity on this Pulse can start a DM."
                            : "Try a different name or message text.",
                    )
                } else {
                    list
                }
            }
        }
    }

    private var list: some View {
        List {
            if !viewModel.query.isEmpty {
                Text("\(viewModel.visible.count) result\(viewModel.visible.count == 1 ? "" : "s")")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            } else {
                subtitle
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            ForEach(viewModel.visible, id: \.id) { summary in
                ZStack {
                    NavigationLink(value: RoomRoute(conversation: summary)) { EmptyView() }
                        .opacity(0)
                    ConversationRow(
                        summary: summary,
                        viewerId: session.viewer?.id ?? "",
                        onlineUserIds: session.onlineUserIds,
                        typers: session.typers(in: summary.id, excluding: session.viewer?.id),
                    )
                }
                .swipeActions(edge: .leading, allowsFullSwipe: true) {
                    Button {
                        Task { await viewModel.setPinned(summary, !summary.isPinned, session: session) }
                    } label: {
                        Label(summary.isPinned ? "Unpin" : "Pin", systemImage: summary.isPinned ? "pin.slash" : "pin.fill")
                    }
                    .tint(PulseTheme.emerald)
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button {
                        Task { await viewModel.setArchived(summary, true, session: session) }
                    } label: {
                        Label("Archive", systemImage: "archivebox.fill")
                    }
                    .tint(Color.gray)
                    Button {
                        Task { await viewModel.setMuted(summary, !summary.isMuted, session: session) }
                    } label: {
                        Label(summary.isMuted ? "Unmute" : "Mute", systemImage: summary.isMuted ? "bell.fill" : "bell.slash.fill")
                    }
                    .tint(.orange)
                }
                .contextMenu {
                    Button {
                        Task { await viewModel.markUnread(summary, session: session) }
                    } label: {
                        Label("Mark as unread", systemImage: "envelope.badge")
                    }
                    Button {
                        Task { await viewModel.setPinned(summary, !summary.isPinned, session: session) }
                    } label: {
                        Label(summary.isPinned ? "Unpin" : "Pin", systemImage: "pin")
                    }
                    Button {
                        Task { await viewModel.setMuted(summary, !summary.isMuted, session: session) }
                    } label: {
                        Label(summary.isMuted ? "Unmute" : "Mute", systemImage: summary.isMuted ? "bell" : "bell.slash")
                    }
                }
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .animation(.pulse(.pulseSoft, reduceMotion: false), value: viewModel.visible.map(\.id))
    }

    private var subtitle: some View {
        Text(subtitleText)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.top, 2)
    }

    private var subtitleText: String {
        let online = session.onlineUserIds.count
        let total = viewModel.visible.count
        return "\(total) conversation\(total == 1 ? "" : "s") · \(online) online"
    }

    private var placeholderList: some View {
        List {
            ForEach(0..<7, id: \.self) { _ in
                HStack(spacing: 12) {
                    Circle().fill(Color.gray.opacity(0.25)).frame(width: 52, height: 52)
                    VStack(alignment: .leading, spacing: 6) {
                        RoundedRectangle(cornerRadius: 4).fill(Color.gray.opacity(0.25)).frame(width: 140, height: 12)
                        RoundedRectangle(cornerRadius: 4).fill(Color.gray.opacity(0.18)).frame(width: 220, height: 10)
                    }
                }
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .redacted(reason: .placeholder)
        .shimmering(false)
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(message).font(.footnote).lineLimit(2)
            Spacer()
            Button("Retry") {
                Task { await viewModel.refresh(session: session) }
            }
            .font(.footnote.weight(.semibold))
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(.regularMaterial))
        .padding(.horizontal, 12)
        .zIndex(2)
    }
}

private extension View {
    /// No-op modifier kept for placeholder styling symmetry.
    func shimmering(_ active: Bool) -> some View { self }
}

/// One conversation row — 52pt avatar with presence ring, bold title with
/// pin/mute glyphs, live preview line (typing dots, draft, streak flame),
/// trailing time + emerald unread capsule.
struct ConversationRow: View {
    let summary: WireConversationSummary
    let viewerId: String
    let onlineUserIds: Set<String>
    let typers: [PulseSession.Typer]

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var otherMembers: [WireConversationMember] {
        summary.members.filter { $0.id != viewerId }
    }

    private var title: String {
        summary.name ?? otherMembers.first?.name ?? summary.members.first?.name ?? "Conversation"
    }

    private var presenceOnline: Bool {
        otherMembers.contains { onlineUserIds.contains($0.id) }
    }

    private var isGroup: Bool { summary.isGroup }

    private var avatarColor: Color {
        if isGroup { return PulseTheme.emerald }
        return PulseTheme.color(named: otherMembers.first?.color ?? summary.lastMessage?.sender?.color)
    }

    private var photoPath: String? {
        summary.photo ?? otherMembers.first?.avatar
    }

    private var streakCount: Int { summary.myStreak?.count ?? 0 }

    private var preview: String? {
        if let draft = summary.myDraft, !draft.isEmpty { return draft }
        guard let last = summary.lastMessage else { return nil }
        if last.deletedAt != nil { return "Message deleted" }
        var body = WireConversationSummary.preview(of: last)
        if isGroup, let sender = last.sender?.name, last.senderId != viewerId {
            body = "\(sender): \(body)"
        }
        return body
    }

    private var previewIsDraft: Bool {
        if let draft = summary.myDraft, !draft.isEmpty { return true }
        return false
    }

    private var timeText: String {
        PulseFormat.rowTime(summary.lastMessage?.createdAt ?? summary.updatedAt)
    }

    private var unreadCount: Int { summary.unreadCount ?? 0 }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            PulseAvatar(
                name: title,
                color: avatarColor,
                photoURL: PulseTheme.photoURL(photoPath, base: PulseEndpoints.gatewayURL),
                online: presenceOnline,
                size: 52,
            )

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Text(title)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    if summary.isPinned {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    if summary.isMuted {
                        Image(systemName: "bell.slash.fill")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    Spacer(minLength: 0)
                    Text(timeText)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 6) {
                    previewContent
                    Spacer(minLength: 0)
                    if streakCount > 0 {
                        HStack(spacing: 2) {
                            Image(systemName: "flame.fill")
                            Text("\(streakCount)")
                        }
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(PulseTheme.amber)
                    }
                    if unreadCount > 0 {
                        UnreadBadge(count: unreadCount)
                            .animation(.pulse(.pulseBouncy, reduceMotion: reduceMotion), value: unreadCount)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var previewContent: some View {
        if !typers.isEmpty {
            HStack(spacing: 4) {
                TypingDotsView()
                Text(typersText)
                    .font(.footnote)
                    .foregroundStyle(PulseTheme.emerald)
            }
        } else if previewIsDraft {
            (Text("Draft: ").italic().foregroundStyle(PulseTheme.rose) + Text(preview ?? "").italic())
                .font(.footnote)
                .foregroundStyle(PulseTheme.rose)
                .lineLimit(1)
        } else {
            Text(preview ?? "")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    private var typersText: String {
        let names = typers.compactMap { typer -> String? in
            let name = typer.userName
            if name.isEmpty { return nil }
            return name
        }
        if names.isEmpty { return "typing…" }
        if names.count == 1 { return "\(names[0]) is typing…" }
        return "typing…"
    }
}

/// Chats state holder (UDF): transport in PulseSession, this owns list state.
@MainActor
final class ChatsViewModel: ObservableObject {
    enum Phase: Equatable {
        case idle, loading, loaded, failed(String)
    }

    @Published private(set) var summaries: [WireConversationSummary] = []
    @Published private(set) var phase: Phase = .idle
    @Published var query = ""
    @Published private(set) var actionError: String?

    private var cancellables: Set<AnyCancellable> = []
    private var observing = false

    var visible: [WireConversationSummary] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return summaries }
        let needle = trimmed.lowercased()
        return summaries.filter { summary in
            let title = summary.name ?? summary.members.first?.name ?? ""
            let preview = summary.lastMessage?.content ?? ""
            return title.lowercased().contains(needle) || preview.lowercased().contains(needle)
        }
    }

    var unreadTotal: Int {
        summaries.reduce(0) { $0 + max($1.unreadCount ?? 0, 0) }
    }

    func observe(session: PulseSession) {
        guard !observing else { return }
        observing = true
        session.signals
            .filter { signal in
                switch signal {
                case .messageNew, .messageDeleted, .messageReact, .messageRead: return true
                default: return false
                }
            }
            .debounce(for: .milliseconds(500), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in
                Task { await self?.refresh(session: session) }
            }
            .store(in: &cancellables)

        // Inbox mutations from other tabs (contacts DM create, hub, …).
        session.$inboxRefreshTick
            .dropFirst()
            .debounce(for: .milliseconds(250), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in
                Task { await self?.refresh(session: session) }
            }
            .store(in: &cancellables)
    }

    func refresh(session: PulseSession, force: Bool = false) async {
        if force || summaries.isEmpty { phase = phase == .loaded ? phase : .loading }
        do {
            let fresh = try await session.api.conversations()
            summaries = fresh
            phase = .loaded
            if let store = session.store {
                try? store.upsert(conversations: fresh.map { $0.toDomain() })
            }
        } catch {
            if summaries.isEmpty {
                phase = .failed(Self.describe(error))
            } else {
                actionError = Self.describe(error)
            }
        }
    }

    func setPinned(_ summary: WireConversationSummary, _ pinned: Bool, session: PulseSession) async {
        do { try await session.api.togglePin(conversationId: summary.id, pinned: pinned) } catch { actionError = Self.describe(error) }
        await refreshQuiet(session: session)
    }

    func setMuted(_ summary: WireConversationSummary, _ muted: Bool, session: PulseSession) async {
        do { try await session.api.setMuted(conversationId: summary.id, muted: muted) } catch { actionError = Self.describe(error) }
        await refreshQuiet(session: session)
    }

    func setArchived(_ summary: WireConversationSummary, _ archived: Bool, session: PulseSession) async {
        do { try await session.api.archive(conversationId: summary.id, archived: archived) } catch { actionError = Self.describe(error) }
        await refreshQuiet(session: session)
    }

    func markUnread(_ summary: WireConversationSummary, session: PulseSession) async {
        do { try await session.api.markUnread(conversationId: summary.id, on: true) } catch { actionError = Self.describe(error) }
        await refreshQuiet(session: session)
    }

    /// Mark every unread conversation read — the honest "mark all read".
    func markAllRead(session: PulseSession) {
        let targets = summaries.filter { ($0.unreadCount ?? 0) > 0 }
        guard !targets.isEmpty else { return }
        Task {
            for summary in targets {
                try? await session.api.markRead(conversationId: summary.id)
            }
            PulseHaptics.success()
            session.particles.fire(kind: .confetti, count: 90)
            await refreshQuiet(session: session)
        }
    }

    private func refreshQuiet(session: PulseSession) async {
        do {
            summaries = try await session.api.conversations()
            phase = .loaded
        } catch {
            actionError = Self.describe(error)
        }
    }

    static func describe(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message, !message.isEmpty {
            return message
        }
        return "The gateway is unreachable."
    }
}
