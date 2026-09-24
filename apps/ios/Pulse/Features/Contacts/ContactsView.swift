import SwiftUI

/// Contacts — the native directory: every identity on this Pulse with live
/// presence rings, one-tap DM creation (server-side dedupe), and native
/// safety actions (block / report) via confirmationDialog.
struct ContactsView: View {
    @ObservedObject var session: PulseSession

    @State private var viewModel = ContactsViewModel()
    @State private var reportTarget: WireUser?
    // Wave 3 — call history surface.
    @State private var callsHistoryOpen = false
    // Wave 6 — user-page push (F-CP-03) + the explicit add-contact page
    // (F-CP-02) + the safety-verified badge cache (F-CP-08).
    @State private var path = NavigationPath()
    @State private var addContactOpen = false
    @StateObject private var safetyBadges = PulseSafetyBadgeCache.shared
    // R5-A Item 2 — the A–Z index rail (web contacts-tab.tsx:141-172):
    // active letter highlight + drag-live-jump tracking.
    @State private var activeLetter: String?
    @State private var railDragging = false

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if viewModel.loading && viewModel.users.isEmpty {
                    ProgressView("Loading contacts…")
                } else if let error = viewModel.errorText, viewModel.users.isEmpty {
                    ContentUnavailableCompat(
                        title: "Contacts unavailable",
                        systemImage: "person.2.slash",
                        note: error,
                    )
                } else {
                    list
                }
            }
            .navigationTitle("Contacts")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 2) {
                        Button {
                            addContactOpen = true
                        } label: {
                            Image(systemName: "person.badge.plus")
                        }
                        .accessibilityLabel("Add contact")
                        Button {
                            callsHistoryOpen = true
                        } label: {
                            Image(systemName: "phone.badge.clock")
                        }
                        .accessibilityLabel("Call history")
                    }
                }
            }
            .searchable(text: $viewModel.query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Find people")
            .refreshable { await viewModel.load(api: session.api) }
            .task { await viewModel.load(api: session.api) }
            .navigationDestination(for: UserRoute.self) { route in
                UserPageView(
                    initial: viewModel.users.first(where: { $0.id == route.userId }),
                    userId: route.userId,
                    session: session,
                )
            }
            // Wave 6 deep links — the session hands a user id over (same
            // bridge as pendingOpenRoom); consume on arrival.
            .onReceive(session.$pendingUserRoute) { route in
                guard let route else { return }
                session.consumePendingUserRoute()
                path.append(route)
            }
        }
        .onAppear { viewModel.observe(session: session) }
        .sheet(isPresented: $callsHistoryOpen) {
            CallsHistoryView(session: session)
        }
        .sheet(isPresented: $addContactOpen) {
            NavigationStack {
                AddContactView(session: session) { user in
                    addContactOpen = false
                    path.append(UserRoute(userId: user.id, name: user.name))
                } onOpenRoom: { conversation in
                    addContactOpen = false
                    session.requestOpenRoom(conversation)
                }
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        // Wave 6 — the full report panel (verbatim intro/reasons/hint) replaces
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

    /// R5-A Item 2 — search REPLACES the A–Z sections with flat results
    /// (web contacts-add/search behavior; the rail hides while filtering).
    private var isFiltering: Bool {
        !viewModel.query.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// A–Z sections from the display name (case-insensitive, "#" last) —
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
        Text("\(session.onlineUserIds.count) online · \(filtered.count) people — tap a row for the full profile; stats, safety number, block and report live there.")
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
            // trackActiveLetter parity — the honest List-native analog).
            if !railDragging { activeLetter = section.letter }
        }
    }

    /// R5-A Item 2 — the right-edge A–Z rail (web :330-373): tap a letter →
    /// scroll to that section; DRAG along the rail → live jump letter by
    /// letter. Hidden while filtering and when there's one section or fewer
    /// (web hides at letters.length ≤ 1).
    @ViewBuilder
    private func indexRail(_ proxy: ScrollViewProxy) -> some View {
        let letters = sections.map(\.letter)
        if !isFiltering && letters.count > 1 {
            // Letters are fixed 17pt rows + 6pt top/bottom padding — the
            // drag math uses that height directly (no geometry needed).
            VStack(spacing: 0) {
                ForEach(letters, id: \.self) { letter in
                    Text(letter)
                        .font(.system(size: 9, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(activeLetter == letter ? PulseTheme.emerald : Color.secondary)
                        .frame(width: 20, height: 17)
                        .background {
                            // The active letter's emerald bubble (web
                            // layoutId="contacts-rail-bubble" analog).
                            if activeLetter == letter {
                                Capsule().fill(PulseTheme.emerald.opacity(0.18))
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { jump(to: letter, proxy: proxy) }
                }
            }
            .padding(.vertical, 6)
            .background(Capsule().fill(.ultraThinMaterial))
            // Gesture scope = the capsule strip ONLY — the full-width
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
            onOpenProfile: {
                // Wave 6 — rows push the FULL user page (stats,
                // block/unblock, report, safety). Chat stays a button.
                path.append(UserRoute(userId: user.id, name: user.name))
            },
            onMessage: {
                viewModel.openDM(user, session: session)
            },
        )
        // The rail strip stays usable — rows keep clear of the right edge
        // (the phone-app index-rail pattern).
        .padding(.trailing, 18)
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                viewModel.call(user, session: session)
            } label: {
                Label("Call", systemImage: "phone.fill")
            }
            .tint(PulseTheme.emerald)
            // Wave R1-W2D — real video call entry (wire kind 'video').
            Button {
                viewModel.callVideo(user, session: session)
            } label: {
                Label("Video", systemImage: "video.fill")
            }
            .tint(PulseTheme.emerald)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                Task { await viewModel.block(user, session: session) }
            } label: {
                Label("Block", systemImage: "hand.raised.fill")
            }
            Button {
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
    /// Wave 6 — the pair's safety number is verified (F-CP-08 badge).
    var safetyVerified: Bool = false
    var onOpenProfile: () -> Void = {}
    let onMessage: () -> Void

    @State private var opening = false

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
                        // F-CP-08 — verified sparkle (account badge OR the
                        // pair's safety number is marked verified).
                        Image(systemName: "checkmark.seal.fill")
                            .font(.caption)
                            .foregroundStyle(PulseTheme.emerald)
                            .accessibilityLabel("Verified")
                    }
                    if isViewer {
                        Text("You")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(PulseTheme.emerald)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(PulseTheme.emerald.opacity(0.14)))
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
                opening = true
                onMessage()
            } label: {
                if opening {
                    ProgressView().controlSize(.small)
                } else {
                    Label("Chat", systemImage: "bubble.left.fill")
                        .font(.footnote.weight(.semibold))
                }
            }
            .tint(PulseTheme.emerald)
            .buttonStyle(.bordered)
            .disabled(isViewer)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(user.name), double-tap Chat to message, or open the profile from the row")
    }

    /// F-CP-09 — status emoji + text rows: the status line wins (glyph +
    /// text), otherwise the @handle, else the neutral stamp.
    private var subtitle: String {
        if let emoji = user.statusEmoji, !emoji.isEmpty {
            let glyph = pulseStatusGlyphDisplay(emoji)
            if let text = user.statusText, !text.isEmpty {
                return "\(glyph) \(text)"
            }
            return glyph
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

    private var observing = false

    func observe(session: PulseSession) {
        guard !observing else { return }
        observing = true
    }

    func load(api: PulseAPIClient) async {
        loading = users.isEmpty
        defer { loading = false }
        do {
            users = try await api.users()
            errorText = nil
        } catch {
            if users.isEmpty { errorText = ChatsViewModel.describe(error) }
        }
    }

    func openDM(_ user: WireUser, session: PulseSession) {
        guard user.id != session.viewer?.id else { return }
        PulseHaptics.tap()
        Task { [weak self] in
            do {
                let conversation = try await session.api.createConversation(memberIds: [user.id], isGroup: false)
                _ = conversation
                session.noteInboxChanged() // Chats tab re-fetches and shows the row
                PulseHaptics.success()
            } catch {
                PulseHaptics.warning()
                errorText = ChatsViewModel.describe(error)
            }
            self?.loading = false
        }
    }

    /// Wave 3 — one-tap voice call. The DM is resolved first (server dedupes
    /// pairs), then the engine opens the ring; the engine owns the mic
    /// permission prompt + the honest denied state itself.
    func call(_ user: WireUser, session: PulseSession) {
        guard user.id != session.viewer?.id else { return }
        guard let engine = session.callEngine else {
            errorText = "Calls aren't ready yet — try again in a moment."
            return
        }
        PulseHaptics.tap()
        Task { [weak self] in
            do {
                let conversation = try await session.api.createConversation(memberIds: [user.id], isGroup: false)
                let peer = CallPeer(id: user.id, name: user.name, color: user.color, avatar: user.avatar)
                engine.startOutgoing(to: peer, conversationId: conversation.id)
            } catch {
                PulseHaptics.warning()
                errorText = ChatsViewModel.describe(error)
            }
            self?.loading = false
        }
    }

    /// Wave R1-W2D — one-tap VIDEO call (wire kind 'video'). The engine
    /// resolves the camera capability first: no usable camera degrades to a
    /// voice call with an honest toast (web acquireMedia parity).
    func callVideo(_ user: WireUser, session: PulseSession) {
        guard user.id != session.viewer?.id else { return }
        guard let engine = session.callEngine else {
            errorText = "Calls aren't ready yet — try again in a moment."
            return
        }
        PulseHaptics.tap()
        Task { [weak self] in
            do {
                let conversation = try await session.api.createConversation(memberIds: [user.id], isGroup: false)
                let peer = CallPeer(id: user.id, name: user.name, color: user.color, avatar: user.avatar)
                engine.startOutgoing(to: peer, conversationId: conversation.id, kind: .video)
            } catch {
                PulseHaptics.warning()
                errorText = ChatsViewModel.describe(error)
            }
            self?.loading = false
        }
    }

    func block(_ user: WireUser, session: PulseSession) async {
        do {
            try await session.api.block(userId: user.id)
            PulseHaptics.success()
            // Wave 6 — verbatim pair-toast (UserPage parity).
            session.toasts.show("Blocked \(user.name)")
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }
}
