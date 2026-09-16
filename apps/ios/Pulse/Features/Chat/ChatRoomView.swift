import SwiftUI
import UIKit
import Combine
import PhotosUI

/// Chat room — native rebuild of the web conversation surface. Same outcome:
/// emerald gradient bubbles for the viewer, material bubbles for others,
/// reply quotes, grouped reaction chips, voice waveform chips, read state,
/// live typing with drifting dots. Apple mechanics: ScrollViewReader anchor,
/// contextMenu reactions, FocusState composer, socket-driven updates.
/// Wave 8 — the room consumes the server-synced PulsePrefs blob: the
/// wallpaper wash background, the bubble corner token (md/lg/pill) and the
/// row density spacing.
struct ChatRoomView: View {
    let conversation: WireConversationSummary
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs
    /// Global-search jump — the room scrolls to + flashes this message after
    /// its initial load (bounded history expansion if it sits out of window).
    var jumpMessageId: String? = nil

    @State private var viewModel: RoomViewModel?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let viewModel {
                RoomContent(
                    conversation: conversation,
                    session: session,
                    prefs: prefs,
                    viewModel: viewModel,
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(roomTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .onAppear {
            if viewModel == nil {
                viewModel = RoomViewModel(conversation: conversation, session: session, initialJumpMessageId: jumpMessageId)
            }
            // The dock hides itself while a room owns the screen (web §12).
            session.roomVisible = true
            // Wave 8 — the incoming-attention gate treats THIS room as read.
            session.activeRoomId = conversation.id
        }
        .onDisappear {
            session.roomVisible = false
            if session.activeRoomId == conversation.id {
                session.activeRoomId = nil
            }
        }
    }

    private var roomTitle: String {
        conversation.name
            ?? conversation.members.first(where: { $0.id != session.viewer?.id })?.name
            ?? conversation.members.first?.name
            ?? "Conversation"
    }
}

/// One river row — day chip + bubble + context menu + pagination trigger.
/// Extracted from RoomContent so the Swift type-checker sees a bounded
/// expression (Wave 1 row carries media/thread/flash wiring).
private struct RoomMessageRow: View {
    let index: Int
    let previous: WireChatMessage?
    let message: WireChatMessage
    @ObservedObject var session: PulseSession
    let conversation: WireConversationSummary
    @ObservedObject var viewModel: RoomViewModel
    let colorOf: (String) -> Color
    /// Wave 8 — prefs bubble corner token (md/lg/pill).
    let bubbleRadius: PulseBubbleRadius
    let onOpenImage: (WireChatMessage) -> Void
    let onOpenFile: (WireChatMessage) -> Void
    let onOpenThread: (WireChatMessage) -> Void
    let onForward: (WireChatMessage) -> Void
    let onInfo: (WireChatMessage) -> Void
    /// Wave 2 view-once — instant reveal (lightbox) + POST /viewed burn.
    let onViewOnce: (WireChatMessage) -> Void
    // ── Wave 7 — rich-object cards + message actions ──
    let wave7: Wave7RoomActions

    var body: some View {
        Group {
            // Day chip when the calendar day changes between rows.
            if index == 0 || PulseFormat.dayLabel(previous?.createdAt) != PulseFormat.dayLabel(message.createdAt) {
                CapsuleLabel(PulseFormat.dayLabel(message.createdAt))
                    .padding(.vertical, 4)
            }
            BubbleView(
                message: message,
                mine: message.senderId == session.viewer?.id,
                groupChat: conversation.isGroup,
                viewerColor: { colorOf(message.senderId) },
                seen: viewModel.isSeen(message),
                flashing: viewModel.flashMessageId == message.id,
                replyCount: viewModel.replyCount(for: message),
                bubbleRadius: bubbleRadius,
                onOpenImage: onOpenImage,
                onOpenFile: onOpenFile,
                onOpenThread: onOpenThread,
                viewerId: session.viewer?.id,
                voicePlayback: viewModel.playback.state,
                voiceRate: viewModel.voiceRate,
                onVoicePlayToggle: { viewModel.toggleVoicePlayback(message, session: session) },
                onVoiceRateCycle: { viewModel.cycleVoiceRate() },
                onTranscribe: { viewModel.transcribeVoice(message, session: session) },
                onPollVote: { optionId in
                    viewModel.votePoll(pollID: message.poll?.id ?? "", optionID: optionId, session: session)
                },
                onPollClose: {
                    if let pollId = message.poll?.id {
                        viewModel.closePoll(pollID: pollId, session: session)
                    }
                },
                onViewOnceOpen: onViewOnce,
                wave7: wave7,
            )
            .contextMenu { contextMenu }
            .onAppear {
                // Oldest rendered row reaching the viewport = page older
                // history (the VM gates reentrancy/hasMore).
                if index == 0 {
                    viewModel.loadOlder(session: session)
                }
            }
        }
    }

    @ViewBuilder
    private var contextMenu: some View {
        ForEach(ReactionPalette.emojis, id: \.self) { emoji in
            Button {
                viewModel.react(message, emoji: emoji, session: session)
            } label: {
                Text(emoji)
            }
        }
        Button {
            viewModel.beginReply(to: message)
        } label: {
            Label("Reply", systemImage: "arrowshape.turn.up.left.fill")
        }
        if message.parentId == nil {
            Button {
                onOpenThread(message)
            } label: {
                Label("Reply in thread", systemImage: "bubble.left.and.bubble.right.fill")
            }
        }
        if message.senderId == session.viewer?.id && message.kind == "text" && message.deletedAt == nil {
            Button {
                viewModel.beginEdit(message)
            } label: {
                Label("Edit", systemImage: "pencil")
            }
        }
        Button {
            UIPasteboard.general.string = message.content
        } label: {
            Label("Copy", systemImage: "doc.on.doc.fill")
        }
        Button {
            viewModel.togglePin(message, session: session)
        } label: {
            Label(message.pinnedAt == nil ? "Pin" : "Unpin", systemImage: "pin")
        }
        Button {
            viewModel.toggleSave(message, session: session)
        } label: {
            Label("Save", systemImage: "bookmark")
        }
        Button {
            onForward(message)
        } label: {
            Label("Forward", systemImage: "arrowshape.turn.up.right.fill")
        }
        if message.senderId == session.viewer?.id && message.deletedAt == nil {
            Button(role: .destructive) {
                viewModel.delete(message, session: session)
            } label: {
                Label("Delete", systemImage: "trash.fill")
            }
        }
        Button {
            onInfo(message)
        } label: {
            Label("Info", systemImage: "info.circle")
        }
        // ── Wave 7 — message→kanban card + per-message reminder ──
        if message.kind == "text" && message.deletedAt == nil {
            Button {
                wave7.kanbanSourceMessage = message
                wave7.kanbanOpen = true
            } label: {
                Label("Add to board", systemImage: "square.stack.3d.up.fill")
            }
        }
        Button {
            wave7.reminderAnchor = message
            wave7.remindersOpen = true
        } label: {
            Label("Remind me…", systemImage: "alarm")
        }
    }
}

private struct RoomContent: View {
    let conversation: WireConversationSummary
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs
    @ObservedObject var viewModel: RoomViewModel

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var composerFocused: Bool

    // Wave 1 surfaces — threads, forward, info, pins, lightbox, QuickLook,
    // room search, and the media attach flow (photo picker + document).
    @State private var threadRoot: WireChatMessage?
    @State private var forwardSource: WireChatMessage?
    @State private var infoTarget: WireChatMessage?
    @State private var pinsOpen = false
    @State private var lightbox: MediaLightboxTarget?
    @State private var quicklook: QuickLookTarget?
    @State private var searchOpen = false
    @State private var searchQuery = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var showFileImporter = false
    // Wave 2 — poll builder sheet.
    @State private var pollBuilderOpen = false
    // ── Wave 7 — collaboration & hub surfaces ──
    @StateObject private var wave7 = Wave7RoomActions()
    @State private var leaderboardOpen = false
    // Wave 6 — DM safety-number sheet (F-CP-07/08) + the @-suggester (F-SM-04).
    @StateObject private var safetyBadges = PulseSafetyBadgeCache.shared
    @State private var safetyOpen = false

    /// F-CH-04 — broadcast composer lock: broadcastMode on + the viewer is
    /// NOT an admin (server 403s the post; the web hides the composer too).
    private var broadcastLocked: Bool {
        guard conversation.broadcastMode == true else { return false }
        let role = conversation.members.first(where: { $0.id == session.viewer?.id })?.role
        return role != "admin"
    }

    /// F-SM-04 — the @-token active right now (draft tail), if any.
    private var mentionSuggestion: (token: String, atIndex: Int)? {
        broadcastLocked ? nil : PulseMentions.activeToken(in: viewModel.draft)
    }

    private var mentionCandidates: [WireConversationMember] {
        guard let suggestion = mentionSuggestion else { return [] }
        let names = conversation.members.map(\.name)
        let allowed = Set(PulseMentions.matches(for: suggestion.token, in: names))
        return conversation.members.filter { allowed.contains($0.name) }
    }

    private func pickMention(_ member: WireConversationMember) {
        guard let suggestion = mentionSuggestion else { return }
        let insertion = PulseMentions.insertion(for: member.name)
        let draft = viewModel.draft
        // The token is anchored at the draft tail — replace from the @ to end.
        let prefix = draft.prefix(suggestion.atIndex)
        PulseHaptics.tap()
        viewModel.draft = prefix + insertion
        composerFocused = true
    }

    /// Wave 8 — the prefs wallpaper wash behind the whole room; 'none'
    /// renders nothing and the plain page look stays.
    private var wallpaperWash: some View {
        Group {
            if let wash = prefs.wallpaper.wash(dark: colorScheme == .dark) {
                wash
            }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Wave 2 topics — group rooms only (General = whole room, spec §1 row 9).
            if conversation.isGroup {
                TopicBar(
                    topics: viewModel.topics,
                    activeTopicId: viewModel.activeTopicId,
                    onSelect: { viewModel.setActiveTopic($0, session: session) },
                    onCreate: { name, emoji in
                        viewModel.createTopic(name: name, emoji: emoji, session: session)
                    },
                )
            }
            pinnedBanner
            if searchOpen {
                roomSearchPanel
            }
            messagesList
            // F-SM-04 — @-suggester popover (roster, top-5 prefix match).
            if !mentionCandidates.isEmpty {
                mentionPopover()
            }
            if !session.connected {
                offlineStrip
            }
            if let error = viewModel.errorText {
                errorStrip(error)
            }
            composer
        }
        .background(alignment: .top) {
            // Wave 8 — prefs wallpaper behind the whole room (web chat-room
            // layered wallpaper parity; 'none' keeps the plain wash).
            wallpaperWash
                .ignoresSafeArea()
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // W5-f — the voice room entry (VR-1): mic tints active while
                // any room of THIS conversation is joined; the "Voice · N
                // live" pill shows when joined + surface closed.
                if let rooms = session.voiceRooms {
                    VoiceRoomChatEntry(model: rooms, conversationId: conversation.id)
                }
            }
            // Wave 7 F-RO-09 — room leaderboard (web group-info-sheet parity).
            if conversation.isGroup {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        leaderboardOpen = true
                    } label: {
                        Image(systemName: "trophy")
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Leaderboard")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    searchOpen.toggle()
                    if !searchOpen { searchQuery = "" }
                } label: {
                    Image(systemName: searchOpen ? "xmark.circle.fill" : "magnifyingglass")
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Search messages")
            }
            // Wave 6 — DM-only safety entry (F-CP-07): ShieldCheck opens the
            // 60-digit sheet; the emerald badge shows while verified (F-CP-08).
            if let partner = dmPartner {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        PulseHaptics.tap()
                        safetyOpen = true
                    } label: {
                        Image(systemName: safetyBadges.isVerified(partner.id) ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                            .foregroundStyle(safetyBadges.isVerified(partner.id) ? PulseTheme.emerald : PulseTheme.textSecondary)
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel(safetyBadges.isVerified(partner.id) ? "Verified — open safety number" : "Open safety number")
                }
            }
        }
        .sheet(isPresented: $safetyOpen) {
            if let partner = dmPartner {
                SafetySheetView(session: session, peer: partner)
            }
        }
        .task {
            // Wave 6 — populate the verified-badge cache for this DM (one
            // quiet GET on room open; the header badge reads the cache).
            guard let partner = dmPartner else { return }
            if let state = try? await session.api.safetyState(peerId: partner.id) {
                safetyBadges.mark(partner.id, verified: state.verified)
            }
        }
        .sheet(item: $threadRoot) { root in
            ThreadView(conversation: conversation, root: root, session: session)
        }
        .sheet(item: $forwardSource) { source in
            ForwardSheet(source: source, session: session)
        }
        .sheet(item: $infoTarget) { message in
            MessageInfoSheet(message: message, conversation: conversation)
        }
        .sheet(isPresented: $pinsOpen) {
            pinsList
        }
        .sheet(isPresented: $pollBuilderOpen) {
            PollBuilderSheet(viewModel: viewModel, session: session)
        }
        // ── Wave 7 sheet hosts ──
        .sheet(isPresented: $wave7.redPacketCreateOpen) {
            Wave7RedPacketCreateSheet { total, count, note in
                wave7.createRedPacket(api: session.api, conversationId: conversation.id, total: total, count: count, note: note)
            }
        }
        .sheet(isPresented: $wave7.gameCreateOpen) {
            Wave7GameCreateSheet(
                members: conversation.members
                    .filter { $0.id != session.viewer?.id }
                    .map { (id: $0.id, name: $0.name) },
                onCreate: { opponentId in
                    wave7.createGame(api: session.api, conversationId: conversation.id, opponentId: opponentId)
                },
            )
        }
        .sheet(isPresented: $wave7.tournamentCreateOpen) {
            Wave7TournamentCreateSheet { name in
                wave7.createTournament(api: session.api, conversationId: conversation.id, name: name)
            }
        }
        .sheet(isPresented: $wave7.kanbanOpen) {
            Wave7KanbanSheet(
                conversationId: conversation.id,
                viewerId: session.viewer?.id ?? "",
                isAdmin: false,
                prefillTitle: nil,
                loadBoard: { try? await session.api.kanbanBoard(conversationId: conversation.id) },
                onAddCard: { title, column, assignee in
                    wave7.addCard(api: session.api, conversationId: conversation.id, title: title, column: column, assigneeId: assignee)
                },
                onMoveCard: { cardId, column in
                    wave7.moveCard(api: session.api, cardId: cardId, column: column)
                },
                onDeleteCard: { cardId in
                    wave7.deleteCard(api: session.api, cardId: cardId)
                },
            )
        }
        .sheet(isPresented: $wave7.whiteboardOpen) {
            Wave7WhiteboardSheet(
                conversationId: conversation.id,
                viewerId: session.viewer?.id ?? "",
                load: { since in try? await session.api.whiteboard(conversationId: conversation.id, since: since) },
                onStrokes: { strokes in
                    wave7.postStrokes(api: session.api, conversationId: conversation.id, strokes: strokes)
                },
                onUndo: { wave7.undoStroke(api: session.api, conversationId: conversation.id) },
                onClear: { wave7.clearBoard(api: session.api, conversationId: conversation.id) },
            )
        }
        .sheet(isPresented: $wave7.eventsOpen) {
            Wave7EventsSheet(
                conversationId: conversation.id,
                viewerId: session.viewer?.id ?? "",
                isAdmin: false,
                loadEvents: { try? await session.api.events(conversationId: conversation.id).events },
                onCreate: { title, iso, desc, loc in
                    wave7.createEvent(api: session.api, conversationId: conversation.id, title: title, startsAtIso: iso, description: desc, location: loc)
                },
                onRsvp: { eventId, status in wave7.rsvp(api: session.api, eventId: eventId, status: status) },
                onCheckin: { eventId in wave7.checkin(api: session.api, eventId: eventId) },
                onDelete: { eventId in wave7.deleteEvent(api: session.api, eventId: eventId) },
            )
        }
        .sheet(isPresented: $wave7.remindersOpen) {
            Wave7RemindersSheet(
                loadReminders: { try? await session.api.reminders(dueOnly: false).items },
                onCreate: { note, iso, anchored in
                    wave7.createReminder(api: session.api, conversationId: conversation.id, note: note, remindAtIso: iso, anchored: anchored)
                },
                onResolve: { id in wave7.resolveReminder(api: session.api, id: id) },
                onDelete: { id in wave7.deleteReminder(api: session.api, id: id) },
                anchored: wave7.reminderAnchor,
            )
        }
        .sheet(isPresented: $leaderboardOpen) {
            Wave7LeaderboardSheet(
                loadRoom: { try? await session.api.leaderboard(conversationId: conversation.id) },
                loadGlobal: { try? await session.api.leaderboard(conversationId: nil) },
            )
        }
        .sheet(item: Binding(
            get: { wave7.redPacketDetailId.map { Wave7PacketTarget(id: $0) } },
            set: { wave7.redPacketDetailId = $0?.id },
        )) { target in
            Wave7PacketDetailLoader(packetId: target.id, api: session.api)
        }
        .overlay(alignment: .bottom) {
            if let toast = wave7.toast {
                Text(toast)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background {
                        Capsule().fill(wave7.toastIsError ? Color.red.opacity(0.92) : Color.black.opacity(0.85))
                    }
                    .padding(.bottom, 96)
                    .transition(.opacity)
            }
        }
        .onAppear {
            wave7.onCarrierMessage = { carrier in
                viewModel.injectCarrier(carrier)
            }
            wave7.cardRedPacketLoad = { id in try? await session.api.redPacketDetail(id) }
            wave7.cardGameLoad = { id in try? await session.api.gameDetail(id) }
            wave7.cardTournamentLoad = { id in try? await session.api.tournamentDetail(id) }
            wave7.cardGrab = { id in wave7.grabRedPacket(api: session.api, packetId: id) }
            wave7.cardMove = { matchId, cell in wave7.moveGame(api: session.api, matchId: matchId, cell: cell) }
            wave7.cardJoinGame = { matchId in wave7.joinGame(api: session.api, matchId: matchId) }
            wave7.cardJoinTournament = { tid in wave7.joinTournament(api: session.api, tournamentId: tid) }
            wave7.cardFinishTournament = { tid in wave7.finishTournament(api: session.api, tournamentId: tid) }
            wave7.cardOpenDetail = { id in wave7.redPacketDetailId = id }
        }
        .fullScreenCover(item: $lightbox) { target in
            MediaLightboxView(url: target.url, caption: target.caption)
        }
        .fullScreenCover(item: $quicklook) { target in
            QuickLookView(url: target.url)
                .ignoresSafeArea()
        }
        .onDisappear {
            // Wave 2 — honest teardown: a recording or playback must never
            // outlive the room (roomVisible off / conversation change).
            viewModel.handleRoomDisappeared()
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: PulseMediaSupport.documentTypes,
            allowsMultipleSelection: false,
        ) { result in
            if case .success(let urls) = result, let url = urls.first {
                viewModel.stageDocument(url: url)
            }
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            photoItem = nil
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    viewModel.stageImage(data)
                }
            }
        }
    }

    @State private var showPhotoPicker = false

    /// Wave 6 — the DM partner (safety entry + verified badge are DM-only,
    /// web parity: group rooms carry no safety number).
    private var dmPartner: WireConversationMember? {
        guard !conversation.isGroup else { return nil }
        return conversation.members.first(where: { $0.id != session.viewer?.id })
            ?? conversation.members.first
    }

    // ── F-SM-04 — the @-suggester popover (roster, top-5 prefix match) ──
    private func mentionPopover() -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(mentionCandidates.enumerated()), id: \.element.id) { index, member in
                Button {
                    pickMention(member)
                } label: {
                    HStack(spacing: 10) {
                        RowAvatar(
                            name: member.name,
                            colorName: member.color,
                            photoPath: member.avatar,
                            size: 26,
                        )
                        Text(member.name)
                            .font(.system(size: 13.5, weight: .medium))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                            .lineLimit(1)
                        if member.id == session.viewer?.id {
                            Text("(you)")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(PulseTheme.textTertiary)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .background(index == 0 ? PulseTheme.emerald500.opacity(0.10) : Color.clear)
                .accessibilityLabel("Mention \(member.name)")
            }
        }
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.regularMaterial))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
        .shadow(color: .black.opacity(0.10), radius: 12, y: 4)
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
        .accessibilityLabel("Mention suggestions")
    }

    // ── room search (server q= + local window filter, jump on tap) ──
    private var roomSearchPanel: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("Search this chat", text: $searchQuery)
                    .textFieldStyle(.plain)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .onSubmit { viewModel.searchInRoom(searchQuery, session: session) }
                    .onChange(of: searchQuery) { _, value in
                        viewModel.searchInRoom(value, session: session)
                    }
                if viewModel.searching {
                    ProgressView().controlSize(.small)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.thinMaterial)
            Divider()
            if !viewModel.searchResults.isEmpty {
                List(viewModel.searchResults, id: \.id) { hit in
                    Button {
                        searchOpen = false
                        searchQuery = ""
                        viewModel.jumpTo(hit.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(hit.sender?.name ?? "Message")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(PulseTheme.emerald)
                            HighlightedSnippet(content: hit.content, query: searchQuery)
                                .lineLimit(2)
                        }
                    }
                }
                .listStyle(.plain)
                .frame(maxHeight: 220)
            }
        }
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    // ── pinned banner (newest pin; tap jumps, pin icon opens the list) ──
    @ViewBuilder
    private var pinnedBanner: some View {
        if let newest = viewModel.pins.last {
            Button {
                viewModel.jumpTo(newest.id)
                PulseHaptics.tap()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "pin.fill")
                        .font(.caption)
                        .foregroundStyle(PulseTheme.amber)
                    Text(newest.content.isEmpty ? "Pinned message" : newest.content)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(.thinMaterial)
            }
            .buttonStyle(PulseButtonStyle())
            .simultaneousGesture(TapGesture())
            .overlay(alignment: .trailing) {
                Button {
                    pinsOpen = true
                } label: {
                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 34, height: 34)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("All pinned messages")
                .padding(.trailing, 6)
            }
        }
    }

    private var pinsList: some View {
        NavigationStack {
            List(viewModel.pins, id: \.id) { pin in
                Button {
                    pinsOpen = false
                    viewModel.jumpTo(pin.id)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(pin.sender?.name ?? "Pinned")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(PulseTheme.emerald)
                        Text(pin.content.isEmpty ? "Media message" : pin.content)
                            .font(.subheadline)
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Pinned messages")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { pinsOpen = false }
                }
            }
            .overlay {
                if viewModel.pins.isEmpty {
                    ContentUnavailableCompat(title: "Nothing pinned", systemImage: "pin", note: "Pin important messages to find them fast")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var offlineStrip: some View {
        HStack(spacing: 6) {
            Image(systemName: "wifi.slash")
            Text("Offline — messages will queue")
        }
        .font(.caption2.weight(.medium))
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 5)
        .background(PulseTheme.amber.opacity(0.12))
    }

    // ── messages ─────────────────────────────────────────────
    private var messagesList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                // Wave 8 — prefs density: cozy keeps the Wave-0 rhythm,
                // compact tightens the river (web chat-room density parity).
                LazyVStack(spacing: prefs.density.rowSpacing) {
                    if viewModel.messages.isEmpty && viewModel.phase == .loading {
                        ProgressView("Loading messages…")
                            .padding(.top, 40)
                    }
                    ForEach(Array(viewModel.messages.enumerated()), id: \.element.id) { index, message in
                        // Type-check split: each row is its own view (Wave 1
                        // row carries media/thread/flash wiring).
                        RoomMessageRow(
                            index: index,
                            previous: index == 0 ? nil : viewModel.messages[index - 1],
                            message: message,
                            session: session,
                            conversation: conversation,
                            viewModel: viewModel,
                            colorOf: colorOf,
                            bubbleRadius: prefs.bubbleRadius,
                            onOpenImage: { openLightbox($0) },
                            onOpenFile: { openFile($0) },
                            onOpenThread: { threadRoot = $0 },
                            onForward: { forwardSource = $0 },
                            onInfo: { infoTarget = $0 },
                            onViewOnce: { message in
                                // Instant reveal first (web parity), burn after.
                                openLightbox(message)
                                viewModel.revealViewOnce(message, session: session)
                            },
                            wave7: wave7,
                        )
                    }
                    if viewModel.loadingOlder && !viewModel.messages.isEmpty {
                        ProgressView()
                            .padding(.vertical, 6)
                    }
                    if !viewModel.typers.isEmpty {
                        HStack(spacing: 6) {
                            TypingDotsView()
                            Text(typersText)
                                .font(.footnote)
                                .foregroundStyle(PulseTheme.emerald)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                    }
                    Color.clear.frame(height: 4).id("tail")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: viewModel.messages.count) { _, _ in
                withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion)) {
                    proxy.scrollTo("tail", anchor: .bottom)
                }
            }
            .onChange(of: viewModel.jumpTargetId) { _, target in
                guard let target else { return }
                proxy.scrollTo(target, anchor: .center)
            }
            .onAppear {
                proxy.scrollTo("tail", anchor: .bottom)
            }
        }
    }

    private func openLightbox(_ message: WireChatMessage) {
        guard let url = PulseMediaOpener.url(for: message) else {
            session.toasts.show("Media unavailable")
            return
        }
        lightbox = MediaLightboxTarget(url: url, caption: message.content)
    }

    private func openFile(_ message: WireChatMessage) {
        guard let url = PulseMediaOpener.url(for: message) else { return }
        Task {
            do {
                let local = try await PulseMediaOpener.downloadForPreview(url: url, fileName: message.fileName)
                quicklook = QuickLookTarget(url: local)
            } catch {
                session.toasts.show("Couldn't download the file")
            }
        }
    }

    private var typersText: String {
        let names = viewModel.typers.map(\.userName).filter { !$0.isEmpty }
        if names.isEmpty { return "typing…" }
        if names.count == 1 { return "\(names[0]) is typing" }
        return "\(names.count) people are typing"
    }

    private func colorOf(senderId: String) -> Color {
        let member = conversation.members.first { $0.id == senderId }
        return PulseTheme.color(named: member?.color)
    }

    private func errorStrip(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(text).font(.footnote).lineLimit(2)
            Spacer()
            Button("Retry") { Task { await viewModel.retry(session: session) } }
                .font(.footnote.weight(.semibold))
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(.regularMaterial))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    // ── composer ─────────────────────────────────────────────
    @ViewBuilder
    private var composer: some View {
        if viewModel.isRecording {
            // Recording bar replaces the composer entirely (spec §1 row 11).
            VoiceRecordingBar(
                elapsedText: PulseFormat.duration(viewModel.recordingElapsedMs),
                onCancel: { viewModel.cancelVoiceRecording() },
                onSend: { viewModel.finishAndSendVoice(session: session) },
            )
        } else {
            idleComposer
        }
    }

    private var idleComposer: some View {
        VStack(spacing: 0) {
            if broadcastLocked {
                // F-CH-04 — the broadcast lock replaces the composer row for
                // non-admins (verbatim web copy; input is gone, not disabled).
                HStack(spacing: 8) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(PulseTheme.emerald)
                    Text("Only admins can post")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(PulseTheme.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(.ultraThinMaterial)
                .accessibilityElement(children: .combine)
            } else {
                composerRows
            }
        }
    }

    @ViewBuilder
    private var composerRows: some View {
            if let editing = viewModel.editingTarget {
                editBar(editing)
            }
            if let reply = viewModel.replyTarget {
                replyBar(reply)
            }
            if let staged = viewModel.staged {
                stagedMediaBar(staged)
            }
            HStack(alignment: .bottom, spacing: 10) {
                attachMenu

                TextField(
                    viewModel.editingTarget != nil ? "Edit message" : "Message",
                    text: $viewModel.draft,
                    axis: .vertical,
                )
                .lineLimit(1...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Capsule().fill(Color(.secondarySystemBackground)))
                .focused($composerFocused)
                .onChange(of: viewModel.draft) { _, _ in viewModel.draftChanged(session: session) }
                .disabled(viewModel.staged != nil)

                // Wave 2 voice — tap-to-start when the draft is empty and
                // nothing is staged/editing (web parity).
                if viewModel.canStartVoiceRecording {
                    Button {
                        PulseHaptics.tap()
                        viewModel.startVoiceRecording(session: session)
                    } label: {
                        Image(systemName: "mic.circle.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(PulseTheme.emerald)
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Record voice note")
                } else {
                    Button {
                        PulseHaptics.tap()
                        if viewModel.editingTarget != nil {
                            viewModel.saveEdit(session: session)
                        } else if viewModel.staged != nil {
                            viewModel.sendStaged(session: session)
                        } else {
                            viewModel.send(session: session)
                        }
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(viewModel.canSend ? AnyShapeStyle(PulseTheme.gradient(named: "emerald")) : AnyShapeStyle(Color.secondary.opacity(0.4)))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .disabled(!viewModel.canSend)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
    }

    private var attachMenu: some View {
        Menu {
            Button {
                showPhotoPicker = true
            } label: {
                Label("Photo Library", systemImage: "photo")
            }
            Button {
                showFileImporter = true
            } label: {
                Label("Document", systemImage: "folder")
            }
            Button {
                pollBuilderOpen = true
            } label: {
                Label("New Poll", systemImage: "chart.bar")
            }
            // ── Wave 7 palette (web chat-room.tsx:2519-2634) ──
            Button {
                wave7.whiteboardOpen = true
            } label: {
                Label("Whiteboard", systemImage: "pencil.and.outline")
            }
            Button {
                wave7.redPacketCreateOpen = true
            } label: {
                Label("Red packet", systemImage: "gift.fill")
            }
            Button {
                wave7.eventsOpen = true
            } label: {
                Label("Events", systemImage: "calendar")
            }
            Button {
                wave7.gameCreateOpen = true
            } label: {
                Label("Game", systemImage: "gamecontroller")
            }
            Button {
                if conversation.isGroup {
                    wave7.tournamentCreateOpen = true
                } else {
                    wave7.toast("Tournaments are for groups only", isError: true)
                }
            } label: {
                Label("Tournament", systemImage: "trophy")
            }
            Button {
                wave7.kanbanOpen = true
            } label: {
                Label("Kanban", systemImage: "square.stack.3d.up.fill")
            }
        } label: {
            Image(systemName: viewModel.staged == nil ? "plus.circle.fill" : "minus.circle.fill")
                .font(.system(size: 26))
                .foregroundStyle(.secondary)
        }
        .disabled(viewModel.editingTarget != nil)
        .accessibilityLabel("Attach")
    }

    private func editBar(_ editing: WireChatMessage) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "pencil")
                .font(.footnote)
                .foregroundStyle(PulseTheme.amber)
            VStack(alignment: .leading, spacing: 1) {
                Text("Editing message")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(PulseTheme.amber)
                Text(editing.content)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                viewModel.cancelEdit()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(.thinMaterial)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func stagedMediaBar(_ staged: RoomViewModel.StagedMedia) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 10) {
                if viewModel.uploading {
                    ProgressView().controlSize(.small)
                }
                switch staged.kind {
                case .image:
                    AsyncImage(url: staged.previewURL) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 8).fill(.quaternary)
                    }
                    .frame(width: 44, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                case .file:
                    Image(systemName: "paperclip.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(PulseTheme.emerald)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(staged.kind == .image ? "Photo" : (staged.fileName ?? "Document"))
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                    if let size = staged.fileSize {
                        Text(PulseMediaSupport.humanized(bytes: size))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                TextField("Caption (optional)", text: $viewModel.caption, axis: .vertical)
                    .lineLimit(1...3)
                    .textFieldStyle(.roundedBorder)
                    .font(.subheadline)
                Button {
                    viewModel.cancelStaged()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
            }
            // Wave 2 view-once — image sends only (server: viewOnce requires
            // imagePath, spec §0).
            if staged.kind == .image {
                Toggle(
                    "View once — disappears after opening",
                    isOn: Binding(
                        get: { viewModel.staged?.viewOnce ?? false },
                        set: { viewModel.setStagedViewOnce($0) },
                    ),
                )
                .font(.caption)
                .tint(PulseTheme.emerald)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(.thinMaterial)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func replyBar(_ reply: WireChatMessage) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "arrowshape.turn.up.left.fill")
                .font(.footnote)
                .foregroundStyle(PulseTheme.emerald)
            VStack(alignment: .leading, spacing: 1) {
                Text(reply.sender?.name ?? "Reply")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(PulseTheme.emerald)
                Text(reply.content)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                viewModel.cancelReply()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(.thinMaterial)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

/// Wave 1 message info — seen-by watermarks (conversation members) + the
/// reaction groups with member names. Read-only, native sheet.
private struct MessageInfoSheet: View {
    let message: WireChatMessage
    let conversation: WireConversationSummary

    @Environment(\.dismiss) private var dismiss

    private var createdAt: Date { PulseFormat.date(message.createdAt) ?? .distantPast }

    private var seenBy: [WireConversationMember] {
        conversation.members.filter { member in
            guard let stamp = member.lastReadAt, let date = PulseFormat.date(stamp) else { return false }
            return date >= createdAt
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Message") {
                    Text(message.content.isEmpty ? "Media message" : message.content)
                        .font(.subheadline)
                    LabeledContent("Sent", value: PulseFormat.listStamp(message.createdAt))
                    if message.editedAt != nil {
                        HStack {
                            Text("Edited")
                                .font(.subheadline)
                            Spacer()
                            Image(systemName: "pencil")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                let seenTitle = "\(seenBy.count) seen"
                Section {
                    if seenBy.isEmpty {
                        Text("Nobody has seen this message yet")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(seenBy, id: \.id) { member in
                        HStack(spacing: 10) {
                            PulseAvatar(name: member.name, color: PulseTheme.color(named: member.color), size: 30)
                            Text(member.name).font(.subheadline)
                            Spacer()
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(PulseTheme.emerald)
                        }
                    }
                } header: {
                    Text(seenTitle)
                }
                if let reactions = message.reactions, !reactions.isEmpty {
                    Section("Reactions") {
                        ForEach(reactions, id: \.emoji) { group in
                            HStack(spacing: 8) {
                                Text(group.emoji).font(.body)
                                Text("\(group.count)")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                        }
                    }
                }
            }
            .navigationTitle("Message info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// One message bubble — the web outcome with an iOS accent: asymmetric
/// corner radius, gradient fill for the viewer, quote block, reaction chips,
/// voice/image/file/system renderings, "Seen" under the viewer's tail.
struct BubbleView: View {
    let message: WireChatMessage
    let mine: Bool
    let groupChat: Bool
    let viewerColor: () -> Color
    var seen: Bool = false
    var onLongPressActions: Bool = true
    /// Wave 1 — jump flash ring, live thread-reply chip, media surfaces.
    var flashing: Bool = false
    var replyCount: Int = 0
    /// Wave 8 — prefs bubble corner token (md=10, lg=16, pill=26 native
    /// radii; the tail corner stays 6). Defaults to the web's 'lg'.
    var bubbleRadius: PulseBubbleRadius = .lg
    var onOpenImage: ((WireChatMessage) -> Void)? = nil
    var onOpenFile: ((WireChatMessage) -> Void)? = nil
    var onOpenThread: ((WireChatMessage) -> Void)? = nil
    // Wave 2 — voice playback/transcribe, poll voting/close, view-once reveal.
    var viewerId: String? = nil
    var voicePlayback: VoicePlaybackManager.PlaybackState? = nil
    var voiceRate: Float = 1.0
    var onVoicePlayToggle: (() -> Void)? = nil
    var onVoiceRateCycle: (() -> Void)? = nil
    var onTranscribe: (() -> Void)? = nil
    var onPollVote: ((String) -> Void)? = nil
    var onPollClose: (() -> Void)? = nil
    var onViewOnceOpen: ((WireChatMessage) -> Void)? = nil
    // ── Wave 7 — rich-object cards (red packet / game / tournament) ──
    var wave7: Wave7RoomActions? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isDeleted: Bool { message.deletedAt != nil }
    private var isSystem: Bool { message.kind == "system" }
    /// Wave 0 outbox — optimistic queued bubble (id "local_<clientId>").
    private var isPending: Bool { message.id.hasPrefix("local_") }

    // Wave 2 view-once — sender always renders normal; a row the viewer has
    // ALREADY opened renders NO image at all (anti-replay hardening, spec §1
    // row 6: no blurred original, no lightbox entry, no preview fetch).
    private var viewOnceGated: Bool {
        message.viewOnce == true && !mine && message.viewedAt == nil && message.imagePath != nil
    }
    private var viewOnceBurned: Bool {
        message.viewOnce == true && !mine && message.viewedAt != nil
    }

    /// Wave 8 — the corner token drives every radius; the "tail" corner
    /// (bottom-trailing for the viewer, bottom-leading for the peer) stays
    /// the asymmetric 6 exactly like the web's rounded-br-md.
    private var bubbleShape: UnevenRoundedRectangle {
        let r = bubbleRadius.cornerRadius
        return mine
            ? UnevenRoundedRectangle(topLeadingRadius: r, bottomLeadingRadius: r, bottomTrailingRadius: 6, topTrailingRadius: r)
            : UnevenRoundedRectangle(topLeadingRadius: r, bottomLeadingRadius: 6, bottomTrailingRadius: r, topTrailingRadius: r)
    }

    var body: some View {
        VStack(alignment: mine ? .trailing : .leading, spacing: 2) {
            if isSystem {
                CapsuleLabel(message.content)
                    .padding(.vertical, 4)
            } else {
                if groupChat && !mine, let sender = message.sender {
                    HStack(spacing: 5) {
                        Circle().fill(PulseTheme.color(named: sender.color)).frame(width: 6, height: 6)
                        Text(sender.name)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 4)
                }

                HStack(alignment: .bottom, spacing: 4) {
                    if mine { Spacer(minLength: 44) }
                    bubble
                    if !mine { Spacer(minLength: 44) }
                }

                // Wave 1 — "N replies ↳" chip opens the thread sheet.
                if replyCount > 0 && message.parentId == nil && onOpenThread != nil {
                    Button {
                        onOpenThread?(message)
                    } label: {
                        Label("\(replyCount) \(replyCount == 1 ? "reply" : "replies")", systemImage: "arrow.turn.down.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(PulseTheme.emerald)
                    }
                    .buttonStyle(PulseButtonStyle())
                    .padding(.trailing, 6)
                }

                if mine && seen {
                    Text("Seen")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(PulseTheme.emerald)
                        .padding(.trailing, 6)
                } else if mine && !isPending && onLongPressActions {
                    Text("Sent")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.tertiary)
                        .padding(.trailing, 6)
                }
            }
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let reply = message.replyTo {
                replyQuote(reply)
            }

            if isDeleted {
                Label("Message deleted", systemImage: "trash.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                content
                // Wave 2 link preview — under the text, suppressed on poll
                // rows; nothing renders until the unfurl envelope lands.
                if message.poll == nil, let preview = message.linkPreview {
                    LinkPreviewCard(preview: preview)
                }
            }

            HStack(spacing: 5) {
                Text(PulseFormat.clockTime(message.createdAt))
                    .font(.caption2)
                    .foregroundStyle(mine ? Color.white.opacity(0.8) : .secondary)
                if isPending {
                    // Queued offline — still in the outbox, clock = not sent yet.
                    Image(systemName: "clock")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(mine ? Color.white.opacity(0.85) : PulseTheme.amber)
                }
                if message.pinnedAt != nil {
                    Image(systemName: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(mine ? Color.white.opacity(0.85) : PulseTheme.amber)
                }
            }
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 9)
        .background(bubbleShape.fill(bubbleFill))
        .overlay(reactionChips, alignment: .bottom)
        .padding(.bottom, reactionChipsHeight())
        .overlay(
            // Jump-to-message flash ring (amber pulse, ~1.5s — web parity).
            RoundedRectangle(cornerRadius: bubbleRadius.cornerRadius, style: .continuous)
                .stroke(PulseTheme.amber, lineWidth: 2.5)
                .opacity(flashing ? 1 : 0)
                .animation(.easeInOut(duration: 0.45).repeatForever(autoreverses: true), value: flashing)
                .allowsHitTesting(false),
        )
    }

    @ViewBuilder
    private var content: some View {
        // Wave 2 polls — the card replaces the body text entirely.
        if message.poll != nil || message.kind == "poll" {
            pollContent
        } else if message.kind == "redpacket" {
            wave7RedPacket
        } else if message.kind == "game" {
            wave7Game
        } else if message.kind == "tournament" {
            wave7Tournament
        } else {
            switch message.kind {
            case "image":
                imageContent
            case "audio", "voice":
                voiceContent
            case "file":
                fileContent
            case "video":
                Label("Video", systemImage: "video.fill")
                    .font(.subheadline)
            default:
                Text(message.content)
                    .font(.body)
                    .textSelection(.enabled)
            }
        }
    }

    // ── Wave 7 card contents ──
    @ViewBuilder private var wave7RedPacket: some View {
        if let wave7, let rp = PulseWave7Logic.redPacketPayload(message.payload) {
            Wave7RedPacketCard(
                packetId: rp.packetId,
                total: rp.total ?? 0,
                count: rp.count ?? 0,
                note: rp.note,
                isMine: mine,
                viewerId: viewerId ?? "",
                load: { id in await wave7.cardRedPacketLoad(id) },
                onGrab: { wave7.cardGrab(rp.packetId) },
                onOpenDetail: { wave7.cardOpenDetail(rp.packetId) },
            )
        } else {
            Text(message.content).font(.body)
        }
    }

    @ViewBuilder private var wave7Game: some View {
        if let wave7, let gp = PulseWave7Logic.gamePayload(message.payload) {
            Wave7TicTacToeCard(
                matchId: gp.matchId,
                viewerId: viewerId ?? "",
                load: { id in await wave7.cardGameLoad(id) },
                onMove: { cell in wave7.cardMove(gp.matchId, cell) },
                onJoin: { wave7.cardJoinGame(gp.matchId) },
            )
        } else {
            Text(message.content).font(.body)
        }
    }

    @ViewBuilder private var wave7Tournament: some View {
        if let wave7, let tp = PulseWave7Logic.tournamentPayload(message.payload) {
            Wave7TournamentCard(
                tournamentId: tp.tournamentId,
                name: tp.name ?? "",
                viewerId: viewerId ?? "",
                isAdmin: message.senderId == viewerId,
                load: { id in await wave7.cardTournamentLoad(id) },
                onJoin: { wave7.cardJoinTournament(tp.tournamentId) },
                onFinish: { wave7.cardFinishTournament(tp.tournamentId) },
            )
        } else {
            Text(message.content).font(.body)
        }
    }

    @ViewBuilder
    private var pollContent: some View {
        if let poll = message.poll {
            PollCard(
                poll: poll,
                mine: mine,
                viewerId: viewerId,
                onVote: { optionId in onPollVote?(optionId) },
                onClose: { onPollClose?() },
            )
        } else {
            // Poll row whose card payload is missing (degraded cache row).
            Label("Poll", systemImage: "chart.bar.fill")
                .font(.subheadline)
        }
    }

    @ViewBuilder
    private var voiceContent: some View {
        if onVoicePlayToggle != nil {
            VoiceBubble(
                message: message,
                mine: mine,
                playback: voicePlayback,
                rate: voiceRate,
                onPlayToggle: onVoicePlayToggle,
                onRateCycle: onVoiceRateCycle,
                onTranscribe: onTranscribe,
            )
        } else {
            // Threads / previews without playback wiring keep the decorative chip.
            voiceChip
        }
    }

    /// Stored image render — AsyncImage against the gateway upload URL,
    /// clamped bubble size, tap → lightbox, caption rides `content`.
    /// Wave 2 view-once (spec §1 rows 5/6/7): gated = blurred + overlay
    /// + instant-reveal tap; burned = dashed tombstone, NO image render.
    @ViewBuilder
    private var imageContent: some View {
        if viewOnceBurned {
            burnedPlaceholder
        } else if viewOnceGated {
            gatedImage
        } else {
            normalImage
        }
    }

    private var normalImage: some View {
        VStack(alignment: .leading, spacing: 5) {
            if let url = PulseEndpoints.mediaURL(message.imagePath) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                            .frame(maxWidth: 240, maxHeight: 240)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    case .empty:
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(.quaternary)
                            .frame(width: 200, height: 140)
                            .overlay(ProgressView())
                    case .failure:
                        Label("Image unavailable", systemImage: "photo.badge.exclamationmark")
                            .font(.footnote)
                    @unknown default:
                        ProgressView()
                    }
                }
                .onTapGesture { onOpenImage?(message) }
            } else {
                Label("Photo", systemImage: "photo.fill")
                    .font(.subheadline)
            }
            if !message.content.isEmpty {
                Text(message.content)
                    .font(.subheadline)
                    .textSelection(.enabled)
            }
        }
    }

    private var gatedImage: some View {
        VStack(alignment: .leading, spacing: 5) {
            ZStack {
                if let url = PulseEndpoints.mediaURL(message.imagePath) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 10, style: .continuous).fill(.quaternary)
                    }
                    .frame(width: 200, height: 200)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    // Blur the IMAGE only (spec) — the overlay stays crisp.
                    .blur(radius: 24)
                } else {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(.quaternary)
                        .frame(width: 200, height: 200)
                }
                VStack(spacing: 6) {
                    Image(systemName: "eye.slash.fill")
                        .font(.title3)
                    Text("Tap to view once")
                        .font(.footnote.weight(.semibold))
                    Text("it disappears after opening")
                        .font(.caption2)
                        .opacity(0.8)
                }
                .foregroundStyle(.white)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.black.opacity(0.45)))
            }
            .frame(width: 200, height: 200)
            .contentShape(Rectangle())
            .onTapGesture { onViewOnceOpen?(message) }
            .accessibilityHint("Opens once, then disappears forever")
            if !message.content.isEmpty {
                Text(message.content)
                    .font(.subheadline)
                    .textSelection(.enabled)
            }
        }
    }

    private var burnedPlaceholder: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .strokeBorder(Color.secondary, style: StrokeStyle(lineWidth: 1.4, dash: [5, 4]))
            .frame(width: 150, height: 200)
            .overlay(
                VStack(spacing: 6) {
                    Image(systemName: "eye.slash")
                        .font(.title3)
                    Text("Photo opened · gone forever")
                        .font(.caption2.weight(.medium))
                        .multilineTextAlignment(.center)
                }
                .foregroundStyle(.secondary)
                .padding(10)
            )
    }

    /// Document bubble — name + humanized size, tap → download + QuickLook.
    private var fileContent: some View {
        Button {
            onOpenFile?(message)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "document.fill")
                    .font(.title3)
                VStack(alignment: .leading, spacing: 1) {
                    Text(message.fileName ?? "Document")
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                    if let size = message.fileSize {
                        Text(PulseMediaSupport.humanized(bytes: size))
                            .font(.caption2)
                            .opacity(0.75)
                    }
                }
                Image(systemName: "arrow.down.circle")
                    .font(.footnote)
                    .opacity(0.8)
            }
        }
        .buttonStyle(.plain)
    }

    private var voiceChip: some View {
        HStack(spacing: 6) {
            Image(systemName: "waveform")
                .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
            // Deterministic waveform bars from the message id (native mirror
            // of the web voice bubble, no emoji chrome).
            HStack(spacing: 2) {
                ForEach(0..<14, id: \.self) { index in
                    let seed = abs(message.id.hashValue)
                    let height = CGFloat(5 + (seed * (index + 7)) % 15)
                    Capsule()
                        .fill(mine ? Color.white.opacity(0.85) : PulseTheme.emerald.opacity(0.7))
                        .frame(width: 2.5, height: height)
                }
            }
            let duration = PulseFormat.duration(message.durationMs)
            if !duration.isEmpty {
                Text(duration)
                    .font(.caption2)
                    .foregroundStyle(mine ? Color.white.opacity(0.85) : .secondary)
            }
        }
    }

    private var bubbleFill: some ShapeStyle {
        if mine {
            return AnyShapeStyle(LinearGradient(
                colors: [PulseTheme.emerald, PulseTheme.emeraldDeep],
                startPoint: .topLeading, endPoint: .bottomTrailing,
            ))
        }
        return AnyShapeStyle(Color(.secondarySystemBackground))
    }

    @ViewBuilder
    private func replyQuote(_ reply: WireReplySnippet) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(reply.senderName)
                .font(.caption.weight(.semibold))
                .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
            Text(reply.deleted == true ? "Deleted message" : reply.content)
                .font(.caption)
                .foregroundStyle(mine ? Color.white.opacity(0.85) : .secondary)
                .lineLimit(2)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 9).fill(mine ? Color.white.opacity(0.16) : Color.primary.opacity(0.05)))
    }

    private var reactions: [WireReactionGroup] { message.reactions ?? [] }

    private var reactionChips: some View {
        HStack(spacing: 4) {
            ForEach(reactions, id: \.emoji) { group in
                HStack(spacing: 3) {
                    Text(group.emoji).font(.caption)
                    if group.count > 1 {
                        Text("\(group.count)")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(Capsule().fill(.thinMaterial))
            }
        }
        .offset(y: 12)
    }

    private func reactionChipsHeight() -> CGFloat {
        reactions.isEmpty ? 0 : 14
    }
}

enum ReactionPalette {
    static let emojis = ["👍", "❤️", "😂", "😮", "😢", "🎉"]
}

/// Room state holder — transport lives in PulseSession; this owns messages,
/// drafts, replies, receipts and the live signal subscriptions.
@MainActor
final class RoomViewModel: ObservableObject {
    enum Phase: Equatable { case idle, loading, loaded, failed(String) }

    struct Typer: Equatable {
        let userId: String
        let userName: String
        let expiresAt: Date
    }

    /// Wave 1 attach flow — a staged photo/document awaiting upload + send.
    struct StagedMedia: Equatable {
        enum Kind { case image, file }
        let kind: Kind
        let dataUrl: String
        let fileName: String?
        let fileSize: Int?
        /// Local preview for the staged card (images only).
        let previewURL: URL?
        /// Wave 2 view-once — image sends only (server: viewOnce needs imagePath).
        let viewOnce: Bool
    }

    @Published private(set) var messages: [WireChatMessage] = []
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var errorText: String?
    @Published private(set) var typers: [Typer] = []
    @Published private(set) var partnerLastReadAt: Date?
    @Published var draft = ""
    @Published var replyTarget: WireChatMessage?

    // Wave 1 — pagination, threads, actions, search, jump, media.
    @Published private(set) var hasMore = false
    @Published private(set) var loadingOlder = false
    @Published private(set) var replyCounts: [String: Int] = [:]
    @Published private(set) var pins: [WireChatMessage] = []
    @Published private(set) var flashMessageId: String?
    @Published private(set) var jumpTargetId: String?
    @Published private(set) var searchResults: [WireChatMessage] = []
    @Published private(set) var searching = false
    @Published var editingTarget: WireChatMessage?
    @Published var staged: StagedMedia?
    @Published var caption = ""
    @Published private(set) var uploading = false

    // Wave 2 — voice recording, playback, polls, transcription, topics.
    @Published private(set) var isRecording = false
    @Published private(set) var recordingElapsedMs: Double = 0
    @Published private(set) var sendingVoice = false
    @Published private(set) var transcriptionBusy: Set<String> = []
    @Published private(set) var topics: [WireTopic] = []
    /// nil = General = the WHOLE room unfiltered (spec §1 row 9).
    @Published var activeTopicId: String?
    @Published private(set) var voiceRate: Float

    /// W7 — carrier rows (game / tournament / red packet) land instantly from
    /// the flow that created them; the next window refresh reconciles.
    func injectCarrier(_ m: WireChatMessage) {
        guard !messages.contains(where: { $0.id == m.id }) else { return }
        messages.append(m)
    }

    private let conversationId: String
    private let isGroupRoom: Bool
    private weak var session: PulseSession?
    private var cancellables: Set<AnyCancellable> = []
    private var typingStopTask: Task<Void, Never>?
    private var draftSaveTask: Task<Void, Never>?
    private var flashClearTask: Task<Void, Never>?
    private var initialJumpMessageId: String?
    /// Member read watermarks (id → lastReadAt) — group-aware "Seen".
    private var memberWatermarks: [String: Date] = [:]
    // Wave 2 voice plumbing — recorder + single active player + timers.
    private var voiceRecorder: VoiceRecorder?
    private var recordingStartedAt: Date?
    private var recordingTicker: AnyCancellable?
    private var topicTicker: AnyCancellable?
    private var voiceLocalFiles: [String: URL] = [:]
    /// One active voice-note player for the whole room (messageId-keyed).
    private(set) var playback = VoicePlaybackManager()

    var canSend: Bool {
        if staged != nil { return true }
        return !draft.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Mic replaces the send arrow exactly when the web mic shows:
    /// empty draft, nothing staged, not editing, not already recording.
    var canStartVoiceRecording: Bool {
        draft.trimmingCharacters(in: .whitespaces).isEmpty
            && editingTarget == nil
            && staged == nil
            && !isRecording
    }

    init(conversation: WireConversationSummary, session: PulseSession, initialJumpMessageId: String? = nil) {
        self.conversationId = conversation.id
        self.session = session
        self.isGroupRoom = conversation.isGroup
        self.initialJumpMessageId = initialJumpMessageId
        self.voiceRate = VoicePlaybackManager.storedRate()
        for member in conversation.members {
            if let stamp = member.lastReadAt {
                memberWatermarks[member.id] = PulseFormat.date(stamp)
            }
        }
        observe(session: session)
        seedLocalState(conversation: conversation, session: session)
        loadPins(session: session)
        startTopicTicker()
        loadTopics(session: session)
        Task { await refresh(session: session) }
    }

    /// Wave 2 — a recording/playback must never outlive the room. Called
    /// from ChatRoomView.onDisappear (roomVisible off); a conversation
    /// change builds a NEW view model, so its deinit covers that path.
    func handleRoomDisappeared() {
        cancelVoiceRecording()
        playback.stop()
    }

    deinit {
        // Plain-class teardown only (no actor-isolated state touched).
        voiceRecorder?.cancel()
    }

    /// Wave 0 offline rehydration — runs BEFORE the network fetch:
    ///   • messages rehydrate from the GRDB cache (relaunch works offline)
    ///   • the composer seeds from the local draft, falling back to myDraft
    private func seedLocalState(conversation: WireConversationSummary, session: PulseSession) {
        if let store = session.store {
            let cached = (try? store.messages(conversationId: conversationId)) ?? []
            if !cached.isEmpty {
                messages = cached
                phase = .loaded
            }
            draft = store.draft(conversationId: conversationId) ?? (conversation.myDraft ?? "")
        } else {
            draft = conversation.myDraft ?? ""
        }
    }

    func observe(session: PulseSession) {
        self.session = session
        cancellables.removeAll()
        session.signals
            .receive(on: DispatchQueue.main)
            .sink { [weak self] signal in
                guard let self else { return }
                switch signal {
                case .messageNew(let convId, let raw):
                    guard convId == self.conversationId,
                          let message = PulseSession.decodeMessage(from: raw) else { return }
                    try? session.store?.upsert(messages: [message])
                    if message.parentId != nil {
                        // Thread reply — river keeps it out; the parent's
                        // "N replies" chip bumps live (web threadCounts parity).
                        let root = message.parentId ?? ""
                        replyCounts[root, default: 0] += 1
                        if message.senderId == session.viewer?.id { return }
                    }
                    upsert(message)
                    if message.senderId != session.viewer?.id {
                        Task { try? await session.api.markRead(conversationId: self.conversationId) }
                    }
                case .messageDeleted(let convId, let raw):
                    guard convId == self.conversationId else { return }
                    if let message = PulseSession.decodeMessage(from: raw) {
                        let tombstone = message.deletedCopy()
                        upsert(tombstone)
                        try? session.store?.upsert(messages: [tombstone])
                    }
                case .messageReact(let convId, let raw):
                    guard convId == self.conversationId,
                          let message = PulseSession.decodeMessage(from: raw) else { return }
                    upsert(message)
                    try? session.store?.upsert(messages: [message])
                case .messageRead(let convId, let userId, let lastReadAt):
                    guard convId == self.conversationId, userId != session.viewer?.id else { return }
                    // W1-DATA-B — trust the relayed watermark when present;
                    // fall back to "now" (older relays / clock-safety).
                    partnerLastReadAt = lastReadAt.flatMap { PulseFormat.date($0) } ?? Date()
                case .typing(let convId, let userId, let userName, let isTyping):
                    guard convId == self.conversationId, userId != session.viewer?.id else { return }
                    var bucket = typers.filter { $0.userId != userId }
                    if isTyping {
                        bucket.append(Typer(userId: userId, userName: userName, expiresAt: Date().addingTimeInterval(4)))
                    }
                    typers = bucket
                case .messageEnvelope(_, let convId, let raw):
                    // Wave 2 — message:viewed / poll:voted / link:preview carry
                    // the AUTHORITATIVE row (spec §0): upsert whole → the
                    // burn stamp, live tally and preview card paint instantly.
                    guard convId == self.conversationId,
                          let message = PulseSession.decodeMessage(from: raw) else { return }
                    try? session.store?.upsert(messages: [message])
                    upsert(message)
                default:
                    break
                }
            }
            .store(in: &cancellables)

        // Wave 2 — realtimeRefreshTick bumps on every background mutation
        // (viewed/poll/link envelopes, outbox deliveries). Recompute the
        // active topic view so incoming topic-tagged rows land instantly
        // (the native improvement over web's 3.5 s cache poll, spec §1 row 9).
        session.$realtimeRefreshTick
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                refilterInPlace()
                activeTopicId = TopicHeal.healed(activeTopicId, topics: topics)
            }
            .store(in: &cancellables)

        // Wave 0 — the outbox engine swaps the queued placeholder for the real
        // row (web sendMessage.onSuccess dedupe parity), drops it on 4xx.
        session.outboxEvents
            .receive(on: DispatchQueue.main)
            .sink { [weak self] event in
                self?.handleOutboxEvent(event)
            }
            .store(in: &cancellables)

        // Typer expiry sweep — keeps the dots honest without server stop events.
        Timer.publish(every: 1.2, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                let now = Date()
                let alive = typers.filter { $0.expiresAt > now }
                if alive.count != typers.count { typers = alive }
            }
            .store(in: &cancellables)
    }

    func refresh(session: PulseSession) async {
        // Cache seed first — the room renders instantly (offline too), then
        // the network refresh replaces it.
        if messages.isEmpty, let store = session.store {
            let cached = (try? store.messages(conversationId: conversationId)) ?? []
            if !cached.isEmpty {
                messages = riverRows(from: cached)
                phase = .loaded
            }
            seedReplyCounts(store: store)
        }
        phase = messages.isEmpty ? .loading : phase
        do {
            let page = try await session.api.messages(conversationId: conversationId)
            messages = riverRows(from: page.messages)
            hasMore = page.hasMore
            phase = .loaded
            errorText = nil
            try? session.store?.upsert(messages: page.messages)
            try? await session.api.markRead(conversationId: conversationId)
            resolveInitialJump()
        } catch {
            phase = .failed(RoomViewModel.describe(error))
            if messages.isEmpty { errorText = RoomViewModel.describe(error) }
        }
    }

    /// The main river EXCLUDES thread replies (web parity) — they live in
    /// ThreadView and surface as "N replies" chips on the parent bubble.
    /// Wave 2 topics: activeTopicId == nil → General = the WHOLE room;
    /// otherwise only rows filed under that topic (spec §1 row 9).
    private func riverRows(from rows: [WireChatMessage]) -> [WireChatMessage] {
        rows.filter { row in
            guard row.parentId == nil else { return false }
            if let active = activeTopicId {
                return row.topicId == active
            }
            return true
        }
    }

    /// Live topic-view hygiene — drops rows that stopped matching the active
    /// filter (e.g. an envelope re-mapped a row's topic). No-op on General.
    private func refilterInPlace() {
        guard let active = activeTopicId else { return }
        let filtered = messages.filter { $0.topicId == active }
        if filtered.count != messages.count {
            messages = filtered
        }
    }

    private func seedReplyCounts(store: PulseStore) {
        if let counts = try? store.threadReplyCounts() {
            replyCounts = counts
        }
    }

    /// Wave 1 pagination — older history page (`before` cursor), 40 rows,
    /// merged id-dedupe ascending. The view triggers from the oldest row.
    func loadOlder(session: PulseSession) {
        guard hasMore, !loadingOlder, let oldest = messages.first(where: { !$0.id.hasPrefix("local_") }) else { return }
        loadingOlder = true
        Task { [weak self] in
            guard let self else { return }
            defer { self.loadingOlder = false }
            do {
                let page = try await session.api.messages(
                    conversationId: self.conversationId,
                    limit: 40,
                    before: oldest.createdAt,
                    query: nil,
                    topicId: self.activeTopicId,
                )
                try? session.store?.upsert(messages: page.messages)
                let known = Set(self.messages.map(\.id))
                let fresh = page.messages.filter { row in
                    guard row.parentId == nil, !known.contains(row.id) else { return false }
                    if let active = self.activeTopicId { return row.topicId == active }
                    return true
                }
                self.messages.insert(contentsOf: fresh, at: 0)
                self.hasMore = page.hasMore && !page.messages.isEmpty
            } catch {
                // Older history is best-effort — the current window stays usable.
            }
        }
    }

    // ── jump + flash (search hits, quote taps, pinned banner) ──

    func replyCount(for message: WireChatMessage) -> Int {
        replyCounts[message.id] ?? 0
    }

    func jumpTo(_ messageId: String) {
        if messages.contains(where: { $0.id == messageId }) {
            scrollAndFlash(messageId)
        } else {
            guard let session = session else { return }
            Task { await expandForJump(messageId, session: session, rounds: 0) }
        }
    }

    private func resolveInitialJump() {
        guard let target = initialJumpMessageId else { return }
        initialJumpMessageId = nil
        jumpTo(target)
    }

    /// Bounded expansion for out-of-window targets (web ≤14 rounds parity).
    private func expandForJump(_ messageId: String, session: PulseSession, rounds: Int) async {
        guard rounds < 14 else {
            session.toasts.show("Couldn't reach that message in this chat's history")
            return
        }
        guard hasMore, let oldest = messages.first(where: { !$0.id.hasPrefix("local_") }) else {
            session.toasts.show("Couldn't reach that message in this chat's history")
            return
        }
        loadingOlder = true
        defer { loadingOlder = false }
        do {
            let page = try await session.api.messages(conversationId: conversationId, limit: 40, before: oldest.createdAt, query: nil, topicId: activeTopicId)
            try? session.store?.upsert(messages: page.messages)
            let known = Set(messages.map(\.id))
            let fresh = page.messages.filter { row in
                guard row.parentId == nil, !known.contains(row.id) else { return false }
                if let active = activeTopicId { return row.topicId == active }
                return true
            }
            messages.insert(contentsOf: fresh, at: 0)
            hasMore = page.hasMore && !page.messages.isEmpty
            if messages.contains(where: { $0.id == messageId }) {
                scrollAndFlash(messageId)
            } else if page.messages.isEmpty {
                session.toasts.show("Couldn't reach that message in this chat's history")
            } else {
                await expandForJump(messageId, session: session, rounds: rounds + 1)
            }
        } catch {
            session.toasts.show("Couldn't reach that message in this chat's history")
        }
    }

    private func scrollAndFlash(_ messageId: String) {
        jumpTargetId = messageId
        flashMessageId = messageId
        flashClearTask?.cancel()
        flashClearTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            guard let self, !Task.isCancelled else { return }
            self.flashMessageId = nil
            self.jumpTargetId = nil
        }
    }

    // ── room search (server q= + local window filter) ───────

    private var searchTask: Task<Void, Never>?

    func searchInRoom(_ query: String, session: PulseSession) {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= 2 else {
            searchResults = []
            searching = false
            return
        }
        searching = true
        searchTask = Task { [weak self] in
            // 220 ms debounce — the room-search rhythm (web room-search-page;
            // the GLOBAL chats search keeps its own 250 ms).
            try? await Task.sleep(nanoseconds: 220_000_000)
            guard let self, !Task.isCancelled else { return }
            var hits: [WireChatMessage] = []
            if let page = try? await session.api.messages(conversationId: self.conversationId, limit: 100, before: nil, query: trimmed) {
                hits = page.messages
            }
            if Task.isCancelled { return }
            // Merge the live loaded window (covers rows newer than the
            // server's scan window and the offline case).
            let local = self.messages.filter { $0.content.lowercased().contains(trimmed.lowercased()) }
            var seenIds = Set<String>()
            var merged: [WireChatMessage] = []
            for row in hits + local {
                if seenIds.insert(row.id).inserted { merged.append(row) }
            }
            merged.sort { (PulseFormat.date($0.createdAt) ?? .distantPast) < (PulseFormat.date($1.createdAt) ?? .distantPast) }
            self.searchResults = Array(merged.prefix(30))
            self.searching = false
        }
    }

    func retry(session: PulseSession) async {
        errorText = nil
        await refresh(session: session)
    }

    func upsert(_ message: WireChatMessage) {
        // Main river only — thread replies render in ThreadView.
        if message.parentId != nil { return }
        // Wave 2 topics — an active topic view must not surface rows filed
        // elsewhere (the store keeps them; switching back refetches).
        if let active = activeTopicId, message.topicId != active { return }
        guard let index = messages.firstIndex(where: { $0.id == message.id }) else {
            messages.append(message)
            messages.sort { PulseFormat.date($0.createdAt) ?? .distantPast < PulseFormat.date($1.createdAt) ?? .distantPast }
            return
        }
        messages[index] = message
    }

    func draftChanged(session: PulseSession) {
        // Wave 0 — 600ms debounced draft persistence (web pulse-drafts parity).
        scheduleDraftSave(session: session)

        let text = draft
        if !text.isEmpty && typingStopTask == nil {
            session.emitTyping(conversationId: conversationId, recipients: [], isTyping: true)
        }
        typingStopTask?.cancel()
        typingStopTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard let self, !Task.isCancelled else { return }
            self.typingStopTask = nil
            if self.draft.isEmpty {
                session.emitTyping(conversationId: self.conversationId, recipients: [], isTyping: false)
            }
        }
    }

    private func scheduleDraftSave(session: PulseSession) {
        let text = draft
        draftSaveTask?.cancel()
        draftSaveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 600_000_000)
            guard !Task.isCancelled, let self else { return }
            self.persistDraft(text, session: session)
        }
    }

    private func persistDraft(_ text: String, session: PulseSession) {
        guard let store = session.store else { return }
        if text.trimmingCharacters(in: .whitespaces).isEmpty {
            try? store.deleteDraft(conversationId: conversationId)
        } else {
            try? store.saveDraft(conversationId: conversationId, text: text)
        }
        // Wave 1 server mirror (web pulse-drafts parity) — fire-and-forget,
        // silent-fail; the local draft stays authoritative for this device.
        if let viewer = session.viewer {
            Task {
                try? await session.api.setDraft(
                    conversationId: conversationId,
                    userId: viewer.id,
                    draft: text.trimmingCharacters(in: .whitespaces).isEmpty ? "" : text,
                )
            }
        }
    }

    /// Called on the successful-send path — the composer is clean, so the
    /// local draft must go (server myDraft takes over on the next read).
    func clearDraft(session: PulseSession) {
        draftSaveTask?.cancel()
        draftSaveTask = nil
        try? session.store?.deleteDraft(conversationId: conversationId)
    }

    func send(session: PulseSession) {
        let body = draft.trimmingCharacters(in: .whitespaces)
        let replySource = replyTarget
        let replyId = replySource?.id
        guard !body.isEmpty, staged == nil, editingTarget == nil else { return }
        draft = ""
        replyTarget = nil
        typingStopTask?.cancel()
        typingStopTask = nil
        // Wave 1 optimistic send — the temp bubble paints immediately and
        // swaps for the server row (web onMutate parity). Network-class
        // failure keeps the temp + queues the outbox (Wave 0 semantics).
        guard let viewer = session.viewer else {
            errorText = "The gateway is unreachable."
            return
        }
        let clientId = UUID().uuidString
        let temp = TempMessages.make(
            conversationId: conversationId,
            viewer: viewer,
            clientId: clientId,
            content: body,
            parentId: nil,
            replyTo: replySource,
        )
        upsert(temp)
        try? session.store?.upsert(messages: [temp])
        Task { [weak self] in
            guard let self else { return }
            do {
                let message = try await session.api.sendMessage(
                    conversationId: conversationId,
                    content: body,
                    replyToId: replyId,
                    topicId: activeTopicId,
                )
                self.swapTemp(temp.id, for: message, session: session)
                self.clearDraft(session: session)
                session.particles.fire(kind: .burst, count: 22)
                session.emitTyping(conversationId: conversationId, recipients: [], isTyping: false)
                // Wave 2 topics — own send bumps Topic.lastMessageAt server-side.
                self.loadTopics(session: session)
                // Wave 2 link previews — spec §1 row 8: the ORIGINAL SENDER's
                // client triggers /unfurl once, fire-and-forget, ignoring the
                // result (nil is valid = nothing link-ish / host unreachable).
                if UnfurlTrigger.matches(body) {
                    self.triggerUnfurl(for: message, viewerId: viewer.id, session: session)
                }
            } catch {
                if PulseOutboxEngine.isDroppable(error) {
                    self.messages.removeAll { $0.id == temp.id }
                    try? session.store?.deleteMessage(id: temp.id)
                    self.errorText = Self.describe(error)
                } else {
                    // Temp row stays (queued clock) — outbox flush reconciles.
                    session.enqueueOutbox(conversationId: conversationId, clientId: clientId, content: body)
                    session.toasts.show("Message queued — sends when you're back online")
                }
            }
        }
    }

    /// Wave 2 — fire-and-forget unfurl after OWN link-bearing send (spec §1
    /// row 8). Detached + swallow-everything: the card arrives either through
    /// this row or the link:preview relay envelope.
    private func triggerUnfurl(for message: WireChatMessage, viewerId: String, session: PulseSession) {
        let api = session.api
        let store = session.store
        Task.detached { [weak self] in
            guard let row = try? await api.unfurl(messageId: message.id, userId: viewerId) else { return }
            try? store?.upsert(messages: [row])
            await MainActor.run { self?.upsert(row) }
        }
    }

    /// Temp → real reconciliation: swap by id AND dedupe any optimistic row
    /// with the same sender + content (web sendMessage.onSuccess parity).
    private func swapTemp(_ tempId: String, for message: WireChatMessage, session: PulseSession) {
        messages.removeAll {
            $0.id == tempId
                || ($0.id.hasPrefix("local_") && $0.content == message.content && $0.senderId == message.senderId)
        }
        upsert(message)
        try? session.store?.upsert(messages: [message])
        try? session.store?.deleteMessage(id: tempId)
    }

    /// Wave 0 outbox reconciliation — the engine swapped/removed a queued
    /// placeholder (text sends only; spec §1.2).
    private func handleOutboxEvent(_ event: PulseOutboxEvent) {
        switch event {
        case .delivered(let message, let conversationId):
            guard conversationId == self.conversationId else { return }
            upsert(message)
            // Generic temp dedupe (web parity): same content + sender.
            messages.removeAll {
                $0.id.hasPrefix("local_")
                    && $0.content == message.content
                    && $0.senderId == message.senderId
            }
        case .dropped(let clientId, let conversationId):
            guard conversationId == self.conversationId else { return }
            messages.removeAll { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }
        }
    }

    func react(_ message: WireChatMessage, emoji: String, session: PulseSession) {
        PulseHaptics.tap()
        Task { [weak self] in
            guard let self else { return }
            do {
                let fresh = try await session.api.react(messageId: message.id, emoji: emoji)
                upsert(fresh)
                if emoji == "❤️" { session.particles.fire(kind: .hearts, count: 24) }
            } catch {
                errorText = Self.describe(error)
            }
        }
    }

    func beginReply(to message: WireChatMessage) {
        replyTarget = message
    }

    func cancelReply() {
        replyTarget = nil
    }

    func isSeen(_ message: WireChatMessage) -> Bool {
        guard message.senderId == session?.viewer?.id,
              let last = messages.last(where: { $0.senderId == session?.viewer?.id }) else { return false }
        guard last.id == message.id else { return false }
        // Wave 1 — group-aware watermarks: any OTHER member's lastReadAt
        // covering this message counts as seen (DM keeps the partner path).
        var stamps = Array(memberWatermarks.values)
        if let partner = partnerLastReadAt { stamps.append(partner) }
        guard let seen = stamps.max() else { return false }
        return seen >= (PulseFormat.date(message.createdAt) ?? .distantFuture)
    }

    // ── Wave 1 message actions ─────────────────────────────

    func loadPins(session: PulseSession) {
        guard let viewer = session.viewer else { return }
        Task { [weak self] in
            guard let self else { return }
            self.pins = (try? await session.api.pinnedMessages(conversationId: self.conversationId, userId: viewer.id)) ?? []
        }
    }

    func beginEdit(_ message: WireChatMessage) {
        replyTarget = nil
        draft = message.content
        editingTarget = message
    }

    func cancelEdit() {
        editingTarget = nil
        draft = ""
    }

    func saveEdit(session: PulseSession) {
        guard let target = editingTarget else { return }
        let body = draft.trimmingCharacters(in: .whitespaces)
        guard !body.isEmpty else { return }
        editingTarget = nil
        draft = ""
        // Edits never queue (spec §1.2) — an honest error covers offline.
        Task { [weak self] in
            guard let self, let viewer = session.viewer else { return }
            do {
                let fresh = try await session.api.editMessage(id: target.id, userId: viewer.id, content: body)
                self.upsert(fresh)
                try? session.store?.upsert(messages: [fresh])
            } catch {
                self.errorText = Self.describe(error)
            }
        }
    }

    func delete(_ message: WireChatMessage, session: PulseSession) {
        Task { [weak self] in
            guard let self else { return }
            do {
                try await session.api.deleteOwnMessage(id: message.id)
                let tombstone = message.deletedCopy()
                self.upsert(tombstone)
                try? session.store?.upsert(messages: [tombstone])
                PulseHaptics.tap()
            } catch {
                self.errorText = Self.describe(error)
            }
        }
    }

    func togglePin(_ message: WireChatMessage, session: PulseSession) {
        PulseHaptics.tap()
        Task { [weak self] in
            guard let self, let viewer = session.viewer else { return }
            do {
                let fresh = try await session.api.toggleMessagePin(id: message.id, userId: viewer.id)
                self.upsert(fresh)
                try? session.store?.upsert(messages: [fresh])
                self.loadPins(session: session)
                session.toasts.show(fresh.pinnedAt == nil ? "Unpinned" : "Pinned")
            } catch {
                self.errorText = Self.describe(error)
            }
        }
    }

    func toggleSave(_ message: WireChatMessage, session: PulseSession) {
        PulseHaptics.tap()
        Task { [weak self] in
            guard let self, let viewer = session.viewer else { return }
            do {
                let saved = try await session.api.toggleMessageSave(id: message.id, userId: viewer.id)
                session.toasts.show(saved ? "Saved to your library" : "Removed from your library")
            } catch {
                self.errorText = Self.describe(error)
            }
        }
    }

    // ── Wave 1 media staging + upload (spec §1.1) ────────

    func stageImage(_ data: Data) {
        guard let jpeg = PulseMediaSupport.downscaledJPEGData(from: data) else {
            errorText = "Couldn't read that image"
            return
        }
        staged = StagedMedia(
            kind: .image,
            dataUrl: PulseMediaSupport.dataUrl(mime: "image/jpeg", data: jpeg),
            fileName: nil,
            fileSize: jpeg.count,
            previewURL: URL(dataRepresentation: jpeg, relativeTo: nil),
            viewOnce: false,
        )
        caption = ""
    }

    func stageDocument(url: URL) {
        let secured = url.startAccessingSecurityScopedResource()
        defer { if secured { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            errorText = "Couldn't read that document"
            return
        }
        if data.count > PulseMediaSupport.maxDocumentBytes {
            errorText = "Documents are limited to 10 MB"
            return
        }
        let ext = url.pathExtension
        guard let mime = PulseMediaSupport.mime(forExtension: ext) else {
            errorText = "That file type isn't supported (pdf, zip, txt, csv)"
            return
        }
        staged = StagedMedia(
            kind: .file,
            dataUrl: PulseMediaSupport.dataUrl(mime: mime, data: data),
            fileName: url.lastPathComponent,
            fileSize: data.count,
            previewURL: nil,
            viewOnce: false,
        )
        caption = ""
    }

    /// Wave 2 view-once — flips the staged card's burn flag (image sends).
    func setStagedViewOnce(_ on: Bool) {
        guard let current = staged else { return }
        staged = StagedMedia(
            kind: current.kind,
            dataUrl: current.dataUrl,
            fileName: current.fileName,
            fileSize: current.fileSize,
            previewURL: current.previewURL,
            viewOnce: on,
        )
    }

    func cancelStaged() {
        staged = nil
        caption = ""
        uploading = false
    }

    /// Upload → send. Media NEVER queues offline (spec §1.2) — failures
    /// surface an honest inline error and keep the staged card for retry.
    func sendStaged(session: PulseSession) {
        guard let media = staged, !uploading else { return }
        guard session.viewer != nil else {
            errorText = "The gateway is unreachable."
            return
        }
        uploading = true
        let body = caption.trimmingCharacters(in: .whitespaces)
        Task { [weak self] in
            guard let self else { return }
            do {
                let filePath = try await session.api.uploadMedia(dataUrl: media.dataUrl)
                let message: WireChatMessage
                switch media.kind {
                case .image:
                    message = try await session.api.sendMessage(
                        conversationId: conversationId,
                        content: body,
                        imagePath: filePath,
                        viewOnce: media.viewOnce ? true : nil,
                        topicId: activeTopicId,
                    )
                case .file:
                    message = try await session.api.sendMessage(
                        conversationId: conversationId,
                        content: body,
                        filePath: filePath,
                        fileName: media.fileName,
                        fileSize: media.fileSize,
                        kind: "file",
                        topicId: activeTopicId
                    )
                }
                self.uploading = false
                self.staged = nil
                self.caption = ""
                self.upsert(message)
                try? session.store?.upsert(messages: [message])
                session.particles.fire(kind: .burst, count: 22)
                self.loadTopics(session: session)
            } catch {
                self.uploading = false
                self.errorText = Self.describe(error)
            }
        }
    }

    // ── Wave 2 voice notes (spec §1 rows 1/11/12/15) ─────

    /// Tap-to-start — mic TCC, then the recording bar replaces the composer.
    func startVoiceRecording(session: PulseSession) {
        guard !isRecording, voiceRecorder == nil else { return }
        Task { [weak self] in
            guard let self else { return }
            let granted = await VoiceRecorder.requestPermission()
            guard granted else {
                session.toasts.show("Microphone access is off — enable it in Settings to record voice notes")
                return
            }
            guard !self.isRecording, self.voiceRecorder == nil else { return }
            let recorder = VoiceRecorder()
            do {
                try recorder.start()
            } catch {
                session.toasts.show("Couldn't start recording")
                return
            }
            self.voiceRecorder = recorder
            self.recordingStartedAt = Date()
            self.recordingElapsedMs = 0
            self.isRecording = true
            self.startRecordingTicker()
        }
    }

    func cancelVoiceRecording() {
        guard isRecording else { return }
        voiceRecorder?.cancel()
        teardownRecording()
    }

    /// Send path — stop, round the duration (spec: max(1, round(ms/100)*100)),
    /// discard <600 ms with the honest toast, else upload + send kind "audio"
    /// (never queued — media never queues, spec §1.2).
    func finishAndSendVoice(session: PulseSession) {
        guard isRecording, !sendingVoice, let startedAt = recordingStartedAt else { return }
        let elapsedMs = Date().timeIntervalSince(startedAt) * 1000
        let durationMs = VoiceMath.roundedDurationMs(fromElapsedMs: elapsedMs)
        voiceRecorder?.stop()
        let fileURL = voiceRecorder?.fileURL
        teardownRecording()
        guard !VoiceMath.isTooShort(elapsedMs), let fileURL else {
            if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
            session.toasts.show("Too short — voice note discarded")
            return
        }
        guard let viewer = session.viewer else {
            try? FileManager.default.removeItem(at: fileURL)
            errorText = "The gateway is unreachable."
            return
        }
        sendingVoice = true
        Task { [weak self] in
            defer {
                self?.sendingVoice = false
                try? FileManager.default.removeItem(at: fileURL)
            }
            guard let self else { return }
            do {
                let data = try Data(contentsOf: fileURL)
                let dataUrl = PulseMediaSupport.dataUrl(mime: "audio/mp4", data: data)
                let filePath = try await session.api.uploadMedia(dataUrl: dataUrl)
                let message = try await session.api.sendMessage(
                    conversationId: conversationId,
                    content: "",
                    audioPath: filePath,
                    durationMs: durationMs,
                    kind: "audio",
                    topicId: activeTopicId,
                )
                self.upsert(message)
                try? session.store?.upsert(messages: [message])
                session.particles.fire(kind: .burst, count: 22)
                self.loadTopics(session: session)
            } catch {
                // Media NEVER queues (web parity) — honest toast instead.
                session.toasts.show(Self.describe(error))
            }
        }
    }

    private func teardownRecording() {
        stopRecordingTicker()
        voiceRecorder = nil
        recordingStartedAt = nil
        isRecording = false
        recordingElapsedMs = 0
    }

    private func startRecordingTicker() {
        stopRecordingTicker()
        recordingTicker = Timer.publish(every: 0.25, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self, let startedAt = self.recordingStartedAt else { return }
                self.recordingElapsedMs = Date().timeIntervalSince(startedAt) * 1000
            }
    }

    private func stopRecordingTicker() {
        recordingTicker?.cancel()
        recordingTicker = nil
    }

    // ── Wave 2 voice playback (spec §1 rows 12/13) ───────

    func toggleVoicePlayback(_ message: WireChatMessage, session: PulseSession) {
        if let state = playback.state, state.messageId == message.id {
            if state.playing {
                playback.pause()
            } else if state.progress < 0.999 {
                playback.resume()
            } else {
                playback.replay()
            }
            return
        }
        // Single active player — starting one message stops the previous one.
        playback.stop()
        startVoicePlayback(message, session: session)
    }

    private func startVoicePlayback(_ message: WireChatMessage, session: PulseSession) {
        guard let path = message.audioPath, let remote = PulseEndpoints.mediaURL(path) else {
            session.toasts.show("Voice note unavailable")
            return
        }
        Task { [weak self] in
            guard let self else { return }
            let local: URL
            if let cached = self.voiceLocalFiles[message.id] {
                local = cached
            } else {
                do {
                    local = try await PulseMediaOpener.downloadForPreview(url: remote, fileName: "voice-\(message.id).m4a")
                    self.voiceLocalFiles[message.id] = local
                } catch {
                    session.toasts.show("Couldn't download the voice note")
                    return
                }
            }
            self.playback.start(messageId: message.id, localURL: local, rate: self.voiceRate)
        }
    }

    /// Speed chip 1x → 1.5x → 2x → 1x — global pref (UserDefaults
    /// "pulse.voiceRate"), applied live to the active player.
    func cycleVoiceRate() {
        let next = VoicePlaybackManager.nextRate(after: voiceRate)
        voiceRate = next
        UserDefaults.standard.set(next, forKey: VoicePlaybackManager.rateKey)
        playback.setRate(next)
    }

    /// POST /transcribe (kind "audio" rows only) — patches the store row AND
    /// the river row; 502 surfaces the service wording, everything else the
    /// honest fallback (spec §1 row 15).
    func transcribeVoice(_ message: WireChatMessage, session: PulseSession) {
        guard message.transcript == nil,
              !message.id.hasPrefix("local_"),
              !transcriptionBusy.contains(message.id),
              let viewer = session.viewer else { return }
        transcriptionBusy.insert(message.id)
        Task { [weak self] in
            defer { self?.transcriptionBusy.remove(message.id) }
            guard let self else { return }
            do {
                let result = try await session.api.transcribe(messageId: message.id, requesterId: viewer.id)
                try? session.store?.updateTranscription(
                    messageId: message.id,
                    transcript: result.transcript,
                    transcribedAt: result.transcribedAt ?? PulseOutboxClock.now(),
                )
                self.upsert(message.withTranscript(result.transcript, transcribedAt: result.transcribedAt))
            } catch {
                session.toasts.show(Self.transcribeErrorWording(error))
            }
        }
    }

    static func transcribeErrorWording(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure {
            if failure.status == 502 {
                return "Transcription service is down — try again later"
            }
            if let message = failure.message, !message.isEmpty {
                return message
            }
        }
        return "Transcription unavailable"
    }

    // ── Wave 2 view-once (spec §1 rows 5/6/7) ────────────

    /// The reveal already happened in the UI (instant lightbox, web parity);
    /// this stamps the burn server-side and patches the rows. Best-effort —
    /// the relayed message:viewed envelope reconciles later on failure.
    func revealViewOnce(_ message: WireChatMessage, session: PulseSession) {
        guard message.viewOnce == true,
              message.senderId != session.viewer?.id,
              message.viewedAt == nil,
              let viewer = session.viewer else { return }
        Task { [weak self] in
            do {
                let fresh = try await session.api.markViewed(messageId: message.id, userId: viewer.id)
                try? session.store?.upsert(messages: [fresh])
                self?.upsert(fresh)
            } catch {
                // Instant-open parity: the image is already on screen; the
                // envelope will re-stamp the row. Nothing honest to add here.
            }
        }
    }

    // ── Wave 2 polls (spec §1 rows 2/3/4) ────────────────

    func createPoll(question: String, options: [String], session: PulseSession) {
        guard let viewer = session.viewer else { return }
        let trimmedQuestion = question.trimmingCharacters(in: .whitespaces)
        guard !trimmedQuestion.isEmpty, options.count >= 2 else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let message = try await session.api.createPoll(
                    conversationId: conversationId,
                    senderId: viewer.id,
                    question: trimmedQuestion,
                    options: options,
                )
                self.upsert(message)
                try? session.store?.upsert(messages: [message])
                self.loadTopics(session: session)
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    func votePoll(pollID: String, optionID: String, session: PulseSession) {
        guard !pollID.isEmpty, !optionID.isEmpty, let viewer = session.viewer else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let message = try await session.api.votePoll(pollId: pollID, userId: viewer.id, optionId: optionID)
                withAnimation { self.upsert(message) }
                try? session.store?.upsert(messages: [message])
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    func closePoll(pollID: String, session: PulseSession) {
        guard !pollID.isEmpty, let viewer = session.viewer else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let message = try await session.api.closePoll(pollId: pollID, userId: viewer.id)
                withAnimation { self.upsert(message) }
                try? session.store?.upsert(messages: [message])
                session.toasts.show("Voting closed — results are final")
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    // ── Wave 2 topics (spec §1 rows 9/10) ────────────────

    /// Open + after own sends + the 15 s ticker — api.topics → store upsert →
    /// read back (the cached rail renders even when the next fetch fails).
    func loadTopics(session: PulseSession) {
        guard isGroupRoom, let viewer = session.viewer else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let fresh = try await session.api.topics(conversationId: conversationId, userId: viewer.id)
                try? session.store?.upsert(topics: fresh, conversationId: conversationId)
            } catch {
                // Offline — the cached rail below still renders.
            }
            if let store = session.store {
                topics = (try? store.topics(conversationId: conversationId)) ?? topics
            }
            activeTopicId = TopicHeal.healed(activeTopicId, topics: topics)
        }
    }

    /// General (nil) = whole room; a topic refetches the river with topicId=
    /// and merges through the store (spec §1 row 9).
    func setActiveTopic(_ id: String?, session: PulseSession) {
        guard activeTopicId != id else { return }
        activeTopicId = id
        PulseHaptics.tap()
        Task { [weak self] in
            guard let self else { return }
            await self.reloadRiver(session: session)
        }
    }

    private func reloadRiver(session: PulseSession) async {
        do {
            let page = try await session.api.messages(conversationId: conversationId, topicId: activeTopicId)
            messages = riverRows(from: page.messages)
            hasMore = page.hasMore
            phase = .loaded
            try? session.store?.upsert(messages: page.messages)
        } catch {
            // Offline — filter the cache in place instead of failing hard.
            if let store = session.store {
                let cached = (try? store.messages(conversationId: conversationId)) ?? []
                messages = riverRows(from: cached)
            }
        }
    }

    /// Create → refresh rail → auto-activate (web toast parity).
    func createTopic(name: String, emoji: String, session: PulseSession) {
        guard isGroupRoom, let viewer = session.viewer else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let topic = try await session.api.createTopic(
                    conversationId: conversationId,
                    userId: viewer.id,
                    name: name,
                    emoji: emoji,
                )
                self.loadTopics(session: session)
                self.setActiveTopic(topic.id, session: session)
                session.toasts.show("Filing to \(emoji) \(topic.name) — next send lands there")
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    /// Every 15 s while the room owns the screen (spec §1 row 9 — counts
    /// refresh on open + after own send + poll).
    private func startTopicTicker() {
        topicTicker?.cancel()
        topicTicker = Timer.publish(every: 15, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self, let session = self.session, session.roomVisible else { return }
                self.loadTopics(session: session)
            }
    }

    static func describe(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message, !message.isEmpty {
            return message
        }
        return "The gateway is unreachable."
    }
}

/// Optimistic temp-row factory shared by the river AND thread sends —
/// id `local_<clientId>` (Wave 0 convention), viewer sender, quote block,
/// optional thread parent. Real rows replace it by id + content dedupe.
/// @MainActor: builds on PulseOutboxEngine.tempMessageId (MainActor-static).
@MainActor
enum TempMessages {
    static func make(
        conversationId: String,
        viewer: PulseViewer,
        clientId: String,
        content: String,
        parentId: String?,
        replyTo: WireChatMessage? = nil,
    ) -> WireChatMessage {
        let sender = WireSender(
            id: viewer.id,
            name: viewer.name,
            username: viewer.username,
            color: viewer.color,
            avatar: viewer.avatar,
        )
        let quoted = replyTo.map { source in
            WireReplySnippet(
                id: source.id,
                content: source.content,
                senderName: source.sender?.name ?? "",
                deleted: source.deletedAt != nil,
            )
        }
        return WireChatMessage(
            id: PulseOutboxEngine.tempMessageId(clientId: clientId),
            conversationId: conversationId,
            senderId: viewer.id,
            content: content,
            kind: "text",
            createdAt: PulseOutboxClock.now(),
            editedAt: nil,
            deletedAt: nil,
            sender: sender,
            reactions: nil,
            replyTo: quoted,
            parentId: parentId,
            imagePath: nil,
            audioPath: nil,
            durationMs: nil,
            filePath: nil,
            fileName: nil,
            fileSize: nil,
            pinnedAt: nil,
            viewOnce: nil,
            anon: nil,
            anonAlias: nil,
            viewedAt: nil,
            viewedBy: nil,
            transcript: nil,
            transcribedAt: nil,
            topicId: nil,
            linkUrl: nil,
            linkPreview: nil,
            poll: nil,
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Wave 8 — native wallpaper washes (web WALLPAPERS preview parity,
// settings-screen.tsx + chat-room.tsx wallpaperGlows). Each token is one
// soft diagonal gradient with light/dark variants; 'none' renders nothing
// so the plain page look stays.
// ─────────────────────────────────────────────────────────────
extension PulseWallpaper {
    func wash(dark: Bool) -> LinearGradient? {
        switch self {
        case .none:
            return nil
        case .aurora:
            return LinearGradient(
                colors: dark
                    ? [Color(hex: "064E3B"), Color(hex: "042F2E"), Color(hex: "047857")]   // emerald-900 → teal-950 → emerald-700
                    : [Color(hex: "A7F3D0"), Color(hex: "99F6E4"), Color(hex: "34D399")],  // emerald-200 → teal-200 → emerald-400
                startPoint: .topLeading, endPoint: .bottomTrailing,
            )
        case .dusk:
            return LinearGradient(
                colors: dark
                    ? [Color(hex: "451A03"), Color(hex: "4C0519"), Color(hex: "27272A")]   // amber-950 → rose-950 → zinc-800
                    : [Color(hex: "FDE68A"), Color(hex: "FDA4AF"), Color(hex: "A1A1AA")],  // amber-200 → rose-300 → zinc-400
                startPoint: .topLeading, endPoint: .bottomTrailing,
            )
        case .forest:
            return LinearGradient(
                colors: dark
                    ? [Color(hex: "052E16"), Color(hex: "064E3B"), Color(hex: "15803D")]   // green-950 → emerald-900 → green-700
                    : [Color(hex: "D9F99D"), Color(hex: "6EE7B7"), Color(hex: "22C55E")],  // lime-200 → emerald-300 → green-500
                startPoint: .topLeading, endPoint: .bottomTrailing,
            )
        case .mono:
            return LinearGradient(
                colors: dark
                    ? [Color(hex: "3F3F46"), Color(hex: "18181B")]                          // zinc-700 → zinc-900
                    : [Color(hex: "E4E4E7"), Color(hex: "A1A1AA")],                         // zinc-200 → zinc-400
                startPoint: .topLeading, endPoint: .bottomTrailing,
            )
        }
    }
}
