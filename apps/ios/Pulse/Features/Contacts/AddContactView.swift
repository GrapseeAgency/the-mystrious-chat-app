import SwiftUI

/// Wave 6 - the add-contact page (F-CP-02, contacts-add-page.tsx parity):
/// search-as-you-type over the live people list (client filter on
/// GET /api/users - name / @handle / about), presence dots, rows open the
/// user page, and a Message action that creates/dedupes a DM then opens it.
struct AddContactView: View {
    @ObservedObject var session: PulseSession
    /// Hands the created/looked-up user to the parent for a UserPage push.
    var onOpenUser: (WireUser) -> Void = { _ in }
    var onOpenRoom: (WireConversationSummary) -> Void = { _ in }

    @State private var users: [WireUser] = []
    @State private var loading = false
    @State private var errorText: String?
    @State private var query = ""
    @State private var pendingDm: String?
    @State private var openingTask: Task<Void, Never>?
    @State private var directoryRetry = 0
    @State private var directoryTicket = UUID()
    @State private var directoryOwner: UUID?

    private var others: [WireUser] {
        users.filter { $0.id != session.viewer?.id }
    }

    private var results: [WireUser] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return others }
        return others.filter {
            $0.name.lowercased().contains(q)
                || ($0.username ?? "").lowercased().contains(q)
                || ($0.about ?? "").lowercased().contains(q)
        }
    }

    var body: some View {
        Group {
            if loading && users.isEmpty {
                ProgressView("Loading people…")
            } else if let error = errorText, users.isEmpty {
                VStack {
                    ContentUnavailableCompat(title: "People unavailable", systemImage: "wifi.exclamationmark", note: error)
                    Button("Retry") { directoryRetry += 1 }
                }
            } else {
                list
            }
        }
        .background(PulseTheme.pageWash.ignoresSafeArea())
        .navigationTitle("Add contact")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search name or @handle")
        .task(id: "\(session.sessionGeneration)-\(directoryRetry)") { await load() }
        .onDisappear { openingTask?.cancel() }
        .onChange(of: session.sessionGeneration) { _, _ in openingTask?.cancel() }
    }

    private var list: some View {
        List {
            if others.isEmpty {
                Section {
                    VStack(spacing: 8) {
                        Image(systemName: "person.badge.plus")
                            .font(.system(size: 30, weight: .light))
                            .foregroundStyle(PulseTheme.textTertiary)
                        Text("No one to add yet")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(PulseTheme.textPrimary)
                        Text("New Pulse members show up here the moment they join.")
                            .font(.system(size: 12))
                            .foregroundStyle(PulseTheme.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                }
            } else if results.isEmpty {
                Section {
                    VStack(spacing: 6) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 26, weight: .light))
                            .foregroundStyle(PulseTheme.textTertiary)
                        Text("No matches")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(PulseTheme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                }
            } else {
                Section {
                    ForEach(results) { person in
                        row(person)
                    }
                } footer: {
                    Text("Tap a row for the full profile, or Message to jump straight into the chat.")
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    private func row(_ person: WireUser) -> some View {
        // Separate buttons: nesting Message inside the profile button could
        // dispatch both destinations for one tap.
        HStack(spacing: 12) {
            Button {
                openingTask?.cancel()
                PulseHaptics.tap()
                onOpenUser(person)
            } label: {
                HStack(spacing: 12) {
                    PulseAvatar(name: person.name, color: PulseTheme.color(named: person.color),
                        photoURL: PulseTheme.photoURL(person.avatar), online: session.isOnline(person.id), size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(person.name).font(.body.weight(.medium)).foregroundStyle(PulseTheme.titleOnPanel)
                            if person.verified == true {
                                Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(PulseTheme.emerald)
                            }
                        }
                        if let handle = person.username {
                            Text("@\(handle)").font(.system(size: 12, design: .monospaced)).foregroundStyle(PulseTheme.textTertiary)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens \(person.name)'s profile")
            Button { startDm(person) } label: {
                if pendingDm == person.id { ProgressView().controlSize(.small) }
                else { Label("Message", systemImage: "bubble.left.fill").font(.footnote.weight(.semibold)) }
            }
            .buttonStyle(.bordered)
            .tint(PulseTheme.emerald)
            .disabled(pendingDm != nil)
        }
    }

    private func load() async {
        let api = session.api, epoch = session.sessionGeneration, ticket = UUID()
        directoryTicket = ticket
        if directoryOwner != epoch { directoryOwner = epoch; users = []; errorText = nil }
        loading = users.isEmpty
        defer { if directoryTicket == ticket { loading = false } }
        do {
            let fresh = try await api.users()
            guard !Task.isCancelled, session.sessionGeneration == epoch, directoryTicket == ticket else { return }
            users = fresh
            errorText = nil
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch, directoryTicket == ticket else { return }
            if users.isEmpty { errorText = ChatsViewModel.describe(error) }
        }
    }

    private func startDm(_ person: WireUser) {
        guard pendingDm == nil, let viewer = session.viewer else { return }
        session.noteRoomNavigationIntent()
        let api = session.api, epoch = session.sessionGeneration, navigation = session.roomNavigationGeneration
        pendingDm = person.id
        openingTask = Task { @MainActor in
            defer { pendingDm = nil }
            do {
                let conv = try await PulseConversationNavigation.create(viewerId: viewer.id,
                    memberIds: [person.id], isGroup: false) {
                    try await api.createConversation(memberIds: [person.id], isGroup: false)
                }
                guard !Task.isCancelled, session.sessionGeneration == epoch,
                      session.roomNavigationGeneration == navigation else { return }
                PulseHaptics.success()
                session.noteInboxChanged()
                onOpenRoom(conv)
            } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch,
                      session.roomNavigationGeneration == navigation else { return }
                session.toasts.show(ChatsViewModel.describe(error))
            }
        }
    }
}
