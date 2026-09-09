import SwiftUI

// ─────────────────────────────────────────────────────────────
// Pulse onboarding — native mirror of the web OnboardingScreen,
// two steps:
//   1. display name + avatar color (existing reclaim-by-name flow)
//   2. @handle picker — auto-suggested from the name, live
//      availability via GET /api/users/check-username (debounced),
//      skippable. Creates the real account via POST /api/users.
// ─────────────────────────────────────────────────────────────

// MARK: - View model

@MainActor
final class OnboardingViewModel: ObservableObject {
    enum Step { case name, handle }

    static let nameMax = 32
    static let usernameMin = 3
    static let usernameMax = 20
    static let checkDebounceNanos: UInt64 = 350_000_000

    @Published var step: Step = .name
    @Published var name = ""
    @Published var color = "emerald"
    @Published var nameTaken = false
    // handle step
    @Published var handle = ""
    @Published private(set) var checking = false
    /// the handle the latest availability result belongs to (staleness guard)
    @Published private(set) var checkedHandle: String?
    @Published private(set) var checkAvailable: Bool?
    @Published private(set) var checkSuggestion: String?
    @Published var serverTakenMessage: String?
    @Published var serverTakenSuggestion: String?
    @Published private(set) var pending = false
    @Published private(set) var signingIn = false
    @Published var notice: String?

    private let api: PulseAPIClient
    private var checkTask: Task<Void, Never>?

    init(api: PulseAPIClient) {
        self.api = api
    }

    // ── handle math — mirrors normalizeUsername on the server ──

    static func isValidHandle(_ value: String) -> Bool {
        guard (usernameMin...usernameMax).contains(value.count) else { return false }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789_")
        return value.unicodeScalars.allSatisfy { allowed.contains($0) }
    }

    /// Auto-suggest a handle from a display name (lowercase, sanitized).
    static func suggestHandle(from name: String) -> String {
        var out = ""
        for ch in name.trimmingCharacters(in: .whitespaces).lowercased() {
            if (ch >= "a" && ch <= "z") || (ch >= "0" && ch <= "9") || ch == "_" {
                out.append(ch)
            } else {
                out.append("_")
            }
        }
        while out.first == "_" { out.removeFirst() }
        while out.last == "_" { out.removeLast() }
        while out.contains("__") { out = out.replacingOccurrences(of: "__", with: "_") }
        return String(out.prefix(usernameMax))
    }

    /// Keep only chars the server would accept, lowercased, capped.
    static func sanitizeHandle(_ value: String) -> String {
        let lowered = value.lowercased()
        var out = ""
        for ch in lowered where (ch >= "a" && ch <= "z") || (ch >= "0" && ch <= "9") || ch == "_" {
            out.append(ch)
        }
        return out.count > usernameMax ? String(out.prefix(usernameMax)) : out
    }

