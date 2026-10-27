import SwiftUI

// Wave 6 - the folder manager (F-FD-02/03, folders-sheet.tsx parity):
// list mode with inline rename, icon-id presets, two-tap delete (2600 ms
// window) and a membership editor over every real chat; create form with
// the 8 icon presets + 24-char name. Every mutation is the REAL endpoint:
// POST/PATCH/DELETE /api/folders[/id] + PUT /api/folders/{id}/conversations
// (FULL ordered replace). Toasts are the verbatim web copy.

/// Membership PUT payload planner - pure, unit-tested. The saved array is
/// the checked set, expressed in the CHAT LIST's order (web parity: the
/// checkbox list is the conversation list, so the folder order follows it).
enum FolderMembership {
    static func orderedSelection(checked: Set<String>, listOrder: [String]) -> [String] {
        var seen = Set<String>()
        let visible = listOrder.filter { checked.contains($0) && seen.insert($0).inserted }
        // Archived/filtered chats are not an instruction to remove existing membership.
        return visible + checked.subtracting(seen).sorted()
    }
}

struct FoldersManageSheet: View {
    @ObservedObject var session: PulseSession
    /// The live chat list (active, non-archived) - the membership editor's
    /// checkbox rows, in display order.
    let conversations: [WireConversationSummary]
    /// Fired after ANY mutation lands so the parent refetches folders.
    var onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var folders: [WireFolder] = []
    @State private var loading = true
    @State private var loadError: String?
    @State private var readTicket = UUID()
    @State private var mode: Mode = .list
    @State private var busyFolderId: String?

    // create form
    @State private var newName = ""
    @State private var newIcon = PulseFolderIconId.fallback.rawValue

    // rename/edit
    @State private var editingId: String?
    @State private var editName = ""
    @State private var editIcon: String?

    // two-tap delete (2600 ms window, PulseTwoTap)
    @State private var deleteArmedId: String?
    @State private var deleteArmedAt: Date?

    // membership editor
    @State private var membershipFolder: WireFolder?
    @State private var checked: Set<String> = []

    /// R18-b - the 8 pickable folder icon IDS (web FOLDER_ICON_IDS), rendered
    /// through their PulseFolderIconId SF Symbols, never as raw emoji.
    static let iconPresets = PulseFolderIconId.allCases.map(\.rawValue)

    private enum Mode { case list, create }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                ScrollView {
                    LazyVStack(spacing: 8) {
                        switch mode {
                        case .list: listContent
                        case .create: createForm
                        }
                        Color.clear.frame(height: 16)
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                }
            }
            .disabled(busyFolderId != nil)
            .navigationTitle(mode == .create ? "New folder" : "Chat folders")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if mode == .create {
                        Button("Back") { withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { mode = .list } }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(mode == .create ? "Cancel" : "Done") {
                        if mode == .create {
                            withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { mode = .list }
                        } else {
                            dismiss()
                        }
                    }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task(id: session.sessionGeneration) {
            folders = []; membershipFolder = nil; editingId = nil; busyFolderId = nil
            newName = ""; newIcon = PulseFolderIconId.fallback.rawValue; checked = []
            await reload()
        }
    }

    // list mode

