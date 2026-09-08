import SwiftUI

/// "Who are you?" — native onboarding + identity switcher, mirroring the web
/// identity picker: pick a seeded identity or create one (name + palette
/// color, 409 username-taken surfaces the server's suggestion).
struct IdentityPickerSheet: View {
    enum Mode { case onboarding, switcher }

    let mode: Mode
    var session: PulseSession
    var prefs: PulsePrefs
    var onPicked: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = IdentityViewModel()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(viewModel.users) { user in
                        Button {
                            pick(user)
                        } label: {
                            IdentityRow(user: user)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("Pick an identity")
                } footer: {
                    Text("Pulse has no passwords in this build — identities are name-keyed, exactly like the web app.")
                }

                Section("Create new identity") {
                    TextField("Display name", text: $viewModel.newName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(SerializerPalette.names, id: \.self) { name in
                                let tint = PulseTheme.color(named: name)
                                Button {
                                    viewModel.newColor = name
                                } label: {
                                    Circle()
                                        .fill(tint)
                                        .frame(width: 30, height: 30)
                                        .overlay(
                                            Circle().strokeBorder(
                                                viewModel.newColor == name ? Color.primary : Color.clear,
                                                lineWidth: 2,
                                            )
                                            .padding(2),
                                        )
                                }
                                .buttonStyle(PulseButtonStyle())
                            }
                        }
                        .padding(.vertical, 2)
                    }

                    if let error = viewModel.errorText {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(.red)
                            if let suggestion = viewModel.suggestionText {
                                Button {
                                    viewModel.newName = suggestion
                                    viewModel.suggestionText = nil
                                } label: {
                                    Label("Use @\(suggestion)", systemImage: "wand.and.stars")
                                        .font(.footnote.weight(.medium))
                                }
                            }
                        }
                    }

                    Button {
                        create()
                    } label: {
                        HStack {
                            if viewModel.creating {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: "person.crop.circle.badge.plus")
                            }
                            Text("Create identity")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PulseButtonStyle())
                    .disabled(viewModel.creating || viewModel.newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle(mode == .onboarding ? "Who are you?" : "Switch identity")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                if mode == .switcher {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { dismiss() }
                    }
                }
            }
            .overlay {
                if viewModel.loading {
                    ProgressView("Loading identities…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .refreshable { await viewModel.load(api: session.api) }
            .task { await viewModel.load(api: session.api) }
        }
        .interactiveDismissDisabled(mode == .onboarding)
    }

    private func pick(_ user: WireUser) {
        let viewer = PulseViewer(from: user)
        prefs.setViewer(viewer)
        session.start(as: viewer)
        PulseHaptics.success()
        onPicked()
        if mode == .switcher { dismiss() }
    }

    private func create() {
        viewModel.create(api: session.api) { user in
            let viewer = PulseViewer(from: user)
            prefs.setViewer(viewer)
            session.start(as: viewer)
            session.particles.fire(kind: .confetti, count: 110)
            PulseHaptics.success()
            onPicked()
            if mode == .switcher { dismiss() }
        }
    }
}

private struct IdentityRow: View {
    let user: WireUser

    var body: some View {
        HStack(spacing: 12) {
            PulseAvatar(
                name: user.name,
                color: PulseTheme.color(named: user.color),
                photoURL: PulseTheme.photoURL(user.avatar),
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
                }
                Text(user.username.map { "@\($0)" } ?? user.about ?? "New here")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 2)
    }
}

/// Web AVATAR_COLORS registry — the server normalizes colors to these names.
enum SerializerPalette {
    static let names = ["emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan"]
}

/// Identity picker state (UDF view-model).
@MainActor
final class IdentityViewModel: ObservableObject {
    @Published private(set) var users: [WireUser] = []
    @Published private(set) var loading = false
    @Published private(set) var creating = false
    @Published var newName = ""
    @Published var newColor = "emerald"
    @Published var errorText: String?
    @Published var suggestionText: String?

    func load(api: PulseAPIClient) async {
        loading = users.isEmpty
        defer { loading = false }
        do {
            users = try await api.users()
        } catch {
            if users.isEmpty { errorText = Self.message(of: error) }
        }
    }

    func create(api: PulseAPIClient, onSuccess: @escaping (WireUser) -> Void) {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        creating = true
        errorText = nil
        suggestionText = nil
        Task { [weak self] in
            guard let self else { return }
            defer { creating = false }
            do {
                let user = try await api.createUser(name: name, color: newColor)
                newName = ""
                onSuccess(user)
            } catch {
                if let failure = error as? PulseAPIClient.Failure, failure.code == "username_taken" {
                    errorText = failure.message
                    suggestionText = failure.suggestion
                } else {
                    errorText = Self.message(of: error)
                }
            }
        }
    }

    static func message(of error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message {
            return message
        }
        return "Something went wrong. Check the gateway and try again."
    }
}
