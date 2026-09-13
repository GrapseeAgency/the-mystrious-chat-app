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

    private var list: some View {
        List {
            Section {
                ForEach(filtered) { user in
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
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        Button {
                            viewModel.call(user, session: session)
                        } label: {
                            Label("Call", systemImage: "phone.fill")
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
            } header: {
                Text("\(session.onlineUserIds.count) online · \(filtered.count) people")
            } footer: {
                Text("Tap a row for the full profile — stats, safety number, block and report live there.")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
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
