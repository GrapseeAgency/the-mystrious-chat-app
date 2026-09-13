import SwiftUI

/// Wave 6 — the mentions feed (F-SM-03, mentions-page.tsx parity): the REAL
/// GET /api/mentions entries (14-day window, sender≠viewer), author avatar
/// circular, snippet with the first "@Me" token in an emerald glass chip,
/// "Direct message" footer for DMs, honest empty copy. NO read-state/ack —
/// opening the page never clears the pill (count = feed length, web parity).
struct MentionsView: View {
    @ObservedObject var session: PulseSession
    /// Open the conversation a mention belongs to (chats-list navigation).
    var onOpenConversation: (WireConversationSummary) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var entries: [WireMentionEntry] = []
    @State private var loading = true
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if loading && entries.isEmpty {
                            ForEach(0..<3, id: \.self) { _ in
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .fill(PulseTheme.glassFill)
                                    .frame(height: 72)
                            }
                        } else if let errorText, entries.isEmpty {
                            errorCard(errorText)
                        } else if entries.isEmpty {
                            emptyCard
                        } else {
                            ForEach(entries) { entry in
                                MentionRow(
                                    entry: entry,
                                    meName: session.viewer?.name ?? "",
                                    onPress: { open(entry) },
                                )
                            }
                        }
                        Color.clear.frame(height: 20)
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                }
            }
            .navigationTitle("Mentions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    // Header count badge (web h1 badge — 99+ cap), no clear-on-open.
                    if !entries.isEmpty {
                        Text(PulseBadgeCap.cap(entries.count))
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(PulseTheme.accent)
                            .padding(.horizontal, 6)
                            .frame(minWidth: 18, minHeight: 18)
                            .background(Capsule().fill(PulseTheme.emerald500.opacity(0.15)))
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task { await load() }
    }

    private var emptyCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "at")
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(PulseTheme.textTertiary)
                .frame(width: 64, height: 64)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.ultraThinMaterial))
            Text("No mentions yet")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Text("No mentions yet — when someone @mentions you, it shows up here.")
                .font(.system(size: 12.5))
                .foregroundStyle(PulseTheme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 260)
        }
        .padding(.top, 60)
        .frame(maxWidth: .infinity)
    }

    private func errorCard(_ message: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "at")
                .font(.system(size: 24))
                .foregroundStyle(PulseTheme.textTertiary)
            Text("Could not load mentions")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Text(message)
                .font(.system(size: 12.5))
                .foregroundStyle(PulseTheme.textSecondary)
                .multilineTextAlignment(.center)
            Button("Try again") { Task { await load() } }
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(PulseTheme.accent)
        }
        .padding(.top, 60)
        .frame(maxWidth: .infinity)
    }

    private func load() async {
        loading = entries.isEmpty
        defer { loading = false }
        let fresh = await session.api.mentions(limit: 50)
        entries = fresh
        errorText = nil
    }

    /// Row tap → the conversation. Live summaries resolve instantly; a cold
    /// entry (push notification walk-in) fetches the summary first.
    private func open(_ entry: WireMentionEntry) {
        guard let conversationId = entry.conversationId else { return }
        PulseHaptics.tap()
        dismiss()
        Task {
            if let conv = await resolve(conversationId) {
                onOpenConversation(conv)
            } else {
                session.toasts.show("That chat isn't available right now")
            }
        }
    }

    private func resolve(_ conversationId: String) async -> WireConversationSummary? {
        if let page = try? await session.api.conversationDetail(id: conversationId, userId: session.api.userId) {
            return page
        }
        return nil
    }
}

/// One mention row — the exact web anatomy: author circle + name, snippet
/// with the "@Me" chip, AtSign + where footer, stamp + chevron.
private struct MentionRow: View {
    let entry: WireMentionEntry
    let meName: String
    let onPress: () -> Void

    private var authorName: String {
        let raw = entry.author?.name ?? ""
        return raw.trimmingCharacters(in: .whitespaces).isEmpty ? "Unknown" : raw
    }

    private var where_: String {
        if entry.isGroup == true {
            let name = entry.conversationName?.trimmingCharacters(in: .whitespaces) ?? ""
            return name.isEmpty ? "Group" : name
        }
        return "Direct message"
    }

    var body: some View {
        Button(action: onPress) {
            HStack(alignment: .top, spacing: 12) {
                RowAvatar(
                    name: authorName,
                    colorName: entry.author?.color,
                    photoPath: entry.author?.avatar,
                    size: 44,
                )
                VStack(alignment: .leading, spacing: 3) {
                    Text(authorName)
                        .font(.system(size: 14.5, weight: .semibold))
                        .foregroundStyle(PulseTheme.titleOnPanel)
                        .lineLimit(1)
                    MentionSnippet(snippet: entry.snippet ?? "", meName: meName)
                        .lineLimit(2)
                    HStack(spacing: 4) {
                        Image(systemName: "at")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(PulseTheme.accent.opacity(0.7))
                        Text(where_)
                            .font(.system(size: 11))
                            .foregroundStyle(PulseTheme.textTertiary)
                            .lineLimit(1)
                    }
                    .padding(.top, 1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .trailing, spacing: 6) {
                    Text(PulseFormat.listStamp(entry.createdAt))
                        .font(.system(size: 11))
                        .foregroundStyle(PulseTheme.textTertiary)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PulseTheme.textTertiary)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(.ultraThinMaterial),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1),
            )
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("Mention from \(authorName) in \(where_), \(PulseFormat.listStamp(entry.createdAt))")
    }
}

/// Snippet with the first "@Me" token wrapped in an emerald glass chip
/// (PulseMentions.firstMatch — the API regex mirror, unit-tested).
struct MentionSnippet: View {
    let snippet: String
    let meName: String

    var body: some View {
        Group {
            if let range = PulseMentions.firstMatch(of: meName, in: snippet) {
                Text(snippet[snippet.startIndex..<range.lowerBound])
                Text(snippet[range])
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(PulseTheme.accent)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 1)
                    .background(
                        RoundedRectangle(cornerRadius: 5)
                            .fill(PulseTheme.emerald500.opacity(0.15)),
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 5)
                            .strokeBorder(PulseTheme.emerald500.opacity(0.30), lineWidth: 1),
                    )
                Text(snippet[range.upperBound...])
            } else {
                Text(snippet)
            }
        }
        .font(.system(size: 12.5))
        .foregroundStyle(PulseTheme.textSecondary)
        .multilineTextAlignment(.leading)
    }
}
