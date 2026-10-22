import SwiftUI
import PhotosUI

/// REM-B P1 - group admin surface (web group-info-sheet.tsx +
/// room-info-page.tsx + room-member-add.tsx parity, native sheet):
///   • member roster with role chips (F-GR-02)
///   • add members via the full roster picker (F-GR-03, admin-only)
///   • promote / demote / kick with role-gated confirms (F-GR-04)
///   • rename (+ broadcast toggle) - admin-only PATCH (F-GR-01)
///   • invite create / regenerate + copy pulse://invite/<code> (F-GR-05)
///   • disappearing-TTL admin picker (F-MS-19: off · 24h · 7d · 30d)
///   • slow-mode admin picker (R44 presets 0/5/10/30/60/300)
///   • leave group with the server's last-admin succession toast
/// All writes hit the REAL routes; failures surface verbatim.
struct GroupInfoView: View {
    let conversation: WireConversationSummary
    @ObservedObject var session: PulseSession
    /// R2-B - the room reads prefs (my veil flag) live; also lets the info
    /// sheet push fresh detail (veil flag, photo) back into the room.
    @ObservedObject var prefs: PulsePrefs
    var onDetailUpdated: ((WireConversationSummary) -> Void)? = nil
    var onLeft: () -> Void

    @Environment(\.dismiss) private var dismiss

    enum Phase: Equatable { case loading, loaded, failed(String) }
    @State private var phase: Phase = .loading
    @State private var detail: WireConversationSummary?
    @State private var mutationGate = PulseMutationGate()
    @State private var mutationTask: Task<Void, Never>?
    private var busy: Bool { mutationGate.isBusy }

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
    @State private var surfaceEpoch: UUID?
    @State private var loadTicket = UUID()
    @State private var loadTask: Task<Void, Never>?
    @State private var inviteTask: Task<Void, Never>?
    @State private var inviteTicket = UUID()
    // R2-B - group photo edit (web R33-b "Edit photo" overlay parity)
    @State private var photoItem: PhotosPickerItem?
    @State private var photoBusy = false
    // R2-D ITEM 9 - member-list search (web room-info-page.tsx:320-331:
    // surfaced only past 8 members, name/@handle substring, clearable).
    @State private var memberQuery = ""

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

