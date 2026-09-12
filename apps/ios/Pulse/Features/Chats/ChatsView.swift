import SwiftUI
import Combine

/// Navigation payload pushed onto any tab's NavigationStack.
struct RoomRoute: Hashable {
    let conversation: WireConversationSummary
    /// Wave 1 — global-search jump target (scroll + flash after load).
    var jumpMessageId: String? = nil

    static func == (lhs: RoomRoute, rhs: RoomRoute) -> Bool {
        lhs.conversation.id == rhs.conversation.id && lhs.jumpMessageId == rhs.jumpMessageId
    }
    func hash(into hasher: inout Hasher) {
        hasher.combine(conversation.id)
        hasher.combine(jumpMessageId)
    }
}

/// Identifiable wrapper so `.sheet(item:)` can drive the row action sheet.
struct SheetTarget: Identifiable, Equatable {
    let conversation: WireConversationSummary
    var id: String { conversation.id }
}

/// One list row, fully resolved — the exact web ConversationRowData shape
/// (chats-row.tsx props) translated for the native row.
struct ChatRowModel: Identifiable {
    let conv: WireConversationSummary
    let id: String
    let name: String
    let time: String
    let previewText: String
    let previewPrefix: String
    let previewDeleted: Bool
    let draft: String?
    let unreadCount: Int
    let manualUnread: Bool
    let isGroup: Bool
    let dmName: String?
    let dmColor: String?
    let groupTitle: String
    let online: Bool
    let isPinned: Bool
    let isMuted: Bool
    let isArchived: Bool
    let typing: Bool
    let streakCount: Int
    let streakAtRisk: WireStreak?
    let streakLost: WireStreak?
    let photo: String?
    let memberCount: Int
    let isSelf: Bool

    var hasUnread: Bool { unreadCount > 0 || manualUnread }

    /// Search haystack — name, draft and the last non-deleted message body.
    func matches(query needle: String) -> Bool {
        let q = needle.lowercased()
        if name.lowercased().contains(q) { return true }
        if let draft, draft.lowercased().contains(q) { return true }
        if let last = conv.lastMessage, last.deletedAt == nil,
           last.content.lowercased().contains(q) { return true }
        return false
    }
}

// ─────────────────────────────────────────────────────────────
// Chats — the home screen. Custom header (normal + search modes),
// filter chips, stories row, folder rail, Note to Self, entry pills,
// pinned/All-chats sections, Telegram multi-select, swipe glass
// chips, action sheet, archived sub-page. 6s REST poll while visible.
// ─────────────────────────────────────────────────────────────
struct ChatsView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs
    var onGoContacts: () -> Void = {}
    var onGoProfile: () -> Void = {}
    /// Whether this tab is the visible one (dock-level tab switch).
    var isActive: Bool = true

    @StateObject private var viewModel = ChatsViewModel()
    @State private var path = NavigationPath()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    // Wave 4 — full-screen stories surfaces (rail is the entry point).
    @State private var storiesViewerPresent = false
    @State private var storiesViewerStart: String?
    @State private var composerPresent = false

    var body: some View {
        NavigationStack(path: $path) {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                content
            }
            .safeAreaInset(edge: .top, spacing: 0) { topChrome }
            .navigationBarHidden(true)
            .navigationDestination(for: RoomRoute.self) { route in
                ChatRoomView(conversation: route.conversation, session: session, jumpMessageId: route.jumpMessageId)
            }
            .sheet(item: $viewModel.sheet) { target in
                ChatActionSheet(
                    conversation: target.conversation,
                    session: session,
                    onChanged: { Task { await viewModel.refreshQuiet(session: session) } },
                )
            }
            .fullScreenCover(isPresented: $storiesViewerPresent) {
                if let model = session.stories {
                    StoryViewerView(session: session, stories: model, startUserId: storiesViewerStart) {
                        storiesViewerPresent = false
                        Task { await viewModel.refreshQuiet(session: session) } // rings re-sync on close
                    }
                }
            }
            .fullScreenCover(isPresented: $composerPresent) {
                if let model = session.stories {
                    StoryComposerView(session: session, stories: model, onPublished: {
                        composerPresent = false
                        Task { await viewModel.refreshQuiet(session: session) }
                    }, onClose: { composerPresent = false })
                }
            }
            .fullScreenCover(isPresented: $viewModel.archivedOpen) {
                ArchivedPageView(
                    session: session,
                    rows: viewModel.archivedRows,
                    loading: viewModel.phase == .loading && viewModel.summaries.isEmpty,
                    onPress: { row in openRoom(row.conv) },
                    onLongPress: { row in viewModel.sheet = SheetTarget(conversation: row.conv) },
                    onPin: { row in Task { await viewModel.togglePin(row.conv, session: session) } },
                    onArchive: { row in Task { await viewModel.setArchived(row.conv, false, session: session) } },
                    onBack: { viewModel.archivedOpen = false },
                )
            }
        }
        .onAppear { viewModel.start(session: session, prefs: prefs) }
        .onDisappear { viewModel.stop() }
        .onChange(of: isActive) { _, active in
            if active {
                viewModel.start(session: session, prefs: prefs)
            } else {
                viewModel.stop()
            }
        }
        .onReceive(session.$searchRequestTick.dropFirst().removeDuplicates()) { _ in
            enterSearch()
        }
        .onReceive(session.$pendingOpenRoom) { pending in
            // Dock compose / More → Saved hand a conversation here. The
            // Published value also replays on re-subscription, so consume
            // immediately (nil re-fire is guarded). Wave 2 — a saved-library
            // "open original" rides the SAME path with a jump target.
            guard let conv = pending else { return }
            let jump = session.pendingJumpMessageId
            session.consumePendingOpenRoom()
            session.consumePendingJumpMessageId()
            openRoom(conv, jumpMessageId: jump)
        }
    }

    // ── actions ──────────────────────────────────────────────

    private func enterSearch() {
        if viewModel.selectMode { viewModel.exitSelect() }
        withAnimation(.pulse(.pulseSnappy, reduceMotion: reduceMotion)) {
            viewModel.searching = true
        }
        viewModel.searchFocused = true
    }

    private func exitSearch() {
        viewModel.closeSearch()
    }

    private func openRoom(_ conv: WireConversationSummary, jumpMessageId: String? = nil) {
        path.append(RoomRoute(conversation: conv, jumpMessageId: jumpMessageId))
    }

    // ── fixed top chrome (header · chips · stories · folders) ──

    @ViewBuilder
    private var topChrome: some View {
        VStack(spacing: 0) {
            if viewModel.searching {
                SearchHeaderBar(
                    query: $viewModel.query,
                    focused: $viewModel.searchFocused,
                    onClose: exitSearch,
                    onQueryChange: { viewModel.searchTextChanged($0, session: session) },
                )
                .transition(.move(edge: .top).combined(with: .opacity))
            } else {
                ChatsHeaderBar(
                    session: session,
                    prefs: prefs,
                    onGoProfile: onGoProfile,
                    onPhone: {
                        PulseHaptics.tap()
                        session.toasts.show("Calls aren't available in this native build yet.")
                    },
                    onCompose: {
                        PulseHaptics.tap()
                        session.toasts.show("New chat composer isn't in this native build yet.")
                    },
                    onStartSearch: {
                        PulseHaptics.tap()
                        enterSearch()
                    },
                )
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            if !viewModel.searching {
                FilterChipsRow(
                    filter: prefs.chatsFilter,
                    unreadTotal: viewModel.unreadTotal,
                    onSelect: { filter in
                        PulseHaptics.tap()
                        prefs.setChatsFilter(filter)
                    },
                )
                StoriesRowView(
                    viewer: session.viewer,
                    groups: viewModel.storyGroups,
                    // Wave 4: ring = viewer (seeded at the tapped author),
                    // "+"/empty own cell = composer (web-defect D1 fixed).
                    onPress: { startUserId in
                        PulseHaptics.tap()
                        guard session.stories != nil else {
                            session.toasts.show("Pick who you are on this device first.")
                            return
                        }
                        if let startUserId {
                            storiesViewerStart = startUserId
                            storiesViewerPresent = true
                        } else {
                            composerPresent = true
                        }
                    },
                )
                FolderRailView(
                    folders: viewModel.folders,
                    activeFolderId: viewModel.activeFolderId,
                    counts: viewModel.folderCounts,
                    onSelectFolder: { id in
                        PulseHaptics.tap()
                        viewModel.selectFolder(id)
                    },
                    onManage: {
                        PulseHaptics.tap()
                        session.toasts.show("Chat folders aren't available in this native build yet.")
                    },
                )
            }
        }
        .background(.ultraThinMaterial)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(PulseTheme.hairlinePanel)
                .frame(height: 1)
        }
    }

    // ── scroll area states (priority order per spec §7) ──────

    @ViewBuilder
    private var content: some View {
        ZStack {
            if viewModel.phase == .loading && viewModel.summaries.isEmpty {
                ChatsSkeletonView()
            } else if viewModel.searching {
                searchResults
            } else if viewModel.rows.isEmpty {
                if case .failed(let message) = viewModel.phase {
                    ErrorCard(message: message) {
                        Task { await viewModel.refresh(session: session, force: true) }
                    }
                } else {
                    EmptyChatsCard(onSayHi: onGoContacts)
                }
            } else {
                mainList
            }

            if viewModel.selectMode {
                // Pinned near the bottom, centered (web §7.5: bottom-[86px]) —
                // the outer ZStack is top-aligned, so pin via a filling spacer.
                VStack {
                    Spacer(minLength: 0)
                    MultiSelectBar(
                        count: viewModel.selection.count,
                        archivePending: viewModel.batchArchivePending,
                        mutePending: viewModel.batchMutePending,
                        readPending: viewModel.batchReadPending,
                        onArchive: { Task { await viewModel.batchArchive(session: session) } },
                        onMute: { Task { await viewModel.batchMute8h(session: session) } },
                        onRead: { Task { await viewModel.batchMarkRead(session: session) } },
                        onExit: { viewModel.exitSelect() },
                    )
                    .padding(.bottom, 86)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.pulse(.pulseSnappy, reduceMotion: reduceMotion), value: viewModel.selectMode)
    }

    private var mainList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                NoteToSelfCard(
                    exists: viewModel.selfConv != nil,
                    creating: viewModel.selfCreating,
                    onPress: {
                        if let conv = viewModel.selfConv {
                            // Existing Note to Self — open directly (the id never
                            // changes, so the onChange replay hook can't fire).
                            viewModel.sheet = nil
                            openRoom(conv)
                        } else {
                            Task { await viewModel.pressSelf(session: session) }
                        }
                    },
                    onOpened: { conv in openRoom(conv) },
                )
                .onChange(of: viewModel.selfConv?.id) { _, newValue in
                    // Self chat appeared/changed → replay the open callback.
                    if let id = newValue, let conv = viewModel.selfConv, conv.id == id, viewModel.openSelfOnNext {
                        viewModel.openSelfOnNext = false
                        openRoom(conv)
                    }
                }

                EntryPill(
                    icon: "at",
                    title: "Mentions",
                    trailing: viewModel.mentionCount == 1 ? "1 mention" : "\(viewModel.mentionCount ?? 0) mentions",
                    badgeCount: viewModel.mentionCount ?? 0,
                    onPress: {
                        PulseHaptics.tap()
                        session.toasts.show("Mentions aren't available in this native build yet.")
                    },
                )
                EntryPill(
                    icon: "dot.radiowaves.left.and.right",
                    title: "Channels",
                    trailing: viewModel.subscribedChannelCount == 1 ? "1 channel" : "\(viewModel.subscribedChannelCount) channels",
                    badgeCount: 0,
                    onPress: {
                        PulseHaptics.tap()
                        session.toasts.show("Channels aren't available in this native build yet.")
                    },
                )
                EntryPill(
                    icon: "archivebox",
                    title: "Archived",
                    trailing: viewModel.archivedRows.count == 1 ? "1 chat" : "\(viewModel.archivedRows.count) chats",
                    badgeCount: viewModel.archivedUnread,
                    onPress: {
                        PulseHaptics.tap()
                        viewModel.archivedOpen = true
                    },
                )

                rowsBlock
                    .id(viewModel.entranceGeneration)

                if viewModel.visibleRows.isEmpty && !viewModel.activeRows.isEmpty {
                    Text(filterEmptyCopy)
                        .font(.system(size: 13))
                        .foregroundStyle(PulseTheme.textTertiary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                        .padding(.top, 40)
                        .padding(.bottom, 16)
                }
                if viewModel.activeRows.isEmpty && !viewModel.archivedRows.isEmpty {
                    Text("Every chat is archived.\nNew messages bring chats back here.")
                        .font(.system(size: 13))
                        .foregroundStyle(PulseTheme.textTertiary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                        .padding(.top, 40)
                        .padding(.bottom, 16)
                }

                Color.clear.frame(height: 24)
            }
        }
        .scrollDismissesKeyboard(.immediately)
        .refreshable { await viewModel.refresh(session: session, force: true) }
    }

    @ViewBuilder
    private var rowsBlock: some View {
        let pinned = viewModel.visibleRows.filter { $0.isPinned }
        let unpinned = viewModel.visibleRows.filter { !$0.isPinned }
        Group {
            if !pinned.isEmpty {
                SectionHeader(label: "PINNED", count: pinned.count)
            }
            ForEach(Array(pinned.enumerated()), id: \.element.id) { index, row in
                rowView(row, entranceIndex: viewModel.entranceOn ? index : nil)
            }
            if !pinned.isEmpty && !unpinned.isEmpty {
                SectionHeader(label: "ALL CHATS", count: unpinned.count)
            }
            ForEach(Array(unpinned.enumerated()), id: \.element.id) { index, row in
                rowView(row, entranceIndex: viewModel.entranceOn ? pinned.count + index : nil)
            }
        }
        .transition(reduceMotion ? .opacity : AnyTransition.folderReplay)
    }

    private func rowView(_ row: ChatRowModel, entranceIndex: Int?) -> some View {
        ConversationRow(
            row: row,
            viewerId: session.viewer?.id ?? "",
            archivedContext: false,
            entranceIndex: entranceIndex,
            selectMode: viewModel.selectMode,
            selected: viewModel.selection.contains(row.id),
            onPress: {
                if viewModel.selectMode {
                    viewModel.toggleSelect(row.id)
                } else {
                    openRoom(row.conv)
                }
            },
            onLongPress: { viewModel.enterSelect(row.id) },
            onPin: { Task { await viewModel.togglePin(row.conv, session: session) } },
            onArchive: { Task { await viewModel.setArchived(row.conv, !row.isArchived, session: session) } },
        )
    }

    private var filterEmptyCopy: String {
        if viewModel.activeFolderId != nil {
            return "This folder is empty — tap the folder button on the rail to add chats."
        }
        switch prefs.chatsFilter {
        case .unread: return "No unread chats — you are all caught up."
        case .groups: return "No groups yet — start one from Contacts."
        default: return "Nothing here yet."
        }
    }

    // ── search results (§10) ─────────────────────────────────

    private var searchResults: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if !viewModel.filteredRows.isEmpty {
                    SectionHeader(label: "CHATS", count: viewModel.filteredRows.count)
                    ForEach(viewModel.filteredRows) { row in
                        ConversationRow(
                            row: row,
                            viewerId: session.viewer?.id ?? "",
                            archivedContext: false,
                            entranceIndex: nil,
                            selectMode: false,
                            selected: false,
                            onPress: { openRoom(row.conv) },
                            onLongPress: { viewModel.sheet = SheetTarget(conversation: row.conv) },
                            onPin: { Task { await viewModel.togglePin(row.conv, session: session) } },
                            onArchive: { Task { await viewModel.setArchived(row.conv, !row.isArchived, session: session) } },
                        )
                    }
                }

                if viewModel.deferredQuery.count >= 2 {
                    if viewModel.serverSearching {
                        HStack(spacing: 8) {
                            ProgressView()
                                .controlSize(.small)
                                .tint(PulseTheme.accent)
                            Text("Searching messages…")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(PulseTheme.textTertiary)
                        }
                        .padding(.vertical, 24)
                        .frame(maxWidth: .infinity)
                    } else if let hits = viewModel.serverHits, !hits.isEmpty {
                        SectionHeader(label: "MESSAGES", count: viewModel.serverTotal ?? hits.count)
                        ForEach(hits, id: \.id) { hit in
                            SearchMessageRowView(hit: hit, query: viewModel.deferredQuery) {
                                if let conv = viewModel.summaries.first(where: { $0.id == hit.conversationId }) {
                                    openRoom(conv, jumpMessageId: hit.id)
                                }
                            }
                        }
                    }
                } else if viewModel.deferredQuery.count == 1 {
                    Text("Keep typing to search inside messages…")
                        .font(.system(size: 12))
                        .foregroundStyle(PulseTheme.textTertiary)
                        .padding(.top, 16)
                        .frame(maxWidth: .infinity)
                }

                if viewModel.filteredRows.isEmpty && viewModel.showNoMatches {
                    VStack(spacing: 6) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 30, weight: .light))
                            .foregroundStyle(PulseTheme.zinc(300))
                        Text("No matches")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(PulseTheme.textSecondary)
                        Text("Nothing here for “\(viewModel.query)”.")
                            .font(.system(size: 12))
                            .foregroundStyle(PulseTheme.textTertiary)
                    }
                    .padding(.top, 90)
                    .frame(maxWidth: .infinity)
                }
                Color.clear.frame(height: 24)
            }
        }
        .scrollDismissesKeyboard(.immediately)
    }
}

