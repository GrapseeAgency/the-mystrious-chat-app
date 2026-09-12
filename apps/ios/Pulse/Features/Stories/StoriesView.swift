import SwiftUI

/// More → Stories — the real surface behind the dock menu. It shows the live
/// 24h status feed (shared [StoriesSessionModel]) with "My status" first;
/// rows open the full-screen viewer, the persistent "Add status" affordance
/// opens the composer (web-defect D1 fixed: reachable even while a story is
/// live). Empty / failed / loading states are honest and distinct.
struct StoriesView: View {
    @ObservedObject var session: PulseSession

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Phase: Equatable {
        case loading
        case loaded
        case failed(String)

        static func == (lhs: Phase, rhs: Phase) -> Bool {
            switch (lhs, rhs) {
            case (.loading, .loading), (.loaded, .loaded): return true
            case (.failed(let a), .failed(let b)): return a == b
            default: return false
            }
        }
    }

    @State private var phase: Phase = .loading
    @State private var viewerStartUser: String?
    @State private var viewerPresent = false
    @State private var composerPresent = false

    private var model: StoriesSessionModel? { session.stories }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()

                switch phase {
                case .loading:
                    VStack(spacing: 14) {
                        ForEach(0..<3, id: \.self) { _ in
                            skeletonRow
                        }
                        Spacer()
                    }
                    .padding(.top, 12)

                case .failed(let message):
                    Spacer()
                    ContentUnavailableCompat(title: "Can't load stories", systemImage: "wifi.exclamationmark", note: message)
                    Button("Retry") {
                        phase = .loading
                        Task { await load() }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(PulseTheme.emeraldDeep)
                    Spacer()

                case .loaded:
                    if (model?.groups ?? []).isEmpty {
                        Spacer()
                        ContentUnavailableCompat(
                            title: "No stories yet",
                            systemImage: "sparkles",
                            note: "Rings light up the moment someone posts. Share the first one.",
                        )
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                addStatusRow
                                ForEach(Array((model?.groups ?? []).enumerated()), id: \.offset) { _, group in
                                    storyRow(group)
                                }
                            }
                            .padding(.vertical, 8)
                        }
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("Stories").font(.headline)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task {
            if model?.flags.loadedOnce != true { phase = .loading }
            await load()
        }
        .fullScreenCover(isPresented: $viewerPresent) {
            if let model {
                StoryViewerView(session: session, stories: model, startUserId: viewerStartUser) {
                    viewerPresent = false
                    Task { await load() } // rings re-sync on close (web parity)
                }
            }
        }
        .fullScreenCover(isPresented: $composerPresent) {
            if let model {
                StoryComposerView(session: session, stories: model, onPublished: {
                    composerPresent = false
                    Task { await load() }
                }, onClose: { composerPresent = false })
            }
        }
    }

    // ── D1: the composer is reachable even while a story is live ──

    private var addStatusRow: some View {
        Button {
            PulseHaptics.tap()
            composerPresent = true
        } label: {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(PulseTheme.emerald.opacity(0.14))
                        .frame(width: 56, height: 56)
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(PulseTheme.emeraldDeep)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text("Add status")
                        .font(.system(size: 15.5, weight: .semibold))
                        .foregroundStyle(PulseTheme.titleOnWash)
                    Text("Text or a photo — visible for 24 hours")
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Add status")
    }

    // ── rows ─────────────────────────────────────────────────

    private func storyRow(_ group: WireStoryGroup) -> some View {
        let name = group.user?.name ?? "Someone"
        let count = group.stories?.count ?? 0
        let isMine = group.mine == true
        let hasLive = !(group.stories ?? []).isEmpty
        return Button {
            PulseHaptics.tap()
            if isMine && !hasLive {
                composerPresent = true // web parity: empty own cell = composer
            } else {
                viewerStartUser = group.user?.id
                viewerPresent = true
            }
        } label: {
            HStack(spacing: 14) {
                StoryRingCell(
                    name: name,
                    color: group.user?.color,
                    ring: ring(for: group),
                    plus: !isMine ? false : !hasLive, // D1: plus badge even while live
                    label: name,
                    onPress: {},
                )
                .disabled(true)
                .allowsHitTesting(false)

                VStack(alignment: .leading, spacing: 3) {
                    Text(isMine ? "My status" : name)
                        .font(.system(size: 15.5, weight: .semibold))
                        .foregroundStyle(PulseTheme.titleOnWash)
                        .lineLimit(1)
                    Text(count == 1 ? "1 story" : "\(count) stories")
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if isMine {
                    chip("You", icon: "person.fill")
                } else if group.allSeen == true {
                    chip("Seen", icon: "checkmark")
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Story from \(isMine ? "you" : name), \(count) stories")
    }

    private func chip(_ text: String, icon: String) -> some View {
        Label(text, systemImage: icon)
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(PulseTheme.emeraldDeep)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(Capsule().fill(PulseTheme.emerald.opacity(0.14)))
    }

    private func ring(for group: WireStoryGroup) -> StoryRingCell.Ring {
        if group.allSeen == true { return .seen }
        return .unseen
    }

    private var skeletonRow: some View {
        HStack(spacing: 14) {
            Circle()
                .fill(PulseTheme.zinc(systemScheme == .dark ? 800 : 200))
                .frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 8) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(PulseTheme.zinc(systemScheme == .dark ? 800 : 200))
                    .frame(width: 120, height: 12)
                RoundedRectangle(cornerRadius: 4)
                    .fill(PulseTheme.zinc(systemScheme == .dark ? 800 : 200))
                    .frame(width: 64, height: 10)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .opacity(0.7)
    }

    // ── data ─────────────────────────────────────────────────

    @Environment(\.colorScheme) private var systemScheme

    private func load() async {
        guard let model else {
            phase = .failed("Pick who you are on this device first.")
            return
        }
        await model.refresh(quiet: false)
        await model.loadIfIdle()
        if model.flags.lastError != nil && model.groups.isEmpty {
            phase = .failed(model.flags.lastError ?? "Can't reach the gateway")
        } else {
            phase = .loaded
        }
    }
}

extension StoriesSessionModel {
    /// One extra fetch when the model has never been refreshed this session
    /// (the cold-start cache rehydrate marks loadedOnce, so the dock sheet
    /// always gets at least one live pull on open).
    func loadIfIdle() async {
        if !flags.loading { await refresh(quiet: true) }
    }
}
