import SwiftUI
import CoreLocation

// ─────────────────────────────────────────────────────────────
// REM-B P2/P3 — messaging-flow surfaces (web parity, native sheets):
//   • ScheduleSheet         — F-MS-18 delayed send (30 s – 30 d window)
//   • ScheduledManagerSheet — F-MS-18 list + cancel pending rows
//   • ReactionPickerSheet   — F-MS-08 the web's exact 24-emoji picker grid
//   • WhoReactedSheet       — F-MS-08 long-press chip → who-reacted list
//   • StickerPickerSheet    — F-MS-24 the web's 5 packs (kind "sticker")
//   • SlashPaletteView      — F-MS-22 '/'-trigger palette above the composer
// R1-W2B additions:
//   • LocationShareSheet    — F-MD-07 CoreLocation fix → kind "location" pin
//   • QuickPhrasesSheet     — F-MS-29 quick-phrase CRUD (rail manage surface)
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
/// PULSE_SLASH_COMMANDS verbatim, fuzzy filter + tap to run — R3-A wires it
/// into the live composer: draft starts with '/' → palette; pick routes the
/// outcome machine). Rendered right above the composer.
struct SlashPaletteView: View {
    let draft: String
    let onPick: (PulseRemediationLogic.SlashCommand) -> Void

    /// Web SlashPalette.matches parity: fuzzyMatch over "cmd args help",
    /// ordered as the command table itself (no re-ranking), every match
    /// rendered inside a bounded scroll (web max-h-64 ≈ 256pt).
    private var candidates: [PulseRemediationLogic.SlashCommand] {
        let token = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard token.hasPrefix("/"), token.count >= 2 else { return [] }
        return PulseRemediationLogic.slashCommands.filter { command in
            let haystack = "\(command.cmd) \(command.args) \(command.help)"
            return PulseRemediationLogic.slashFuzzyMatch(haystack: haystack, needle: token)
        }
    }

    var body: some View {
        if !candidates.isEmpty {
            VStack(spacing: 0) {
                ScrollView {
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
                }
                .frame(maxHeight: 256)
                Text("tap a command to run it · clear the draft to dismiss")
                    .font(.system(size: 9.5, weight: .medium))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .overlay(alignment: .top) {
                        Rectangle().fill(PulseTheme.hairlineSoft).frame(height: 1)
                    }
            }
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.regularMaterial))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
            .shadow(color: .black.opacity(0.10), radius: 12, y: 4)
            .padding(.horizontal, 12)
            .padding(.bottom, 4)
            .accessibilityLabel("Slash commands")
        }
    }
}

// MARK: - R1-W2B F-MD-07 — location share

