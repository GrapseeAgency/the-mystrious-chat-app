import SwiftUI
import PhotosUI

// ─────────────────────────────────────────────────────────────
// Wave 6 - profile edit (F-CP-04 + F-CP-09, profile-tab.tsx /
// handle-editor.tsx / avatar-editor.tsx parity): name (32), bio (140,
// web placeholder), status glyph picker (the 11 fixed stored values) +
// status text (48), 8 avatar color swatches, @handle editor (350 ms
// debounced live check with the verbatim copy), avatar upload
// (square ≤512 JPEG q0.85 → /api/uploads → PATCH). Only confirmed,
// current-session responses update the viewer mirror.
// ─────────────────────────────────────────────────────────────

/// Pure @handle availability state - the verdict derivation is testable
/// (handle-validation + suggestion tests) with zero networking involved.
struct HandleCheckState: Equatable {
    var value = ""
    /// the handle the latest availability result belongs to (staleness guard)
    var checkedValue: String?
    var available: Bool?
    var suggestion: String?
    /// 409 username_taken from the PATCH save (the server carries a suggestion)
    var clashSuggestion: String?
    var checking = false

    enum Verdict: Equatable {
        case empty
        case invalid
        case checking
        case free(String, suggestion: String?)
        case current
        case taken(String, suggestion: String?)
        case clash(String, suggestion: String?)
    }

    /// Web handle-editor verdict order: empty → invalid → checking →
    /// free → current-handle → taken → 409-clash.
    func verdict(currentHandle: String) -> Verdict {
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return .empty }
        guard pulseValidHandle(trimmed) else { return .invalid }
        if clashSuggestion != nil { return .clash(trimmed, suggestion: clashSuggestion) }
        if checking || checkedValue != trimmed { return .checking }
        if trimmed == currentHandle { return .current }
        if available == true { return .free(trimmed, suggestion: suggestion) }
        if available == false { return .taken(trimmed, suggestion: suggestion) }
        return .checking
    }

    /// The verbatim availability line (web handle-editor.tsx:137-224).
    static func message(for verdict: Verdict) -> String {
        switch verdict {
        case .empty: return "Type a handle, or leave empty."
        case .invalid: return "3–20 characters: a-z, 0-9, underscore."
        case .checking: return "" // spinner row
        case .free(let handle, _): return "@\(handle) is free"
        case .current: return "That's your current handle"
        case .taken(let handle, _): return "@\(handle) is taken"
        case .clash(let handle, _): return "@\(handle) was just taken"
        }
    }
}

