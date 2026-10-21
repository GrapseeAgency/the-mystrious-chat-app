import SwiftUI

/// Contacts - the native directory: every identity on this Pulse with live
/// presence rings, one-tap DM creation (server-side dedupe), and native
/// safety actions (block / report) via confirmationDialog.
struct ContactsView: View {
    @ObservedObject var session: PulseSession
    var onOpenRoom: (WireConversationSummary) -> Void

    @StateObject private var viewModel = ContactsViewModel()
    @State private var reportTarget: WireUser?
    @State private var directoryRetry = 0
    // Wave 3 - call history surface.
    @State private var callsHistoryOpen = false
    // Wave 6 - user-page push (F-CP-03) + the explicit add-contact page
    // (F-CP-02) + the safety-verified badge cache (F-CP-08).
    @State private var path = NavigationPath()
    @State private var addContactOpen = false
    @StateObject private var safetyBadges = PulseSafetyBadgeCache.shared
    // R5-A Item 2 - the A–Z index rail (web contacts-tab.tsx:141-172):
    // active letter highlight + drag-live-jump tracking.
    @State private var activeLetter: String?
    @State private var railDragging = false

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if viewModel.loading && viewModel.users.isEmpty {
                    ProgressView("Loading contacts…")
                } else if let error = viewModel.errorText, viewModel.users.isEmpty {
                    VStack {
                        ContentUnavailableCompat(
                            title: "Contacts unavailable",
                            systemImage: "person.2.slash",
                            note: error,
                        )
                        Button("Retry") { directoryRetry += 1 }
                    }
                } else {
                    list
                }
            }
            .background(
                // PULSE EMBER (EMB-I): sunset ground, subtree pinned dark.
                Rectangle().fill(PulseTheme.emberBackdrop).ignoresSafeArea()
            )
            .environment(\.colorScheme, .dark)
            .tint(.white)
            .navigationTitle("Contacts")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 2) {
                        Button {
                            viewModel.cancelOpening()
                            addContactOpen = true
                        } label: {
                            Image(systemName: "person.badge.plus")
                        }
                        .accessibilityLabel("Add contact")
                        Button {
                            viewModel.cancelOpening()
                            callsHistoryOpen = true
                        } label: {
                            Image(systemName: "phone.badge.clock")
                        }
                        .accessibilityLabel("Call history")
                    }
                }
            }
            .searchable(text: $viewModel.query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Find people")
            .refreshable { await viewModel.load(session: session) }
            .task(id: "\(session.sessionGeneration)-\(directoryRetry)") { await viewModel.load(session: session) }
            .navigationDestination(for: UserRoute.self) { route in
                UserPageView(
                    initial: viewModel.users.first(where: { $0.id == route.userId }),
                    userId: route.userId,
                    session: session,
                )
            }
            // Wave 6 deep links - the session hands a user id over (same
            // bridge as pendingOpenRoom); consume on arrival.
            .onReceive(session.$pendingUserRoute) { route in
                guard let route else { return }
                session.consumePendingUserRoute()
                viewModel.cancelOpening()
                path.append(route)
            }
        }
        .onDisappear { viewModel.cancelOpening() }
        .onChange(of: session.sessionGeneration) { _, _ in
            viewModel.cancelOpening()
            path = NavigationPath()
        }
        .onChange(of: callsHistoryOpen) { _, open in if open { viewModel.cancelOpening() } }
        .onChange(of: addContactOpen) { _, open in if open { viewModel.cancelOpening() } }
        .onChange(of: reportTarget?.id) { _, id in if id != nil { viewModel.cancelOpening() } }
        .sheet(isPresented: $callsHistoryOpen) {
            // R2-D - web calls-page parity: tapping a row closes the history
            // and reopens the chat through the RootView linked-room bridge
            // (the same path pulse://room takes - fetch → Chats tab → open).
            CallsHistoryView(session: session, onOpenConversation: { conversationId in
                callsHistoryOpen = false
                session.pendingLinkedRoomId = conversationId
            })
        }
        .sheet(isPresented: $addContactOpen) {
            NavigationStack {
                AddContactView(session: session) { user in
                    addContactOpen = false
                    path.append(UserRoute(userId: user.id, name: user.name))
                } onOpenRoom: { conversation in
                    addContactOpen = false
                    onOpenRoom(conversation)
                }
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        // Wave 6 - the full report panel (verbatim intro/reasons/hint) replaces
        // the Wave-0 form; the verdict toasts live in the panel itself.
        .sheet(item: $reportTarget) { target in
            ReportPanelView(reported: target, session: session) {}
        }
    }

    private var filtered: [WireUser] {
        let needle = viewModel.query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return viewModel.users }
        return viewModel.users.filter {
            $0.name.lowercased().contains(needle) || ($0.username ?? "").lowercased().contains(needle)
        }
    }

    /// R5-A Item 2 - search REPLACES the A–Z sections with flat results
    /// (web contacts-add/search behavior; the rail hides while filtering).
    private var isFiltering: Bool {
        !viewModel.query.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// A–Z sections from the display name (case-insensitive, "#" last) -
    /// the pure grouping kernel lives in PulseAZIndex (tested).
    private var sections: [PulseAZIndex.Section<WireUser>] {
        PulseAZIndex.sections(filtered, nameOf: { $0.name })
    }

    private var list: some View {
        ScrollViewReader { proxy in
            List {
                if isFiltering {
                    Section {
                        ForEach(filtered) { user in
                            contactRow(user)
                        }
                    } footer: {
                        listFooter
                    }
                } else {
                    ForEach(sections, id: \.letter) { section in
                        Section {
                            ForEach(section.items) { user in
                                contactRow(user)
                                    // Scroll anchor: the first row of each
                                    // section carries the rail's jump id.
                                    .id(user.id == section.items.first?.id ? "az-\(section.letter)" : user.id)
                            }
                        } header: {
                            sectionHeader(section)
                        }
                    }
                    Section {
                    } footer: {
                        listFooter
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .overlay(alignment: .trailing) {
                indexRail(proxy)
            }
        }
    }

    private var listFooter: some View {
        Text("\(session.onlineUserIds.count) online · \(filtered.count) people - tap a row for the full profile; stats, safety number, block and report live there.")
            .font(.caption)
            .foregroundStyle(.secondary)
    }

    /// Sticky glass letter pill (web contacts-tab.tsx:301-309: letter +
    /// people count). The .plain list style pins it while the section
    /// scrolls, matching the web sticky header.
    private func sectionHeader(_ section: PulseAZIndex.Section<WireUser>) -> some View {
        HStack(spacing: 6) {
            Text(section.letter)
                .font(.caption.weight(.bold))
                .tracking(1)
            Text("\(section.items.count)")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 12)
        .padding(.vertical, 3)
        .background(Capsule().fill(.ultraThinMaterial))
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            // Live active-letter tracking while scrolling (web
            // trackActiveLetter parity - the honest List-native analog).
            if !railDragging { activeLetter = section.letter }
        }
    }

    /// R5-A Item 2 - the right-edge A–Z rail (web :330-373): tap a letter →
    /// scroll to that section; DRAG along the rail → live jump letter by
    /// letter. Hidden while filtering and when there's one section or fewer
    /// (web hides at letters.length ≤ 1).
    @ViewBuilder
    private func indexRail(_ proxy: ScrollViewProxy) -> some View {
        let letters = sections.map(\.letter)
        if !isFiltering && letters.count > 1 {
            // Letters are fixed 17pt rows + 6pt top/bottom padding - the
            // drag math uses that height directly (no geometry needed).
            VStack(spacing: 0) {
                ForEach(letters, id: \.self) { letter in
                    Text(letter)
                        .font(.system(size: 9, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(activeLetter == letter ? PulseTheme.emberOnline : Color.secondary)
                        .frame(width: 20, height: 17)
                        .background {
                            // The active letter's emerald bubble (web
                            // layoutId="contacts-rail-bubble" analog).
                            if activeLetter == letter {
                                Capsule().fill(PulseTheme.emberOnline.opacity(0.18))
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { jump(to: letter, proxy: proxy) }
                }
            }
            .padding(.vertical, 6)
            .background(Capsule().fill(.ultraThinMaterial))
            // Gesture scope = the capsule strip ONLY - the full-width
            // frames below are pure layout and must stay swipe/tap
            // transparent to the rows beneath.
            .contentShape(Rectangle())
            .gesture(railDrag(letters: letters, railHeight: CGFloat(letters.count) * 17 + 12, proxy: proxy))
            .frame(maxHeight: .infinity, alignment: .center)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.trailing, 2)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Contact index")
        }
    }

    private func jump(to letter: String, proxy: ScrollViewProxy) {
        PulseHaptics.tap()
        activeLetter = letter
        withAnimation(.easeOut(duration: 0.18)) {
            proxy.scrollTo("az-\(letter)", anchor: .top)
        }
    }

    /// Drag along the rail → live jump: the touch Y position maps onto the
    /// letter stack (web kinetic rail parity), with a tap haptic per new
    /// letter and the matching section scrolled into view immediately.
    private func railDrag(letters: [String], railHeight: CGFloat, proxy: ScrollViewProxy) -> some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { value in
                guard !letters.isEmpty, railHeight > 0 else { return }
                railDragging = true
                let fraction = min(max(value.location.y / railHeight, 0), 0.999)
                let index = Int(fraction * CGFloat(letters.count))
                let letter = letters[index]
                if letter != activeLetter {
                    jump(to: letter, proxy: proxy)
                }
            }
            .onEnded { _ in
                railDragging = false
            }
    }

    private func contactRow(_ user: WireUser) -> some View {
        ContactRow(
            user: user,
            online: session.isOnline(user.id),
            isViewer: user.id == session.viewer?.id,
            safetyVerified: safetyBadges.isVerified(user.id),
            opening: viewModel.openingPeerId == user.id,
            actionPending: viewModel.openingPeerId != nil,
            onOpenProfile: {
                // Wave 6 - rows push the FULL user page (stats,
                // block/unblock, report, safety). Chat stays a button.
                viewModel.cancelOpening()
                path.append(UserRoute(userId: user.id, name: user.name))
            },
            onMessage: {
                viewModel.openDM(user, session: session, onOpenRoom: onOpenRoom)
            },
        )
        // The rail strip stays usable - rows keep clear of the right edge
        // (the phone-app index-rail pattern).
        .padding(.trailing, 18)
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                viewModel.call(user, session: session)
            } label: {
                Label("Call", systemImage: "phone.fill")
            }
            .tint(.white)
            // Wave R1-W2D - real video call entry (wire kind 'video').
            Button {
                viewModel.callVideo(user, session: session)
            } label: {
                Label("Video", systemImage: "video.fill")
            }
            .tint(.white)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                viewModel.cancelOpening()
                Task { await viewModel.block(user, session: session) }
            } label: {
                Label("Block", systemImage: "hand.raised.fill")
            }
            Button {
                viewModel.cancelOpening()
                reportTarget = user
            } label: {
                Label("Report", systemImage: "flag.fill")
            }
            .tint(.orange)
        }
    }
}

private struct ContactRow: View {
    let user: WireUser
    let online: Bool
    let isViewer: Bool
    /// Wave 6 - the pair's safety number is verified (F-CP-08 badge).
    var safetyVerified: Bool = false
    var opening = false
    var actionPending = false
    var onOpenProfile: () -> Void = {}
    let onMessage: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            PulseAvatar(
                name: user.name,
                color: PulseTheme.color(named: user.color),
                photoURL: PulseTheme.photoURL(user.avatar),
                online: online,
                size: 44,
            )
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(user.name).font(.body.weight(.semibold))
                    if user.verified == true || safetyVerified {
                        // F-CP-08 - verified sparkle (account badge OR the
                        // pair's safety number is marked verified).
                        Image(systemName: "checkmark.seal.fill")
                            .font(.caption)
                            .foregroundStyle(PulseTheme.emberOnline)
                            .accessibilityLabel("Verified")
                    }
                    if isViewer {
                        Text("You")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(PulseTheme.emberOnline)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(PulseTheme.emberOnline.opacity(0.14)))
                    }
                }
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture {
                PulseHaptics.tap()
                onOpenProfile()
            }
            Button {
                onMessage()
            } label: {
                if opening {
                    ProgressView().controlSize(.small)
                } else {
                    Label("Chat", systemImage: "bubble.left.fill")
                        .font(.footnote.weight(.semibold))
                }
            }
            .tint(.white)
            .buttonStyle(.bordered)
            .disabled(isViewer || actionPending)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(user.name), double-tap Chat to message, or open the profile from the row")
    }

    /// F-CP-09 - status rows: the stored statusEmoji is an icon ID; the row
    /// speaks its human label (web status-glyph LABEL_BY_ID), never a raw emoji.
    private var subtitle: String {
        if let emoji = user.statusEmoji, !emoji.isEmpty {
            let id = PulseStatusIconId.normalize(emoji) ?? PulseStatusIconId.fallback
            if let text = user.statusText, !text.isEmpty {
                return "\(id.label) \u{00B7} \(text)"
            }
            return id.label
        }
        if let text = user.statusText, !text.isEmpty { return text }
        return user.username.map { "@\($0)" } ?? "On Pulse"
    }
}

