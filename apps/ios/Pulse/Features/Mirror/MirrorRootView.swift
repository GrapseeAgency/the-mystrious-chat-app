import SwiftUI

// R59 - NATIVE MIRROR (iOS SwiftUI). The web artboard - home, profile, dock,
// room bubble language - redrawn natively with the EXACT palette and geometry
// the web renders (globals.css R54 ARTBOARD EMBER + reference measurements).
// Data rides the REAL PulseAPIClient (gateway REST), zero mock rows.
// Gated by the "ui.mirrorAudit" UserDefaults flag (Settings toggle) so the
// shipping shell stays untouched.

enum MirrorArt {
    static let bg = Color(red: 0x0D/255.0, green: 0x09/255.0, blue: 0x06/255.0)
    static let sceneTop = Color(red: 0x2B/255.0, green: 0x1C/255.0, blue: 0x10/255.0)
    static let sceneMid = Color(red: 0x15/255.0, green: 0x0D/255.0, blue: 0x07/255.0)
    static let text = Color(red: 0xF5/255.0, green: 0xEF/255.0, blue: 0xE8/255.0)
    static let textSoft = Color(red: 0xCB/255.0, green: 0xC0/255.0, blue: 0xB4/255.0)
    static let dim = Color(red: 0x9B/255.0, green: 0x8C/255.0, blue: 0x7B/255.0)
    static let faint = Color(red: 0x7A/255.0, green: 0x6D/255.0, blue: 0x5D/255.0)
    static let hairline = Color.white.opacity(0.08)
    static let chip = Color.white.opacity(0.06)
    static let chipActive = Color.white.opacity(0.16)
    static let panel = Color(red: 24/255.0, green: 18/255.0, blue: 13/255.0).opacity(0.66)
    static let bubbleOut = Color(red: 0xF2/255.0, green: 0xEB/255.0, blue: 0xDF/255.0)
    static let onBubbleOut = Color(red: 0x20/255.0, green: 0x15/255.0, blue: 0x0C/255.0)
    static let bubbleIn = Color(red: 0x29/255.0, green: 0x1F/255.0, blue: 0x16/255.0)
    static let inkSoft = Color(red: 0x20/255.0, green: 0x15/255.0, blue: 0x0C/255.0).opacity(0.66)
    static let inkFaint = Color(red: 0x20/255.0, green: 0x15/255.0, blue: 0x0C/255.0).opacity(0.48)
    static let glass7 = Color.white.opacity(0.07)
    static let accent = Color(red: 0xFF/255.0, green: 0x7A/255.0, blue: 0x3D/255.0)
    static let accent2 = Color(red: 0xFF/255.0, green: 0xB8/255.0, blue: 0x6B/255.0)
    static let red = Color(red: 0xFF/255.0, green: 0x45/255.0, blue: 0x3A/255.0)
    static let presenceOnline = Color(red: 0x22/255.0, green: 0xC5/255.0, blue: 0x5E/255.0)
    static let presenceOffline = Color(red: 0x52/255.0, green: 0x52/255.0, blue: 0x5B/255.0)
    static let sealAmber = Color(red: 0xC9/255.0, green: 0x76/255.0, blue: 0x2B/255.0)
    static let gapInk = Color(red: 0x1E/255.0, green: 0x16/255.0, blue: 0x10/255.0)
    static let pillInk = Color(red: 0x24/255.0, green: 0x13/255.0, blue: 0x04/255.0)
    static let ridgeFloor = Color(red: 0x19/255.0, green: 0x10/255.0, blue: 0x09/255.0)
    static let starAmber = Color(red: 0xE5/255.0, green: 0xA3/255.0, blue: 0x3C/255.0)
    static let fabGradient = LinearGradient(colors: [fabTop, accent, fabDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let fabTop = Color(red: 0xF0/255.0, green: 0xA3/255.0, blue: 0x5C/255.0)
    static let fabDeep = Color(red: 0xD9/255.0, green: 0x5F/255.0, blue: 0x22/255.0)
    static let badgeTop = Color(red: 0xF4/255.0, green: 0x3F/255.0, blue: 0x5E/255.0)
    static let badgeDeep = Color(red: 0xEF/255.0, green: 0x44/255.0, blue: 0x44/255.0)

    static func avatarGradient(_ color: String?) -> [Color] {
        switch color {
        case "rose": return [Color(red: 0xFB/255, green: 0x71/255, blue: 0x85/255), Color(red: 0xE1/255, green: 0x1D/255, blue: 0x48/255)]
        case "amber": return [Color(red: 0xFB/255, green: 0xBF/255, blue: 0x24/255), Color(red: 0xD9/255, green: 0x77/255, blue: 0x06/255)]
        case "violet": return [Color(red: 0xA7/255, green: 0x8B/255, blue: 0xFA/255), Color(red: 0x7C/255, green: 0x3A/255, blue: 0xED/255)]
        case "teal": return [Color(red: 0x2D/255, green: 0xD4/255, blue: 0xBF/255), Color(red: 0x0D/255, green: 0x94/255, blue: 0x88/255)]
        case "orange": return [Color(red: 0xFB/255, green: 0x92/255, blue: 0x3C/255), Color(red: 0xEA/255, green: 0x58/255, blue: 0x0C/255)]
        case "pink": return [Color(red: 0xF4/255, green: 0x72/255, blue: 0xB6/255), Color(red: 0xDB/255, green: 0x27/255, blue: 0x77/255)]
        case "cyan": return [Color(red: 0x22/255, green: 0xD3/255, blue: 0xEE/255), Color(red: 0x08/255, green: 0x91/255, blue: 0xB2/255)]
        default: return [Color(red: 0x34/255, green: 0xD3/255, blue: 0x99/255), Color(red: 0x05/255, green: 0x96/255, blue: 0x69/255)]
        }
    }

    static func initials(_ name: String) -> String {
        let parts = name.trimmingCharacters(in: .whitespaces).split(separator: " ").filter { !$0.isEmpty }
        if parts.isEmpty { return "?" }
        if parts.count == 1 { return String(parts[0].prefix(2)).uppercased() }
        return (String(parts[0].prefix(1)) + String(parts[parts.count - 1].prefix(1))).uppercased()
    }
}

/// The /api/users/{id} profile row the mirror renders (tolerant subset).
public struct MirrorProfileRow: Codable, Sendable {
    public let name: String?
    public let username: String?
    public let about: String?
    public let color: String?
    public let coverImage: String?
    public let statusText: String?
    public let createdAt: String?
}

@MainActor
final class MirrorViewModel: ObservableObject {
    @Published var rows: [MirrorRow] = []
    @Published var archivedRows: [MirrorRow] = []
    @Published var profile: MirrorProfileRow?
    @Published var stats: WireUserStats?
    @Published var coins: Int = 0

    var totalUnread: Int { rows.reduce(0) { $0 + $1.unread } }

    struct MirrorRow: Identifiable, Hashable {
        let id: String
        let title: String
        let color: String?
        let isGroup: Bool
        let preview: String
        let time: String
        let unread: Int
        let online: Bool
        let pinned: Bool
        let muted: Bool
        let subtitle: String
        // R64 - web row affordances
        var isArchived: Bool = false
        var manualUnread: Bool = false
        var streak: Int = 0
        var draft: String? = nil
    }

    private var timer: Timer?
    private var client: PulseAPIClient?


    /// One conversation summary -> the home row (web chats-row mapping).
    static func buildRow(summary: WireConversationSummary, viewerId: String) -> MirrorRow {
        let other = summary.members.first { $0.id != viewerId }
        let last = summary.lastMessage
        let deleted = last?.deletedAt != nil
        let raw: String
        if deleted {
            raw = "Message deleted"
        } else if last?.imagePath != nil {
            raw = "Photo"
        } else {
            raw = last?.content ?? "No messages yet"
        }
        let mine = last?.senderId == viewerId
        let authorPrefix: String
        if mine {
            authorPrefix = "You: "
        } else if summary.isGroup {
            authorPrefix = (last?.sender?.name ?? "") + ": "
        } else {
            authorPrefix = ""
        }
        return MirrorRow(
            id: summary.id,
            title: summary.name ?? (other?.name ?? "Chat"),
            color: other?.color,
            isGroup: summary.isGroup,
            preview: authorPrefix + raw,
            time: MirrorRowTime.short(summary.updatedAt ?? last?.createdAt),
            unread: summary.unreadCount ?? 0,
            online: false,
            pinned: summary.pinnedAt != nil,
            muted: summary.mutedUntil != nil,
            subtitle: summary.isGroup
                ? summary.members.map { $0.name }.filter { !$0.isEmpty }.joined(separator: ", ")
                : "",
            isArchived: summary.isArchived,
            manualUnread: summary.manualUnread,
            streak: summary.myStreak?.count ?? 0,
            draft: (summary.myDraft ?? "").isEmpty ? nil : summary.myDraft
        )
    }

    func start(session: PulseSession) {
        client = session.api
        timer?.invalidate()
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    func stop() { timer?.invalidate(); timer = nil }

    func refresh() {
        guard let client else { return }
        Task { @MainActor in
            // rows - real summaries, same mapping the web home uses;
            // R64 web parity: the main list EXCLUDES archived chats
            if let page = try? await client.conversations() {
                let built = page.map { MirrorViewModel.buildRow(summary: $0, viewerId: client.userId) }
                rows = built.filter { !$0.isArchived }
                archivedRows = built.filter { $0.isArchived }
            }
            // profile row (tolerant subset), stats, coins
            if let url = URL(string: PulseEndpoints.gatewayURL.absoluteString + "/api/users/" + client.userId) {
                var req = URLRequest(url: url)
                req.timeoutInterval = 8
                if let token = client.authToken {
                    req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
                }
                if let (data, _) = try? await URLSession.shared.data(for: req) {
                    let root = try? JSONDecoder().decode(MirrorEnvelope<MirrorProfileRow>.self, from: data)
                    if let unwrapped = root?.user {
                        profile = unwrapped
                    }
                }
            }
            if let s = try? await client.userStats(client.userId) { stats = s }
            if let w = try? await client.wallet() { coins = w.coins ?? 0 }
        }
    }
}

/// {user: {...}} envelope unwrap - the users routes return both shapes.
private struct MirrorEnvelope<T: Codable>: Codable {
    let user: T?
}

enum MirrorRowTime {
    static func short(_ iso: String?) -> String {
        guard let iso, let date = MirrorISO.parse(iso) else { return "" }
        let cal = Calendar.current
        if cal.isDateInToday(date) { return MirrorISO.hhmm.string(from: date) }
        if cal.isDateInYesterday(date) { return "Yesterday" }
        return MirrorISO.day.string(from: date)
    }
}

enum MirrorISO {
    static func parse(_ iso: String?) -> Date? {
        guard let iso else { return nil }
        if let d = ISO8601DateFormatter().date(from: iso) { return d }
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
        return fmt.date(from: iso)
    }
    static let hhmm: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f
    }()
    static let day: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "MMM d"; return f
    }()
}

struct MirrorRootView: View {
    @ObservedObject var session: PulseSession
    @StateObject private var model = MirrorViewModel()
    @State private var tab: Int = 0
    @State private var openRow: MirrorViewModel.MirrorRow?