    var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }
    var validName: Bool {
        let trimmed = trimmedName
        return !trimmed.isEmpty && trimmed.count <= Self.nameMax
    }

    // ── intents ──────────────────────────────────────────────

    func startHandleStep() {
        guard validName else { return }
        checkTask?.cancel()
        handle = Self.suggestHandle(from: trimmedName)
        serverTakenMessage = nil
        serverTakenSuggestion = nil
        checkedHandle = nil
        checkAvailable = nil
        checkSuggestion = nil
        checking = false
        notice = nil
        step = .handle
        scheduleCheck()
    }

    func backToName() {
        checkTask?.cancel()
        checking = false
        step = .name
    }

    func setHandle(_ value: String) {
        handle = Self.sanitizeHandle(value)
        serverTakenMessage = nil
        serverTakenSuggestion = nil
        scheduleCheck()
    }

    func useSuggestion(_ suggestion: String) {
        setHandle(suggestion)
    }

    /// Start chatting — create the account WITH the picked @handle.
    func submit(onSuccess: @escaping (WireUser) -> Void) {
        let trimmed = handle.trimmingCharacters(in: .whitespaces)
        guard Self.isValidHandle(trimmed), !pending, serverTakenMessage == nil else { return }
        create(username: trimmed, onSuccess: onSuccess)
    }

    /// Skip for now — create the account without a handle.
    func skip(onSuccess: @escaping (WireUser) -> Void) {
        guard !pending else { return }
        create(username: nil, onSuccess: onSuccess)
    }

    /// "That's me — log in instead" — the web's reclaim-by-name affordance.
    func loginInstead(onSuccess: @escaping (WireUser) -> Void) {
        guard validName, !signingIn else { return }
        signingIn = true
        notice = nil
        Task { [weak self] in
            guard let self else { return }
            defer { self.signingIn = false }
            do {
                if let user = try await self.api.lookupUserByName(self.trimmedName) {
                    onSuccess(user)
                } else {
                    self.notice = "No Pulse account with that name."
                }
            } catch {
                self.notice = Self.message(of: error)
            }
        }
    }

    // ── plumbing ─────────────────────────────────────────────

    private func create(username: String?, onSuccess: @escaping (WireUser) -> Void) {
        let trimmed = trimmedName
        guard !trimmed.isEmpty, !pending else { return }
        pending = true
        notice = nil
        Task { [weak self] in
            guard let self else { return }
            defer { self.pending = false }
            do {
                let user = try await self.api.createUser(name: trimmed, color: self.color, username: username)
                onSuccess(user)
            } catch let failure as PulseAPIClient.Failure {
                if failure.code == "username_taken" {
                    self.serverTakenMessage = failure.message
                    self.serverTakenSuggestion = failure.suggestion
                } else if failure.status == 409 {
                    // display-name clash → step back and reuse the "log in instead" flow
                    self.step = .name
                    self.nameTaken = true
                } else if failure.status == nil || failure.status == 404 || (500...599).contains(failure.status ?? 0) {
                    // offline-first: no live gateway answered → local identity.
                    // Onboarding completes; the app runs offline-first from here.
                    onSuccess(Self.localIdentity(name: trimmed, color: self.color, username: username))
                } else {
                    self.notice = failure.message ?? "Network error — try again."
                }
            } catch {
                self.notice = "Network error — try again."
            }
        }
    }

    /// Debounced live availability — same 350ms rhythm as the web picker.
    private func scheduleCheck() {
        checkTask?.cancel()
        let candidate = handle
        guard Self.isValidHandle(candidate) else {
            checking = false
            checkedHandle = nil
            checkAvailable = nil
            checkSuggestion = nil
            return
        }
        checkTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: Self.checkDebounceNanos)
            guard !Task.isCancelled, self.handle == candidate, self.step == .handle else { return }
            self.checking = true
            do {
                let result = try await self.api.checkUsernameWithFallback(candidate)
                guard !Task.isCancelled, self.handle == candidate, self.step == .handle else { return }
                self.checking = false
                self.checkedHandle = candidate
                self.checkAvailable = result.available
                self.checkSuggestion = result.suggestion
            } catch {
                guard !Task.isCancelled, self.handle == candidate, self.step == .handle else { return }
                // web parity: a failed probe shows no line, never a false verdict
                self.checking = false
                self.checkedHandle = nil
                self.checkAvailable = nil
                self.checkSuggestion = nil
            }
        }
    }

    static func message(of error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure {
            // Transport-level errors carry raw engine strings — humans get copy.
            if failure.status == nil { return "Can't reach the Pulse server — check your connection." }
            if let message = failure.message { return message }
        }
        return "Network error — try again."
    }

    /// Offline identity — stable random id, same shape as a server row.
    static func localIdentity(name: String, color: String, username: String?) -> WireUser {
        let suffix = UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "")
        return WireUser(
            id: "local_" + String(suffix.prefix(12)),
            name: name,
            username: username,
            about: nil,
            color: color,
            avatar: nil,
            statusEmoji: nil,
            statusText: nil,
            createdAt: nil,
            lastSeenAt: nil,
            verified: nil,
        )
    }
}

// MARK: - View

struct OnboardingView: View {
    let session: PulseSession
    let prefs: PulsePrefs
    var onPicked: () -> Void

    @StateObject private var viewModel: OnboardingViewModel
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var glowUp = false

    init(session: PulseSession, prefs: PulsePrefs, onPicked: @escaping () -> Void) {
        self.session = session
        self.prefs = prefs
        self.onPicked = onPicked
        _viewModel = StateObject(wrappedValue: OnboardingViewModel(api: session.api))
    }