// ─────────────────────────────────────────────────────────────
// Header bars
// ─────────────────────────────────────────────────────────────

private struct ChatsHeaderBar: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs
    let onGoProfile: () -> Void
    let onPhone: () -> Void
    let onCompose: () -> Void
    let onStartSearch: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button(action: onGoProfile) {
                    RowAvatar(
                        name: session.viewer?.name ?? "You",
                        colorName: session.viewer?.color,
                        photoPath: session.viewer?.avatar,
                        size: 36,
                    )
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Open my profile")

                HStack(spacing: 6) {
                    Text("Pulse")
                        .font(.system(size: 20, weight: .bold, design: .default))
                        .tracking(-0.3)
                        .foregroundStyle(PulseTheme.titleOnWash)
                    Circle()
                        .fill(PulseTheme.emeraldGradient)
                        .frame(width: 6, height: 6)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 4)

                Button(action: onPhone) {
                    Image(systemName: "phone")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(PulseTheme.textSecondary)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(Color.clear))
                        .contentShape(Circle())
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Open calls")

                Button(action: onCompose) {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(PulseTheme.textSecondary)
                        .frame(width: 40, height: 40)
                        .contentShape(Circle())
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("New chat")

                Button(action: { prefs.cycleAppearance() }) {
                    Image(systemName: appearanceIcon)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(PulseTheme.textSecondary)
                        .frame(width: 40, height: 40)
                        .contentShape(Circle())
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Appearance: \(appearanceName)")
            }
            .padding(.horizontal, 12)
            .padding(.top, 10)
            .padding(.bottom, 10)

            Button(action: onStartSearch) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 15))
                        .foregroundStyle(PulseTheme.textTertiary)
                    Text("Search chats and messages")
                        .font(.system(size: 14))
                        .foregroundStyle(PulseTheme.textTertiary)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .frame(height: 40)
                .background(Capsule().fill(PulseTheme.glassFill))
                .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
            }
            .buttonStyle(PulseButtonStyle())
            .padding(.horizontal, 12)
            .padding(.bottom, 10)
            .accessibilityLabel("Start searching")
        }
    }

    private var appearanceIcon: String {
        switch prefs.appearance {
        case "light": return "sun.max.fill"
        case "dark": return "moon.fill"
        default: return "circle.lefthalf.filled"
        }
    }

    private var appearanceName: String {
        switch prefs.appearance {
        case "light": return "Light"
        case "dark": return "Dark"
        default: return "System"
        }
    }
}

private extension PulseTheme {
    /// 6pt title dot — emerald 400→600.
    static var emeraldGradient: LinearGradient {
        LinearGradient(colors: [emerald400, emerald600], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// Search mode — glass pill with a live text field, springing clear button,
/// emerald focus halo, ghost close. Web §3b.
private struct SearchHeaderBar: View {
    @Binding var query: String
    @Binding var focused: Bool
    // .focused() requires a FocusState projection, not a plain Binding —
    // keep the view-local FocusState and mirror it both ways with the
    // parent-owned binding (pill tap sets searchFocused = true → keyboard).
    @FocusState private var keyFocus: Bool
    let onClose: () -> Void
    let onQueryChange: (String) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var hot: Bool { focused && !query.isEmpty }

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15))
                    .foregroundStyle(PulseTheme.textTertiary)
                TextField(
                    "Search chats and messages…",
                    text: Binding(
                        get: { query },
                        set: { onQueryChange($0) },
                    ),
                )
                .font(.system(size: 14))
                .foregroundStyle(PulseTheme.titleOnWash)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .focused($keyFocus)
                .onChange(of: keyFocus) { focused = $0 }
                .onChange(of: focused) { keyFocus = $0 }
                .accessibilityLabel("Search conversations")

                if !query.isEmpty {
                    Button {
                        onQueryChange("")
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(PulseTheme.textPrimary)
                            .frame(width: 24, height: 24)
                            .background(Circle().fill(PulseTheme.zinc(300).opacity(0.7)))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Clear search")
                    .transition(.scale(scale: 0.4).combined(with: .opacity))
                }
            }
            .padding(.leading, 16)
            .padding(.trailing, 10)
            .frame(height: 40)
            .frame(maxWidth: .infinity)
            .background(Capsule().fill(PulseTheme.glassFill))
            .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
            .overlay {
                if hot {
                    Capsule()
                        .strokeBorder(PulseTheme.accent.opacity(0.5), lineWidth: 2)
                        .shadow(color: PulseTheme.emerald500.opacity(0.25), radius: 10)
                }
            }
            .animation(.pulse(.pulseSoft, reduceMotion: reduceMotion), value: hot)
            .animation(.pulse(.pulseBouncy, reduceMotion: reduceMotion), value: query.isEmpty)

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(hot ? PulseTheme.accent : PulseTheme.textSecondary)
                    .frame(width: 40, height: 40)
                    .contentShape(Circle())
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Close search")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
    }
}