    var body: some View {
        ZStack {
            LinearGradient(colors: [MirrorArt.sceneTop, MirrorArt.sceneMid, MirrorArt.bg], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            if let row = openRow {
                MirrorRoomView(row: row, session: session, onBack: { openRow = nil })
            } else {
                VStack(spacing: 0) {
                    switch tab {
                    case 0: MirrorHomeView(model: model, session: session, onOpen: { openRow = $0 })
                    case 3: MirrorProfileView(model: model, session: session)
                    default: MirrorLaterView(title: tab == 1 ? "Calls" : "Updates")
                    }
                    Spacer(minLength: 0)
                }
                VStack {
                    Spacer()
                    MirrorDock(tab: $tab, unread: model.totalUnread, onFab: {})
                }
            }
        }
        .onAppear { model.start(session: session) }
        .onDisappear { model.stop() }
    }
}

// MARK: - Home

struct MirrorHomeView: View {
    @ObservedObject var model: MirrorViewModel
    @ObservedObject var session: PulseSession
    let onOpen: (MirrorViewModel.MirrorRow) -> Void

    // R64 - live filters (web rails), search, kebab menu, row long-press
    @State private var chip: Int = 0
    @State private var searching = false
    @State private var query = ""
    @State private var kebabOpen = false
    @State private var optionsRow: MirrorViewModel.MirrorRow?
    @State private var activeSheet: MirrorHomeSheet?

    private var visibleRows: [MirrorViewModel.MirrorRow] {
        let base: [MirrorViewModel.MirrorRow]
        switch chip {
        case 1: base = model.rows.filter { $0.unread > 0 }
        case 2: base = model.rows.filter { $0.isGroup }
        default: base = model.rows
        }
        if query.trimmingCharacters(in: .whitespaces).isEmpty { return base }
        let q = query.lowercased()
        return base.filter { $0.title.lowercased().contains(q) || $0.preview.lowercased().contains(q) }
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text("Chats")
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(MirrorArt.text)
                        Spacer()
                        Button {
                            withAnimation { searching = true }
                        } label: {
                            Image(systemName: "magnifyingglass").font(.system(size: 20)).foregroundColor(MirrorArt.text)
                                .frame(width: 44, height: 44)
                        }
                        Image(systemName: "camera").font(.system(size: 20)).foregroundColor(MirrorArt.text)
                            .frame(width: 44, height: 44)
                        Button {
                            kebabOpen = true
                        } label: {
                            Image(systemName: "ellipsis").font(.system(size: 20)).foregroundColor(MirrorArt.text)
                                .frame(width: 44, height: 44)
                        }
                    }
                    .padding(.horizontal, 12).padding(.top, 10).padding(.bottom, 6)

                    if searching {
                        HStack(spacing: 10) {
                            HStack(spacing: 10) {
                                Image(systemName: "magnifyingglass").font(.system(size: 14)).foregroundColor(MirrorArt.faint)
                                TextField("Search chats and messages…", text: $query)
                                    .font(.system(size: 14)).foregroundColor(MirrorArt.text)
                                    .autocorrectionDisabled()
                                if !query.isEmpty {
                                    Button {
                                        query = ""
                                    } label: {
                                        Image(systemName: "xmark.circle.fill").font(.system(size: 14)).foregroundColor(MirrorArt.dim)
                                    }
                                }
                            }
                            .padding(.horizontal, 16).frame(height: 40)
                            .background(MirrorArt.glass7)
                            .overlay(Capsule().strokeBorder(MirrorArt.hairline, lineWidth: 1))
                            .clipShape(Capsule())
                            Button {
                                searching = false
                                query = ""
                            } label: {
                                Image(systemName: "xmark").font(.system(size: 18)).foregroundColor(MirrorArt.dim)
                                    .frame(width: 40, height: 40)
                            }
                        }
                        .padding(.horizontal, 12).padding(.bottom, 8)
                    }

                    HStack(spacing: 6) {
                        MirrorChipText(label: "All", active: chip == 0) { chip = 0 }
                        MirrorChipCount(label: "Unread", count: model.totalUnread, active: chip == 1) { chip = 1 }
                        MirrorChipText(label: "Groups", active: chip == 2) { chip = 2 }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 6)

                    ForEach(visibleRows) { row in
                        MirrorRowView(row: row)
                            .contentShape(Rectangle())
                            .onTapGesture { onOpen(row) }
                            .onLongPressGesture(minimumDuration: 0.45) { optionsRow = row }
                    }
                    if visibleRows.isEmpty {
                        VStack(spacing: 4) {
                            Text(query.isEmpty ? "No chats here yet" : "No matches")
                                .font(.system(size: 14, weight: .medium)).foregroundColor(MirrorArt.dim)
                            Text(query.isEmpty ? "Your next great chat is one tap away. Find someone and break the ice."
                                 : "Try a different word or check the spelling.")
                                .font(.system(size: 12)).foregroundColor(MirrorArt.faint)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 48)
                    }
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }

            if kebabOpen {
                MirrorHomeKebab(
                    archivedCount: model.archivedRows.count,
                    onDismiss: { kebabOpen = false },
                    onSearch: { kebabOpen = false; withAnimation { searching = true } },
                    onNewChat: { kebabOpen = false; activeSheet = .newChat },
                    onNewGroup: { kebabOpen = false; activeSheet = .newGroup },
                    onContacts: { kebabOpen = false; activeSheet = .contacts },
                    onArchived: { kebabOpen = false; activeSheet = .archived }
                )
            }
        }
        .sheet(item: $optionsRow) { row in
            MirrorRowOptions(row: row, session: session, onDone: { model.refresh() })
        }
        .sheet(item: $activeSheet) { sheet in
            MirrorHomeSheetView(sheet: sheet, model: model, session: session, onOpenRow: { row in
                activeSheet = nil
                onOpen(row)
            })
        }
    }
}

/// R64 - the home kebab rows the mirror makes REAL natively (no dead taps).
struct MirrorHomeKebab: View {
    let archivedCount: Int
    let onDismiss: () -> Void
    let onSearch: () -> Void
    let onNewChat: () -> Void
    let onNewGroup: () -> Void
    let onContacts: () -> Void
    let onArchived: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.001)
                .contentShape(Rectangle())
                .onTapGesture { onDismiss() }
            VStack(alignment: .leading, spacing: 0) {
                MirrorKebabRow(icon: "magnifyingglass", label: "Search", action: onSearch)
                MirrorKebabRow(icon: "bubble.left", label: "New chat", action: onNewChat)
                MirrorKebabRow(icon: "person.2", label: "New group", action: onNewGroup)
                MirrorKebabRow(icon: "book", label: "Contacts", action: onContacts)
                MirrorKebabRow(icon: "archivebox", label: "Archived", trailing: "\(archivedCount)", action: onArchived)
            }
            .frame(width: 240)
            .background(Color(red: 0x1C/255.0, green: 0x16/255.0, blue: 0x10/255.0).opacity(0.95))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(MirrorArt.hairline, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .padding(.trailing, 12)
            .padding(.top, 60)
            .shadow(color: .black.opacity(0.5), radius: 18, y: 8)
        }
    }
}

struct MirrorKebabRow: View {
    let icon: String
    let label: String
    var trailing: String = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 15)).foregroundColor(MirrorArt.dim).frame(width: 20)
                Text(label).font(.system(size: 13.5, weight: .medium)).foregroundColor(MirrorArt.text)
                Spacer(minLength: 0)
                if !trailing.isEmpty {
                    Text(trailing).font(.system(size: 11, weight: .semibold)).foregroundColor(MirrorArt.faint)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Sheet destinations behind the home kebab (all gateway-backed).
enum MirrorHomeSheet: String, Identifiable {
    case newChat, newGroup, contacts, archived
    var id: String { rawValue }
}

struct MirrorHomeSheetView: View {
    let sheet: MirrorHomeSheet
    @ObservedObject var model: MirrorViewModel
    @ObservedObject var session: PulseSession
    let onOpenRow: (MirrorViewModel.MirrorRow) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var users: [WireUser] = []
    @State private var query = ""
    @State private var groupName = ""
    @State private var picked: Set<String> = []
    @State private var busy = false

    private var filteredUsers: [WireUser] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return users }
        return users.filter { $0.name.lowercased().contains(q) }
    }

    var body: some View {
        MirrorPanel(title: panelTitle) {
            if sheet == .newGroup {
                TextField("Group name", text: $groupName)
                    .font(.system(size: 14)).foregroundColor(MirrorArt.text)
                    .padding(.horizontal, 16).frame(height: 40)
                    .background(MirrorArt.glass7)
                    .overlay(Capsule().strokeBorder(MirrorArt.hairline, lineWidth: 1))
                    .clipShape(Capsule())
                    .padding(.bottom, 8)
            }
            if sheet == .archived {
                MirrorPanelScroll {
                    if model.archivedRows.isEmpty {
                        MirrorPanelHint("Nothing archived")
                    }
                    ForEach(model.archivedRows) { row in
                        MirrorPanelUserRow(
                            name: row.title,
                            color: row.color,
                            isGroup: row.isGroup,
                            id: row.id,
                            subtitle: row.preview,
                            trailingIcon: "arrow.up.bin",
                            onTap: { onOpenRow(row) }
                        )
                    }
                }
            } else {
                MirrorPanelSearch(query: $query)
                MirrorPanelScroll {
                    ForEach(filteredUsers) { user in
                        if sheet == .newGroup {
                            MirrorPanelUserRow(
                                name: user.name,
                                color: user.color,
                                isGroup: false,
                                id: user.id,
                                subtitle: "",
                                checked: picked.contains(user.id),
                                onTap: {
                                    if picked.contains(user.id) { picked.remove(user.id) } else { picked.insert(user.id) }
                                }
                            )
                        } else {
                            MirrorPanelUserRow(
                                name: user.name,
                                color: user.color,
                                isGroup: false,
                                id: user.id,
                                subtitle: user.about ?? "",
                                onTap: { openOrCreateDM(with: user) }
                            )
                        }
                    }
                }
                if sheet == .newGroup {
                    Button {
                        createGroup()
                    } label: {
                        Text(busy ? "Creating…" : "Create group")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(canCreateGroup ? .white : MirrorArt.faint)
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .background(canCreateGroup ? MirrorArt.accent : MirrorArt.glass7)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(!canCreateGroup)
                }
            }
        }
        .task {
            users = (try? await session.api.users()) ?? []
        }
    }

    private var panelTitle: String {
        switch sheet {
        case .newChat: return "New chat"
        case .newGroup: return "New group"
        case .contacts: return "Contacts"
        case .archived: return "Archived"
        }
    }

    private var canCreateGroup: Bool {
        !groupName.trimmingCharacters(in: .whitespaces).isEmpty && picked.count >= 2 && !busy
    }

    private func openOrCreateDM(with user: WireUser) {
        guard !busy else { return }
        busy = true
        Task {
            if let existing = model.rows.first(where: { !$0.isGroup && $0.title == user.name }) {
                onOpenRow(existing)
            } else {
                _ = try? await session.api.createConversation(memberIds: [user.id], isGroup: false)
                model.refresh()
                if let created = model.rows.first(where: { !$0.isGroup && $0.title == user.name }) {
                    onOpenRow(created)
                } else {
                    onDismissSheet()
                }
            }
        }
    }

    private func createGroup() {
        busy = true
        Task {
            _ = try? await session.api.createConversation(
                memberIds: Array(picked), isGroup: true,
                name: groupName.trimmingCharacters(in: .whitespaces)
            )
            model.refresh()
            onDismissSheet()
        }
    }

    private func onDismissSheet() { busy = false; dismiss() }
}

/// Row long-press options - the web ChatOptionsSheet rows, real API calls.
struct MirrorRowOptions: View {
    let row: MirrorViewModel.MirrorRow
    @ObservedObject var session: PulseSession
    let onDone: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var muteStrip = false
    @State private var note = ""

    var body: some View {
        MirrorPanel(title: row.title) {
            if muteStrip {
                MirrorPanelHint("Mute for")
                HStack(spacing: 6) {
                    ForEach(["8h", "1w", "Always"], id: \.self) { preset in
                        Button {
                            mute(until: preset == "8h" ? "8h" : preset == "1w" ? "1w" : "always")
                        } label: {
                            Text(preset)
                                .font(.system(size: 12, weight: .semibold)).foregroundColor(MirrorArt.textSoft)
                                .frame(maxWidth: .infinity).frame(height: 36)
                                .background(MirrorArt.glass7)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                MirrorPanelOption(icon: row.pinned ? "pin.slash" : "pin", label: row.pinned ? "Unpin from top" : "Pin to top") {
                    Task { try? await session.api.togglePin(conversationId: row.id); finish() }
                }
                MirrorPanelOption(icon: "archivebox", label: "Archive chat") {
                    Task { try? await session.api.archive(conversationId: row.id, archived: true); finish() }
                }
                MirrorPanelOption(icon: row.manualUnread ? "envelope.open" : "envelope", label: row.manualUnread ? "Mark as read" : "Mark as unread") {
                    Task { try? await session.api.markUnread(conversationId: row.id, on: !row.manualUnread); finish() }
                }
                MirrorPanelOption(icon: "bell.slash", label: "Mute notifications") { muteStrip = true }
                if !note.isEmpty {
                    MirrorPanelHint(note)
                }
            }
        }
    }

    private func finish() { onDone(); dismiss() }

    private func mute(until: String?) {
        Task {
            try? await session.api.setMuted(conversationId: row.id, until: until)
            finish()
        }
    }
}

/// Shared dark artboard panel chrome for every mirror sheet.
struct MirrorPanel<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(title).font(.system(size: 18, weight: .bold)).foregroundColor(MirrorArt.text)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark").font(.system(size: 14)).foregroundColor(MirrorArt.dim)
                        .frame(width: 32, height: 32)
                        .background(MirrorArt.glass7)
                        .clipShape(Circle())
                }
            }
            .padding(.bottom, 12)
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 0x1C/255.0, green: 0x16/255.0, blue: 0x10/255.0))
        .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(MirrorArt.hairline, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }
}