    // zinc slices the web screen uses, per color scheme
    private var zinc900: Color { colorScheme == .dark ? Color(hex: 0xFAFAFA) : Color(hex: 0x18181B) }
    private var zinc400: Color { Color(hex: 0xA1A1AA) }
    private var zinc500: Color { Color(hex: 0x71717A) }
    private var zinc200: Color { colorScheme == .dark ? Color(hex: 0x3F3F46) : Color(hex: 0xE4E4E7) }
    private var zincField: Color { colorScheme == .dark ? Color(hex: 0x27272A) : Color(hex: 0xFAFAFA) }
    private var emerald600: Color { Color(hex: 0x059669) }
    private var amberText: Color { colorScheme == .dark ? Color(hex: 0xFBBF24) : Color(hex: 0xD97706) }

    private var stepTransition: AnyTransition {
        .asymmetric(
            insertion: .move(edge: .trailing).combined(with: .opacity),
            removal: .move(edge: .leading).combined(with: .opacity),
        )
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                hero
                wordmark
                ZStack {
                    switch viewModel.step {
                    case .name:
                        nameStep.transition(stepTransition)
                    case .handle:
                        handleStep.transition(stepTransition)
                    }
                }
                tipCard
            }
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .animation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.87), value: viewModel.step)
    }

    // ── hero + wordmark ──────────────────────────────────────

    private var hero: some View {
        ZStack {
            // webgl-glow stand-in: slow emerald breathing behind the illustration
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(
                    RadialGradient(
                        colors: [PulseTheme.emerald.opacity(glowUp ? 0.85 : 0.45), .clear],
                        center: .center,
                        startRadius: 8,
                        endRadius: 120,
                    ),
                )
            Image("OnboardingHero")
                .resizable()
                .scaledToFill()
                .frame(width: 196, height: 196)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .shadow(color: PulseTheme.emerald.opacity(0.10), radius: 12, y: 6)
        }
        .frame(width: 196, height: 196)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 3.6).repeatForever(autoreverses: true)) {
                glowUp = true
            }
        }
    }

    private var wordmark: some View {
        VStack(spacing: 4) {
            HStack(spacing: 6) {
                Text("Pulse")
                    .font(.system(size: 26, weight: .bold))
                    .tracking(-0.5)
                    .foregroundStyle(zinc900)
                Circle()
                    .fill(LinearGradient(colors: [Color(hex: 0x34D399), emerald600], startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 8, height: 8)
            }
            Text("Your conversations, instantly alive.")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(zinc500)
        }
    }

    // ── step 1: display name + avatar color ──────────────────

    private var nameStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                fieldLabel("Display name")
                PulseOnboardingField(
                    text: Binding(get: { viewModel.name }, set: { viewModel.name = String($0.prefix(OnboardingViewModel.nameMax)); viewModel.nameTaken = false; viewModel.notice = nil }),
                    placeholder: "What should people call you?",
                    isError: viewModel.nameTaken,
                    autoFocus: true,
                    onSubmit: { if viewModel.validName && !viewModel.pending && !viewModel.signingIn { viewModel.startHandleStep() } },
                )
                if viewModel.nameTaken {
                    noticeLine("Already on Pulse as “\(viewModel.trimmedName)”?", amberText)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                fieldLabel("Avatar color")
                HStack(spacing: 0) {
                    ForEach(SerializerPalette.names.indices, id: \.self) { index in
                        SwatchCircle(
                            name: SerializerPalette.names[index],
                            isSelected: viewModel.color == SerializerPalette.names[index],
                        ) {
                            viewModel.color = SerializerPalette.names[index]
                            PulseHaptics.tap()
                        }
                        if index != SerializerPalette.names.count - 1 {
                            Spacer(minLength: 0)
                        }
                    }
                }
            }

            VStack(spacing: 8) {
                primaryButton(
                    title: "Continue",
                    icon: "arrow.right",
                    enabled: viewModel.validName && !viewModel.pending && !viewModel.signingIn,
                    action: { viewModel.startHandleStep() },
                )
                if viewModel.nameTaken {
                    loginButton
                }
            }

            if let notice = viewModel.notice, viewModel.step == .name {
                noticeLine(notice, amberText)
            }
        }
    }

    // ── step 2: pick a @handle ───────────────────────────────

    private var trimmedHandle: String { viewModel.handle.trimmingCharacters(in: .whitespaces) }
    private var validHandle: Bool { OnboardingViewModel.isValidHandle(trimmedHandle) }
    /// availability only counts when it belongs to the handle currently on screen
    private var checkStale: Bool { viewModel.checkedHandle != trimmedHandle }
    private var isChecking: Bool { validHandle && (checkStale || viewModel.checking) }
    private var isAvailable: Bool { validHandle && !checkStale && viewModel.checkAvailable == true }
    private var isTaken: Bool { validHandle && !checkStale && viewModel.checkAvailable == false && !isChecking }
    private var blocked: Bool { viewModel.serverTakenMessage != nil }
    private var canSubmit: Bool { validHandle && !isChecking && !isTaken && !viewModel.pending && !blocked }

    private var handleStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 6) {
                Button(action: { viewModel.backToName() }) {
                    Image(systemName: "arrow.left")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(zinc500)
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(colorScheme == .dark ? Color(hex: 0x27272A) : Color(hex: 0xF4F4F5)))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Back to name step")

                VStack(alignment: .leading, spacing: 2) {
                    Text("Pick your handle")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(zinc900)
                    Text("Creating account for “\(viewModel.trimmedName)” — optional, but it makes you findable.")
                        .font(.system(size: 11))
                        .foregroundStyle(zinc500)
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    fieldLabel("@handle")
                    Spacer()
                    Text("OPTIONAL")
                        .font(.system(size: 10, weight: .medium))
                        .tracking(0.8)
                        .foregroundStyle(zinc400)
                }
                PulseOnboardingField(
                    text: Binding(get: { viewModel.handle }, set: { viewModel.setHandle($0) }),
                    placeholder: "e.g. alice_chen",
                    leadingAt: true,
                    isError: isTaken || blocked,
                    onSubmit: { if canSubmit { viewModel.submit(onSuccess: complete) } },
                )

                // live availability line (web aria-live region)
                Group {
                    if trimmedHandle.isEmpty {
                        noticeLine("Skip it if you prefer — you can add one later in Profile.", zinc400)
                    } else if !validHandle {
                        noticeLine("3–20 characters: lowercase letters, digits, underscore.", zinc500)
                    } else if isChecking {
                        HStack(spacing: 6) {
                            ProgressView()
                                .scaleEffect(0.6)
                                .frame(width: 14, height: 14)
                            Text("Checking @\(trimmedHandle)…")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(zinc500)
                        }
                    } else if isAvailable {
                        noticeLine("@\(trimmedHandle) is free!", emerald600)
                    } else if isTaken {
                        takenLine("@\(trimmedHandle) is taken", suggestion: viewModel.checkSuggestion)
                    } else if blocked {
                        takenLine(viewModel.serverTakenMessage ?? "", suggestion: viewModel.serverTakenSuggestion)
                    }
                }
                .frame(minHeight: 18, alignment: .leading)
            }

            VStack(spacing: 8) {
                primaryButton(
                    title: viewModel.pending ? "Creating your account…" : "Start chatting",
                    icon: viewModel.pending ? nil : "arrow.right",
                    enabled: canSubmit,
                    loading: viewModel.pending,
                    action: { viewModel.submit(onSuccess: complete) },
                )
                ghostButton(title: "Skip for now", enabled: !viewModel.pending) {
                    viewModel.skip(onSuccess: complete)
                }
            }
        }
    }

    // ── shared atoms ─────────────────────────────────────────

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(.secondary)
    }

    private func noticeLine(_ text: String, _ color: Color) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(color)
    }

    /// amber "taken" line with the optional Use-@suggestion pill (web parity).
    private func takenLine(_ text: String, suggestion: String?) -> some View {
        HStack(spacing: 6) {
            Text(text)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(amberText)
            if let suggestion, !suggestion.isEmpty {
                Button {
                    viewModel.useSuggestion(suggestion)
                } label: {
                    Text("Use @\(suggestion)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(amberText)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(Color(hex: 0xF59E0B).opacity(0.15)))
                }
                .buttonStyle(PulseButtonStyle())
            }
        }
    }

    private func primaryButton(title: String, icon: String?, enabled: Bool, loading: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if loading {
                    ProgressView()
                        .controlSize(.mini)
                        .tint(.white)
                }
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .semibold))
                }
            }
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(emerald600.opacity(enabled ? 1.0 : 0.5)),
            )
            .foregroundStyle(.white)
            .shadow(color: emerald600.opacity(0.2), radius: 6, y: 3)
        }
        .buttonStyle(PulseButtonStyle())
        .disabled(!enabled)
    }

    /// "That's me — log in instead" — emerald-tinted outline button (web parity).
    private var loginButton: some View {
        Button {
            viewModel.loginInstead(onSuccess: complete)
        } label: {
            HStack(spacing: 6) {
                if viewModel.signingIn {
                    ProgressView().controlSize(.mini).tint(colorScheme == .dark ? Color(hex: 0x34D399) : Color(hex: 0x047857))
                } else {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .font(.system(size: 13, weight: .semibold))
                }
                Text(viewModel.signingIn ? "Signing you in…" : "That's me — log in instead")
                    .font(.system(size: 14, weight: .semibold))
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(PulseTheme.emerald.opacity(0.10)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(PulseTheme.emerald.opacity(0.5), lineWidth: 1),
            )
            .foregroundStyle(colorScheme == .dark ? Color(hex: 0x34D399) : Color(hex: 0x047857))
        }
        .buttonStyle(PulseButtonStyle())
        .disabled(!viewModel.validName || viewModel.signingIn)
    }

    private func ghostButton(title: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(zinc500)
                .frame(maxWidth: .infinity, minHeight: 40)
        }
        .buttonStyle(PulseButtonStyle())
        .disabled(!enabled)
    }

    /// dashed zinc tip card — the web footer, copy adapted for the native app.
    private var tipCard: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "sparkles")
                .font(.system(size: 12))
                .foregroundStyle(PulseTheme.emerald)
            Text("Tip: your @handle is optional — add or change it anytime from your Profile.")
                .font(.system(size: 12))
                .foregroundStyle(zinc500)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(colorScheme == .dark ? Color(hex: 0x27272A).opacity(0.6) : Color(hex: 0xFAFAFA)),
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(zinc200, style: StrokeStyle(lineWidth: 1, dash: [5, 4])),
        )
    }

    // ── completion ───────────────────────────────────────────

    private func complete(_ user: WireUser) {
        let viewer = PulseViewer(from: user)
        prefs.setViewer(viewer)
        session.start(as: viewer)
        session.particles.fire(kind: .confetti, count: 110)
        PulseHaptics.success()
        onPicked()
    }
}

