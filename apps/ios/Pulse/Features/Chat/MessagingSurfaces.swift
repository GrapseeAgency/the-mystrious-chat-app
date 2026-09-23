import SwiftUI

// ─────────────────────────────────────────────────────────────
// REM-B P2/P3 — messaging-flow surfaces (web parity, native sheets):
//   • ScheduleSheet         — F-MS-18 delayed send (30 s – 30 d window)
//   • ScheduledManagerSheet — F-MS-18 list + cancel pending rows
//   • ReactionPickerSheet   — F-MS-08 the web's exact 24-emoji picker grid
//   • WhoReactedSheet       — F-MS-08 long-press chip → who-reacted list
//   • StickerPickerSheet    — F-MS-24 the web's 5 packs (kind "sticker")
//   • SlashPaletteView      — F-MS-22 '/'-trigger palette above the composer
// ─────────────────────────────────────────────────────────────

/// F-MS-18 — arm a delayed send. The server enforces 30 s minimum and a
/// 30-day horizon; the presets mirror the web schedule sheet.
struct ScheduleSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var mode: Mode = .preset
    @State private var presetIndex = 0
    @State private var customDate = Date().addingTimeInterval(3_600)
    @State private var customTime = Date().addingTimeInterval(3_600)

    enum Mode: String, CaseIterable { case preset = "Presets", custom = "Custom" }

    static let presets: [(label: String, seconds: Double)] = [
        ("In 1 hour", 3_600),
        ("Tomorrow 9:00", -1),
        ("In 1 week", 7 * 86_400),
    ]

    /// Computed per open — the web remindPresets pattern (never cached).
    private var resolvedDate: Date? {
        switch mode {
        case .preset:
            let preset = Self.presets[presetIndex]
            if preset.seconds > 0 { return Date().addingTimeInterval(preset.seconds) }
            var components = Calendar.current.dateComponents([.year, .month, .day], from: Date())
            components.hour = 9
            components.minute = 0
            return Calendar.current.date(from: components).flatMap { $0 > Date() ? $0 : Calendar.current.date(byAdding: .day, value: 1, to: $0) }
        case .custom:
            var components = Calendar.current.dateComponents([.year, .month, .day], from: customDate)
            let time = Calendar.current.dateComponents([.hour, .minute], from: customTime)
            components.hour = time.hour
            components.minute = time.minute
            return Calendar.current.date(from: components)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Send later") {
                    Picker("Mode", selection: $mode) {
                        ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    if mode == .preset {
                        ForEach(Array(Self.presets.enumerated()), id: \.offset) { index, preset in
                            Button {
                                presetIndex = index
                            } label: {
                                HStack {
                                    Text(preset.label).foregroundStyle(.primary)
                                    Spacer()
                                    if presetIndex == index {
                                        Image(systemName: "checkmark").foregroundStyle(PulseTheme.emerald)
                                    }
                                }
                            }
                        }
                    } else {
                        DatePicker("Date", selection: $customDate, displayedComponents: .date)
                        DatePicker("Time", selection: $customTime, displayedComponents: .hourAndMinute)
                    }
                }
                if let when = resolvedDate {
                    Section {
                        LabeledContent("Will send at", value: PulseFormat.dayLabel(isoOf(when)) + " " + PulseFormat.clockTime(isoOf(when)))
                    } footer: {
                        Text("Between 30 seconds and 30 days from now. Cancel any time from the banner.")
                    }
                }
            }
            .navigationTitle("Schedule message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Schedule") { dismissAndArm() }
                        .font(.subheadline.weight(.semibold))
                        .disabled(!isInServerWindow)
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private var isInServerWindow: Bool {
        guard let when = resolvedDate else { return false }
        let seconds = when.timeIntervalSinceNow
        return seconds >= 30 && seconds <= 30 * 24 * 3_600
    }

    private func isoOf(_ date: Date) -> String {
        Self.isoFormatter.string(from: date)
    }

    /// Server-side parseIsoDate accepts standard ISO-8601; mirrors the
    /// outbox clock's fractional-seconds shape.
    static let isoFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private func dismissAndArm() {
        guard let when = resolvedDate, isInServerWindow else { return }
        onSchedule(when)
        dismiss()
    }

    var onSchedule: (Date) -> Void = { _ in }
}

/// F-MS-18 — the sender's pending scheduled rows with cancel (GET/POST
/// conversations/{id}/scheduled + DELETE /api/scheduled/{id}).
struct ScheduledManagerSheet: View {
    let conversationId: String
    @ObservedObject var session: PulseSession

    @Environment(\.dismiss) private var dismiss
    @State private var items: [WireScheduledItem] = []
    @State private var loading = true

