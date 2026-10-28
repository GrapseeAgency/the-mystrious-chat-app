import SwiftUI

/// Wave 8 - Drafts & outbox manager (web settings Chat section parity):
/// the REAL per-conversation GRDB draft rows and the offline outbox queue
/// with per-row delete + clear-all. Fully offline - both stores are local
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
    @State private var loadedOwner: UUID?
    @State private var draftRevisions: [String: UInt64] = [:]
    @State private var loadError: String?
    @State private var recovery: [PulseComposerRecovery] = []
    @State private var recoveryCursor: String?
    @State private var recoveryConfirmation: PulseComposerRecovery?
    @State private var recoveryAction = "remove"
    @State private var actionNotice: String?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PulseTheme.pageWash
                    .ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 14) {
                        if let loadError {
                            Text(loadError).font(.footnote).foregroundStyle(PulseTheme.titleOnPanel)
                            Button("Retry loading saved messages") { reload() }
                        }
                        if let actionNotice { Text(actionNotice).font(.footnote).foregroundStyle(PulseTheme.titleOnPanel) }
                        recoveryCard
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
        .onReceive(session.outboxEvents) { _ in reload() }
        .onChange(of: session.scheduledRecoveryRevision) { _, _ in reloadRecovery() }
        .alert(recoveryAction == "replace" ? "Use this saved version?" : "Remove local recovery?",isPresented: Binding(get: { recoveryConfirmation != nil },set: { if !$0 { recoveryConfirmation = nil } })) {
            Button("Keep it",role: .cancel) { recoveryConfirmation = nil }
            Button("Confirm") { if let row = recoveryConfirmation { performRecovery(row,action: recoveryAction) }; recoveryConfirmation = nil }
        } message: {
            Text(recoveryAction == "replace" ? "Replace the current server draft or edit with this saved text? A further server change will still cause a conflict rather than being overwritten." : "This removes only the local recovery copy. It does NOT cancel a scheduled message or undo a send or edit already accepted by the server.")
        }
        .onChange(of: session.sessionGeneration) { _, _ in
            drafts = []; queued = []; recovery = []; recoveryCursor = nil; recoveryConfirmation = nil
            conversationNames = [:]; draftRevisions = [:]; loadedOwner = nil; actionNotice = nil
            dismiss()
        }
    }

    private var recoveryCard: some View {
        settingsCard(title: "Composer recovery",icon: "arrow.clockwise",count: recovery.count) {
            Button("Refresh recovery") { reloadRecovery() }
            ForEach(recovery) { row in
                VStack(alignment: .leading,spacing: 6) {
                    Text(row.kind).font(.subheadline.weight(.semibold))
                    if !row.text.isEmpty { Text(row.text).font(.footnote).lineLimit(3) }
                    Text(row.notice).font(.caption).foregroundStyle(.secondary)
                    HStack {
                        if !row.text.isEmpty { Button("Copy text") { if ownedStore() != nil { UIPasteboard.general.string = row.text } } }
                        if row.canRetry { Button("Retry original") { performRecovery(row,action: "retry") } }
                    }.font(.caption)
                    if row.canReplace { Button("Use this version") { recoveryAction = "replace"; recoveryConfirmation = row }.font(.caption) }
                    Button("Remove local copy") { recoveryAction = "remove"; recoveryConfirmation = row }.font(.caption)
                }.padding(10).background(rowPanel)
            }
            if recoveryCursor != nil { Button("Load more recovery") { reloadRecovery(more: true) } }
        }
    }
    private func reloadRecovery(more: Bool = false) {
        guard let store = ownedStore(), let owner = session.viewer?.id else { return }
        do {
            let page = try store.composerRecoveryPage(owner: owner,after: more ? recoveryCursor : nil)
            recovery = (more ? recovery : []) + page.items; recoveryCursor = page.nextCursor
        } catch { actionNotice = "Recovery could not be loaded. Original text has not been discarded." }
    }
    private func performRecovery(_ row: PulseComposerRecovery, action: String) {
        let epoch = session.sessionGeneration
        Task {
            do {
                try await session.recoverComposer(row,action: action)
                guard session.sessionGeneration == epoch else { return }
                actionNotice = action == "remove" ? "Local copy removed. Server actions were not undone." : "Original text retained while the request is reconciled."
            } catch { if session.sessionGeneration == epoch { actionNotice = "Recovery changed or could not be confirmed. Refresh before trying again." } }
            if session.sessionGeneration == epoch { reloadRecovery() }
        }
    }

    // drafts

    private var draftsCard: some View {
        settingsCard(title: "Saved drafts", icon: "doc.text", count: drafts.count) {
            if loadError != nil { emptyNote("Drafts could not be verified. Nothing has been cleared.") }
            else if drafts.isEmpty {
                emptyNote("No drafts - composer text saves per conversation on this device.")
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

    // outbox

    private var outboxCard: some View {
        settingsCard(title: "Outbox & failed sends", icon: "cloud.slash", count: queued.count) {
            if loadError != nil { emptyNote("The outgoing queue could not be verified.") }
            else if queued.isEmpty {
                emptyNote("No retained outgoing messages.")
            } else {
                ForEach(queued, id: \.clientId) { row in
                    outboxRow(row)
                }
                Text("Pending messages retry online. Failed messages are kept for recovery. Discarding stops pending retries, but a send already in flight may still arrive.")
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
            if let reason = row.failureReason {
                Text("Not sent: " + reason).font(.system(size: 12)).foregroundStyle(.red)
                Button("Retry original message") {
                    guard ownedStore() != nil else { return }
                    session.retryOutbox(clientId: row.clientId, conversationId: row.conversationId)
                    reload()
                }
                    .font(.system(size: 12, weight: .semibold))
            }
            if let envelope = PulseOutboxForward.decode(row.payloadJson), ["image", "file", "audio"].contains(envelope.kind ?? "") {
                Label(envelope.fileName ?? (envelope.kind == "audio" ? "Voice note" : "Photo"), systemImage: "paperclip")
                    .font(.caption)
                Text(envelope.uploadDataUrl == nil ? "Upload kept; message awaits delivery." : "Attachment bytes kept on this device.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Button("Copy text") { UIPasteboard.general.string = row.content }
                .font(.system(size: 12, weight: .semibold))
            Text(row.content)
                .textSelection(.enabled)
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(10)
        .background(rowPanel)
        .accessibilityElement(children: .combine)
    }

    // primitives

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
        if PulseThreadDraft.isThread(conversationId) { return "Thread reply" }
        return conversationNames[conversationId] ?? "Conversation"
    }

    private func stamp(_ iso: String) -> String? {
        guard let date = PulseFormat.date(iso) else { return nil }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    // actions (real GRDB rows)

    private func ownedStore() -> PulseStore? {
        guard loadedOwner == session.sessionGeneration, let store = session.store else {
            actionNotice = "This account changed or its storage is unavailable. Reopen the manager."
            return nil
        }
        return store
    }

    private func reload() {
        let epoch = session.sessionGeneration
        if loadedOwner != epoch {
            drafts = []; queued = []; conversationNames = [:]; draftRevisions = [:]
            recovery = []; recoveryCursor = nil; recoveryConfirmation = nil; actionNotice = nil
        }
        loadedOwner = epoch
        reloadRecovery()
        clearDraftsArmed = false
        discardQueueArmed = false
        guard let store = session.store else { loadError = "Saved messages are unavailable on this session."; return }
        do {
            let loadedDrafts = try store.allDraftRows()
            let loadedQueue = try store.outboxAll()
            draftRevisions = Dictionary(uniqueKeysWithValues: loadedDrafts.map {
                ($0.conversationId, store.captureDraftConsumption(conversationId: $0.conversationId).revision)
            })
            drafts = loadedDrafts
            queued = loadedQueue
            if let conversations = try? store.cachedConversations() {
                conversationNames = Dictionary(conversations.map { ($0.id, $0.title) }, uniquingKeysWith: { _, last in last })
            }
            loadError = nil
        } catch { loadError = "Couldn't read saved messages. Your drafts and outgoing queue have not been discarded." }
    }

    private func deleteDraft(_ row: DraftRow) {
        clearDrafts([row])
    }

    private func clearDrafts(_ rows: [DraftRow]) {
        guard loadError == nil, let store = ownedStore() else { return }
        var changed = 0, failed = 0
        for row in rows {
            do {
                guard let revision = draftRevisions[row.conversationId] else { failed += 1; continue }
                if try store.deleteDraftIfUnchanged(row, revision: revision) { changed += 1 } else { failed += 1 }
            } catch { failed += 1 }
        }
        actionNotice = failed == 0 ? "Cleared \(changed) draft(s)." : "Cleared \(changed). \(failed) changed or couldn't be cleared; review the remaining drafts."
        reload()
    }

    private func clearAllDrafts() {
        guard loadError == nil, ownedStore() != nil else { return }
        if !clearDraftsArmed { clearDraftsArmed = true; return }
        clearDrafts(drafts)
    }

    private func deleteQueued(_ row: OutboxRow) { discard([row]) }

    private func discard(_ rows: [OutboxRow]) {
        guard loadError == nil, let store = ownedStore() else { return }
        let epoch = session.sessionGeneration
        var changed = 0, failed = 0
        for row in rows {
            guard session.sessionGeneration == epoch, session.store === store else { dismiss(); return }
            do {
                if try session.discardOutbox(row) { changed += 1 } else { failed += 1 }
            } catch { failed += 1 }
        }
        guard session.sessionGeneration == epoch else { dismiss(); return }
        actionNotice = failed == 0 ? "Discarded \(changed) recovery copy/copies." : "Discarded \(changed). \(failed) changed or couldn't be discarded; review the remaining queue."
        reload()
    }

    private func discardAll() {
        guard loadError == nil, ownedStore() != nil else { return }
        if !discardQueueArmed { discardQueueArmed = true; return }
        discard(queued)
    }
}
