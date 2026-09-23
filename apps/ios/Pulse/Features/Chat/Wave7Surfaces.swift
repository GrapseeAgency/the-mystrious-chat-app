import SwiftUI

// ─────────────────────────────────────────────────────────────
// Wave 7 — collaboration & hub room surfaces (F-RO-02…09):
// red packet card + create/detail sheets, tic-tac-toe card,
// tournament card, kanban board, whiteboard canvas, events,
// reminders and the leaderboard. Poll transports mirror the web
// exactly: kanban 1.5 s while open, game card 1.5 s while active,
// whiteboard 900 ms since-delta, red packet bubble 20 s.
// Server error copy surfaces verbatim through Wave7RoomActions.
// ─────────────────────────────────────────────────────────────

// MARK: - Room actions hub (sheet flags + API calls)

@MainActor
final class Wave7RoomActions: ObservableObject {
    @Published var redPacketCreateOpen = false
    @Published var gameCreateOpen = false
    @Published var tournamentCreateOpen = false
    @Published var kanbanOpen = false
    @Published var whiteboardOpen = false
    @Published var eventsOpen = false
    @Published var remindersOpen = false
    @Published var leaderboardOpen = false
    @Published var redPacketDetailId: String?
    @Published var toast: String?
    @Published var toastIsError = false

    /// Message→kanban conversion source (web parity: message→card, title cap 80).
    var kanbanSourceMessage: WireChatMessage?
    /// Message-anchored reminder source (web parity: per-message "Remind me").
    var reminderAnchor: WireChatMessage?

    /// Carrier messages created by game/tournament/red-packet flows must appear
    /// in the stream — the room VM injects this sink.
    var onCarrierMessage: ((WireChatMessage) -> Void)?

    // Card-side fetchers/actors, wired once by the room (captures session.api).
    var cardRedPacketLoad: (String) async -> WireRedPacketDetail? = { _ in nil }
    var cardGameLoad: (String) async -> WireGameDetail? = { _ in nil }
    var cardTournamentLoad: (String) async -> WireTournamentSummary? = { _ in nil }
    var cardGrab: (String) -> Void = { _ in }
    var cardMove: (String, Int) -> Void = { _, _ in }
    var cardJoinGame: (String) -> Void = { _ in }
    var cardJoinTournament: (String) -> Void = { _ in }
    var cardFinishTournament: (String) -> Void = { _ in }
    var cardOpenDetail: (String) -> Void = { _ in }

