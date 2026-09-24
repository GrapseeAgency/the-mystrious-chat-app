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

/// R30-c — the unread divider (web chat-room.tsx:4418-4434 parity):
/// emerald hairlines around a tracking-widest UNREAD capsule.
struct UnreadDividerRow: View {
    var body: some View {
        HStack(spacing: 8) {
            Rectangle()
                .fill(PulseTheme.emerald.opacity(0.4))
                .frame(height: 1)
            Text("UNREAD")
                .font(.system(size: 10, weight: .bold))
                .kerning(1.6)
                .foregroundStyle(PulseTheme.emerald)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Capsule().fill(PulseTheme.emerald.opacity(0.10)))
            Rectangle()
                .fill(PulseTheme.emerald.opacity(0.4))
                .frame(height: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Unread messages")
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
    /// R30-c — the tap-time unread divider sits right above this row.
    let showUnreadDivider: Bool
    let onOpenImage: (WireChatMessage) -> Void
    let onOpenFile: (WireChatMessage) -> Void
    let onOpenThread: (WireChatMessage) -> Void
    let onForward: (WireChatMessage) -> Void
    let onInfo: (WireChatMessage) -> Void
    /// Wave 2 view-once — instant reveal (lightbox) + POST /viewed burn.
    let onViewOnce: (WireChatMessage) -> Void
    // ── Wave 7 — rich-object cards + message actions ──
    let wave7: Wave7RoomActions
    // R3-A item 7 — roster names drive the @mention chips in bubble bodies.
    let memberNames: [String]
    // R3-A item 4 — tap a reaction chip → who-reacted roster sheet.
    let onWhoReacted: (WireChatMessage, String) -> Void

    var body: some View {
        Group {
            // R30-c — the unread divider anchors at the FIRST message newer
            // than the frozen lastRead watermark (web buildItems parity).
            if showUnreadDivider {
                UnreadDividerRow()
                    .padding(.vertical, 4)
            }
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
                memberNames: memberNames,
                onReactionChip: onWhoReacted,
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
        // R1-W2B F-MD-06 — on-demand LLM translation (POST /translate);
        // the fresh row's translations render inline in the bubble.
        if message.kind == "text", message.deletedAt == nil, !message.id.hasPrefix("local_") {
            Button {
                viewModel.translate(message, session: session)
            } label: {
                Label(message.translations?.isEmpty == false ? "Translate again" : "Translate", systemImage: "character.bubble")
            }
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
        // R3-A item 11 — Chanty-style direct conversion (web chat-room.tsx
        // "Convert to task" :6372-6379: text rows only, not deleted, thread
        // roots only — the message itself becomes the board card via the
        // messageId POST, no form).
        if message.kind == "text" && message.deletedAt == nil && message.parentId == nil
            && !message.id.hasPrefix("local_") {
            Button {
                viewModel.convertMessageToTask(message, session: session)
            } label: {
                Label("Convert to task", systemImage: "checklist")
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
    // D30 camera capture — system camera + TCC state; D31 hold-to-record state.
    @State private var showCamera = false
    @State private var cameraDenied = false
    @State private var micDenied = false
    @State private var holdStarted = false
    @State private var holdCancelArmed = false
    // Wave 2 — poll builder sheet.
    @State private var pollBuilderOpen = false
    // ── Wave 7 — collaboration & hub surfaces ──
    @StateObject private var wave7 = Wave7RoomActions()
    @State private var leaderboardOpen = false
    // Wave 6 — DM safety-number sheet (F-CP-07/08) + the @-suggester (F-SM-04).
    @StateObject private var safetyBadges = PulseSafetyBadgeCache.shared
    @State private var safetyOpen = false
    // R2-D ITEM 1 — the DM info surface (web header-menu parity: TTL, screen
    // security, mute, theme + safety-number entries for DIRECT chats).
    @State private var dmInfoOpen = false

    // ── REM-B — group admin / scheduled / reactions / stickers / who-reacted ──
    @State private var groupInfoOpen = false
    @State private var scheduleOpen = false
    @State private var scheduledManagerOpen = false
    @State private var reactionTarget: WireChatMessage?
    @State private var stickerOpen = false
    @State private var whoReactedOpen = false
    @State private var whoReactedMessage: WireChatMessage?
    @State private var whoReactedEmoji = ""

    /// R3-A item 7 — roster display names for the @mention chips in bubble
    /// bodies (web memberNames stable-list parity).
    private var memberNames: [String] { conversation.members.map(\.name) }

    // ── R1-W2B — location share / conv themes / quick phrases ──
    @State private var locationOpen = false
    @State private var themeOpen = false
    @State private var phrasesOpen = false
    // R5-A Item 1 — composer emoji picker (draft-only; stickers send instantly).
    @State private var emojiPickerOpen = false

    // R2-B — the veil reads the app scene (the native blur/hidden signal).
    @Environment(\.scenePhase) private var scenePhase

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

    /// Wave 8 — the room background: the per-conversation theme override
    /// (R1-W2B F-FX-05) wins over the global prefs wallpaper (web
    /// effectiveConvWallpaper parity), and an optional tint layers a soft
    /// accent glow over it (web applyConvTint parity — conv-theme.ts:175).
    private var effectiveWallpaper: PulseWallpaper {
        if let raw = prefs.convThemes[conversation.id]?.wallpaper,
           let token = PulseWallpaper(rawValue: raw) {
            return token
        }
        return prefs.wallpaper
    }

    private var convTintGlow: Color? {
        guard let tint = prefs.convThemes[conversation.id]?.tint else { return nil }
        return Self.convTintColor(named: tint)
    }

    /// Web CONV_TINT_META glow colors — emerald/rose/amber/violet/teal.
    static func convTintColor(named tint: String) -> Color {
        switch tint {
        case "rose": return Color(red: 0.96, green: 0.25, blue: 0.37)
        case "amber": return Color(red: 0.96, green: 0.62, blue: 0.04)
        case "violet": return Color(red: 0.55, green: 0.36, blue: 0.97)
        case "teal": return Color(red: 0.08, green: 0.72, blue: 0.65)
        default: return PulseTheme.emerald
        }
    }

    /// R3-A item 8 — the four effect names (web EFFECT set verbatim:
    /// confetti/lasers/echo/sparkles) with SF glyphs for the attach-menu
    /// Effects submenu. The pick routes the EXISTING sendWithEffect engine.
    static let expressEffects: [(name: String, icon: String)] = [
        ("confetti", "party.popper"),
        ("lasers", "bolt"),
        ("echo", "dot.radiowaves.left.and.right"),
        ("sparkles", "sparkles"),
    ]

    private var wallpaperWash: some View {
        Group {
            if let wash = effectiveWallpaper.wash(dark: colorScheme == .dark) {
                wash
            }
            if let tint = convTintGlow {
                // The tint glow sits above the wallpaper (web replaces the
                // top glow color — visible even on the `none` wallpaper).
                LinearGradient(
                    colors: [tint.opacity(0.16), Color.clear],
                    startPoint: .top, endPoint: .center,
                )
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
            // REM-B F-MS-19 — header TTL chip when disappearing is on.
            if (conversation.ttlSeconds ?? 0) > 0 {
                HStack(spacing: 6) {
                    Image(systemName: "timer")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(PulseTheme.emerald)
                    Text("Disappearing · \(GroupInfoView.ttlLabel(conversation.ttlSeconds))")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(PulseTheme.textSecondary)
                    Spacer()
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 5)
                .background(PulseTheme.emerald.opacity(0.08))
                .accessibilityLabel("Disappearing messages enabled")
            }
            if searchOpen {
                roomSearchPanel
            }
            // R38/R42 — the veil covers ONLY the message river (header +
            // composer stay untouched, web parity). Stable modifier chain so
            // the scroll identity never resets across toggles.
            messagesList
                .blur(radius: veilEngaged ? 24 : 0)
                .overlay {
                    if veilEngaged {
                        veilCover
                    }
                }
                .allowsHitTesting(!veilEngaged)
            // R34-b — AI recap card pinned above the composer.
            if viewModel.recap != nil {
                recapCard
                    .padding(.horizontal, 12)
                    .padding(.bottom, 4)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            // F-SM-04 — @-suggester popover (roster, top-5 prefix match).
            if !mentionCandidates.isEmpty {
                mentionPopover()
            }
            // R3-A item 1 — the live '/' palette (web SlashPalette parity:
            // draft starts with '/', pick routes the outcome machine). The
            // palette and the @-suggester never compete — a '/' draft carries
            // no @-token at the tail.
            if slashPaletteVisible {
                SlashPaletteView(draft: viewModel.draft) { command in
                    pickSlashCommand(command)
                }
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
            // REM-B P1 — group info entry (member list, roles, invite,
            // rename, leave, TTL, slow mode) — groups only, web parity.
            if conversation.isGroup {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        PulseHaptics.tap()
                        groupInfoOpen = true
                    } label: {
                        Image(systemName: "info.circle")
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Group info")
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
            // R1-W2B D34 — the UNVERIFIED state also carries the tiny amber
            // dot (web R37 header parity, chat-room.tsx:3887-3892).
            if let partner = dmPartner {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        PulseHaptics.tap()
                        safetyOpen = true
                    } label: {
                        Image(systemName: safetyBadges.isVerified(partner.id) ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                            .foregroundStyle(safetyBadges.isVerified(partner.id) ? PulseTheme.emerald : PulseTheme.textSecondary)
                            .overlay(alignment: .topTrailing) {
                                if !safetyBadges.isVerified(partner.id) {
                                    Circle()
                                        .fill(PulseTheme.amber)
                                        .frame(width: 6, height: 6)
                                        .offset(x: 3, y: -3)
                                }
                            }
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel(safetyBadges.isVerified(partner.id) ? "Verified — open safety number" : "Not verified — open safety number")
                }
                // R2-D ITEM 1 — DM info entry (web header-menu parity: the
                // menu covers DMs with TTL + screen security + mute; iOS had
                // NO privacy surface for DMs before this). Opens the compact
                // RoomInfoSheet; groups keep their GroupInfoView branch.
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        PulseHaptics.tap()
                        dmInfoOpen = true
                    } label: {
                        Image(systemName: "info.circle")
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Chat info")
                }
            }
        }
        // R1-W2B F-FX-05 — per-conversation theme picker entry (web lives in
        // the room info page; native mirrors the chat toolbar for reach).
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    PulseHaptics.tap()
                    themeOpen = true
                } label: {
                    Image(systemName: "paintpalette")
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Chat theme")
            }
            // R34-b — AI recap entry (web header-menu "Recap with AI" parity;
            // the requestRecap ≥5 gate + 15 s auto-dismiss card handle the rest).
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    PulseHaptics.tap()
                    viewModel.requestRecap(session: session)
                } label: {
                    Image(systemName: "sparkles")
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Recap with AI")
            }
            // R1-W2I F-PI-03 — the pop-out mini-chat toggle (web chat-room
            // header PictureInPicture2 button): opens/closes this room's pane.
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    PulseHaptics.tap()
                    if session.pip.conversationId == conversation.id {
                        session.pip.close()
                    } else {
                        session.pip.open(conversation.id)
                    }
                } label: {
                    Image(systemName: session.pip.conversationId == conversation.id
                        ? "pip.fill" : "pip")
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Pop out mini chat")
            }
        }
        .sheet(isPresented: $safetyOpen) {
            if let partner = dmPartner {
                SafetySheetView(session: session, peer: partner)
            }
        }
        // R2-D ITEM 1 — the DM info sheet (groups keep GroupInfoView below;
        // the onDetailUpdated handoff matches that sheet's veil plumbing).
        .sheet(isPresented: $dmInfoOpen) {
            if let partner = dmPartner {
                RoomInfoSheet(
                    conversation: conversation,
                    partner: partner,
                    session: session,
                    prefs: prefs,
                    onDetailUpdated: { detail in
                        viewModel.roomScreenPrivacy = detail.screenPrivacy
                    },
                )
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
        .task {
            // R1-W2B F-MS-29 — the quick-phrase rail seeds on room open.
            viewModel.loadQuickPhrases(session: session)
        }
        .task {
            // R2-B R38/R42 — server truth for both veil flags on room open.
            await viewModel.loadPrivacyState(session: session)
        }
        .sheet(item: $threadRoot) { root in
            ThreadView(conversation: conversation, root: root, session: session)
        }
        .sheet(item: $forwardSource) { source in
            ForwardSheet(source: source, session: session)
        }
        .sheet(item: $infoTarget) { message in
            MessageInfoSheet(message: message, conversation: conversation, viewerId: session.viewer?.id)
        }
        .sheet(isPresented: $pinsOpen) {
            pinsList
        }
        .sheet(isPresented: $pollBuilderOpen) {
            PollBuilderSheet(viewModel: viewModel, session: session)
        }
        // ── REM-B sheet hosts ──
        .sheet(isPresented: $groupInfoOpen) {
            GroupInfoView(
                conversation: conversation,
                session: session,
                prefs: prefs,
                onDetailUpdated: { detail in
                    // R38 — the room-wide veil flag rides the info-sheet's
                    // fresh detail so the river re-veils without a refetch.
                    viewModel.roomScreenPrivacy = detail.screenPrivacy
                },
            )
        }
        .sheet(isPresented: $scheduleOpen) {
            ScheduleSheet { date in
                viewModel.scheduleDraft(for: date, session: session)
            }
        }
        .sheet(isPresented: $scheduledManagerOpen) {
            ScheduledManagerSheet(conversationId: conversation.id, session: session)
                .onDisappear { viewModel.loadScheduled(session: session) }
        }
        .sheet(item: $reactionTarget) { target in
            ReactionPickerSheet { emoji in
                viewModel.react(target, emoji: emoji, session: session)
            }
        }
        .sheet(isPresented: $stickerOpen) {
            StickerPickerSheet { emoji, pack in
                viewModel.sendSticker(emoji: emoji, pack: pack, session: session)
            }
        }
        // R5-A Item 1 — emoji picker (web chat-room.tsx:5385-5412 parity):
        // tap APPENDS to the draft (web setInput(prev => prev + emoji)) and
        // the focus hand-back on dismiss keeps the keyboard up (web
        // requestAnimationFrame(textareaRef.focus) parity).
        .sheet(isPresented: $emojiPickerOpen) {
            EmojiPickerSheet { emoji in
                viewModel.draft = viewModel.draft + emoji
            }
            .onDisappear { composerFocused = true }
        }
        .sheet(isPresented: $whoReactedOpen) {
            if let target = whoReactedMessage {
                WhoReactedSheet(
                    message: target,
                    emoji: whoReactedEmoji,
                    members: conversation.members,
                    viewerId: session.viewer?.id,
                    session: session,
                )
            }
        }
        // ── R1-W2B sheet hosts ──
        .sheet(isPresented: $locationOpen) {
            LocationShareSheet { lat, lng, label in
                viewModel.sendLocation(lat: lat, lng: lng, label: label, session: session)
            }
        }
        .sheet(isPresented: $themeOpen) {
            ConvThemeSheet(conversationId: conversation.id, prefs: prefs)
        }
        .sheet(isPresented: $phrasesOpen) {
            QuickPhrasesSheet(session: session) {
                viewModel.loadQuickPhrases(session: session)
            }
        }
        .onChange(of: viewModel.pendingSlashSheet) { _, name in
            guard let name else { return }
            viewModel.pendingSlashSheet = nil
            switch name {
            case "poll": pollBuilderOpen = true
            case "schedule": scheduleOpen = true
            case "sticker": stickerOpen = true
            case "whiteboard": wave7.whiteboardOpen = true
            case "redpacket": wave7.redPacketCreateOpen = true
            case "kanban": wave7.kanbanOpen = true
            case "events": wave7.eventsOpen = true
            case "game": wave7.gameCreateOpen = true
            case "tournament":
                if conversation.isGroup {
                    wave7.tournamentCreateOpen = true
                } else {
                    wave7.toast("Tournaments are for groups only", isError: true)
                }
            case "stage", "space":
                wave7.toast("Open the stage / space from the mic menu")
            case "location":
                locationOpen = true
            default: break
            }
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
                    // R1-W2G D44 — the sheet awaits the sync verdict: the
                    // draft store clears a stroke only on server confirmation.
                    await wave7.postStrokes(api: session.api, conversationId: conversation.id, strokes: strokes)
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
        // D30 camera capture — the shot flows through stageImage → the SAME
        // ≤1280px JPEG staged pipeline as the photo picker (web parity).
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker(
                onCapture: { image in
                    if let data = image.jpegData(compressionQuality: 0.95) {
                        viewModel.stageImage(data)
                    } else {
                        session.toasts.show("Couldn't read the camera photo")
                    }
                },
                onCancel: {},
            )
            .ignoresSafeArea()
        }
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
                            showUnreadDivider: (unreadDividerIndex ?? -1) == index,
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
                            memberNames: memberNames,
                            onWhoReacted: { message, emoji in
                                // R3-A item 4 — chip tap → the who-reacted roster.
                                whoReactedMessage = message
                                whoReactedEmoji = emoji
                                whoReactedOpen = true
                            },
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
                        .onAppear {
                            // R26 — the tail sentinel doubles as the native
                            // nearBottomRef: visible = the viewer sits at the
                            // bottom (didSet resets the missed badge).
                            viewModel.isTailVisible = true
                        }
                        .onDisappear {
                            viewModel.isTailVisible = false
                        }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: viewModel.messages.count) { _, _ in
                // R26 — the missed-badge machine + tail-id pill arming run
                // on every river change; the auto-scroll only fires while
                // the viewer sits at the bottom (web nearBottomRef parity —
                // reading history is never yanked to the tail).
                viewModel.noteRiverChanged()
                if viewModel.isTailVisible {
                    withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion)) {
                        proxy.scrollTo("tail", anchor: .bottom)
                    }
                }
            }
            .onChange(of: viewModel.jumpTargetId) { _, target in
                guard let target else { return }
                proxy.scrollTo(target, anchor: .center)
            }
            .onAppear {
                proxy.scrollTo("tail", anchor: .bottom)
            }
            // R26 — Telegram-style jump-to-latest pill (web chat-room
            // :4527-4568 parity): armed on off-screen arrivals, badge shows
            // the missed count, tap lands on the tail.
            .overlay(alignment: .bottom) {
                jumpPill(proxy: proxy)
            }
        }
    }

    // ── R2-B — jump pill · unread divider index · recap card · veil ──

    /// The tap-time anchor (frozen in the view model) drives the divider
    /// row index — nil when there is nothing unread or nothing qualifies.
    private var unreadDividerIndex: Int? {
        viewModel.unreadDividerRow(viewerId: session.viewer?.id)
    }

    @ViewBuilder
    private func jumpPill(proxy: ScrollViewProxy) -> some View {
        if viewModel.showJumpPill {
            Button {
                PulseHaptics.tap()
                viewModel.jumpToLatest()
                withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion)) {
                    proxy.scrollTo("tail", anchor: .bottom)
                }
            } label: {
                HStack(spacing: 6) {
                    Text("New messages")
                        .font(.caption.weight(.semibold))
                    Image(systemName: "arrow.down")
                        .font(.system(size: 11, weight: .bold))
                    if viewModel.missedCount > 0 {
                        Text(PulseRoomParityLogic.missedBadgeText(viewModel.missedCount))
                            .font(.system(size: 10, weight: .bold, design: .rounded).monospacedDigit())
                            .padding(.horizontal, 4)
                            .frame(minWidth: 18, minHeight: 18)
                            .background(Circle().fill(.white))
                            .foregroundStyle(PulseTheme.emerald)
                    }
                }
                .padding(.leading, 12)
                .padding(.trailing, 14)
                .padding(.vertical, 8)
                .foregroundStyle(.white)
                .background(Capsule().fill(PulseTheme.emerald))
                .shadow(color: PulseTheme.emerald.opacity(0.35), radius: 8, y: 3)
            }
            .buttonStyle(PulseButtonStyle())
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.horizontal, 12)
            .padding(.bottom, 6)
            .transition(.opacity.combined(with: .scale(scale: 0.9)))
            .accessibilityLabel(viewModel.missedCount > 0
                ? "Jump to newest messages — \(viewModel.missedCount) new"
                : "Jump to newest messages")
        } else {
            Color.clear.frame(height: 0)
            .accessibilityHidden(true)
        }
    }

    /// R34-b — the recap card (web :4835-4905 parity): sparkles chip, the
    /// "Summarizing…" loading state, the bullet summary with a copy button,
    /// and a dismiss control; auto-dismiss is armed by the view model.
    @ViewBuilder
    private var recapCard: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "sparkles")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(PulseTheme.violet700)
                .frame(width: 28, height: 28)
                .background(Circle().fill(PulseTheme.violet700.opacity(0.12)))
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text("AI recap")
                        .font(.caption.weight(.bold))
                    Spacer()
                    if case .ready(_, let basedOn)? = viewModel.recap {
                        Text("Based on \(basedOn) messages")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                        Button {
                            viewModel.copyRecap()
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 11, weight: .semibold))
                        }
                        .buttonStyle(.plain)
                        .tint(PulseTheme.emerald)
                        .accessibilityLabel("Copy recap")
                    }
                    Button {
                        viewModel.dismissRecap()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Dismiss recap")
                }
                switch viewModel.recap {
                case .loading:
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.mini)
                        Text("Reading the room…")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                case .ready:
                    Text(viewModel.recapText)
                        .font(.caption)
                        .foregroundStyle(.primary)
                        .lineSpacing(3)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                case nil:
                    EmptyView()
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.regularMaterial))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1),
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel("AI recap")
    }

    /// R38/R42 — the veil engages when EITHER flag is on; on iOS the
    /// "unfocused" state is scenePhase != .active (app switcher, another
    /// app, lock — web blur/hidden parity).
    private var veilEngaged: Bool {
        let eitherOn = viewModel.roomScreenPrivacy == true
            || (session.prefs?.screenPrivacy[conversation.id] == true)
        return eitherOn && scenePhase != .active
    }

    private var veilCover: some View {
        ZStack {
            Rectangle().fill(.regularMaterial)
            VStack(spacing: 10) {
                Image(systemName: "eye.slash")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(PulseTheme.emerald)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(PulseTheme.emerald.opacity(0.12)))
                Text("Screen security is on")
                    .font(.subheadline.weight(.semibold))
                Text("Messages are hidden while Pulse is not focused")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.thinMaterial))
            .padding(28)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Screen security is on — messages are hidden while Pulse is not focused")
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
    // D31 hold-to-record: the trailing slot (voiceSendSlot) is ALWAYS mounted
    // in the same HStack position — idle mic, recording send-arrow and plain
    // send are the SAME view with a swapped glyph — so a press gesture that
    // starts on the mic survives the recording bar appearing, and release
    // still lands where the finger went down.
    private var composer: some View {
        VStack(spacing: 0) {
            // R5-A Item 6 — the scheduled chip above the composer (web
            // chat-room.tsx:4726-4744): "{next} · {count} pending — tap to
            // manage", tap opens the manager sheet (the dead manager entry
            // point now has a real affordance too).
            if let chipText = viewModel.scheduledChipText {
                Button {
                    PulseHaptics.tap()
                    scheduledManagerOpen = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "calendar.badge.clock")
                            .font(.system(size: 12, weight: .semibold))
                        Text(chipText)
                            .font(.system(size: 11, weight: .medium))
                            .lineLimit(1)
                            .multilineTextAlignment(.leading)
                        Spacer()
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(PulseTheme.amber.opacity(0.10))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(PulseTheme.amber)
                .accessibilityLabel("Scheduled sends: \(chipText)")
            }
            if micDenied {
                deniedNotice(
                    "Microphone access is off — voice notes need it. Hold-to-record unlocks once it's on.",
                ) { micDenied = false }
            }
            if cameraDenied {
                deniedNotice("Camera access is off — allow it to take photos for this chat.") { cameraDenied = false }
            }
            // R2-D — the F-MS-20 slow-mode countdown: the chip appears the
            // moment the server's 429 arms the lock, counts the honest wait
            // down live (web chat-room.tsx slow-mode-chip parity) and
            // collapses when the ticker clears the lock.
            if viewModel.isSlowModeLocked {
                slowModeChip
            }
            // R3-A item 5 — the incognito hint (web anon-pill parity,
            // chat-room.tsx:4799-4833): groups only, X disarms the mask.
            if conversation.isGroup && viewModel.anonOn {
                incognitoPill
            }
            // R3-A item 3 — pending scheduled sends (web scheduledChip parity,
            // chat-room.tsx:4726-4741) — tap opens the manager drawer.
            if viewModel.scheduledCount > 0 {
                scheduledChip
            }
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

    /// R2-D — slow-mode countdown chip (web chat-room.tsx:4758-4770 copy
    /// verbatim, Gauge glyph + mm:ss countdown); role=status/live so the
    /// remaining time is announced as it ticks.
    private var slowModeChip: some View {
        HStack(spacing: 6) {
            Image(systemName: "gauge")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.emerald)
            Text("Slow mode — you can send again in \(PulseFormat.countdown(viewModel.slowModeRemainingSeconds))")
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(PulseTheme.textSecondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(
            Capsule()
                .fill(PulseTheme.glassFill)
                .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1)),
        )
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Slow mode — you can send again in \(viewModel.slowModeRemainingSeconds) seconds")
    }

    /// R3-A item 5 — the incognito hint pill (web anon-pill :4809-4830 copy
    /// verbatim): emerald wash + mask glyph + "hides your name" line + an X
    /// that disarms. Only mounted while the mask is armed in a GROUP.
    private var incognitoPill: some View {
        HStack(spacing: 6) {
            Image(systemName: "theatermasks.fill")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.emerald)
            Text("Incognito on — next message hides your name")
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(PulseTheme.textSecondary)
            Spacer(minLength: 0)
            Button {
                PulseHaptics.tap()
                viewModel.anonOn = false
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(PulseTheme.textTertiary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Turn off incognito")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(
            Capsule()
                .fill(PulseTheme.emerald.opacity(0.12))
                .overlay(Capsule().strokeBorder(PulseTheme.emerald.opacity(0.35), lineWidth: 1)),
        )
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
        .frame(maxWidth: .infinity)
    }

    /// R3-A item 3 — pending scheduled sends (web scheduledChip :4726-4741
    /// "N pending — tap to manage" parity; the web also stamps the next
    /// dispatch time, the iOS count-only chip notes that divergence).
    private var scheduledChip: some View {
        Button {
            PulseHaptics.tap()
            scheduledManagerOpen = true
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "clock")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(PulseTheme.amber)
                Text("\(viewModel.scheduledCount) pending — tap to manage")
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(PulseTheme.textSecondary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                Capsule()
                    .fill(PulseTheme.glassFill)
                    .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1)),
            )
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PulseButtonStyle())
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
        .accessibilityLabel("\(viewModel.scheduledCount) scheduled messages — open the manager")
    }

    // ── R3-A item 1 — the '/' palette wiring ─────────────────

    /// Web SlashPalette open-gate parity (chat-room.tsx:5200): the draft
    /// starts with '/' while the composer is in normal send mode. The
    /// @-suggester owns the popover slot when its candidates are visible.
    private var slashPaletteVisible: Bool {
        !broadcastLocked
            && viewModel.editingTarget == nil
            && !viewModel.isRecording
            && mentionCandidates.isEmpty
            && viewModel.draft.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("/")
    }

    /// Web runPaletteCommand parity (chat-room.tsx:3226-3330): the pick
    /// stages "/cmd + any typed args"; commands that take arguments wait for
    /// the send tap (the send path runs the SAME outcome machine), arg-less
    /// commands fire immediately through interpretSlashDraft so EVERY
    /// outcome (sheets / help / recap / remind / effects / errors) lands.
    private func pickSlashCommand(_ command: PulseRemediationLogic.SlashCommand) {
        let typedArgs = slashArgs(from: viewModel.draft)
        viewModel.draft = typedArgs.isEmpty ? "\(command.cmd) " : "\(command.cmd) \(typedArgs)"
        guard command.args.isEmpty else {
            composerFocused = true
            return
        }
        _ = viewModel.interpretSlashDraft(session: session)
    }

    /// The args after the leading "/token" (web replace(/^\/\S*\s*/, '')
    /// parity) — no closure predicates, plain token walk.
    private func slashArgs(from draft: String) -> String {
        var pieces: [String] = []
        var droppedFirst = false
        for piece in draft.split(separator: " ") {
            if !droppedFirst && piece.hasPrefix("/") {
                droppedFirst = true
                continue
            }
            pieces.append(String(piece))
        }
        return pieces.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    @ViewBuilder
    private var composerRows: some View {
            // R1-W2B F-MS-29 — the quick-phrase rail sits right above the
            // composer row (web spec row: "rail chips"; tap inserts the line).
            if !viewModel.quickPhrases.isEmpty {
                quickPhraseRail
            }
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
                if viewModel.isRecording {
                    // Recording bar replaces the composer TEXT (spec §1 row 11)
                    // — the trailing slot stays mounted for release-to-send.
                    VoiceRecordingBar(
                        elapsedText: PulseFormat.duration(viewModel.recordingElapsedMs),
                        amplitudes: viewModel.recordingAmplitudes,
                        cancelArmed: holdCancelArmed,
                        sending: viewModel.sendingVoice,
                        onCancel: {
                            PulseHaptics.tap()
                            viewModel.cancelVoiceRecording()
                        },
                    )
                } else {
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
                    // R2-D — the composer is VISIBLY locked while the
                    // slow-mode window runs (web send/mic disabled parity).
                    .disabled(viewModel.isSlowModeLocked)
                    .opacity(viewModel.isSlowModeLocked ? 0.55 : 1)
                    .overlay(alignment: .leading) {
                        if viewModel.isSlowModeLocked {
                            Image(systemName: "lock.fill")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(PulseTheme.textTertiary)
                                .padding(.leading, 16)
                                .transition(.opacity)
                        }
                    }

                    // R5-A Item 1 — Smile button (web composer row parity: the
                    // emoji popover sits between the input and the mic/send).
                    Button {
                        PulseHaptics.tap()
                        emojiPickerOpen = true
                    } label: {
                        Image(systemName: "face.smiling")
                            .font(.system(size: 22))
                            .foregroundStyle(Color.secondary)
                            .frame(width: 30, height: 42)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Insert emoji")
                }

                voiceSendSlot
                    // R2-D — mic/send refuses touches while the slow-mode
                    // window runs (web disabled={slowRemaining > 0} parity).
                    .opacity(viewModel.isSlowModeLocked ? 0.45 : 1)
                    .allowsHitTesting(!viewModel.isSlowModeLocked)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
    }

    /// D31 — the ALWAYS-MOUNTED trailing composer slot. One Image node whose
    /// glyph/style swap by state keeps view identity (and the in-flight press
    /// gesture) alive across idle → recording:
    ///   • blank draft → mic: press-and-hold records; release sends; slide
    ///     left past 80pt arms cancel ("Release to cancel" in the bar).
    ///   • recording → send arrow: release sends; a quick tap also sends (the
    ///     post-grant case where the TCC prompt swallowed the finger lift).
    ///   • draft text / staged / editing → plain send (tap, prior behavior).
    /// ONE gesture (DragGesture minimumDistance 0) owns the slot — a competing
    /// TapGesture would never win recognition, so taps route through onEnded.
    private var voiceSendSlot: some View {
        ZStack {
            Image(systemName: viewModel.isRecording || !viewModel.canStartVoiceRecording ? "arrow.up.circle.fill" : "mic.circle.fill")
                .font(.system(size: 32))
                .foregroundStyle(slotStyle)
                .gesture(holdGesture)
            if viewModel.sendingVoice && !viewModel.isRecording {
                ProgressView().tint(PulseTheme.emerald)
            }
        }
        .accessibilityLabel(
            viewModel.isRecording
                ? "Release to send voice note — slide left to cancel"
                : (viewModel.canStartVoiceRecording ? "Record voice note" : "Send"),
        )
    }

    private var slotStyle: AnyShapeStyle {
        if viewModel.isRecording { return AnyShapeStyle(PulseTheme.gradient(named: "emerald")) }
        if viewModel.canStartVoiceRecording { return AnyShapeStyle(PulseTheme.emerald) }
        return viewModel.canSend
            ? AnyShapeStyle(PulseTheme.gradient(named: "emerald"))
            : AnyShapeStyle(Color.secondary.opacity(0.4))
    }

    /// D31 press-and-hold recorder gesture — minimumDistance 0 so a plain
    /// press counts; translation tracks the slide-to-cancel arm (leftward).
    private var holdGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if !holdStarted {
                    guard viewModel.canStartVoiceRecording else { return }
                    holdStarted = true
                    holdCancelArmed = false
                    PulseHaptics.tap()
                    viewModel.startVoiceRecording(session: session)
                    return
                }
                let armed = value.translation.width < -80
                if armed != holdCancelArmed {
                    holdCancelArmed = armed
                    if armed { PulseHaptics.tap() }
                }
            }
            .onEnded { _ in
                if !holdStarted {
                    // Text-mode tap (drag with zero distance) — plain send.
                    sendSlotTap()
                    return
                }
                holdStarted = false
                let cancelled = holdCancelArmed
                holdCancelArmed = false
                viewModel.endVoiceHold(cancelled: cancelled, session: session)
            }
    }

    /// Slot tap — sends while recording (release already processed, e.g. the
    /// TCC prompt case) or the plain send/edit/staged dispatch when text mode.
    private func sendSlotTap() {
        if holdStarted { return }
        if viewModel.isRecording {
            viewModel.finishAndSendVoice(session: session)
            return
        }
        guard !viewModel.canStartVoiceRecording else { return }
        PulseHaptics.tap()
        if viewModel.editingTarget != nil {
            viewModel.saveEdit(session: session)
        } else if viewModel.staged != nil {
            viewModel.sendStaged(session: session)
        } else {
            // R3-A item 1 — the send tap is a LIVE slash trigger (web Enter
            // parity: submit runs parseComposerInput first). A leading '/'
            // command is consumed by the outcome machine; plain text falls
            // through to the ordinary send.
            if viewModel.interpretSlashDraft(session: session) { return }
            viewModel.send(session: session)
        }
    }

    /// D30/D31 — the honest inline permission explainer (no crash, no dead
    /// end): an explanatory row above the composer with a Settings jump.
    private func deniedNotice(_ message: String, dismiss: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "lock.fill")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.orange)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            .font(.caption.weight(.semibold))
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(.thinMaterial)
    }

    private var attachMenu: some View {
        Menu {
            Button {
                showPhotoPicker = true
            } label: {
                Label("Photo Library", systemImage: "photo")
            }
            // D30 — take a full-resolution shot with the system camera; the
            // file flows through the same staged upload path as the library.
            Button {
                CameraPicker.requestAccess { granted in
                    DispatchQueue.main.async {
                        if granted {
                            showCamera = true
                        } else {
                            cameraDenied = true
                        }
                    }
                }
            } label: {
                Label("Camera", systemImage: "camera")
            }
            Button {
                showFileImporter = true
            } label: {
                Label("Document", systemImage: "folder")
            }
            // R1-W2B F-MD-07 — location share (web composer attach parity,
            // chat-room.tsx composer palette + '/location').
            Button {
                locationOpen = true
            } label: {
                Label("Location", systemImage: "location.fill")
            }
            // R3-A item 3 — scheduled sends UI: arm the draft (ScheduleSheet)
            // and manage the pending rows (ScheduledManagerSheet: list +
            // cancel). Web tray 'Schedule' + manager drawer parity.
            Button {
                scheduleOpen = true
            } label: {
                Label("Schedule send", systemImage: "calendar.badge.clock")
            }
            Button {
                scheduledManagerOpen = true
            } label: {
                Label("Scheduled sends", systemImage: "clock.arrow.circlepath")
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
            // ── R3-A items 2/5/8 — the Express section (web tray 'Express'
            // group parity): sticker packs, the effect-flagged sends and the
            // incognito mask (groups only) — every former-dead surface now
            // has a discoverable entry.
            Section("Express") {
                Button {
                    stickerOpen = true
                } label: {
                    Label("Stickers", systemImage: "face.smiling")
                }
                Menu {
                    ForEach(Self.expressEffects, id: \.name) { effect in
                        Button {
                            viewModel.sendEffect(effect: effect.name, session: session)
                        } label: {
                            Label(effect.name.capitalized, systemImage: effect.icon)
                        }
                    }
                } label: {
                    Label("Effects", systemImage: "sparkles")
                }
                if conversation.isGroup {
                    Button {
                        PulseHaptics.tap()
                        viewModel.anonOn.toggle()
                    } label: {
                        Label(
                            viewModel.anonOn ? "Incognito on — tap to send under your name" : "Incognito",
                            systemImage: viewModel.anonOn ? "theatermasks.fill" : "theatermasks",
                        )
                    }
                }
            }
        } label: {
            Image(systemName: viewModel.staged == nil ? "plus.circle.fill" : "minus.circle.fill")
                .font(.system(size: 26))
                .foregroundStyle(.secondary)
        }
        .disabled(viewModel.editingTarget != nil)
        .accessibilityLabel("Attach")
    }

    // ── R1-W2B F-MS-29 — the quick-phrase rail ───────────────
    // One-tap lines above the composer (tap inserts at the caret end); the
    // trailing "⋯" chip opens the manage sheet (add/delete rides the API).

    private var quickPhraseRail: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(viewModel.quickPhrases) { phrase in
                    Button {
                        insertPhrase(phrase)
                    } label: {
                        Text(phrase.text)
                            .font(.system(size: 12.5, weight: .semibold))
                            .foregroundStyle(PulseTheme.titleOnPanel)
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(Capsule().fill(Color(.secondarySystemBackground)))
                            .overlay(Capsule().strokeBorder(PulseTheme.hairlineStrong, lineWidth: 1))
                    }
                    .buttonStyle(PulseButtonStyle())
                    .disabled(viewModel.editingTarget != nil)
                    .accessibilityLabel("Insert \(phrase.text)")
                }
                Button {
                    PulseHaptics.tap()
                    phrasesOpen = true
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 12.5, weight: .bold))
                        .foregroundStyle(PulseTheme.emerald)
                        .frame(width: 30, height: 30)
                        .background(Capsule().fill(PulseTheme.emerald.opacity(0.10)))
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel("Manage quick phrases")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
        }
    }

    private func insertPhrase(_ phrase: WireQuickPhrase) {
        PulseHaptics.tap()
        let current = viewModel.draft
        guard !current.isEmpty else {
            viewModel.draft = phrase.text
            return
        }
        let needsSpace = !current.hasSuffix(" ") && !current.hasSuffix("\n")
        viewModel.draft = current + (needsSpace ? " " : "") + phrase.text
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
/// R2-D — the sheet mirrors the web/Android delivered split: "Seen by" =
/// lastReadAt >= createdAt, "Delivered to" = the rest (viewer excluded).
private struct MessageInfoSheet: View {
    let message: WireChatMessage
    let conversation: WireConversationSummary
    /// R2-D — the viewer id (excluded from both receipt lists, web parity).
    var viewerId: String? = nil

    @Environment(\.dismiss) private var dismiss

    private var receipts: PulseRoomParityLogic.ReceiptSplit {
        PulseRoomParityLogic.receiptSplit(
            members: conversation.members.map {
                PulseRoomParityLogic.ReceiptMember(id: $0.id, lastReadAtIso: $0.lastReadAt)
            },
            viewerId: viewerId,
            createdAtIso: message.createdAt,
        )
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
                let split = receipts
                let seenTitle = "Seen by · \(split.seenBy.count)"
                Section {
                    if split.seenBy.isEmpty {
                        Text("No read receipts yet")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(split.seenBy, id: \.id) { member in
                        receiptRow(member, seen: true)
                    }
                } header: {
                    Text(seenTitle)
                }
                // R2-D — "Delivered to" = everyone who hasn't read it yet
                // (web chat-room.tsx:5940-5957 + Android MessageSheets parity).
                Section {
                    if split.deliveredTo.isEmpty {
                        Text("Everyone has seen this message")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(split.deliveredTo, id: \.id) { member in
                        receiptRow(member, seen: false)
                    }
                } header: {
                    Text("Delivered to · \(split.deliveredTo.count)")
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

    /// One receipt row — read watermark stamp + the double-check (seen) or
    /// plain check (delivered) glyph, web seen-by-sheet rhythm.
    private func receiptRow(_ member: PulseRoomParityLogic.ReceiptMember, seen: Bool) -> some View {
        let summary = conversation.members.first(where: { $0.id == member.id })
        return HStack(spacing: 10) {
            PulseAvatar(
                name: summary?.name ?? "Member",
                color: PulseTheme.color(named: summary?.color),
                photoURL: PulseTheme.photoURL(summary?.avatar),
                size: 30,
            )
            VStack(alignment: .leading, spacing: 1) {
                Text(summary?.name ?? "Member")
                    .font(.subheadline)
                Text(seen
                    ? "Read at \(PulseFormat.listStamp(member.lastReadAtIso))"
                    : "Delivered")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: seen ? "checkmark.circle.fill" : "checkmark.circle")
                .foregroundStyle(seen ? PulseTheme.emerald : PulseTheme.textTertiary)
        }
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
    // R3-A item 7 — roster names drive the @mention chips in body text.
    var memberNames: [String] = []
    // R3-A item 4 — tap a reaction chip → who-reacted roster sheet (web
    // chat-room.tsx ReactionChip tap → reactionInfo parity). nil = decorative.
    var onReactionChip: ((WireChatMessage, String) -> Void)? = nil

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
                // R1-W2B F-MD-06 — persisted LLM translation under the
                // original (web TranslationLine parity: italic secondary
                // strip with the globe glyph; chat-room.tsx:6913-6955).
                if let translations = message.translations, let first = translations.first {
                    translationStrip(first)
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
            case "location":
                locationContent
            default:
                // R3-A items 6/7 — the web BubbleText outcome natively: jumbo
                // solo-emoji rows render oversized plain text; everything else
                // flows through the ported FORMAT_RE formatter + mention chips.
                if PulseRemediationLogic.isJumboEmoji(message.content) {
                    Text(message.content)
                        .font(.system(size: 34))
                        .lineSpacing(2)
                        .textSelection(.enabled)
                        .accessibilityLabel("Emoji message: \(message.content)")
                } else {
                    PulseBubbleBody(
                        content: message.content,
                        cacheKey: message.id,
                        memberNames: memberNames,
                        mine: mine,
                    )
                }
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

    // ── R1-W2B F-MD-06 — translation strip ───────────────────

    /// The first persisted translation rendered under the original text —
    /// secondary italic with a globe glyph (web TranslationLine parity).
    private func translationStrip(_ translation: WireTranslation) -> some View {
        HStack(alignment: .top, spacing: 5) {
            Image(systemName: "globe")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(mine ? Color.white.opacity(0.85) : PulseTheme.emerald)
            VStack(alignment: .leading, spacing: 1) {
                Text(translation.text)
                    .font(.footnote.italic())
                    .foregroundStyle(mine ? Color.white.opacity(0.88) : Color.primary.opacity(0.7))
                    .textSelection(.enabled)
                Text(translation.lang.uppercased())
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(mine ? Color.white.opacity(0.6) : PulseTheme.textTertiary)
            }
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 9).fill(mine ? Color.white.opacity(0.14) : PulseTheme.emerald.opacity(0.06)))
    }

    // ── R1-W2B F-MD-07 — location card ───────────────────────

    /// kind "location" rows render the stylized map card (web LocationBubble
    /// parity — pure vector art, no tile servers); tapping opens Apple Maps
    /// at the pin (spec F-MD-07 "Map card tap → platform maps").
    @ViewBuilder
    private var locationContent: some View {
        if let loc = PulseRemediationLogic.locationOfPayload(message.payload) {
            Button {
                if let url = PulseRemediationLogic.appleMapsURL(lat: loc.lat, lng: loc.lng, label: loc.label) {
                    UIApplication.shared.open(url)
                }
            } label: {
                VStack(alignment: .leading, spacing: 6) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(mine ? Color.white.opacity(0.18) : PulseTheme.emerald.opacity(0.10))
                        // Graticule grid — the pin's spot comes from the real
                        // coordinates (equirectangular projection, web parity).
                        VStack(spacing: 18) {
                            ForEach(0..<4, id: \.self) { _ in
                                Rectangle()
                                    .fill((mine ? Color.white : PulseTheme.emerald).opacity(0.16))
                                    .frame(height: 1)
                            }
                        }
                        HStack(spacing: 26) {
                            ForEach(0..<5, id: \.self) { _ in
                                Rectangle()
                                    .fill((mine ? Color.white : PulseTheme.emerald).opacity(0.16))
                                    .frame(width: 1)
                            }
                        }
                        VStack(spacing: 3) {
                            Image(systemName: "mappin.circle.fill")
                                .font(.system(size: 30))
                                .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
                        }
                    }
                    .frame(width: 216, height: 116)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(loc.label.isEmpty ? "Location" : loc.label)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(mine ? Color.white : PulseTheme.titleOnPanel)
                            .lineLimit(1)
                        Text(PulseRemediationLogic.coordinateText(lat: loc.lat, lng: loc.lng))
                            .font(.caption2)
                            .foregroundStyle(mine ? Color.white.opacity(0.8) : PulseTheme.textSecondary)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Location pin \(loc.label) — open in Maps")
        } else {
            // Corrupt/legacy payload — degrade to the plain content text.
            Text(message.content)
                .font(.body)
                .textSelection(.enabled)
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
                // R3-A item 4 — the chip is a Button whenever the room wires
                // the who-reacted sheet (web ReactionChip long-press → drawer
                // parity, native affordance = tap). Without the callback the
                // chip stays decorative (threads).
                Button {
                    PulseHaptics.tap()
                    onReactionChip?(message, group.emoji)
                } label: {
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
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    onReactionChip == nil
                        ? "\(group.count) reactions with \(group.emoji)"
                        : "Who reacted with \(group.emoji) — \(group.count) people",
                )
            }
        }
        .offset(y: 12)
    }

    private func reactionChipsHeight() -> CGFloat {
        reactions.isEmpty ? 0 : 14
    }
}

/// R3-A items 6/7 — the web BubbleText outcome natively. Runs come from the
/// tested pure logic (PulseBubbleTextLogic = buildMentionRuns + the ported
/// FORMAT_RE parser + the R4-A splitUrlSegments composition); the renderer:
///   • concatenates consecutive inline runs into ONE SwiftUI Text (perfect
///     flow for the common bold/italic/code/mention cases),
///   • renders ```pre``` blocks and ||spoiler|| runs as detached blocks —
///     spoilers blur + tap-reveal (web SpoilerSpan :6804-6834 parity),
///   • R4-A item 1 — url runs ride AttributedString `.link` so they render
///     as tappable links INSIDE the composed text (web renderPlain anchors,
///     :6873-6896). One attributed Text per inline chunk keeps every
///     per-run attribute (including links) intact — Text-concatenation
///     fragments cannot reliably carry per-run links.
/// Performance: the parsed run list is NSCache'd per message id + roster
/// signature (PulseBubbleTextLogic.cachedRuns) so LazyVStack re-renders
/// never re-scan long bodies.
struct PulseBubbleBody: View {
    let content: String
    let cacheKey: String
    let memberNames: [String]
    let mine: Bool

    @State private var revealedSpoilers: Set<Int> = []

    var body: some View {
        let runs = PulseBubbleTextLogic.cachedRuns(key: cacheKey, content: content, memberNames: memberNames)
        VStack(alignment: .leading, spacing: 3) {
            ForEach(Array(blocked(runs).enumerated()), id: \.offset) { _, block in
                chunkView(block)
            }
        }
        .textSelection(.enabled)
    }

    /// One rendered chunk: a pure inline run list → one attributed Text;
    /// a pre/spoiler run → its dedicated block view.
    @ViewBuilder
    private func chunkView(_ chunk: [PulseBubbleTextLogic.Run]) -> some View {
        if let first = chunk.first {
            switch first.style {
            case .pre:
                preBlock(first.text)
            case .spoiler:
                spoilerBlock(first.text, id: spoilerId(first.text))
            default:
                inlineText(chunk)
            }
        }
    }

    /// R4-A item 1 — inline chunks assemble as ONE AttributedString so a
    /// `.link` run stays tappable inside the composed Text (the previous
    /// Text(a)+Text(b) fragments could not carry per-run links). Attribute
    /// parity with the R3 styled() modifiers is preserved run-for-run.
    private func inlineText(_ runs: [PulseBubbleTextLogic.Run]) -> Text {
        var attributed = AttributedString()
        for run in runs {
            attributed += styled(run)
        }
        return Text(attributed)
    }

    /// Web run styling parity (BubbleText :6898-6967): bold/italic/underline/
    /// strike, mono code chips, emerald mention chips — plus R4-A url runs
    /// as real anchors (underline, mine = white, else emerald-700/400;
    /// www. opens prefixed https:// exactly like the web href :6879). Run
    /// backgrounds make the chips readable on BOTH bubble fills (web
    /// bg-emerald-500/20).
    private func styled(_ run: PulseBubbleTextLogic.Run) -> AttributedString {
        var piece = AttributedString(run.text)
        switch run.style {
        case .plain:
            break
        case .bold:
            piece.inlinePresentationIntent = .stronglyEmphasized
        case .italic:
            piece.inlinePresentationIntent = .emphasized
        case .underline:
            piece.underlineStyle = .single
        case .strike:
            piece.strikethroughStyle = .single
        case .code:
            piece.font = .system(.callout, design: .monospaced)
            piece.backgroundColor = mine ? Color.white.opacity(0.20) : Color.primary.opacity(0.07)
        case .pre:
            break
        case .spoiler:
            break
        case .mention:
            piece.inlinePresentationIntent = .stronglyEmphasized
            piece.foregroundColor = PulseTheme.emeraldDeep
            piece.backgroundColor = PulseTheme.emerald.opacity(0.20)
        case .url:
            // Web renderPlain :6876-6891 — href https://-prefixes www.,
            // underline, text-white on my bubbles / emerald-700 light and
            // emerald-400 dark otherwise. A token the URL parser cannot
            // promote to a URL degrades to styled text (honest, no crash).
            piece.link = Self.linkURL(for: run.text)
            piece.underlineStyle = .single
            piece.foregroundColor = mine ? Color.white : PulseTheme.bubbleLink
        }
        return piece
    }

    /// Web anchor href parity: "www." tokens open as https://www.… (the
    /// regex keeps scheme-less tokens only when they start with www.);
    /// http(s):// tokens open as-is. Returns nil for unsalvageable tokens.
    private static func linkURL(for value: String) -> URL? {
        if value.hasPrefix("www.") {
            return URL(string: "https://" + value)
        }
        return URL(string: value)
    }

    /// ```pre``` — block-level mono card (web :6917-6928).
    private func preBlock(_ text: String) -> some View {
        Text(text)
            .font(.system(.callout, design: .monospaced))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(mine ? Color.black.opacity(0.20) : Color.primary.opacity(0.06)),
            )
            .accessibilityLabel("Code block")
    }

    /// ||spoiler|| — blur-reveal (web SpoilerSpan: 5px blur + wash, tap once).
    private func spoilerBlock(_ text: String, id: Int) -> some View {
        let revealed = revealedSpoilers.contains(id)
        return Text(text)
            .padding(.horizontal, 3)
            .padding(.vertical, 1)
            .background(
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(revealed ? Color.clear : (mine ? Color.white.opacity(0.25) : Color.secondary.opacity(0.20))),
            )
            .blur(radius: revealed ? 0 : 5)
            .onTapGesture {
                guard !revealed else { return }
                PulseHaptics.tap()
                revealedSpoilers.insert(id)
            }
            .accessibilityLabel(revealed ? text : "Hidden spoiler — tap to reveal")
    }

    /// Spoiler ids must survive re-renders even when the SAME text appears
    /// twice — key on the run text (revealed state is per-row @State anyway).
    private func spoilerId(_ text: String) -> Int {
        PulseTheme.hashString(text)
    }

    /// Groups consecutive runs so pre/spoiler split the flow into blocks and
    /// everything else concatenates inline (web <p> semantics).
    private func blocked(_ runs: [PulseBubbleTextLogic.Run]) -> [[PulseBubbleTextLogic.Run]] {
        var blocks: [[PulseBubbleTextLogic.Run]] = []
        var inline: [PulseBubbleTextLogic.Run] = []
        for run in runs {
            if run.style == .pre || run.style == .spoiler {
                if !inline.isEmpty {
                    blocks.append(inline)
                    inline = []
                }
                blocks.append([run])
            } else {
                inline.append(run)
            }
        }
        if !inline.isEmpty {
            blocks.append(inline)
        }
        return blocks
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

    // ── REM-B — scheduled / slow-mode / incognito / slash ──
    /// F-MS-18 — the viewer's pending scheduled rows for THIS room (the list
    /// feeds the composer banner chip; the manager sheet lists details).
    /// R5-A Item 6 — the chip now also surfaces the SOONEST non-cancelled
    /// row's relative stamp (web scheduledChip parity), so the full list
    /// replaces the bare count.
    @Published private(set) var scheduledItems: [WireScheduledItem] = []
    /// F-MS-18 — pending-row count for the chip (R5-A: derived from the list).
    var scheduledCount: Int { scheduledItems.count }
    /// R5-A Item 6 — web scheduledChip parity: "{next} · {count} pending —
    /// tap to manage" where next = the soonest non-cancelled row's stamp
    /// (web formatListStamp(items[0]); the API sorts pending soonest-first).
    var scheduledChipText: String? {
        guard !scheduledItems.isEmpty else { return nil }
        let next = PulseFormat.listStamp(PulseScheduledChip.nextIso(in: scheduledItems))
        if next.isEmpty {
            return "\(scheduledCount) pending — tap to manage"
        }
        return "\(next) · \(scheduledCount) pending — tap to manage"
    }
    /// F-MS-20 — slow-mode lockout: after a 429 the composer refuses sends
    /// until the server-suggested window elapses (countdown chip in the UI).
    @Published private(set) var slowModeLockUntil: Date?
    @Published private(set) var slowModeRemainingSeconds = 0
    /// F-MS-17 — incognito mask (groups only): sends ride anon:true and the
    /// optimistic bubble shows the deterministic FNV-1a alias.
    @Published var anonOn = false
    /// F-MS-22 — sheet command armed by the slash palette ('/poll' etc.) —
    /// the view consumes it in onChange to present the right surface.
    @Published var pendingSlashSheet: String?
    /// R1-W2B F-MS-29 — the viewer's quick phrases for the composer rail
    /// (server CRUD via /api/users/{id}/phrases; the manage sheet reloads
    /// this through loadQuickPhrases after every mutation).
    @Published private(set) var quickPhrases: [WireQuickPhrase] = []

    // ── R2-B — unread divider · jump-pill missed count · AI recap · veil ──
    /// R30-c — the tap-time unread anchor (web chats-tab.tsx:431-435 freeze):
    /// MY lastReadAt from the route-carried summary when the row showed an
    /// unread badge, else nil — nil means the divider never places.
    private let unreadAnchorMs: Double?
    /// R26 — off-screen arrivals badge on the jump-to-latest pill (the web
    /// missedCountRef machine chat-room.tsx:1594-1612; the pure step lives
    /// in PulseRoomParityLogic.missedStep).
    @Published private(set) var missedCount = 0
    private var lastSeenLen = 0
    private var lastTailId: String?
    /// The tail sentinel row is on-screen — the viewer sits at the bottom
    /// (the native nearBottomRef). Reaching the bottom resets the badge.
    @Published var isTailVisible = true {
        didSet {
            guard oldValue != isTailVisible, isTailVisible else { return }
            missedCount = 0
            lastSeenLen = messages.count
            showJumpPill = false
        }
    }
    /// Web showJump — armed when a new tail lands while scrolled away,
    /// cleared at the bottom (never by the scroll position alone).
    @Published private(set) var showJumpPill = false
    /// R34-b — live recap card content; nil = no card. Loading while the
    /// LLM summarizes, then the returned summary; auto-dismisses after 15 s.
    enum RecapPhase: Equatable { case loading; case ready(text: String, basedOn: Int) }
    @Published private(set) var recap: RecapPhase?
    private var recapDismissTask: Task<Void, Never>?
    /// R38 — the room-wide screen-security switch (server conversation flag;
    /// nil = unknown / older relay → veil off).
    @Published var roomScreenPrivacy: Bool?

    // Wave 2 — voice recording, playback, polls, transcription, topics.
    @Published private(set) var isRecording = false
    @Published private(set) var recordingElapsedMs: Double = 0
    @Published private(set) var sendingVoice = false
    /// D31 — live mic levels (0…1) sampled every 100 ms while held; the
    /// composer renders the last VoiceMath.liveWaveformBars as bars.
    @Published private(set) var recordingAmplitudes: [Double] = []
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
    /// D31 — the session that armed the hold; the auto-send-at-cap tick needs
    /// it when the finger is still down and cannot deliver it.
    private weak var voiceSession: PulseSession?
    private var topicTicker: AnyCancellable?
    private var voiceLocalFiles: [String: URL] = [:]
    /// One active voice-note player for the whole room (messageId-keyed).
    private(set) var playback = VoicePlaybackManager()

    var canSend: Bool {
        if staged != nil { return true }
        return !draft.trimmingCharacters(in: .whitespaces).isEmpty && !isSlowModeLocked
    }

    /// F-MS-20 — the composer is locked while the slow-mode window runs.
    var isSlowModeLocked: Bool {
        (slowModeLockUntil ?? .distantPast) > Date()
    }

    /// F-MS-18 — scheduled-row refresh (open + after arming/cancelling).
    func loadScheduled(session: PulseSession) {
        Task { [weak self] in
            guard let self else { return }
            self.scheduledItems = (try? await session.api.scheduledMessages(conversationId: conversationId)) ?? []
        }
    }

    private var slowModeTicker: AnyCancellable?

    private func armSlowModeLock(seconds: Int) {
        slowModeLockUntil = Date().addingTimeInterval(Double(seconds))
        slowModeRemainingSeconds = seconds
        slowModeTicker?.cancel()
        slowModeTicker = Timer.publish(every: 1, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                let remaining = Int((self.slowModeLockUntil ?? .distantPast).timeIntervalSinceNow)
                if remaining <= 0 {
                    self.slowModeLockUntil = nil
                    self.slowModeRemainingSeconds = 0
                    self.slowModeTicker?.cancel()
                    self.slowModeTicker = nil
                } else {
                    self.slowModeRemainingSeconds = remaining
                }
            }
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
        // R2-B R30-c — freeze the unread anchor from the list summary the
        // route carried (web freezes the same values at tap time).
        let viewerId = session.viewer?.id
        self.unreadAnchorMs = PulseRoomParityLogic.unreadAnchorMs(
            myLastReadAtIso: conversation.members.first(where: { $0.id == viewerId })?.lastReadAt,
            unreadCount: conversation.unreadCount,
        )
        self.roomScreenPrivacy = conversation.screenPrivacy
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
        // REM-B F-MS-18 — pending scheduled count for the composer banner.
        loadScheduled(session: session)
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
                    // REM-B F-MS-23 — effect-flagged rows burst full-screen.
                    fireIncomingEffectIfAny(message, viewerId: session.viewer?.id, session: session)
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
                    fireIncomingEffectIfAny(message, viewerId: session.viewer?.id, session: session)
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
            // D47 delta sync — the route accepts `since=<ISO>` and answers
            // with only the rows STRICTLY NEWER than the cursor (same shape).
            // First load (no window on screen yet) keeps the full newest
            // window; every later refresh fetches just the tail.
            let deltaCursor = deltaSyncCursor
            let page = try await session.api.messages(conversationId: conversationId, since: deltaCursor)
            if deltaCursor != nil {
                mergeDelta(page.messages)
            } else {
                messages = riverRows(from: page.messages)
                hasMore = page.hasMore
            }
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

    /// D47 — the delta-sync cursor: the newest REAL (non-optimistic) row's
    /// wire createdAt ISO, exactly what the route's `since` expects. Local
    /// `local_` rows are excluded — their client-side stamps must never gate
    /// what the server considers "newer". Nil = no real rows → full load.
    private var deltaSyncCursor: String? {
        let real = messages.filter { !$0.id.hasPrefix("local_") }
        guard let newest = real.max(by: {
            let lhs = PulseFormat.date($0.createdAt) ?? .distantPast
            let rhs = PulseFormat.date($1.createdAt) ?? .distantPast
            return lhs < rhs
        }) else { return nil }
        return newest.createdAt
    }

    /// D47 — merges a delta page into the river: server rows the window
    /// doesn't know yet are appended id-dedupe + re-sorted ascending (the
    /// same rhythm loadOlder uses). Edits/deletes keep flowing through the
    /// socket signals — this path only ever ADDS missing rows.
    private func mergeDelta(_ fresh: [WireChatMessage]) {
        let rows = riverRows(from: fresh)
        guard !rows.isEmpty else { return }
        let known = Set(messages.map(\.id))
        let additions = rows.filter { !known.contains($0.id) }
        guard !additions.isEmpty else { return }
        for row in additions {
            upsert(row)
        }
        noteRiverChanged()
    }

    /// The main river EXCLUDES thread replies (web parity) — they live in
    /// ThreadView and surface as "N replies" chips on the parent bubble.
    /// Wave 2 topics: activeTopicId == nil → General = the WHOLE room;
    /// otherwise only rows filed under that topic (spec §1 row 9).
    /// REM-B F-MS-19 — rows whose TTL already elapsed never render.
    private func riverRows(from rows: [WireChatMessage]) -> [WireChatMessage] {
        rows.filter { row in
            guard row.parentId == nil else { return false }
            guard PulseRemediationLogic.isAlive(row.expiresAt) else { return false }
            if let active = activeTopicId {
                return row.topicId == active
            }
            return true
        }
    }

    /// Live topic-view hygiene — drops rows that stopped matching the active
    /// filter (e.g. an envelope re-mapped a row's topic). No-op on General.
    private func refilterInPlace() {
        let expired = messages.filter { !PulseRemediationLogic.isAlive($0.expiresAt) }
        if !expired.isEmpty {
            let alive = messages.filter { PulseRemediationLogic.isAlive($0.expiresAt) }
            messages = alive
        }
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

    // ── R2-B — jump-pill missed machine + unread divider anchor ──

    /// R26 — fed on EVERY river length change by the view (the web effects
    /// on messages.data + lastMessageId, chat-room.tsx:1543-1612): the badge
    /// grows while away, resets at the bottom, and the pill arms only when a
    /// NEW tail lands while the viewer is scrolled away. History prepends
    /// (load older) grow the badge silently without arming the pill — the
    /// web's exact behavior (the pill show is tail-id driven).
    func noteRiverChanged() {
        let newLen = messages.count
        if isTailVisible {
            missedCount = 0
            lastSeenLen = newLen
        } else if newLen > lastSeenLen {
            missedCount += newLen - lastSeenLen
            lastSeenLen = newLen
        } else if newLen < lastSeenLen {
            // room switch / cache reset — badge drops
            lastSeenLen = newLen
            missedCount = 0
        }
        let tailId = messages.last?.id
        if tailId != lastTailId {
            let isArrival = lastTailId != nil
            lastTailId = tailId
            if isArrival, !isTailVisible {
                showJumpPill = true
                PulseHaptics.tap()
            }
        }
    }

    /// Pill tap — drop the badge, land on the tail (web jump button parity).
    func jumpToLatest() {
        showJumpPill = false
        missedCount = 0
        lastSeenLen = messages.count
    }

    /// R30-c — the index the unread divider row sits BEFORE (web
    /// buildItems parity). Nil = anchor absent or nothing qualifies.
    func unreadDividerRow(viewerId: String?) -> Int? {
        PulseRoomParityLogic.unreadDividerIndex(
            messages: messages,
            viewerId: viewerId,
            anchorMs: unreadAnchorMs,
        )
    }

    // ── R2-B R34-b — AI recap ──

    /// Web requestRecap parity: the ≥5-live-messages gate toasts locally,
    /// then POST /api/ai/recap fills the card; failures surface verbatim.
    func requestRecap(session: PulseSession) {
        if case .loading = recap { return }
        let liveCount = messages.filter { $0.deletedAt == nil }.count
        guard PulseRoomParityLogic.recapGatePassed(liveCount: liveCount) else {
            session.toasts.show("Recap needs at least 5 messages in this chat")
            return
        }
        recap = .loading
        Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await session.api.aiRecap(conversationId: self.conversationId)
                self.recap = .ready(text: result.recap, basedOn: result.basedOn ?? 0)
                PulseHaptics.tap()
                self.scheduleRecapDismiss()
            } catch {
                self.recap = nil
                session.toasts.show(RoomViewModel.describe(error))
            }
        }
    }

    /// Auto-dismiss after 15 s so the card never outstays its welcome.
    private func scheduleRecapDismiss() {
        recapDismissTask?.cancel()
        recapDismissTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            guard let self, !Task.isCancelled else { return }
            withAnimation { self.recap = nil }
        }
    }

    func dismissRecap() {
        recapDismissTask?.cancel()
        recapDismissTask = nil
        withAnimation { recap = nil }
    }

    func copyRecap() {
        if case .ready(let text, _)? = recap {
            UIPasteboard.general.string = text
            PulseHaptics.tap()
            session?.toasts.show("Recap copied")
        }
    }

    /// The returned summary once the card holds a result (nil while loading).
    var recapText: String? {
        if case .ready(let text, _)? = recap { return text }
        return nil
    }

    // ── R2-B R38/R42 — screen security (veil) ──

    /// One quiet detail GET on room open (web detail-query parity): refresh
    /// the room-wide flag + adopt the server's per-viewer flag into the
    /// prefs map. Local state stays authoritative until this lands.
    func loadPrivacyState(session: PulseSession) async {
        let viewerId = session.viewer?.id ?? ""
        guard !viewerId.isEmpty, let detail = try? await session.api.conversationDetail(id: conversationId, userId: viewerId) else { return }
        roomScreenPrivacy = detail.screenPrivacy
        if let mine = detail.myScreenPrivacy {
            session.prefs?.adoptServerScreenPrivacy(conversationId: conversationId, on: mine)
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
        // REM-B F-MS-19 — expired rows never surface.
        guard PulseRemediationLogic.isAlive(message.expiresAt) else { return }
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
        // REM-B F-MS-17 — groups only: armed mask + the server's deterministic
        // FNV-1a alias (the optimistic bubble shows the exact stored alias).
        let anonAlias = (isGroupRoom && anonOn) ? PulseRemediationLogic.anonAlias(viewerId: viewer.id, conversationId: conversationId) : nil
        // R4-A item 2 — armed state captured AT SEND TIME (Android
        // `anonArmed` parity) so an in-flight toggle can't desync the
        // one-shot verdict.
        let wasAnonArmed = anonAlias != nil
        let temp = TempMessages.make(
            conversationId: conversationId,
            viewer: viewer,
            clientId: clientId,
            content: body,
            parentId: nil,
            replyTo: replySource,
            anon: anonAlias != nil,
            anonAlias: anonAlias,
        )
        upsert(temp)
        try? session.store?.upsert(messages: [temp])
        Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await session.api.sendMessageWithStreak(
                    conversationId: conversationId,
                    content: body,
                    replyToId: replyId,
                    topicId: activeTopicId,
                    anon: anonAlias != nil,
                )
                self.swapTemp(temp.id, for: result.message, session: session)
                self.clearDraft(session: session)
                // R5-A Item 5 — streak nudge (web chat-room.tsx:1736-1751):
                // fires ONLY when THIS send GREW the streak (second-or-later
                // consecutive day). Same-day re-sends carry no streak sibling
                // and restarts carry continued=false — both stay silent here,
                // mirrored from PulseStreakVerdict.
                if let line = PulseStreakVerdict.toastLine(
                    count: result.streak?.count,
                    continued: result.streak?.continued,
                ) {
                    session.toasts.show(line)
                }
                // R4-A item 2 — ONE-SHOT incognito: a server-accepted send
                // consumes the mask and the hint pill hides with it (web
                // chat-room.tsx:1747-1751 onSuccess; Android R3-B one-shot
                // disarm, ChatRoomViewModel.kt:566-570). Gated on the
                // send-time arming like Android — a plain send never touches
                // a mask the user armed mid-flight.
                if wasAnonArmed {
                    self.anonOn = PulseRoomParityLogic.anonDisarmAfterSend(armed: wasAnonArmed, serverAccepted: true)
                }
                session.particles.fire(kind: .burst, count: 22)
                session.emitTyping(conversationId: conversationId, recipients: [], isTyping: false)
                // Wave 2 topics — own send bumps Topic.lastMessageAt server-side.
                self.loadTopics(session: session)
                // Wave 2 link previews — spec §1 row 8: the ORIGINAL SENDER's
                // client triggers /unfurl once, fire-and-forget, ignoring the
                // result (nil is valid = nothing link-ish / host unreachable).
                if UnfurlTrigger.matches(body) {
                    self.triggerUnfurl(for: result.message, viewerId: viewer.id, session: session)
                }
            } catch {
                // REM-B F-MS-20 — slow mode: 429 + retryAfter arms the
                // composer countdown lock (web backoff parity).
                if let failure = error as? PulseAPIClient.Failure,
                   failure.status == 429, let wait = failure.retryAfter, wait > 0 {
                    self.messages.removeAll { $0.id == temp.id }
                    try? session.store?.deleteMessage(id: temp.id)
                    self.draft = body
                    self.armSlowModeLock(seconds: wait)
                    self.errorText = Self.describe(error)
                } else if PulseOutboxEngine.isDroppable(error) {
                    self.messages.removeAll { $0.id == temp.id }
                    try? session.store?.deleteMessage(id: temp.id)
                    self.errorText = Self.describe(error)
                } else {
                    // R4-A privacy repair (orchestrator) — an incognito send
                    // NEVER rides the outbox: the queue payload is
                    // content-only, so a later flush would post the text
                    // UN-masked (identity leak). Android parity (R3-B
                    // PulseRepositoryImpl :812-818): retract the optimistic
                    // bubble, keep the draft, keep the mask armed — privacy
                    // over delivery.
                    if wasAnonArmed {
                        self.messages.removeAll { $0.id == temp.id }
                        try? session.store?.deleteMessage(id: temp.id)
                        self.draft = body
                        session.toasts.show("Incognito needs a live connection — message kept in the composer")
                    } else {
                        // Temp row stays (queued clock) — outbox flush reconciles.
                        // R4-A item 2 — a QUEUED send is not a server-accepted
                        // send: the mask stays armed (PulseRoomParityLogic
                        // .anonDisarmAfterSend false branch — Android
                        // :560-564 parity; the manual disarm path above the
                        // composer still works).
                        session.enqueueOutbox(conversationId: conversationId, clientId: clientId, content: body)
                        session.toasts.show("Message queued — sends when you're back online")
                    }
                }
            }
        }
    }

    // ── REM-B F-MS-22 — slash commands ─────────────────────

    /// Interprets a leading '/' command from the draft. Returns TRUE when the
    /// draft was consumed (sheet opened / error shown / effect sent) — FALSE
    /// when the plain send path should proceed (text transform applied).
    func interpretSlashDraft(session: PulseSession) -> Bool {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.hasPrefix("/") else { return false }
        switch PulseRemediationLogic.applySlash(text) {
        case .send(let content):
            if content != text { draft = content }
            return false
        case .error(let message):
            errorText = message
            return true
        case .help:
            session.toasts.show("Try /me /shrug /roll /poll /schedule /sticker /effects confetti — type '/' for the full list")
            return true
        case .sheet(let name):
            pendingSlashSheet = name
            draft = ""
            return true
        case .topic(let name):
            createTopic(name: name, emoji: "📌", session: session)
            draft = ""
            return true
        case .remind:
            session.toasts.show("Reminders ride \"Remind me…\" on any message")
            return true
        case .recap:
            // R34-b — /recap rides the same requestRecap as the header entry.
            draft = ""
            requestRecap(session: session)
            return true
        case .effect(let name, let content):
            draft = ""
            // R3-A item 8 — the web send path can never fire an effect with
            // an empty body (its send button is disabled on empty input), so
            // the machine mirrors that honestly instead of POSTing "".
            guard !content.isEmpty else {
                session.toasts.show("Type the message first — the effect rides your send")
                return true
            }
            sendWithEffect(effect: name, content: content, session: session)
            return true
        }
    }

    /// R3-A item 8 — attach-menu Effects entry: the CURRENT draft rides the
    /// EXISTING sendWithEffect engine (optimistic temp → payload {effect} →
    /// particle burst). An empty draft surfaces the honest toast instead of
    /// a silent no-op; reply/edit/staged state blocks like the plain send.
    func sendEffect(effect: String, session: PulseSession) {
        let body = draft.trimmingCharacters(in: .whitespaces)
        guard !body.isEmpty else {
            session.toasts.show("Type the message first — the effect rides your send")
            return
        }
        guard editingTarget == nil, staged == nil else { return }
        draft = ""
        replyTarget = nil
        typingStopTask?.cancel()
        typingStopTask = nil
        session.emitTyping(conversationId: conversationId, recipients: [], isTyping: false)
        sendWithEffect(effect: effect, content: body, session: session)
    }

    // ── R3-A item 11 — Chanty-style message → task conversion ──

    /// Re-entry guard while a conversion POST is in flight (the context menu
    /// has no spinner; a second tap inside the window is ignored).
    private var convertingTaskIds: Set<String> = []

    /// Web convertToTask parity (chat-room.tsx:2100-2122): the message itself
    /// becomes a board card via POST /api/conversations/{id}/kanban with
    /// { userId, messageId } — the server derives the title (first 80 chars)
    /// and keeps sourceMessageId provenance. Honest toast on BOTH outcomes;
    /// the success burst mirrors web fireParticles({kind:'burst',count:40}).
    func convertMessageToTask(_ message: WireChatMessage, session: PulseSession) {
        PulseHaptics.tap()
        guard !convertingTaskIds.contains(message.id) else { return }
        convertingTaskIds.insert(message.id)
        Task { [weak self] in
            guard let self else { return }
            defer { self.convertingTaskIds.remove(message.id) }
            do {
                let card = try await session.api.createKanbanCard(
                    conversationId: conversationId,
                    title: nil,
                    column: nil,
                    assigneeId: nil,
                    messageId: message.id,
                )
                session.toasts.show("Task \"\(card.title ?? "")\" created from message")
                session.particles.fire(kind: .burst, count: 40)
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    /// F-MS-22/23 — effect-flagged send: payload { effect } rides kind text;
    /// the sender's client fires the burst locally (web fireParticles parity).
    private func sendWithEffect(effect: String, content: String, session: PulseSession) {
        guard let viewer = session.viewer, editingTarget == nil, staged == nil else { return }
        let clientId = UUID().uuidString
        let temp = TempMessages.make(
            conversationId: conversationId,
            viewer: viewer,
            clientId: clientId,
            content: content,
            parentId: nil,
        )
        upsert(temp)
        try? session.store?.upsert(messages: [temp])
        Task { [weak self] in
            guard let self else { return }
            do {
                let message = try await session.api.sendMessage(
                    conversationId: conversationId,
                    content: content,
                    topicId: activeTopicId,
                    payload: ["effect": effect],
                )
                self.swapTemp(temp.id, for: message, session: session)
                if let kindName = PulseRemediationLogic.particleKind(forEffect: effect) {
                    session.particles.fire(kind: Self.particleKind(named: kindName), count: 90, center: CGPoint(x: 0.5, y: 0.85))
                }
                self.loadTopics(session: session)
            } catch {
                self.messages.removeAll { $0.id == temp.id }
                try? session.store?.deleteMessage(id: temp.id)
                self.errorText = Self.describe(error)
            }
        }
    }

    // ── REM-B F-MS-24 — stickers ───────────────────────────

    /// kind "sticker" + payload { emoji, pack } (web sticker-picker parity;
    /// the bubble renders the emoji big). Online-only like all rich kinds.
    func sendSticker(emoji: String, pack: String, session: PulseSession) {
        guard let viewer = session.viewer else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let message = try await session.api.sendMessage(
                    conversationId: conversationId,
                    content: emoji,
                    kind: "sticker",
                    topicId: activeTopicId,
                    payload: ["emoji": emoji, "pack": pack],
                )
                self.upsert(message)
                try? session.store?.upsert(messages: [message])
                self.loadTopics(session: session)
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    // ── R1-W2B F-MD-06 — translate ───────────────────────

    /// POST /api/messages/{id}/translate (text rows only) — the fresh row
    /// carries message.translations; the bubble renders the strip inline.
    /// Peers receive the same row via the translation:added relay envelope.
    func translate(_ message: WireChatMessage, session: PulseSession) {
        PulseHaptics.tap()
        guard message.kind == "text", message.deletedAt == nil,
              !message.id.hasPrefix("local_"),
              !transcriptionBusy.contains(message.id) else { return }
        transcriptionBusy.insert(message.id)
        Task { [weak self] in
            defer { self?.transcriptionBusy.remove(message.id) }
            guard let self else { return }
            do {
                let fresh = try await session.api.translateMessage(id: message.id)
                try? session.store?.upsert(messages: [fresh])
                self.upsert(fresh)
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    // ── R1-W2B F-MD-07 — location send ───────────────────

    /// kind "location" + payload { lat, lng, label } (web sendLocation parity,
    /// chat-room.tsx:3120-3138; content rides empty like the web). Online-only
    /// like every rich kind — failures surface the honest toast.
    func sendLocation(lat: Double, lng: Double, label: String, session: PulseSession) {
        guard session.viewer != nil else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let message = try await session.api.sendMessage(
                    conversationId: self.conversationId,
                    content: "",
                    kind: "location",
                    topicId: self.activeTopicId,
                    payload: ["lat": lat, "lng": lng, "label": label],
                )
                self.upsert(message)
                try? session.store?.upsert(messages: [message])
                self.loadTopics(session: session)
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    // ── R1-W2B F-MS-29 — quick phrases ───────────────────

    func loadQuickPhrases(session: PulseSession) {
        guard session.viewer != nil else { return }
        Task { [weak self] in
            let rows = (try? await session.api.quickPhrases()) ?? []
            await MainActor.run { self?.quickPhrases = rows }
        }
    }

    // ── REM-B F-MS-18 — schedule the composer draft ────────

    func scheduleDraft(for date: Date, session: PulseSession) {
        let body = draft.trimmingCharacters(in: .whitespaces)
        guard !body.isEmpty else {
            session.toasts.show("Type the message first — scheduling sends the draft")
            return
        }
        let iso = ScheduleSheet.isoFormatter.string(from: date)
        Task { [weak self] in
            guard let self else { return }
            do {
                // R5-A Item 6 — the returned row joins the cached list (no
                // refetch) so the chip count + next-dispatch stamp update.
                let item = try await session.api.scheduleMessage(conversationId: conversationId, content: body, scheduledAtIso: iso)
                self.draft = ""
                self.scheduledItems.append(item)
                try? session.store?.deleteDraft(conversationId: conversationId)
                PulseHaptics.success()
                session.toasts.show("Scheduled — \(PulseFormat.dayLabel(iso)) \(PulseFormat.clockTime(iso))")
            } catch {
                session.toasts.show(Self.describe(error))
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

    /// D31 hold-to-record — called the moment the mic slot receives a press.
    /// Mic TCC first; if the prompt swallows the finger lift the recording
    /// simply continues with the explicit bar (cancel X + slot tap to send).
    func startVoiceRecording(session: PulseSession) {
        guard !isRecording, voiceRecorder == nil else { return }
        voiceSession = session
        Task { [weak self] in
            guard let self else { return }
            let granted = await VoiceRecorder.requestPermission()
            guard granted else {
                self.voiceSession = nil
                session.toasts.show("Microphone access is off — enable it in Settings to record voice notes")
                return
            }
            guard !self.isRecording, self.voiceRecorder == nil else { return }
            let recorder = VoiceRecorder()
            do {
                try recorder.start()
            } catch {
                self.voiceSession = nil
                session.toasts.show("Couldn't start recording")
                return
            }
            self.voiceRecorder = recorder
            self.recordingStartedAt = Date()
            self.recordingElapsedMs = 0
            self.recordingAmplitudes = []
            self.isRecording = true
            self.startRecordingTicker()
        }
    }

    /// D31 — hold finished: slide-away cancels (discard), plain release sends.
    func endVoiceHold(cancelled: Bool, session: PulseSession) {
        if cancelled {
            cancelVoiceRecording()
        } else {
            finishAndSendVoice(session: session)
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
        recordingAmplitudes = []
        voiceSession = nil
    }

    /// 100 ms sweep — wall-clock elapsed + LIVE metering (averagePower →
    /// normalized 0…1 into the ring buffer for the waveform bars). At the
    /// wire cap (server refuses durationMs > 600000) the take auto-sends
    /// instead of recording a note the gateway would reject (D31).
    private func startRecordingTicker() {
        stopRecordingTicker()
        recordingTicker = Timer.publish(every: 0.1, on: .main, in: .common)
            .autoconnect()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self, let startedAt = self.recordingStartedAt else { return }
                let elapsed = Date().timeIntervalSince(startedAt) * 1000
                self.recordingElapsedMs = elapsed
                if let power = self.voiceRecorder?.averagePower() {
                    // −50dB…0dB → 0…1 (anything below −50dB reads as silence).
                    let normalized = Double(max(0, min(1, (power + 50) / 50)))
                    self.recordingAmplitudes.append(normalized)
                    if self.recordingAmplitudes.count > VoiceMath.liveWaveformBars {
                        self.recordingAmplitudes.removeFirst(self.recordingAmplitudes.count - VoiceMath.liveWaveformBars)
                    }
                }
                if elapsed >= VoiceMath.maximumSendMs, let session = self.voiceSession {
                    self.finishAndSendVoice(session: session)
                }
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

    /// F-MS-23 — web EFFECT_PARTICLES name → native bus kind.
    private static func particleKind(named name: String) -> ParticleBus.Kind {
        switch name {
        case "confetti": return .confetti
        case "stars": return .stars
        default: return .burst
        }
    }

    /// F-MS-23 — incoming effect-flagged rows fire the burst for rows OTHERS
    /// sent (own sends fire on the success path). messageNew + envelopes.
    private func fireIncomingEffectIfAny(_ message: WireChatMessage, viewerId: String?, session: PulseSession) {
        guard message.senderId != viewerId,
              let effect = PulseRemediationLogic.effectOfPayload(message.payload),
              let kindName = PulseRemediationLogic.particleKind(forEffect: effect) else { return }
        session.particles.fire(kind: Self.particleKind(named: kindName), count: 90, center: CGPoint(x: 0.5, y: 0.85))
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
        anon: Bool = false,
        anonAlias: String? = nil,
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
            anon: anon ? true : nil,
            anonAlias: anonAlias,
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
// R1-W2B F-FX-05 — per-conversation theme sheet (web conv-theme-picker.tsx
// parity). Wallpaper swatches reuse the exact global token set + the same
// wash previews; optional accent tint chips layer over the wallpaper;
// "Reset to default" removes the override entirely. Writes go through the
// settings PATCH funnel (prefs blob key chat.convThemes) — the same server
// path the web picker uses, so both surfaces converge.
// ─────────────────────────────────────────────────────────────
struct ConvThemeSheet: View {
    let conversationId: String
    @ObservedObject var prefs: PulsePrefs

    @Environment(\.dismiss) private var dismiss

    private var override: WireConvTheme? {
        prefs.convThemes[conversationId]
    }

    /// The EFFECTIVE wallpaper (override ?? global) — the selected swatch
    /// matches what the room actually renders (web parity).
    private var effectiveWallpaper: PulseWallpaper {
        if let raw = override?.wallpaper, let token = PulseWallpaper(rawValue: raw) {
            return token
        }
        return prefs.wallpaper
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("WALLPAPER")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.secondary)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 5), spacing: 10) {
                            ForEach(PulseWallpaper.allCases, id: \.self) { token in
                                swatch(token)
                            }
                        }
                        Text(override == nil
                            ? "Following Appearance default · \(prefs.wallpaper.rawValue)"
                            : "Custom for this chat only")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("ACCENT TINT")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.secondary)
                        HStack(spacing: 10) {
                            ForEach(PulsePrefs.convTints, id: \.self) { tint in
                                tintChip(tint)
                            }
                        }
                        Text("Layers a soft glow over the wallpaper — also visible on None.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    if override != nil {
                        Button {
                            PulseHaptics.tap()
                            prefs.clearConvTheme(conversationId: conversationId)
                        } label: {
                            Label("Reset to default", systemImage: "arrow.counterclockwise")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.red)
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .background(RoundedRectangle(cornerRadius: 14).fill(Color.red.opacity(0.08)))
                        }
                        .buttonStyle(PulseButtonStyle())
                    }
                }
                .padding(16)
            }
            .background(PulseTheme.pageWash.ignoresSafeArea())
            .navigationTitle("Chat theme")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    /// Wallpaper swatch — the room's own wash preview (light + dark aware of
    /// nothing here; the gradient IS the room background source of truth).
    private func swatch(_ token: PulseWallpaper) -> some View {
        let selected = effectiveWallpaper == token
        let isOverride = override?.wallpaper == token.rawValue
        return Button {
            PulseHaptics.tap()
            prefs.setConvTheme(conversationId: conversationId, wallpaper: token, tint: nil)
        } label: {
            VStack(spacing: 5) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(
                        token.wash(dark: false).map { AnyShapeStyle($0) }
                            ?? AnyShapeStyle(Color(.secondarySystemBackground)),
                    )
                    .frame(height: 54)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(selected ? PulseTheme.emerald : PulseTheme.hairlineStrong, lineWidth: selected ? 2 : 1),
                    )
                    .overlay(alignment: .topTrailing) {
                        if isOverride {
                            Circle()
                                .fill(PulseTheme.emerald)
                                .frame(width: 6, height: 6)
                                .padding(4)
                        }
                    }
                Text(token.rawValue.capitalized)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(selected ? PulseTheme.emerald : PulseTheme.textSecondary)
            }
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("\(token.rawValue.capitalized) wallpaper")
    }

    private func tintChip(_ tint: String) -> some View {
        let selected = override?.tint == tint
        return Button {
            PulseHaptics.tap()
            if selected {
                // Tapping the active tint clears it (web patch.tint:null).
                prefs.setConvTheme(conversationId: conversationId, wallpaper: nil, tint: nil)
            } else {
                prefs.setConvTheme(conversationId: conversationId, wallpaper: nil, tint: tint)
            }
        } label: {
            Circle()
                .fill(RoomContent.convTintColor(named: tint))
                .frame(width: 32, height: 32)
                .overlay(
                    Circle().strokeBorder(selected ? Color.primary : Color.clear, lineWidth: 2).padding(2),
                )
        }
        .buttonStyle(PulseButtonStyle())
        .accessibilityLabel("\(tint.capitalized) tint")
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
