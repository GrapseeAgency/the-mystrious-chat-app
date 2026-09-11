import SwiftUI

/// Contacts — the native directory: every identity on this Pulse with live
/// presence rings, one-tap DM creation (server-side dedupe), and native
/// safety actions (block / report) via confirmationDialog.
struct ContactsView: View {
    @ObservedObject var session: PulseSession

    @State private var viewModel = ContactsViewModel()
    @State private var reportTarget: WireUser?
    @State private var reportReason = ""
    @State private var reportDetails = ""
    @State private var reportPending = false
    // Wave 3 — call history surface.
    @State private var callsHistoryOpen = false

    var body: some View {
        NavigationStack {
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
                    Button {
                        callsHistoryOpen = true
                    } label: {
                        Image(systemName: "phone.badge.clock")
                    }
                    .accessibilityLabel("Call history")
                }
            }
            .searchable(text: $viewModel.query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Find people")
            .refreshable { await viewModel.load(api: session.api) }
            .task { await viewModel.load(api: session.api) }
        }
        .onAppear { viewModel.observe(session: session) }
        .sheet(isPresented: $callsHistoryOpen) {
            CallsHistoryView(session: session)
        }
        .sheet(item: $reportTarget) { target in
            ReportSheet(
                user: target,
                reason: $reportReason,
                details: $reportDetails,
                pending: reportPending,
                onSubmit: { Task { await submitReport(target) } },
            )
        }
    }

    /// Wave 0 — the report dialog is now WIRED to the real endpoint
    /// (POST /api/users/{id}/report via PulseAPIClient.report) with an
    /// honest success/failure toast.
    private func submitReport(_ user: WireUser) async {
        guard !reportPending else { return }
        let reason = reportReason.trimmingCharacters(in: .whitespaces)
        guard !reason.isEmpty else {
            session.toasts.show("Pick a reason first")
            return
        }
        reportPending = true
        defer { reportPending = false }
        let details = reportDetails.trimmingCharacters(in: .whitespaces)
        do {
            try await session.api.report(userId: user.id, reason: reason, details: details.isEmpty ? nil : details)
            PulseHaptics.success()
            session.toasts.show("Report sent — our team will review")
            reportTarget = nil
            reportReason = ""
            reportDetails = ""
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
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
                    ) {
                        viewModel.openDM(user, session: session)
                    }
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
                Text("Swipe for safety actions. Opening a chat reuses the existing DM — the server dedupes pairs.")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }
}

private struct ReportSheet: View {
    let user: WireUser
    @Binding var reason: String
    @Binding var details: String
    let pending: Bool
    let onSubmit: () -> Void

    @Environment(\.dismiss) private var dismiss

    /// Wire reasons — the exact values POST /api/users/[id]/report accepts.
    private static let options: [(label: String, wire: String)] = [
        ("Spam or scam", "spam"),
        ("Harassment", "harassment"),
        ("Inappropriate content", "inappropriate"),
        ("Impersonation", "impersonation"),
        ("Other", "other"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section("Reporting @\(user.username ?? user.name)") {
                    Picker("Reason", selection: $reason) {
                        Text("Select a reason").tag("")
                        ForEach(Self.options, id: \.wire) { option in
                            Text(option.label).tag(option.wire)
                        }
                    }
                    TextField("Details (optional)", text: $details, axis: .vertical)
                        .lineLimit(2...5)
                }
                Section {
                    Button {
                        onSubmit()
                    } label: {
                        if pending {
                            ProgressView().frame(maxWidth: .infinity)
                        } else {
                            Text("Send report")
                                .frame(maxWidth: .infinity)
                                .font(.body.weight(.semibold))
                        }
                    }
                    .disabled(reason.isEmpty || pending)
                } footer: {
                    Text("Reports go to the moderation team. Blocking stays separate — use the red swipe action.")
                }
            }
            .navigationTitle("Report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
            .interactiveDismissDisabled(pending)
        }
    }
}

private struct ContactRow: View {
    let user: WireUser
    let online: Bool
    let isViewer: Bool
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
                    if user.verified == true {
                        Image(systemName: "checkmark.seal.fill")
                            .font(.caption)
                            .foregroundStyle(PulseTheme.emerald)
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
                Text(user.statusText ?? user.username.map { "@\($0)" } ?? "On Pulse")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
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
        } catch {
            errorText = ChatsViewModel.describe(error)
        }
    }
}