/// Contacts state holder (UDF).
@MainActor
final class ContactsViewModel: ObservableObject {
    @Published private(set) var users: [WireUser] = []
    @Published private(set) var loading = false
    @Published private(set) var errorText: String?
    @Published var query = ""

    @Published private(set) var openingPeerId: String?
    private var directoryRevision: UInt = 0
    private var directoryOwner: UUID?
    private var openingRevision: UInt = 0
    private var openingTask: Task<Void, Never>?

    func load(session: PulseSession) async {
        let api = session.api, epoch = session.sessionGeneration
        if directoryOwner != epoch {
            directoryOwner = epoch
            users = []
            errorText = nil
            query = ""
        }
        directoryRevision &+= 1
        let revision = directoryRevision
        loading = users.isEmpty
        defer { if directoryRevision == revision { loading = false } }
        do {
            let fresh = try await api.users()
            guard !Task.isCancelled, directoryRevision == revision, session.sessionGeneration == epoch else { return }
            users = fresh
            errorText = nil
        } catch {
            guard !Task.isCancelled, directoryRevision == revision, session.sessionGeneration == epoch else { return }
            if users.isEmpty { errorText = ChatsViewModel.describe(error) }
            else { session.toasts.show(ChatsViewModel.describe(error)) }
        }
    }