/// CoreLocation one-shot fix provider. CLLocationManager guarantees its
/// delegate callbacks on the main run loop, so plain @Published writes are
/// safe without extra isolation plumbing.
final class LocationFixModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    enum Status: Equatable {
        case idle, locating, ready, denied, failed
    }

    @Published var status: Status = .idle
    @Published var lat: Double?
    @Published var lng: Double?

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// Ask for a fix — triggers the when-in-use TCC prompt on first use,
    /// then a single high-level location reading (no continuous tracking —
    /// a static pin needs no background location, spec F-MD-07 PERMS).
    func requestFix() {
        status = .locating
        switch manager.authorizationStatus {
        case .denied, .restricted:
            status = .denied
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        default:
            manager.requestLocation()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .denied, .restricted:
            status = .denied
        case .notDetermined:
            break
        default:
            if status == .locating {
                manager.requestLocation()
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last else { return }
        lat = fix.coordinate.latitude
        lng = fix.coordinate.longitude
        status = .ready
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code == CLError.Code.denied {
            status = .denied
        } else {
            status = .failed
        }
    }
}

/// F-MD-07 — the location confirm sheet (web location-share.tsx flow:
/// locate → coords + label → REAL kind:'location' message with payload
/// { lat, lng, label } — chat-room.tsx sendLocation L3120-3138).
struct LocationShareSheet: View {
    /// Called with the confirmed pin once the sheet dismisses (the room then
    /// POSTs the message — the sheet owns NO transport).
    var onConfirm: (Double, Double, String) -> Void = { _, _, _ in }

    @Environment(\.dismiss) private var dismiss
    @StateObject private var fix = LocationFixModel()
    @State private var label = ""

    private static let defaultLabel = "Current location"

    var body: some View {
        NavigationStack {
            Form {
                Section("Pin") {
                    switch fix.status {
                    case .idle:
                        Button {
                            fix.requestFix()
                        } label: {
                            Label("Locate me", systemImage: "location.fill")
                        }
                    case .locating:
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text("Finding your location…").foregroundStyle(.secondary)
                        }
                    case .ready:
                        if let lat = fix.lat, let lng = fix.lng {
                            VStack(alignment: .leading, spacing: 4) {
                                Label(PulseRemediationLogic.coordinateText(lat: lat, lng: lng), systemImage: "location.fill")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(PulseTheme.emerald)
                                Button("Refresh fix") { fix.requestFix() }
                                    .font(.caption)
                            }
                        }
                    case .denied:
                        Label("Location access is off — enable it in Settings to share a pin", systemImage: "location.slash")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    case .failed:
                        VStack(alignment: .leading, spacing: 4) {
                            Label("Couldn't get a fix — try again", systemImage: "location.slash")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Button("Retry") { fix.requestFix() }
                                .font(.caption.weight(.semibold))
                        }
                    }
                }
                Section("Label") {
                    TextField("Label", text: $label, prompt: Text(Self.defaultLabel))
                        .textInputAutocapitalization(.sentences)
                }
                if fix.status == .ready, fix.lat != nil, fix.lng != nil {
                    Section {
                        Button {
                            dismiss()
                            onConfirm(fix.lat ?? 0, fix.lng ?? 0, resolvedLabel)
                        } label: {
                            Text("Send location")
                                .frame(maxWidth: .infinity)
                                .font(.subheadline.weight(.bold))
                        }
                        .disabled(fix.status != .ready)
                    } footer: {
                        Text("Sends a map pin to this chat. Your coordinates ride the message.")
                    }
                }
            }
            .navigationTitle("Share location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear {
                if fix.status == .idle { fix.requestFix() }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private var resolvedLabel: String {
        label.trimmingCharacters(in: .whitespaces).isEmpty ? Self.defaultLabel : label
    }
}

// MARK: - R1-W2B F-MS-29 — quick phrases manager

/// F-MS-29 — quick-phrase CRUD (GET/POST/DELETE /api/users/{id}/phrases,
/// phrases/route.ts: 1-120 chars, ≤12 rows, position asc). The composer rail
/// renders the same list; this sheet manages it.
struct QuickPhrasesSheet: View {
    @ObservedObject var session: PulseSession
    /// Fired after every mutation so the composer rail re-fetches.
    var onChanged: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @State private var phrases: [WireQuickPhrase] = []
    @State private var draft = ""
    @State private var loading = true

    private static let maxPhrases = 12
    private static let maxChars = 120

    private var canAdd: Bool {
        !draft.trimmingCharacters(in: .whitespaces).isEmpty && phrases.count < Self.maxPhrases
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(phrases) { phrase in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(phrase.text)
                                .font(.subheadline)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                delete(phrase)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                    if loading && phrases.isEmpty {
                        ProgressView().frame(maxWidth: .infinity).padding(.vertical, 16)
                    }
                } header: {
                    Text("Quick phrases")
                } footer: {
                    Text("One-tap lines above the composer. Up to \(Self.maxPhrases) phrases, \(Self.maxChars) characters each.")
                }
                Section("Add a phrase") {
                    TextField("Type a phrase…", text: $draft, axis: .vertical)
                        .lineLimit(1...3)
                        .textInputAutocapitalization(.sentences)
                    Button {
                        add()
                    } label: {
                        Label("Add phrase", systemImage: "plus.circle.fill")
                    }
                    .disabled(!canAdd)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Quick phrases")
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
        phrases = (try? await session.api.quickPhrases()) ?? []
        loading = false
        onChanged()
    }

    private func add() {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, text.count <= Self.maxChars, phrases.count < Self.maxPhrases else { return }
        Task {
            do {
                _ = try await session.api.createQuickPhrase(text: text)
                draft = ""
                PulseHaptics.success()
                await load()
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    private func delete(_ phrase: WireQuickPhrase) {
        Task {
            do {
                try await session.api.deleteQuickPhrase(phrase.id)
                PulseHaptics.tap()
                await load()
            } catch {
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }
}