// ─────────────────────────────────────────────────────────────
// Filter chips · stories · folders
// ─────────────────────────────────────────────────────────────

private struct FilterChipsRow: View {
    let filter: PulsePrefs.ChatsFilter
    let unreadTotal: Int
    let onSelect: (PulsePrefs.ChatsFilter) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                chip(.all, "All")
                chip(.unread, "Unread")
                chip(.groups, "Groups")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
    }

    private func chip(_ key: PulsePrefs.ChatsFilter, _ label: String) -> some View {
        let active = filter == key
        return Button {
            onSelect(key)
        } label: {
            HStack(spacing: 4) {
                if key == .unread && unreadTotal > 0 && !active {
                    Text(unreadTotal > 99 ? "99+" : "\(unreadTotal)")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(PulseTheme.accent)
                        .padding(.horizontal, 4)
                        .frame(minWidth: 15, minHeight: 15)
                        .background(Capsule().fill(PulseTheme.emerald500.opacity(0.20)))
                }
                Text(label)
            }
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(active ? Color.white : PulseTheme.textSecondary)
            .padding(.horizontal, 12)
            .frame(height: 28)
            .background(
                Capsule().fill(active ? PulseTheme.emerald500 : PulseTheme.chipFill),
            )
            .shadow(color: active ? PulseTheme.emerald600.opacity(0.25) : .clear, radius: 3, y: 1)
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("\(label) filter")
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}

private struct StoriesRowView: View {
    let viewer: PulseViewer?
    let groups: [WireStoryGroup]
    /// arg = author userId to seed the viewer, nil = open the composer (own cell without a live story)
    let onPress: (String?) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    StoryRingCell(
                        name: viewer?.name ?? "You",
                        color: viewer?.color,
                        ring: myStoryGroup != nil ? .unseen : .none,
                        plus: true, // D1: "+" stays reachable even while a story is live
                        label: "My status",
                        onPress: {
                            onPress(myStoryGroup?.user?.id) // live → viewer; none → composer
                        },
                    )
                    ForEach(otherGroups, id: \.user?.id) { group in
                        if let user = group.user {
                            StoryRingCell(
                                name: user.name,
                                color: user.color,
                                ring: group.allSeen == true ? .seen : .unseen,
                                plus: false,
                                label: user.name,
                                onPress: { onPress(user.id) },
                            )
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 6)
                .padding(.bottom, 8)
            }
        }
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(PulseTheme.hairlineSoft)
                .frame(height: 1)
        }
    }

    private var myStoryGroup: WireStoryGroup? {
        groups.first(where: { $0.mine == true })
    }

    private var otherGroups: [WireStoryGroup] {
        groups.filter { $0.mine != true }
    }
}

private struct FolderRailView: View {
    let folders: [WireFolder]?
    let activeFolderId: String?
    let counts: [String: Int]
    let onSelectFolder: (String?) -> Void
    let onManage: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                railPill(id: nil, emoji: nil, label: "All", count: 0)
                ForEach(folders ?? [], id: \.id) { folder in
                    railPill(
                        id: folder.id,
                        emoji: folder.emoji,
                        label: folder.name,
                        count: counts[folder.id] ?? 0,
                    )
                }
                Button(action: onManage) {
                    Image(systemName: "folder.badge.plus")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(PulseTheme.textSecondary)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(PulseTheme.glassFill))
                        .overlay(Circle().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Manage chat folders")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
        }
    }

    private func railPill(id: String?, emoji: String?, label: String, count: Int) -> some View {
        let active = activeFolderId == id
        return Button {
            onSelectFolder(id)
        } label: {
            HStack(spacing: 6) {
                if let emoji {
                    Text(emoji).font(.system(size: 13))
                }
                Text(label)
                    .lineLimit(1)
                    .frame(maxWidth: id == nil ? nil : 96, alignment: .leading)
                if count > 0 {
                    Text(count > 99 ? "99+" : "\(count)")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(active ? .white : PulseTheme.accent)
                        .padding(.horizontal, 4)
                        .frame(minWidth: 15, minHeight: 15)
                        .background(
                            Capsule().fill(active ? Color.white.opacity(0.25) : PulseTheme.emerald500.opacity(0.20)),
                        )
                }
            }
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(active ? Color.white : PulseTheme.textPrimary)
            .padding(.horizontal, 16)
            .frame(height: 44)
            .background(
                Capsule().fill(active ? PulseTheme.emerald500 : Color.clear)
                    .background(Capsule().fill(PulseTheme.glassFill)),
            )
            .overlay(Capsule().strokeBorder(active ? Color.clear : PulseTheme.hairlineStrong, lineWidth: 1))
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(id == nil ? "All chats" : "Folder \(label)")
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}

// ─────────────────────────────────────────────────────────────
// Cards & pills
// ─────────────────────────────────────────────────────────────

/// Note to Self hero card — deep glass, glyph tile, Create/Open chip.
private struct NoteToSelfCard: View {
    let exists: Bool
    let creating: Bool
    let onPress: () -> Void
    let onOpened: (WireConversationSummary) -> Void

    var body: some View {
        Button(action: onPress) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(PulseTheme.brandGradient)
                    Image(systemName: "book.closed.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                    // Top radial highlight (web glass-sheen on the tile).
                    RadialGradient(
                        colors: [Color.white.opacity(0.55), .clear],
                        center: UnitPoint(x: 0.5, y: 0.0),
                        startRadius: 0, endRadius: 26,
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .frame(width: 36, height: 36)
                .shadow(color: PulseTheme.emerald600.opacity(0.30), radius: 3, y: 1)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Note to Self")
                        .font(.system(size: 14, weight: .semibold))
                        .tracking(-0.2)
                        .foregroundStyle(PulseTheme.titleOnWash)
                    Text("Your private space — notes, links, ideas")
                        .font(.system(size: 11.5))
                        .foregroundStyle(PulseTheme.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if creating {
                    ProgressView()
                        .controlSize(.small)
                        .tint(PulseTheme.accent)
                } else if exists {
                    HStack(spacing: 1) {
                        Text("Open")
                        Image(systemName: "chevron.right")
                    }
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(PulseTheme.accent)
                } else {
                    HStack(spacing: 1) {
                        Text("Create")
                        Image(systemName: "chevron.right")
                    }
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.init(top: 4, leading: 8, bottom: 4, trailing: 5))
                    .background(Capsule().fill(PulseTheme.emerald500))
                    .shadow(color: PulseTheme.emerald600.opacity(0.30), radius: 3, y: 1)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(.ultraThinMaterial),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1),
            )
        }
        .buttonStyle(PulseButtonStyle())
        .padding(.horizontal, 8)
        .padding(.top, 4)
        .padding(.bottom, 4)
        .accessibilityLabel(exists ? "Open Note to Self — your private space" : "Create Note to Self — your private space")
    }
}

/// Mentions / Channels / Archived entry pill — glass, h-11.
private struct EntryPill: View {
    let icon: String
    let title: String
    let trailing: String
    let badgeCount: Int
    let onPress: () -> Void

    var body: some View {
        Button(action: onPress) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(PulseTheme.accent)
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                if badgeCount > 0 {
                    Text(badgeCount > 99 ? "99+" : "\(badgeCount)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .frame(minWidth: 17, minHeight: 17)
                        .background(Capsule().fill(PulseTheme.emerald500))
                }
                Spacer(minLength: 0)
                HStack(spacing: 2) {
                    Text(trailing)
                        .font(.system(size: 12))
                        .foregroundStyle(PulseTheme.textTertiary)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PulseTheme.textTertiary)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .background(
                Capsule()
                    .fill(.ultraThinMaterial)
                    .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1)),
            )
        }
        .buttonStyle(PulseButtonStyle())
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .accessibilityLabel("\(title) — \(trailing)")
    }
}

// ─────────────────────────────────────────────────────────────
// Conversation row (spec §7.4)
// ─────────────────────────────────────────────────────────────

private struct ConversationRow: View {
    let row: ChatRowModel
    let viewerId: String
    let archivedContext: Bool
    let entranceIndex: Int?
    let selectMode: Bool
    let selected: Bool
    let onPress: () -> Void
    let onLongPress: () -> Void
    let onPin: () -> Void
    let onArchive: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var entered = false
    @State private var swipeOpen = false
    @State private var swipeOffset: CGFloat = 0
    @State private var dragEngaged = false

    private static let revealWidth: CGFloat = 112
    private static let openThreshold: CGFloat = 56

