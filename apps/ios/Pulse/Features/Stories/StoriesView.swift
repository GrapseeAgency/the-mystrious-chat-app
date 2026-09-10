import SwiftUI

/// More → Stories — the real surface behind the dock menu (replaces the
/// honest toast). It fetches the live stories endpoint; every ring here is a
/// real story group from the gateway. The composer/viewer detail surface
/// lands with the stories data wave — tapping a ring says so honestly.
struct StoriesView: View {
    @ObservedObject var session: PulseSession

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Phase { case loading, loaded([WireStoryGroup]), failed(String) }

    @State private var phase: Phase = .loading

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
                    Spacer()

                case .loaded(let groups):
                    if groups.isEmpty {
                        Spacer()
                        ContentUnavailableCompat(
                            title: "No stories yet",
                            systemImage: "sparkles",
                            note: "Rings light up the moment someone posts — the composer and viewer arrive with the stories data wave.",
                        )
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
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
        .task { await load() }
    }

    // ── rows ─────────────────────────────────────────────────

    private func storyRow(_ group: WireStoryGroup) -> some View {
        let name = group.user?.name ?? "Someone"
        let count = group.stories?.count ?? 0
        return Button {
            PulseHaptics.tap()
            session.toasts.show("The story viewer lands with the stories data wave.")
        } label: {
            HStack(spacing: 14) {
                StoryRingCell(
                    name: name,
                    color: group.user?.color,
                    ring: ring(for: group),
                    plus: false,
                    label: name,
                    onPress: {},
                )
                .disabled(true)
                .allowsHitTesting(false)

                VStack(alignment: .leading, spacing: 3) {
                    Text(name)
                        .font(.system(size: 15.5, weight: .semibold))
                        .foregroundStyle(PulseTheme.titleOnWash)
                        .lineLimit(1)
                    Text(count == 1 ? "1 story" : "\(count) stories")
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if group.mine == true {
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
        .accessibilityLabel("Story from \(name), \(count) stories")
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
        // stories() is failure-soft (returns nil when the gateway can't
        // answer) — nil with no live relay is the offline-first story.
        if let page = await session.api.stories() {
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                phase = .loaded(page.groups ?? [])
            }
        } else {
            phase = .failed(ChatsViewModel.describe(
                PulseAPIClient.Failure(kind: .network, message: nil)
            ))
        }
    }
}
