import SwiftUI

/// Wave 3 — call history (the CALL-LOG half of the wave). Renders the
/// single-writer REST rows cached in callLogCache, refreshed from
/// GET /api/calls on entry. All eight directive cases:
///   outgoing accepted   → arrow.up + "Outgoing · duration"
///   outgoing declined   → arrow.up + "Declined"
///   outgoing cancelled  → arrow.up + "No answer" (wire collapses cancel/timeout → missed)
///   incoming accepted   → arrow.down + "Incoming · duration"
///   incoming declined   → arrow.down + "Declined"
///   incoming missed     → arrow.down.right + "Missed" (caller cancel / 30s timeout)
///   timeout             → caller side of the same row ("No answer")
///   completed           → any connected call that ended normally (duration > 0)
struct CallsHistoryView: View {
    @ObservedObject var session: PulseSession
    @Environment(\.dismiss) private var dismiss

    @State private var rows: [CallLogEntry] = []
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            Group {
                if rows.isEmpty && loadError == nil {
                    ProgressView("Loading calls…")
                } else if rows.isEmpty {
                    ContentUnavailableCompat(
                        title: "No calls yet",
                        systemImage: "phone.badge.clock",
                        note: loadError,
                    )
                } else {
                    List(rows) { row in
                        CallHistoryRow(row: row)
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Calls")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .refreshable { await refresh(showErrors: true) }
            .task { await refresh(showErrors: false) }
        }
    }

    /// Cache first (instant paint), then the server (truth) — the store
    /// prunes rows the endpoint no longer lists.
    private func refresh(showErrors: Bool) async {
        if let store = session.store, let cached = try? store.callLog() {
            rows = cached
        }
        guard let viewer = session.viewer else { return }
        do {
            let items = try await session.api.callHistory(userId: viewer.id)
            try session.store?.syncCallLog(from: items)
            if let cached = try? session.store?.callLog() {
                rows = cached
            }
            loadError = nil
        } catch {
            if showErrors {
                loadError = ChatsViewModel.describe(error)
            }
        }
    }
}

private struct CallHistoryRow: View {
    let row: CallLogEntry

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(PulseTheme.emerald.opacity(0.14))
                    .frame(width: 40, height: 40)
                Text(initials)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(PulseTheme.emerald)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(row.peerName)
                    .font(.system(size: 15, weight: .medium))
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Image(systemName: directionIcon)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(tint)
                    Text(label)
                        .font(.system(size: 12))
                        .foregroundStyle(tint)
                }
            }
            Spacer()
            Image(systemName: "phone")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(row.peerName), \(label)")
    }

    private var initials: String {
        let parts = row.peerName.trimmingCharacters(in: .whitespaces).split(separator: " ")
        let joined = parts.prefix(2).compactMap { $0.first }.map { String($0).uppercased() }.joined()
        return joined.isEmpty ? "?" : joined
    }

    /// The 8 directive cases → (icon, label, tint).
    private var directionIcon: String {
        switch (row.outgoing, row.status) {
        case (true, "completed"): return "arrow.up.right"
        case (true, "declined"): return "arrow.up.right"
        case (true, _): return "arrow.up.right"
        case (false, "missed"): return "arrow.down.right"
        case (false, _): return "arrow.down.right"
        }
    }

    private var label: String {
        let duration = formatCallDuration(row.durationSec)
        switch (row.outgoing, row.status) {
        case (true, "completed"):
            return duration.isEmpty ? "Outgoing" : "Outgoing · \(duration)"
        case (true, "declined"):
            return "Declined"
        case (true, _):
            return "No answer"
        case (false, "completed"):
            return duration.isEmpty ? "Incoming" : "Incoming · \(duration)"
        case (false, "declined"):
            return "Declined"
        case (false, _):
            return "Missed"
        }
    }

    private var tint: Color {
        switch row.status {
        case "completed": return PulseTheme.emerald
        default: return Color(red: 0.88, green: 0.11, blue: 0.28)
        }
    }
}

/// m:ss / h:mm:ss — the same shape the overlay ticks with.
func formatCallDuration(_ totalSec: Int) -> String {
    let s = max(0, totalSec)
    let hours = s / 3600
    let minutes = (s % 3600) / 60
    let seconds = s % 60
    if hours > 0 {
        return String(format: "%d:%02d:%02d", hours, minutes, seconds)
    }
    return String(format: "%d:%02d", minutes, seconds)
}