    func toast(_ text: String, isError: Bool = false) {
        toast = text
        toastIsError = isError
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            if self.toast == text { self.toast = nil }
        }
    }

    private func describe(_ error: Error) -> String {
        (error as? PulseAPIClient.Failure)?.message ?? error.localizedDescription
    }

    // MARK: red packet

    func createRedPacket(api: PulseAPIClient, conversationId: String, total: Int, count: Int, note: String?) {
        Task { @MainActor in
            do {
                let result = try await api.createRedPacket(conversationId: conversationId, total: total, count: count, note: note)
                if let message = result.message { onCarrierMessage?(message) }
                redPacketCreateOpen = false
                toast("Red packet sent — \(total) PC in \(count) grabs")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func grabRedPacket(api: PulseAPIClient, packetId: String) {
        Task { @MainActor in
            do {
                let result = try await api.grabRedPacket(packetId)
                toast("You grabbed \(result.amount) PC 🎉")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    // MARK: games / tournaments

    func createGame(api: PulseAPIClient, conversationId: String, opponentId: String?) {
        Task { @MainActor in
            do {
                let result = try await api.createGame(conversationId: conversationId, opponentId: opponentId)
                if let message = result.message { onCarrierMessage?(message) }
                gameCreateOpen = false
                toast("Tic-tac-toe challenge sent")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func moveGame(api: PulseAPIClient, matchId: String, cell: Int) {
        Task { @MainActor in
            do { _ = try await api.gameMove(matchId, cell: cell) }
            catch { toast(describe(error), isError: true) }
        }
    }

    func joinGame(api: PulseAPIClient, matchId: String) {
        Task { @MainActor in
            do { _ = try await api.joinGame(matchId) }
            catch { toast(describe(error), isError: true) }
        }
    }

    func createTournament(api: PulseAPIClient, conversationId: String, name: String) {
        Task { @MainActor in
            do {
                let result = try await api.createTournament(conversationId: conversationId, name: name)
                if let message = result.message { onCarrierMessage?(message) }
                tournamentCreateOpen = false
                toast("Tournament \"\(name)\" started")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func joinTournament(api: PulseAPIClient, tournamentId: String) {
        Task { @MainActor in
            do {
                _ = try await api.joinTournament(tournamentId)
                toast("You are in — play tic-tac-toe matches to score points")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func finishTournament(api: PulseAPIClient, tournamentId: String) {
        Task { @MainActor in
            do {
                _ = try await api.finishTournament(tournamentId)
                toast("Tournament finished")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    // MARK: kanban

    func addCard(api: PulseAPIClient, conversationId: String, title: String, column: String, assigneeId: String?) {
        let messageId = kanbanSourceMessage?.id
        Task { @MainActor in
            do {
                let card = try await api.createKanbanCard(conversationId: conversationId, title: title, column: column, assigneeId: assigneeId, messageId: messageId)
                kanbanSourceMessage = nil
                toast("Task \"\(card.title ?? title)\" added to the board")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func moveCard(api: PulseAPIClient, cardId: String, column: String) {
        Task { @MainActor in
            do { _ = try await api.updateKanbanCard(cardId, title: nil, column: column, assigneeId: nil, clearAssignee: false, position: nil) }
            catch { toast(describe(error), isError: true) }
        }
    }

    func deleteCard(api: PulseAPIClient, cardId: String) {
        Task { @MainActor in
            do { try await api.deleteKanbanCard(cardId) }
            catch { toast(describe(error), isError: true) }
        }
    }

    // MARK: whiteboard

    /// R1-W2G D44 — the sheet needs the sync VERDICT: a pending stroke
    /// leaves the durable draft only when the server confirms the POST
    /// (web whiteboard-sheet.tsx:497-500 truth). The verbatim error toast
    /// still surfaces from here on failure.
    func postStrokes(api: PulseAPIClient, conversationId: String, strokes: [WireWhiteboardStrokePost]) async -> Bool {
        do {
            _ = try await api.postWhiteboardStrokes(conversationId: conversationId, strokes: strokes)
            return true
        } catch {
            toast(describe(error), isError: true)
            return false
        }
    }

    func undoStroke(api: PulseAPIClient, conversationId: String) {
        Task { @MainActor in
            do { _ = try await api.undoWhiteboardStroke(conversationId: conversationId) }
            catch { toast(describe(error), isError: true) }
        }
    }

    func clearBoard(api: PulseAPIClient, conversationId: String) {
        Task { @MainActor in
            do { _ = try await api.clearWhiteboard(conversationId: conversationId) }
            catch { toast(describe(error), isError: true) }
        }
        toast("Board cleared")
    }

    // MARK: events

    func createEvent(api: PulseAPIClient, conversationId: String, title: String, startsAtIso: String, description: String?, location: String?) {
        Task { @MainActor in
            do {
                _ = try await api.createEvent(conversationId: conversationId, title: title, startsAtIso: startsAtIso, description: description, location: location)
                eventsOpen = false
                toast("Event scheduled — see you there")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func rsvp(api: PulseAPIClient, eventId: String, status: String) {
        Task { @MainActor in
            do { _ = try await api.rsvpEvent(eventId, status: status) }
            catch { toast(describe(error), isError: true) }
        }
    }

    func checkin(api: PulseAPIClient, eventId: String) {
        Task { @MainActor in
            do {
                let result = try await api.checkinEvent(eventId)
                if result.alreadyCheckedIn == true {
                    toast("Already checked in.")
                } else if result.xpAwarded == true {
                    toast("Checked in — see you there · +15 XP")
                } else {
                    toast("Checked in — see you there")
                }
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func deleteEvent(api: PulseAPIClient, eventId: String) {
        Task { @MainActor in
            do { try await api.deleteEvent(eventId) }
            catch { toast(describe(error), isError: true) }
        }
        toast("Event deleted.")
    }

    // MARK: reminders

    func createReminder(api: PulseAPIClient, conversationId: String, note: String, remindAtIso: String, anchored: WireChatMessage?) {
        Task { @MainActor in
            do {
                let item = try await api.createReminder(
                    conversationId: conversationId,
                    messageId: anchored?.id,
                    note: note,
                    remindAtIso: remindAtIso,
                )
                reminderAnchor = nil
                remindersOpen = false
                // Tier 1 — schedule the local fire (offline-capable).
                if let iso = item.remindAt {
                    let ms = Self.epochMs(from: iso)
                    if let ms {
                        PulseReminderNotifications.requestAuthorization()
                        PulseReminderNotifications.schedule(reminderId: item.id, note: item.note ?? note, remindAtEpochMs: ms)
                    }
                }
                toast("Reminder set")
            } catch {
                toast(describe(error), isError: true)
            }
        }
    }

    func resolveReminder(api: PulseAPIClient, id: String) {
        Task { @MainActor in
            do { _ = try await api.resolveReminder(id) }
            catch { toast(describe(error), isError: true) }
        }
    }

    func deleteReminder(api: PulseAPIClient, id: String) {
        Task { @MainActor in
            do { try await api.deleteReminder(id) }
            catch { toast(describe(error), isError: true) }
        }
        PulseReminderNotifications.cancel(reminderId: id)
        toast("Reminder canceled")
    }

    static func epochMs(from iso: String) -> Int64? {
        let formats = ["yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX", "yyyy-MM-dd'T'HH:mm:ssXXXXX"]
        for f in formats {
            let df = DateFormatter()
            df.dateFormat = f
            df.locale = Locale(identifier: "en_US_POSIX")
            if let d = df.date(from: iso) {
                return Int64(d.timeIntervalSince1970 * 1000)
            }
        }
        return nil
    }
}

// MARK: - Red packet (F-RO-02)

struct Wave7RedPacketCard: View {
    let packetId: String
    let total: Int
    let count: Int
    let note: String?
    let isMine: Bool
    let viewerId: String
    let load: (String) async -> WireRedPacketDetail?
    let onGrab: () -> Void
    let onOpenDetail: () -> Void

    @State private var detail: WireRedPacketDetail?

    var body: some View {
        HStack(spacing: 10) {
            Text("🧧").font(.system(size: 24))
            VStack(alignment: .leading, spacing: 2) {
                Text(note?.isEmpty == false ? note! : "Red packet")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(metaText)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.8))
            }
            Spacer(minLength: 4)
            if canGrab {
                Button(action: onGrab) {
                    Text("Open")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.white, in: Capsule())
                        .foregroundStyle(Color(red: 0.55, green: 0.18, blue: 0.16))
                }
            }
        }
        .padding(12)
        .background(
            LinearGradient(colors: [Color(red: 0.71, green: 0.27, blue: 0.23), Color(red: 0.55, green: 0.18, blue: 0.16)], startPoint: .leading, endPoint: .trailing),
            in: RoundedRectangle(cornerRadius: 16),
        )
        .contentShape(Rectangle())
        .onTapGesture { if detail != nil { onOpenDetail() } }
        .task(id: packetId) {
            // 20 s poll — web redpacket-bubble.tsx:79.
            while !Task.isCancelled {
                detail = await load(packetId)
                try? await Task.sleep(nanoseconds: 20_000_000_000)
            }
        }
    }

    private var myGrab: Int? { detail?.myGrab }
    private var status: String? { detail?.packet?.status }

    private var canGrab: Bool {
        !isMine && status == nil && myGrab == nil && detail != nil
    }

    private var metaText: String {
        if let myGrab, myGrab > 0 { return "You grabbed \(myGrab) PC" }
        if isMine { return "\(detail?.packet?.grabbed ?? 0)/\(count) claimed · \(total) PC" }
        if status == "expired" { return "Expired — unclaimed PC refunded" }
        if status == "exhausted" { return "Fully grabbed" }
        return "\(total) PC · \(count) grabs"
    }
}

struct Wave7RedPacketDetailBody: View {
    let detail: WireRedPacketDetail

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(statusText)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("From \(detail.senderName ?? "Unknown") · \(detail.packet?.total ?? 0) PC in \(detail.packet?.count ?? 0) grabs")
                .font(.subheadline.weight(.semibold))
            if let myGrab = detail.myGrab, myGrab > 0 {
                Text("You grabbed \(myGrab) PC 🎉")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PulseTheme.color(named: "emerald"))
            }
            if (detail.grabs ?? []).isEmpty {
                Text("No grabs yet.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach((detail.grabs ?? []).enumerated().map { $1 }, id: \.userId) { grab in
                HStack {
                    Text(grab.name?.prefix(1).uppercased() ?? "?")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(PulseTheme.color(named: "emerald"))
                        .frame(width: 24, height: 24)
                        .background(PulseTheme.color(named: "emerald").opacity(0.15), in: Circle())
                    Text(grab.name ?? "Player")
                        .font(.subheadline)
                        .lineLimit(1)
                    Spacer()
                    Text("\(grab.amount ?? 0) PC")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(PulseTheme.color(named: "emerald"))
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.bottom, 18)
    }

    private var statusText: String {
        switch detail.packet?.status {
        case "expired": return "Expired — unclaimed PC refunded to the sender"
        case "exhausted": return "Fully grabbed"
        default: return "\(detail.packet?.grabbed ?? 0)/\(detail.packet?.count ?? 0) claimed"
        }
    }
}

struct Wave7RedPacketCreateSheet: View {
    let onSend: (Int, Int, String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var total = "50"
    @State private var count = "5"
    @State private var note = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Total PC (1–10000)", text: $total)
                        .keyboardType(.numberPad)
                    TextField("Grabs (1–50)", text: $count)
                        .keyboardType(.numberPad)
                    TextField("Note (optional, ≤60)", text: $note)
                } footer: {
                    Text("Whole PC only — every grab wins at least 1.")
                }
                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
                Button("Send red packet") {
                    let t = Int(total) ?? 0
                    let c = Int(count) ?? 0
                    if t < 1 || t > 10_000 {
                        error = "total must be an integer between 1 and 10000 PC."
                    } else if c < 1 || c > 50 {
                        error = "count must be an integer between 1 and 50."
                    } else if c > t {
                        error = "count must be ≤ total so every grab wins at least 1 PC."
                    } else {
                        onSend(t, c, note.isEmpty ? nil : note)
                    }
                }
            }
            .navigationTitle("🧧 Red packet")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - Tic-tac-toe (F-RO-07)

struct Wave7TicTacToeCard: View {
    let matchId: String
    let viewerId: String
    let load: (String) async -> WireGameDetail?
    let onMove: (Int) -> Void
    let onJoin: () -> Void

    @State private var detail: WireGameDetail?

    var body: some View {
        VStack(spacing: 8) {
            Text(headline)
                .font(.subheadline.weight(.semibold))
            board
            HStack {
                Text("✕ \(detail?.playerX?.name ?? "Player")")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(PulseTheme.color(named: "emerald"))
                Spacer()
                Text("vs").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text("◯ \(detail?.playerO?.name ?? "waiting…")")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.orange)
            }
            if openSeat {
                Button("Take the O seat", action: onJoin)
                    .buttonStyle(.bordered)
            }
        }
        .padding(12)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 16))
        .task(id: "\(matchId)-\(detail?.match?.status ?? "")") {
            // 1.5 s poll while active — web game-tictactoe-card.tsx:33,134.
            guard detail?.match?.status == "active" || detail == nil else { return }
            detail = await load(matchId)
            while detail?.match?.status == "active" && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                detail = await load(matchId)
            }
        }
    }

    private var match: WireGameMatch {
        detail?.match ?? WireGameMatch(
            id: matchId, conversationId: nil, game: nil, playerXId: nil, playerOId: nil,
            board: nil, turn: nil, status: nil, winnerId: nil, winLine: nil,
            moveCount: nil, createdAt: nil, updatedAt: nil,
        )
    }

    private var mySide: Character? { PulseWave7Logic.sideOf(match, viewerId) }
    private var myTurn: Bool { PulseWave7Logic.isMyTurn(match, viewerId) }
    private var openSeat: Bool {
        match.status == "active" && match.playerOId == nil && mySide == nil
    }

    private var headline: String {
        switch match.status {
        case "x_won": return "X wins — \(detail?.playerX?.name ?? "X")"
        case "o_won": return "O wins — \(detail?.playerO?.name ?? "O")"
        case "draw": return "Draw — both +10 XP"
        default:
            if match.playerOId == nil && mySide == nil { return "⚔️ Open challenge — tap a seat to join" }
            if match.playerOId == nil { return "⚔️ Tic-tac-toe — open challenge" }
            return myTurn ? "Your turn (\(mySide.map(String.init) ?? ""))" : "Turn: \(match.turn ?? "X")"
        }
    }

    private var board: some View {
        VStack(spacing: 0) {
            ForEach(0..<3, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(0..<3, id: \.self) { col in
                        let idx = row * 3 + col
                        let mark = PulseWave7Logic.cellAt(match.board, idx)
                        Text(mark == "X" ? "✕" : mark == "O" ? "◯" : " ")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(mark == "X" ? PulseTheme.color(named: "emerald") : Color.orange)
                            .frame(width: 56, height: 56)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if myTurn && mark == " " && match.status == "active" { onMove(idx) }
                            }
                    }
                }
            }
        }
        .overlay(strike)
        .background(Color.primary.opacity(0.03))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var strike: some View {
        GeometryReader { geo in
            if let (_, line) = PulseWave7Logic.detectWinLine(match.board) {
                let (a, c) = (line[0], line[2])
                let cell = geo.size.width / 3
                Path { p in
                    p.move(to: CGPoint(x: CGFloat(a % 3) * cell + cell / 2, y: CGFloat(a / 3) * cell + cell / 2))
                    p.addLine(to: CGPoint(x: CGFloat(c % 3) * cell + cell / 2, y: CGFloat(c / 3) * cell + cell / 2))
                }
                .stroke(PulseTheme.color(named: "emerald"), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            }
        }
        .allowsHitTesting(false)
    }
}

struct Wave7GameCreateSheet: View {
    let members: [(id: String, name: String)]
    let onCreate: (String?) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button {
                    onCreate(nil)
                } label: {
                    Label("Open challenge", systemImage: "bolt")
                }
                if !members.isEmpty {
                    Section("Or invite someone") {
                        ForEach(members, id: \.id) { member in
                            Button {
                                onCreate(member.id)
                            } label: {
                                Text(member.name)
                            }
                        }
                    }
                }
            }
            .navigationTitle("⚔️ Tic-tac-toe")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - Tournament (F-RO-08)

struct Wave7TournamentCard: View {
    let tournamentId: String
    let name: String
    let viewerId: String
    let isAdmin: Bool
    let load: (String) async -> WireTournamentSummary?
    let onJoin: () -> Void
    let onFinish: () -> Void

    @State private var tournament: WireTournamentSummary?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("🏆").font(.subheadline)
                Text(name.isEmpty ? "Tournament" : name)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                Text(running ? "\(tournament?.playerCount ?? tournament?.entries?.count ?? 0) players" : "finished")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            ForEach(Array((tournament?.entries ?? []).prefix(3).enumerated()), id: \.offset) { i, entry in
                HStack {
                    Text("\(i + 1).")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(PulseTheme.color(named: "emerald"))
                    Text(entry.name ?? "Player")
                        .font(.caption)
                        .lineLimit(1)
                    Spacer()
                    Text("\(entry.points ?? 0)p · \(entry.wins ?? 0)W \(entry.losses ?? 0)L \(entry.draws ?? 0)D")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 8) {
                if running && !joined {
                    Button("Join", action: onJoin)
                        .buttonStyle(.borderedProminent)
                }
                if running && isAdmin {
                    Button("Finish season", action: onFinish)
                        .buttonStyle(.bordered)
                }
            }
        }
        .padding(12)
        .background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
        .task(id: tournamentId) {
            tournament = await load(tournamentId)
        }
    }

    private var running: Bool { tournament?.status == "running" }
    private var joined: Bool {
        (tournament?.entries ?? []).contains { $0.userId == viewerId }
    }
}

struct Wave7TournamentCreateSheet: View {
    let onCreate: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Season name (1–40)", text: $name)
                } footer: {
                    Text("Season runs until someone finishes it. Match wins feed the standings (+25 XP).")
                }
                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
                Button("Start season") {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    if trimmed.isEmpty {
                        error = "name must be between 1 and 40 characters."
                    } else {
                        onCreate(trimmed)
                    }
                }
            }
            .navigationTitle("🏆 New tournament")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - Kanban (F-RO-04)

struct Wave7KanbanSheet: View {
    let conversationId: String
    let viewerId: String
    let isAdmin: Bool
    let prefillTitle: String?
    let loadBoard: () async -> WireKanbanPage?
    let onAddCard: (String, String, String?) -> Void
    let onMoveCard: (String, String) -> Void
    let onDeleteCard: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var cards: [WireKanbanCard] = []
    @State private var addColumn: String?
    @State private var addTitle = ""
    @State private var ticking = true

    var body: some View {
        NavigationStack {
            Form {
                ForEach(PulseWave7Logic.kanbanColumns, id: \.self) { col in
                    Section {
                        ForEach(cards.filter { $0.column == col }.sorted { ($0.position ?? 0) < ($1.position ?? 0) }, id: \.id) { card in
                            cardRow(card, col: col)
                        }
                        if addColumn == col {
                            TextField("Task title", text: $addTitle)
                            Button("Add card") {
                                let trimmed = addTitle.trimmingCharacters(in: .whitespaces)
                                if !trimmed.isEmpty {
                                    onAddCard(trimmed, col, nil)
                                    addTitle = ""
                                    addColumn = nil
                                }
                            }
                        }
                    } header: {
                        HStack {
                            Text(headerLabel(col) + " · \(cards.filter { $0.column == col }.count)")
                            Spacer()
                            Button(addColumn == col ? "Cancel" : "+ Add") {
                                addColumn = addColumn == col ? nil : col
                                addTitle = prefillTitle ?? ""
                            }
                            .font(.caption)
                        }
                    }
                }
            }
            .navigationTitle("🗂️ Kanban")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(ticking ? "Pause" : "Live") { ticking.toggle() }
                }
            }
            .task(id: ticking) {
                // 1.5 s poll while open (web kanban-sheet.tsx:65), paused by the toggle.
                while ticking && !Task.isCancelled {
                    if let page = await loadBoard() { cards = page.cards }
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                }
            }
        }
    }

    private func headerLabel(_ col: String) -> String {
        switch col {
        case "todo": return "To do"
        case "doing": return "Doing"
        default: return "Done"
        }
    }

    private func cardRow(_ card: WireKanbanCard, col: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(card.title ?? "")
                    .font(.subheadline)
                    .lineLimit(2)
                let who = [card.assigneeName.map { "→ \($0)" }, card.createdByName.map { "by \($0)" }]
                    .compactMap { $0 }.joined(separator: " · ")
                if !who.isEmpty {
                    Text(who).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if col != "todo" {
                Button { onMoveCard(card.id, "todo") } label: { Text("‹").font(.title3) }
                    .buttonStyle(.borderless)
            }
            if col != "doing" && col != "done" {
                Button { onMoveCard(card.id, "doing") } label: { Text("›").font(.title3) }
                    .buttonStyle(.borderless)
            }
            if col == "doing" {
                Button { onMoveCard(card.id, "done") } label: { Text("›").font(.title3) }
                    .buttonStyle(.borderless)
            }
            if card.createdById == viewerId || isAdmin {
                Button { onDeleteCard(card.id) } label: { Text("✕").foregroundStyle(.red) }
                    .buttonStyle(.borderless)
            }
        }
    }
}

// MARK: - Whiteboard (F-RO-03)

struct Wave7WhiteboardSheet: View {
    let conversationId: String
    let viewerId: String
    let load: (Int64?) async -> WireWhiteboardPage?
    /// R1-W2G D44 — async WITH a verdict: the draft store clears a stroke
    /// only when its server sync confirms (see Wave7RoomActions.postStrokes).
    let onStrokes: ([WireWhiteboardStrokePost]) async -> Bool
    let onUndo: () -> Void
    let onClear: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var remoteStrokes: [WireWhiteboardStroke] = []
    @State private var lastServerTime: Int64 = 0
    @State private var resetAt: Int64 = 0
    @State private var localStrokes: [(String, Double, [[Double]])] = []
    /// R1-W2G D44 — single-flight flush: while a batch POST is in flight,
    /// fresh strokes (already draft-persisted at draw time) ride the NEXT
    /// pass instead of racing the in-flight verdict.
    @State private var flushing = false
    @State private var color = "#22c55e"
    @State private var width: Double = 3
    @State private var confirmClear = false

    private let colors = ["#22c55e", "#0ea5e9", "#f59e0b", "#ef4444", "#a855f7", "#e2e8f0"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                HStack {
                    Text("🖌️ Whiteboard").font(.headline)
                    Spacer()
                    Button("Undo", action: onUndo)
                    Button(confirmClear ? "Tap again to clear" : "Clear") {
                        if confirmClear { onClear() } else { confirmClear = true }
                    }
                    .foregroundStyle(confirmClear ? Color.red : Color.accentColor)
                }
                .padding(.horizontal, 14)

                HStack(spacing: 8) {
                    ForEach(colors, id: \.self) { c in
                        Circle()
                            .fill(Color(hex: c))
                            .frame(width: 26, height: 26)
                            .overlay(Circle().stroke(Color.primary, lineWidth: color == c ? 3 : 1))
                            .onTapGesture { color = c }
                    }
                    Slider(value: $width, in: 0.5...40)
                }
                .padding(.horizontal, 14)

                Wave7WhiteboardCanvas(
                    remote: remoteStrokes,
                    local: localStrokes,
                    color: color,
                    width: width,
                    onFinish: { pts in
                        if pts.count >= 2 {
                            localStrokes.append((color, width, pts))
                            // R1-W2G D44 — persist the pending stroke BEFORE
                            // any sync attempt so a crash/kill mid-draw
                            // never loses it (web :527-529 draws the pending
                            // stroke instantly; the native adds the durable
                            // store the web does not have).
                            PulseWhiteboardDraft.append(
                                WireWhiteboardStrokePost(color: color, width: width, points: pts),
                                conversationId: conversationId,
                            )
                            flushPending()
                        }
                    },
                )
                .aspectRatio(1, contentMode: .fit)
                .padding(.horizontal, 14)

                Text("Strokes sync live (~1 s). Coordinates are normalized 0..1 on the server.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .task {
                // R1-W2G D44 — restore the unsynced draft FIRST (a crash/kill
                // mid-draw must not lose strokes), then the full snapshot
                // drops restored copies the server already has (their first
                // POST landed before the crash — exact color+width+points).
                localStrokes = PulseWhiteboardDraft.strokes(conversationId: conversationId)
                    .map { stroke in (stroke.color, stroke.width, stroke.points) }
                // Full snapshot, then since-delta poll at 900 ms (web whiteboard-sheet.tsx:66).
                if let page = await load(nil) {
                    remoteStrokes = page.strokes ?? []
                    lastServerTime = page.serverTime ?? 0
                    resetAt = page.resetAt ?? 0
                    let kept = PulseWhiteboardDraft.droppingSynced(
                        PulseWhiteboardDraft.strokes(conversationId: conversationId),
                        snapshot: remoteStrokes,
                    )
                    PulseWhiteboardDraft.replaceAll(conversationId: conversationId, strokes: kept)
                    localStrokes = kept.map { stroke in (stroke.color, stroke.width, stroke.points) }
                }
                flushPending()
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 900_000_000)
                    if let page = await load(lastServerTime) {
                        let st = page.serverTime ?? 0
                        if st > lastServerTime { lastServerTime = st }
                        if let r = page.resetAt, r > resetAt {
                            resetAt = r
                            remoteStrokes = []
                            localStrokes = []
                            // R1-W2G D44 — the board was reset server-side;
                            // the pending draft is gone with it.
                            PulseWhiteboardDraft.clear(conversationId: conversationId)
                        }
                        remoteStrokes.append(contentsOf: page.strokes ?? [])
                    }
                }
            }
        }
    }

    /// R1-W2G D44 — the draft store IS the pending queue (web pending
    /// semantics, whiteboard-sheet.tsx:527-529): batches of ≤ 40 POST, and
    /// each batch leaves the draft only on the server verdict. A failed
    /// batch is removed honestly (:497-500) — the strokes never reached the
    /// server, so the canvas drops them too instead of faking a sync (the
    /// postStrokes toast says why). The draft is re-read every pass, so
    /// strokes drawn mid-flight simply ride the next batch.
    private func flushPending() {
        guard !flushing else { return }
        flushing = true
        Task { @MainActor in
            defer { flushing = false }
            while true {
                let pending = PulseWhiteboardDraft.strokes(conversationId: conversationId)
                guard !pending.isEmpty else { break }
                let batch = Array(pending.prefix(40))
                let synced = await onStrokes(batch)
                PulseWhiteboardDraft.removeFirst(conversationId: conversationId, count: batch.count)
                localStrokes = PulseWhiteboardDraft.strokes(conversationId: conversationId)
                    .map { stroke in (stroke.color, stroke.width, stroke.points) }
                if !synced { break }
            }
        }
    }
}

private struct Wave7WhiteboardCanvas: View {
    let remote: [WireWhiteboardStroke]
    let local: [(String, Double, [[Double]])]
    let color: String
    let width: Double
    let onFinish: ([[Double]]) -> Void

    @State private var current: [[Double]] = []
    @GestureState private var drag: DragGesture.Value?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                canvas(size: geo.size)
                Path { p in
                    guard let drag else { return }
                    guard let first = current.first else { return }
                    p.move(to: CGPoint(x: first[0] * geo.size.width, y: first[1] * geo.size.height))
                    p.addLine(to: CGPoint(x: drag.location.x / geo.size.width * geo.size.width, y: drag.location.y))
                    for pt in current.dropFirst() {
                        p.addLine(to: CGPoint(x: pt[0] * geo.size.width, y: pt[1] * geo.size.height))
                    }
                    p.addLine(to: drag.location)
                }
                .stroke(Color(hex: color), style: StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round))
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($drag) { value, state, _ in state = value }
                    .onChanged { value in
                        let x = Double(value.location.x / geo.size.width)
                        let y = Double(value.location.y / geo.size.height)
                        let clamped = [min(max(x, 0), 1), min(max(y, 0), 1)]
                        if current.isEmpty { current = [clamped] } else { current.append(clamped) }
                    }
                    .onEnded { _ in
                        onFinish(current)
                        current = []
                    },
            )
        }
    }

    private func canvas(size: CGSize) -> some View {
        Canvas { context, _ in
            func draw(_ pts: [[Double]], _ c: String, _ w: Double) {
                guard pts.count >= 2 else { return }
                var path = Path()
                path.move(to: CGPoint(x: pts[0][0] * size.width, y: pts[0][1] * size.height))
                for pt in pts.dropFirst() {
                    path.addLine(to: CGPoint(x: pt[0] * size.width, y: pt[1] * size.height))
                }
                context.stroke(path, with: .color(Color(hex: c)), style: StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round))
            }
            for s in remote {
                draw(s.points ?? [], s.color ?? "#000000", s.width ?? 3)
            }
            for (c, w, pts) in local {
                draw(pts, c, w)
            }
        }
    }
}

extension Color {
    init(hex: String) {
        let clean = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        var rgb: UInt64 = 0
        Scanner(string: clean).scanHexInt64(&rgb)
        let r = Double((rgb >> 16) & 0xFF) / 255
        let g = Double((rgb >> 8) & 0xFF) / 255
        let b = Double(rgb & 0xFF) / 255
        self.init(red: r, green: g, blue: b)
    }
}

// MARK: - Events (F-RO-05)

struct Wave7EventsSheet: View {
    let conversationId: String
    let viewerId: String
    let isAdmin: Bool
    let loadEvents: () async -> [WireGroupEvent]?
    let onCreate: (String, String, String?, String?) -> Void
    let onRsvp: (String, String) -> Void
    let onCheckin: (String) -> Void
    let onDelete: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var events: [WireGroupEvent] = []
    @State private var creating = false
    @State private var title = ""
    @State private var dateText = ""
    @State private var location = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button(creating ? "Close" : "+ New event") { creating.toggle() }
                    if creating {
                        TextField("Event title", text: $title)
                        TextField("Date (YYYY-MM-DD HH:mm)", text: $dateText)
                        TextField("Location (optional)", text: $location)
                        if let error {
                            Text(error).font(.footnote).foregroundStyle(.red)
                        }
                        Button("Schedule event") {
                            let trimmed = title.trimmingCharacters(in: .whitespaces)
                            guard !trimmed.isEmpty else {
                                error = "title must be 1-120 characters."
                                return
                            }
                            guard let iso = Self.parseDate(dateText) else {
                                error = "startsAt must be a parseable ISO date string."
                                return
                            }
                            error = nil
                            onCreate(trimmed, iso, nil, location.isEmpty ? nil : location)
                            title = ""; dateText = ""; location = ""; creating = false
                        }
                    }
                }
                if events.isEmpty {
                    Section { Text("No events yet — schedule the first one.").foregroundStyle(.secondary) }
                }
                ForEach(events, id: \.id) { event in
                    Section {
                        HStack {
                            Text(event.title ?? "")
                                .font(.subheadline.weight(.semibold))
                            Spacer()
                            if event.createdById == viewerId || isAdmin {
                                Button { onDelete(event.id) } label: { Text("✕").foregroundStyle(.red) }
                                    .buttonStyle(.borderless)
                            }
                        }
                        if let location = event.location, !location.isEmpty {
                            Label(location, systemImage: "mappin").font(.caption).foregroundStyle(.secondary)
                        }
                        Text(Self.formatTime(event.startsAt)).font(.caption).foregroundStyle(.secondary)
                        HStack(spacing: 6) {
                            ForEach(PulseWave7Logic.rsvpStatuses, id: \.self) { status in
                                let selected = event.myStatus == status
                                Button {
                                    onRsvp(event.id, status)
                                } label: {
                                    Text("\(Self.statusLabel(status)) \(Self.count(event, status))")
                                        .font(.caption)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 5)
                                        .background(
                                            selected ? PulseTheme.color(named: "emerald").opacity(0.25) : Color.primary.opacity(0.06),
                                            in: Capsule(),
                                        )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        if let my = event.myStatus, my != "no", inWindow(event) {
                            Button("Check in · +15 XP") { onCheckin(event.id) }
                                .buttonStyle(.borderedProminent)
                        }
                    }
                }
            }
            .navigationTitle("📅 Events")
            .navigationBarTitleDisplayMode(.inline)
            .task {
                while !Task.isCancelled {
                    if let list = await loadEvents() { events = list }
                    try? await Task.sleep(nanoseconds: 4_000_000_000)
                }
            }
        }
    }

    private func inWindow(_ event: WireGroupEvent) -> Bool {
        guard let iso = event.startsAt, let ms = Wave7RoomActions.epochMs(from: iso) else { return false }
        return PulseWave7Logic.checkinWindowOpen(startsAtEpochMs: ms, nowEpochMs: Int64(Date().timeIntervalSince1970) * 1000)
    }

    static func statusLabel(_ s: String) -> String {
        switch s {
        case "going": return "Going"
        case "maybe": return "Maybe"
        default: return "Can't"
        }
    }

    static func count(_ event: WireGroupEvent, _ status: String) -> Int {
        switch status {
        case "going": return event.counts?.going ?? 0
        case "maybe": return event.counts?.maybe ?? 0
        default: return event.counts?.no ?? 0
        }
    }

    /// "YYYY-MM-DD HH:mm" (local) or ISO → UTC ISO-8601 wire form.
    static func parseDate(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if let d = ISO8601DateFormatter().date(from: trimmed) {
            return ISO8601DateFormatter().string(from: d)
        }
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm"
        df.locale = Locale(identifier: "en_US_POSIX")
        if let d = df.date(from: trimmed) {
            return ISO8601DateFormatter().string(from: d)
        }
        df.dateFormat = "yyyy-MM-dd"
        if let d = df.date(from: trimmed) {
            return ISO8601DateFormatter().string(from: d)
        }
        return nil
    }

    static func formatTime(_ iso: String?) -> String {
        guard let iso, let ms = Wave7RoomActions.epochMs(from: iso) else { return "time TBD" }
        let d = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        let f = DateFormatter()
        f.dateFormat = "EEE, MMM d · HH:mm"
        return f.string(from: d)
    }
}

// MARK: - Reminders (F-RO-06)

struct Wave7RemindersSheet: View {
    let loadReminders: () async -> [WireReminderItem]?
    let onCreate: (String, String, WireChatMessage?) -> Void
    let onResolve: (String) -> Void
    let onDelete: (String) -> Void
    let anchored: WireChatMessage?
    @Environment(\.dismiss) private var dismiss

    @State private var items: [WireReminderItem] = []
    @State private var note = ""
    @State private var whenText = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(anchored != nil ? "Note (anchored to a message)" : "Note", text: $note)
                    TextField("When — \"in 30m\", \"tomorrow\", \"2026-01-20 09:00\"", text: $whenText)
                    if let error {
                        Text(error).font(.footnote).foregroundStyle(.red)
                    }
                    Button("Set reminder") {
                        let nowMs = Int64(Date().timeIntervalSince1970) * 1000
                        let parsed = PulseWave7Logic.parseRelativeReminder(whenText, nowEpochMs: nowMs)
                        var resolvedIso: String?
                        if let parsed {
                            resolvedIso = Self.iso(fromMs: parsed.remindAtEpochMs)
                        }
                        if resolvedIso == nil {
                            resolvedIso = Self.parseIsoLoose(whenText)
                        }
                        let finalNote = parsed?.note ?? note.trimmingCharacters(in: .whitespaces)
                        if resolvedIso == nil {
                            error = "No sane time found — try \"in 30m\", \"tomorrow\" or an exact date."
                        } else if finalNote.isEmpty {
                            error = "Note can't be empty."
                        } else {
                            error = nil
                            onCreate(finalNote, resolvedIso ?? "", anchored)
                            note = ""; whenText = ""
                        }
                    }
                } footer: {
                    Text("Fires locally even offline; resolves to the server when online.")
                }
                if items.isEmpty {
                    Section { Text("No reminders yet.").foregroundStyle(.secondary) }
                }
                ForEach(items, id: \.id) { item in
                    Section {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.note?.isEmpty == false ? item.note! : "Reminder")
                                    .font(.subheadline)
                                    .lineLimit(1)
                                Text(subtitle(item))
                                    .font(.caption)
                                    .foregroundStyle(isDue(item) ? PulseTheme.color(named: "emerald") : Color.secondary)
                            }
                            Spacer()
                            if item.firedAt == nil {
                                Button { onResolve(item.id) } label: { Text("✓").foregroundStyle(PulseTheme.color(named: "emerald")) }
                                    .buttonStyle(.borderless)
                            }
                            Button { onDelete(item.id) } label: { Text("✕").foregroundStyle(.red) }
                                .buttonStyle(.borderless)
                        }
                    }
                }
            }
            .navigationTitle("⏰ Reminders")
            .navigationBarTitleDisplayMode(.inline)
            .task {
                while !Task.isCancelled {
                    if let list = await loadReminders() { items = list }
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                }
            }
        }
    }

    private func isDue(_ item: WireReminderItem) -> Bool {
        guard item.firedAt == nil, let iso = item.remindAt, let ms = Wave7RoomActions.epochMs(from: iso) else { return false }
        return ms <= Int64(Date().timeIntervalSince1970) * 1000
    }

    private func subtitle(_ item: WireReminderItem) -> String {
        let time = Wave7EventsSheet.formatTime(item.remindAt)
        let due = isDue(item) ? "DUE NOW — " : ""
        let room: String
        if let name = item.conversation?.name, !name.isEmpty {
            room = " · \(name)"
        } else {
            room = ""
        }
        return due + time + room
    }

    static func iso(fromMs ms: Int64) -> String {
        ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }

    /// Loose absolute parse for the sheet ("2026-01-20 09:00" / "2026-01-20" / ISO).
    static func parseIsoLoose(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if let d = ISO8601DateFormatter().date(from: trimmed) {
            return ISO8601DateFormatter().string(from: d)
        }
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd HH:mm"
        if let d = df.date(from: trimmed) { return ISO8601DateFormatter().string(from: d) }
        df.dateFormat = "yyyy-MM-dd"
        if let d = df.date(from: trimmed) { return ISO8601DateFormatter().string(from: d) }
        return nil
    }
}

// MARK: - Leaderboard (F-RO-09)

struct Wave7LeaderboardSheet: View {
    let loadRoom: () async -> WireLeaderboardPage?
    let loadGlobal: () async -> WireLeaderboardPage?
    @Environment(\.dismiss) private var dismiss

    @State private var scopeGlobal = false
    @State private var rows: [WireLeaderboardRow] = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button(scopeGlobal ? "This room" : "Global") { scopeGlobal.toggle() }
                }
                if rows.isEmpty {
                    Section { Text("No standings yet — send messages, win games, join tournaments.").foregroundStyle(.secondary) }
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                    HStack {
                        Text("#\(i + 1)")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(PulseTheme.color(named: "emerald"))
                            .frame(width: 40, alignment: .leading)
                        Text(row.name ?? "")
                            .lineLimit(1)
                        Spacer()
                        Text("\(row.tournamentPoints ?? 0)p · \(row.gameWins ?? 0)W · \(row.xp ?? 0) XP")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("🏅 Leaderboard")
            .navigationBarTitleDisplayMode(.inline)
            .task(id: scopeGlobal) {
                let page = await (scopeGlobal ? loadGlobal() : loadRoom())
                rows = page?.rows ?? []
            }
        }
    }
}
