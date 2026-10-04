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
    static let bubbleOut = Color(red: 0xED/255.0, green: 0xE7/255.0, blue: 0xDC/255.0)
    static let onBubbleOut = Color(red: 0x24/255.0, green: 0x1A/255.0, blue: 0x10/255.0)
    static let bubbleIn = Color(red: 0x29/255.0, green: 0x20/255.0, blue: 0x19/255.0)
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
            muted: summary.mutedUntil != nil
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
            // rows - real summaries, same mapping the web home uses
            if let page = try? await client.conversations() {
                rows = page.map { MirrorViewModel.buildRow(summary: $0, viewerId: client.userId) }
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
                    case 0: MirrorHomeView(model: model, onOpen: { openRow = $0 })
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
    let onOpen: (MirrorViewModel.MirrorRow) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Chats")
                        .font(.system(size: 26, weight: .bold))
                        .foregroundColor(MirrorArt.text)
                    Spacer()
                    Image(systemName: "magnifyingglass").font(.system(size: 20)).foregroundColor(MirrorArt.text)
                        .padding(.horizontal, 8)
                    Image(systemName: "camera").font(.system(size: 20)).foregroundColor(MirrorArt.text)
                        .padding(.horizontal, 8)
                    Image(systemName: "ellipsis").font(.system(size: 20)).foregroundColor(MirrorArt.text)
                        .padding(.horizontal, 8)
                }
                .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 6)

                HStack(spacing: 8) {
                    MirrorChipText(label: "All", active: true)
                    MirrorChipCount(label: "Unread", count: model.totalUnread)
                    MirrorChipText(label: "Groups", active: false)
                }
                .padding(.horizontal, 12).padding(.vertical, 6)

                ForEach(model.rows) { row in
                    MirrorRowView(row: row)
                        .contentShape(Rectangle())
                        .onTapGesture { onOpen(row) }
                }
            }
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
    }
}

struct MirrorChipText: View {
    let label: String
    let active: Bool
    var body: some View {
        Text(label)
            .font(.system(size: 13, weight: .medium))
            .foregroundColor(active ? MirrorArt.text : MirrorArt.textSoft)
            .padding(.horizontal, 14).padding(.vertical, 5)
            .background(active ? MirrorArt.chipActive : MirrorArt.chip)
            .clipShape(Capsule())
    }
}

struct MirrorChipCount: View {
    let label: String
    let count: Int
    var body: some View {
        HStack(spacing: 6) {
            if count > 0 {
                Text("\(min(count, 99))")
                    .font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                    .frame(width: 18, height: 18).background(MirrorArt.red).clipShape(Capsule())
            }
            Text(label).font(.system(size: 13, weight: .medium)).foregroundColor(MirrorArt.textSoft)
        }
        .padding(.horizontal, 14).padding(.vertical, 5)
        .background(MirrorArt.chip).clipShape(Capsule())
    }
}

