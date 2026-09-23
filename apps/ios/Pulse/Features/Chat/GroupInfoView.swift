import SwiftUI
import PhotosUI

/// REM-B P1 — group admin surface (web group-info-sheet.tsx +
/// room-info-page.tsx + room-member-add.tsx parity, native sheet):
///   • member roster with role chips (F-GR-02)
///   • add members via the full roster picker (F-GR-03, admin-only)
///   • promote / demote / kick with role-gated confirms (F-GR-04)
///   • rename (+ broadcast toggle) — admin-only PATCH (F-GR-01)
///   • invite create / regenerate + copy pulse://invite/<code> (F-GR-05)
///   • disappearing-TTL admin picker (F-MS-19: off · 24h · 7d · 30d)
///   • slow-mode admin picker (R44 presets 0/5/10/30/60/300)
///   • leave group with the server's last-admin succession toast
/// All writes hit the REAL routes; failures surface verbatim.
struct GroupInfoView: View {
    let conversation: WireConversationSummary
    @ObservedObject var session: PulseSession

    @Environment(\.dismiss) private var dismiss

    enum Phase: Equatable { case loading, loaded, failed(String) }
    @State private var phase: Phase = .loading
    @State private var detail: WireConversationSummary?
    @State private var busy = false

    // modals
    @State private var renameOpen = false
    @State private var addMembersOpen = false
    @State private var leaveOpen = false
    @State private var confirmAction: MemberAction?
    @State private var ttlOpen = false
    @State private var slowModeOpen = false
    @State private var broadcastOpen = false
    @State private var inviteCode: String?
    @State private var inviteBusy = false

    struct MemberAction: Identifiable {
        var id: String { memberId }
        let memberId: String
        let memberName: String
        let kind: Kind
        enum Kind { case promote, demote, kick }
    }

    private var viewerId: String { session.viewer?.id ?? "" }
    private var viewerRole: String? {
        (detail ?? conversation).members.first(where: { $0.id == viewerId })?.role
    }
    private var isAdmin: Bool { viewerRole == "admin" }

