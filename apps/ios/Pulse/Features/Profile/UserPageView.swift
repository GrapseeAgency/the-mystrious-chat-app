import SwiftUI

/// Wave 6 — the full user page (F-CP-03, user-route-page.tsx +
/// user-profile-sheet.tsx parity): hero + presence, @handle, status
/// glyph+text, bio, REAL stats grid (GET /api/users/{id}/stats), rooms in
/// common (max 3 + "+N more"), member-since/last-seen stamps, and the
/// Message / Block|Unblock / Report actions.
public struct UserRoute: Hashable {
    public let userId: String
    public let name: String

    public init(userId: String, name: String) {
        self.userId = userId
        self.name = name
    }
}

struct UserPageView: View {
    let initial: WireUser?
    let userId: String
    @ObservedObject var session: PulseSession

    @StateObject private var model = UserPageModel()

    var body: some View {
        Group {
            if let user = model.user ?? initial {
                content(user)
            } else if let error = model.errorText {
                ContentUnavailableCompat(title: "Profile unavailable", systemImage: "person.slash", note: error)
            } else {
                ProgressView("Loading profile…")
            }
        }
        .navigationTitle(model.user?.name ?? initial?.name ?? "Profile")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.configure(session: session, userId: userId) }
        .task { await model.load() }
    }

    @ViewBuilder
    private func content(_ user: WireUser) -> some View {
        ScrollView {
            VStack(spacing: 14) {
                hero(user)
                statusAndBio(user)
                statsGrid
                roomsInCommon
                stampsFooter(user)
                actions(user)
                if model.reportOpen {
                    ReportPanelView(reported: user, session: session) {
                        model.reportOpen = false
                    }
                }
            }
            .padding(14)
            .padding(.bottom, 24)
        }
        .background(PulseTheme.pageWash.ignoresSafeArea())
    }

    // ── hero ─────────────────────────────────────────────────

    private var online: Bool { session.isOnline(userId) }

    private func hero(_ user: WireUser) -> some View {
        VStack(spacing: 10) {
            PulseAvatar(
                name: user.name,
                color: PulseTheme.color(named: user.color),
                photoURL: PulseTheme.photoURL(user.avatar),
                online: online,
                size: 96,
            )
            Text(user.name)
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(PulseTheme.titleOnWash)
                .multilineTextAlignment(.center)
            if let handle = user.username {
                Text("@\(handle)")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(PulseTheme.accent)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(PulseTheme.glassFill))
                    .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
            } else {
                Text("No handle yet")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(PulseTheme.textTertiary)
            }
            presencePill(user)
        }
        .frame(maxWidth: .infinity)
    }

    private func presencePill(_ user: WireUser) -> some View {
        let label: String
        if online {
            label = "Online now"
        } else if let lastSeen = user.lastSeenAt {
            label = PulseFormat.rowTime(lastSeen)
        } else {
            label = "Last seen hidden"
        }
        return HStack(spacing: 6) {
            Circle()
                .fill(online ? PulseTheme.emerald : PulseTheme.presenceOffline)
            Text(label)
                .font(.system(size: 11, weight: .bold, design: .default))
                .textCase(.uppercase)
                .foregroundStyle(PulseTheme.textSecondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(PulseTheme.glassFill))
        .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
    }

    // ── status + bio ─────────────────────────────────────────

    @ViewBuilder
    private func statusAndBio(_ user: WireUser) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if user.statusEmoji != nil || (user.statusText ?? "").isEmpty == false {
                HStack(spacing: 6) {
                    if let emoji = user.statusEmoji {
                        Text(statusGlyphDisplay(emoji))
                    }
                    if let text = user.statusText {
                        Text(text)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(PulseTheme.textPrimary)
                    }
                }
            }
            Text((user.about ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "No bio yet"
                : user.about ?? "")
                .font(.system(size: 13))
                .foregroundStyle(PulseTheme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16).fill(PulseTheme.rowFill))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(PulseTheme.rowRim, lineWidth: 1))
    }

    /// The 11 fixed stored values are emoji glyphs + the literal "vacation"
    /// (web renders Lucide icons; the native picker shows the emoji set and
    /// maps the vacation token to its plane glyph for display only).
    static func statusGlyphDisplay(_ value: String) -> String {
        value == "vacation" ? "✈️" : value
    }

    // ── stats (real GET /stats numbers) ──────────────────────

    @ViewBuilder
    private var statsGrid: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Activity")
                .font(.system(size: 11, weight: .semibold))
                .textCase(.uppercase)
                .foregroundStyle(PulseTheme.textSecondary)
            if let stats = model.stats {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                    statCell("messages", stats.messages)
                    statCell("reactions", stats.reactions)
                    statCell("photos", stats.photos)
                    statCell("voice", stats.voiceNotes)
                    statCell("chats", stats.chats)
                    statCell("groups", stats.groups)
                }
            } else if model.statsFailed {
                Text("Stats unavailable right now.")
                    .font(.system(size: 12))
                    .foregroundStyle(PulseTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.glassFill))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(PulseTheme.hairlineSoft, lineWidth: 1))
            } else {
                RoundedRectangle(cornerRadius: 12)
                    .fill(PulseTheme.glassFill)
                    .frame(height: 56)
            }
        }
    }

    private func statCell(_ label: String, _ value: Int?) -> some View {
        VStack(spacing: 2) {
            Text("\(value ?? 0)")
                .font(.system(size: 17, weight: .bold, design: .rounded))
                .foregroundStyle(PulseTheme.titleOnPanel)
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(PulseTheme.textTertiary)
        }
        .frame(maxWidth: .infinity, minHeight: 56)
        .background(RoundedRectangle(cornerRadius: 14).fill(PulseTheme.rowFill))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(value ?? 0) \(label)")
    }

    // ── rooms in common (max 3 + "+N more") ──────────────────

    @ViewBuilder
    private var roomsInCommon: some View {
        if !model.sharedRooms.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Rooms in common")
                    .font(.system(size: 11, weight: .semibold))
                    .textCase(.uppercase)
                    .foregroundStyle(PulseTheme.textSecondary)
                ForEach(model.sharedRooms.prefix(3)) { room in
                    Button {
                        PulseHaptics.tap()
                        model.openRoom(room)
                    } label: {
                        HStack(spacing: 10) {
                            RowAvatar(name: room.name ?? "Group", colorName: nil, photoPath: room.photo, size: 32)
                            Text(room.name ?? "Group")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(PulseTheme.titleOnPanel)
                                .lineLimit(1)
                            Spacer()
                            Text("\(room.members.count) \(room.members.count == 1 ? "member" : "members")")
                                .font(.system(size: 11))
                                .foregroundStyle(PulseTheme.textTertiary)
                        }
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: 12).fill(PulseTheme.rowFill))
                    }
                    .buttonStyle(PulseButtonStyle())
                }
                if model.sharedRooms.count > 3 {
                    Text("+\(model.sharedRooms.count - 3) more")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(PulseTheme.textTertiary)
                }
            }
        }
    }

    // ── member since / last seen ─────────────────────────────

    private func stampsFooter(_ user: WireUser) -> some View {
        let joined = model.stats?.joinedAt ?? user.createdAt
        let joinedText = joined.flatMap { PulseFormat.dayLabel($0) } ?? ""
        let lastSeen: String
        if online {
            lastSeen = "Online now"
        } else if let stamp = model.stats?.lastSeenAt {
            lastSeen = "Last active \(PulseFormat.rowTime(stamp))"
        } else {
            lastSeen = "Last seen hidden"
        }
        return Text("Member since \(joinedText)  ·  \(lastSeen)")
            .font(.system(size: 11))
            .foregroundStyle(PulseTheme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // ── actions ──────────────────────────────────────────────

    private func actions(_ user: WireUser) -> some View {
        let isViewer = user.id == session.viewer?.id
        return VStack(spacing: 8) {
            if !isViewer {
                Button {
                    Task { await model.message() }
                } label: {
                    Group {
                        if model.messaging {
                            ProgressView().tint(.white)
                        } else {
                            Label("Message \(firstName(user))", systemImage: "message.fill")
                                .font(.system(size: 14, weight: .bold))
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(RoundedRectangle(cornerRadius: 16).fill(PulseTheme.brandGradient))
                    .foregroundStyle(.white)
                }
                .buttonStyle(PulseButtonStyle())

                HStack(spacing: 8) {
                    Button {
                        Task { await model.toggleBlock() }
                    } label: {
                        Group {
                            if model.blockBusy {
                                ProgressView()
                            } else {
                                Text(model.blocked ? "Unblock" : "Block")
                                    .font(.system(size: 13, weight: .semibold))
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 42)
                    }
                    .buttonStyle(.bordered)
                    .tint(PulseTheme.rose500)

                    Button {
                        PulseHaptics.tap()
                        withAnimation(.pulse(.pulseSoft, reduceMotion: false)) { model.reportOpen.toggle() }
                    } label: {
                        Text(model.reportOpen ? "Hide report" : "Report")
                            .font(.system(size: 13, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 42)
                    }
                    .buttonStyle(.bordered)
                    .tint(PulseTheme.amber600)
                }
            }
        }
    }

    private func firstName(_ user: WireUser) -> String {
        user.name.split(separator: " ").first.map(String.init) ?? user.name
    }
}

// ─────────────────────────────────────────────────────────────
// UserPage state holder (UDF): user + stats + pair block state +
// rooms-in-common overlap from the live conversations list.
// ─────────────────────────────────────────────────────────────
@MainActor
final class UserPageModel: ObservableObject {
    @Published private(set) var user: WireUser?
    @Published private(set) var stats: WireUserStats?
    @Published private(set) var statsFailed = false
    @Published private(set) var blocked = false
    @Published private(set) var sharedRooms: [WireConversationSummary] = []
    @Published private(set) var messaging = false
    @Published private(set) var blockBusy = false
    @Published private(set) var errorText: String?
    @Published var reportOpen = false

    private weak var session: PulseSession?
    private var userId = ""
    private var configured = false

    func configure(session: PulseSession, userId: String) {
        guard !configured || self.userId != userId else { return }
        configured = true
        self.session = session
        self.userId = userId
    }

    func load() async {
        guard let session else { return }
        if user == nil, let fresh = try? await session.api.user(userId) {
            user = fresh
        }
        if stats == nil {
            do {
                stats = try await session.api.userStats(userId)
                statsFailed = false
            } catch {
                statsFailed = true // honest empty — "Stats unavailable right now."
            }
        }
        blocked = (try? await session.api.blockState(target: userId)) ?? false
        if sharedRooms.isEmpty {
            let mine = (try? await session.api.conversations()) ?? []
            sharedRooms = mine.filter { conv in
                conv.isGroup && conv.members.contains(where: { $0.id == userId })
            }
        }
    }

    /// Message {firstName} — create-or-dedupe DM, then open via the session
    /// handoff (Chats tab pushes the room — same path the dock compose uses).
    func message() async {
        guard let session, !messaging else { return }
        messaging = true
        defer { messaging = false }
        do {
            let conv = try await session.api.createConversation(memberIds: [userId], isGroup: false)
            PulseHaptics.success()
            session.noteInboxChanged()
            session.requestOpenRoom(conv)
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

    /// Block/unblock with the honest toasts — the pair state refetches
    /// afterwards (server truth, no optimistic lies).
    func toggleBlock() async {
        guard let session, !blockBusy else { return }
        blockBusy = true
        defer { blockBusy = false }
        do {
            if blocked {
                try await session.api.unblock(userId: userId)
                PulseHaptics.success()
                session.toasts.show("Unblocked \(user?.name ?? "account")")
            } else {
                try await session.api.block(userId: userId)
                PulseHaptics.success()
                session.toasts.show("Blocked \(user?.name ?? "account")")
            }
            blocked = (try? await session.api.blockState(target: userId)) ?? blocked
        } catch {
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }

    func openRoom(_ conv: WireConversationSummary) {
        session?.requestOpenRoom(conv)
    }
}