struct MirrorPanelScroll<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 0) { content } }
            .frame(maxHeight: 380)
    }
}

struct MirrorPanelSearch: View {
    @Binding var query: String
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").font(.system(size: 14)).foregroundColor(MirrorArt.faint)
            TextField("Search people…", text: $query)
                .font(.system(size: 14)).foregroundColor(MirrorArt.text)
        }
        .padding(.horizontal, 16).frame(height: 40)
        .background(MirrorArt.glass7)
        .overlay(Capsule().strokeBorder(MirrorArt.hairline, lineWidth: 1))
        .clipShape(Capsule())
        .padding(.bottom, 8)
    }
}

struct MirrorPanelHint: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .kerning(1.2)
            .foregroundColor(MirrorArt.faint)
            .padding(.horizontal, 8).padding(.vertical, 6)
    }
}

struct MirrorPanelUserRow: View {
    let name: String
    let color: String?
    let isGroup: Bool
    let id: String
    var subtitle: String = ""
    var trailingIcon: String? = nil
    var checked: Bool? = nil
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 10) {
                MirrorAvatarTile(name: name, color: color, isGroup: isGroup, id: id, online: false, showPresence: false, size: isGroup ? 44 : 38, corner: isGroup ? 14 : 19)
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.system(size: 14, weight: .semibold)).foregroundColor(MirrorArt.text).lineLimit(1)
                    if !subtitle.isEmpty {
                        Text(subtitle).font(.system(size: 11)).foregroundColor(MirrorArt.faint).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if let checked {
                    ZStack {
                        Circle().fill(checked ? MirrorArt.accent : MirrorArt.glass7)
                            .overlay(Circle().strokeBorder(checked ? Color.clear : MirrorArt.hairline, lineWidth: 1))
                        if checked {
                            Image(systemName: "check").font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                        }
                    }
                    .frame(width: 22, height: 22)
                }
                if let trailingIcon {
                    Image(systemName: trailingIcon).font(.system(size: 14)).foregroundColor(MirrorArt.dim)
                }
            }
            .padding(.horizontal, 4).padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct MirrorPanelOption: View {
    let icon: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 15)).foregroundColor(MirrorArt.dim).frame(width: 20)
                Text(label).font(.system(size: 13.5, weight: .medium)).foregroundColor(MirrorArt.text)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12).padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct MirrorChipText: View {
    let label: String
    let active: Bool
    var action: (() -> Void)? = nil
    var body: some View {
        Button {
            action?()
        } label: {
            Text(label)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(active ? MirrorArt.text : MirrorArt.textSoft)
                .padding(.horizontal, 14).padding(.vertical, 5)
                .background(active ? MirrorArt.chipActive : MirrorArt.chip)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct MirrorChipCount: View {
    let label: String
    let count: Int
    var active: Bool = false
    var action: (() -> Void)? = nil
    var body: some View {
        Button {
            action?()
        } label: {
            HStack(spacing: 6) {
                if count > 0 {
                    Text("\(min(count, 99))")
                        .font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                        .frame(width: 18, height: 18).background(MirrorArt.red).clipShape(Capsule())
                }
                Text(label).font(.system(size: 13, weight: .medium)).foregroundColor(active ? MirrorArt.text : MirrorArt.textSoft)
            }
            .padding(.horizontal, 14).padding(.vertical, 5)
            .background(active ? MirrorArt.chipActive : MirrorArt.chip)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/**
 * R64 - web ArtConversationRow anatomy verbatim: L1 name (baseline) with the
 * streak chip + time on the RIGHT (web items-baseline); L2 typing/Draft/
 * preview with the rotated FILLED pin, the BellOff muted chip and the FLAT
 * signal-red 18pt art-badge (the old gradient pill is dead on the web).
 */
struct MirrorRowView: View {
    let row: MirrorViewModel.MirrorRow
    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            MirrorAvatarTile(name: row.title, color: row.color, isGroup: row.isGroup, id: row.id, online: row.online, showPresence: !row.isGroup, size: 50, corner: row.isGroup ? 14 : 25)
            VStack(alignment: .leading, spacing: 2) {
                // L1 - name ... streak chip + time (baseline feel)
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(row.title).font(.system(size: 15, weight: .semibold)).foregroundColor(MirrorArt.text).lineLimit(1)
                    Spacer(minLength: 8)
                    if row.streak > 0 {
                        HStack(spacing: 2) {
                            Image(systemName: "flame").font(.system(size: 9)).foregroundColor(MirrorArt.dim)
                            Text("\(row.streak)").font(.system(size: 10, weight: .bold)).foregroundColor(MirrorArt.dim)
                        }
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(MirrorArt.chip)
                        .overlay(Capsule().strokeBorder(MirrorArt.hairline, lineWidth: 1))
                        .clipShape(Capsule())
                    }
                    Text(row.time)
                        .font(.system(size: 11, weight: (row.unread > 0 || row.manualUnread) ? .semibold : .regular))
                        .foregroundColor((row.unread > 0 || row.manualUnread) ? MirrorArt.textSoft : MirrorArt.faint)
                }
                // L2 - typing | Draft | preview ... pin + muted + flat red badge
                HStack(spacing: 6) {
                    if let draft = row.draft, !draft.trimmingCharacters(in: .whitespaces).isEmpty {
                        Image(systemName: "pencil.line").font(.system(size: 9)).foregroundColor(MirrorArt.accent)
                        Text("Draft:").font(.system(size: 13, weight: .semibold)).foregroundColor(MirrorArt.accent2)
                        Text(draft).font(.system(size: 13).italic()).foregroundColor(MirrorArt.dim).lineLimit(1)
                    } else {
                        Text(row.preview).font(.system(size: 13)).foregroundColor(MirrorArt.dim).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    if row.pinned {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 9))
                            .rotationEffect(.degrees(45))
                            .foregroundColor(MirrorArt.faint)
                    }
                    if row.unread > 0 {
                        Text("\(min(row.unread, 99))")
                            .font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                            .padding(.horizontal, 6).frame(height: 18)
                            .background(MirrorArt.red)
                            .clipShape(Capsule())
                    } else if row.manualUnread {
                        Circle().fill(MirrorArt.red).frame(width: 10, height: 10)
                    }
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
    }
}

private extension Optional where Wrapped == String {
    var isNilOrEmpty: Bool {
        switch self {
        case .some(let v): return v.trimmingCharacters(in: .whitespaces).isEmpty
        case .none: return true
        }
    }
}

struct MirrorAvatarTile: View {
    let name: String
    let color: String?
    let isGroup: Bool
    let id: String
    let online: Bool
    let showPresence: Bool
    let size: CGFloat
    let corner: CGFloat

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            RoundedRectangle(cornerRadius: corner, style: .continuous)
                .fill(LinearGradient(colors: MirrorArt.avatarGradient(color), startPoint: .top, endPoint: .bottom))
                .frame(width: size, height: size)
                .overlay(
                    Text(MirrorArt.initials(name))
                        .font(.system(size: size * 0.34, weight: .semibold)).foregroundColor(.white)
                )
            if showPresence {
                Circle()
                    .fill(online ? MirrorArt.presenceOnline : MirrorArt.presenceOffline)
                    .frame(width: size * 0.28, height: size * 0.28)
                    .overlay(Circle().strokeBorder(MirrorArt.bg, lineWidth: 2))
                    .offset(x: 2, y: 2)
            }
        }
    }
}

// MARK: - Profile

struct MirrorProfileView: View {
    @ObservedObject var model: MirrorViewModel
    @ObservedObject var session: PulseSession

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                MirrorProfileCover()
                MirrorProfileIdentity(model: model, session: session)
            }
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
    }
}

/// Cover band with ridges + corner kebab (reference 1 top).
private struct MirrorProfileCover: View {
    var body: some View {
        ZStack(alignment: .topTrailing) {
            MirrorRidges().frame(height: 132).frame(maxWidth: .infinity)
            Image(systemName: "ellipsis")
                .font(.system(size: 18)).foregroundColor(MirrorArt.text)
                .frame(width: 40, height: 40).background(MirrorArt.chip).clipShape(Circle())
                .padding(12)
        }
    }
}

/// Ringed avatar, name + seal, handle chip, bio, pills, stats, SAVED, ACCOUNT.
private struct MirrorProfileIdentity: View {
    @ObservedObject var model: MirrorViewModel
    @ObservedObject var session: PulseSession

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            MirrorProfileAvatar(model: model, session: session)

            HStack(spacing: 6) {
                Text(model.profile?.name ?? session.viewer?.name ?? "")
                    .font(.system(size: 22, weight: .bold)).foregroundColor(MirrorArt.text)
                Image(systemName: "seal.fill").font(.system(size: 13)).foregroundColor(MirrorArt.sealAmber)
            }
            .padding(.top, 8)

            Text("@" + MirrorHandleText(model: model, session: session))
                .font(.system(size: 13, weight: .semibold)).foregroundColor(MirrorArt.accent2)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(MirrorArt.chip).clipShape(Capsule())
                .padding(.top, 10)

            if let about = model.profile?.about, !about.isEmpty {
                Text(about).font(.system(size: 14)).foregroundColor(MirrorArt.textSoft).padding(.top, 10)
            }

            MirrorProfilePills()

            MirrorStatsCardView(model: model)

            MirrorSectionLabel(text: "SAVED")
            MirrorCard {
                MirrorAccountRow(icon: "star", iconTint: MirrorArt.starAmber, title: "Saved messages", subtitle: "Long-press any message in a chat, then Save", titleTint: MirrorArt.text)
            }

            MirrorSectionLabel(text: "ACCOUNT")
            MirrorCard {
                MirrorAccountRow(icon: "touchid", iconTint: MirrorArt.textSoft, title: "Copy account ID", subtitle: session.viewer?.id ?? "", titleTint: MirrorArt.text)
                Rectangle().fill(MirrorArt.hairline).frame(height: 1)
                MirrorAccountRow(icon: "rectangle.portrait.and.arrow.right", iconTint: MirrorArt.red, title: "Sign out", subtitle: "Return to the welcome screen - nothing is deleted", titleTint: MirrorArt.red)
            }
        }
        .padding(.horizontal, 20)
    }
}

