import SwiftUI

/// Wave 6 - the full user page (F-CP-03, user-route-page.tsx +
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
    var onOpenRoom: (WireConversationSummary) -> Void

    @State private var messageTask: Task<Void, Never>?
    @State private var blockTask: Task<Void, Never>?
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
        .task(id: "\(session.sessionGeneration)-\(userId)") {
            model.configure(session: session, userId: userId)
            await model.load()
        }
        .onDisappear { messageTask?.cancel(); blockTask?.cancel() }
        .onChange(of: session.sessionGeneration) { _, _ in messageTask?.cancel(); blockTask?.cancel() }
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

    // hero

    private var online: Bool { session.isOnline(userId) }

    private func hero(_ user: WireUser) -> some View {
        VStack(spacing: 10) {
            // R39 - cover picture banner (web parity) when the member set one.
            if let cover = user.coverImage, !cover.isEmpty {
                AsyncImage(url: PulseTheme.photoURL(cover)) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        Rectangle().fill(PulseTheme.glassFill)
                    }
                }
                .frame(height: 120)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .strokeBorder(PulseTheme.hairlineSoft, lineWidth: 1)
                )
                .accessibilityLabel("Cover picture of \(user.name)")
            }
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

    // status + bio

    @ViewBuilder
    private func statusAndBio(_ user: WireUser) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if user.statusEmoji != nil || (user.statusText ?? "").isEmpty == false {
                HStack(spacing: 6) {
                    // R18-b - the stored value is an icon ID; unknown/stale
                    // values normalize to the registry default (never raw emoji).
                    if let id = PulseStatusIconId.normalize(user.statusEmoji) {
                        Image(systemName: id.symbolName)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(PulseTheme.accent)
                            .accessibilityLabel(id.label)
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

    /// stats (real GET /stats numbers)

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

    // rooms in common (max 3 + "+N more")

    @ViewBuilder
    private var roomsInCommon: some View {
        if !model.sharedRooms.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Rooms in common")
                    .font(.system(size: 11, weight: .semibold))
                    .textCase(.uppercase)
                    .foregroundStyle(PulseTheme.textSecondary)
                ForEach(Array(model.sharedRooms.prefix(3)), id: \.id) { room in
                    Button {
                        PulseHaptics.tap()
                        messageTask?.cancel()
                        onOpenRoom(room)
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

    // member since / last seen

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

    // actions

    private func actions(_ user: WireUser) -> some View {
        let isViewer = user.id == session.viewer?.id
        return VStack(spacing: 8) {
            if !isViewer {
                Button {
                    guard messageTask == nil, blockTask == nil else { return }
                    session.noteRoomNavigationIntent()
                    let epoch = session.sessionGeneration, navigation = session.roomNavigationGeneration
                    messageTask = Task { @MainActor in
                        defer { messageTask = nil }
                        guard session.roomNavigationGeneration == navigation else { return }
                        if let conversation = await model.message(), !Task.isCancelled,
                           session.sessionGeneration == epoch, session.roomNavigationGeneration == navigation {
                            onOpenRoom(conversation)
                        }
                    }
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
                        guard blockTask == nil else { return }
                        session.noteRoomNavigationIntent()
                        messageTask?.cancel()
                        blockTask = Task { @MainActor in
                            defer { blockTask = nil }
                            await model.toggleBlock()
                        }
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
                        session.noteRoomNavigationIntent()
                        messageTask?.cancel()
                        PulseHaptics.tap()
                        withAnimation(.pulse(.pulseSoft, reduceMotion: PulseMotion.reduceMotion)) { model.reportOpen.toggle() }
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

// UserPage state holder (UDF): user + stats + pair block state +
// rooms-in-common overlap from the live conversations list.
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
    private var configuredEpoch: UUID?
    private var blockRevision: UInt = 0

    func configure(session: PulseSession, userId: String) {
        guard configuredEpoch != session.sessionGeneration || self.userId != userId else { return }
        configuredEpoch = session.sessionGeneration
        blockRevision &+= 1
        self.session = session
        self.userId = userId
        user = nil; stats = nil; statsFailed = false; sharedRooms = []; errorText = nil
        blocked = false
    }

    func load() async {
        guard let session else { return }
        let api = session.api, epoch = session.sessionGeneration, target = userId, blockTicket = blockRevision
        func isCurrent() -> Bool {
            !Task.isCancelled && session.sessionGeneration == epoch && userId == target
        }
        if user == nil {
            do {
                let fresh = try await api.user(target)
                guard isCurrent() else { return }
                user = fresh
                errorText = nil
            } catch {
                guard isCurrent() else { return }
                errorText = ChatsViewModel.describe(error)
            }
        }
        if stats == nil {
            do {
                let fresh = try await api.userStats(target)
                guard isCurrent() else { return }
                stats = fresh
                statsFailed = false
            } catch {
                guard isCurrent() else { return }
                statsFailed = true
            }
        }
        guard isCurrent() else { return }
        if let state = try? await api.blockState(target: target) {
            guard isCurrent() else { return }
            if blockTicket == blockRevision { blocked = state }
        }
        guard isCurrent() else { return }
        if sharedRooms.isEmpty, let mine = try? await api.conversations() {
            guard isCurrent() else { return }
            sharedRooms = mine.filter { $0.isGroup && $0.members.contains(where: { $0.id == target }) }
        }
    }

    /// Return the confirmed DM to the owning view; it knows which tab must open.
    func message() async -> WireConversationSummary? {
        guard let session, !messaging, let viewer = session.viewer else { return nil }
        let api = session.api, epoch = session.sessionGeneration, target = userId
        messaging = true
        defer { messaging = false }
        do {
            let conv = try await PulseConversationNavigation.create(viewerId: viewer.id,
                memberIds: [target], isGroup: false) {
                try await api.createConversation(memberIds: [target], isGroup: false)
            }
            guard !Task.isCancelled, session.sessionGeneration == epoch, userId == target else { return nil }
            PulseHaptics.success()
            session.noteInboxChanged()
            return conv
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch, userId == target else { return nil }
            session.toasts.show(ChatsViewModel.describe(error))
            return nil
        }
    }

    /// Block/unblock with the honest toasts - the pair state refetches
    /// afterwards (server truth, no optimistic lies).
    func toggleBlock() async {
        guard let session, !blockBusy else { return }
        let api = session.api, epoch = session.sessionGeneration, target = userId, wasBlocked = blocked
        blockBusy = true
        blockRevision &+= 1
        defer { blockBusy = false }
        do {
            if wasBlocked { try await api.unblock(userId: target) }
            else { try await api.block(userId: target) }
            guard !Task.isCancelled, session.sessionGeneration == epoch, userId == target else { return }
            blockRevision &+= 1
            blocked = !wasBlocked
            PulseHaptics.success()
            session.toasts.show("\(wasBlocked ? "Unblocked" : "Blocked") \(user?.name ?? "account")")
            if let fresh = try? await api.blockState(target: target) {
                guard !Task.isCancelled, session.sessionGeneration == epoch, userId == target else { return }
                blocked = fresh
            }
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch, userId == target else { return }
            session.toasts.show(ChatsViewModel.describe(error))
        }
    }
}
