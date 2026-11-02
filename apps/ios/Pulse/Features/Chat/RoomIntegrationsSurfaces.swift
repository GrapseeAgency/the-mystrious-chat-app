import SwiftUI
import UIKit

// R2-B - integrations manager surfaces (web automations-sheet.tsx +
// group-info-sheet.tsx WebhooksSection parity, native):
//   • AutomationsSection - keyword auto-reply rules: rows + confirmed
//     enable/disable + honest delete + R41 trigger rename + create sheet.
//   • WebhooksSection - Discord-style incoming hooks: rows + copy ingest
//     URL + admin delete + Section 12 item 9 rotate (confirm dialog →
//     fresh capability; the row shows "Rotated <date>") + create sheet.
// Data rides the REAL routes (GET/POST /api/conversations/[id]/automations,
// PATCH/DELETE /api/automations/[id], GET/POST /api/webhooks,
// PATCH/DELETE /api/webhooks/[token]) through PulseAPIClient - no mocks.
// Section 12: every mutation identity is journaled in
// PulseSection12Journal BEFORE the wire (a lost ACK replays the SAME
// clientId on the session-start flush) and the lists walk the bounded
// keyset page walk (hasMore + nextCursor, 20-iteration ceiling).
// Mounted inside GroupInfoView (the iOS room-info surface, web
// room-info-page.tsx:1335 parity).

// MARK: - Automations (R39/R41)

/// The room-info "Automations" section: every rule as a row (trigger bold →
/// reply preview, Zap hits counter, honest Switch and delete for admins),
/// read-only for members with an honest caption. Section child - must sit
/// directly inside a List.
struct AutomationsSection: View {
    let conversationId: String
    let viewerId: String
    let isAdmin: Bool
    @ObservedObject var session: PulseSession

    enum Phase: Equatable { case loading, ready, failed(String) }
    @State private var lifetime = UUID()
    @State private var active = false
    @State private var revision = 0
    @State private var writing = false
    @State private var phase: Phase = .loading
    @State private var rows: [WireAutomation] = []
    @State private var createOpen = false
    @State private var editTarget: WireAutomation?
    @State private var editDraft = ""
    @State private var deletingIds: Set<String> = []