// MARK: - atoms

/// One avatar-color circle — gradient fill, emerald ring + check when picked.
private struct SwatchCircle: View {
    let name: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(PulseTheme.gradient(named: name))
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                }
            }
            .frame(width: 36, height: 36)
            .overlay {
                if isSelected {
                    // web ring-2 ring-emerald-600 ring-offset-2
                    Circle()
                        .strokeBorder(Color(hex: 0x059669), lineWidth: 2)
                        .frame(width: 42, height: 42)
                }
            }
            .scaleEffect(isSelected ? 1.05 : 1.0)
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("\(name) avatar")
    }
}

/// The web's shadcn Input: 44dp rounded field on zinc-50/zinc-800,
/// emerald focus ring, amber error border — SwiftUI implementation.
private struct PulseOnboardingField: View {
    @Binding var text: String
    let placeholder: String
    var leadingAt = false
    var isError = false
    var autoFocus = false
    var onSubmit: (() -> Void)?

    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var focused: Bool

    private var borderColor: Color {
        if isError { return Color(hex: 0xFBBF24) }
        if focused { return Color(hex: 0x059669).opacity(0.6) }
        return colorScheme == .dark ? Color(hex: 0x3F3F46) : Color(hex: 0xE4E4E7)
    }

    var body: some View {
        HStack(spacing: leadingAt ? 4 : 0) {
            if leadingAt {
                Text("@")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xA1A1AA))
            }
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: 15))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused)
                .onSubmit { onSubmit?() }
        }
        .padding(.horizontal, leadingAt ? 14 : 12)
        .frame(height: 44)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(colorScheme == .dark ? Color(hex: 0x27272A) : Color(hex: 0xFAFAFA)),
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(borderColor, lineWidth: 1),
        )
        .onAppear {
            if autoFocus {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    focused = true
                }
            }
        }
    }
}

// MARK: - tiny hex helper (file-local)

private extension Color {
    init(hex: UInt) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
        )
    }
}