struct MirrorRowView: View {
    let row: MirrorViewModel.MirrorRow
    var body: some View {
        HStack(spacing: 12) {
            MirrorAvatarTile(name: row.title, color: row.color, isGroup: row.isGroup, id: row.id, online: row.online, showPresence: !row.isGroup, size: 50, corner: 16)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(.system(size: 15, weight: .semibold)).foregroundColor(MirrorArt.text).lineLimit(1)
                Text(row.preview).font(.system(size: 13)).foregroundColor(MirrorArt.dim).lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text(row.time).font(.system(size: 11, weight: .semibold)).foregroundColor(MirrorArt.faint)
                if row.unread > 0 {
                    Text("\(min(row.unread, 99))")
                        .font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(LinearGradient(colors: [MirrorArt.badgeTop, MirrorArt.badgeDeep], startPoint: .leading, endPoint: .trailing))
                        .clipShape(Capsule())
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
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

            MirrorSectionLabel("SAVED")
            MirrorCard {
                MirrorAccountRow(icon: "star", iconTint: MirrorArt.starAmber, title: "Saved messages", subtitle: "Long-press any message in a chat, then Save", titleTint: MirrorArt.text)
            }

            MirrorSectionLabel("ACCOUNT")
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
            MirrorStatCell("\(model.stats?.messages ?? 0)", "MESSAGES", MirrorArt.text)
            MirrorStatCell("\(model.stats?.chats ?? 0)", "ROOMS", MirrorArt.text)
            MirrorStatCell("\(model.coins)", "COINS", MirrorArt.accent)
            MirrorStatCell(MirrorSince.short(model.stats?.joinedAt), "SINCE", MirrorArt.text, small: true)
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
                Image(systemName: "plus").font(.system(size: 22, weight: .semibold)).foregroundColor(.white)
                    .frame(width: 52, height: 52)
                    .background(LinearGradient(colors: [MirrorArt.fabTop, MirrorArt.accent, MirrorArt.fabDeep], startPoint: .topLeading, endPoint: .bottomTrailing))
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

// MARK: - Room + placeholder

struct MirrorRoomView: View {
    let row: MirrorViewModel.MirrorRow
    @ObservedObject var session: PulseSession
    let onBack: () -> Void
    @State private var messages: [WireChatMessage] = []
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.system(size: 20)).foregroundColor(MirrorArt.text)
                        .frame(width: 40, height: 40)
                }
                MirrorAvatarTile(name: row.title, color: row.color, isGroup: row.isGroup, id: row.id, online: false, showPresence: !row.isGroup, size: 38, corner: 19)
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.title).font(.system(size: 16, weight: .bold)).foregroundColor(MirrorArt.text).lineLimit(1)
                    Text("").font(.system(size: 11)).foregroundColor(MirrorArt.dim)
                }
                Spacer()
                Image(systemName: "video").font(.system(size: 18)).foregroundColor(MirrorArt.text).padding(.horizontal, 6)
                Image(systemName: "phone").font(.system(size: 18)).foregroundColor(MirrorArt.text).padding(.horizontal, 6)
                Image(systemName: "ellipsis").font(.system(size: 18)).foregroundColor(MirrorArt.text).padding(.horizontal, 6)
            }
            .padding(.horizontal, 6).frame(height: 56)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 6) {
                        ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                            MirrorBubble(message: message, mine: message.senderId == session.viewer?.id, showSender: row.isGroup && message.senderId != session.viewer?.id)
                                .id(message.id)
                                .onAppear {
                                    if index == messages.count - 1 {
                                        proxy.scrollTo(message.id, anchor: .bottom)
                                    }
                                }
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                }
            }

            HStack(alignment: .bottom, spacing: 8) {
                HStack {
                    Image(systemName: "paperclip").font(.system(size: 16)).foregroundColor(MirrorArt.dim)
                    TextField("Type here", text: $draft)
                        .font(.system(size: 15)).foregroundColor(MirrorArt.text)
                    Image(systemName: "camera").font(.system(size: 16)).foregroundColor(MirrorArt.dim)
                }
                .padding(.horizontal, 14).frame(height: 48)
                .background(Color.white.opacity(0.07)).clipShape(Capsule())

                Button(action: send) {
                    Image(systemName: draft.isEmpty ? "plus" : "paperplane.fill")
                        .font(.system(size: 18)).foregroundColor(draft.isEmpty ? MirrorArt.text : .white)
                        .frame(width: 44, height: 44)
                        .background(draft.isEmpty ? AnyShapeStyle(Color.white.opacity(0.07)) : AnyShapeStyle(LinearGradient(colors: [MirrorArt.fabTop, MirrorArt.accent, MirrorArt.fabDeep], startPoint: .topLeading, endPoint: .bottomTrailing)))
                        .clipShape(Circle())
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 8)
        }
        .task { await load() }
    }

    private func load() async {
        messages = (try? await session.api.messages(conversationId: row.id).messages) ?? []
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
}

struct MirrorBubble: View {
    let message: WireChatMessage
    let mine: Bool
    let showSender: Bool

    var body: some View {
        HStack {
            if mine { Spacer(minLength: 60) }
            VStack(alignment: .leading, spacing: 2) {
                if showSender {
                    Text(message.sender?.name ?? "").font(.system(size: 12, weight: .semibold)).foregroundColor(MirrorArt.textSoft)
                }
                Text(message.deletedAt != nil ? "This message was deleted" : message.content)
                    .font(.system(size: 15))
                    .foregroundColor(mine ? MirrorArt.onBubbleOut : MirrorArt.text)
                    .italic(message.deletedAt != nil)
                if mine, message.deletedAt == nil {
                    HStack {
                        Spacer()
                        Image(systemName: "checkmark").font(.system(size: 9)).foregroundColor(MirrorArt.onBubbleOut.opacity(0.45))
                    }
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(mine ? MirrorArt.bubbleOut : MirrorArt.bubbleIn)
            .clipShape(mine
                ? UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 18, bottomTrailingRadius: 18, topTrailingRadius: 6)
                : UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 6, bottomTrailingRadius: 18, topTrailingRadius: 18))
            if !mine { Spacer(minLength: 60) }
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
