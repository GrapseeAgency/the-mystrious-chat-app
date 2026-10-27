import SwiftUI

/// Wave 6 - the report panel (F-CP-06), finishing the Wave 0 dialog with
/// full web parity (report-panel.tsx): private-by-design intro copy, the
/// 6 wire reasons, details ≤500, the reporter's OWN prior submissions as
/// the honest "already reported" hint, and the idempotent-verdict toasts.
struct ReportPanelView: View {
    let reported: WireUser
    @ObservedObject var session: PulseSession
    var onDone: () -> Void

    @Environment(\.dismiss) private var dismiss

    /// Wire reasons - the exact values POST /api/users/[id]/report accepts.
    static let reasons: [(key: String, label: String)] = [
        ("spam", "Spam"),
        ("harassment", "Harassment or bullying"),
        ("impersonation", "Impersonation"),
        ("inappropriate", "Inappropriate content"),
        ("scam", "Scam or fraud"),
        ("other", "Something else"),
    ]

    static let detailsMax = 500

    @State private var reason = ""
    @State private var details = ""
    @State private var history: [WireReportReasonRow] = []
    @State private var pending = false
    @State private var historyError: String?
    @State private var surfaceEpoch: UUID?
    @State private var surfaceTarget: String?
    @State private var actionTicket = UUID()
    @State private var readTicket = UUID()
    @State private var submitTask: Task<Void, Never>?
    @State private var historyTask: Task<Void, Never>?

    private var firstName: String {
        reported.name.split(separator: " ").first.map(String.init) ?? reported.name
    }

    private var hint: String? {
        guard !history.isEmpty else { return nil }
        let labels = history.map { row in
            Self.reasons.first(where: { $0.key == row.reason })?.label ?? (row.reason ?? "other")
        }
        return "You already reported this account for \(labels.joined(separator: ", "))."
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    reasonList.disabled(pending)
                    detailsField.disabled(pending)
                    submitButton
                }
                .padding(16)
                .padding(.bottom, 24)
            }
            .background(PulseTheme.pageWash.ignoresSafeArea())
            .navigationTitle("Report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
            .interactiveDismissDisabled(pending)
            .task(id: "\(session.sessionGeneration):\(reported.id)") {
                retire()
                surfaceEpoch = session.sessionGeneration; surfaceTarget = reported.id
                reason = ""; details = ""; history = []; historyError = nil
                loadHistory()
            }
            .onDisappear { retire() }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Report \(reported.name)", systemImage: "flag.fill")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(PulseTheme.amber600)
            Text("Tell us what's happening. Reports are private - \(firstName) will not be notified.")
                .font(.system(size: 12.5))
                .foregroundStyle(PulseTheme.textSecondary)
            if let historyError {
                Text(historyError).font(.footnote).foregroundStyle(PulseTheme.textSecondary)
                Button("Retry report history") { loadHistory() }.disabled(pending)
            }
            if let hint {
                Text(hint)
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(PulseTheme.amber600)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.amber500.opacity(0.10)))
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(PulseTheme.amber500.opacity(0.05)))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(PulseTheme.amber500.opacity(0.25), lineWidth: 1))
    }

    private var reasonList: some View {
        VStack(spacing: 6) {
            ForEach(Self.reasons, id: \.key) { option in
                reasonRow(option)
            }
        }
    }

    private func reasonRow(_ option: (key: String, label: String)) -> some View {
        let active = reason == option.key
        return Button {
            PulseHaptics.tap()
            reason = active ? "" : option.key
        } label: {
            HStack(spacing: 10) {
                ZStack {
                    Circle()
                        .strokeBorder(active ? PulseTheme.amber500 : PulseTheme.zinc(300), lineWidth: 1.5)
                    if active {
                        Circle().fill(PulseTheme.amber500).padding(3)
                    }
                }
                .frame(width: 16, height: 16)
                Text(option.label)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(PulseTheme.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 40)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(active ? PulseTheme.amber500.opacity(0.15) : PulseTheme.rowFill),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(active ? PulseTheme.amber500.opacity(0.5) : PulseTheme.hairlineSoft, lineWidth: 1),
            )
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("\(option.label)\(active ? ", selected" : "")")
    }

    private var detailsField: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField(
                "Details (optional)",
                text: $details,
                axis: .vertical,
            )
            .lineLimit(3...6)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.rowFill))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(PulseTheme.hairlineSoft, lineWidth: 1))
            Text("\(details.utf16.count)/\(Self.detailsMax)")
                .font(.system(size: 11))
                .foregroundStyle(PulseTheme.textTertiary)
        }
    }

    private var submitButton: some View {
        Button {
            submit()
        } label: {
            Group {
                if pending {
                    ProgressView().tint(.white)
                } else {
                    Text("Send report")
                        .font(.system(size: 14, weight: .bold))
                }
            }
            .frame(maxWidth: .infinity, minHeight: 46)
            .background(RoundedRectangle(cornerRadius: 14).fill(reason.isEmpty || pending ? PulseTheme.zinc(300) : PulseTheme.amber600))
            .foregroundStyle(.white)
        }
        .buttonStyle(PulseButtonStyle())
        .disabled(reason.isEmpty || pending || details.utf16.count > Self.detailsMax)
    }

    // actions

    private func owns(_ epoch: UUID, _ target: String) -> Bool {
        !Task.isCancelled && surfaceEpoch == epoch && session.sessionGeneration == epoch &&
            surfaceTarget == target && reported.id == target
    }

    private func retire() {
        surfaceEpoch = nil; surfaceTarget = nil
        readTicket = UUID(); actionTicket = UUID()
        historyTask?.cancel(); submitTask?.cancel()
        historyTask = nil; submitTask = nil; pending = false
    }

    private func loadHistory() {
        guard let epoch = surfaceEpoch, let target = surfaceTarget, owns(epoch, target), !pending else { return }
        let api = session.api, ticket = UUID()
        readTicket = ticket; historyTask?.cancel()
        historyTask = Task { @MainActor in
            do {
                let fresh = try await api.reportHistory(reportedId: target)
                guard owns(epoch, target), readTicket == ticket else { return }
                history = fresh; historyError = nil
            } catch {
                guard owns(epoch, target), readTicket == ticket else { return }
                historyError = "Could not refresh your prior reports. You can still submit a report."
            }
        }
    }

    private func submit() {
        guard let epoch = surfaceEpoch, let target = surfaceTarget, owns(epoch, target),
              !pending, !reason.isEmpty, details.utf16.count <= Self.detailsMax else { return }
        let api = session.api, chosenReason = reason, content = details, ticket = UUID()
        pending = true; actionTicket = ticket; readTicket = UUID(); historyTask?.cancel()
        submitTask = Task { @MainActor in
            defer { if owns(epoch, target), actionTicket == ticket { pending = false; submitTask = nil } }
            do {
                let verdict = try await api.report(userId: target, reason: chosenReason, details: content)
                guard owns(epoch, target), actionTicket == ticket else { return }
                PulseHaptics.success()
                session.toasts.show(verdict.updated == true
                    ? "Report updated - thanks for the extra detail"
                    : "Report submitted. Thanks for helping keep Pulse safe.")
                dismiss()
                onDone()
            } catch {
                guard owns(epoch, target), actionTicket == ticket else { return }
                session.toasts.show(ChatsViewModel.describe(error))
            }
        }
    }
}