    private var heatLevel: Int? {
        guard !row.isGroup, row.streakAtRisk == nil, row.streakCount >= 2 else { return nil }
        return row.streakCount >= 10 ? 3 : (row.streakCount >= 5 ? 2 : 1)
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .trailing) {
                swipeTray
                rowBody
                    .offset(x: swipeOffset)
            }
            Rectangle()
                .fill(PulseTheme.hairlineSoft)
                .frame(height: 1)
                .padding(.leading, 64)
        }
        .padding(.horizontal, 8)
        .opacity(entered ? 1 : 0)
        .offset(y: entered ? 0 : 14)
        .onAppear { playEntrance() }
    }

    private func playEntrance() {
        guard !entered else { return }
        guard let index = entranceIndex, !reduceMotion else {
            entered = true
            return
        }
        let delay = Double(min(index, 12)) * 0.028
        withAnimation(.spring(response: 0.32, dampingFraction: 0.9).delay(delay)) {
            entered = true
        }
    }

    // swipe-left glass chips (Pin/Unpin + Archive/Unarchive)
    private var swipeTray: some View {
        HStack(spacing: 6) {
            SwipeActionChip(
                icon: row.isPinned ? "pin.slash" : "pin",
                iconTint: row.isPinned ? PulseTheme.amber500 : PulseTheme.accent,
                label: row.isPinned ? "Unpin" : "Pin",
                action: {
                    closeSwipe()
                    onPin()
                },
            )
            SwipeActionChip(
                icon: row.isArchived ? "archivebox" : "archivebox",
                iconTint: row.isArchived ? PulseTheme.amber500 : PulseTheme.textSecondary,
                label: row.isArchived ? "Unarchive" : "Archive",
                action: {
                    closeSwipe()
                    onArchive()
                },
            )
        }
        .opacity(swipeOffset < -4 && !selectMode ? 1 : 0)
        .allowsHitTesting(swipeOffset < -8 && !selectMode)
    }

    private func closeSwipe() {
        withAnimation(.pulse(.pulseSnappy, reduceMotion: reduceMotion)) {
            swipeOpen = false
            swipeOffset = 0
        }
    }

    private var swipeGesture: some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { value in
                guard !selectMode else { return }
                // Vertical scroll wins until the drag is clearly horizontal.
                if !dragEngaged {
                    guard abs(value.translation.width) > abs(value.translation.height) else { return }
                    dragEngaged = true
                }
                let base: CGFloat = swipeOpen ? -Self.revealWidth : 0
                swipeOffset = min(0, max(-Self.revealWidth, base + value.translation.width))
            }
            .onEnded { value in
                guard dragEngaged || swipeOpen else { return }
                dragEngaged = false
                let base: CGFloat = swipeOpen ? -Self.revealWidth : 0
                let final = base + value.translation.width
                withAnimation(.pulse(.pulseSnappy, reduceMotion: reduceMotion)) {
                    swipeOpen = final <= -Self.openThreshold
                    swipeOffset = swipeOpen ? -Self.revealWidth : 0
                }
            }
    }

    private var rowBody: some View {
        Button(action: onPress) {
            HStack(alignment: .center, spacing: 12) {
                avatarBlock
                VStack(alignment: .leading, spacing: 2) {
                    titleLine
                    previewLine
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(row.isPinned ? PulseTheme.pinnedWash : PulseTheme.rowFill)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(PulseTheme.rowFill)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(PulseTheme.rowRim, lineWidth: 1),
            )
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(RowPressStyle(selectMode: selectMode))
        .scaleEffect(selectMode ? 0.985 : 1)
        .animation(.pulse(.pulseSnappy, reduceMotion: reduceMotion), value: selectMode)
        .onLongPressGesture(minimumDuration: 0.45) {
            guard !selectMode else { return }
            PulseHaptics.tap()
            if archivedContext {
                onLongPress()
            } else {
                onLongPress()
            }
        }
        .simultaneousGesture(swipeGesture)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(row.name), \(row.time), \(row.previewText)")
    }

    // avatar 48pt — presence halo + heat ring + presence dot / squircle group
    private var avatarBlock: some View {
        ZStack {
            if !row.isGroup && row.online {
                PresenceHalo()
            }
            if let level = heatLevel {
                StreakHeatRing(level: level)
            }
            RowAvatar(
                name: row.isGroup ? row.groupTitle : (row.dmName ?? row.name),
                colorName: row.isGroup ? nil : (row.dmColor ?? "emerald"),
                photoPath: row.photo,
                size: 48,
                groupID: row.isGroup ? row.id : nil,
                avatarShape: row.isGroup ? .squircle : .circle,
                showPresence: !row.isGroup,
                online: row.online,
            )
            if selectMode {
                SelectCheckmark(selected: selected)
                    .transition(.scale(scale: 0.4).combined(with: .opacity))
            }
        }
        .frame(width: 48, height: 48)
        .animation(.pulse(.pulseBouncy, reduceMotion: reduceMotion), value: selectMode)
    }

    private var titleLine: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            if row.isPinned {
                Image(systemName: "pin.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(PulseTheme.emerald500)
            }
            Text(row.name)
                .font(.system(size: 15, weight: row.hasUnread ? .semibold : .medium))
                .tracking(-0.2)
                .foregroundStyle(row.hasUnread ? PulseTheme.titleOnWash : PulseTheme.titleOnPanel)
                .lineLimit(1)
            Spacer(minLength: 8)
            streakChip
            Text(row.time)
                .font(.system(size: 11, weight: row.hasUnread ? .semibold : .regular))
                .foregroundStyle(row.hasUnread ? PulseTheme.accent : PulseTheme.textTertiary)
        }
    }

    @ViewBuilder
    private var streakChip: some View {
        // Priority: at-risk > live > lost — never two at once (web R33-b/R37).
        if let risk = row.streakAtRisk, (risk.count ?? 0) >= 2 {
            StreakChipView(icon: "hourglass", text: "ends tonight", lost: false)
        } else if row.streakCount > 0 {
            StreakChipView(icon: "flame", text: "\(row.streakCount)", lost: false)
        } else if let lost = row.streakLost, (lost.count ?? 0) >= 2 {
            StreakChipView(icon: "flame", text: "streak lost", lost: true)
        }
    }

    @ViewBuilder
    private var previewLine: some View {
        HStack(spacing: 6) {
            previewContent
            Spacer(minLength: 8)
            trailingStatus
        }
    }

    @ViewBuilder
    private var previewContent: some View {
        if row.typing {
            HStack(spacing: 6) {
                RowTypingDots()
                Text("typing…")
                    .font(.system(size: 13, weight: .medium).italic())
                    .foregroundStyle(PulseTheme.accent)
            }
            .lineLimit(1)
        } else if let draft = row.draft, !draft.isEmpty {
            HStack(spacing: 4) {
                Image(systemName: "pencil")
                    .font(.system(size: 11))
                    .foregroundStyle(PulseTheme.amber500)
                Text("Draft:")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(PulseTheme.amber600)
                Text(draft)
                    .font(.system(size: 13).italic())
                    .foregroundStyle(PulseTheme.textSecondary)
                    .lineLimit(1)
            }
        } else {
            (Text(row.previewPrefix) + Text(row.previewText).italic(row.previewDeleted))
                .font(.system(size: 13, weight: row.hasUnread ? .medium : .regular))
                .foregroundStyle(PulseTheme.textSecondary)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private var trailingStatus: some View {
        let unreadCount = row.unreadCount
        if row.hasUnread && !row.isMuted {
            if unreadCount > 0 {
                RowUnreadBadge(count: unreadCount)
                    .animation(.pulse(.pulseBouncy, reduceMotion: reduceMotion), value: unreadCount)
            } else {
                Circle()
                    .fill(PulseTheme.emerald500)
                    .frame(width: 12, height: 12)
                    .overlay(Circle().strokeBorder(PulseTheme.badgeRing, lineWidth: 2))
                    .padding(.horizontal, 3)
                    .accessibilityLabel("Marked as unread")
                    .animation(.pulse(.pulseBouncy, reduceMotion: reduceMotion), value: row.manualUnread)
            }
        } else if row.isMuted {
            MutedChip(unreadCount: unreadCount)
        }
    }
}

/// Row press style — slight scale + press overlay, web 0.975 tap.
private struct RowPressStyle: ButtonStyle {
    let selectMode: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay {
                if configuration.isPressed {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(PulseTheme.pressOverlay)
                }
            }
            .scaleEffect(
                configuration.isPressed && !reduceMotion ? (selectMode ? 0.96 : 0.975) : 1,
            )
            .animation(.pulse(.pulseSnappy, reduceMotion: reduceMotion), value: configuration.isPressed)
    }
}

private extension Text {
    func italic(_ active: Bool) -> Text {
        active ? self.italic() : self
    }
}

/// 3 bouncing 3.5pt emerald dots (web typing indicator).
struct RowTypingDots: View {
    @State private var animating = false

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(PulseTheme.emerald500)
                    .frame(width: 3.5, height: 3.5)
                    .offset(y: animating ? -2.5 : 0)
                    .opacity(animating ? 1 : 0.45)
                    .animation(
                        .easeInOut(duration: 0.45)
                            .repeatForever(autoreverses: true)
                            .delay(Double(index) * 0.15),
                        value: animating,
                    )
            }
        }
        .onAppear { animating = true }
        .accessibilityHidden(true)
    }
}

/// Streak chip — amber live count / "ends tonight" / rose lost.
private struct StreakChipView: View {
    let icon: String
    let text: String
    let lost: Bool

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: icon)
                .font(.system(size: 10))
            Text(text)
        }
        .font(.system(size: 10, weight: .bold))
        .foregroundStyle(lost ? PulseTheme.rose400 : PulseTheme.amber600)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(
            Capsule().fill(lost ? PulseTheme.rose500.opacity(0.07) : PulseTheme.amber500.opacity(0.10)),
        )
        .overlay(
            Capsule().strokeBorder(
                lost ? PulseTheme.rose500.opacity(0.20) : PulseTheme.amber500.opacity(0.20),
                lineWidth: 1,
            ),
        )
    }
}

/// Swipe-reveal glass chip — 48pt icon + 9pt label.
private struct SwipeActionChip: View {
    let icon: String
    let iconTint: Color
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(iconTint)
                Text(label)
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(PulseTheme.textSecondary)
            }
            .frame(width: 48, height: 48)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(.ultraThinMaterial),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1),
            )
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(label)
    }
}

/// Telegram-style select check circle over the avatar.
private struct SelectCheckmark: View {
    let selected: Bool

    var body: some View {
        ZStack {
            Circle()
                .fill(selected ? PulseTheme.emerald500 : PulseTheme.zinc(900).opacity(0.35))
                .overlay(
                    Circle().strokeBorder(
                        selected ? Color.white.opacity(0.70) : Color.white.opacity(0.60),
                        lineWidth: 2,
                    ),
                )
            if selected {
                Image(systemName: "checkmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: 24, height: 24)
    }
}

// ─────────────────────────────────────────────────────────────
// Multi-select floating bar (spec §7.5)
// ─────────────────────────────────────────────────────────────

private struct MultiSelectBar: View {
    let count: Int
    let archivePending: Bool
    let mutePending: Bool
    let readPending: Bool
    let onArchive: () -> Void
    let onMute: () -> Void
    let onRead: () -> Void
    let onExit: () -> Void

    var body: some View {
        HStack(spacing: 2) {
            Text("\(count) selected")
                .font(.system(size: 12, weight: .bold).monospacedDigit())
                .foregroundStyle(PulseTheme.textPrimary)
                .padding(.leading, 8)
                .padding(.trailing, 4)
            barAction(icon: "archivebox", pending: archivePending, label: "Archive selected chats", action: onArchive)
            barAction(icon: "bell.slash", pending: mutePending, label: "Mute selected chats for 8 hours", action: onMute)
            barAction(icon: "checkmark.double", pending: readPending, label: "Mark selected chats read", action: onRead)
            Button(action: onExit) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(PulseTheme.textTertiary)
                    .frame(width: 40, height: 40)
                    .contentShape(Circle())
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Exit multi-select")
        }
        .padding(6)
        .background(
            Capsule()
                .fill(.ultraThinMaterial)
                .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1)),
        )
        .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    private func barAction(icon: String, pending: Bool, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if pending {
                    ProgressView()
                        .controlSize(.small)
                        .tint(PulseTheme.textPrimary)
                } else {
                    Image(systemName: icon)
                        .font(.system(size: 15, weight: .medium))
                }
            }
            .foregroundStyle(PulseTheme.textPrimary)
            .frame(width: 40, height: 40)
            .contentShape(Circle())
        }
        .buttonStyle(PulseButtonStyle())
        .disabled(pending)
        .accessibilityLabel(label)
    }
}