@MainActor
struct ProfileEditView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs

    @Environment(\.dismiss) private var dismiss

    // field limits - the exact server contract (users/[id] route.ts)
    static let nameMax = 32
    static let aboutMax = 140
    static let statusMax = 48
    /// R18-b - the 11 stored status IDS (web STATUS_ICON_IDS); each renders
    /// through its PulseStatusIconId SF Symbol, never as a raw emoji.
    static let statusGlyphs = PulseStatusIconId.allCases.map(\.rawValue)
    /// The 8 avatar colors (web PULSE_COLORS).
    static let colors = ["emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan"]

    @State private var name = ""
    @State private var about = ""
    @State private var statusEmoji = ""
    @State private var statusText = ""
    @State private var color = "emerald"
    @State private var loaded = false
    @State private var loadingProfile = false
    @State private var profileLoadTask: Task<Void, Never>?
    @State private var profileLoadId = UUID()
    @State private var baselineForm = [String](repeating: "", count: 6)
    @State private var editorOwner: ProfileMutationGate.Owner?
    @State private var mutationGate = ProfileMutationGate()
    @State private var operationTask: Task<Void, Never>?
    @State private var operationId = UUID()

    // avatar pipeline
    @State private var photoItem: PhotosPickerItem?
    @State private var avatarUploading = false
    @State private var avatarPreview: UIImage?

    // Cover bytes retain a real 2:1 crop, rather than uploading a square avatar.
    @State private var coverItem: PhotosPickerItem?
    @State private var coverUploading = false

    // handle editor
    @State private var handleState = HandleCheckState()
    @State private var handleCheckTask: Task<Void, Never>?

    @State private var saving = false
    @State private var notice: String?

    private var viewer: PulseViewer? { prefs.viewer }

    var body: some View {
        NavigationStack {
            Form {
                if loaded {
                    identitySection
                    statusSection
                    colorSection
                    handleSection
                    saveSection
                } else {
                    Section {
                        if loadingProfile { ProgressView("Loading your profile…") }
                        if let notice { Text(notice).foregroundStyle(PulseTheme.amber600) }
                        if !loadingProfile { Button("Retry loading profile", action: seedFromViewer) }
                    }
                }
            }
            .navigationTitle("Edit profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear(perform: seedFromViewer)
            .onDisappear(perform: stopOperations)
            .onChange(of: session.sessionGeneration) { _, _ in stopOperations(); dismiss() }
            .onChange(of: prefs.viewer?.id) { _, _ in stopOperations(); dismiss() }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                photoItem = nil
                launchOperation { await uploadAvatar(item) }
            }
            .onChange(of: coverItem) { _, item in
                guard let item else { return }
                coverItem = nil
                launchOperation { await uploadCover(item) }
            }
        }
        // R17 Neo - sheets at 28pt (web sheet radius parity).
        .presentationCornerRadius(28)
    }

    // ── sections ─────────────────────────────────────────────

    private var identitySection: some View {
        Section("Identity") {
            HStack(spacing: 14) {
                if let avatarPreview {
                    Image(uiImage: avatarPreview)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 64, height: 64)
                        .clipShape(Circle())
                } else {
                    PulseAvatar(
                        name: viewer?.name ?? "You",
                        color: PulseTheme.color(named: color),
                        photoURL: PulseTheme.photoURL(viewer?.avatar),
                        size: 64,
                    )
                }
                PhotosPicker(selection: $photoItem, matching: .images) {
                    if avatarUploading {
                        ProgressView()
                    } else {
                        Text("Change photo")
                            .font(.footnote.weight(.semibold))
                    }
                }
                .tint(PulseTheme.accent)
                .disabled(operationTask != nil)
                // R14 5-b - the remove branch (web avatar-editor.tsx
                // runRemovePhoto): shown only while a photo exists, PATCHes
                // { avatar: "" } - the server nulls the column.
                if viewer?.avatar != nil {
                    if operationTask != nil {
                        EmptyView()
                    } else {
                        Button(role: .destructive) {
                            launchOperation { await removePhoto() }
                        } label: {
                            Text("Remove photo")
                                .font(.footnote.weight(.semibold))
                        }
                    }
                }
            }
            // R50-b - the profile cover: upload then PATCH cover (the same
            // wire contract web cover-editor.tsx uses).
            HStack(spacing: 14) {
                PhotosPicker(selection: $coverItem, matching: .images) {
                    if coverUploading {
                        ProgressView()
                    } else {
                        Text(viewer?.coverImage == nil ? "Add cover photo" : "Change cover")
                            .font(.footnote.weight(.semibold))
                    }
                }
                .tint(PulseTheme.accent)
                .disabled(operationTask != nil)
                if viewer?.coverImage != nil, operationTask == nil {
                    Button(role: .destructive) {
                        launchOperation { await removeCover() }
                    } label: {
                        Text("Remove cover")
                            .font(.footnote.weight(.semibold))
                    }
                }
            }
            TextField("Display name", text: $name)
                .maxLength($name, max: Self.nameMax)
            TextField(
                "Bio",
                text: $about,
                prompt: Text("Hey there! I'm using Pulse."),
                axis: .vertical,
            )
            .maxLength($about, max: Self.aboutMax)
            .lineLimit(2...4)
        }
    }

    private var statusSection: some View {
        Section("Status") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.statusGlyphs, id: \.self) { glyph in
                        glyphButton(glyph)
                    }
                }
                .padding(.vertical, 2)
            }
            TextField(
                "Status text",
                text: $statusText,
                prompt: Text("What's happening? (optional)"),
            )
            .maxLength($statusText, max: Self.statusMax)
        }
    }

    private func glyphButton(_ glyph: String) -> some View {
        // R18-b - stored values are icon IDs; each renders through its
        // PulseStatusIconId SF Symbol, never as a raw emoji.
        let id = PulseStatusIconId.normalize(glyph) ?? PulseStatusIconId.fallback
        let selected = statusEmoji == glyph
        return Button {
            PulseHaptics.tap()
            statusEmoji = selected ? "" : glyph
        } label: {
            Image(systemName: id.symbolName)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(selected ? PulseTheme.emberGlowTop : PulseTheme.textSecondary)
                .frame(width: 44, height: 44)
                .background(RoundedRectangle(cornerRadius: 12).fill(selected ? PulseTheme.emberGlowTop.opacity(0.16) : PulseTheme.chipFill))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(selected ? PulseTheme.emberGlowTop : Color.clear, lineWidth: 2),
                )
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("\(id.label)\(selected ? ", selected" : "")")
    }

    private var colorSection: some View {
        Section("Avatar color") {
            HStack(spacing: 10) {
                ForEach(Self.colors, id: \.self) { candidate in
                    swatch(candidate)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func swatch(_ candidate: String) -> some View {
        let selected = color == candidate
        return Button {
            PulseHaptics.tap()
            color = candidate
        } label: {
            Circle()
                .fill(PulseTheme.gradient(named: candidate))
                .frame(width: 34, height: 34)
                .overlay(
                    Circle().strokeBorder(PulseTheme.accent, lineWidth: selected ? 2.5 : 0),
                )
                .overlay(alignment: .center) {
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white)
                    }
                }
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("\(candidate) avatar\(selected ? ", selected" : "")")
    }

    private var handleSection: some View {
        Section {
            HStack(spacing: 8) {
                Text("@")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(PulseTheme.textTertiary)
                TextField("e.g. alice_chen", text: handleBinding)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            handleVerdictLine
        } header: {
            Text("Your @handle")
        } footer: {
            Text("3–20 characters: lowercase letters, digits, underscore. Friends can find you by it.")
        }
    }

    private var handleBinding: Binding<String> {
        Binding(
            get: { handleState.value },
            set: { newValue in
                handleState.clashSuggestion = nil
                handleState.value = OnboardingViewModel.sanitizeHandle(newValue)
                scheduleHandleCheck()
            },
        )
    }

    @ViewBuilder
    private var handleVerdictLine: some View {
        let verdict = handleState.verdict(currentHandle: viewer?.username ?? "")
        switch verdict {
        case .checking:
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text("Checking @\(handleState.value.trimmingCharacters(in: .whitespaces))…")
                    .font(.footnote)
                    .foregroundStyle(PulseTheme.textSecondary)
            }
        case .free, .current:
            Label(HandleCheckState.message(for: verdict), systemImage: "checkmark")
                .font(.footnote.weight(.medium))
                .foregroundStyle(PulseTheme.accent)
        case .empty, .invalid:
            Text(HandleCheckState.message(for: verdict))
                .font(.footnote)
                .foregroundStyle(PulseTheme.textSecondary)
        case .taken(let handle, let suggestion), .clash(let handle, let suggestion):
            VStack(alignment: .leading, spacing: 6) {
                Label(HandleCheckState.message(for: verdict), systemImage: "xmark")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(PulseTheme.amber600)
                if let suggestion, !suggestion.isEmpty {
                    Button {
                        PulseHaptics.tap()
                        handleState.clashSuggestion = nil
                        handleState.value = OnboardingViewModel.sanitizeHandle(suggestion)
                        scheduleHandleCheck()
                    } label: {
                        Text("Use @\(suggestion)")
                            .font(.caption.weight(.bold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(Capsule().fill(PulseTheme.amber500.opacity(0.15)))
                    }
                    .tint(PulseTheme.amber600)
                    .accessibilityLabel("Use @\(suggestion)")
                }
            }
        }
    }

    private var saveSection: some View {
        Section {
            Button {
                launchOperation { await save() }
            } label: {
                Group {
                    if saving {
                        ProgressView().tint(.white)
                    } else {
                        Label("Save changes", systemImage: "checkmark")
                            .font(.system(size: 15, weight: .bold))
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 44)
            }
            .listRowBackground(canSave ? PulseTheme.emerald : PulseTheme.zinc(300))
            .foregroundStyle(.white)
            .disabled(!canSave || operationTask != nil)
            if let notice {
                Text(notice)
                    .font(.footnote)
                    .foregroundStyle(PulseTheme.amber600)
            }
        } footer: {
            Text("Confirmed changes appear in your profile, chats and mentions.")
        }
    }

    // ── derived ──────────────────────────────────────────────

    private var nameDirty: Bool {
        name.trimmingCharacters(in: .whitespaces) != baselineForm[0].trimmingCharacters(in: .whitespaces)
    }
    private var aboutDirty: Bool {
        about.trimmingCharacters(in: .whitespaces) != baselineForm[1].trimmingCharacters(in: .whitespaces)
    }
    private var statusDirty: Bool {
        statusEmoji != baselineForm[2] || statusText != baselineForm[3]
    }
    private var colorDirty: Bool { color != baselineForm[4] }
    private var handleDirty: Bool {
        handleState.value.trimmingCharacters(in: .whitespaces) != baselineForm[5].trimmingCharacters(in: .whitespaces)
    }

    private var canSave: Bool {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        guard !trimmedName.isEmpty, trimmedName.utf16.count <= Self.nameMax else { return false }
        if handleDirty {
            let handle = handleState.value.trimmingCharacters(in: .whitespaces)
            if !handle.isEmpty && !pulseValidHandle(handle) { return false }
        }
        return nameDirty || aboutDirty || statusDirty || colorDirty || handleDirty
    }

    // ── actions ──────────────────────────────────────────────

    private var currentOwner: ProfileMutationGate.Owner? {
        guard let id = prefs.viewer?.id, session.viewer?.id == id,
              PulseWalletOperations.gatewayKey(session.api.baseURL) == PulseWalletOperations.gatewayKey(PulseEndpoints.gatewayURL) else { return nil }
        return .init(userId: id, session: session.sessionGeneration)
    }

    private func seedFromViewer() {
        guard let owner = currentOwner, let viewer else { return }
        if editorOwner == nil { editorOwner = owner }
        guard editorOwner == owner else { stopOperations(); dismiss(); return }
        mutationGate.bind(owner)
        guard !loaded, profileLoadTask == nil else { return }
        let api = session.api
        let id = UUID(); profileLoadId = id
        let oldExtras = [prefs.viewerAbout, prefs.viewerStatusEmoji, prefs.viewerStatusText]
        loadingProfile = true; notice = nil
        profileLoadTask = Task { @MainActor in
            defer { if profileLoadId == id { profileLoadTask = nil; loadingProfile = false } }
            do {
                let user = try await api.user(owner.userId)
                guard !Task.isCancelled, profileLoadId == id, currentOwner == owner, mutationGate.owner == owner else { return }
                guard user.id == owner.userId else {
                    throw PulseAPIClient.Failure(kind: .server, message: "Profile response did not match this account.")
                }
                adopt(user)
                baselineForm = formSnapshot
                loaded = true
                // Do not roll a locally updated mirror backward when a read was
                // already in flight. The editor's own baseline stays immutable.
                if prefs.viewer == viewer { prefs.setViewer(PulseViewer(from: user)) }
                if [prefs.viewerAbout, prefs.viewerStatusEmoji, prefs.viewerStatusText] == oldExtras {
                    prefs.setViewerProfile(about: user.about ?? "", statusEmoji: user.statusEmoji ?? "", statusText: user.statusText ?? "")
                }
            } catch {
                guard !Task.isCancelled, profileLoadId == id, currentOwner == owner, mutationGate.owner == owner else { return }
                notice = ChatsViewModel.describe(error)
            }
        }
    }

    private func launchOperation(_ work: @escaping @MainActor () async -> Void) {
        guard loaded, operationTask == nil, currentOwner == editorOwner, mutationGate.owner != nil else { return }
        let id = UUID(); operationId = id
        operationTask = Task { @MainActor in
            await work()
            if operationId == id { operationTask = nil }
        }
    }
    private func stopOperations() {
        operationId = UUID()
        profileLoadId = UUID(); profileLoadTask?.cancel(); profileLoadTask = nil; loadingProfile = false
        operationTask?.cancel(); operationTask = nil
        handleCheckTask?.cancel(); handleCheckTask = nil
        mutationGate.invalidate()
        avatarUploading = false; coverUploading = false; saving = false
    }
    private func accepts(_ ticket: ProfileMutationGate.Ticket) -> Bool {
        !Task.isCancelled && mutationGate.accepts(ticket, current: currentOwner)
    }

    /// 350 ms debounced live availability - skipped while re-typing the
    /// current handle (web HANDLE_DEBOUNCE_MS parity).
    private func scheduleHandleCheck() {
        handleCheckTask?.cancel()
        let candidate = handleState.value.trimmingCharacters(in: .whitespaces)
        guard !candidate.isEmpty, pulseValidHandle(candidate) else {
            handleState.checking = false
            return
        }
        handleState.checking = true
        let prefs = self.prefs
        let session = self.session
        let owner = currentOwner
        let api = session.api
        handleCheckTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled, currentOwner == owner, mutationGate.owner == owner else { return }
            if candidate == (prefs.viewer?.username ?? "") {
                handleState.checking = false
                handleState.checkedValue = candidate
                handleState.available = true
                return
            }
            do {
                let result = try await api.checkUsername(candidate)
                guard !Task.isCancelled, currentOwner == owner, mutationGate.owner == owner,
                      handleState.value.trimmingCharacters(in: .whitespaces) == candidate else { return }
                handleState.checkedValue = candidate
                handleState.available = result.available
                handleState.suggestion = result.suggestion
            } catch {
                guard !Task.isCancelled, currentOwner == owner, mutationGate.owner == owner,
                      handleState.value.trimmingCharacters(in: .whitespaces) == candidate else { return }
                handleState.checkedValue = candidate
                handleState.available = nil
            }
            handleState.checking = false
        }
    }

    private func removePhoto() async { await changeMedia(nil, cover: false) }
    private func removeCover() async { await changeMedia(nil, cover: true) }
    private func uploadAvatar(_ item: PhotosPickerItem) async { await changeMedia(item, cover: false) }
    private func uploadCover(_ item: PhotosPickerItem) async { await changeMedia(item, cover: true) }

    /// Acquire ownership before the first suspension (including the picker
    /// transfer), and never re-read session.api halfway through the operation.
    private func changeMedia(_ item: PhotosPickerItem?, cover: Bool) async {
        guard let ticket = mutationGate.begin(current: currentOwner) else { return }
        let api = session.api
        if cover { coverUploading = true } else { avatarUploading = true }
        defer {
            if mutationGate.active == ticket {
                mutationGate.finish(ticket)
                if cover { coverUploading = false } else { avatarUploading = false }
            }
        }
        do {
            var path = ""
            if let item {
                guard let photo = try await item.loadTransferable(type: PulsePickedPhoto.self) else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                guard accepts(ticket) else { return }
                guard let jpeg = cover ? PulseCoverImage.jpegData(from: photo.jpeg) : PulseAvatarImage.jpegData(from: photo.jpeg) else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                let receipt = try await api.uploadMedia(dataUrl: PulseAvatarImage.dataUrl(jpeg))
                path = try PulseAvatarImage.managedPath(receipt)
                guard accepts(ticket) else { return }
                guard !path.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
            }
            guard accepts(ticket) else { return }
            let user = try await api.updateProfile(userId: ticket.owner.userId, body: [cover ? "coverImage" : "avatar": path])
            guard accepts(ticket), let current = prefs.viewer else { return }
            guard user.id == ticket.owner.userId else {
                throw PulseAPIClient.Failure(kind: .server, message: "Profile response did not match this account. Refresh before retrying.")
            }
            // Media acknowledgement must not adopt the whole returned profile:
            // that would erase text currently being edited or unrelated fields.
            prefs.setViewer(PulseViewer(id: current.id, name: current.name, username: current.username,
                color: current.color, avatar: cover ? current.avatar : user.avatar,
                coverImage: cover ? user.coverImage : current.coverImage))
            avatarPreview = nil
            notice = nil
            PulseHaptics.success()
            session.toasts.show(cover ? "Cover photo updated" : "Profile photo updated")
        } catch {
            guard accepts(ticket) else { return }
            notice = ChatsViewModel.describe(error)
        }
    }

    private var formSnapshot: [String] { [name, about, statusEmoji, statusText, color, handleState.value] }

    /// Apply confirmed fields only. A late failure cannot roll preferences back
    /// to an earlier account/profile, and edits made while saving stay editable.
    private func save() async {
        guard canSave, let ticket = mutationGate.begin(current: currentOwner) else { return }
        let api = session.api
        let submitted = formSnapshot
        saving = true; notice = nil
        defer { if mutationGate.active == ticket { mutationGate.finish(ticket); saving = false } }
        var body: [String: Any] = [:]
        if nameDirty { body["name"] = name.trimmingCharacters(in: .whitespaces) }
        if aboutDirty { body["about"] = about.trimmingCharacters(in: .whitespaces) }
        if statusDirty { body["statusEmoji"] = statusEmoji; body["statusText"] = statusText }
        if colorDirty { body["color"] = color }
        let handle = handleState.value.trimmingCharacters(in: .whitespaces)
        if handleDirty { body["username"] = handle }
        do {
            let user = try await api.updateProfile(userId: ticket.owner.userId, body: body)
            guard accepts(ticket), let current = prefs.viewer else { return }
            guard user.id == ticket.owner.userId else {
                throw PulseAPIClient.Failure(kind: .server, message: "Profile response did not match this account. Refresh before retrying.")
            }
            prefs.setViewer(PulseViewer(id: current.id,
                name: body["name"] == nil ? current.name : user.name,
                username: body["username"] == nil ? current.username : user.username,
                color: body["color"] == nil ? current.color : user.color,
                avatar: current.avatar, coverImage: current.coverImage))
            prefs.setViewerProfile(
                about: body["about"] == nil ? prefs.viewerAbout : user.about ?? "",
                statusEmoji: body["statusEmoji"] == nil ? prefs.viewerStatusEmoji : user.statusEmoji ?? "",
                statusText: body["statusText"] == nil ? prefs.viewerStatusText : user.statusText ?? "")
            let confirmed = [user.name, user.about ?? "", user.statusEmoji ?? "", user.statusText ?? "", user.color ?? "emerald", user.username ?? ""]
            let keys = ["name", "about", "statusEmoji", "statusText", "color", "username"]
            for index in keys.indices where body[keys[index]] != nil { baselineForm[index] = confirmed[index] }
            PulseHaptics.success()
            if formSnapshot == submitted {
                adopt(user)
                session.toasts.show("Profile updated")
                dismiss()
            } else {
                notice = "Submitted changes saved. Your newer edits are still here."
            }
        } catch let failure as PulseAPIClient.Failure where failure.status == 409 {
            guard accepts(ticket) else { return }
            if handleState.value == submitted[5] { handleState.clashSuggestion = failure.suggestion }
            notice = failure.message ?? "That handle was just taken"
        } catch {
            guard accepts(ticket) else { return }
            notice = ChatsViewModel.describe(error)
        }
    }

    private func adopt(_ user: WireUser) {
        name = user.name
        about = user.about ?? ""
        statusEmoji = user.statusEmoji ?? ""
        statusText = user.statusText ?? ""
        color = user.color ?? "emerald"
        handleState.value = user.username ?? ""
        handleState.checkedValue = user.username
        handleState.available = true
        handleState.clashSuggestion = nil
    }
}

// ── tiny modifiers/helpers ────────────────────────────────────

private extension TextField {
    /// Hard cap while typing (web maxLength parity, no local state echo).
    func maxLength(_ binding: Binding<String>, max: Int) -> some View {
        self.onChange(of: binding.wrappedValue) { _, newValue in
            let clipped = ProfileTextLimit.clip(newValue, maximum: max)
            if clipped != newValue { binding.wrappedValue = clipped }
        }
    }
}
