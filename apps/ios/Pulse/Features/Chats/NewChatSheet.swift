import SwiftUI

/// The dock compose button's real surface — pick a contact for an instant DM
/// or switch to Group mode (name + multi-select). Every list row is a live
/// identity from GET /api/users (web NewChatSheet parity); creation goes
/// through POST /api/conversations (the server folds the creator into the
/// member set, so we send only the picked ids).
struct NewChatSheet: View {
    @ObservedObject var session: PulseSession
    var onCreated: (WireConversationSummary) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Mode: String, CaseIterable { case direct = "Direct", group = "Group" }

    @State private var phase: Phase = .loading
    @State private var users: [WireUser] = []
    @State private var query = ""
    @State private var mode: Mode = .direct
    @State private var picked: Set<String> = []
    @State private var groupName = ""
    @State private var creating = false

    private enum Phase { case loading, ready, failed(String) }

    private var filtered: [WireUser] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return users }
        return users.filter {
            $0.name.lowercased().contains(q.lowercased())
                || ($0.username ?? "").lowercased().contains(q.lowercased())
        }
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()

                VStack(spacing: 0) {
                    modeSegment
                        .padding(.horizontal, 16)
                        .padding(.top, 4)
                    searchField
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)

                    switch phase {
                    case .loading:
                        Spacer()
                        ProgressView().controlSize(.large)
                        Spacer()
                    case .failed(let message):
                        Spacer()
                        ContentUnavailableCompat(title: "Can't load contacts", systemImage: "wifi.exclamationmark", note: message)
                        Spacer()
                    case .ready:
                        if filtered.isEmpty {
                            Spacer()
                            ContentUnavailableCompat(
                                title: query.isEmpty ? "No contacts yet" : "No matches",
                                systemImage: "person.2",
                                note: query.isEmpty
                                    ? "Other Pulse identities appear here the moment they exist — invite someone to get going."
                                    : "Nobody matches \"\(query)\".",
                            )
                            Spacer()
                        } else {
                            roster
                        }
                    }

                    if mode == .group { groupComposer }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("New chat").font(.headline)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task { await load() }
    }

    // ── sections ─────────────────────────────────────────────

    private var modeSegment: some View {
        Picker("Mode", selection: $mode) {
            ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
        }
        .pickerStyle(.segmented)
        .tint(PulseTheme.emerald)
        .onChange(of: mode) { _, new in
            PulseHaptics.tap()
            if new == .direct { picked.removeAll() }
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("Search name or @handle", text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                }
            }
        }
        .padding(10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(PulseTheme.zinc(200).opacity(0.6), lineWidth: 1)
        )
    }

    private var roster: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(filtered) { user in
                    row(user)
                }
            }
            .padding(.bottom, mode == .group ? 120 : 24)
        }
    }

    private func row(_ user: WireUser) -> some View {
        let isPicked = picked.contains(user.id)
        let online = session.isOnline(user.id)
        return Button {
            PulseHaptics.tap()
            switch mode {
            case .direct:
                startDirect(user)
            case .group:
                if isPicked { picked.remove(user.id) } else { picked.insert(user.id) }
            }
        } label: {
            HStack(spacing: 12) {
                PulseAvatar(name: user.name, color: PulseTheme.color(named: user.color), photoURL: user.avatar.flatMap(URL.init(string:)), online: online, size: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Text(user.name)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(PulseTheme.titleOnWash)
                        .lineLimit(1)
                    if let handle = user.username {
                        Text("@\(handle)")
                            .font(.system(size: 12.5, weight: .medium, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if mode == .group {
                    Image(systemName: isPicked ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(isPicked ? PulseTheme.emerald : PulseTheme.zinc(300))
                        .animation(reduceMotion ? nil : .pulse(.pulseSnappy, reduceMotion: reduceMotion), value: isPicked)
                } else {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(mode == .group ? "Toggle" : "Chat with") \(user.name)")
    }

    @ViewBuilder
    private var groupComposer: some View {
        VStack(spacing: 10) {
            if !picked.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(users.filter { picked.contains($0.id) }) { user in
                            HStack(spacing: 6) {
                                Text(user.name).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                                Button {
                                    picked.remove(user.id)
                                } label: {
                                    Image(systemName: "xmark").font(.system(size: 10, weight: .bold))
                                }
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(PulseTheme.emerald.opacity(0.14)))
                            .foregroundStyle(PulseTheme.emeraldDeep)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            HStack(spacing: 10) {
                TextField("Group name", text: $groupName)
                    .textFieldStyle(.plain)
                    .font(.system(size: 15, weight: .medium))
                    .padding(.horizontal, 14)
                    .frame(height: 46)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(PulseTheme.zinc(200).opacity(0.6), lineWidth: 1)
                    )
                Button {
                    createGroup()
                } label: {
                    Group {
                        if creating {
                            ProgressView().tint(.white)
                        } else {
                            Text("Create").font(.system(size: 15, weight: .bold))
                        }
                    }
                    .frame(width: 104, height: 46)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(canCreateGroup ? PulseTheme.brandGradient : PulseTheme.zinc(300))
                    )
                    .foregroundStyle(.white)
                }
                .disabled(!canCreateGroup || creating)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
        .padding(.top, 8)
        .background(
            Rectangle()
                .fill(.ultraThinMaterial)
                .ignoresSafeArea()
                .overlay(alignment: .top) {
                    Rectangle().fill(PulseTheme.zinc(200).opacity(0.6)).frame(height: 1)
                }
        )
        .animation(reduceMotion ? nil : .pulse(.pulseSnappy, reduceMotion: reduceMotion), value: picked)
    }

    // ── actions ──────────────────────────────────────────────

    private var canCreateGroup: Bool { !groupName.trimmingCharacters(in: .whitespaces).isEmpty && !picked.isEmpty }

    private func load() async {
        do {
            let page = try await session.api.users()
            // The viewer never chats with themselves here (Note to Self exists for that).
            users = page.filter { $0.id != session.viewer?.id }
            phase = .ready
        } catch {
            phase = .failed(ChatsViewModel.describe(error))
        }
    }

    private func startDirect(_ user: WireUser) {
        guard !creating else { return }
        creating = true
        Task {
            defer { creating = false }
            do {
                let conv = try await session.api.createConversation(memberIds: [user.id], isGroup: false)
                session.noteInboxChanged()
                onCreated(conv)
            } catch {
                session.toasts.show(ChatsViewModel.describe(error))
            }
        }
    }

    private func createGroup() {
        guard canCreateGroup, !creating else { return }
        creating = true
        let name = groupName.trimmingCharacters(in: .whitespaces)
        let memberIds = Array(picked)
        Task {
            defer { creating = false }
            do {
                let conv = try await session.api.createConversation(memberIds: memberIds, isGroup: true, name: name)
                session.noteInboxChanged()
                onCreated(conv)
            } catch {
                session.toasts.show(ChatsViewModel.describe(error))
            }
        }
    }
}