// ─────────────────────────────────────────────────────────────
// Search message hit row (spec §10)
// ─────────────────────────────────────────────────────────────

private struct SearchMessageRowView: View {
    let hit: WireSearchMessage
    let query: String
    let onPress: () -> Void

    var body: some View {
        Button(action: onPress) {
            HStack(spacing: 12) {
                ZStack(alignment: .bottomTrailing) {
                    RowAvatar(
                        name: hit.sender?.name ?? "?",
                        colorName: hit.sender?.color,
                        photoPath: hit.sender?.avatar,
                        size: 40,
                    )
                    if hit.isGroup == true {
                        Image(systemName: "person.2")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundStyle(PulseTheme.textSecondary)
                            .frame(width: 16, height: 16)
                            .background(Circle().fill(PulseTheme.chipFill))
                            .overlay(Circle().strokeBorder(PulseTheme.badgeRing, lineWidth: 2))
                            .offset(x: 4, y: 4)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(hit.conversationName ?? "Chat")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        Text(PulseFormat.listStamp(hit.createdAt))
                            .font(.system(size: 11))
                            .foregroundStyle(PulseTheme.textTertiary)
                    }
                    snippet
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("Message in \(hit.conversationName ?? "chat")")
    }

    @ViewBuilder
    private var snippet: some View {
        let deleted = hit.deletedAt != nil
        let content = hit.content ?? ""
        let isFileHit = hit.filePath != nil
        let captionMatches = !content.isEmpty && content.localizedCaseInsensitiveContains(query)
        if deleted {
            Text("Deleted message")
                .font(.system(size: 13).italic())
                .foregroundStyle(PulseTheme.textTertiary)
                .lineLimit(1)
        } else if hit.imagePath != nil && content.isEmpty {
            Text("📷 Photo")
                .font(.system(size: 13))
                .foregroundStyle(PulseTheme.textSecondary)
                .lineLimit(1)
        } else if isFileHit && !captionMatches {
            HighlightedSnippet(
                content: hit.fileName.map { "Document — \($0)" } ?? (content.isEmpty ? "Document" : content),
                query: query,
            )
        } else {
            HighlightedSnippet(content: content, query: query)
        }
    }
}

/// ≤64-char clip window with the match highlighted (emerald mark).
/// Highlighted message snippet shared by the chats search AND the Wave 1
/// in-room search panel (internal so ChatRoomView can reuse the rhythm).
struct HighlightedSnippet: View {
    let content: String
    let query: String

    var body: some View {
        let lower = content.lowercased()
        let q = query.lowercased()
        let idx = q.isEmpty ? -1 : (lower.range(of: q)?.lowerBound).map { lower.distance(from: lower.startIndex, to: $0) } ?? -1
        // (recomputed below — kept simple and total)
        let window = Self.window(content: content, query: q)
        return HStack(spacing: 0) {
            if window.clippedHead {
                Text("…").foregroundStyle(PulseTheme.zinc(300))
            }
            if let match = window.matchRange {
                Text(window.body[window.body.startIndex..<match.lowerBound])
                Text(window.body[match.lowerBound..<match.upperBound])
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(PulseTheme.accent)
                    .background(
                        RoundedRectangle(cornerRadius: 3)
                            .fill(PulseTheme.emerald500.opacity(0.20)),
                    )
                Text(window.body[match.upperBound..<window.body.endIndex])
            } else {
                Text(window.body)
            }
            if window.clippedTail {
                Text("…").foregroundStyle(PulseTheme.zinc(300))
            }
        }
        .font(.system(size: 13))
        .foregroundStyle(PulseTheme.textSecondary)
        .lineLimit(1)
        .accessibilityLabel(content)
        .animation(nil, value: idx)
    }

    private struct Window {
        var body: Substring
        var matchRange: Range<Substring.Index>?
        var clippedHead = false
        var clippedTail = false
    }

    private static func window(content: String, query: String) -> Window {
        let lower = content.lowercased()
        guard let found = lower.range(of: query), !query.isEmpty else {
            // No highlightable hit — show a plain head clip.
            if content.count > 64 {
                let head = content.prefix(64)
                return Window(body: Substring(head), matchRange: nil, clippedTail: true)
            }
            return Window(body: Substring(content), matchRange: nil)
        }
        let idx = lower.distance(from: lower.startIndex, to: found.lowerBound)
        var from = content.startIndex
        var clippedHead = false
        if idx > 28 {
            from = content.index(content.startIndex, offsetBy: idx - 24)
            clippedHead = true
        }
        let matchEnd = content.index(from, offsetBy: min(query.count, content.distance(from: from, to: content.endIndex)))
        var to = content.endIndex
        var clippedTail = false
        if content.distance(from: matchEnd, to: content.endIndex) > 28 {
            to = content.index(matchEnd, offsetBy: 28)
            clippedTail = true
        }
        let body = content[from..<to]
        let localStart = body.index(body.startIndex, offsetBy: idx - content.distance(from: content.startIndex, to: from))
        let localEnd = body.index(localStart, offsetBy: query.count)
        return Window(body: body, matchRange: localStart..<localEnd, clippedHead: clippedHead, clippedTail: clippedTail)
    }
}

// ─────────────────────────────────────────────────────────────
// Skeleton · Empty · Error (spec §11)
// ─────────────────────────────────────────────────────────────

private struct ChatsSkeletonView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                ForEach(0..<6, id: \.self) { _ in
                    RowSkeletonView()
                }
            }
            .padding(.top, 8)
        }
        .accessibilityLabel("Loading conversations")
    }
}

/// Empty state — deep glass card, emerald glow, EmptyChats illustration.
private struct EmptyChatsCard: View {
    let onSayHi: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var entered = false

    var body: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [PulseTheme.emerald500.opacity(0.28), .clear],
                            center: .center, startRadius: 0, endRadius: 80,
                        ),
                    )
                    .frame(width: 160, height: 160)
                    .blur(radius: 6)
                    .offset(y: -60)
                VStack(spacing: 16) {
                    Image("EmptyChats")
                        .resizable()
                        .scaledToFill()
                        .frame(width: 144, height: 144)
                        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 24, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.5), lineWidth: 1),
                        )
                        .shadow(color: PulseTheme.zinc(200).opacity(0.7), radius: 8, y: 4)
                    VStack(spacing: 4) {
                        Text("No conversations yet")
                            .font(.system(size: 16, weight: .semibold))
                            .tracking(-0.2)
                            .foregroundStyle(PulseTheme.titleOnPanel)
                        Text("Your next great chat is one tap away. Find someone and break the ice.")
                            .font(.system(size: 13))
                            .foregroundStyle(PulseTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 240)
                    }
                    Button(action: onSayHi) {
                        HStack(spacing: 6) {
                            Text("Say hi to someone")
                            Image(systemName: "arrow.right")
                        }
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(PulseTheme.accent)
                        .padding(.horizontal, 20)
                        .frame(height: 40)
                        .background(Capsule().fill(.ultraThinMaterial))
                        .overlay(Capsule().strokeBorder(PulseTheme.accent.opacity(0.4), lineWidth: 1))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Say hi to someone — open contacts")
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 32)
            }
        }
        .frame(maxWidth: 300)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(.ultraThinMaterial),
        )
        .overlay(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1),
        )
        .opacity(entered ? 1 : 0)
        .offset(y: entered ? 0 : 14)
        .onAppear {
            withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion)) {
                entered = true
            }
        }
        .padding(.top, 40)
    }
}

private struct ErrorCard: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(PulseTheme.textTertiary)
            Text("Could not reach the gateway")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(PulseTheme.textSecondary)
                .multilineTextAlignment(.center)
                .lineLimit(3)
            Button(action: onRetry) {
                Text("Retry")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(PulseTheme.accent)
                    .padding(.horizontal, 24)
                    .frame(height: 40)
                    .background(Capsule().fill(.ultraThinMaterial))
                    .overlay(Capsule().strokeBorder(PulseTheme.accent.opacity(0.4), lineWidth: 1))
            }
            .buttonStyle(PulseButtonStyle())
        }
        .frame(maxWidth: 300)
        .padding(.horizontal, 24)
        .padding(.vertical, 32)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(.ultraThinMaterial),
        )
        .overlay(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1),
        )
        .padding(.top, 40)
    }
}

// ─────────────────────────────────────────────────────────────
// Row action sheet (spec §8)
// ─────────────────────────────────────────────────────────────

private struct ChatActionSheet: View {
    let conversation: WireConversationSummary
    @ObservedObject var session: PulseSession
    let onChanged: () -> Void

    enum SheetView { case actions, mute }
    @State private var view: SheetView = .actions
    @State private var confirmClear = false
    @State private var exportPending = false
    @State private var clearPending = false
    @State private var shareURL: URL?

    @Environment(\.dismiss) private var dismiss

    private var viewerId: String { session.viewer?.id ?? "" }
    private var typing: Bool {
        !session.typers(in: conversation.id, excluding: session.viewer?.id).isEmpty
    }
    private var title: String {
        conversation.name
            ?? conversation.members.first(where: { $0.id != viewerId })?.name
            ?? conversation.members.first?.name
            ?? "Conversation"
    }
    private var subtitle: String {
        if typing { return "typing…" }
        if let draft = conversation.myDraft, !draft.isEmpty { return "Draft: \(draft)" }
        return "\(conversation.members.count) member\(conversation.members.count == 1 ? "" : "s")"
    }
    private var mutedNow: Bool { conversation.isMutedNow }

    var body: some View {
        VStack(spacing: 4) {
            sheetHeader
            if view == .actions {
                actionRows
            } else {
                muteStrip
            }
        }
        .padding(.top, 20)
        .padding(.horizontal, 14)
        .padding(.bottom, 24)
        .frame(maxWidth: 340)
        .frame(maxWidth: .infinity)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .presentationBackground(.ultraThinMaterial)
        .confirmationDialog(
            "Clear this chat?",
            isPresented: $confirmClear,
            titleVisibility: .visible,
        ) {
            Button("Clear chat", role: .destructive, action: { Task { await clearChat() } })
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your messages will be deleted for everyone — messages from other people stay in the chat. This cannot be undone.")
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let shareURL {
                ActivityShareSheet(items: [shareURL])
            }
        }
        .onAppear { view = .actions }
    }