    @ViewBuilder
    private var listContent: some View {
        if let loadError {
            Button { Task { await reload() } } label: { Text("\(loadError) Tap to retry.").font(.footnote) }
        }
        if loading && folders.isEmpty {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(PulseTheme.glassFill)
                .frame(height: 120)
        } else if folders.isEmpty && loadError == nil {
            VStack(spacing: 8) {
                Image(systemName: "folder.badge.plus")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(PulseTheme.textTertiary)
                Text("No folders yet")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Text("Folders group your chats - create one to start sorting.")
                    .font(.system(size: 12))
                    .foregroundStyle(PulseTheme.textSecondary)
                Button {
                    withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { mode = .create }
                } label: {
                    Text("Create a folder")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 40)
                        .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.brandGradient))
                }
                .buttonStyle(PulseButtonStyle())
            }
            .padding(.vertical, 24)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial))
        } else {
            ForEach(folders, id: \.id) { folder in
                if membershipFolder?.id == folder.id {
                    membershipEditor(folder)
                } else {
                    folderRow(folder)
                }
            }
            Button {
                PulseHaptics.tap()
                withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { mode = .create }
            } label: {
                Label("New folder", systemImage: "plus")
                    .font(.system(size: 13.5, weight: .bold))
                    .foregroundStyle(PulseTheme.accent)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(RoundedRectangle(cornerRadius: 14).fill(PulseTheme.glassFill))
                    .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
            }
            .buttonStyle(PulseButtonStyle())
        }
    }

    private func folderRow(_ folder: WireFolder) -> some View {
        let editing = editingId == folder.id
        let deleteArmed = deleteArmedId == folder.id
        let count = folder.conversationIds?.count ?? 0
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Image(systemName: PulseFolderIconId.normalize(editIcon ?? folder.emoji).symbolName)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(PulseTheme.accent)
                    .frame(width: 22)
                if editing {
                    TextField("Folder name", text: $editName)
                        .textFieldStyle(.plain)
                        .font(.system(size: 14, weight: .semibold))
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 8).fill(PulseTheme.chipFill))
                        .submitLabel(.done)
                        .onSubmit { Task { await commitEdit(folder) } }
                } else {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(folder.name)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                        Text(count == 1 ? "1 chat" : "\(count) chats")
                            .font(.system(size: 11))
                            .foregroundStyle(PulseTheme.textTertiary)
                    }
                }
                Spacer()
                if busyFolderId == folder.id {
                    ProgressView().controlSize(.small)
                }
                // reorder (position PATCH - GET returns position asc)
                if editing {
                    Button {
                        PulseHaptics.tap()
                        move(folder, by: -1)
                    } label: {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 12, weight: .semibold))
                            .frame(width: 30, height: 30)
                            .background(Circle().fill(PulseTheme.chipFill))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .disabled(folders.first?.id == folder.id)
                    Button {
                        PulseHaptics.tap()
                        move(folder, by: 1)
                    } label: {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 12, weight: .semibold))
                            .frame(width: 30, height: 30)
                            .background(Circle().fill(PulseTheme.chipFill))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .disabled(folders.last?.id == folder.id)
                }
                Button {
                    PulseHaptics.tap()
                    if editing {
                        Task { await commitEdit(folder) }
                    } else {
                        editingId = folder.id
                        editName = folder.name
                        editIcon = nil
                    }
                } label: {
                    Image(systemName: editing ? "checkmark" : "pencil")
                        .font(.system(size: 12, weight: .semibold))
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(editing ? PulseTheme.emerald500.opacity(0.2) : PulseTheme.chipFill))
                        .foregroundStyle(editing ? PulseTheme.accent : PulseTheme.textSecondary)
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel(editing ? "Save name" : "Rename \(folder.name)")
                // Two-tap delete - first tap arms for 2600 ms, second executes.
                Button {
                    PulseHaptics.tap()
                    handleDeleteTap(folder)
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(deleteArmed ? .white : PulseTheme.rose500)
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(deleteArmed ? PulseTheme.rose500 : PulseTheme.rose500.opacity(0.12)))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel(deleteArmed ? "Tap again to delete \(folder.name)" : "Delete \(folder.name)")
            }
            if editing {
                // icon preset row (the same 8 the create form uses)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(Self.iconPresets, id: \.self) { preset in
                            Button {
                                PulseHaptics.tap()
                                editIcon = preset
                            } label: {
                                Image(systemName: PulseFolderIconId(rawValue: preset)?.symbolName ?? "folder")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(editIcon == preset ? PulseTheme.accent : PulseTheme.textSecondary)
                                    .frame(width: 36, height: 36)
                                    .background(RoundedRectangle(cornerRadius: 9).fill(editIcon == preset ? PulseTheme.emerald500.opacity(0.2) : PulseTheme.chipFill))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 9)
                                            .strokeBorder(editIcon == preset ? PulseTheme.accent : Color.clear, lineWidth: 1.5),
                                    )
                            }
                            .buttonStyle(PulseButtonStyle())
                            .accessibilityLabel("Icon \(preset)\(editIcon == preset ? ", selected" : "")")
                        }
                    }
                }
            }
            Button {
                PulseHaptics.tap()
                openMembers(folder)
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "checklist")
                    Text("Choose chats")
                    Spacer()
                    Image(systemName: "chevron.right")
                }
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.accent)
            }
            .buttonStyle(.plain)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.ultraThinMaterial))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
    }

    // create mode

    private var createFill: AnyShapeStyle {
        PulseFolderDraft.isValidName(newName) ? AnyShapeStyle(PulseTheme.brandGradient) : AnyShapeStyle(PulseTheme.zinc(300))
    }

    private var createIconRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Pick an icon")
                .font(.system(size: 11, weight: .semibold))
                .textCase(.uppercase)
                .foregroundStyle(PulseTheme.textSecondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.iconPresets, id: \.self) { preset in
                        iconButton(preset)
                    }
                }
            }
        }
    }

    private func iconButton(_ preset: String) -> some View {
        let selected = newIcon == preset
        return Button {
            PulseHaptics.tap()
            newIcon = preset
        } label: {
            Image(systemName: PulseFolderIconId(rawValue: preset)?.symbolName ?? "folder")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(selected ? PulseTheme.accent : PulseTheme.textSecondary)
                .frame(width: 46, height: 46)
                .background(RoundedRectangle(cornerRadius: 12).fill(selected ? PulseTheme.emerald500.opacity(0.2) : PulseTheme.chipFill))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(selected ? PulseTheme.accent : Color.clear, lineWidth: 2),
                )
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("Icon \(preset)\(selected ? ", selected" : "")")
    }

    private var createForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            createIconRow
            Text("Name")
                .font(.system(size: 11, weight: .semibold))
                .textCase(.uppercase)
                .foregroundStyle(PulseTheme.textSecondary)
            TextField("e.g. Work", text: $newName)
                .textFieldStyle(.plain)
                .font(.system(size: 15, weight: .semibold))
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.rowFill))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(PulseTheme.hairlineSoft, lineWidth: 1))
                .onChange(of: newName) { _, value in
                    if value.count > PulseFolderDraft.nameMax {
                        newName = String(value.prefix(PulseFolderDraft.nameMax))
                    }
                }
            Text("\(newName.trimmingCharacters(in: .whitespaces).count)/\(PulseFolderDraft.nameMax)")
                .font(.system(size: 10.5))
                .foregroundStyle(PulseTheme.textTertiary)
            Button {
                Task { await create() }
            } label: {
                Group {
                    if busyFolderId == "creating" {
                        ProgressView().tint(.white)
                    } else {
                        Text("Create folder")
                            .font(.system(size: 14, weight: .bold))
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 46)
                .background(RoundedRectangle(cornerRadius: 14).fill(createFill))
                .foregroundStyle(.white)
            }
            .buttonStyle(PulseButtonStyle())
            .disabled(!PulseFolderDraft.isValidName(newName) || busyFolderId == "creating")
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
    }

    // membership editor

    private func membershipEditor(_ folder: WireFolder) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Chats in \(folder.name)")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                Spacer()
                Text("\(checked.count) selected")
                    .font(.system(size: 11))
                    .foregroundStyle(PulseTheme.textTertiary)
            }
            if conversations.isEmpty {
                Text("No chats yet - the list fills in as conversations arrive.")
                    .font(.system(size: 12))
                    .foregroundStyle(PulseTheme.textSecondary)
            } else {
                ForEach(conversations, id: \.id) { conv in
                    Button {
                        PulseHaptics.tap()
                        if checked.contains(conv.id) {
                            checked.remove(conv.id)
                        } else {
                            checked.insert(conv.id)
                        }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: checked.contains(conv.id) ? "checkmark.circle.fill" : "circle")
                                .font(.system(size: 19))
                                .foregroundStyle(checked.contains(conv.id) ? PulseTheme.accent : PulseTheme.zinc(300))
                            RowAvatar(
                                name: rowTitle(conv),
                                colorName: conv.isGroup ? nil : conv.members.first?.color,
                                photoPath: conv.photo ?? conv.members.first?.avatar,
                                size: 30,
                            )
                            Text(rowTitle(conv))
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(PulseTheme.titleOnPanel)
                                .lineLimit(1)
                            Spacer()
                        }
                        .padding(.vertical, 5)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            Button {
                Task { await saveMembership(folder) }
            } label: {
                Group {
                    if busyFolderId == folder.id {
                        ProgressView().tint(.white)
                    } else {
                        Text("Save")
                            .font(.system(size: 14, weight: .bold))
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.brandGradient))
                .foregroundStyle(.white)
            }
            .buttonStyle(PulseButtonStyle())
            .disabled(busyFolderId == folder.id)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(PulseTheme.emerald500.opacity(0.05)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(PulseTheme.emerald500.opacity(0.3), lineWidth: 1))
    }

    private func rowTitle(_ conv: WireConversationSummary) -> String {
        if let name = conv.name?.trimmingCharacters(in: .whitespaces), !name.isEmpty { return name }
        let others = conv.members.filter { $0.id != session.viewer?.id }
        return others.first?.name ?? "Chat"
    }

    // actions

    private func reload() async {
        let epoch = session.sessionGeneration, api = session.api, ticket = UUID()
        readTicket = ticket; loading = true
        do {
            let page = try await api.folderSnapshot()
            guard !Task.isCancelled, session.sessionGeneration == epoch, readTicket == ticket else { return }
            folders = page; loadError = nil; loading = false
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch, readTicket == ticket else { return }
            if let failure = error as? PulseAPIClient.Failure, [.auth, .forbidden].contains(failure.kind) { folders = [] }
            loading = false; loadError = "Could not refresh folders."
        }
    }

    private func create() async {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard PulseFolderDraft.isValidName(name), busyFolderId == nil else { return }
        let epoch = session.sessionGeneration, api = session.api
        readTicket = UUID(); busyFolderId = "creating"
        defer { if session.sessionGeneration == epoch { busyFolderId = nil } }
        do {
            let folder = try await api.createFolder(name: name, emoji: newIcon)
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            PulseHaptics.success()
            folders.append(folder)
            session.toasts.show("Folder \(folder.name) created")
            newName = ""
            newIcon = PulseFolderIconId.fallback.rawValue
            withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { mode = .list }
            await reload()
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            onChanged()
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

    private func commitEdit(_ folder: WireFolder) async {
        guard busyFolderId == nil else { return }
        let name = editName.trimmingCharacters(in: .whitespaces)
        let emojiChanged = editIcon != nil && editIcon != folder.emoji
        let nameChanged = PulseFolderDraft.isValidName(name) && name != folder.name
        guard nameChanged || emojiChanged else {
            editingId = nil
            editIcon = nil
            return
        }
        let epoch = session.sessionGeneration, api = session.api
        readTicket = UUID(); busyFolderId = folder.id
        defer { if session.sessionGeneration == epoch { busyFolderId = nil } }
        do {
            let fresh = try await api.updateFolder(
                id: folder.id,
                name: nameChanged ? name : nil,
                emoji: emojiChanged ? editIcon : nil,
                position: nil,
            )
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            if let index = folders.firstIndex(where: { $0.id == fresh.id }) { folders[index] = fresh }
            if nameChanged { session.toasts.show("Renamed to “\(fresh.name)”") }
            PulseHaptics.success()
            editingId = nil
            editIcon = nil
            await reload()
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            onChanged()
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

    private func handleDeleteTap(_ folder: WireFolder) {
        let now = Date()
        if deleteArmedId == folder.id,
           PulseTwoTap.shouldExecute(
               nowMs: Int(now.timeIntervalSince1970 * 1000),
               armedAtMs: deleteArmedAt.map { Int($0.timeIntervalSince1970 * 1000) },
           ) {
            deleteArmedId = nil
            deleteArmedAt = nil
            Task { await delete(folder) }
        } else {
            deleteArmedId = folder.id
            deleteArmedAt = now
            session.toasts.show("Tap delete again to remove “\(folder.name)”")
        }
    }

    private func delete(_ folder: WireFolder) async {
        guard busyFolderId == nil else { return }
        let epoch = session.sessionGeneration, api = session.api
        readTicket = UUID(); busyFolderId = folder.id
        defer { if session.sessionGeneration == epoch { busyFolderId = nil } }
        do {
            try await api.deleteFolder(id: folder.id)
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            PulseHaptics.success()
            folders.removeAll { $0.id == folder.id }
            session.toasts.show("Folder deleted - chats stay in your list")
            await reload()
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            onChanged()
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

    private func move(_ folder: WireFolder, by delta: Int) {
        guard busyFolderId == nil,
              let index = folders.firstIndex(where: { $0.id == folder.id }) else { return }
        let target = index + delta
        guard folders.indices.contains(target) else { return }
        let epoch = session.sessionGeneration, api = session.api
        readTicket = UUID(); busyFolderId = folder.id
        Task {
            defer { if session.sessionGeneration == epoch { busyFolderId = nil } }
            do {
                _ = try await api.updateFolder(id: folder.id, name: nil, emoji: nil, position: target)
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                folders.swapAt(index, target)
                await reload()
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                onChanged()
            } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                session.toasts.show(ChatsViewModel.describe(error))
            }
        }
    }

    private func openMembers(_ folder: WireFolder) {
        membershipFolder = folder
        checked = Set(folder.conversationIds ?? [])
    }

    private func saveMembership(_ folder: WireFolder) async {
        guard busyFolderId == nil else { return }
        let epoch = session.sessionGeneration, api = session.api
        readTicket = UUID(); busyFolderId = folder.id
        defer { if session.sessionGeneration == epoch { busyFolderId = nil } }
        let payload = FolderMembership.orderedSelection(
            checked: checked,
            listOrder: conversations.map(\.id),
        )
        do {
            let fresh = try await api.saveFolderMembership(folderId: folder.id, conversationIds: payload)
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            PulseHaptics.success()
            if let index = folders.firstIndex(where: { $0.id == fresh.id }) { folders[index] = fresh }
            let saved = fresh.conversationIds?.count ?? payload.count
            session.toasts.show(saved == 1 ? "1 chat saved to the folder" : "\(saved) chats saved to the folder")
            membershipFolder = nil
            await reload()
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            onChanged()
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }
}