    /// R2-D ITEM 9 - the filtered member list (web room-info-page.tsx
    /// :320-331 verbatim): short rooms stay unfiltered; past 8 members a
    /// non-empty query keeps name OR @handle case-insensitive matches.
    private func visibleMembers(_ detail: WireConversationSummary) -> [WireConversationMember] {
        let query = memberQuery.trimmingCharacters(in: .whitespaces).lowercased()
        guard detail.members.count > 8, !query.isEmpty else { return detail.members }
        return detail.members.filter { member in
            member.name.lowercased().contains(query)
                || (member.username ?? "").lowercased().contains(query)
        }
    }

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
                        Button("Retry") { reloadDetail() }
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
        .onAppear {
            surfaceEpoch = session.sessionGeneration
            reloadDetail()
        }
        .onDisappear { retireSurfaceWork() }
        .onChange(of: session.sessionGeneration) { _, _ in retireSurfaceWork(); dismiss() }
    }

    // data

    private func reloadDetail() {
        let epoch = session.sessionGeneration
        guard surfaceEpoch == epoch else { return }
        loadTask?.cancel(); loadTicket = UUID()
        loadTask = Task { @MainActor in
            guard !Task.isCancelled, surfaceEpoch == epoch, session.sessionGeneration == epoch else { return }
            await load()
        }
    }

    private func load() async {
        let epoch = session.sessionGeneration, api = session.api, owner = viewerId, ticket = UUID()
        guard surfaceEpoch == epoch, !Task.isCancelled else { return }
        loadTicket = ticket
        phase = detail == nil ? .loading : phase
        do {
            let fresh = try await api.conversationDetail(id: conversation.id, userId: owner)
            guard surfaceEpoch == epoch, session.sessionGeneration == epoch, loadTicket == ticket, !Task.isCancelled else { return }
            try acceptDetail(fresh)
            phase = .loaded
        } catch {
            guard surfaceEpoch == epoch, session.sessionGeneration == epoch, loadTicket == ticket, !Task.isCancelled else { return }
            if detail == nil { phase = .failed(RoomViewModel.describe(error)) }
        }
    }

    // content

    private func content(_ detail: WireConversationSummary) -> some View {
        return groupContentStyled3(detail).disabled(busy || inviteBusy)
    }

    // GroupInfoView.content - R12 type-check decomposition: the original
    // single builder expression exceeded Swift’s type-check budget; the
    // view tree is VERBATIM, split only (funcs thread the detail param -
    // it shadows the optional stored `detail` property).
    private func groupContentStyled_view(_ detail: WireConversationSummary) -> some View {
        List {
            groupContentPart0(detail)
            groupContentPart1(detail)
            groupContentPart2(detail)
            groupContentPart3(detail)
            groupContentPart4(detail)
            groupContentPart5(detail)
            groupContentPart6(detail)
            groupContentPart7(detail)
            groupContentPart8(detail)
            groupContentPart9(detail)
            groupContentPart10(detail)
            groupContentPart11(detail)
            groupContentPart12(detail)
            groupContentPart13(detail)
            groupContentPart14(detail)
            groupContentPart15(detail)
            groupContentPart16(detail)
            groupContentPart17(detail)
            groupContentPart18(detail)
            groupContentPart19(detail)
            groupContentPart20(detail)
            groupContentPart21(detail)
            groupContentPart22(detail)
            groupContentPart23(detail)
        }
    }

    @ViewBuilder
    private func groupContentPart0(_ detail: WireConversationSummary) -> some View {
            Section {
                HStack(spacing: 12) {
                    RowAvatar(
                        name: detail.name ?? "Group",
                        colorName: "violet",
                        photoPath: detail.photo,
                        size: 56,
                        groupID: detail.id,
                    )
                    .overlay(alignment: .bottomTrailing) {
                        // R33-b - "Edit photo" overlay for admins of ANY group
                        // (channels and plain groups alike; the PATCH route has
                        // always accepted photo for every group) - opens the
                        // real upload chain (pick → /api/uploads → PATCH photo).
                        if isAdmin {
                            PhotosPicker(selection: $photoItem, matching: .images) {
                                Image(systemName: photoBusy ? "hourglass" : "pencil")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(.white)
                                    .frame(width: 22, height: 22)
                                    .background(Circle().fill(PulseTheme.emerald))
                                    .overlay(Circle().strokeBorder(Color.white, lineWidth: 1.5))
                            }
                            .disabled(photoBusy)
                            .accessibilityLabel(detail.photo == nil ? "Add group photo" : "Edit group photo")
                        }
                    }
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

    }

    @ViewBuilder
    private func groupContentPart1(_ detail: WireConversationSummary) -> some View {
            // admin group settings
    }

    @ViewBuilder
    private func groupContentPart2(_ detail: WireConversationSummary) -> some View {
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
                            Text(Self.ttlLabel(detail.ttlSeconds))
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
                            Text(Self.slowModeLabel(detail.slowModeSeconds ?? 0))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

    }

    @ViewBuilder
    private func groupContentPart3(_ detail: WireConversationSummary) -> some View {
            // R38/R42 - screen security (comfort setting, deliberately
    }

    @ViewBuilder
    private func groupContentPart4(_ detail: WireConversationSummary) -> some View {
            // NOT admin-gated - web parity): the personal veil frosts MY
    }

    @ViewBuilder
    private func groupContentPart5(_ detail: WireConversationSummary) -> some View {
            // view; the room-wide switch frosts every member's view. Both
    }

    @ViewBuilder
    private func groupContentPart6(_ detail: WireConversationSummary) -> some View {
            // flags OR together inside the room.
    }

    @ViewBuilder
    private func groupContentPart7(_ detail: WireConversationSummary) -> some View {
            Section {
                Toggle(isOn: Binding(
                    get: { prefs.screenPrivacy[conversation.id] ?? false },
                    set: { setMyScreenPrivacy($0) },
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Screen security", systemImage: "eye.slash")
                            .font(.subheadline.weight(.medium))
                        Text("Blur messages when Pulse loses focus - just for you")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityLabel("Screen security for you")
                Toggle(isOn: Binding(
                    get: { detail.screenPrivacy ?? false },
                    set: { setRoomScreenPrivacy($0) },
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Screen security for everyone", systemImage: "shield.lefthalf.filled")
                            .font(.subheadline.weight(.medium))
                        Text("Applies to every member of this chat")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .disabled(detail == nil)
                .accessibilityLabel("Screen security for everyone")
            }

    }

    @ViewBuilder
    private func groupContentPart8(_ detail: WireConversationSummary) -> some View {
            // invite (admin; web room-info-page parity)
    }

    @ViewBuilder
    private func groupContentPart9(_ detail: WireConversationSummary) -> some View {
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
                        .disabled(inviteBusy)
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
                                Label("Get invite link", systemImage: "link.badge.plus")
                            }
                        }
                        .disabled(inviteBusy)
                    }
                }
            }

    }

    @ViewBuilder
    private func groupContentPart10(_ detail: WireConversationSummary) -> some View {
            // members
    }

    @ViewBuilder
    private func groupContentPart11(_ detail: WireConversationSummary) -> some View {
            Section {
                // R2-D ITEM 9 - the search pill (web shows it past 8 members:
                // room-info-page.tsx:1425-1459, "Search members" placeholder).
                if detail.members.count > 8 {
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                        TextField("Search members", text: $memberQuery)
                            .font(.system(size: 13))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .accessibilityLabel("Search members")
                        if !memberQuery.isEmpty {
                            Button {
                                memberQuery = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 14))
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Clear member search")
                        }
                    }
                    .padding(.vertical, 2)
                }
                ForEach(visibleMembers(detail), id: \.id) { member in
                    memberRow(member, detail: detail)
                }
                // R2-D ITEM 9 - the honest empty state (web :1604-1609 copy).
                if detail.members.count > 8, visibleMembers(detail).isEmpty {
                    HStack(spacing: 6) {
                        Image(systemName: "magnifyingglass")
                            .font(.caption)
                        Text("No members match \u{201C}\(memberQuery.trimmingCharacters(in: .whitespaces))\u{201D}")
                            .font(.caption)
                    }
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 4)
                }
            } header: {
                Text("Members")
            } footer: {
                if isAdmin {
                    Text("Tap a member for admin actions - promote, demote or remove.")
                }
            }

    }

    @ViewBuilder
    private func groupContentPart12(_ detail: WireConversationSummary) -> some View {
            if isAdmin {
                Section {
                    Button {
                        addMembersOpen = true
                    } label: {
                        Label("Add members", systemImage: "person.badge.plus")
                    }
                }
            }

    }

    @ViewBuilder
    private func groupContentPart13(_ detail: WireConversationSummary) -> some View {
            // R39 - Automations (keyword-triggered auto-replies). One
    }

    @ViewBuilder
    private func groupContentPart14(_ detail: WireConversationSummary) -> some View {
            // section (rows + optimistic switch + honest delete + create
    }

    @ViewBuilder
    private func groupContentPart15(_ detail: WireConversationSummary) -> some View {
            // sheet live in RoomIntegrationsSurfaces.swift). Admins manage;
    }

    @ViewBuilder
    private func groupContentPart16(_ detail: WireConversationSummary) -> some View {
            // members read rows or the honest manage caption (web parity).
    }

    @ViewBuilder
    private func groupContentPart17(_ detail: WireConversationSummary) -> some View {
            AutomationsSection(
                conversationId: conversation.id,
                viewerId: viewerId,
                isAdmin: isAdmin,
                session: session,
            )

    }

    @ViewBuilder
    private func groupContentPart18(_ detail: WireConversationSummary) -> some View {
            // R2-B - Webhooks (Discord-style incoming integrations, web
    }

    @ViewBuilder
    private func groupContentPart19(_ detail: WireConversationSummary) -> some View {
            // group-info-sheet parity): everyone copies ingest URLs, admins
    }

    @ViewBuilder
    private func groupContentPart20(_ detail: WireConversationSummary) -> some View {
            // create/delete.
    }

    @ViewBuilder
    private func groupContentPart21(_ detail: WireConversationSummary) -> some View {
            WebhooksSection(
                conversationId: conversation.id,
                viewerId: viewerId,
                isAdmin: isAdmin,
                session: session,
            )

    }

    @ViewBuilder
    private func groupContentPart22(_ detail: WireConversationSummary) -> some View {
            // leave
    }

    @ViewBuilder
    private func groupContentPart23(_ detail: WireConversationSummary) -> some View {
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

    private func groupContentStyled0(_ detail: WireConversationSummary) -> some View {
        groupContentStyled_view(detail)
        .listStyle(.insetGrouped)
        .scrollDismissesKeyboard(.immediately)
        .onChange(of: photoItem) { _, item in
            // R33-b - the picked photo flows through the avatar square
            // pipeline → /api/uploads → PATCH conversation photo.
            guard let item else { return }
            photoItem = nil
            uploadPhoto(item)
        }
    }

    private func groupContentStyled1(_ detail: WireConversationSummary) -> some View {
        groupContentStyled0(detail)
        .alert("Rename group", isPresented: $renameOpen) {
            TextField("Group name", text: $renameDraft)
            Button("Save") { rename() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("1–48 characters.")
        }
        .confirmationDialog(
            "Announcement mode",
            isPresented: $broadcastOpen,
            titleVisibility: .visible,
        ) {
            Button(detail.broadcastMode == true ? "Turn off" : "Turn on") { toggleBroadcast() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Only admins can post while announcement mode is on.")
        }
        .confirmationDialog(
            "Disappearing messages",
            isPresented: $ttlOpen,
            titleVisibility: .visible,
        ) {
            ForEach(Self.ttlPresets, id: \.seconds) { preset in
                Button(preset.label) { setTtl(preset.seconds) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("New messages in this group disappear after the selected time.")
        }
    }

    private func groupContentStyled2(_ detail: WireConversationSummary) -> some View {
        groupContentStyled1(detail)
        .confirmationDialog(
            "Slow mode",
            isPresented: $slowModeOpen,
            titleVisibility: .visible,
        ) {
            ForEach(Self.slowModePresets, id: \.seconds) { preset in
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
    }

    private func groupContentStyled3(_ detail: WireConversationSummary) -> some View {
        groupContentStyled2(detail)
        .sheet(isPresented: $addMembersOpen) {
            GroupMemberAddSheet(
                conversationId: conversation.id,
                existing: detail.members.map(\.id),
                session: session,
            ) { added in
                guard surfaceEpoch == session.sessionGeneration else { return }
                session.toasts.show(added == 0 ? "Selected people are already members" : "Added \(added) member\(added == 1 ? "" : "s")")
                session.noteInboxChanged()
                reloadDetail()
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
                // kick affordance - admins can remove NON-admin members
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

    // actions

    @State private var renameDraft = ""

    private func requireSurface(_ epoch: UUID) throws {
        try Task.checkCancellation()
        guard surfaceEpoch == epoch, session.sessionGeneration == epoch else { throw CancellationError() }
    }

    private func acceptDetail(_ fresh: WireConversationSummary, notice: String? = nil) throws {
        guard fresh.id == conversation.id else {
            throw PulseAPIClient.Failure(kind: .server, message: "The response didn't match this group. Refresh group info.")
        }
        let epoch = session.sessionGeneration
        detail = fresh
        inviteCode = fresh.inviteCode
        if let notice { session.toasts.show(notice) }
        guard surfaceEpoch == epoch, session.sessionGeneration == epoch else { return }
        onDetailUpdated?(fresh)
    }

    private func mutate<Value: Sendable>(photo: Bool = false, optimistic: () -> Void = {},
        request: @escaping @MainActor (PulseAPIClient, String) async throws -> Value,
        apply: @escaping @MainActor (Value) throws -> Void) {
        let epoch = session.sessionGeneration, api = session.api, owner = viewerId
        guard surfaceEpoch == epoch, !inviteBusy, !Task.isCancelled,
              let ticket = mutationGate.begin(owner: epoch, resource: conversation.id) else { return }
        loadTicket = UUID()
        photoBusy = photo
        optimistic()
        mutationTask = Task { @MainActor in
            defer {
                if mutationGate.finish(ticket) { photoBusy = false; mutationTask = nil }
                // Even cancellation/lost transport can follow a committed change.
                if session.sessionGeneration == epoch { session.noteInboxChanged() }
            }
            do {
                try requireSurface(epoch)
                guard mutationGate.isCurrent(ticket, owner: session.sessionGeneration, resource: conversation.id) else { return }
                let result = try await request(api, owner)
                try requireSurface(epoch)
                guard mutationGate.isCurrent(ticket, owner: session.sessionGeneration, resource: conversation.id) else { return }
                loadTicket = UUID()
                try apply(result)
            } catch {
                guard surfaceEpoch == epoch, session.sessionGeneration == epoch, !Task.isCancelled,
                      mutationGate.isCurrent(ticket, owner: epoch, resource: conversation.id) else { return }
                session.toasts.show(RoomViewModel.describe(error))
                if (error as? PulseAPIClient.Failure)?.kind != .validation {
                    // Permission may have changed, or the reply may have been lost
                    // after commit. Do not leave stale controls as the only truth.
                    detail = nil; phase = .loading
                    reloadDetail()
                }
            }
        }
    }

    private func rename() {
        let name = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...48).contains(name.utf16.count) else {
            session.toasts.show("Group name is empty or too long (maximum 48 characters)")
            return
        }
        mutate(request: { api, owner in
            try await api.patchConversation(conversation.id, requesterId: owner, name: name)
        }, apply: { fresh in
            try acceptDetail(fresh, notice: "Group renamed")
        })
    }

    private func toggleBroadcast() {
        let next = !(detail?.broadcastMode == true)
        mutate(request: { api, owner in
            try await api.patchConversation(conversation.id, requesterId: owner, broadcast: next)
        }, apply: { fresh in
            try acceptDetail(fresh, notice: fresh.broadcastMode == true ? "Announcement mode on - only admins can post" : "Announcement mode off")
        })
    }

    private func leave() {
        let members = detail?.members ?? []
        mutate(request: { api, owner in
            try await api.leaveGroup(conversation.id, requesterId: owner)
        }, apply: { result in
            guard result.ok == true else { throw PulseAPIClient.Failure(kind: .server, message: "Leaving was not confirmed. Refresh Chats before retrying.") }
            let promoted = result.promotedUserId.flatMap { id in members.first(where: { $0.id == id })?.name }
            session.toasts.show(promoted.map { "You left the group - \($0) is now an admin" } ?? "You left the group")
            onLeft()
        })
    }

    private func runMemberAction() {
        guard let action = confirmAction else { return }
        confirmAction = nil
        switch action.kind {
        case .promote, .demote:
            let role = action.kind == .promote ? "admin" : "member"
            mutate(request: { api, owner in
                try await api.setMemberRole(conversation.id, requesterId: owner, userId: action.memberId, role: role)
            }, apply: { fresh in
                try acceptDetail(fresh, notice: "\(action.memberName) is now \(role == "admin" ? "an admin" : "a member")")
            })
        case .kick:
            mutate(request: { api, owner in
                try await api.kickMember(conversation.id, requesterId: owner, userId: action.memberId)
            }, apply: { _ in
                // The removal is confirmed; a later roster fetch is a separate operation.
                session.toasts.show("\(action.memberName) removed from the group")
                detail = nil; phase = .loading
                reloadDetail()
            })
        }
    }

    private func retireSurfaceWork() {
        surfaceEpoch = nil; loadTicket = UUID(); inviteTicket = UUID()
        loadTask?.cancel(); loadTask = nil
        inviteTask?.cancel(); inviteTask = nil; inviteBusy = false
        mutationGate.cancel(); mutationTask?.cancel(); mutationTask = nil; photoBusy = false
    }

    private func rotateInvite(regenerate: Bool) {
        let epoch = session.sessionGeneration, api = session.api, owner = viewerId, ticket = UUID()
        guard surfaceEpoch == epoch, !inviteBusy, !busy, isAdmin else { return }
        inviteTicket = ticket
        inviteBusy = true // claim before launching; two taps cannot enqueue two rotations
        loadTicket = UUID()
        inviteTask = Task { @MainActor in
            defer { if inviteTicket == ticket { inviteBusy = false; inviteTask = nil } }
            do {
                guard surfaceEpoch == epoch, session.sessionGeneration == epoch, inviteTicket == ticket, !Task.isCancelled else { return }
                let next = try await api.inviteCreate(conversation.id, requesterId: owner, regenerate: regenerate)
                guard surfaceEpoch == epoch, session.sessionGeneration == epoch, inviteTicket == ticket, !Task.isCancelled else { return }
                loadTicket = UUID() // a pre-rotation detail load cannot restore the retired code
                inviteCode = next
                PulseHaptics.success()
                session.toasts.show(regenerate ? "Invite link regenerated" : "Invite link created")
            } catch {
                guard surfaceEpoch == epoch, session.sessionGeneration == epoch, inviteTicket == ticket, !Task.isCancelled else { return }
                loadTicket = UUID()
                inviteCode = nil // an unconfirmed rotation may already have retired the displayed code
                session.toasts.show("Couldn't confirm the invite link. Get the current link again before sharing it.")
            }
        }
    }

    private func setTtl(_ seconds: Int) {
        mutate(request: { api, owner in
            try await api.setDisappearingTtl(conversation.id, userId: owner, ttlSeconds: seconds)
        }, apply: { fresh in
            guard let applied = fresh.ttlSeconds else {
                throw PulseAPIClient.Failure(kind: .server, message: "Disappearing-message setting couldn't be confirmed")
            }
            try acceptDetail(fresh, notice: applied == 0 ? "Disappearing messages off" : "New messages disappear after \(Self.ttlLabel(applied).lowercased())")
        })
    }

    private func setSlowMode(_ seconds: Int) {
        mutate(request: { api, owner in
            try await api.setSlowMode(conversation.id, userId: owner, seconds: seconds)
        }, apply: { applied in
            detail?.slowModeSeconds = applied
            if let detail { onDetailUpdated?(detail) }
        })
    }

    /// Preserve the existing immediate device-local comfort preference; only
    /// the matching server acknowledgement may adopt its mirror afterward.
    private func setMyScreenPrivacy(_ on: Bool) {
        mutate(optimistic: { prefs.setScreenPrivacy(conversationId: conversation.id, on: on) }, request: { api, _ in
            try await api.setMyScreenPrivacy(conversation.id, on: on)
        }, apply: { confirmed in
            prefs.adoptServerScreenPrivacy(conversationId: conversation.id, on: confirmed)
        })
    }

    private func setRoomScreenPrivacy(_ on: Bool) {
        mutate(request: { api, owner in
            try await api.patchConversation(conversation.id, requesterId: owner, screenPrivacy: on)
        }, apply: { fresh in try acceptDetail(fresh) })
    }

    private func uploadPhoto(_ item: PhotosPickerItem) {
        let epoch = session.sessionGeneration
        mutate(photo: true, request: { api, owner in
            let raw = try await item.loadTransferable(type: Data.self)
            try requireSurface(epoch)
            guard let raw, let jpeg = PulseAvatarImage.jpegData(from: raw) else {
                throw PulseAPIClient.Failure(kind: .validation, message: "Couldn't read that image - try another one")
            }
            let path = try await api.uploadMedia(dataUrl: PulseAvatarImage.dataUrl(jpeg))
            try requireSurface(epoch) // dismissal must not progress an upload into a group mutation
            return try await api.patchConversation(conversation.id, requesterId: owner, photo: path)
        }, apply: { fresh in
            try acceptDetail(fresh, notice: "Photo updated")
        })
    }

    // preset ladders (backend-verbatim)

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

// roster picker (web room-member-add.tsx parity)

/// Admin-only "add members" - full identity roster minus existing members,
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
    @State private var mutationGate = PulseMutationGate()
    @State private var mutationTask: Task<Void, Never>?
    @State private var loadTask: Task<Void, Never>?
    @State private var surfaceEpoch: UUID?
    @State private var loadTicket = UUID()
    private var adding: Bool { mutationGate.isBusy }
    private var canAdd: Bool {
        if case .ready = phase { return !picked.isEmpty && !adding }
        return false
    }

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
                    VStack {
                        ContentUnavailableCompat(title: "Can't load contacts", systemImage: "wifi.exclamationmark", note: message)
                        Button("Retry") { retryLoad() }.buttonStyle(.borderedProminent)
                    }
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
            .disabled(adding)
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
                    .disabled(!canAdd)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task {
            guard !Task.isCancelled else { return }
            surfaceEpoch = session.sessionGeneration
            await load()
        }
        .onDisappear { retireWork() }
        .onChange(of: session.sessionGeneration) { _, _ in
            retireWork(); users = []; picked = []; phase = .loading
            dismiss()
        }
    }

    private func retireWork() {
        surfaceEpoch = nil; loadTicket = UUID()
        loadTask?.cancel(); loadTask = nil
        mutationGate.cancel(); mutationTask?.cancel(); mutationTask = nil
    }

    private func retryLoad() {
        let epoch = session.sessionGeneration
        guard surfaceEpoch == epoch, !adding else { return }
        loadTask?.cancel()
        loadTask = Task { @MainActor in
            guard !Task.isCancelled, surfaceEpoch == epoch, session.sessionGeneration == epoch else { return }
            await load()
        }
    }

    private func load() async {
        let epoch = session.sessionGeneration, api = session.api, owner = session.viewer?.id ?? ""
        guard surfaceEpoch == epoch, !adding, !Task.isCancelled else { return }
        let ticket = UUID(); loadTicket = ticket
        phase = .loading
        do {
            let all = try await api.users()
            guard !Task.isCancelled, surfaceEpoch == epoch, session.sessionGeneration == epoch, loadTicket == ticket else { return }
            users = all.filter { $0.id != owner && !existing.contains($0.id) }
            picked.formIntersection(Set(users.map(\.id)))
            phase = .ready
        } catch {
            guard !Task.isCancelled, surfaceEpoch == epoch, session.sessionGeneration == epoch, loadTicket == ticket else { return }
            phase = .failed(RoomViewModel.describe(error))
        }
    }

    private func add() {
        let epoch = session.sessionGeneration, api = session.api, owner = session.viewer?.id ?? ""
        let ids = picked.sorted()
        guard surfaceEpoch == epoch, !owner.isEmpty, canAdd,
              let ticket = mutationGate.begin(owner: epoch, resource: conversationId) else { return }
        loadTicket = UUID()
        mutationTask = Task { @MainActor in
            defer {
                if mutationGate.finish(ticket) { mutationTask = nil }
                if session.sessionGeneration == epoch { session.noteInboxChanged() }
            }
            do {
                guard !Task.isCancelled, surfaceEpoch == epoch, session.sessionGeneration == epoch,
                      mutationGate.isCurrent(ticket, owner: epoch, resource: conversationId) else { return }
                let added = try await api.addMembers(conversationId, requesterId: owner, userIds: ids)
                guard !Task.isCancelled, surfaceEpoch == epoch, session.sessionGeneration == epoch,
                      mutationGate.isCurrent(ticket, owner: epoch, resource: conversationId) else { return }
                onAdded(added.count)
                if surfaceEpoch == epoch, session.sessionGeneration == epoch { dismiss() }
            } catch {
                guard !Task.isCancelled, surfaceEpoch == epoch, session.sessionGeneration == epoch,
                      mutationGate.isCurrent(ticket, owner: epoch, resource: conversationId) else { return }
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }
}
