import SwiftUI
import PhotosUI

// ─────────────────────────────────────────────────────────────
// Wave 6 — profile edit (F-CP-04 + F-CP-09, profile-tab.tsx /
// handle-editor.tsx / avatar-editor.tsx parity): name (32), bio (140,
// web placeholder), status glyph picker (the 11 fixed stored values) +
// status text (48), 8 avatar color swatches, @handle editor (350 ms
// debounced live check with the verbatim copy), avatar upload
// (square ≤512 JPEG q0.85 → /api/uploads → PATCH). Save is optimistic
// with rollback ("Profile updated").
// ─────────────────────────────────────────────────────────────

/// Pure @handle availability state — the verdict derivation is testable
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

struct ProfileEditView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs

    @Environment(\.dismiss) private var dismiss

    // field limits — the exact server contract (users/[id] route.ts)
    static let nameMax = 32
    static let aboutMax = 140
    static let statusMax = 48
    /// The 11 fixed stored status values (audit 6-a — web STATUS_GLYPH_CHOICES).
    static let statusGlyphs = ["🔥", "✨", "🎯", "☕", "🎧", "🌙", "💡", "🚀", "😴", "🍽️", "vacation"]
    /// The 8 avatar colors (web PULSE_COLORS).
    static let colors = ["emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan"]

    @State private var name = ""
    @State private var about = ""
    @State private var statusEmoji = ""
    @State private var statusText = ""
    @State private var color = "emerald"
    @State private var loaded = false

    // avatar pipeline
    @State private var photoItem: PhotosPickerItem?
    @State private var avatarUploading = false
    @State private var avatarPreview: UIImage?

    // handle editor
    @State private var handleState = HandleCheckState()
    @State private var handleCheckTask: Task<Void, Never>?

    @State private var saving = false
    @State private var notice: String?

    private var viewer: PulseViewer? { prefs.viewer }

    var body: some View {
        NavigationStack {
            Form {
                identitySection
                statusSection
                colorSection
                handleSection
                saveSection
            }
            .navigationTitle("Edit profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear(perform: seedFromViewer)
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                photoItem = nil
                Task { await uploadAvatar(item) }
            }
        }
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
        let display = pulseStatusGlyphDisplay(glyph)
        let selected = statusEmoji == glyph
        return Button {
            PulseHaptics.tap()
            statusEmoji = selected ? "" : glyph
        } label: {
            Text(display)
                .font(.system(size: 18))
                .frame(width: 44, height: 44)
                .background(RoundedRectangle(cornerRadius: 12).fill(selected ? PulseTheme.emerald.opacity(0.16) : PulseTheme.chipFill))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(selected ? PulseTheme.accent : Color.clear, lineWidth: 2),
                )
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("\(glyph == "vacation" ? "On vacation" : display)\(selected ? ", selected" : "")")
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
                Task { await save() }
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
            .disabled(!canSave || saving)
            if let notice {
                Text(notice)
                    .font(.footnote)
                    .foregroundStyle(PulseTheme.amber600)
            }
        } footer: {
            Text("Changes appear everywhere instantly — profile, chats and mentions.")
        }
    }

    // ── derived ──────────────────────────────────────────────

    private var nameDirty: Bool {
        name.trimmingCharacters(in: .whitespaces) != (viewer?.name ?? "")
    }
    private var aboutDirty: Bool {
        about.trimmingCharacters(in: .whitespaces) != (prefs.viewerAbout ?? "")
    }
    private var statusDirty: Bool {
        statusEmoji != (prefs.viewerStatusEmoji ?? "") || statusText != (prefs.viewerStatusText ?? "")
    }
    private var colorDirty: Bool { color != (viewer?.color ?? "emerald") }
    private var handleDirty: Bool {
        handleState.value.trimmingCharacters(in: .whitespaces) != (viewer?.username ?? "")
    }

    private var canSave: Bool {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        guard !trimmedName.isEmpty, trimmedName.count <= Self.nameMax else { return false }
        if handleDirty {
            let handle = handleState.value.trimmingCharacters(in: .whitespaces)
            if !handle.isEmpty && !pulseValidHandle(handle) { return false }
        }
        return nameDirty || aboutDirty || statusDirty || colorDirty || handleDirty
    }

    // ── actions ──────────────────────────────────────────────

    private func seedFromViewer() {
        guard !loaded, let viewer else { return }
        loaded = true
        name = viewer.name
        about = prefs.viewerAbout ?? ""
        statusEmoji = prefs.viewerStatusEmoji ?? ""
        statusText = prefs.viewerStatusText ?? ""
        color = viewer.color ?? "emerald"
        handleState.value = viewer.username ?? ""
    }

    /// 350 ms debounced live availability — skipped while re-typing the
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
        handleCheckTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            if candidate == (prefs.viewer?.username ?? "") {
                handleState.checking = false
                handleState.checkedValue = candidate
                handleState.available = true
                return
            }
            do {
                let result = try await session.api.checkUsername(candidate)
                handleState.checkedValue = candidate
                handleState.available = result.available
                handleState.suggestion = result.suggestion
            } catch {
                handleState.checkedValue = candidate
                handleState.available = nil
            }
            handleState.checking = false
        }
    }

    /// PhotosPicker → square ≤512 JPEG q0.85 → /api/uploads → PATCH avatar.
    /// Optimistic local preview; failures surface honestly.
    private func uploadAvatar(_ item: PhotosPickerItem) async {
        guard let viewer, !avatarUploading else { return }
        guard let raw = try? await item.loadTransferable(type: Data.self),
              let jpeg = PulseAvatarImage.jpegData(from: raw) else {
            notice = "Couldn't read that image — try another one"
            return
        }
        avatarUploading = true
        defer { avatarUploading = false }
        do {
            let path = try await session.api.uploadMedia(dataUrl: PulseAvatarImage.dataUrl(jpeg))
            let user = try await session.api.updateProfile(userId: viewer.id, body: ["avatar": path])
            adopt(user)
            avatarPreview = nil
            PulseHaptics.success()
            session.toasts.show("Profile updated")
        } catch {
            avatarPreview = nil
            notice = ChatsViewModel.describe(error)
        }
    }

    /// Optimistic save — the viewer prefs flip immediately, the PATCH runs,
    /// and any failure rolls the snapshot back (never a silent lie).
    private func save() async {
        guard let viewer, canSave, !saving else { return }
        saving = true
        notice = nil
        defer { saving = false }

        let snapshot = viewer
        var body: [String: Any] = [:]
        if nameDirty { body["name"] = name.trimmingCharacters(in: .whitespaces) }
        let trimmedAbout = about.trimmingCharacters(in: .whitespaces)
        if !trimmedAbout.isEmpty && aboutDirty { body["about"] = trimmedAbout }
        if statusDirty {
            body["statusEmoji"] = statusEmoji
            body["statusText"] = statusText
        }
        if colorDirty { body["color"] = color }
        let handle = handleState.value.trimmingCharacters(in: .whitespaces)
        if handleDirty && !handle.isEmpty { body["username"] = handle }

        // Optimistic apply (name/color/handle are what the prefs carry).
        var optimistic = PulseViewer(
            id: viewer.id,
            name: name.trimmingCharacters(in: .whitespaces).isEmpty ? viewer.name : name.trimmingCharacters(in: .whitespaces),
            username: handle.isEmpty ? viewer.username : handle,
            color: color,
            avatar: viewer.avatar,
        )
        prefs.setViewer(optimistic)

        do {
            let user = try await session.api.updateProfile(userId: viewer.id, body: body)
            optimistic = PulseViewer(id: user.id, name: user.name, username: user.username, color: user.color, avatar: user.avatar)
            prefs.setViewer(optimistic)
            prefs.setViewerProfile(
                about: user.about ?? "",
                statusEmoji: user.statusEmoji ?? "",
                statusText: user.statusText ?? "",
            )
            adopt(user)
            PulseHaptics.success()
            session.toasts.show("Profile updated")
            dismiss()
        } catch let failure as PulseAPIClient.Failure where failure.status == 409 {
            // Handle clash between check and save — surface the server suggestion.
            prefs.setViewer(snapshot)
            handleState.clashSuggestion = failure.suggestion
            notice = failure.message ?? "That handle was just taken"
        } catch {
            prefs.setViewer(snapshot)
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

// ── small prefs extensions (Wave 6 additive seam) ─────────────

extension PulsePrefs {
    /// The full viewer profile fields live on the WIRE user, not the stored
    /// PulseViewer — mirror them here (UserDefaults-backed JSON on the same
    /// pulse.viewer payload keys) so the edit form seeds + persists status.
    var viewerAbout: String? { extraProfile["about"] }
    var viewerStatusEmoji: String? { extraProfile["statusEmoji"] }
    var viewerStatusText: String? { extraProfile["statusText"] }

    private var extraProfile: [String: String] {
        guard let data = UserDefaults.standard.data(forKey: "pulse.viewer.profile"),
              let map = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return map
    }

    func setViewerProfile(about: String?, statusEmoji: String?, statusText: String?) {
        var map: [String: String] = [:]
        if let about { map["about"] = about }
        if let statusEmoji { map["statusEmoji"] = statusEmoji }
        if let statusText { map["statusText"] = statusText }
        if let data = try? JSONEncoder().encode(map) {
            UserDefaults.standard.set(data, forKey: "pulse.viewer.profile")
        }
        objectWillChange.send()
    }
}

// ── tiny modifiers/helpers ────────────────────────────────────

private extension TextField {
    /// Hard cap while typing (web maxLength parity, no local state echo).
    func maxLength(_ binding: Binding<String>, max: Int) -> some View {
        self.onChange(of: binding.wrappedValue) { _, newValue in
            if newValue.count > max {
                binding.wrappedValue = String(newValue.prefix(max))
            }
        }
    }
}