@MainActor
private func MirrorHandleText(model: MirrorViewModel, session: PulseSession) -> String {
    if let handle = model.profile?.username, !handle.isEmpty { return handle }
    if let viewerHandle = session.viewer?.username, !viewerHandle.isEmpty { return viewerHandle }
    return (model.profile?.name ?? "").lowercased()
}

/// The ringed 84dp avatar with presence dot, overlapping the cover.
private struct MirrorProfileAvatar: View {
    @ObservedObject var model: MirrorViewModel
    @ObservedObject var session: PulseSession

    var body: some View {
        let gradient = MirrorArt.avatarGradient(model.profile?.color ?? session.viewer?.color)
        let online = session.onlineUserIds.contains(session.viewer?.id ?? "-")
        return ZStack(alignment: .bottomTrailing) {
            Circle()
                .fill(LinearGradient(colors: gradient, startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 92, height: 92)
                .overlay(Circle().fill(MirrorArt.gapInk).frame(width: 87, height: 87))
                .overlay(
                    Text(verbatim: MirrorArt.initials(model.profile?.name ?? session.viewer?.name ?? "?"))
                        .font(.system(size: 28, weight: .semibold)).foregroundColor(.white)
                )
            Circle()
                .fill(online ? MirrorArt.presenceOnline : MirrorArt.presenceOffline)
                .frame(width: 22, height: 22)
                .overlay(Circle().strokeBorder(MirrorArt.bg, lineWidth: 3))
                .offset(x: 2, y: 2)
        }
        .offset(y: -44)
        .padding(.bottom, -30)
    }
}

/// Edit profile (ember pill, ink text) + Share (glass pill).
private struct MirrorProfilePills: View {
    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "pencil").font(.system(size: 13, weight: .bold)).foregroundColor(MirrorArt.pillInk)
                Text("Edit profile").font(.system(size: 13, weight: .bold)).foregroundColor(MirrorArt.pillInk)
            }
            .padding(.horizontal, 16).frame(height: 40)
            .background(MirrorArt.fabGradient).clipShape(Capsule())
            HStack(spacing: 8) {
                Image(systemName: "dot.radioworld.left.and.right").font(.system(size: 13)).foregroundColor(MirrorArt.text)
                Text("Share").font(.system(size: 13, weight: .bold)).foregroundColor(MirrorArt.text)
            }
            .padding(.horizontal, 16).frame(height: 40)
            .background(MirrorArt.chip).clipShape(Capsule())
        }
        .padding(.top, 14)
    }
}

/// 4 column stats card (messages / rooms / coins / since).
private struct MirrorStatsCardView: View {
    @ObservedObject var model: MirrorViewModel

    var body: some View {
        HStack(spacing: 0) {
            MirrorStatCell(value: "\(model.stats?.messages ?? 0)", label: "MESSAGES", tint: MirrorArt.text)
            MirrorStatCell(value: "\(model.stats?.chats ?? 0)", label: "ROOMS", tint: MirrorArt.text)
            MirrorStatCell(value: "\(model.coins)", label: "COINS", tint: MirrorArt.accent)
            MirrorStatCell(value: MirrorSince.short(model.stats?.joinedAt), label: "SINCE", tint: MirrorArt.text, small: true)
        }
        .padding(.vertical, 16)
        .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(MirrorArt.hairline))
        .background(MirrorArt.panel)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .padding(.top, 16)
    }
}

/// Panel card shell with the hairline border.
private struct MirrorCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(MirrorArt.hairline))
            .background(MirrorArt.panel)
            .clipShape(RoundedRectangle(cornerRadius: 20))
    }
}

struct MirrorRidges: View {
    var body: some View {
        Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(Gradient(colors: [MirrorArt.sceneTop, MirrorArt.ridgeFloor]), startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            var y: CGFloat = 0
            while y < size.height {
                var line = Path()
                line.move(to: CGPoint(x: 0, y: y))
                line.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(line, with: .color(.white.opacity(0.05)), lineWidth: 1)
                y += 6
            }
        }
    }
}

struct MirrorStatCell: View {
    let value: String
    let label: String
    let tint: Color
    var small = false
    init(value: String, label: String, tint: Color, small: Bool = false) {
        self.value = value; self.label = label; self.tint = tint; self.small = small
    }
    var body: some View {
        VStack(spacing: 3) {
            Text(value).font(.system(size: small ? 15 : 17, weight: .bold)).foregroundColor(tint).lineLimit(1)
            Text(label).font(.system(size: 10, weight: .medium)).foregroundColor(MirrorArt.dim)
        }
        .frame(maxWidth: .infinity)
    }
}

struct MirrorSectionLabel: View {
    let text: String
    var body: some View {
        Text(text).font(.system(size: 11, weight: .bold)).foregroundColor(MirrorArt.faint)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 4).padding(.top, 18).padding(.bottom, 8)
    }
}

struct MirrorAccountRow: View {
    let icon: String
    let iconTint: Color
    let title: String
    let subtitle: String
    let titleTint: Color
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 17)).foregroundColor(iconTint)
                .frame(width: 44, height: 44).background(MirrorArt.chip).clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .semibold)).foregroundColor(titleTint)
                Text(subtitle).font(.system(size: 12)).foregroundColor(MirrorArt.dim).lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 13)).foregroundColor(MirrorArt.faint)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
    }
}

enum MirrorSince {
    static func short(_ iso: String?) -> String {
        guard let iso, let date = MirrorISO.parse(iso) else { return "" }
        let f = DateFormatter(); f.dateFormat = "MMM yyyy"; return f.string(from: date)
    }
}

// MARK: - Dock