    func cancelOpening() {
        openingRevision &+= 1
        openingTask?.cancel()
        openingTask = nil
        openingPeerId = nil
    }

    func openDM(_ user: WireUser, session: PulseSession, onOpenRoom: @escaping (WireConversationSummary) -> Void) {
        open(user, session: session, kind: nil, onOpenRoom: onOpenRoom)
    }

    func call(_ user: WireUser, session: PulseSession) {
        open(user, session: session, kind: .voice, onOpenRoom: { _ in })
    }

    func callVideo(_ user: WireUser, session: PulseSession) {
        open(user, session: session, kind: .video, onOpenRoom: { _ in })
    }

    private func open(_ user: WireUser, session: PulseSession, kind: CallKind?,
                      onOpenRoom: @escaping (WireConversationSummary) -> Void) {
        guard openingPeerId == nil, let viewer = session.viewer, user.id != viewer.id else { return }
        let api = session.api, epoch = session.sessionGeneration, engine = session.callEngine
        if kind != nil && engine == nil {
            session.toasts.show("Calls aren't ready yet - try again in a moment.")
            return
        }
        openingRevision &+= 1
        let revision = openingRevision
        openingPeerId = user.id
        PulseHaptics.tap()
        openingTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if openingRevision == revision { openingPeerId = nil; openingTask = nil }
            }
            do {
                let conversation = try await PulseConversationNavigation.create(viewerId: viewer.id,
                    memberIds: [user.id], isGroup: false) {
                    try await api.createConversation(memberIds: [user.id], isGroup: false)
                }
                guard !Task.isCancelled, openingRevision == revision, session.sessionGeneration == epoch else { return }
                session.noteInboxChanged()
                if let kind {
                    let peer = CallPeer(id: user.id, name: user.name, color: user.color, avatar: user.avatar)
                    engine?.startOutgoing(to: peer, conversationId: conversation.id, kind: kind)
                } else {
                    PulseHaptics.success()
                    onOpenRoom(conversation)
                }
            } catch {
                guard !Task.isCancelled, openingRevision == revision, session.sessionGeneration == epoch else { return }
                PulseHaptics.warning()
                session.toasts.show(ChatsViewModel.describe(error))
            }
        }
    }

    func block(_ user: WireUser, session: PulseSession) async {
        let api = session.api, epoch = session.sessionGeneration
        do {
            try await api.block(userId: user.id)
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            PulseHaptics.success()
            // Wave 6 - verbatim pair-toast (UserPage parity).
            session.toasts.show("Blocked \(user.name)")
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }
}