    var body: some View {
        NavigationStack {
            List {
                if loading && items.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 20)
                }
                ForEach(items) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.content.isEmpty ? "(empty message)" : item.content)
                            .font(.subheadline)
                            .lineLimit(3)
                        HStack(spacing: 6) {
                            if item.cancelledAt != nil {
                                Label("Dispatch refused", systemImage: "xmark.octagon.fill")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.red)
                            } else {
                                Image(systemName: "clock")
                                    .font(.caption2)
                                    .foregroundStyle(PulseTheme.amber)
                                Text(PulseFormat.dayLabel(item.scheduledAt) + " " + PulseFormat.clockTime(item.scheduledAt))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(.vertical, 2)
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            cancel(item)
                        } label: {
                            Label("Cancel", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .overlay {
                if !loading && items.isEmpty {
                    ContentUnavailableCompat(title: "Nothing scheduled", systemImage: "clock.badge.questionmark", note: "Arm a delayed send from the composer banner.")
                }
            }
            .navigationTitle("Scheduled messages")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .refreshable { await load() }
            .task { await load() }
        }
        .presentationDetents([.medium, .large])
    }

    private func load() async {
        items = (try? await session.api.scheduledMessages(conversationId: conversationId)) ?? []
        loading = false
    }

    private func cancel(_ item: WireScheduledItem) {
        Task {
            do {
                try await session.api.cancelScheduled(item.id)
                withAnimation { items.removeAll { $0.id == item.id } }
                PulseHaptics.tap()
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }
}

/// F-MS-08 — the web's EXACT 24-emoji picker grid (EMOJI_PICKER_CHOICES,
/// pulse-utils.ts:146-150) used as the extended reaction picker.
struct ReactionPickerSheet: View {
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    static let choices = [
        "😀", "😂", "🥹", "😍", "😎", "🤔", "😴", "🥳",
        "👍", "🙏", "👏", "🔥", "❤️", "💜", "✨", "🎉",
        "🚀", "🌈", "☀️", "🌙", "☕", "🍕", "🎂", "⚽",
    ]

    var body: some View {
        VStack(spacing: 12) {
            Capsule()
                .fill(Color.secondary.opacity(0.4))
                .frame(width: 36, height: 4)
                .padding(.top, 8)
            Text("React")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 8), spacing: 10) {
                ForEach(Self.choices, id: \.self) { emoji in
                    Button {
                        PulseHaptics.tap()
                        onPick(emoji)
                        dismiss()
                    } label: {
                        Text(emoji)
                            .font(.system(size: 26))
                            .frame(width: 40, height: 40)
                            .background(Circle().fill(Color.secondary.opacity(0.08)))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("React with \(emoji)")
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 18)
        }
        .presentationDetents([.height(240)])
    }
}

/// F-MS-08 — who-reacted drawer: per-member list for one emoji group +
/// the toggle action (web reactionInfo drawer parity).
struct WhoReactedSheet: View {
    let message: WireChatMessage
    let emoji: String
    let members: [WireConversationMember]
    let viewerId: String?
    @ObservedObject var session: PulseSession
    var onClosed: () -> Void = {}

    @Environment(\.dismiss) private var dismiss

    private var group: WireReactionGroup? {
        (message.reactions ?? []).first { $0.emoji == emoji }
    }
    private var iReacted: Bool {
        group?.userIds.contains(viewerId ?? "") == true
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 6) {
                Text(emoji).font(.title3)
                Text(group?.count == 1 ? "1 reaction" : "\(group?.count ?? 0) reactions")
                    .font(.subheadline.weight(.bold))
            }
            .padding(.top, 14)
            List {
                ForEach(group?.userIds ?? [], id: \.self) { userId in
                    let member = members.first { $0.id == userId }
                    HStack(spacing: 12) {
                        RowAvatar(name: member?.name ?? "Unknown", colorName: member?.color, photoPath: member?.avatar, size: 34)
                        Text(member?.name ?? "Unknown")
                            .font(.subheadline.weight(.medium))
                        if userId == viewerId {
                            Text("(you)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 1)
                }
            }
            .listStyle(.plain)
            Button {
                PulseHaptics.tap()
                if let viewerId {
                    Task {
                        do {
                            _ = try await session.api.react(messageId: message.id, emoji: emoji)
                        } catch {
                            session.toasts.show(RoomViewModel.describe(error))
                        }
                    }
                }
                dismiss()
            } label: {
                HStack(spacing: 6) {
                    Text(emoji)
                    Text(iReacted ? "Remove your reaction" : "React \(emoji)")
                        .font(.subheadline.weight(.bold))
                }
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(RoundedRectangle(cornerRadius: 14).fill(PulseTheme.emerald))
                .foregroundStyle(.white)
            }
            .buttonStyle(PulseButtonStyle())
            .disabled(viewerId == nil)
            .padding(.horizontal, 14)
            .padding(.bottom, 14)
        }
        .presentationDetents([.medium, .large])
        .onDisappear { onClosed() }
    }
}

/// F-MS-24 — the web sticker-picker packs (verbatim 5 × 10). Picking posts
/// kind "sticker" with payload { emoji, pack } (chat-room parseSticker shape).
struct StickerPickerSheet: View {
    let onPick: (_ emoji: String, _ pack: String) -> Void

    @Environment(\.dismiss) private var dismiss
    @AppStorage("pulse.sticker-recents.v1") private var recentsRaw = ""
    @State private var packIndex = 0

    var body: some View {
        VStack(spacing: 10) {
            Capsule()
                .fill(Color.secondary.opacity(0.4))
                .frame(width: 36, height: 4)
                .padding(.top, 8)
            if !recents.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("RECENT")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 12)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(recents, id: \.emoji) { pick in
                                tile(pick.emoji, pack: pick.pack, size: 44, fontSize: 22)
                            }
                        }
                        .padding(.horizontal, 12)
                    }
                }
            }
            Picker("Pack", selection: $packIndex) {
                ForEach(Array(PulseRemediationLogic.stickerPacks.enumerated()), id: \.offset) { index, pack in
                    Text(pack.badge).tag(index)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 12)
            let active = PulseRemediationLogic.stickerPacks[packIndex]
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                    ForEach(active.items, id: \.self) { emoji in
                        tile(emoji, pack: active.name, size: 74, fontSize: 38)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 16)
            }
            Text("\(active.name) pack · tap to send")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .presentationDetents([.medium, .large])
    }

    struct RecentPick: Codable, Equatable {
        let emoji: String
        let pack: String
    }

    private var recents: [RecentPick] {
        guard let data = recentsRaw.data(using: .utf8),
              let picks = try? JSONDecoder().decode([RecentPick].self, from: data) else { return [] }
        return Array(picks.prefix(12))
    }

    private func remember(_ emoji: String, pack: String) {
        var picks = recents.filter { $0.emoji != emoji || $0.pack != pack }
        picks.insert(RecentPick(emoji: emoji, pack: pack), at: 0)
        if picks.count > 12 { picks = Array(picks.prefix(12)) }
        recentsRaw = String(data: (try? JSONEncoder().encode(picks)) ?? Data(), encoding: .utf8) ?? ""
    }

    private func tile(_ emoji: String, pack: String, size: CGFloat, fontSize: CGFloat) -> some View {
        Button {
            PulseHaptics.tap()
            remember(emoji, pack: pack)
            onPick(emoji, pack)
        } label: {
            Text(emoji)
                .font(.system(size: fontSize))
                .frame(width: size, height: size)
                .background(
                    RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                        .fill(PulseTheme.emerald.opacity(0.10)),
                )
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("Send \(emoji) sticker from \(pack)")
    }
}