struct MirrorDock: View {
    @Binding var tab: Int
    let unread: Int
    let onFab: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 0) {
                MirrorDockItem(icon: "ellipsis.bubble", label: "Chats", active: tab == 0, badge: unread) { tab = 0 }
                MirrorDockItem(icon: "phone", label: "Calls", active: tab == 1, badge: 0) { tab = 1 }
                MirrorDockItem(icon: "sphere", label: "Updates", active: tab == 2, badge: 0) { tab = 2 }
                MirrorDockItem(icon: "person.crop.circle", label: "Profile", active: tab == 3, badge: 0) { tab = 3 }
            }
            .frame(height: 64)
            .background(MirrorArt.panel)
            .clipShape(Capsule())

            Button(action: onFab) {
                Image(systemName: "plus").font(.system(size: 22, weight: .semibold)).foregroundColor(MirrorArt.text)
                    .frame(width: 52, height: 52)
                    .background(MirrorArt.glass7)
                    .overlay(Capsule().strokeBorder(MirrorArt.hairline, lineWidth: 1))
                    .clipShape(Circle())
            }
        }
        .padding(.horizontal, 10).padding(.bottom, 10)
    }
}

struct MirrorDockItem: View {
    let icon: String
    let label: String
    let active: Bool
    let badge: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: icon).font(.system(size: 20)).foregroundColor(active ? MirrorArt.text : MirrorArt.dim)
                    if badge > 0 {
                        Text("\(min(badge, 9))")
                            .font(.system(size: 10, weight: .bold)).foregroundColor(.white)
                            .frame(width: 18, height: 18).background(LinearGradient(colors: [MirrorArt.badgeTop, MirrorArt.badgeDeep], startPoint: .leading, endPoint: .trailing))
                            .clipShape(Circle())
                            .offset(x: 7, y: -4)
                    }
                }
                Text(label).font(.system(size: 10, weight: .medium)).foregroundColor(active ? MirrorArt.text : MirrorArt.dim)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

// MARK: - Room (R63 - the web chat-room.tsx conversion)

/// One render unit of the room list (web ClusterItem parity).
private enum MirrorRoomEntry {
    case day(key: String, label: String)
    case stamp(key: String, iso: String)
    case message(key: String, message: WireChatMessage, head: Bool)

    var id: String {
        switch self {
        case .day(let key, _): return key
        case .stamp(let key, _): return key
        case .message(let key, _, _): return key
        }
    }
}

private enum MirrorCluster {
    /// web CLUSTER_WINDOW_MS = 5 minutes.
    static let window: TimeInterval = 5 * 60

    static func entries(_ messages: [WireChatMessage]) -> [MirrorRoomEntry] {
        var out: [MirrorRoomEntry] = []
        var prev: WireChatMessage?
        var dayKey: String?
        let cal = Calendar.current
        var seq = 0
        for m in messages {
            let head: Bool
            if let p = prev {
                let gap = (MirrorISO.parse(m.createdAt) ?? Date.distantPast)
                    .timeIntervalSince(MirrorISO.parse(p.createdAt) ?? Date.distantPast)
                let dayBreak = !cal.isDate(MirrorISO.parse(p.createdAt) ?? Date.distantPast,
                                           inSameDayAs: MirrorISO.parse(m.createdAt) ?? Date.distantPast)
                head = dayBreak || p.senderId != m.senderId || abs(gap) > window
            } else {
                head = true
            }
            if head {
                let date = MirrorISO.parse(m.createdAt) ?? Date()
                let key = cal.dateComponents([.year, .month, .day], from: date).description
                if key != dayKey {
                    out.append(.day(key: "day-\(seq)", label: MirrorRoomDay.short(m.createdAt)))
                    dayKey = key
                }
                out.append(.stamp(key: "stamp-\(seq)", iso: m.createdAt))
            }
            seq += 1
            out.append(.message(key: m.id, message: m, head: head))
            prev = m
        }
        return out
    }
}

private enum MirrorRoomDay {
    static func short(_ iso: String) -> String {
        guard let date = MirrorISO.parse(iso) else { return "" }
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "Today" }
        if cal.isDateInYesterday(date) { return "Yesterday" }
        return MirrorISO.day.string(from: date)
    }
}

private enum MirrorBubbleText {
    /// web BubbleText: **bold**, *italic*, ~~strike~~, `code`, @mentions, links.
    static func make(_ content: String, mine: Bool, memberNames: [String]) -> AttributedString {
        var out = AttributedString()
        let ink = mine ? MirrorArt.onBubbleOut : MirrorArt.text
        let mentionBg = mine ? MirrorArt.onBubbleOut.opacity(0.10) : MirrorArt.accent2.opacity(0.15)
        let warm = mine ? Color(red: 0x9A/255, green: 0x4E/255, blue: 0x06/255) : MirrorArt.accent2
        var rest = Substring(content)
        while !rest.isEmpty {
            // mentions first (web buildMentionRuns order)
            var mentionName: String?
            if rest.hasPrefix("@") {
                let lowered = rest.dropFirst().lowercased()
                mentionName = memberNames
                    .filter { !$0.isEmpty && lowered.hasPrefix($0.lowercased()) }
                    .max(by: { $0.count < $1.count })
            }
            if let name = mentionName {
                var span = AttributedString("@" + rest.dropFirst().prefix(name.count))
                span.backgroundColor = mentionBg
                span.foregroundColor = warm
                span.font = .system(size: 15, weight: .semibold)
                out += span
                rest = rest.dropFirst(1 + name.count)
                continue
            }
            // markdown tokens
            let tokens: [(String, Font.Weight?, Bool?, Bool?, Color?)] = [
                ("**", .bold, nil, nil, ink), ("~~", nil, true, nil, ink.opacity(0.8)),
                ("`", nil, nil, nil, ink), ("*", .regular, nil, true, ink), ("_", .regular, nil, true, ink),
            ]
            var matched = false
            for (marker, weight, strike, italic, tint) in tokens {
                let body = rest.dropFirst(marker.count)
                if rest.hasPrefix(marker), let end = body.range(of: marker) {
                    let innerText = body[..<(end.lowerBound)]
                    if !innerText.isEmpty {
                        var span = AttributedString(String(innerText))
                        var font: Font = .system(size: 15)
                        if weight == .bold { font = .system(size: 15, weight: .bold) }
                        if italic == true { font = font.italic() }
                        span.font = font
                        if strike == true { span.strikethroughStyle = .single }
                        if marker == "`" { span.backgroundColor = Color.black.opacity(0.3); span.font = .system(size: 12.5, design: .monospaced) }
                        span.foregroundColor = tint ?? ink
                        out += span
                        rest = body[(end.upperBound)...]
                        matched = true
                        break
                    }
                }
            }
            if matched { continue }
            // links
            if let range = rest.range(of: #"https?://\S+|www\.\S+"#, options: .regularExpression) {
                if range.lowerBound > rest.startIndex {
                    var plain = AttributedString(String(rest[..<(range.lowerBound)]))
                    plain.foregroundColor = ink
                    plain.font = .system(size: 15)
                    out += plain
                }
                var link = AttributedString(String(rest[range]))
                link.foregroundColor = warm
                link.underlineStyle = .single
                link.font = .system(size: 15)
                out += link
                rest = rest[(range.upperBound)...]
                continue
            }
            // plain run to end
            var plain = AttributedString(String(rest))
            plain.foregroundColor = ink
            plain.font = .system(size: 15)
            out += plain
            rest = rest[rest.endIndex...]
        }
        return out
    }
}

struct MirrorRoomView: View {
    let row: MirrorViewModel.MirrorRow
    @ObservedObject var session: PulseSession
    let onBack: () -> Void
    @State private var messages: [WireChatMessage] = []
    @State private var memberNames: [String] = []
    @State private var memberRows: [WireConversationMember] = []
    @State private var draft = ""
    // R64 - room kebab + sheets + the composer focus ring
    @State private var menuOpen = false
    @State private var activeSheet: MirrorRoomSheet?
    @FocusState private var composerFocused: Bool

    private var bubbleMax: CGFloat { UIScreen.main.bounds.width * 0.78 }