    var body: some View {
        Section {
            switch phase {
            case .loading:
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text("Loading automations…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            case .failed(let message):
                VStack(alignment: .leading, spacing: 8) {
                    Text("Could not load automations.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Try again") { Task { await load() } }
                        .font(.footnote.weight(.semibold))
                    Text(message)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(2)
                }
            case .ready:
                if rows.isEmpty {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: "bolt.badge.clock")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(.secondary)
                            .frame(width: 34, height: 34)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.08)))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(isAdmin ? "No automations yet" : "No automations here")
                                .font(.footnote.weight(.semibold))
                            Text(isAdmin
                                ? "Add a keyword that fires an instant reply when a member sends it."
                                : "Only admins can manage automations.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if isAdmin {
                            Button {
                                PulseHaptics.tap()
                                createOpen = true
                            } label: {
                                Image(systemName: "plus")
                            }
                            .buttonStyle(.bordered)
                            .tint(PulseTheme.emerald)
                            .accessibilityLabel("New automation")
                        }
                    }
                } else {
                    ForEach(rows) { row in
                        automationRow(row)
                    }
                    if isAdmin {
                        Button {
                            PulseHaptics.tap()
                            createOpen = true
                        } label: {
                            Label("New automation", systemImage: "plus")
                                .font(.subheadline.weight(.semibold))
                        }
                        .tint(PulseTheme.emerald)
                    }
                }
            }
        } header: {
            Text(sectionTitle)
        } footer: {
            Text("Fires once per matching message - as a reply from you.")
        }
        .disabled(writing)
        .sheet(isPresented: $createOpen) {
            AutomationsCreateSheet(conversationId: conversationId, session: session) { created in
                revision += 1
                rows.removeAll { $0.id == created.id }
                rows.insert(created, at: 0)
                phase = .ready
            }
        }
        .sheet(isPresented: Binding(get: { editTarget != nil }, set: { if !$0 && !writing { editTarget = nil } })) {
            NavigationStack {
                Form {
                    TextField("Trigger keyword", text: $editDraft).disabled(writing)
                    Text("2–40 characters. A trigger already used in this room is rejected.").font(.caption).foregroundStyle(.secondary)
                    Button(writing ? "Saving…" : "Save trigger") { renameTarget() }.disabled(writing)
                }
                .navigationTitle("Edit trigger")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { editTarget = nil }.disabled(writing)
                    }
                }
            }
            .interactiveDismissDisabled(writing)
            .presentationDetents([.medium])
        }
        .task(id: "\(session.sessionGeneration)|\(conversationId)|\(isAdmin)") {
            lifetime = UUID(); active = true; revision += 1
            rows = []; writing = false; deletingIds = []
            createOpen = false; editTarget = nil; editDraft = ""
            await load()
        }
        .onDisappear { active = false; lifetime = UUID(); revision += 1 }
    }

    private var sectionTitle: String {
        if case Phase.ready = phase, !rows.isEmpty {
            return "Automations · \(rows.count)"
        }
        return "Automations"
    }

    private func automationRow(_ row: WireAutomation) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "bolt.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(row.enabled == true ? PulseTheme.emerald : PulseTheme.textTertiary)
                .frame(width: 30, height: 30)
                .background(RoundedRectangle(cornerRadius: 9).fill(
                    row.enabled == true ? PulseTheme.emerald.opacity(0.12) : Color.secondary.opacity(0.08),
                ))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(row.trigger)
                        .font(.system(size: 13.5, weight: .bold))
                        .lineLimit(1)
                    if isAdmin {
                        Button {
                            PulseHaptics.tap()
                            editDraft = row.trigger
                            editTarget = row
                        } label: {
                            Image(systemName: "pencil")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .disabled(writing)
                        .accessibilityLabel("Edit trigger \"\(row.trigger)\"")
                    }
                }
                Text(row.reply ?? "")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(hitsLine(row))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
            Spacer()
            if isAdmin {
                Toggle("", isOn: Binding(
                    get: { row.enabled == true },
                    set: { next in toggle(row, next: next) },
                ))
                .labelsHidden()
                .disabled(writing)
                .accessibilityLabel("\(row.enabled == true ? "Disable" : "Enable") automation \"\(row.trigger)\"")
                Button {
                    PulseHaptics.tap()
                    delete(row)
                } label: {
                    if deletingIds.contains(row.id) {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "trash")
                            .font(.system(size: 14))
                            .foregroundStyle(Color.red.opacity(0.75))
                    }
                }
                .buttonStyle(.plain)
                .disabled(!deletingIds.isEmpty)
                .accessibilityLabel("Delete automation \"\(row.trigger)\"")
            } else {
                Text(row.enabled == true ? "On" : "Off")
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(row.enabled == true ? PulseTheme.emerald.opacity(0.12) : Color.secondary.opacity(0.10)))
                    .foregroundStyle(row.enabled == true ? PulseTheme.emeraldDeep : PulseTheme.textSecondary)
            }
        }
        .padding(.vertical, 2)
        .opacity(row.enabled == false ? 0.7 : 1)
    }

    /// Row meta line - "{hits} hit/hits · last {stamp} · by {name}"
    /// (web chat-room AutomationsSection row parity).
    private func hitsLine(_ row: WireAutomation) -> String {
        let hits = row.hits ?? 0
        var line = "\(hits) \(hits == 1 ? "hit" : "hits")"
        if row.lastFiredAt != nil {
            let stamp = PulseFormat.listStamp(row.lastFiredAt)
            if !stamp.isEmpty {
                line += " · last \(stamp)"
            }
        }
        if let author = row.createdBy?.name, !author.isEmpty {
            line += " · by \(author)"
        }
        return line
    }

    // data

    private func load() async {
        guard active, !writing else { return }
        revision += 1
        let ticket = revision, life = lifetime, epoch = session.sessionGeneration, api = session.api
        phase = .loading
        do {
            // Section 12 - bounded page walk: fetch, then chase the opaque
            // "<epochMs>.<id>" keyset cursor while the gateway says hasMore
            // (the 20-iteration ceiling keeps a misbehaving relay honest).
            var fresh: [WireAutomation] = []
            var cursor: String? = nil
            var iterations = 0
            repeat {
                guard active, life == lifetime, ticket == revision, !Task.isCancelled else { return }
                let page = try await api.automationsPage(conversationId: conversationId, limit: 100, cursor: cursor)
                fresh += page.automations ?? []
                if page.page?.hasMore == true, let next = page.page?.nextCursor, !next.isEmpty {
                    cursor = next
                } else {
                    cursor = nil
                }
                iterations += 1
            } while cursor != nil && iterations < 20
            guard active, life == lifetime, epoch == session.sessionGeneration, ticket == revision, !Task.isCancelled else { return }
            rows = fresh; phase = .ready
        } catch {
            guard active, life == lifetime, epoch == session.sessionGeneration, ticket == revision, !Task.isCancelled else { return }
            rows = []; phase = .failed(RoomViewModel.describe(error))
        }
    }

    // Serialize changes and apply only confirmed rows. An old rollback must not
    // resurrect a deletion or overwrite a newer rename.
    private func change(removed: String? = nil, closesEditor: Bool = false, notice: String? = nil,
                        operation: @escaping (PulseAPIClient) async throws -> WireAutomation?) {
        guard active, isAdmin, !writing else { return }
        writing = true; revision += 1
        if let removed { deletingIds.insert(removed) }
        let life = lifetime, epoch = session.sessionGeneration, api = session.api
        Task {
            defer {
                if life == lifetime { writing = false; deletingIds = [] }
            }
            do {
                let fresh = try await operation(api)
                guard active, life == lifetime, epoch == session.sessionGeneration, !Task.isCancelled else { return }
                revision += 1
                if let fresh { applyRow(fresh) }
                if let removed { rows.removeAll { $0.id == removed } }
                if closesEditor { editTarget = nil }
                phase = .ready
                if let notice { session.toasts.show(notice) }
            } catch {
                guard active, life == lifetime, epoch == session.sessionGeneration, !Task.isCancelled else { return }
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func toggle(_ row: WireAutomation, next: Bool) {
        change { api in try await api.updateAutomation(row.id, enabled: next) }
    }

    private func delete(_ row: WireAutomation) {
        // Section 12 item 1 - the deletion identity is journaled BEFORE the
        // wire; the row leaves the journal ONLY on the confirmed verdict, so
        // a lost ACK replays the SAME verdict on the next launch flush.
        guard active, isAdmin, !writing, deletingIds.isEmpty else { return }
        let journal = PulseSection12Journal.shared
        let owner = session.api.userId
        let intent = journal.enqueue(owner: owner, kind: "automation-delete", targetId: row.id)
        writing = true; revision += 1
        deletingIds.insert(row.id)
        let life = lifetime, epoch = session.sessionGeneration, api = session.api
        Task {
            defer { if life == lifetime { writing = false; deletingIds = [] } }
            do {
                try await api.deleteAutomation(row.id, clientId: intent.clientId)
                journal.confirm(owner: owner, clientId: intent.clientId)
                guard active, life == lifetime, epoch == session.sessionGeneration, !Task.isCancelled else { return }
                revision += 1
                rows.removeAll { $0.id == row.id }
                phase = .ready
                session.toasts.show("Automation deleted")
            } catch {
                // Failure is not a receipt: the intent stays pending for the
                // next launch flush (which replays the SAME clientId).
                guard active, life == lifetime, epoch == session.sessionGeneration, !Task.isCancelled else { return }
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func renameTarget() {
        guard let target = editTarget else { return }
        let trigger = editDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...40).contains(trigger.utf16.count) else {
            session.toasts.show("Use 2–40 characters for the trigger")
            return
        }
        change(closesEditor: true, notice: "Trigger updated") { api in
            try await api.updateAutomation(target.id, trigger: trigger)
        }
    }

    private func applyRow(_ fresh: WireAutomation) {
        if let index = rows.firstIndex(where: { $0.id == fresh.id }) {
            rows[index] = fresh
        } else {
            rows.append(fresh)
        }
    }
}

/// Small form sheet: trigger + reply with live validation and a
/// pending-disabled submit (web AutomationsCreateSheet parity).
private struct AutomationsCreateSheet: View {
    let conversationId: String
    @ObservedObject var session: PulseSession
    var onCreated: (WireAutomation) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var trigger = ""
    @State private var reply = ""
    @State private var creating = false
    @State private var active = false
    @State private var lifetime = UUID()

    private var trimmedTrigger: String { trigger.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedReply: String { reply.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var valid: Bool {
        PulseRoomParityLogic.automationValid(trigger: trigger, reply: reply)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("Trigger keyword")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text("\(trimmedTrigger.count)/\(PulseRoomParityLogic.automationTriggerMax)")
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        TextField("e.g. pricing", text: $trigger)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        if !trigger.isEmpty && trimmedTrigger.count < PulseRoomParityLogic.automationTriggerMin {
                            Text("Use \(PulseRoomParityLogic.automationTriggerMin)-\(PulseRoomParityLogic.automationTriggerMax) characters.")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(Color.red)
                        } else {
                            Text("Matched as a standalone word - \"pricing\" will not fire on \"pricinggg\".")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("Reply")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text("\(trimmedReply.count)/\(PulseRoomParityLogic.automationReplyMax)")
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        TextField("Sent as a normal message when the keyword appears", text: $reply, axis: .vertical)
                            .lineLimit(3...5)
                        if !reply.isEmpty && trimmedReply.isEmpty {
                            Text("Use 1-\(PulseRoomParityLogic.automationReplyMax) characters.")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(Color.red)
                        } else {
                            Text("The message lands from your account, marked Automation.")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    Button {
                        create()
                    } label: {
                        HStack {
                            if creating {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: "bolt.fill")
                            }
                            Text("Create automation")
                                .font(.subheadline.weight(.bold))
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .disabled(!valid || creating)
                    .tint(PulseTheme.emerald)
                }
            }
            .disabled(creating)
            .navigationTitle("New automation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(creating)
                }
            }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled(creating)
        .onAppear { active = true; lifetime = UUID() }
        .onDisappear { active = false; lifetime = UUID() }
        .onChange(of: session.sessionGeneration) { _ in active = false; dismiss() }
    }

    private func create() {
        let trimmedTrigger = self.trimmedTrigger
        let trimmedReply = self.trimmedReply
        guard valid, !creating else { return }
        let epoch = session.sessionGeneration
        let api = session.api
        let life = lifetime
        // Section 12 item 1 - the rule identity is journaled BEFORE the wire;
        // a lost ACK replays the SAME clientId and the server answers the
        // SAME rule (never a duplicate).
        let journal = PulseSection12Journal.shared
        let owner = api.userId
        let intent = journal.enqueue(owner: owner, kind: "automation-create", targetId: conversationId,
            payload: ["trigger": trimmedTrigger, "reply": trimmedReply])
        creating = true
        Task {
            defer { creating = false }
            do {
                let fresh = try await api.createAutomation(
                    conversationId: conversationId,
                    trigger: trimmedTrigger,
                    reply: trimmedReply,
                    clientId: intent.clientId,
                )
                journal.confirm(owner: owner, clientId: intent.clientId)
                guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration else { return }
                onCreated(fresh)
                PulseHaptics.success()
                session.toasts.show("Automation created")
                dismiss()
            } catch {
                guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration else { return }
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }
}

// MARK: - Webhooks (Discord-style incoming integrations)

/// Admin-only management of incoming webhook posting credentials. Data is fully real: GET/POST
/// /api/webhooks + DELETE /api/webhooks/[token].
struct WebhooksSection: View {
    let conversationId: String
    let viewerId: String
    let isAdmin: Bool
    @ObservedObject var session: PulseSession

    enum Phase: Equatable { case loading, ready, failed(String) }
    @State private var lifetime = UUID()
    @State private var active = false
    @State private var revision = 0
    @State private var phase: Phase = .loading
    @State private var rows: [WireWebhook] = []
    @State private var createOpen = false
    @State private var deletingIds: Set<String> = []
    // Section 12 item 9 - the rotation affordance: confirm-then-rotate.
    @State private var rotateTarget: WireWebhook?
    @State private var rotatingIds: Set<String> = []

    var body: some View {
        Section {
            if !isAdmin {
                Text("Webhook credentials are available to admins only.").font(.footnote).foregroundStyle(.secondary)
            } else {
                switch phase {
                case .loading:
                    HStack(spacing: 10) {
                        ProgressView().controlSize(.small)
                        Text("Loading webhooks…")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                case .failed(let message):
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Could not load webhooks.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Button("Try again") { Task { await load() } }
                            .font(.footnote.weight(.semibold))
                        Text(message)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .lineLimit(2)
                    }
                case .ready:
                    if rows.isEmpty {
                        VStack(spacing: 6) {
                            Image(systemName: "number.circle")
                                .font(.system(size: 22, weight: .light))
                                .foregroundStyle(.tertiary)
                            Text("No webhooks yet")
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(.secondary)
                            Text(isAdmin
                                ? "Create one to let outside services post into this chat."
                                : "Admins can add Discord-style integrations here.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    } else {
                        ForEach(rows) { webhook in
                            webhookRow(webhook)
                        }
                    }
                    if isAdmin {
                        Button {
                            PulseHaptics.tap()
                            createOpen = true
                        } label: {
                            Label("Create webhook", systemImage: "plus")
                                .font(.subheadline.weight(.semibold))
                        }
                        .tint(PulseTheme.emerald)
                    }
                }
            }
        } header: {
            Text(isAdmin && phase == .ready && !rows.isEmpty ? "Webhooks · \(rows.count)" : "Webhooks")
        } footer: {
            Text("Outside services POST to its URL and drop messages into this chat as a named sender.")
        }
        .sheet(isPresented: Binding(get: { createOpen && isAdmin }, set: { createOpen = $0 })) {
            WebhookCreateSheet(conversationId: conversationId, session: session) { created in
                revision += 1
                rows.removeAll { $0.id == created.id }
                rows.append(created)
                phase = .ready
            }
        }
        .confirmationDialog(
            rotateTarget.map { Text("Rotate \($0.name) URL?") } ?? Text("Rotate webhook URL?"),
            isPresented: Binding(get: { rotateTarget != nil }, set: { if !$0 { rotateTarget = nil } }),
            titleVisibility: .visible,
            presenting: rotateTarget,
        ) { webhook in
            Button("Rotate URL", role: .destructive) { rotate(webhook) }
            Button("Cancel", role: .cancel) {}
        } message: { webhook in
            Text("A fresh URL replaces \(webhook.name)'s current one. The old link stops working immediately - services using it must be updated.")
        }
        .task(id: "\(session.sessionGeneration)|\(conversationId)|\(isAdmin)") {
            active = true; lifetime = UUID(); revision += 1
            rows = []; createOpen = false; deletingIds = []; rotateTarget = nil; rotatingIds = []
            if isAdmin { await load() }
        }
        .onDisappear { active = false; lifetime = UUID(); revision += 1 }
    }

    private func webhookRow(_ webhook: WireWebhook) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(PulseTheme.color(named: webhook.avatarColor))
                .frame(width: 12, height: 12)
            VStack(alignment: .leading, spacing: 2) {
                Text(webhook.name)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text(metaLine(webhook))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                copyIngestURL(webhook)
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 15))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Copy \(webhook.name) webhook URL")
            if isAdmin {
                // Section 12 item 9 - mint a fresh capability URL.
                Button {
                    PulseHaptics.tap()
                    rotateTarget = webhook
                } label: {
                    if rotatingIds.contains(webhook.id) {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
                .disabled(!rotatingIds.isEmpty || !deletingIds.isEmpty)
                .accessibilityLabel("Rotate \(webhook.name) webhook URL")
                Button {
                    delete(webhook)
                } label: {
                    if deletingIds.contains(webhook.token) {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "trash")
                            .font(.system(size: 15))
                            .foregroundStyle(Color.red.opacity(0.75))
                    }
                }
                .buttonStyle(.plain)
                .disabled(!deletingIds.isEmpty)
                .accessibilityLabel("Delete \(webhook.name)")
            }
        }
        .padding(.vertical, 2)
    }

    /// "/api/webhooks/<token> · added <stamp> · Rotated <stamp>" - the
    /// rotation stamp shows only once the capability has been rotated
    /// (Section 12 item 9).
    private func metaLine(_ webhook: WireWebhook) -> String {
        var line = "/api/webhooks/\(webhook.token) · added \(PulseFormat.listStamp(webhook.createdAt))"
        if let rotatedAt = webhook.rotatedAt {
            let stamp = PulseFormat.listStamp(rotatedAt)
            if !stamp.isEmpty { line += " · Rotated \(stamp)" }
        }
        return line
    }

    /// The shareable ingest URL - the gateway origin + the relative path the
    /// route returns (web `${location.origin}/api/webhooks/${token}` parity).
    private func copyIngestURL(_ webhook: WireWebhook) {
        let relative = webhook.url ?? "/api/webhooks/\(webhook.token)"
        guard let url = URL(string: relative, relativeTo: PulseEndpoints.gatewayURL)?.absoluteURL else {
            session.toasts.show("Webhook URL could not be formed")
            return
        }
        PulseClipboard.copyText(url.absoluteString)
        PulseHaptics.tap()
        session.toasts.show("Webhook URL copied")
    }

    private func load() async {
        guard active, isAdmin, deletingIds.isEmpty else { return }
        revision += 1
        let ticket = revision, life = lifetime
        let epoch = session.sessionGeneration
        let api = session.api
        phase = .loading
        do {
            // Section 12 - bounded page walk: fetch, then chase the opaque
            // "<epochMs>.<id>" keyset cursor while the gateway says hasMore
            // (the 20-iteration ceiling keeps a misbehaving relay honest).
            var fresh: [WireWebhook] = []
            var cursor: String? = nil
            var iterations = 0
            repeat {
                guard active, life == lifetime, ticket == revision, !Task.isCancelled else { return }
                let page = try await api.webhooksPage(conversationId: conversationId, limit: 100, cursor: cursor)
                fresh += page.webhooks ?? []
                if page.page?.hasMore == true, let next = page.page?.nextCursor, !next.isEmpty {
                    cursor = next
                } else {
                    cursor = nil
                }
                iterations += 1
            } while cursor != nil && iterations < 20
            guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration, ticket == revision else { return }
            rows = fresh
            phase = .ready
        } catch {
            guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration, ticket == revision else { return }
            rows = []
            phase = .failed(RoomViewModel.describe(error))
        }
    }

    private func delete(_ webhook: WireWebhook) {
        guard active, isAdmin, deletingIds.isEmpty else { return }
        // Section 12 item 1 - the deletion identity is journaled BEFORE the
        // wire; a lost ACK replays the SAME verdict on the next launch flush.
        let journal = PulseSection12Journal.shared
        let owner = session.api.userId
        let intent = journal.enqueue(owner: owner, kind: "webhook-delete", targetId: webhook.token)
        revision += 1
        let life = lifetime
        let epoch = session.sessionGeneration
        let api = session.api
        deletingIds.insert(webhook.token)
        Task {
            defer { if life == lifetime { deletingIds.remove(webhook.token) } }
            do {
                try await api.deleteWebhook(token: webhook.token, clientId: intent.clientId)
                journal.confirm(owner: owner, clientId: intent.clientId)
                guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration else { return }
                revision += 1
                rows.removeAll { $0.id == webhook.id }
                session.toasts.show("Webhook deleted")
            } catch {
                // Failure is not a receipt: the intent stays pending for the
                // next launch flush (which replays the SAME clientId).
                guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration else { return }
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    /// Section 12 item 9 - the confirm dialog funnels here: the rotation
    /// identity is journaled BEFORE the wire (targetId = the OLD token the
    /// replay must ride), then the row swaps for the FRESH capability the
    /// receipt carries. A lost ACK replays the SAME fresh token on the next
    /// launch flush; the copy affordance then copies the new URL.
    private func rotate(_ webhook: WireWebhook) {
        guard active, isAdmin, rotatingIds.isEmpty, deletingIds.isEmpty else { return }
        let journal = PulseSection12Journal.shared
        let owner = session.api.userId
        let intent = journal.enqueue(owner: owner, kind: "webhook-rotate", targetId: webhook.token)
        revision += 1
        let life = lifetime
        let epoch = session.sessionGeneration
        let api = session.api
        rotatingIds.insert(webhook.id)
        Task {
            defer { if life == lifetime { rotatingIds.remove(webhook.id) } }
            do {
                let mutation = try await api.rotateWebhook(token: webhook.token, requesterId: owner, clientId: intent.clientId)
                journal.confirm(owner: owner, clientId: intent.clientId)
                guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration else { return }
                revision += 1
                if let index = rows.firstIndex(where: { $0.id == webhook.id }) {
                    rows[index] = WireWebhook(mutation: mutation, fallback: webhook)
                }
                session.toasts.show("Webhook URL rotated - the old link no longer works")
            } catch {
                guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration else { return }
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }
}

/// Create form - name (1–32 chars, WEBHOOK_NAME_MAX), POST → 201 row.
private struct WebhookCreateSheet: View {
    let conversationId: String
    @ObservedObject var session: PulseSession
    var onCreated: (WireWebhook) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var creating = false
    @State private var active = false
    @State private var lifetime = UUID()

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var valid: Bool { PulseRoomParityLogic.webhookNameValid(name) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("e.g. Deploys · CI · Weather", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Webhook name")
                } footer: {
                    Text("\(trimmed.count)/\(PulseRoomParityLogic.webhookNameMax) - outside services POST to its URL and drop messages into this chat as a named sender.")
                }
                Section {
                    Button {
                        create()
                    } label: {
                        if creating {
                            HStack {
                                ProgressView().controlSize(.small)
                                Text("Creating…")
                            }
                            .frame(maxWidth: .infinity)
                        } else {
                            Text("Create webhook")
                                .font(.subheadline.weight(.bold))
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .disabled(!valid || creating)
                    .tint(PulseTheme.emerald)
                }
            }
            .disabled(creating)
            .navigationTitle("Create webhook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(creating)
                }
            }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled(creating)
        .onAppear { active = true; lifetime = UUID() }
        .onDisappear { active = false; lifetime = UUID() }
        .onChange(of: session.sessionGeneration) { _ in active = false; dismiss() }
    }

    private func create() {
        let trimmedName = trimmed
        guard valid, !creating else { return }
        let epoch = session.sessionGeneration
        let api = session.api
        let life = lifetime
        // Section 12 item 1 - the capability identity is journaled BEFORE the
        // wire; a lost ACK replays the SAME clientId and the server answers
        // the SAME token instead of minting a second credential.
        let journal = PulseSection12Journal.shared
        let owner = api.userId
        let intent = journal.enqueue(owner: owner, kind: "webhook-create", targetId: conversationId,
            payload: ["name": trimmedName])
        creating = true
        Task {
            defer { creating = false }
            do {
                let fresh = try await api.createWebhook(conversationId: conversationId, name: trimmedName, clientId: intent.clientId)
                journal.confirm(owner: owner, clientId: intent.clientId)
                guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration else { return }
                onCreated(fresh)
                PulseHaptics.success()
                session.toasts.show("Webhook “\(fresh.name)” created")
                dismiss()
            } catch {
                guard active, life == lifetime, !Task.isCancelled, epoch == session.sessionGeneration else { return }
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }
}

// MARK: - helpers

extension WireAutomation {
    /// Optimistic flip helper - mirrors the web's `{ ...r, enabled }` cache
    /// patch while the PATCH is in flight.
    func withEnabled(_ value: Bool) -> WireAutomation {
        WireAutomation(
            id: id, conversationId: conversationId, trigger: trigger, reply: reply,
            enabled: value, hits: hits, lastFiredAt: lastFiredAt, createdAt: createdAt,
            createdBy: createdBy,
        )
    }
}

extension WireWebhook {
    /// Section 12 item 9 - the rotation receipt replaces the capability in
    /// place: same row identity, fresh token/URL, rotatedAt stamped. The old
    /// row fills any field an older relay omits from the receipt.
    init(mutation: WireWebhookMutation, fallback: WireWebhook) {
        self.init(
            id: mutation.id ?? fallback.id,
            name: mutation.name ?? fallback.name,
            token: mutation.token ?? fallback.token,
            avatarColor: mutation.avatarColor ?? fallback.avatarColor,
            url: mutation.url ?? fallback.url,
            createdAt: mutation.createdAt ?? fallback.createdAt,
            createdBy: mutation.createdBy ?? fallback.createdBy,
            rotatedAt: mutation.rotatedAt ?? fallback.rotatedAt,
        )
    }
}