    private var sheetHeader: some View {
        HStack(spacing: 12) {
            RowAvatar(
                name: title,
                colorName: conversation.members.first(where: { $0.id != viewerId })?.color,
                photoPath: conversation.photo,
                size: 44,
                groupID: conversation.isGroup ? conversation.id : nil,
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(PulseTheme.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.bottom, 10)
    }

    private var actionRows: some View {
        VStack(spacing: 2) {
            sheetRow(
                icon: conversation.isPinned ? "pin.slash" : "pin",
                tint: PulseTheme.textPrimary,
                label: conversation.isPinned ? "Unpin from top" : "Pin to top",
            ) {
                Task { await togglePin() }
            }
            sheetRow(
                icon: conversation.isArchived ? "archivebox" : "archivebox.fill",
                tint: PulseTheme.textPrimary,
                label: conversation.isArchived ? "Unarchive chat" : "Archive chat",
            ) {
                Task { await toggleArchive() }
            }
            sheetRow(
                icon: conversation.manualUnread ? "envelope.open" : "envelope.badge",
                tint: PulseTheme.textPrimary,
                label: conversation.manualUnread ? "Mark as read" : "Mark as unread",
            ) {
                Task { await toggleMarkUnread() }
            }
            if mutedNow {
                sheetRow(icon: "bell", tint: PulseTheme.textPrimary, label: "Unmute notifications") {
                    Task { await setMuted(until: nil) }
                }
            } else {
                sheetRow(icon: "bell.slash", tint: PulseTheme.textPrimary, label: "Mute notifications", trailing: "chevron.right") {
                    withAnimation(.pulse(.pulseSnappy, reduceMotion: false)) { view = .mute }
                }
            }
            Divider().background(PulseTheme.hairlineSoft).padding(.vertical, 6)
            sheetRow(icon: "square.and.arrow.up", tint: PulseTheme.textPrimary, label: "Export chat (.txt)") {
                Task { await exportChat() }
            }
            .disabled(exportPending)
            sheetRow(icon: "trash", tint: PulseTheme.rose500, label: "Clear chat…") {
                confirmClear = true
            }
            .disabled(clearPending)
            Divider().background(PulseTheme.hairlineSoft).padding(.vertical, 6)
            sheetRow(icon: "xmark", tint: PulseTheme.textTertiary, label: "Close") {
                dismiss()
            }
        }
    }

    private var muteStrip: some View {
        VStack(spacing: 2) {
            HStack(spacing: 6) {
                mutePreset("8 hours", until: "8h")
                mutePreset("1 week", until: "1w")
                mutePreset("Always", until: "always")
            }
            sheetRow(icon: "chevron.left", tint: PulseTheme.textPrimary, label: "Back") {
                withAnimation(.pulse(.pulseSnappy, reduceMotion: false)) { view = .actions }
            }
        }
    }

    private func mutePreset(_ label: String, until: String) -> some View {
        Button {
            PulseHaptics.tap()
            Task { await setMuted(until: until) }
        } label: {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.textPrimary)
                .frame(maxWidth: .infinity, minHeight: 36)
                .background(Capsule().fill(PulseTheme.chipFill))
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("Mute for \(label)")
    }

    private func sheetRow(
        icon: String,
        tint: Color,
        label: String,
        trailing: String? = nil,
        action: @escaping () -> Void,
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(tint)
                    .frame(width: 22)
                Text(label)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(tint)
                Spacer(minLength: 0)
                if let trailing {
                    Image(systemName: trailing)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PulseTheme.textTertiary)
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 42)
            .contentShape(Rectangle())
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel(label)
    }

    // ── actions (exact verbs + bodies per spec §14) ─────────

    private func togglePin() async {
        PulseHaptics.tap()
        dismiss()
        do {
            try await session.api.togglePin(conversationId: conversation.id)
            await MainActor.run { onChanged() }
        } catch {
            await MainActor.run { session.toasts.show("Could not update the pin") }
        }
    }

    private func toggleArchive() async {
        PulseHaptics.tap()
        dismiss()
        do {
            try await session.api.archive(conversationId: conversation.id, archived: !conversation.isArchived)
            await MainActor.run {
                session.toasts.show(conversation.isArchived ? "Chat unarchived" : "Chat archived")
                onChanged()
            }
        } catch {
            await MainActor.run { session.toasts.show("Could not update the archive") }
        }
    }

    private func toggleMarkUnread() async {
        PulseHaptics.tap()
        dismiss()
        do {
            try await session.api.markUnread(conversationId: conversation.id, on: !conversation.manualUnread)
            await MainActor.run { onChanged() }
        } catch {
            await MainActor.run { session.toasts.show("Could not update the unread flag") }
        }
    }

    private func setMuted(until preset: String?) async {
        PulseHaptics.tap()
        dismiss()
        do {
            try await session.api.setMuted(conversationId: conversation.id, until: preset)
            await MainActor.run {
                if let preset {
                    if preset == "always" {
                        session.toasts.show("Muted — always")
                    } else {
                        let until = Date().addingTimeInterval(preset == "1w" ? 7 * 86_400 : 8 * 3_600)
                        session.toasts.show("Muted until \(PulseFormat.listStamp(until))")
                    }
                } else {
                    session.toasts.show("Notifications unmuted")
                }
                onChanged()
            }
        } catch {
            await MainActor.run { session.toasts.show("Could not update the mute") }
        }
    }

    /// Export the real history to a .txt and hand it to the share sheet.
    private func exportChat() async {
        PulseHaptics.tap()
        exportPending = true
        defer { exportPending = false }
        guard let history = await session.api.fullHistory(conversationId: conversation.id) else {
            await MainActor.run { session.toasts.show("Could not export this chat") }
            return
        }
        let text = Self.transcript(for: conversation, viewerId: viewerId, messages: history)
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(Self.exportFileName(title: title))
        do {
            try text.write(to: fileURL, atomically: true, encoding: .utf8)
        } catch {
            await MainActor.run { session.toasts.show("Could not export this chat") }
            return
        }
        await MainActor.run {
            session.toasts.show("Chat exported — Saved \(fileURL.lastPathComponent)")
            shareURL = fileURL
        }
    }

    /// Clear chat — soft-delete MY OWN non-deleted messages, sequentially.
    private func clearChat() async {
        clearPending = true
        defer { clearPending = false }
        guard let history = await session.api.fullHistory(conversationId: conversation.id) else {
            await MainActor.run { session.toasts.show("Could not clear this chat") }
            return
        }
        let mine = history.filter { $0.senderId == viewerId && $0.deletedAt == nil }
        var cleared = 0
        for message in mine {
            // Keep going — clear as many of my own messages as the server allows.
            if (try? await session.api.deleteOwnMessage(id: message.id)) != nil {
                cleared += 1
            }
        }
        await MainActor.run {
            if cleared == 0 {
                session.toasts.show("Nothing to clear — none of your messages are left in this chat.")
            } else {
                session.toasts.show("Cleared \(cleared) message\(cleared == 1 ? "" : "s")")
            }
            dismiss()
            onChanged()
        }
    }

    // ── transcript building (web chats-actions.tsx parity) ──

    static func transcript(for conversation: WireConversationSummary, viewerId: String, messages: [WireChatMessage]) -> String {
        let title = conversation.name
            ?? conversation.members.first(where: { $0.id != viewerId })?.name
            ?? conversation.members.first?.name
            ?? "Conversation"
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        var lines = [
            "Pulse — chat export",
            "Chat: \(title)",
            "Exported: \(formatter.string(from: Date()))",
            "Messages: \(messages.count)",
            "──────────────────────",
            "",
        ]
        for message in messages {
            let who = message.anon == true && message.anonAlias != nil
                ? (message.anonAlias ?? "Unknown")
                : (message.sender?.name ?? "Unknown")
            lines.append("[\(formatter.string(from: PulseFormat.date(message.createdAt) ?? Date()))] \(who): \(transcriptBody(message))")
        }
        return lines.joined(separator: "\n")
    }

    private static func transcriptBody(_ message: WireChatMessage) -> String {
        if message.deletedAt != nil { return "[message deleted]" }
        let text = message.content.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        if !text.isEmpty { return text }
        if message.imagePath != nil { return "[photo]" }
        if message.audioPath != nil {
            if let durationMs = message.durationMs, durationMs > 0 {
                return "[voice note — \(max(1, Int((durationMs / 1000).rounded())))s]"
            }
            return "[voice note]"
        }
        return "[message]"
    }

    private static func exportFileName(title: String) -> String {
        let slug = title.lowercased()
            .map { $0.isLetter || $0.isNumber ? $0 : "-" }
            .reduce(into: "") { $0.append($1) }
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        let day = DateFormatter()
        day.dateFormat = "yyyy-MM-dd"
        return "pulse-\(slug.isEmpty ? "chat" : String(slug.prefix(32)))-\(day.string(from: Date())).txt"
    }
}

// ─────────────────────────────────────────────────────────────
// Archived sub-page (spec §9)
// ─────────────────────────────────────────────────────────────

private struct ArchivedPageView: View {
    @ObservedObject var session: PulseSession
    let rows: [ChatRowModel]
    let loading: Bool
    let onPress: (ChatRowModel) -> Void
    let onLongPress: (ChatRowModel) -> Void
    let onPin: (ChatRowModel) -> Void
    let onArchive: (ChatRowModel) -> Void
    let onBack: () -> Void

    @State private var sheetTarget: SheetTarget?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .top) {
            PulseTheme.pageWash
                .ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 0) {
                    if loading && rows.isEmpty {
                        ForEach(0..<3, id: \.self) { _ in
                            RowSkeletonView()
                        }
                    } else if rows.isEmpty {
                        archivedEmpty
                    } else {
                        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                            ConversationRow(
                                row: row,
                                viewerId: session.viewer?.id ?? "",
                                archivedContext: true,
                                entranceIndex: index,
                                selectMode: false,
                                selected: false,
                                onPress: { onPress(row) },
                                onLongPress: { sheetTarget = SheetTarget(conversation: row.conv) },
                                onPin: { onPin(row) },
                                onArchive: { onArchive(row) },
                            )
                        }
                    }
                    Color.clear.frame(height: 32)
                }
                .padding(.top, 8)
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) { header }
        .sheet(item: $sheetTarget) { target in
            ChatActionSheet(
                conversation: target.conversation,
                session: session,
                onChanged: {},
            )
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(PulseTheme.textPrimary)
                    .frame(width: 40, height: 40)
                    .background(Circle().fill(PulseTheme.glassFill))
                    .overlay(Circle().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Back to chats")
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 8) {
                    Text("Archived")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(PulseTheme.titleOnWash)
                    Text("\(rows.count)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(PulseTheme.accent)
                        .padding(.horizontal, 6)
                        .frame(minWidth: 18, minHeight: 18)
                        .background(Capsule().fill(PulseTheme.emerald500.opacity(0.15)))
                }
                Text("Muted here — a new message moves a chat back to your inbox")
                    .font(.system(size: 11))
                    .foregroundStyle(PulseTheme.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
        .overlay(alignment: .bottom) {
            Rectangle().fill(PulseTheme.hairlinePanel).frame(height: 1)
        }
    }

    private var archivedEmpty: some View {
        VStack(spacing: 12) {
            Image(systemName: "archivebox")
                .font(.system(size: 26, weight: .light))
                .foregroundStyle(PulseTheme.textTertiary)
                .frame(width: 64, height: 64)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.ultraThinMaterial))
            Text("No archived chats")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Text("Swipe left on a chat and tap Archive — it waits here. A new message brings it straight back to your inbox.")
                .font(.system(size: 13))
                .foregroundStyle(PulseTheme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 260)
        }
        .padding(.top, 90)
        .frame(maxWidth: .infinity)
    }
}