/// F-MS-22 — the '/'-triggered command palette (web slash-palette.tsx
/// PULSE_SLASH_COMMANDS verbatim, fuzzy prefix filter). Rendered right
/// above the composer whenever the draft starts with '/'.
struct SlashPaletteView: View {
    let draft: String
    let onPick: (PulseRemediationLogic.SlashCommand) -> Void

    private var candidates: [PulseRemediationLogic.SlashCommand] {
        let token = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard token.hasPrefix("/"), token.count >= 2 else { return [] }
        let needle = token.lowercased()
        let visible = PulseRemediationLogic.slashCommands.filter {
            $0.cmd.lowercased().hasPrefix(needle)
                || $0.cmd.dropFirst().lowercased().contains(String(needle.dropFirst()))
        }
        return Array(visible.prefix(6))
    }

    var body: some View {
        if !candidates.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(candidates.enumerated()), id: \.element.id) { index, command in
                    Button {
                        PulseHaptics.tap()
                        onPick(command)
                    } label: {
                        HStack(spacing: 10) {
                            Text(command.cmd)
                                .font(.system(size: 13.5, weight: .bold, design: .monospaced))
                                .foregroundStyle(PulseTheme.emerald)
                            if !command.args.isEmpty {
                                Text(command.args)
                                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(command.help)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .background(index == 0 ? PulseTheme.emerald500.opacity(0.10) : Color.clear)
                    .accessibilityLabel("\(command.cmd) — \(command.help)")
                }
            }
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.regularMaterial))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
            .shadow(color: .black.opacity(0.10), radius: 12, y: 4)
            .padding(.horizontal, 12)
            .padding(.bottom, 4)
        }
    }
}