    var body: some View {
        VStack(spacing: 0) {
            // header: bg #0d0906/70, hairline bottom, back / 40 avatar / title+subtitle / 3 icons
            VStack(spacing: 0) {
                HStack(spacing: 2) {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left").font(.system(size: 20)).foregroundColor(MirrorArt.textSoft)
                            .frame(width: 44, height: 44)
                    }
                    MirrorAvatarTile(name: row.title, color: row.color, isGroup: row.isGroup, id: row.id, online: false, showPresence: !row.isGroup, size: 40, corner: 20)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(row.title).font(.system(size: 16, weight: .semibold)).foregroundColor(MirrorArt.text).lineLimit(1)
                        Text(row.subtitle).font(.system(size: 11)).foregroundColor(MirrorArt.dim).lineLimit(1)
                    }
                    Spacer(minLength: 6)
                    Image(systemName: "video").font(.system(size: 18)).foregroundColor(MirrorArt.textSoft).frame(width: 44, height: 44)
                    Image(systemName: "phone").font(.system(size: 18)).foregroundColor(MirrorArt.textSoft).frame(width: 44, height: 44)
                    Button {
                        menuOpen = true
                    } label: {
                        Image(systemName: "ellipsis").font(.system(size: 18)).foregroundColor(MirrorArt.textSoft)
                            .frame(width: 44, height: 44)
                    }
                }
                .padding(.horizontal, 6).frame(minHeight: 56)
                Rectangle().fill(MirrorArt.hairline).frame(height: 1)
            }
            .background(Color(red: 0x0D/255.0, green: 0x09/255.0, blue: 0x06/255.0).opacity(0.7))

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(MirrorCluster.entries(messages), id: \.id) { entry in
                            switch entry {
                            case .day(_, let label):
                                Text(label)
                                    .font(.system(size: 11, weight: .medium)).foregroundColor(MirrorArt.faint)
                                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                            case .stamp(_, let iso):
                                Text(MirrorISO.hhmm.string(from: MirrorISO.parse(iso) ?? Date()))
                                    .font(.system(size: 11, weight: .medium)).foregroundColor(MirrorArt.faint)
                                    .frame(maxWidth: .infinity).padding(.vertical, 4)
                            case .message(_, let message, let head):
                                MirrorBubble(
                                    message: message,
                                    mine: message.senderId == session.viewer?.id,
                                    isGroup: row.isGroup,
                                    head: head,
                                    memberNames: memberNames,
                                    viewerId: session.viewer?.id ?? "",
                                    bubbleMax: bubbleMax,
                                    onToggleReaction: { emoji in react(message.id, emoji) }
                                )
                                .padding(.top, head ? 10 : 2)
                                .id(message.id)
                            }
                        }
                    }
                    .padding(.horizontal, 12).padding(.top, 12).padding(.bottom, 8)
                }
                .onChange(of: messages.count) { _ in
                    if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }

            // composer: art-input-pill + 44dp dark-glass FAB
            HStack(alignment: .bottom, spacing: 8) {
                HStack(spacing: 4) {
                    Image(systemName: "paperclip").font(.system(size: 17)).foregroundColor(MirrorArt.dim)
                        .frame(width: 36, height: 36)
                    TextField("Type here", text: $draft, axis: .vertical)
                        .font(.system(size: 15)).foregroundColor(MirrorArt.text)
                        .lineLimit(1...5)
                        .padding(.vertical, 6)
                        .focused($composerFocused)
                    Image(systemName: "camera").font(.system(size: 17)).foregroundColor(MirrorArt.dim)
                        .frame(width: 36, height: 36)
                }
                .padding(.horizontal, 4).padding(.vertical, 6)
                .frame(minHeight: 48)
                .background(MirrorArt.glass7)
                // web focus hairline: ring-2 inset ring-accent/45 over the WHOLE pill
                .overlay(
                    Capsule().strokeBorder(MirrorArt.accent.opacity(0.45), lineWidth: 2)
                        .opacity(composerFocused ? 1 : 0)
                )
                .overlay(Capsule().strokeBorder(MirrorArt.hairline, lineWidth: 1))
                .clipShape(Capsule())

                Button(action: send) {
                    Image(systemName: draft.isEmpty ? "plus" : "paperplane.fill")
                        .font(.system(size: 18)).foregroundColor(MirrorArt.text)
                        .frame(width: 44, height: 44)
                        .background(MirrorArt.glass7)
                        .overlay(Circle().strokeBorder(MirrorArt.hairline, lineWidth: 1))
                        .clipShape(Circle())
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 12)
        }
        .overlay(alignment: .topTrailing) {
            if menuOpen {
                MirrorRoomKebab(
                    isGroup: row.isGroup,
                    muted: row.muted,
                    onDismiss: { menuOpen = false },
                    onRoomInfo: { menuOpen = false; activeSheet = .info },
                    onSearch: { menuOpen = false; activeSheet = .search },
                    onPinned: { menuOpen = false; activeSheet = .pinned },
                    onMute: { preset in
                        menuOpen = false
                        Task {
                            try? await session.api.setMuted(conversationId: row.id, until: preset)
                        }
                    }
                )
            }
        }
        .sheet(item: $activeSheet) { sheet in
            MirrorRoomSheetView(
                sheet: sheet,
                row: row,
                session: session,
                messages: messages,
                memberRows: memberRows
            )
        }
        .task {
            await load()
            try? await session.api.markRead(conversationId: row.id)
        }
    }

    private func load() async {
        messages = (try? await session.api.messages(conversationId: row.id).messages) ?? []
        if let summary = try? await session.api.conversationDetail(id: row.id, userId: session.api.userId) {
            memberNames = summary.members.map { $0.name }
            memberRows = summary.members
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        draft = ""
        Task {
            _ = try? await session.api.sendMessage(conversationId: row.id, content: text)
            await load()
        }
    }

    private func react(_ messageId: String, _ emoji: String) {
        Task {
            _ = try? await session.api.react(messageId: messageId, emoji: emoji)
            await load()
        }
    }
}

private struct MirrorReactionChip: Identifiable {
    let emoji: String
    let count: Int
    let iReacted: Bool
    var id: String { emoji }
}

struct MirrorBubble: View {
    let message: WireChatMessage
    let mine: Bool
    let isGroup: Bool
    let head: Bool
    let memberNames: [String]
    let viewerId: String
    let bubbleMax: CGFloat
    let onToggleReaction: (String) -> Void

    private var chips: [MirrorReactionChip] {
        (message.reactions ?? []).map { g in
            MirrorReactionChip(emoji: g.emoji, count: g.count, iReacted: g.userIds.contains(viewerId))
        }
    }