// ─────────────────────────────────────────────────────────────
// Chats state holder (UDF) — transport in PulseSession, this
// owns home list state + the 6s poll + search + multi-select.
// ─────────────────────────────────────────────────────────────
@MainActor
final class ChatsViewModel: ObservableObject {
    enum Phase: Equatable {
        case idle, loading, loaded, failed(String)
    }

    // list data
    @Published private(set) var summaries: [WireConversationSummary] = []
    @Published private(set) var phase: Phase = .idle
    /// Offline rehydration — cached rows published FIRST (before the network
    /// answers), replaced once live summaries arrive.
    @Published private(set) var cachedRows: [ChatRowModel] = []
    /// Local composer drafts — a non-empty local draft wins over myDraft.
    @Published private(set) var localDrafts: [String: String] = [:]

    // search
    @Published var searching = false
    @Published var searchFocused = false
    @Published var query = ""
    @Published private(set) var deferredQuery = ""
    @Published private(set) var serverHits: [WireSearchMessage]?
    @Published private(set) var serverTotal: Int?
    @Published private(set) var serverSearching = false

    // side queries (nil = unreachable → honest degradation)
    @Published private(set) var storyGroups: [WireStoryGroup] = []
    @Published private(set) var folders: [WireFolder]?
    @Published private(set) var mentionCount: Int?

    // folder rail
    @Published var activeFolderId: String?
    @Published var entranceGeneration = 0

    // multi-select
    @Published private(set) var selectMode = false
    @Published private(set) var selection: Set<String> = []

    // overlays
    @Published var sheet: SheetTarget?
    @Published var archivedOpen = false

    // pending flags
    @Published private(set) var selfCreating = false
    @Published var openSelfOnNext = false
    @Published private(set) var batchArchivePending = false
    @Published private(set) var batchMutePending = false
    @Published private(set) var batchReadPending = false

    /// Entrance stagger plays only on the first list render.
    var entranceOn = true

    private var pollTask: Task<Void, Never>?
    private var searchDebounceTask: Task<Void, Never>?
    private var observing = false
    private var cancellables: Set<AnyCancellable> = []

    // ── derived rows ─────────────────────────────────────────

    func row(for conv: WireConversationSummary, session: PulseSession) -> ChatRowModel {
        let viewerId = session.viewer?.id ?? ""
        let last = conv.lastMessage
        let other = conv.isGroup ? nil : conv.members.first(where: { $0.id != viewerId }) ?? conv.members.first
        let groupName = conv.name?.trimmingCharacters(in: .whitespaces).isEmpty == false
            ? conv.name!
            : conv.members.filter { $0.id != viewerId }.map(\.name).joined(separator: ", ")
        let displayName = conv.isGroup
            ? groupName
            // Cached rows carry no members — fall back to the stored title.
            : (other?.name ?? conv.name ?? "You")

        // Preview (web conversationPreview parity).
        var previewText = "No messages yet"
        var previewPrefix = ""
        var previewDeleted = false
        if let last {
            if last.deletedAt != nil {
                previewText = "🚫 message deleted"
                previewDeleted = true
            } else {
                let collapsed = last.content.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                    .trimmingCharacters(in: .whitespaces)
                let isImage = last.imagePath != nil && collapsed.isEmpty
                let isAudio = !isImage && last.audioPath != nil && collapsed.isEmpty
                let isFile = !isImage && !isAudio && last.kind == "file" && last.filePath != nil
                previewText = isImage
                    ? "📷 Photo"
                    : isAudio
                        ? "🎤 Voice message"
                        : isFile
                            ? "Document — \(last.fileName ?? "file")"
                            : collapsed
                let mine = last.senderId == viewerId
                let replyArrow = last.replyTo != nil ? "↩ " : ""
                if !previewText.isEmpty {
                    if mine {
                        previewPrefix = "\(replyArrow)You: "
                    } else if conv.isGroup {
                        previewPrefix = "\(replyArrow)\(last.sender?.name ?? ""): "
                    } else {
                        previewPrefix = replyArrow
                    }
                }
            }
        }

        let draft = Self.draftPreview(local: localDrafts[conv.id], server: conv.myDraft)
        return ChatRowModel(
            conv: conv,
            id: conv.id,
            name: displayName,
            time: PulseFormat.listStamp(last?.createdAt ?? conv.updatedAt),
            previewText: previewText,
            previewPrefix: previewPrefix,
            previewDeleted: previewDeleted,
            draft: draft,
            unreadCount: conv.unreadCount ?? 0,
            manualUnread: conv.manualUnread,
            isGroup: conv.isGroup,
            dmName: other?.name,
            dmColor: other?.color,
            groupTitle: groupName.isEmpty ? "Group" : groupName,
            online: !conv.isGroup && other.map { session.onlineUserIds.contains($0.id) } == true,
            isPinned: conv.isPinned,
            isMuted: conv.isMutedNow,
            isArchived: !(conv.archivedAt ?? "").isEmpty,
            typing: !session.typers(in: conv.id, excluding: session.viewer?.id).isEmpty,
            streakCount: conv.myStreak?.count ?? 0,
            streakAtRisk: conv.deadStreak,
            streakLost: conv.lostStreak,
            photo: conv.photo ?? other?.avatar,
            memberCount: conv.members.count,
            isSelf: conv.isSelf == true,
        )
    }

    var rows: [ChatRowModel] {
        if summaries.isEmpty && !cachedRows.isEmpty {
            return cachedRows
        }
        guard let session = observedSession else {
            return summaries.filter { $0.isSelf != true }.map { row(for: $0, session: PulseSession()) }
        }
        return summaries.filter { $0.isSelf != true }.map { row(for: $0, session: session) }
    }

    var activeRows: [ChatRowModel] { rows.filter { !$0.isArchived } }
    var archivedRows: [ChatRowModel] { rows.filter { $0.isArchived } }

