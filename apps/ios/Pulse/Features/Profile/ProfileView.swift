import SwiftUI

/// Profile — identity management (native onboarding parity), appearance
/// (dark override + the ambient FX picker with LIVE shader preview strips),
/// and honest about-notes. All settings persist via PulsePrefs.
struct ProfileView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs

    @State private var identitySheet = false

    var body: some View {
        NavigationStack {
            List {
                identitySection
                appearanceSection
                motionSection
                aboutSection
            }
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.large)
        }
        .sheet(isPresented: $identitySheet) {
            IdentityPickerSheet(mode: .switcher, session: session, prefs: prefs) {}
        }
    }

    // ── sections ─────────────────────────────────────────────
    private var identitySection: some View {
        Section("Identity") {
            HStack(spacing: 12) {
                PulseAvatar(
                    name: prefs.viewer?.name ?? "You",
                    color: PulseTheme.color(named: prefs.viewer?.color),
                    photoURL: PulseTheme.photoURL(prefs.viewer?.avatar),
                    size: 52,
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(prefs.viewer?.name ?? "No identity")
                        .font(.body.weight(.semibold))
                    Text(prefs.viewer?.username.map { "@\($0)" } ?? "Signed in on this device")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    identitySheet = true
                } label: {
                    Label("Switch", systemImage: "arrow.left.arrow.right")
                        .font(.footnote.weight(.semibold))
                }
                .tint(PulseTheme.emerald)
                .buttonStyle(.bordered)
            }
            .padding(.vertical, 2)
        }
    }

    private var appearanceSection: some View {
        Section("Appearance") {
            Picker("Mode", selection: Binding(
                get: { prefs.appearance },
                set: { prefs.setAppearance($0) },
            )) {
                Text("Auto").tag("system")
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            }
            .pickerStyle(.segmented)

            VStack(alignment: .leading, spacing: 10) {
                Text("Ambient field")
                    .font(.subheadline.weight(.semibold))
                Text("The web's WebGL modes, rebuilt with Metal + Canvas — same palette, same physics.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(AmbientMode.allCases) { mode in
                            ambientCard(mode)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func ambientCard(_ mode: AmbientMode) -> some View {
        let selected = prefs.ambientMode == mode
        return Button {
            prefs.setAmbientMode(mode)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                AmbientFieldView(mode: mode, dark: true, preview: true)
                    .frame(width: 132, height: 74)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(selected ? PulseTheme.emerald : Color.primary.opacity(0.08), lineWidth: selected ? 2.5 : 1),
                    )
                Text(mode.label)
                    .font(.caption.weight(selected ? .bold : .medium))
                    .foregroundStyle(selected ? PulseTheme.emerald : .secondary)
            }
        }
        .buttonStyle(PulseButtonStyle())
    }

    private var motionSection: some View {
        Section {
            // Reduce Motion is honored system-wide by every FX surface.
            Label("Respects system Reduce Motion", systemImage: "figure.mind.and.body")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("Motion")
        } footer: {
            Text("Particles, springs and the ambient loop all render a single static frame when Reduce Motion is on.")
        }
    }

    private var aboutSection: some View {
        Section("About") {
            LabeledContent("Version", value: "1.0.0-native")
            LabeledContent("Stack", value: "SwiftUI · GRDB · Socket.IO")
            VStack(alignment: .leading, spacing: 4) {
                Text("Rebuilt natively against the same live gateway as the web app — chats, rooms, reactions, typing and presence are real; stories, calls and games graduate in later waves.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