    var body: some View {
        NavigationStack {
            Group {
                switch phase {
                case .loading:
                    ProgressView("Loading group info…").frame(maxWidth: .infinity, maxHeight: .infinity)
                case .failed(let message):
                    VStack(spacing: 10) {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.system(size: 26, weight: .light))
                            .foregroundStyle(.secondary)
                        Text(message).font(.footnote).foregroundStyle(.secondary)
                        Button("Retry") { Task { await load() } }
                            .font(.footnote.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .loaded:
                    if let detail {
                        content(detail)
                    }
                }
            }
            .background(PulseTheme.pageWash.ignoresSafeArea())
            .navigationTitle("Group info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task { await load() }
    }

    // ── data ─────────────────────────────────────────────────

    private func load() async {
        phase = detail == nil ? .loading : phase
        do {
            detail = try await session.api.conversationDetail(id: conversation.id, userId: viewerId)
            phase = .loaded
            if let code = detail?.inviteCode { inviteCode = code }
        } catch {
            if detail == nil { phase = .failed(RoomViewModel.describe(error)) }
        }
    }

    // ── content ──────────────────────────────────────────────

    private func content(_ detail: WireConversationSummary) -> some View {
        List {
            Section {
                HStack(spacing: 12) {
                    RowAvatar(
                        name: detail.name ?? "Group",
                        colorName: "violet",
                        photoPath: detail.photo,
                        size: 56,
                        groupID: detail.id,
                    )
                    VStack(alignment: .leading, spacing: 3) {
                        Text(detail.name ?? "Group")
                            .font(.headline)
                            .lineLimit(1)
                        Text("\(detail.members.count) members")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if detail.broadcastMode == true {
                            Label("Announcement mode", systemImage: "megaphone.fill")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(PulseTheme.amber)
                        }
                    }
                }
                .padding(.vertical, 2)
            }

            // ── admin group settings ──
            if isAdmin {
                Section("Group settings") {
                    Button {
                        renameDraft = detail.name ?? ""
                        renameOpen = true
                    } label: {
                        Label("Rename group", systemImage: "pencil")
                    }
                    Button {
                        broadcastOpen = true
                    } label: {
                        Label(
                            detail.broadcastMode == true ? "Turn off announcement mode" : "Turn on announcement mode",
                            systemImage: "megaphone",
                        )
                    }
                    Button {
                        ttlOpen = true
                    } label: {
                        HStack {
                            Label("Disappearing messages", systemImage: "timer")
                            Spacer()
                            Text(ttlLabel(detail.ttlSeconds))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Button {
                        slowModeOpen = true
                    } label: {
                        HStack {
                            Label("Slow mode", systemImage: "tortoise")
                            Spacer()
                            Text(slowModeLabel(detail.slowModeSeconds ?? 0))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            // ── invite (admin; web room-info-page parity) ──
            if isAdmin {
                Section("Invite link") {
                    if let code = inviteCode {
                        LabeledContent("Code", value: code)
                        Button {
                            UIPasteboard.general.string = "pulse://invite/\(code)"
                            session.toasts.show("Invite link copied")
                            PulseHaptics.tap()
                        } label: {
                            Label("Copy pulse://invite/\(code)", systemImage: "doc.on.doc")
                        }
                        Button {
                            rotateInvite(regenerate: true)
                        } label: {
                            if inviteBusy {
                                ProgressView().controlSize(.small)
                            } else {
                                Label("Regenerate link", systemImage: "arrow.triangle.2.circlepath")
                            }
                        }
                        .disabled(inviteBusy)
                    } else {
                        Button {
                            rotateInvite(regenerate: false)
                        } label: {
                            if inviteBusy {
                                ProgressView().controlSize(.small)
                            } else {
                                Label("Create invite link", systemImage: "link.badge.plus")
                            }
                        }
                        .disabled(inviteBusy)
                    }
                }
            }

            // ── members ──
            Section {
                ForEach(detail.members, id: \.id) { member in
                    memberRow(member, detail: detail)
                }
            } header: {
                Text("Members")
            } footer: {
                if isAdmin {
                    Text("Tap a member for admin actions — promote, demote or remove.")
                }
            }

            if isAdmin {
                Section {
                    Button {
                        addMembersOpen = true
                    } label: {
                        Label("Add members", systemImage: "person.badge.plus")
                    }
                }
            }

            // ── leave ──
            Section {
                Button(role: .destructive) {
                    leaveOpen = true
                } label: {
                    Label("Leave group", systemImage: "rectangle.portrait.and.arrow.right")
                }
            } footer: {
                Text("If you are the last admin, the longest-standing member is promoted automatically.")
            }
        }
        .listStyle(.insetGrouped)
        .scrollDismissesKeyboard(.immediately)
        .alert("Rename group", isPresented: $renameOpen) {
            TextField("Group name", text: $renameDraft)
            Button("Save") { rename() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("1–32 characters.")
        }
        .confirmationDialog(
            "Announcement mode",
            isPresented: $broadcastOpen,
            titleVisibility: .visible,
        ) {
            Button(detail?.broadcastMode == true ? "Turn off" : "Turn on") { toggleBroadcast() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Only admins can post while announcement mode is on.")
        }
        .confirmationDialog(
            "Disappearing messages",
            isPresented: $ttlOpen,
            titleVisibility: .visible,
        ) {
            ForEach(ttlPresets, id: \.seconds) { preset in
                Button(preset.label) { setTtl(preset.seconds) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("New messages in this group disappear after the selected time.")
        }
        .confirmationDialog(
            "Slow mode",
            isPresented: $slowModeOpen,
            titleVisibility: .visible,
        ) {
            ForEach(slowModePresets, id: \.seconds) { preset in
                Button(preset.label) { setSlowMode(preset.seconds) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Limits how often members can send (admins are exempt).")
        }
        .confirmationDialog(
            confirmAction?.kind.dialogTitle(confirmAction?.memberName ?? "") ?? "",
            isPresented: Binding(
                get: { confirmAction != nil },
                set: { if !$0 { confirmAction = nil } },
            ),
            titleVisibility: .visible,
        ) {
            switch confirmAction?.kind {
            case .promote:
                Button("Promote to admin") { runMemberAction() }
            case .demote:
                Button("Demote to member", role: .destructive) { runMemberAction() }
            case .kick:
                Button("Remove \(confirmAction?.memberName ?? "")", role: .destructive) { runMemberAction() }
            default:
                EmptyView()
            }
            Button("Cancel", role: .cancel) { confirmAction = nil }
        } message: {
            Text(confirmAction?.kind.dialogMessage ?? "")
        }
        .alert("Leave \"\(detail.name ?? "group")\"?", isPresented: $leaveOpen) {
            Button("Leave group", role: .destructive) { leave() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You will stop receiving messages from this group. Chat history stays for the others.")
        }
        .sheet(isPresented: $addMembersOpen) {
            GroupMemberAddSheet(
                conversationId: conversation.id,
                existing: detail.members.map(\.id),
                session: session,
            ) { added in
                Task {
                    await load()
                    session.toasts.show("Added \(added) member\(added == 1 ? "" : "s")")
                    session.noteInboxChanged()
                }
            }
        }
    }

    private func memberRow(_ member: WireConversationMember, detail: WireConversationSummary) -> some View {
        Button {
            guard isAdmin, member.id != viewerId else { return }
            PulseHaptics.tap()
            if member.role == "admin" {
                confirmAction = .init(memberId: member.id, memberName: member.name, kind: .demote)
            } else {
                confirmAction = .init(memberId: member.id, memberName: member.name, kind: .promote)
            }
        } label: {
            HStack(spacing: 12) {
                RowAvatar(
                    name: member.name,
                    colorName: member.color,
                    photoPath: member.avatar,
                    size: 40,
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(member.name)
                        .font(.system(size: 14.5, weight: .semibold))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                        .lineLimit(1)
                    if member.id == viewerId {
                        Text("You")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                // role chip (F-GR-02)
                Text(member.role == "admin" ? "Admin" : "Member")
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(member.role == "admin" ? PulseTheme.emerald.opacity(0.15) : Color.secondary.opacity(0.10)))
                    .foregroundStyle(member.role == "admin" ? PulseTheme.emeraldDeep : PulseTheme.textSecondary)
                // kick affordance — admins can remove NON-admin members
                // (kicking admins is rejected server-side; web hides it too)
                if isAdmin, member.role != "admin", member.id != viewerId {
                    Button {
                        confirmAction = .init(memberId: member.id, memberName: member.name, kind: .kick)
                    } label: {
                        Image(systemName: "minus.circle.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(Color.red.opacity(0.75))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Remove \(member.name)")
                }
            }
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityLabel("\(member.name), \(member.role == "admin" ? "admin" : "member")")
    }

    // ── actions ──────────────────────────────────────────────

    @State private var renameDraft = ""

    private func rename() {
        let name = renameDraft.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        Task {
            busy = true
            defer { busy = false }
            do {
                detail = try await session.api.patchConversation(conversation.id, requesterId: viewerId, name: name)
                session.noteInboxChanged()

                session.toasts.show("Group renamed")
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func toggleBroadcast() {
        let next = !(detail?.broadcastMode == true)
        Task {
            busy = true
            defer { busy = false }
            do {
                detail = try await session.api.patchConversation(conversation.id, requesterId: viewerId, broadcast: next)
                session.noteInboxChanged()
                session.toasts.show(next ? "Announcement mode on — only admins can post" : "Announcement mode off")
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func leave() {
        Task {
            busy = true
            defer { busy = false }
            do {
                let result = try await session.api.leaveGroup(conversation.id, requesterId: viewerId)
                session.noteInboxChanged()

                dismiss()
                let promoted = result.promotedUserId.flatMap { id in
                    detail?.members.first(where: { $0.id == id })?.name
                }
                session.toasts.show(promoted != nil ? "You left the group — \(promoted!) is now an admin" : "You left the group")
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func runMemberAction() {
        guard let action = confirmAction else { return }
        confirmAction = nil
        Task {
            busy = true
            defer { busy = false }
            do {
                switch action.kind {
                case .promote:
                    detail = try await session.api.setMemberRole(conversation.id, requesterId: viewerId, userId: action.memberId, role: "admin")
                    session.toasts.show("\(action.memberName) is now an admin")
                case .demote:
                    detail = try await session.api.setMemberRole(conversation.id, requesterId: viewerId, userId: action.memberId, role: "member")
                    session.toasts.show("\(action.memberName) is now a member")
                case .kick:
                    try await session.api.kickMember(conversation.id, requesterId: viewerId, userId: action.memberId)
                    await load()
                    session.toasts.show("\(action.memberName) removed from the group")
                }
                session.noteInboxChanged()

            } catch {
                // Honest copy — "Cannot demote the only admin…", 403s, etc.
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func rotateInvite(regenerate: Bool) {
        Task {
            inviteBusy = true
            defer { inviteBusy = false }
            do {
                inviteCode = try await session.api.inviteCreate(conversation.id, requesterId: viewerId, regenerate: regenerate)
                PulseHaptics.success()
                session.toasts.show(regenerate ? "Invite link regenerated" : "Invite link created")
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func setTtl(_ seconds: Int) {
        Task {
            do {
                detail = try await session.api.setDisappearingTtl(conversation.id, userId: viewerId, ttlSeconds: seconds)
                session.toasts.show(seconds == 0 ? "Disappearing messages off" : "New messages disappear after \(ttlLabel(seconds).lowercased())")
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func setSlowMode(_ seconds: Int) {
        Task {
            do {
                let applied = try await session.api.setSlowMode(conversation.id, userId: viewerId, seconds: seconds)
                detail?.slowModeSeconds = applied
                session.toasts.show(applied == 0 ? "Slow mode off" : "Slow mode: 1 message per \(slowModeLabel(applied).lowercased())")
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    // ── preset ladders (backend-verbatim) ────────────────────

    struct Preset {
        let seconds: Int
        let label: String
    }

    /// PATCH disappearing route TTL_PRESETS: off · 24h · 7d · 30d.
    static let ttlPresets: [Preset] = [
        .init(seconds: 0, label: "Off"),
        .init(seconds: 86_400, label: "24 hours"),
        .init(seconds: 604_800, label: "7 days"),
        .init(seconds: 2_592_000, label: "30 days"),
    ]

    /// PATCH slow-mode route SLOW_MODE_PRESETS: 0/5/10/30/60/300.
    static let slowModePresets: [Preset] = [
        .init(seconds: 0, label: "Off"),
        .init(seconds: 5, label: "5 seconds"),
        .init(seconds: 10, label: "10 seconds"),
        .init(seconds: 30, label: "30 seconds"),
        .init(seconds: 60, label: "1 minute"),
        .init(seconds: 300, label: "5 minutes"),
    ]

    static func ttlLabel(_ seconds: Int?) -> String {
        guard let seconds, seconds > 0 else { return "Off" }
        switch seconds {
        case 86_400: return "24 hours"
        case 604_800: return "7 days"
        case 2_592_000: return "30 days"
        default: return "\(seconds)s"
        }
    }

    static func slowModeLabel(_ seconds: Int?) -> String {
        guard let seconds, seconds > 0 else { return "Off" }
        switch seconds {
        case 60: return "1 minute"
        case 300: return "5 minutes"
        default: return "\(seconds)s"
        }
    }
}

extension GroupInfoView.MemberAction.Kind {
    var dialogTitle: (String) -> String {
        switch self {
        case .promote: return { "Promote \($0) to admin?" }
        case .demote: return { "Demote \($0)?" }
        case .kick: return { _ in "Remove member?" }
        }
    }
    var dialogMessage: String {
        switch self {
        case .promote: return "Admins can rename the group, add and remove members, and toggle announcement mode."
        case .demote: return "They will keep their chat history but lose admin powers."
        case .kick: return "They will leave the group immediately. Their chat history stays."
        }
    }
}

// ── roster picker (web room-member-add.tsx parity) ──────────

/// Admin-only "add members" — full identity roster minus existing members,
/// multi-select, POST /members { userIds } in one shot.
struct GroupMemberAddSheet: View {
    let conversationId: String
    let existing: [String]
    @ObservedObject var session: PulseSession
    var onAdded: (Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var users: [WireUser] = []
    @State private var picked: Set<String> = []
    @State private var query = ""
    @State private var phase: Phase = .loading
    @State private var adding = false

    enum Phase { case loading, ready, failed(String) }

    private var candidates: [WireUser] {
        let existingSet = Set(existing)
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        return users
            .filter { !existingSet.contains($0.id) }
            .filter { needle.isEmpty || $0.name.lowercased().contains(needle) || ($0.username ?? "").lowercased().contains(needle) }
    }

    var body: some View {
        NavigationStack {
            Group {
                switch phase {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                case .failed(let message):
                    ContentUnavailableCompat(title: "Can't load contacts", systemImage: "wifi.exclamationmark", note: message)
                case .ready:
                    if candidates.isEmpty {
                        ContentUnavailableCompat(title: "No one to add", systemImage: "person.2", note: "Everyone on this Pulse is already in the group.")
                    } else {
                        List(candidates) { user in
                            Button {
                                if picked.contains(user.id) {
                                    picked.remove(user.id)
                                } else {
                                    picked.insert(user.id)
                                }
                                PulseHaptics.tap()
                            } label: {
                                HStack(spacing: 12) {
                                    RowAvatar(name: user.name, colorName: user.color, photoPath: user.avatar, size: 38)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(user.name).font(.subheadline.weight(.semibold))
                                        if let handle = user.username {
                                            Text("@\(handle)").font(.caption2).foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                    Image(systemName: picked.contains(user.id) ? "checkmark.circle.fill" : "circle")
                                        .font(.system(size: 20))
                                        .foregroundStyle(picked.contains(user.id) ? PulseTheme.emerald : PulseTheme.textTertiary)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                        .listStyle(.plain)
                    }
                }
            }
            .navigationTitle("Add members")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "Search name or @handle")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        add()
                    } label: {
                        if adding {
                            ProgressView().controlSize(.small)
                        } else {
                            Text(picked.isEmpty ? "Add" : "Add (\(picked.count))")
                                .font(.subheadline.weight(.semibold))
                        }
                    }
                    .disabled(picked.isEmpty || adding)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await load() }
    }

    private func load() async {
        do {
            users = try await session.api.users()
            phase = .ready
        } catch {
            phase = .failed(RoomViewModel.describe(error))
        }
    }

    private func add() {
        guard !picked.isEmpty, !adding else { return }
        adding = true
        let viewerId = session.viewer?.id ?? ""
        Task {
            defer { adding = false }
            do {
                let added = try await session.api.addMembers(conversationId, requesterId: viewerId, userIds: Array(picked))
                dismiss()
                onAdded(added.count)
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }
}