    var filteredRows: [ChatRowModel] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return rows }
        return rows.filter { $0.matches(query: needle) }
    }

    var folderFiltered: [ChatRowModel] {
        let active = activeRows
        switch prefsFilter {
        case .unread: return active.filter { $0.unreadCount > 0 }
        case .groups: return active.filter { $0.isGroup }
        default: return active
        }
    }

    var visibleRows: [ChatRowModel] {
        if let folderId = activeFolderId {
            let ids = Set(folders?.first(where: { $0.id == folderId })?.conversationIds ?? [])
            return folderFiltered.filter { ids.contains($0.id) }
        }
        return folderFiltered
    }

    var selfConv: WireConversationSummary? {
        summaries.first(where: { $0.isSelf == true })
    }

    var unreadTotal: Int {
        activeRows.reduce(0) { $0 + $1.unreadCount }
    }

    var archivedUnread: Int {
        archivedRows.reduce(0) { $0 + $1.unreadCount }
    }

    var subscribedChannelCount: Int {
        rows.filter { $0.isGroup && ($0.conv.broadcastMode == true) }.count
    }

    var showNoMatches: Bool {
        let deferred = deferredQuery
        if deferred.count < 2 { return true }
        if serverSearching { return false }
        if let hits = serverHits { return hits.isEmpty }
        return true // unreachable server → local misses are final
    }

    var folderCounts: [String: Int] {
        guard let folders else { return [:] }
        let activeIds = Set(activeRows.map(\.id))
        var counts: [String: Int] = [:]
        for folder in folders {
            counts[folder.id] = (folder.conversationIds ?? []).filter { activeIds.contains($0) }.count
        }
        return counts
    }

    /// Filter preference lives on prefs (persisted, OBSERVED — the Wave 0 fix
    /// for the stale filter: chips re-filter the list without a tab remount).
    @Published private(set) var prefsFilter: PulsePrefs.ChatsFilter = .all

    private weak var observedSession: PulseSession?
    private weak var boundPrefs: PulsePrefs?
    private var storeBinding = false
    private var storeAttached = false

    /// Web `local ?? conv.myDraft` parity — the local draft wins when present.
    static func draftPreview(local: String?, server: String?) -> String? {
        if let local, !local.isEmpty { return local }
        if let server, !server.isEmpty { return server }
        return nil
    }

    // ── lifecycle ────────────────────────────────────────────

    func start(session: PulseSession, prefs: PulsePrefs) {
        observedSession = session
        bind(prefs: prefs)
        observe(session: session)
        bindStore(session)
        reloadDrafts(session: session)
        guard pollTask == nil else { return }
        guard session.viewer != nil else { return }
        Task { await refresh(session: session, force: summaries.isEmpty) }
        Task { await loadSideQueries(session: session) }
        pollTask = Task { [weak self] in
            // 6s REST poll while the home is visible (web refetchInterval).
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                guard !Task.isCancelled, let self else { return }
                await self.refreshQuiet(session: session)
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// Wave 0 — bind the filter to the LIVE prefs object (the old code read a
    /// throwaway PulsePrefs() in start(), so chips never re-filtered the list).
    private func bind(prefs: PulsePrefs) {
        guard boundPrefs !== prefs else { return }
        boundPrefs = prefs
        prefsFilter = prefs.chatsFilter
        prefs.$chatsFilter
            .removeDuplicates()
            .sink { [weak self] filter in
                self?.prefsFilter = filter
            }
            .store(in: &cancellables)
    }

    /// Wave 0 — offline rehydration: publish cached conversations the moment
    /// the store exists (before the first network answer lands).
    private func bindStore(_ session: PulseSession) {
        guard !storeBinding else { return }
        storeBinding = true
        session.$store
            .compactMap { $0 }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] store in
                self?.attachStore(store)
            }
            .store(in: &cancellables)
    }

    private func attachStore(_ store: PulseStore) {
        guard !storeAttached else { return }
        storeAttached = true
        store.observeConversations()
            .receive(on: DispatchQueue.main)
            // GRDB observation failure degrades to "no cached rows yet"; the
            // network answer below stays authoritative (summaries.isEmpty guard).
            .replaceError(with: [])
            .sink { [weak self] rows in
                guard let self, self.summaries.isEmpty else { return }
                let session = self.observedSession ?? PulseSession()
                self.cachedRows = rows.map { row in
                    let summary = WireConversationSummary(cached: row)
                    return self.row(for: summary, session: session)
                }
            }
            .store(in: &cancellables)
    }

    private func reloadDrafts(session: PulseSession) {
        guard let store = session.store else { return }
        localDrafts = store.allDrafts()
    }

    private func observe(session: PulseSession) {
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
                guard let self else { return }
                Task { await self.refreshQuiet(session: session) }
            }
            .store(in: &cancellables)

        session.$inboxRefreshTick
            .dropFirst()
            .debounce(for: .milliseconds(250), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                Task { await self.refreshQuiet(session: session) }
            }
            .store(in: &cancellables)

        // Wave 0 — the message:* envelope family + conversation:updated bump
        // the session's realtimeRefreshTick; the inbox re-fetches on it.
        session.$realtimeRefreshTick
            .dropFirst()
            .debounce(for: .milliseconds(400), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                Task { await self.refreshQuiet(session: session) }
            }
            .store(in: &cancellables)
    }

    // ── refresh ──────────────────────────────────────────────

    func refresh(session: PulseSession, force: Bool = false) async {
        if force || summaries.isEmpty { phase = phase == .loaded ? phase : .loading }
        do {
            let fresh = try await session.api.conversations()
            summaries = fresh
            phase = .loaded
            entranceOn = false
            session.dockUnreadCount = unreadTotal
            if let store = session.store {
                try? store.upsert(conversations: fresh.map { $0.toDomain() })
            }
        } catch {
            if summaries.isEmpty {
                phase = .failed(Self.describe(error))
            } else {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    func refreshQuiet(session: PulseSession) async {
        do {
            let fresh = try await session.api.conversations()
            summaries = fresh
            phase = .loaded
            session.dockUnreadCount = unreadTotal
        } catch {
            // Poll failures stay silent — the list keeps its cached rows.
        }
        reloadDrafts(session: session)
    }

    private func loadSideQueries(session: PulseSession) async {
        // Stories — unreachable today → row degrades to My status only.
        if let storiesPage = await session.api.stories() {
            storyGroups = storiesPage.groups ?? []
        } else {
            storyGroups = []
        }
        // Folders — nil keeps the rail at All + manage.
        folders = await session.api.folders()
        // Mentions — nil → pill shows 0.
        mentionCount = await session.api.mentionsCount() ?? 0
    }

    func selectFolder(_ id: String?) {
        withAnimation(.pulse(.pulseSoft, reduceMotion: false)) {
            activeFolderId = id
            entranceGeneration += 1
        }
    }

    // ── search ───────────────────────────────────────────────

    func searchTextChanged(_ value: String, session: PulseSession) {
        query = value
        searchDebounceTask?.cancel()
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            deferredQuery = ""
            serverHits = nil
            serverTotal = nil
            return
        }
        searchDebounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard !Task.isCancelled, let self else { return }
            self.deferredQuery = trimmed
            if trimmed.count >= 2 {
                self.serverSearching = true
                let page = await session.api.searchMessages(trimmed)
                self.serverSearching = false
                self.serverHits = page?.messages ?? nil
                self.serverTotal = page?.total
            }
        }
    }

    func closeSearch() {
        searchDebounceTask?.cancel()
        searching = false
        searchFocused = false
        query = ""
        deferredQuery = ""
        serverHits = nil
        serverTotal = nil
    }

    // ── multi-select ─────────────────────────────────────────

    func enterSelect(_ conversationId: String) {
        PulseHaptics.tap()
        selectMode = true
        selection = [conversationId]
    }

    func exitSelect() {
        selectMode = false
        selection = []
    }

    func toggleSelect(_ conversationId: String) {
        PulseHaptics.tap()
        if selection.contains(conversationId) {
            selection.remove(conversationId)
        } else {
            selection.insert(conversationId)
        }
        if selection.isEmpty { selectMode = false }
    }

    private func updateSummaries(_ transform: (WireConversationSummary) -> WireConversationSummary, ids: Set<String>) {
        summaries = summaries.map { ids.contains($0.id) ? transform($0) : $0 }
    }

    // ── single-row actions (optimistic, web copies) ──────────

    func togglePin(_ conv: WireConversationSummary, session: PulseSession) async {
        let wasPinned = conv.isPinned
        updateSummaries({ $0.withPinnedAt(wasPinned ? nil : "optimistic") }, ids: [conv.id])
        do {
            try await session.api.togglePin(conversationId: conv.id)
            session.toasts.show(wasPinned ? "Unpinned" : "Pinned to top")
        } catch {
            updateSummaries({ $0.withPinnedAt(conv.pinnedAt) }, ids: [conv.id])
            session.toasts.show("Could not update the pin")
        }
        await refreshQuiet(session: session)
    }

    func setMuted(_ conv: WireConversationSummary, _ until: String?, session: PulseSession) async {
        let offsets: [String: TimeInterval] = [
            "8h": 8 * 3600,
            "1w": 7 * 86_400,
            "always": 50 * 365 * 86_400,
        ]
        let optimistic: String?
        if let until {
            let offset = offsets[until] ?? 8 * 3600
            optimistic = ISO8601DateFormatter().string(from: Date().addingTimeInterval(offset))
        } else {
            optimistic = nil
        }
        updateSummaries({ $0.withMutedUntil(optimistic) }, ids: [conv.id])
        do {
            try await session.api.setMuted(conversationId: conv.id, until: until)
            if let until {
                if until == "always" {
                    session.toasts.show("Muted — always")
                } else {
                    let stamp = Date().addingTimeInterval(until == "1w" ? 7 * 86_400 : 8 * 3_600)
                    session.toasts.show("Muted until \(PulseFormat.listStamp(stamp))")
                }
            } else {
                session.toasts.show("Notifications unmuted")
            }
        } catch {
            updateSummaries({ $0.withMutedUntil(conv.mutedUntil) }, ids: [conv.id])
            session.toasts.show("Could not update the mute")
        }
        await refreshQuiet(session: session)
    }

    func setArchived(_ conv: WireConversationSummary, _ archived: Bool, session: PulseSession) async {
        updateSummaries({ $0.withArchived(archived ? ISO8601DateFormatter().string(from: Date()) : nil) }, ids: [conv.id])
        do {
            try await session.api.archive(conversationId: conv.id, archived: archived)
            PulseHaptics.success()
            session.toasts.show(archived ? "Chat archived" : "Chat unarchived")
        } catch {
            updateSummaries({ $0.withArchived(conv.archivedAt) }, ids: [conv.id])
            session.toasts.show("Could not update the archive")
        }
        await refreshQuiet(session: session)
    }

    func toggleMarkUnread(_ conv: WireConversationSummary, session: PulseSession) async {
        let next = !conv.manualUnread
        updateSummaries({ $0.withManualUnread(next) }, ids: [conv.id])
        do {
            try await session.api.markUnread(conversationId: conv.id, on: next)
            session.toasts.show(next ? "Marked as unread" : "Marked as read")
        } catch {
            updateSummaries({ $0.withManualUnread(conv.manualUnread) }, ids: [conv.id])
            session.toasts.show("Could not update the unread flag")
        }
        await refreshQuiet(session: session)
    }

    // ── batch actions (spec §7.5) ────────────────────────────

    private var selectedConvs: [WireConversationSummary] {
        summaries.filter { selection.contains($0.id) }
    }

    func batchArchive(session: PulseSession) async {
        let convs = selectedConvs
        guard !convs.isEmpty else { return }
        batchArchivePending = true
        defer { batchArchivePending = false }
        let ids = Set(convs.map(\.id))
        let stamp = ISO8601DateFormatter().string(from: Date())
        updateSummaries({ $0.withArchived(stamp) }, ids: ids)
        var archived = 0
        for conv in convs {
            // Same PATCH /archive the sheet + swipe chips use.
            if (try? await session.api.archive(conversationId: conv.id, archived: true)) != nil {
                archived += 1
            }
        }
        if archived == convs.count {
            PulseHaptics.success()
            session.toasts.show("Archived \(archived) chat\(archived == 1 ? "" : "s")")
            exitSelect()
        } else {
            session.toasts.show("Could not archive the selected chats")
        }
        await refreshQuiet(session: session)
    }

    func batchMute8h(session: PulseSession) async {
        let convs = selectedConvs
        guard !convs.isEmpty else { return }
        batchMutePending = true
        defer { batchMutePending = false }
        let ids = Set(convs.map(\.id))
        let stamp = ISO8601DateFormatter().string(from: Date().addingTimeInterval(8 * 3600))
        updateSummaries({ $0.withMutedUntil(stamp) }, ids: ids)
        var muted = 0
        for conv in convs {
            if (try? await session.api.setMuted(conversationId: conv.id, until: "8h")) != nil {
                muted += 1
            }
        }
        if muted > 0 {
            PulseHaptics.success()
            session.toasts.show("Muted \(muted) chat\(muted == 1 ? "" : "s") for 8 hours")
        } else {
            session.toasts.show("Could not mute the selected chats")
        }
        await refreshQuiet(session: session)
    }

    func batchMarkRead(session: PulseSession) async {
        let convs = selectedConvs
        guard !convs.isEmpty else { return }
        batchReadPending = true
        defer { batchReadPending = false }
        let ids = Set(convs.map(\.id))
        updateSummaries({ $0.withUnreadCount(0) }, ids: ids)
        var read = 0
        var failed = 0
        for conv in convs {
            // POST /read { userId } — per-chat failures counted honestly.
            if (try? await session.api.markRead(conversationId: conv.id)) != nil {
                read += 1
            } else {
                failed += 1
            }
        }
        if read > 0 {
            PulseHaptics.success()
            session.toasts.show("\(read) chat\(read == 1 ? "" : "s") marked as read")
        }
        if failed > 0 {
            session.toasts.show("\(failed) chat\(failed == 1 ? "" : "s") could not be marked read — try again")
        } else {
            exitSelect()
        }
        await refreshQuiet(session: session)
    }

    // ── Note to Self ─────────────────────────────────────────

    func pressSelf(session: PulseSession) async {
        PulseHaptics.tap()
        if let conv = selfConv {
            openSelfOnNext = true
            sheet = nil
            _ = conv // consumed by the view through selfConv onChange
            return
        }
        selfCreating = true
        defer { selfCreating = false }
        do {
            let conv = try await session.api.createSelfChat()
            await refreshQuiet(session: session)
            openSelfOnNext = true
        } catch {
            session.toasts.show("Could not open Note to Self")
        }
    }

    static func describe(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message, !message.isEmpty {
            return message
        }
        return "The gateway is unreachable."
    }
}

// ─────────────────────────────────────────────────────────────
// Transitions
// ─────────────────────────────────────────────────────────────

extension AnyTransition {
    /// Folder change replays the list — opacity + 10pt rise, soft spring.
    static var folderReplay: AnyTransition {
        .asymmetric(
            insertion: .opacity.combined(with: .offset(y: 10)),
            removal: .opacity,
        )
    }
}
