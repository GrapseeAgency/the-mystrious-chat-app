import SwiftUI

/// Wave 6 - the mentions feed (F-SM-03, mentions-page.tsx parity): the REAL
/// GET /api/mentions entries (paginated all-history, sender≠viewer), author avatar
/// circular, snippet with the first "@Me" token in an emerald glass chip,
/// "Direct message" footer for DMs, honest empty copy. NO read-state/ack -
/// opening the page never clears the pill (count = feed length, web parity).
struct MentionsView: View {
    @ObservedObject var session: PulseSession
    /// Open the conversation a mention belongs to (chats-list navigation).
    var onOpenConversation: (WireConversationSummary, String) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var entries: [WireMentionEntry] = []
    @State private var loading = true
    @State private var errorText: String?
    @State private var reloadTick = 0
    @State private var cursor: String?
    @State private var nextCursor: String?
    @State private var previous: [String?] = []
    @State private var loadTicket: UUID?
    @State private var ownerEpoch: UUID?
    @State private var openingTicket: UUID?
    @State private var openingTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if nextCursor != nil || !previous.isEmpty {
                            HStack {
                                Button("Newer results") {
                                    guard !loading, !previous.isEmpty else { return }
                                    loading = true; entries = []; cursor = previous.removeLast(); reloadTick += 1
                                }.disabled(loading || previous.isEmpty)
                                Spacer()
                                Button("Search older history") {
                                    guard !loading, let next = nextCursor else { return }
                                    loading = true; entries = []; previous.append(cursor); cursor = next; reloadTick += 1
                                }.disabled(loading || nextCursor == nil)
                            }.font(.footnote).tint(PulseTheme.accent).padding(.vertical, 8)
                        }
                        if let errorText, !entries.isEmpty {
                            Text(errorText).font(.footnote).foregroundStyle(PulseTheme.textSecondary)
                            Button("Try again") { reloadTick += 1 }
                        }
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
                    // Header count badge (web h1 badge - 99+ cap), no clear-on-open.
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
        .task(id: "\(session.sessionGeneration):\(reloadTick)") {
            await load()
        }
        // Independent of transport: a stalled refresh must not extend a preview's lifetime.
        .task(id: session.sessionGeneration) {
            while !Task.isCancelled {
                entries.removeAll { !$0.isVisible() }
                do { try await Task.sleep(nanoseconds: 1_000_000_000) } catch { return }
            }
        }
        .onDisappear { openingTicket = nil; openingTask?.cancel(); openingTask = nil }
        .onChange(of: session.sessionGeneration) { _, _ in
            if ownerEpoch != session.sessionGeneration { entries = []; errorText = nil; cursor = nil; nextCursor = nil; previous = [] }
            openingTicket = nil; openingTask?.cancel(); openingTask = nil
        }
    }

    private var emptyCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "at")
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(PulseTheme.textTertiary)
                .frame(width: 64, height: 64)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.ultraThinMaterial))
            Text(nextCursor != nil || !previous.isEmpty ? "No mentions on this page" : "No mentions yet")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Text(nextCursor == nil ? "No mentions in this window." : "No matches in this window. Continue searching older history.")
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
            Button("Try again") { reloadTick += 1 }
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(PulseTheme.accent)
        }
        .padding(.top, 60)
        .frame(maxWidth: .infinity)
    }

    @MainActor private func load() async {
        let epoch = session.sessionGeneration, client = session.api, ticket = UUID()
        if ownerEpoch != epoch { entries = []; ownerEpoch = epoch }
        loadTicket = ticket
        loading = true
        defer { if loadTicket == ticket { loading = false; loadTicket = nil } }
        do {
            let page = try await client.mentionPage(cursor: cursor)
            guard !Task.isCancelled, session.sessionGeneration == epoch, loadTicket == ticket else { return }
            entries = (page.items ?? []).filter { $0.isVisible() }; nextCursor = page.nextCursor; errorText = nil
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch, loadTicket == ticket else { return }
            if let failure = error as? PulseAPIClient.Failure {
                if [401, 403, 404].contains(failure.status ?? 0) { entries = [] }
                if failure.status == 400 { entries = []; cursor = nil; nextCursor = nil; previous = [] }
            }
            errorText = "Could not refresh mentions. Please try again."
        }
    }

    @MainActor private func open(_ entry: WireMentionEntry) {
        guard ownerEpoch == session.sessionGeneration, entry.isVisible(), let conversationId = entry.conversationId, let messageId = entry.messageId else { return }
        let epoch = session.sessionGeneration, client = session.api, ticket = UUID()
        openingTask?.cancel(); openingTicket = ticket
        session.noteRoomNavigationIntent()
        let navigation = session.roomNavigationGeneration
        PulseHaptics.tap()
        openingTask = Task { @MainActor in
            defer { if openingTicket == ticket { openingTicket = nil; openingTask = nil } }
            do {
                let room = try await client.conversationDetail(id: conversationId, userId: client.userId)
                guard !Task.isCancelled, openingTicket == ticket, session.sessionGeneration == epoch, session.roomNavigationGeneration == navigation, entry.isVisible() else { return }
                openingTicket = nil; openingTask = nil
                dismiss()
                onOpenConversation(room, messageId)
            } catch {
                guard !Task.isCancelled, openingTicket == ticket, session.sessionGeneration == epoch else { return }
                session.toasts.show("That chat isn't available right now")
            }
        }
    }

}

/// One mention row - the exact web anatomy: author circle + name, snippet
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
/// (PulseMentions.firstMatch - the API regex mirror, unit-tested).
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