    private var bubbleShape: UnevenRoundedRectangle {
        mine
            ? UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 18, bottomTrailingRadius: 18, topTrailingRadius: 6)
            : UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 6, bottomTrailingRadius: 18, topTrailingRadius: 18)
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            if !mine && isGroup {
                if head {
                    MirrorAvatarTile(name: message.sender?.name ?? "?", color: message.sender?.color, isGroup: false, id: message.senderId, online: false, showPresence: false, size: 24, corner: 12)
                        .padding(.trailing, 6).padding(.bottom, 20)
                } else {
                    Color.clear.frame(width: 30, height: 1)
                }
            }
            VStack(alignment: mine ? .trailing : .leading, spacing: 0) {
                if message.deletedAt != nil {
                    Text("This message was deleted")
                        .font(.system(size: 13)).italic().foregroundColor(MirrorArt.faint)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(MirrorArt.hairline, style: StrokeStyle(lineWidth: 1, dash: [6, 4])))
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        // R54-c: sender name INSIDE the incoming group bubble
                        if !mine && isGroup && head {
                            Text(message.sender?.name ?? "")
                                .font(.system(size: 12, weight: .semibold)).foregroundColor(MirrorArt.textSoft)
                                .padding(.bottom, 2)
                        }
                        if let reply = message.replyTo {
                            HStack(alignment: .top, spacing: 0) {
                                Rectangle().fill(mine ? Color.black.opacity(0.15) : MirrorArt.accent).frame(width: 3)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(reply.deleted == true ? "Deleted message" : reply.senderName)
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundColor(mine ? MirrorArt.onBubbleOut : MirrorArt.accent2)
                                    Text(reply.deleted == true ? "This message was deleted" : reply.content)
                                        .font(.system(size: 12)).foregroundColor(mine ? MirrorArt.inkSoft : MirrorArt.dim)
                                        .lineLimit(1)
                                }
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                Spacer(minLength: 0)
                            }
                            .background(mine ? Color.black.opacity(0.05) : Color.white.opacity(0.06))
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .padding(.bottom, 4)
                        }
                        if message.kind == "image", let imagePath = message.imagePath {
                            AsyncImage(url: URL(string: PulseEndpoints.gatewayURL.absoluteString + "/api/uploads/" + imagePath)) { image in
                                image.resizable().aspectRatio(contentMode: .fill)
                            } placeholder: {
                                Rectangle().fill(MirrorArt.chip)
                            }
                            .frame(maxWidth: 240, maxHeight: 300)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            if !message.content.isEmpty {
                                Text(MirrorBubbleText.make(message.content, mine: mine, memberNames: memberNames))
                                    .padding(2)
                            }
                        } else {
                            Text(MirrorBubbleText.make(message.content, mine: mine, memberNames: memberNames))
                        }
                        if mine {
                            // meta row: the read ticks
                            HStack(spacing: 0) {
                                Spacer(minLength: 0)
                                Image(systemName: "checkmark.double")
                                    .font(.system(size: 12)).foregroundColor(MirrorArt.onBubbleOut)
                            }
                            .padding(.top, 2)
                        }
                    }
                    .padding(.horizontal, message.kind == "image" && message.imagePath != nil ? 4 : 12)
                    .padding(.vertical, message.kind == "image" && message.imagePath != nil ? 4 : 8)
                    .background(mine ? MirrorArt.bubbleOut : MirrorArt.bubbleIn)
                    .clipShape(bubbleShape)
                }
                // reactions: -mt overlap chips
                if !chips.isEmpty && message.deletedAt == nil {
                    HStack(spacing: 4) {
                        ForEach(chips) { chip in
                            Button(action: { onToggleReaction(chip.emoji) }) {
                                HStack(spacing: 2) {
                                    Text(chip.emoji).font(.system(size: 12))
                                    if chip.count > 1 {
                                        Text("\(chip.count)")
                                            .font(.system(size: 11, weight: .semibold))
                                            .foregroundColor(chip.iReacted ? MirrorArt.accent2 : MirrorArt.textSoft)
                                    }
                                }
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(chip.iReacted ? Color(red: 0x1A/255, green: 0x12/255, blue: 0x0B/255).opacity(0.9) : Color(red: 0x1A/255, green: 0x12/255, blue: 0x0B/255).opacity(0.8))
                                .overlay(Capsule().strokeBorder(chip.iReacted ? MirrorArt.accent.opacity(0.45) : MirrorArt.hairline, lineWidth: 1))
                                .clipShape(Capsule())
                            }
                        }
                    }
                    .padding(mine ? .trailing : .leading, 8)
                    .offset(y: -6)
                }
            }
            .frame(maxWidth: bubbleMax, alignment: mine ? .trailing : .leading)
            if mine { Color.clear.frame(width: 8) }
        }
        .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
    }
}

struct MirrorLaterView: View {
    let title: String
    var body: some View {
        VStack(spacing: 8) {
            Text(title).font(.system(size: 26, weight: .bold)).foregroundColor(MirrorArt.text)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20).padding(.top, 16)
            Text("Native mirror audit - this tab mirrors the web next round")
                .font(.system(size: 13)).foregroundColor(MirrorArt.dim)
            Spacer()
        }
    }
}

// R64 - room kebab + sheets (mirror parity with the web Conversation menu)

enum MirrorRoomSheet: String, Identifiable {
    case info, search, pinned
    var id: String { rawValue }
}

struct MirrorRoomKebab: View {
    let isGroup: Bool
    let muted: Bool
    let onDismiss: () -> Void
    let onRoomInfo: () -> Void
    let onSearch: () -> Void
    let onPinned: () -> Void
    let onMute: (String?) -> Void
    @State private var muteStrip = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.opacity(0.001)
                .contentShape(Rectangle())
                .onTapGesture { onDismiss() }
            VStack(alignment: .leading, spacing: 0) {
                MirrorKebabRow(icon: "info.circle", label: "Room info", action: onRoomInfo)
                MirrorKebabRow(icon: "magnifyingglass", label: "Search in conversation", action: onSearch)
                MirrorKebabRow(icon: "pin", label: "Pinned messages", action: onPinned)
                if muteStrip {
                    MirrorPanelHint("Mute for")
                    HStack(spacing: 4) {
                        ForEach(["8h", "1w", "Always"], id: \.self) { preset in
                            Button {
                                onDismiss()
                                onMute(preset == "8h" ? "8h" : preset == "1w" ? "1w" : "always")
                            } label: {
                                Text(preset)
                                    .font(.system(size: 12, weight: .semibold)).foregroundColor(MirrorArt.textSoft)
                                    .frame(maxWidth: .infinity).frame(height: 30)
                                    .background(MirrorArt.glass7)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 8).padding(.bottom, 8)
                } else {
                    MirrorKebabRow(
                        icon: muted ? "bell" : "bell.slash",
                        label: muted ? "Unmute notifications" : "Mute notifications",
                        action: { muted ? onMute(nil) : (muteStrip = true) }
                    )
                }
            }
            .frame(width: 224)
            .background(Color(red: 0x1C/255.0, green: 0x16/255.0, blue: 0x10/255.0).opacity(0.95))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(MirrorArt.hairline, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .padding(.trailing, 8)
            .padding(.top, 62)
            .shadow(color: .black.opacity(0.5), radius: 16, y: 8)
        }
    }
}

struct MirrorRoomSheetView: View {
    let sheet: MirrorRoomSheet
    let row: MirrorViewModel.MirrorRow
    @ObservedObject var session: PulseSession
    let messages: [WireChatMessage]
    let memberRows: [WireConversationMember]
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var pinned: [WireChatMessage]?

    var body: some View {
        MirrorPanel(title: panelTitle) {
            switch sheet {
            case .info:
                MirrorPanelScroll {
                    ForEach(memberRows, id: \.id) { member in
                        MirrorPanelUserRow(
                            name: member.name,
                            color: member.color,
                            isGroup: false,
                            id: member.id,
                            subtitle: member.id == session.api.userId ? "you" : "",
                            onTap: {}
                        )
                    }
                }
            case .search:
                MirrorPanelSearch(query: $query)
                MirrorPanelScroll {
                    let q = query.trimmingCharacters(in: .whitespaces).lowercased()
                    let hits = q.isEmpty
                        ? []
                        : messages.filter { ($0.content ?? "").lowercased().contains(q) }.suffix(40)
                    if !q.isEmpty && hits.isEmpty {
                        MirrorPanelHint("No matches in this conversation")
                    }
                    ForEach(Array(hits), id: \.id) { hit in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(hit.content ?? "").font(.system(size: 13)).foregroundColor(MirrorArt.textSoft).lineLimit(2)
                            Text(MirrorRowTime.short(hit.createdAt))
                                .font(.system(size: 10)).foregroundColor(MirrorArt.faint)
                        }
                        .padding(.horizontal, 8).padding(.vertical, 6)
                    }
                }
            case .pinned:
                MirrorPanelScroll {
                    if let pinned {
                        if pinned.isEmpty {
                            MirrorPanelHint("Nothing pinned yet")
                        }
                        ForEach(pinned, id: \.id) { message in
                            HStack(alignment: .top, spacing: 8) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(message.content ?? "Photo")
                                        .font(.system(size: 13)).foregroundColor(MirrorArt.textSoft).lineLimit(2)
                                    Text(MirrorRowTime.short(message.createdAt))
                                        .font(.system(size: 10)).foregroundColor(MirrorArt.faint)
                                }
                                Spacer(minLength: 0)
                                Button {
                                    Task {
                                        _ = try? await session.api.toggleMessagePin(id: message.id, userId: session.api.userId)
                                        pinned = (try? await session.api.pinnedMessages(conversationId: row.id, userId: session.api.userId)) ?? []
                                    }
                                } label: {
                                    Image(systemName: "pin.slash").font(.system(size: 13)).foregroundColor(MirrorArt.dim)
                                        .frame(width: 32, height: 32)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(.horizontal, 8).padding(.vertical, 6)
                        }
                    } else {
                        MirrorPanelHint("Loading pins…")
                    }
                }
                .task {
                    pinned = (try? await session.api.pinnedMessages(conversationId: row.id, userId: session.api.userId)) ?? []
                }
            }
        }
    }

    private var panelTitle: String {
        switch sheet {
        case .info: return row.isGroup ? "Group info" : "Chat info"
        case .search: return "Search in conversation"
        case .pinned: return "Pinned messages"
        }
    }
}
