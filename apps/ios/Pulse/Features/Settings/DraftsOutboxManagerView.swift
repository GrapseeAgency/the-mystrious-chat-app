import SwiftUI

/// Wave 8 — Drafts & outbox manager (web settings Chat section parity):
/// the REAL per-conversation GRDB draft rows and the offline outbox queue
/// with per-row delete + clear-all. Fully offline — both stores are local
/// GRDB tables (`draft`, `outbox`); nothing here touches the network.
struct DraftsOutboxManagerView: View {
    @ObservedObject var session: PulseSession

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var systemScheme

    @State private var drafts: [DraftRow] = []
    @State private var queued: [OutboxRow] = []
    @State private var conversationNames: [String: String] = [:]
    /// Two-tap confirm for the destructive clear-all actions (house pattern).
    @State private var clearDraftsArmed = false
    @State private var discardQueueArmed = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 14) {
                        draftsCard
                        outboxCard
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 28)
                }
            }
            .navigationTitle("Drafts & outbox")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("Drafts & outbox").font(.headline)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear { reload() }
    }

    // ── drafts ───────────────────────────────────────────────

    private var draftsCard: some View {
        settingsCard(title: "Saved drafts", icon: "doc.text", count: drafts.count) {
            if drafts.isEmpty {
                emptyNote("No drafts — composer text saves per conversation on this device.")
            } else {
                ForEach(drafts, id: \.conversationId) { row in
                    draftRow(row)
                }
                clearAllButton(
                    label: clearDraftsArmed ? "Tap again to clear \(drafts.count) drafts" : "Clear all drafts",
                    armed: clearDraftsArmed,
                    disabled: drafts.isEmpty,
                ) {
                    clearAllDrafts()
                }
            }
        }
    }

    private func draftRow(_ row: DraftRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "doc.plaintext")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.secondary)
                Text(name(for: row.conversationId))
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                    .lineLimit(1)
                Spacer()
                Button {
                    deleteDraft(row)
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.red)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Delete draft for \(name(for: row.conversationId))")
            }
            Text(row.text)
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
                .lineLimit(2)
            if let stamp = stamp(row.updatedAt) {
                Text(stamp)
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(10)
        .background(rowPanel)
        .accessibilityElement(children: .combine)
    }

    // ── outbox ───────────────────────────────────────────────

    private var outboxCard: some View {
        settingsCard(title: "Offline queue", icon: "cloud.slash", count: queued.count) {
            if queued.isEmpty {
                emptyNote("Empty — every composed message has been delivered.")
            } else {
                ForEach(queued, id: \.clientId) { row in
                    outboxRow(row)
                }
                Text("These wait to send when you're back online. Discarding drops them without sending.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                clearAllButton(
                    label: discardQueueArmed ? "Tap again to discard \(queued.count) messages" : "Discard all",
                    armed: discardQueueArmed,
                    disabled: queued.isEmpty,
                ) {
                    discardAll()
                }
            }
        }
    }

    private func outboxRow(_ row: OutboxRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(PulseTheme.amber)
                Text(name(for: row.conversationId))
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(PulseTheme.titleOnPanel)
                    .lineLimit(1)
                if row.attempts > 0 {
                    Text("\(row.attempts)×")
                        .font(.system(size: 10.5, weight: .bold))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    deleteQueued(row)
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.red)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Discard queued message for \(name(for: row.conversationId))")
            }
            Text(row.content)
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(10)
        .background(rowPanel)
        .accessibilityElement(children: .combine)
    }

    // ── primitives ───────────────────────────────────────────

    private func settingsCard<Content: View>(
        title: String,
        icon: String,
        count: Int,
        @ViewBuilder content: () -> Content,
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Label(title, systemImage: icon)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(PulseTheme.accent)
                    .textCase(.uppercase)
                Spacer()
                Text("\(count)")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundStyle(count > 0 ? PulseTheme.accent : .secondary)
            }
            VStack(alignment: .leading, spacing: 8) {
                content()
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(cardPanel)
        .accessibilityElement(children: .contain)
    }

    private func emptyNote(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12.5))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 8)
    }

    private func clearAllButton(label: String, armed: Bool, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(armed ? Color.white : PulseTheme.accent)
                .frame(maxWidth: .infinity, minHeight: 42)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(armed ? Color.red : PulseTheme.accent.opacity(0.10)),
                )
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .accessibilityLabel(label)
    }

    private var rowPanel: some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(systemScheme == .dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.7), lineWidth: 1),
            )
    }

    private var cardPanel: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(.ultraThinMaterial)
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(systemScheme == .dark ? Color.white.opacity(0.10) : PulseTheme.zinc(200).opacity(0.7), lineWidth: 1),
            )
            .shadow(color: .black.opacity(0.08), radius: 12, y: 5)
    }

    private func name(for conversationId: String) -> String {
        conversationNames[conversationId] ?? "Conversation"
    }

    private func stamp(_ iso: String) -> String? {
        guard let date = PulseFormat.date(iso) else { return nil }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    // ── actions (real GRDB rows) ─────────────────────────────

    private func reload() {
        guard let store = session.store else { return }
        drafts = (try? store.allDraftRows()) ?? []
        queued = (try? store.outboxAll()) ?? []
        var names: [String: String] = [:]
        for conversation in (try? store.cachedConversations()) ?? [] {
            names[conversation.id] = conversation.title
        }
        conversationNames = names
        clearDraftsArmed = false
        discardQueueArmed = false
    }

    private func deleteDraft(_ row: DraftRow) {
        PulseHaptics.tap()
        try? session.store?.deleteDraft(conversationId: row.conversationId)
        reload()
    }

    private func clearAllDrafts() {
        guard let store = session.store else { return }
        if !clearDraftsArmed {
            clearDraftsArmed = true
            return
        }
        PulseHaptics.warning()
        for row in drafts {
            try? store.deleteDraft(conversationId: row.conversationId)
        }
        reload()
    }

    private func deleteQueued(_ row: OutboxRow) {
        PulseHaptics.tap()
        try? session.store?.deleteOutbox(clientId: row.clientId)
        reload()
    }

    private func discardAll() {
        guard let store = session.store else { return }
        if !discardQueueArmed {
            discardQueueArmed = true
            return
        }
        PulseHaptics.warning()
        for row in queued {
            try? store.deleteOutbox(clientId: row.clientId)
        }
        reload()
    }
}
