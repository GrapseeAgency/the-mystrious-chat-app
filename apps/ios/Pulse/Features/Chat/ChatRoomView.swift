import SwiftUI
import UIKit
import Combine
import PhotosUI

/// Chat room - native rebuild of the web conversation surface. Same outcome:
/// emerald gradient bubbles for the viewer, material bubbles for others,
/// reply quotes, grouped reaction chips, voice waveform chips, read state,
/// live typing with drifting dots. Apple mechanics: ScrollViewReader anchor,
/// contextMenu reactions, FocusState composer, socket-driven updates.
/// Wave 8 - the room consumes the server-synced PulsePrefs blob: the
/// wallpaper wash background, the bubble corner token (md/lg/pill) and the
/// row density spacing.
struct ChatRoomView: View {
    let conversation: WireConversationSummary
    @ObservedObject var session: PulseSession
    @ObservedObject var prefs: PulsePrefs
    /// Global-search jump - the room scrolls to + flashes this message after
    /// its initial load (bounded history expansion if it sits out of window).
    var jumpMessageId: String? = nil
    var onLeft: () -> Void

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
                    onLeft: onLeft,
                )
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(roomTitle)
        .navigationBarTitleDisplayMode(.inline)
        // EMB-I - the header chrome melts into the ember ground (the same
        // #1C1410 92% surface as the dock and composer), never a mint tint.
        .toolbarBackground(PulseTheme.emberChrome.opacity(0.92), for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .onAppear {
            if viewModel == nil {
                viewModel = RoomViewModel(conversation: conversation, session: session, initialJumpMessageId: jumpMessageId)
            }
            // The dock hides itself while a room owns the screen (web §12).
            session.roomVisible = true
            // Wave 8 - the incoming-attention gate treats THIS room as read.
            session.activeRoomId = conversation.id
            viewModel?.updateReadBoundary()
            viewModel?.requestRefresh(session: session)
            // 3-d - the group-call engine tracks the OPEN room: this drives
            // the 20s ongoing-call probe + the 'Ongoing group call' banner
            // (web useGroupCallSession options.conversationId parity). Groups
            // only - DMs never host group calls.
            if conversation.isGroup {
                session.groupCallEngine?.setActiveConversation(conversation.id)
            }
        }
        .onDisappear {
            session.roomVisible = false
            viewModel?.updateReadViewport(visibleIDs: [], tailVisible: false)
            if session.activeRoomId == conversation.id {
                session.activeRoomId = nil
            }
            // 3-d - room closed: the probe + banner stand down for THIS room
            // (the engine keeps an in-flight call alive regardless).
            if conversation.isGroup,
               session.groupCallEngine?.activeConversationId == conversation.id {
                session.groupCallEngine?.setActiveConversation(nil)
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

/// R30-c - the unread divider (web chat-room.tsx:4418-4434 parity):
/// emerald hairlines around a tracking-widest UNREAD capsule.
struct UnreadDividerRow: View {
    var body: some View {
        HStack(spacing: 8) {
            Rectangle()
                .fill(PulseTheme.emberGlowTop.opacity(0.4))
                .frame(height: 1)
            Text("UNREAD")
                .font(.system(size: 10, weight: .bold))
                .kerning(1.6)
                .foregroundStyle(PulseTheme.emberGlowTop)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Capsule().fill(PulseTheme.emberGlowTop.opacity(0.10)))
            Rectangle()
                .fill(PulseTheme.emberGlowTop.opacity(0.4))
                .frame(height: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Unread messages")
    }
}

/// One river row - day chip + bubble + context menu + pagination trigger.
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
    /// Wave 8 - prefs bubble corner token (md/lg/pill).
    let bubbleRadius: PulseBubbleRadius
    /// R30-c - the tap-time unread divider sits right above this row.
    let showUnreadDivider: Bool
    // R7 - web buildItems cluster verdict for THIS row: head rows carry the
    // sender name (web MessageRow `head` gate), non-head rows tighten the
    // stack gap by `clusterTighten` (web mt-px/mt-0.5 parity).
    let cluster: PulseCluster.Flags
    let clusterTighten: CGFloat
    let onOpenImage: (WireChatMessage) -> Void
    let onOpenFile: (WireChatMessage) -> Void
    let onOpenThread: (WireChatMessage) -> Void
    let onForward: (WireChatMessage) -> Void
    let onInfo: (WireChatMessage) -> Void
    /// Reveal only after the server grants this tap one-time access.
    let onViewOnce: (WireChatMessage) -> Void
    // ── Wave 7 - rich-object cards + message actions ──
    let wave7: Wave7RoomActions
    // R3-A item 7 - roster names drive the @mention chips in bubble bodies.
    let memberNames: [String]
    // R3-A item 4 - tap a reaction chip → who-reacted roster sheet.
    let onWhoReacted: (WireChatMessage, String) -> Void

    var body: some View {
        Group {
            // R30-c - the unread divider anchors at the FIRST message newer
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
            bubble
            // R7 - non-head clustered rows pull the bubble up (negative top
            // padding inside the LazyVStack gap): cozy 6→2, compact 2→1 - the
            // web's mt-px/mt-0.5 clustered rhythm vs the mt-2.5 head gap.
            // Attached to the bubble only (a Group modifier would also hit the
            // optional divider/day-chip children).
            .padding(.top, cluster.head ? 0 : -clusterTighten)
            .contextMenu { contextMenu } preview: {
                bubble
                    .padding(8)
                    .background(PulseTheme.emberChrome)
                    .allowsHitTesting(false)
                    .onAppear { session.setTransientReadObscured(contextMenuReadID, true) }
                    .onDisappear { session.setTransientReadObscured(contextMenuReadID, false) }
            }
            .onDisappear { session.setTransientReadObscured(contextMenuReadID, false) }
            .onAppear {
                // Oldest rendered row reaching the viewport = page older
                // history (the VM gates reentrancy/hasMore).
                if index == 0 {
                    viewModel.loadOlder(session: session)
                }
            }
        }
    }

    @State private var contextMenuReadID = UUID()

    private var bubble: some View {
    BubbleView(
        message: message,
        mine: message.senderId == session.viewer?.id,
        groupChat: conversation.isGroup,
        viewerColor: { colorOf(message.senderId) },
        seen: viewModel.isSeen(message),
        seenLabel: viewModel.readLabel(message),
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
        onDoubleTapHeart: {
            // R47 - double-tap bubble fires the heart quick reaction (web
            // chat-room.tsx:7265-7268 onDoubleClick parity). The SAME
            // react path the quick-reactions context menu uses; the
            // hearts burst rides the VM react path. R19-b: the react
            // body carries the stable registry id.
            viewModel.react(message, emoji: PulseReactionId.heart.rawValue, session: session)
        },
        isClusterHead: cluster.head,
        onSwipeReply: {
            // R7 - swipe past 28pt → the SAME reply path the context
            // menu "Reply" uses (web beginReply parity).
            viewModel.beginReply(to: message)
        },
    )
    }

    @ViewBuilder
    private var contextMenu: some View {
        // R19-b - the quick reaction strip is the 7 REACTION_IDS rendered as
        // SF Symbol glyphs; the button sends the STABLE ID (the react body
        // key keeps its historical name).
        ForEach(PulseReactionId.allCases, id: \.self) { reaction in
            Button {
                viewModel.react(message, emoji: reaction.rawValue, session: session)
            } label: {
                Label("\(reaction.label)", systemImage: reaction.symbolName)
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
        }.disabled(message.deletedAt != nil || (message.viewOnce == true && message.senderId != session.viewer?.id))
        // R1-W2B F-MD-06 - on-demand LLM translation (POST /translate);
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
            viewModel.saveMessage(message, session: session)
        } label: {
            Label("Save", systemImage: "bookmark")
        }
        Button {
            onForward(message)
        } label: {
            Label("Forward", systemImage: "arrowshape.turn.up.right.fill")
        }.disabled(message.deletedAt != nil || message.viewOnce == true)
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
        // ── Wave 7 - message→kanban card + per-message reminder ──
        if message.kind == "text" && message.deletedAt == nil {
            Button {
                wave7.kanbanSourceMessage = message
                wave7.kanbanOpen = true
            } label: {
                Label("Add to board", systemImage: "square.stack.3d.up.fill")
            }
        }
        // R3-A item 11 - Chanty-style direct conversion (web chat-room.tsx
        // "Convert to task" :6372-6379: text rows only, not deleted, thread
        // roots only - the message itself becomes the board card via the
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
    var onLeft: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var composerFocused: Bool

    // Wave 1 surfaces - threads, forward, info, pins, lightbox, QuickLook,
    // room search, and the media attach flow (photo picker + document).
    @State private var threadRoot: WireChatMessage?
    @State private var threadJumpMessageId: String?
    @State private var forwardSource: WireChatMessage?
    @State private var infoTarget: WireChatMessage?
    @State private var pinsOpen = false
    @State private var lightbox: MediaLightboxTarget?
    @State private var lightboxMessageId: String?
    @State private var viewOnceTask: Task<Void, Never>?
    @State private var quicklook: QuickLookTarget?
    @State private var searchOpen = false
    @State private var searchQuery = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var photoPickerOwner: UUID?
    @State private var documentPickerOwner: UUID?
    @State private var cameraPickerOwner: UUID?
    @State private var cameraRequest = UUID()
    @State private var showFileImporter = false
    // D30 camera capture - system camera + TCC state; D31 hold-to-record state.
    @State private var showCamera = false
    @State private var cameraDenied = false
    @State private var micDenied = false
    @State private var holdStarted = false
    @State private var holdCancelArmed = false
    // Wave 2 - poll builder sheet.
    @State private var pollBuilderOpen = false
    // ── Wave 7 - collaboration & hub surfaces ──
    @StateObject private var wave7 = Wave7RoomActions()
    @State private var leaderboardOpen = false
    // Wave 6 - DM safety-number sheet (F-CP-07/08) + the @-suggester (F-SM-04).
    @StateObject private var safetyBadges = PulseSafetyBadgeCache.shared
    @State private var safetyOpen = false
    // R2-D ITEM 1 - the DM info surface (web header-menu parity: TTL, screen
    // security, mute, theme + safety-number entries for DIRECT chats).
    @State private var dmInfoOpen = false

    // ── REM-B - group admin / scheduled / reactions / stickers / who-reacted ──
    @State private var groupInfoOpen = false
    @State private var departureEpoch: UUID?
    @State private var scheduleOpen = false
    @State private var scheduledManagerOpen = false
    @State private var reactionTarget: WireChatMessage?
    @State private var stickerOpen = false
    @State private var whoReactedOpen = false
    @State private var whoReactedMessage: WireChatMessage?
    @State private var whoReactedEmoji = ""

    /// R3-A item 7 - roster display names for the @mention chips in bubble
    /// bodies (web memberNames stable-list parity).
    private var memberNames: [String] { conversation.members.map(\.name) }

    /// 3-d - the group-call dial title (web `displayName || 'Group call'`
    /// parity, chat-room.tsx :4034): the conversation name, then the partner
    /// name, then any member, then the honest generic.
    private var groupCallTitle: String {
        conversation.name
            ?? conversation.members.first(where: { $0.id != session.viewer?.id })?.name
            ?? conversation.members.first?.name
            ?? "Group call"
    }

    // ── R1-W2B - location share / conv themes / quick phrases ──
    @State private var locationOpen = false
    @State private var themeOpen = false
    @State private var phrasesOpen = false
    // R5-A Item 1 - composer emoji picker (draft-only; stickers send instantly).

    // R2-B - the veil reads the app scene (the native blur/hidden signal).
    @Environment(\.scenePhase) private var scenePhase

    private var readSurfaceObscured: Bool {
        let messageSurface = threadRoot != nil || forwardSource != nil || infoTarget != nil ||
            lightbox != nil || quicklook != nil || reactionTarget != nil || whoReactedOpen
        let roomSurface = pinsOpen || searchOpen || showFileImporter || showPhotoPicker || showCamera || pollBuilderOpen ||
            safetyOpen || dmInfoOpen || groupInfoOpen || departureEpoch != nil || scheduleOpen || scheduledManagerOpen ||
            stickerOpen || locationOpen || themeOpen || phrasesOpen || leaderboardOpen
        let collaboration = wave7.redPacketCreateOpen || wave7.redPacketDetailId != nil || wave7.gameCreateOpen || wave7.tournamentCreateOpen ||
            wave7.kanbanOpen || wave7.whiteboardOpen || wave7.eventsOpen || wave7.remindersOpen
        return messageSurface || roomSurface || collaboration || viewModel.staged != nil ||
            scenePhase != .active || veilEngaged
    }

    /// F-CH-04 - broadcast composer lock: broadcastMode on + the viewer is
    /// NOT an admin (server 403s the post; the web hides the composer too).
    private var broadcastLocked: Bool {
        guard conversation.broadcastMode == true else { return false }
        let role = conversation.members.first(where: { $0.id == session.viewer?.id })?.role
        return role != "admin"
    }

    /// F-SM-04 - the @-token active right now (draft tail), if any. The R47
    /// dead-end notice suppresses it too (the composer itself is replaced).
    private var mentionSuggestion: (token: String, atIndex: Int)? {
        (broadcastLocked || viewModel.dmBlocked) ? nil : PulseMentions.activeToken(in: viewModel.draft)
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
        // The token is anchored at the draft tail - replace from the @ to end.
        let prefix = draft.prefix(suggestion.atIndex)
        PulseHaptics.tap()
        viewModel.draft = prefix + insertion
        composerFocused = true
    }

    /// Wave 8 - the room background: the per-conversation theme override
    /// (R1-W2B F-FX-05) wins over the global prefs wallpaper (web
    /// effectiveConvWallpaper parity), and an optional tint layers a soft
    /// accent glow over it (web applyConvTint parity - conv-theme.ts:175).
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

    /// Web CONV_TINT_META glow colors - emerald/rose/amber/violet/teal.
    static func convTintColor(named tint: String) -> Color {
        switch tint {
        case "rose": return Color(red: 0.96, green: 0.25, blue: 0.37)
        case "amber": return Color(red: 0.96, green: 0.62, blue: 0.04)
        case "violet": return Color(red: 0.55, green: 0.36, blue: 0.97)
        case "teal": return Color(red: 0.08, green: 0.72, blue: 0.65)
        default: return PulseTheme.emberGlowTop
        }
    }

    /// R3-A item 8 - the four effect names (web EFFECT set verbatim:
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
                // top glow color - visible even on the `none` wallpaper).
                LinearGradient(
                    colors: [tint.opacity(0.16), Color.clear],
                    startPoint: .top, endPoint: .center,
                )
            }
        }
    }

    var body: some View {
        chatBodyStyled14
            .background(PulseWindowPrivacyShield(policy: { [weak model = viewModel, weak settings = prefs,
                                                          weak liveSession = session, room = conversation.id] in
                guard let model, let settings, let liveSession,
                      model.readSessionGeneration == liveSession.sessionGeneration else { return true }
                return PulseScreenSecurityPolicy.requiresProtection(room: model.roomScreenPrivacy,
                    personal: settings.screenPrivacy[room])
            }))
            .disabled(departureEpoch != nil)
    }

    // ChatRoomView.body - R12 type-check decomposition: the original single builder
    // expression exceeded Swift’s type-check budget; VERBATIM tree, split only.
    private var chatBodyStyled_view: some View {
        VStack(spacing: 0) {
            chatBodyPart0
            chatBodyPart1
            chatBodyPart2
            chatBodyPart3
            chatBodyPart4
            chatBodyPart5
            chatBodyPart6
            chatBodyPart7
            chatBodyPart8
            chatBodyPart9
            chatBodyPart10
            chatBodyPart11
            chatBodyPart12
            chatBodyPart13
            chatBodyPart14
            chatBodyPart15
            chatBodyPart16
            chatBodyPart17
            chatBodyPart18
            chatBodyPart19
            chatBodyPart20
            chatBodyPart21
        }
        // EMB-I - the room is an ember surface: the subtree scheme pins
        // dark so every adaptive token resolves onto the warm ground, and
        // the tint goes white so toolbar chrome renders white (spec).
        .environment(\.colorScheme, .dark)
        .tint(.white)
    }

    @ViewBuilder
    private var chatBodyPart0: some View {
            // Wave 2 topics - group rooms only (General = whole room, spec §1 row 9).
    }

    @ViewBuilder
    private var chatBodyPart1: some View {
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
    }

    @ViewBuilder
    private var chatBodyPart2: some View {
            pinnedBanner
    }

    @ViewBuilder
    private var chatBodyPart3: some View {
            // REM-B F-MS-19 - header TTL chip when disappearing is on.
    }

    @ViewBuilder
    private var chatBodyPart4: some View {
            if (conversation.ttlSeconds ?? 0) > 0 {
                HStack(spacing: 6) {
                    Image(systemName: "timer")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(PulseTheme.emberGlowTop)
                    Text("Disappearing · \(GroupInfoView.ttlLabel(conversation.ttlSeconds))")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(PulseTheme.textSecondary)
                    Spacer()
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 5)
                .background(PulseTheme.emberGlowTop.opacity(0.08))
                .accessibilityLabel("Disappearing messages enabled")
            }
    }

    @ViewBuilder
    private var chatBodyPart5: some View {
            if searchOpen {
                roomSearchPanel
            }
    }

    @ViewBuilder
    private var chatBodyPart6: some View {
            // R38/R42 - the veil covers ONLY the message river (header +
    }

    @ViewBuilder
    private var chatBodyPart7: some View {
            // composer stay untouched, web parity). Stable modifier chain so
    }

    @ViewBuilder
    private var chatBodyPart8: some View {
            // the scroll identity never resets across toggles.
    }

    @ViewBuilder
    private var chatBodyPart9: some View {
            messagesList
                .blur(radius: veilEngaged ? 24 : 0)
                .overlay {
                    if veilEngaged {
                        veilCover
                    }
                }
                .allowsHitTesting(!veilEngaged)
                .accessibilityHidden(veilEngaged)
    }

    @ViewBuilder
    private var chatBodyPart10: some View {
            // R34-b - AI recap card pinned above the composer.
    }

    @ViewBuilder
    private var chatBodyPart11: some View {
            if viewModel.recap != nil {
                recapCard
                    .padding(.horizontal, 12)
                    .padding(.bottom, 4)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
    }

    @ViewBuilder
    private var chatBodyPart12: some View {
            // F-SM-04 - @-suggester popover (roster, top-5 prefix match).
    }

    @ViewBuilder
    private var chatBodyPart13: some View {
            if !mentionCandidates.isEmpty {
                mentionPopover()
            }
    }

    @ViewBuilder
    private var chatBodyPart14: some View {
            // R3-A item 1 - the live '/' palette (web SlashPalette parity:
    }

    @ViewBuilder
    private var chatBodyPart15: some View {
            // draft starts with '/', pick routes the outcome machine). The
    }

    @ViewBuilder
    private var chatBodyPart16: some View {
            // palette and the @-suggester never compete - a '/' draft carries
    }

    @ViewBuilder
    private var chatBodyPart17: some View {
            // no @-token at the tail.
    }

    @ViewBuilder
    private var chatBodyPart18: some View {
            if slashPaletteVisible {
                SlashPaletteView(draft: viewModel.draft) { command in
                    pickSlashCommand(command)
                }
            }
    }

    @ViewBuilder
    private var chatBodyPart19: some View {
            if !session.connected {
                offlineStrip
            }
    }

    @ViewBuilder
    private var chatBodyPart20: some View {
            if let error = viewModel.errorText {
                errorStrip(error)
            }
    }

    @ViewBuilder
    private var chatBodyPart21: some View {
            composer
    }

    private var chatBodyStyled0: some View {
        chatBodyStyled_view
            // EMB-I - the ember ground under everything: sunset gradient
            // plus the warm radial glow behind the header. A user-picked
            // wallpaper still layers on top of it (Wave 8 behavior kept).
            .background(alignment: .top) {
                ZStack {
                    Rectangle()
                        .fill(PulseTheme.emberBackdrop)
                    EmberRadialGlow()
                    wallpaperWash
                }
                .ignoresSafeArea()
            }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // W5-f - the voice room entry (VR-1): mic tints active while
                // any room of THIS conversation is joined; the "Voice · N
                // live" pill shows when joined + surface closed.
                if let rooms = session.voiceRooms {
                    VoiceRoomChatEntry(model: rooms, conversationId: conversation.id)
                }
            }
            // 3-d - GROUP call buttons (mesh, web chat-room.tsx :4026-4053
            // parity: same shell-dial contract, one session). Groups only;
            // the engine owns every gate (1:1-call exclusion, offline toast,
            // mic/camera permission asks, camera→voice degrade).
            if conversation.isGroup, let groupEngine = session.groupCallEngine {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        PulseHaptics.tap()
                        groupEngine.startCall(kind: .voice, conversationId: conversation.id, title: groupCallTitle)
                    } label: {
                        Image(systemName: "phone")
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Start group voice call in \(groupCallTitle)")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        PulseHaptics.tap()
                        groupEngine.startCall(kind: .video, conversationId: conversation.id, title: groupCallTitle)
                    } label: {
                        Image(systemName: "video")
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Start group video call in \(groupCallTitle)")
                }
            }
            // R9 - 1:1 dial entry (web chat-room.tsx :3988-4012 parity: the DM
            // room header carries voice + video dials). The engine owns every
            // gate (idle check, group-call exclusion, offline toast, mic/
            // camera permission asks) - the room only hands it the peer, the
            // same contract the group buttons above use for the mesh engine.
            if !conversation.isGroup, let engine = session.callEngine, let partner = dmPartner {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        PulseHaptics.tap()
                        engine.startOutgoing(
                            to: CallPeer(id: partner.id, name: partner.name, color: partner.color, avatar: partner.avatar),
                            conversationId: conversation.id
                        )
                    } label: {
                        Image(systemName: "phone")
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Start voice call with \(partner.name)")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        PulseHaptics.tap()
                        engine.startOutgoing(
                            to: CallPeer(id: partner.id, name: partner.name, color: partner.color, avatar: partner.avatar),
                            conversationId: conversation.id,
                            kind: .video
                        )
                    } label: {
                        Image(systemName: "video")
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel("Start video call with \(partner.name)")
                }
            }
            // Wave 7 F-RO-09 - room leaderboard (web group-info-sheet parity).
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
            // REM-B P1 - group info entry (member list, roles, invite,
            // rename, leave, TTL, slow mode) - groups only, web parity.
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
            // Wave 6 - DM-only safety entry (F-CP-07): ShieldCheck opens the
            // 60-digit sheet; the emerald badge shows while verified (F-CP-08).
            // R1-W2B D34 - the UNVERIFIED state also carries the tiny amber
            // dot (web R37 header parity, chat-room.tsx:3887-3892).
            if let partner = dmPartner {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        PulseHaptics.tap()
                        safetyOpen = true
                    } label: {
                        Image(systemName: safetyBadges.isVerified(partner.id, epoch: session.sessionGeneration) ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                            .foregroundStyle(safetyBadges.isVerified(partner.id, epoch: session.sessionGeneration) ? PulseTheme.emberGlowTop : PulseTheme.textSecondary)
                            .overlay(alignment: .topTrailing) {
                                if !safetyBadges.isVerified(partner.id, epoch: session.sessionGeneration) {
                                    Circle()
                                        .fill(PulseTheme.amber)
                                        .frame(width: 6, height: 6)
                                        .offset(x: 3, y: -3)
                                }
                            }
                    }
                    .buttonStyle(PulseButtonStyle())
                    .accessibilityLabel(safetyBadges.isVerified(partner.id, epoch: session.sessionGeneration) ? "Verified - open safety number" : "Not verified - open safety number")
                }
                // R2-D ITEM 1 - DM info entry (web header-menu parity: the
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
        // R1-W2B F-FX-05 - per-conversation theme picker entry (web lives in
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
            // R34-b - AI recap entry (web header-menu "Recap with AI" parity;
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
            // R30-b - room-header reminders entry (web chat-room.tsx:4054-4075
            // parity): opens the existing Wave7 sheet (room-level - no message
            // anchor); the emerald badge counts the viewer's unfired reminders.
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    PulseHaptics.tap()
                    wave7.reminderAnchor = nil
                    wave7.remindersOpen = true
                } label: {
                    Image(systemName: "bell")
                        .overlay(alignment: .topTrailing) {
                            if viewModel.upcomingReminderCount > 0 {
                                Text(PulseRoomParityLogic.reminderBadgeText(viewModel.upcomingReminderCount))
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 4)
                                    .frame(minWidth: 16, minHeight: 16)
                                    .background(Capsule().fill(PulseTheme.emberGlowBottom))
                                    .offset(x: 8, y: -4)
                            }
                        }
                }
                .buttonStyle(PulseButtonStyle())
                .accessibilityLabel(viewModel.upcomingReminderCount > 0
                    ? "Reminders - \(viewModel.upcomingReminderCount) upcoming"
                    : "Reminders")
            }
            // R1-W2I F-PI-03 - the pop-out mini-chat toggle (web chat-room
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
    }

    private var chatBodyStyled1: some View {
        chatBodyStyled0
        .onChange(of: readSurfaceObscured, initial: true) { _, hidden in viewModel.readSurfaceObscured = hidden }
        .sheet(isPresented: $safetyOpen) {
            if let partner = dmPartner {
                SafetySheetView(session: session, peer: partner)
            }
        }
        // R2-D ITEM 1 - the DM info sheet (groups keep GroupInfoView below;
        // the onDetailUpdated handoff matches that sheet's veil plumbing).
        .sheet(isPresented: $dmInfoOpen) {
            if let partner = dmPartner {
                RoomInfoSheet(
                    conversation: conversation,
                    partner: partner,
                    session: session,
                    prefs: prefs,
                    onDetailUpdated: { detail in
                        // R38/R47 - one mutator adopts both flags from the
                        // sheet's fresh detail (dmBlocked is private(set)).
                        viewModel.applyFreshDetail(detail)
                    },
                )
            }
        }
        .task(id: "\(session.sessionGeneration):\(dmPartner?.id ?? "")") {
            // Wave 6 - populate the verified-badge cache for this DM (one
            // quiet GET on room open; the header badge reads the cache).
            guard let partner = dmPartner else { return }
            let api = session.api, epoch = session.sessionGeneration
            if let state = try? await api.safetyState(peerId: partner.id) {
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                safetyBadges.mark(partner.id, verified: state.verified, epoch: epoch)
            }
        }
    }

    private var chatBodyStyled2: some View {
        chatBodyStyled1
        .task {
            // R1-W2B F-MS-29 - the quick-phrase rail seeds on room open.
            viewModel.loadQuickPhrases(session: session)
        }
        .task {
            // R2-B R38/R42 - server truth for both veil flags on room open.
            await viewModel.loadPrivacyState(session: session)
        }
        .onChange(of: viewModel.jumpThreadRoot) { _, root in
            if let root {
                threadJumpMessageId = viewModel.jumpThreadReplyId
                threadRoot = root
                viewModel.consumeThreadJump()
            }
        }
        .sheet(item: $threadRoot, onDismiss: { threadJumpMessageId = nil }) { root in
            ThreadView(conversation: conversation, root: root, session: session, initialJumpMessageId: threadJumpMessageId)
        }
    }

    private var chatBodyStyled3: some View {
        chatBodyStyled2
        .sheet(item: $forwardSource) { source in
            ForwardSheet(source: source, session: session)
        }
        .sheet(item: $infoTarget) { message in
            MessageInfoSheet(message: message, conversation: conversation, viewerId: session.viewer?.id)
        }
        .sheet(isPresented: $pinsOpen) {
            pinsList
        }
    }

    private var chatBodyStyled4: some View {
        chatBodyStyled3
        .sheet(isPresented: $pollBuilderOpen) {
            PollBuilderSheet(viewModel: viewModel, session: session)
        }
        // ── REM-B sheet hosts ──
        .sheet(isPresented: $groupInfoOpen, onDismiss: {
            guard let epoch = departureEpoch else { return }
            guard session.sessionGeneration == epoch else {
                departureEpoch = nil
                return
            }
            // Wait for the sheet to close before retiring its exact route.
            // Retain the read fence through the navigation transition.
            onLeft()
        }) {
            GroupInfoView(
                conversation: conversation,
                session: session,
                prefs: prefs,
                onDetailUpdated: { detail in
                    // R38/R47 - one mutator adopts both flags from the
                    // info-sheet's fresh detail (dmBlocked is private(set)).
                    viewModel.applyFreshDetail(detail)
                },
                onLeft: {
                    departureEpoch = session.sessionGeneration
                    viewModel.updateReadViewport(visibleIDs: [], tailVisible: false)
                    groupInfoOpen = false
                },
            )
            .id("\(session.sessionGeneration.uuidString)|\(conversation.id)")
        }
        .sheet(isPresented: $scheduleOpen) {
            ScheduleSheet { date in
                await viewModel.scheduleDraft(for: date, session: session)
            }
            .id("\(session.sessionGeneration.uuidString)|\(conversation.id)")
        }
    }

    private var chatBodyStyled5: some View {
        chatBodyStyled4
        .sheet(isPresented: $scheduledManagerOpen) {
            ScheduledManagerSheet(conversationId: conversation.id, session: session)
                .onDisappear { viewModel.loadScheduled(session: session) }
        }
        .sheet(item: $reactionTarget) { target in
            // R19-b - the picker emits the STABLE id; the react body key keeps
            // its historical name.
            ReactionPickerSheet { reaction in
                viewModel.react(target, emoji: reaction.rawValue, session: session)
            }
        }
        .sheet(isPresented: $stickerOpen) {
            StickerPickerSheet { emoji, pack in
                viewModel.sendSticker(emoji: emoji, pack: pack, session: session)
            }
        }
        // R5-A Item 1 - emoji picker (web chat-room.tsx:5385-5412 parity):
        // tap APPENDS to the draft (web setInput(prev => prev + emoji)) and
        // the focus hand-back on dismiss keeps the keyboard up (web
        // requestAnimationFrame(textareaRef.focus) parity).
    }

    private var chatBodyStyled6: some View {
        chatBodyStyled5
        .sheet(isPresented: $whoReactedOpen) {
            if let target = whoReactedMessage {
                let selectedEmoji = whoReactedEmoji
                WhoReactedSheet(
                    message: target,
                    emoji: selectedEmoji,
                    members: conversation.members,
                    viewerId: session.viewer?.id,
                    session: session,
                    onReact: { present in
                        try await viewModel.setReaction(target, emoji: PulseReactionId.normalize(selectedEmoji).rawValue, present: present, session: session)
                    }
                )
            }
        }
        // ── R1-W2B sheet hosts ──
        .sheet(isPresented: $locationOpen) {
            LocationShareSheet { lat, lng, label in
                viewModel.sendLocation(lat: lat, lng: lng, label: label, session: session)
            }
        }
    }

    private var chatBodyStyled7: some View {
        chatBodyStyled6
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
    }

    private var chatBodyStyled8: some View {
        chatBodyStyled7
        .sheet(isPresented: $wave7.redPacketCreateOpen) {
            Wave7RedPacketCreateSheet { total, count, note in
                wave7.createRedPacket(api: session.api, conversationId: conversation.id, total: total, count: count, note: note)
            }
        }
        .sheet(isPresented: $wave7.gameCreateOpen) {
            let roomAPI = session.api
            Wave7GameCreateSheet(
                members: conversation.members
                    .filter { $0.id != session.viewer?.id }
                    .map { (id: $0.id, name: $0.name) },
                onCreate: { opponentId in
                    await wave7.createGame(api: roomAPI, conversationId: conversation.id, opponentId: opponentId)
                },
            )
            .id(session.sessionGeneration)
        }
        .sheet(isPresented: $wave7.tournamentCreateOpen) {
            let roomAPI = session.api
            Wave7TournamentCreateSheet { name in
                await wave7.createTournament(api: roomAPI, conversationId: conversation.id, name: name)
            }
            .id(session.sessionGeneration)
        }
    }

    private var chatBodyStyled9: some View {
        chatBodyStyled8
        .sheet(isPresented: $wave7.kanbanOpen) {
            let roomAPI = session.api
            Wave7KanbanSheet(
                conversationId: conversation.id,
                viewerId: session.viewer?.id ?? "",
                isAdmin: false,
                prefillTitle: nil,
                loadBoard: { try? await roomAPI.kanbanBoard(conversationId: conversation.id) },
                onAddCard: { title, column, assignee in
                    await wave7.addCard(api: roomAPI, conversationId: conversation.id, title: title, column: column, assigneeId: assignee)
                },
                onMoveCard: { cardId, column in
                    await wave7.moveCard(api: roomAPI, cardId: cardId, column: column)
                },
                onDeleteCard: { cardId in
                    await wave7.deleteCard(api: roomAPI, cardId: cardId)
                },
            )
            .id("\(session.sessionGeneration):\(conversation.id)")
        }
        .sheet(isPresented: $wave7.whiteboardOpen) {
            Wave7WhiteboardSheet(conversationId: conversation.id,
                viewerId: session.viewer?.id ?? "", api: session.api)
        }
        .sheet(isPresented: $wave7.eventsOpen) {
            let roomAPI = session.api
            Wave7EventsSheet(
                conversationId: conversation.id,
                viewerId: session.viewer?.id ?? "",
                isAdmin: false,
                loadEvents: { try? await roomAPI.events(conversationId: conversation.id).events },
                onCreate: { title, iso, desc, loc in
                    await wave7.createEvent(api: roomAPI, conversationId: conversation.id, title: title, startsAtIso: iso, description: desc, location: loc)
                },
                onRsvp: { eventId, status in await wave7.rsvp(api: roomAPI, eventId: eventId, status: status) },
                onCheckin: { eventId in await wave7.checkin(api: roomAPI, eventId: eventId) },
                onDelete: { eventId in await wave7.deleteEvent(api: roomAPI, eventId: eventId) },
            )
            .id("\(session.sessionGeneration):\(conversation.id)")
        }
    }

    private var chatBodyStyled10: some View {
        chatBodyStyled9
        .sheet(isPresented: $wave7.remindersOpen) {
            let reminderAPI = session.api
            Wave7RemindersSheet(
                loadReminders: { try? await reminderAPI.reminders(dueOnly: false).items },
                onCreate: { note, iso, anchored in
                    await wave7.createReminder(api: reminderAPI, conversationId: conversation.id, note: note, remindAtIso: iso, anchored: anchored)
                },
                onResolve: { id in wave7.resolveReminder(api: reminderAPI, id: id) },
                onDelete: { id in wave7.deleteReminder(api: reminderAPI, id: id) },
                onJump: { item in
                    // R7 - web onReminderJump parity (chat-room.tsx:1093-1103):
                    // close the sheet first; a reminder anchored in THIS room
                    // jumps + flashes, another room navigates there with the
                    // jump target riding the established requestOpenRoom bridge
                    // (saved-library "open original" parity).
                    wave7.remindersOpen = false
                    guard let targetId = item.conversationId?.trimmingCharacters(in: .whitespacesAndNewlines),
                          !targetId.isEmpty else {
                        session.toasts.show("This reminder has no linked chat.")
                        return
                    }
                    let messageId = item.messageId?.trimmingCharacters(in: .whitespacesAndNewlines)
                    if targetId == conversation.id {
                        if let messageId, !messageId.isEmpty { viewModel.jumpTo(messageId) }
                    } else {
                        // No feature-owned fetch/task: the root resolver captures
                        // account/navigation generations and cancels superseded opens.
                        session.requestLinkedRoom(targetId, jumpMessageId: messageId)
                    }
                },
                anchored: wave7.reminderAnchor,
            )
        }
        .onChange(of: wave7.remindersOpen) { _, open in
            // R30-b - the header badge re-syncs after create/resolve/delete
            // inside the sheet (its list fetch is the sheet's own).
            if !open { viewModel.loadUpcomingReminderCount(session: session) }
        }
        .sheet(isPresented: $leaderboardOpen) {
            Wave7LeaderboardSheet(
                loadRoom: { try? await session.api.leaderboard(conversationId: conversation.id) },
                loadGlobal: { try? await session.api.leaderboard(conversationId: nil) },
            )
        }
    }

    private var chatBodyStyled11: some View {
        chatBodyStyled10
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
            let cardAPI = session.api
            wave7.onCarrierMessage = { carrier in
                viewModel.injectCarrier(carrier)
            }
            wave7.cardRedPacketLoad = { id in try? await cardAPI.redPacketDetail(id) }
            wave7.cardGameLoad = { id in try await cardAPI.gameDetail(id) }
            wave7.cardTournamentLoad = { id in try await cardAPI.tournamentDetail(id) }
            wave7.cardGrab = { id in wave7.grabRedPacket(api: cardAPI, packetId: id) }
            wave7.cardMove = { matchId, cell in await wave7.moveGame(api: cardAPI, matchId: matchId, cell: cell) }
            wave7.cardJoinGame = { matchId in await wave7.joinGame(api: cardAPI, matchId: matchId) }
            // R7 - rematch: card hands the opponent id, the room supplies
            // conversation.id + api (in-flight spinner + toast ride wave7).
            wave7.cardRematch = { opponentId in
                await wave7.rematchGame(api: cardAPI, conversationId: conversation.id, opponentId: opponentId)
            }
            wave7.cardJoinTournament = { tid in await wave7.joinTournament(api: cardAPI, tournamentId: tid) }
            wave7.cardFinishTournament = { tid in await wave7.finishTournament(api: cardAPI, tournamentId: tid) }
            wave7.cardOpenDetail = { id in wave7.redPacketDetailId = id }
        }
    }

    private var chatBodyStyled12: some View {
        chatBodyStyled11
        .fullScreenCover(item: $lightbox) { target in
            MediaLightboxView(url: target.url, caption: target.caption, expiresAt: target.expiresAt, singleUse: target.singleUse)
        }
        .onReceive(session.messageVisibilityClock) { now in
            guard lightbox != nil, let id = lightboxMessageId else { return }
            let current = try? session.store?.message(id: id, now: now)
            if current == nil || current?.deletedAt != nil { lightbox = nil; lightboxMessageId = nil }
        }
        .fullScreenCover(item: $quicklook) { target in
            QuickLookView(url: target.url)
                .ignoresSafeArea()
        }
        .onDisappear {
            // Wave 2 - honest teardown: a recording or playback must never
            // outlive the room (roomVisible off / conversation change).
            viewOnceTask?.cancel()
            viewModel.handleRoomDisappeared()
        }
    }

    private var chatBodyStyled13: some View {
        chatBodyStyled12
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
        // D30 camera capture - the shot flows through stageImage → the SAME
        // ≤1280px JPEG staged pipeline as the photo picker (web parity).
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker(
                onCapture: { image in
                    guard cameraPickerOwner == session.sessionGeneration,
                          viewModel.acceptsAttachmentSession(session, conversationId: conversation.id) else { return }
                    cameraPickerOwner = nil
                    viewModel.stageCameraImage(image)
                },
                onCancel: { cameraPickerOwner = nil },
            )
            .ignoresSafeArea()
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: PulseMediaSupport.documentTypes,
            allowsMultipleSelection: false,
        ) { result in
            let owner = documentPickerOwner
            documentPickerOwner = nil
            guard owner == session.sessionGeneration,
                  viewModel.acceptsAttachmentSession(session, conversationId: conversation.id) else { return }
            if case .success(let urls) = result, let url = urls.first { viewModel.stageDocument(url: url) }
        }
    }

    private var chatBodyStyled14: some View {
        chatBodyStyled13
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            let owner = photoPickerOwner
            photoPickerOwner = nil; photoItem = nil
            guard owner == session.sessionGeneration,
                  viewModel.acceptsAttachmentSession(session, conversationId: conversation.id) else { return }
            viewModel.importPhoto(item, session: session)
        }
    }


    @State private var showPhotoPicker = false

    /// Wave 6 - the DM partner (safety entry + verified badge are DM-only,
    /// web parity: group rooms carry no safety number).
    private var dmPartner: WireConversationMember? {
        guard !conversation.isGroup else { return nil }
        return conversation.members.first(where: { $0.id != session.viewer?.id })
            ?? conversation.members.first
    }

    // ── F-SM-04 - the @-suggester popover (roster, top-5 prefix match) ──
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
                .background(index == 0 ? PulseTheme.emberGlowBottom.opacity(0.10) : Color.clear)
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
                            Text(hit.integrationLabel ?? hit.sender?.name ?? "Message")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(PulseTheme.emberGlowTop)
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
                        Text(pin.integrationLabel ?? pin.sender?.name ?? "Pinned")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(PulseTheme.emberGlowTop)
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
            Text("Offline - messages will queue")
        }
        .font(.caption2.weight(.medium))
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 5)
        .background(PulseTheme.amber.opacity(0.12))
    }

    // ── messages ─────────────────────────────────────────────
    private var messagesList: some View {
        // R7 - cluster verdicts computed ONCE per river build (web buildItems
        // parity, chat-room.tsx:1367-1424): sender name renders on head rows
        // only and clustered rows tighten. Pure kernel → no memo cache needed;
        // the map pass is O(n) like the row build itself.
        let clusterFlags = PulseCluster.clusterFlags(
            createdAtMs: viewModel.messages.map { (PulseFormat.date($0.createdAt)?.timeIntervalSince1970 ?? 0) * 1000 },
            senderIds: viewModel.messages.map(\.senderId),
            anon: viewModel.messages.map { $0.anon ?? false },
            anonAliases: viewModel.messages.map(\.anonAlias),
        )
        return ScrollViewReader { proxy in
            GeometryReader { viewport in
                ScrollView {
                    // Wave 8 - prefs density: cozy keeps the Wave-0 rhythm,
                    // compact tightens the river (web chat-room density parity).
                    LazyVStack(spacing: prefs.density.rowSpacing) {
                        if viewModel.hasMore {
                            Button(viewModel.loadingOlder ? "Loading earlier messages…" : "Load earlier messages") {
                                viewModel.loadOlder(session: session)
                            }.disabled(viewModel.loadingOlder)
                        }
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
                                // R7 - this row's web buildItems cluster verdict
                                // + the density-scaled gap tighten for non-head
                                // rows (web mt-0.5/mt-px parity).
                                cluster: clusterFlags[index],
                                clusterTighten: prefs.density == .cozy ? 4 : 1,
                                onOpenImage: { openLightbox($0) },
                                onOpenFile: { openFile($0) },
                                onOpenThread: { threadJumpMessageId = nil; threadRoot = $0 },
                                onForward: { forwardSource = $0 },
                                onInfo: { infoTarget = $0 },
                                onViewOnce: { message in
                                    guard viewOnceTask == nil, lightbox == nil else { return }
                                    viewOnceTask = Task {
                                        defer { viewOnceTask = nil }
                                        if let granted = await session.consumeViewOnce(message), !Task.isCancelled {
                                            viewModel.upsert(granted)
                                            openLightbox(granted, singleUse: true)
                                        }
                                    }
                                },
                                wave7: wave7,
                                memberNames: memberNames,
                                onWhoReacted: { message, emoji in
                                    // R3-A item 4 - chip tap → the who-reacted roster.
                                    whoReactedMessage = message
                                    whoReactedEmoji = emoji
                                    whoReactedOpen = true
                                },
                            )
                            .background(GeometryReader { geometry in
                                Color.clear.preference(key: PulseRoomViewportKey.self,
                                    value: PulseRoomViewport(messages: [message.id: geometry.frame(in: .named("room-viewport"))]))
                            })
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
                                    .foregroundStyle(PulseTheme.emberGlowTop)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                        }
                        Color.clear.frame(height: 4).id("tail")
                            .background(GeometryReader { geometry in
                                Color.clear.preference(key: PulseRoomViewportKey.self,
                                    value: PulseRoomViewport(tail: geometry.frame(in: .named("room-viewport"))))
                            })
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                }
                .coordinateSpace(name: "room-viewport")
                .onPreferenceChange(PulseRoomViewportKey.self) { layout in
                    viewModel.updateReadViewport(
                        visibleIDs: PulseReadViewport.visibleIDs(layout.messages, height: viewport.size.height),
                        tailVisible: PulseReadViewport.tailVisible(layout.tail, height: viewport.size.height))
                }
                .onDisappear { viewModel.updateReadViewport(visibleIDs: [], tailVisible: false) }
                .defaultScrollAnchor(.bottom)
                .onChange(of: viewModel.messages.last?.id) { _, _ in
                    viewModel.noteRiverChanged()
                }
                .onChange(of: viewModel.messages.count) { _, _ in
                    // R26 - the missed-badge machine + tail-id pill arming run
                    // on every river change; the auto-scroll only fires while
                    // the viewer sits at the bottom (web nearBottomRef parity -
                    // reading history is never yanked to the tail).
                    viewModel.noteRiverChanged()
                    if viewModel.isTailVisible {
                        withAnimation(.pulse(.pulseSoft, reduceMotion: reduceMotion)) {
                            proxy.scrollTo("tail", anchor: .bottom)
                        }
                    }
                }
                .onChange(of: viewModel.jumpRequestRevision) { _, _ in
                    guard let target = viewModel.jumpTargetId else { return }
                    proxy.scrollTo(target, anchor: .center)
                }
                .onAppear {
                    proxy.scrollTo("tail", anchor: .bottom)
                }
                // R26 - Telegram-style jump-to-latest pill (web chat-room
                // :4527-4568 parity): armed on off-screen arrivals, badge shows
                // the missed count, tap lands on the tail.
                .overlay(alignment: .bottom) {
                    jumpPill(proxy: proxy)
                }
            }
        }
    }

    // ── R2-B - jump pill · unread divider index · recap card · veil ──

    /// The tap-time anchor (frozen in the view model) drives the divider
    /// row index - nil when there is nothing unread or nothing qualifies.
    private var unreadDividerIndex: Int? {
        viewModel.unreadDividerRow(viewerId: session.viewer?.id)
    }

    @ViewBuilder
    private func jumpPill(proxy: ScrollViewProxy) -> some View {
        if viewModel.showJumpPill {
            Button {
                PulseHaptics.tap()
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
                            .foregroundStyle(PulseTheme.emberGlowTop)
                    }
                }
                .padding(.leading, 12)
                .padding(.trailing, 14)
                .padding(.vertical, 8)
                .foregroundStyle(.white)
                .background(Capsule().fill(PulseTheme.emberGlowTop))
                .shadow(color: PulseTheme.emberGlowTop.opacity(0.35), radius: 8, y: 3)
            }
            .buttonStyle(PulseButtonStyle())
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.horizontal, 12)
            .padding(.bottom, 6)
            .transition(.opacity.combined(with: .scale(scale: 0.9)))
            .accessibilityLabel(viewModel.missedCount > 0
                ? "Jump to newest messages - \(viewModel.missedCount) new"
                : "Jump to newest messages")
        } else {
            Color.clear.frame(height: 0)
            .accessibilityHidden(true)
        }
    }

    /// R34-b - the recap card (web :4835-4905 parity): sparkles chip, the
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
                        .tint(PulseTheme.emberGlowTop)
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
                    Text(viewModel.recapText ?? "-")
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

    /// R38/R42 - the veil engages when EITHER flag is on; on iOS the
    /// "unfocused" state is scenePhase != .active (app switcher, another
    /// app, lock - web blur/hidden parity).
    private var veilEngaged: Bool {
        PulseScreenSecurityPolicy.shouldCover(room: viewModel.roomScreenPrivacy,
            personal: session.prefs?.screenPrivacy[conversation.id], active: scenePhase == .active)
    }

    private var veilCover: some View {
        ZStack {
            Rectangle().fill(PulseTheme.emberChrome)
            VStack(spacing: 10) {
                Image(systemName: "eye.slash")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(PulseTheme.emberGlowTop)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(PulseTheme.emberGlowTop.opacity(0.12)))
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
        .accessibilityLabel("Screen security is on - messages are hidden while Pulse is not focused")
    }

    private func openLightbox(_ message: WireChatMessage, singleUse: Bool = false) {
        guard message.deletedAt == nil, PulseRemediationLogic.isAlive(message.expiresAt),
              singleUse || message.viewOnce != true || message.senderId == session.viewer?.id else { return }
        guard let url = PulseMediaOpener.url(for: message) else {
            session.toasts.show("Media unavailable")
            return
        }
        lightboxMessageId = message.id
        lightbox = MediaLightboxTarget(url: url, caption: message.content, expiresAt: message.expiresAt, singleUse: singleUse)
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
    // in the same HStack position - idle mic, recording send-arrow and plain
    // send are the SAME view with a swapped glyph - so a press gesture that
    // starts on the mic survives the recording bar appearing, and release
    // still lands where the finger went down.
    private var composer: some View {
        VStack(spacing: 0) {
            // R5-A Item 6 - the scheduled chip above the composer (web
            // chat-room.tsx:4726-4744): "{next} · {count} pending - tap to
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
                    "Microphone access is off - voice notes need it. Hold-to-record unlocks once it's on.",
                ) { micDenied = false }
            }
            if cameraDenied {
                deniedNotice("Camera access is off - allow it to take photos for this chat.") { cameraDenied = false }
            }
            // R2-D - the F-MS-20 slow-mode countdown: the chip appears the
            // moment the server's 429 arms the lock, counts the honest wait
            // down live (web chat-room.tsx slow-mode-chip parity) and
            // collapses when the ticker clears the lock.
            if viewModel.isSlowModeLocked {
                slowModeChip
            }
            // R3-A item 5 - the incognito hint (web anon-pill parity,
            // chat-room.tsx:4799-4833): groups only, X disarms the mask.
            if conversation.isGroup && viewModel.anonOn {
                incognitoPill
            }
            // R7 - topic filing pill (web chat-room.tsx:4663-4690): persistent
            // emerald pill above the composer while a topic view is active and
            // the composer is not editing - X stops filing (back to General).
            if conversation.isGroup,
               viewModel.activeTopicId != nil,
               viewModel.editingTarget == nil,
               let filingName = viewModel.topics.first(where: { $0.id == viewModel.activeTopicId })?.name {
                topicFilingPill(name: filingName)
            }
            if broadcastLocked {
                // F-CH-04 - the broadcast lock replaces the composer row for
                // non-admins (verbatim web copy; input is gone, not disabled).
                HStack(spacing: 8) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(PulseTheme.emberGlowTop)
                    Text("Only admins can post")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(PulseTheme.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(.ultraThinMaterial)
                .accessibilityElement(children: .combine)
            } else if viewModel.dmBlocked {
                dmBlockedNotice
            } else {
                composerRows
            }
        }
    }

    /// R47 - blocked-pair DM dead-end notice (web chat-room.tsx:5035-5044
    /// copy verbatim: rose Ban glyph on the deep-glass strip). The composer
    /// is REPLACED, not disabled; the server's messages-POST 403 stays the
    /// enforcement - this is UX only.
    private var dmBlockedNotice: some View {
        HStack(spacing: 8) {
            Image(systemName: "minus.circle.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(PulseTheme.rose)
            Text("You can no longer message this account")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(.ultraThinMaterial)
        .accessibilityElement(children: .combine)
    }

    /// R2-D - slow-mode countdown chip (web chat-room.tsx:4758-4770 copy
    /// verbatim, Gauge glyph + mm:ss countdown); role=status/live so the
    /// remaining time is announced as it ticks.
    private var slowModeChip: some View {
        HStack(spacing: 6) {
            Image(systemName: "gauge")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.emberGlowTop)
            Text("Slow mode - you can send again in \(PulseFormat.countdown(viewModel.slowModeRemainingSeconds))")
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
        .accessibilityLabel("Slow mode - you can send again in \(viewModel.slowModeRemainingSeconds) seconds")
    }

    /// R3-A item 5 - the incognito hint pill (web anon-pill :4809-4830 copy
    /// verbatim): emerald wash + mask glyph + "hides your name" line + an X
    /// that disarms. Only mounted while the mask is armed in a GROUP.
    private var incognitoPill: some View {
        HStack(spacing: 6) {
            Image(systemName: "theatermasks.fill")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(PulseTheme.emberGlowTop)
            Text("Incognito on - next message hides your name")
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
                .fill(PulseTheme.emberGlowTop.opacity(0.12))
                .overlay(Capsule().strokeBorder(PulseTheme.emberGlowTop.opacity(0.35), lineWidth: 1)),
        )
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
        .frame(maxWidth: .infinity)
    }

    /// R7 - topic filing pill (web chat-room.tsx:4663-4690 copy verbatim:
    /// "Filing to #<name>", MessagesSquare glyph, X → back to General with
    /// haptic). Same glass style as the sibling incognito pill.
    private func topicFilingPill(name: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(PulseTheme.emberGlowTop)
            Text("Filing to #\(name)")
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(PulseTheme.textSecondary)
                .lineLimit(1)
            Spacer(minLength: 0)
            Button {
                // setActiveTopic already haptics (web pill X parity) - no
                // second tap here.
                viewModel.setActiveTopic(nil, session: session)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(PulseTheme.textTertiary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Stop filing to this topic - back to General")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(
            Capsule()
                .fill(PulseTheme.emberGlowTop.opacity(0.12))
                .overlay(Capsule().strokeBorder(PulseTheme.emberGlowTop.opacity(0.35), lineWidth: 1)),
        )
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
        .frame(maxWidth: .infinity)
    }

    // ── R3-A item 1 - the '/' palette wiring ─────────────────

    /// Web SlashPalette open-gate parity (chat-room.tsx:5200): the draft
    /// starts with '/' while the composer is in normal send mode. The
    /// @-suggester owns the popover slot when its candidates are visible.
    private var slashPaletteVisible: Bool {
        !broadcastLocked
            && !viewModel.dmBlocked
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
    /// parity) - no closure predicates, plain token walk.
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

    /// Type-check relief: the top bars split out of the composer builder.
    @ViewBuilder
    private var composerTopBars: some View {
        // R1-W2B F-MS-29 - the quick-phrase rail sits right above the
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
    }

    @ViewBuilder
    private var composerRows: some View {
            composerTopBars
            HStack(alignment: .bottom, spacing: 10) {
                if viewModel.isRecording {
                    // Recording bar replaces the composer TEXT (spec §1 row 11)
                    // - the trailing slot stays mounted for release-to-send.
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
                    // EMB-I composer row: dark plus-circle (attach), the
                    // 52pt ember capsule (smile, text field, camera) and the
                    // always-mounted voice/send slot on the right.
                    attachMenu

                    emberComposerCapsule
                }

                voiceSendSlot
                    // R2-D - mic/send refuses touches while the slow-mode
                    // window runs (web disabled={slowRemaining > 0} parity).
                    .opacity(viewModel.isSlowModeLocked ? 0.45 : 1)
                    .allowsHitTesting(!viewModel.isSlowModeLocked)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial)
    }

    /// Type-check relief: the 52pt ember input capsule split out of the
    /// composer builder (smile opens the stamp picker, text field, camera).
    private var emberComposerCapsule: some View {
        HStack(spacing: 8) {
            // R5-A Item 1 - Smile button hosts the stamp picker
            // (R19-a retired the draft-append emoji popover; the
            // stamps are registry ids with designed glyphs).
            Button {
                PulseHaptics.tap()
                stickerOpen = true
            } label: {
                Image(systemName: "face.smiling")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.80))
                    .frame(width: 30, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Insert emoji")

            TextField(
                viewModel.editingTarget != nil ? "Edit message" : "Type here",
                text: $viewModel.draft,
                // String title resolves to the (title:text:prompt:axis:)
                // overload - prompt precedes axis.
                prompt: Text(viewModel.editingTarget != nil ? "Edit message" : "Type here")
                    .foregroundColor(Color.white.opacity(0.40)),
                axis: .vertical,
            )
            .lineLimit(1...5)
            .foregroundStyle(.white)
            .padding(.vertical, 9)
            .focused($composerFocused)
            .onChange(of: viewModel.draft) { _, _ in viewModel.draftChanged(session: session) }
            .disabled(viewModel.staged != nil)
            // R2-D - the composer is VISIBLY locked while the
            // slow-mode window runs (web send/mic disabled parity).
            .disabled(viewModel.isSlowModeLocked)
            .opacity(viewModel.isSlowModeLocked ? 0.55 : 1)
            .overlay(alignment: .leading) {
                if viewModel.isSlowModeLocked {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.40))
                        .padding(.leading, 16)
                        .transition(.opacity)
                }
            }

            // EMB-I - camera shortcut inside the capsule; it
            // rides the EXISTING D30 camera capture flow.
            Button {
                requestCamera()
            } label: {
                Image(systemName: "camera")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.80))
                    .frame(width: 30, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Camera")
        }
        .padding(.leading, 10)
        .padding(.trailing, 8)
        .frame(minHeight: 52)
        .background(Capsule().fill(PulseTheme.emberChrome.opacity(0.92)))
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.08), lineWidth: 1))
    }

    /// D31 - the ALWAYS-MOUNTED trailing composer slot. EMB-I visuals: the
    /// send state is the 48pt ember-gradient circle with a white paperplane
    /// (or arrow while recording); the voice-idle state is a dark circle
    /// with a white mic. State machine + gesture are untouched.
    private var voiceSendSlot: some View {
        ZStack {
            Image(systemName: viewModel.isRecording
                ? "arrow.up"
                : (viewModel.canStartVoiceRecording ? "mic.fill" : "paperplane.fill"))
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 48, height: 48)
                .background(
                    Circle().fill(
                        viewModel.canStartVoiceRecording && !viewModel.isRecording
                            ? AnyShapeStyle(Color.white.opacity(0.08))
                            : AnyShapeStyle(PulseTheme.emberSignalGradient),
                    ),
                )
                .overlay(Circle().strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
                .gesture(holdGesture)
            if viewModel.sendingVoice && !viewModel.isRecording {
                ProgressView().tint(.white)
            }
        }
        .accessibilityLabel(
            viewModel.isRecording
                ? "Release to send voice note - slide left to cancel"
                : (viewModel.canStartVoiceRecording ? "Record voice note" : "Send"),
        )
    }

    /// D31 press-and-hold recorder gesture - minimumDistance 0 so a plain
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
                    // Text-mode tap (drag with zero distance) - plain send.
                    sendSlotTap()
                    return
                }
                holdStarted = false
                let cancelled = holdCancelArmed
                holdCancelArmed = false
                viewModel.endVoiceHold(cancelled: cancelled, session: session)
            }
    }

    /// Slot tap - sends while recording (release already processed, e.g. the
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
            // R3-A item 1 - the send tap is a LIVE slash trigger (web Enter
            // parity: submit runs parseComposerInput first). A leading '/'
            // command is consumed by the outcome machine; plain text falls
            // through to the ordinary send.
            if viewModel.interpretSlashDraft(session: session) { return }
            viewModel.send(session: session)
        }
    }

    /// D30/D31 - the honest inline permission explainer (no crash, no dead
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

    private func requestCamera() {
        guard viewModel.acceptsAttachmentSession(session, conversationId: conversation.id) else { return }
        guard CameraPicker.isAvailable else { session.toasts.show("This device has no available camera."); return }
        let owner = session.sessionGeneration, ticket = UUID()
        cameraPickerOwner = owner; cameraRequest = ticket
        CameraPicker.requestAccess { granted in
            DispatchQueue.main.async {
                guard cameraRequest == ticket, cameraPickerOwner == owner, session.sessionGeneration == owner,
                      viewModel.acceptsAttachmentSession(session, conversationId: conversation.id) else { return }
                if granted { showCamera = true } else { cameraPickerOwner = nil; cameraDenied = true }
            }
        }
    }

    @State private var attachmentMenuReadID = UUID()

    private var attachMenu: some View {
        PulseAttentionMenu(symbol: viewModel.staged == nil ? "plus" : "minus",
            enabled: viewModel.editingTarget == nil, makeMenu: { attachmentActions },
            visibilityChanged: { session.setTransientReadObscured(attachmentMenuReadID, $0) })
            .frame(width: 48, height: 48)
    }

    private var attachmentActions: UIMenu {
        let epoch = session.sessionGeneration
        func item(_ title: String, _ symbol: String, _ action: @escaping () -> Void) -> UIAction {
            UIAction(title: title, image: UIImage(systemName: symbol)) { _ in
                guard session.sessionGeneration == epoch, session.activeRoomId == conversation.id,
                      viewModel.acceptsAttachmentSession(session, conversationId: conversation.id) else { return }
                action()
            }
        }
        var express: [UIMenuElement] = [
            item("Stickers", "face.smiling") { stickerOpen = true },
            UIMenu(title: "Effects", image: UIImage(systemName: "sparkles"), children: Self.expressEffects.map { effect in
                item(effect.name.capitalized, effect.icon) { viewModel.sendEffect(effect: effect.name, session: session) }
            }),
        ]
        if conversation.isGroup {
            express.append(item(viewModel.anonOn ? "Incognito on - tap to send under your name" : "Incognito",
                viewModel.anonOn ? "theatermasks.fill" : "theatermasks") {
                PulseHaptics.tap(); viewModel.anonOn.toggle()
            })
        }
        return UIMenu(children: [
            item("Photo Library", "photo") { photoPickerOwner = session.sessionGeneration; showPhotoPicker = true },
            item("Camera", "camera") { requestCamera() },
            item("Document", "folder") { documentPickerOwner = session.sessionGeneration; showFileImporter = true },
            item("Location", "location.fill") { locationOpen = true },
            item("Schedule send", "calendar.badge.clock") { scheduleOpen = true },
            item("Scheduled sends", "clock.arrow.circlepath") { scheduledManagerOpen = true },
            item("New Poll", "chart.bar") { pollBuilderOpen = true },
            item("Whiteboard", "pencil.and.outline") { wave7.whiteboardOpen = true },
            item("Red packet", "gift.fill") { wave7.redPacketCreateOpen = true },
            item("Events", "calendar") { wave7.eventsOpen = true },
            item("Game", "gamecontroller") { wave7.gameCreateOpen = true },
            item("Tournament", "trophy") {
                if conversation.isGroup { wave7.tournamentCreateOpen = true }
                else { wave7.toast("Tournaments are for groups only", isError: true) }
            },
            item("Kanban", "square.stack.3d.up.fill") { wave7.kanbanOpen = true },
            UIMenu(title: "Express", options: .displayInline, children: express),
        ])
    }

    // ── R1-W2B F-MS-29 - the quick-phrase rail ───────────────
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
                        .foregroundStyle(PulseTheme.emberGlowTop)
                        .frame(width: 30, height: 30)
                        .background(Capsule().fill(PulseTheme.emberGlowTop.opacity(0.10)))
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
                    Group {
                        if let data = staged.previewData, let image = UIImage(data: data) {
                            Image(uiImage: image).resizable().scaledToFill()
                        } else { RoundedRectangle(cornerRadius: 8).fill(.quaternary) }
                    }
                    .frame(width: 44, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                case .file, .audio:
                    Image(systemName: staged.kind == .audio ? "waveform.circle.fill" : "paperclip.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(PulseTheme.emberGlowTop)
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
            // Wave 2 view-once - image sends only (server: viewOnce requires
            // imagePath, spec §0).
            if staged.kind == .image {
                Toggle(
                    "View once - disappears after opening",
                    isOn: Binding(
                        get: { viewModel.staged?.viewOnce ?? false },
                        set: { viewModel.setStagedViewOnce($0) },
                    ),
                )
                .font(.caption)
                .tint(PulseTheme.emberGlowTop)
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
                .foregroundStyle(PulseTheme.emberGlowTop)
            VStack(alignment: .leading, spacing: 1) {
                Text(reply.displayAuthorName)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(PulseTheme.emberGlowTop)
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

/// Wave 1 message info - seen-by watermarks (conversation members) + the
/// reaction groups with member names. Read-only, native sheet.
/// R2-D - the sheet mirrors the web/Android delivered split: "Seen by" =
/// lastReadAt >= createdAt, "Delivered to" = the rest (viewer excluded).
private struct MessageInfoSheet: View {
    let message: WireChatMessage
    let conversation: WireConversationSummary
    /// R2-D - the viewer id (excluded from both receipt lists, web parity).
    var viewerId: String? = nil

    @Environment(\.dismiss) private var dismiss

    private var receipts: PulseRoomParityLogic.ReceiptSplit {
        PulseRoomParityLogic.receiptSplit(
            members: conversation.members.map {
                PulseRoomParityLogic.ReceiptMember(id: $0.id, lastReadAtIso: $0.lastReadAt, readMembershipId: $0.readMembershipId)
            },
            viewerId: viewerId,
            createdAtIso: message.createdAt, readByMembershipIds: message.readByMembershipIds ?? [],
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
                // A missing read receipt is NOT proof of device delivery.
                // Privacy-disabled receipts also belong in this unknown group.
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
                    Text("No read confirmation · \(split.deliveredTo.count)")
                }
                if let reactions = message.reactions, !reactions.isEmpty {
                    Section("Reactions") {
                        ForEach(reactions, id: \.emoji) { group in
                            // R19-b - normalized registry symbol + label, the
                            // wire group key is never rendered raw.
                            HStack(spacing: 8) {
                                Image(systemName: PulseReactionId.normalize(group.emoji).symbolName)
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(PulseTheme.emberGlowTop)
                                Text(PulseReactionId.normalize(group.emoji).label)
                                    .font(.subheadline)
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

    /// One receipt row - read watermark stamp + the double-check (seen) or
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
                    ? "Read confirmed"
                    : "No read confirmation")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: seen ? "checkmark.circle.fill" : "checkmark.circle")
                .foregroundStyle(seen ? PulseTheme.emberGlowTop : PulseTheme.textTertiary)
        }
    }
}

/// R47 - conditional double-tap attachment: when `action` is nil NOTHING is
/// attached (call sites keep their exact gesture graph). When present, the
/// COUNT-2 tap is attached BEFORE any single-tap on the same view so the
/// double-tap wins the disambiguation (SwiftUI higher-count-first rule).
private extension View {
    @ViewBuilder
    func heartDoubleTapGesture(_ action: (() -> Void)?) -> some View {
        if action != nil {
            onTapGesture(count: 2) { action?() }
        } else {
            self
        }
    }

    /// R7 - conditional drag attachment: when `active` is false NOTHING is
    /// attached (call sites keep their exact gesture graph) - the swipe-to-
    /// reply twin of heartDoubleTapGesture above.
    @ViewBuilder
    func swipeReplyDrag<G: Gesture>(_ gesture: G, active: Bool) -> some View {
        if active {
            self.gesture(gesture)
        } else {
            self
        }
    }
}

/// One message bubble - the web outcome with an iOS accent: asymmetric
/// corner radius, gradient fill for the viewer, quote block, reaction chips,
/// voice/image/file/system renderings, "Seen" under the viewer's tail.
struct BubbleView: View {
    let message: WireChatMessage
    let mine: Bool
    let groupChat: Bool
    let viewerColor: () -> Color
    var seen: Bool = false
    var seenLabel: String = "Seen"
    var onLongPressActions: Bool = true
    /// Wave 1 - jump flash ring, live thread-reply chip, media surfaces.
    var flashing: Bool = false
    var replyCount: Int = 0
    /// Wave 8 - prefs bubble corner token (md=10, lg=16, pill=26 native
    /// radii; the tail corner stays 6). Defaults to the web's 'lg'.
    var bubbleRadius: PulseBubbleRadius = .lg
    var onOpenImage: ((WireChatMessage) -> Void)? = nil
    var onOpenFile: ((WireChatMessage) -> Void)? = nil
    var onOpenThread: ((WireChatMessage) -> Void)? = nil
    // Wave 2 - voice playback/transcribe, poll voting/close, view-once reveal.
    var viewerId: String? = nil
    var voicePlayback: VoicePlaybackManager.PlaybackState? = nil
    var voiceRate: Float = 1.0
    var onVoicePlayToggle: (() -> Void)? = nil
    var onVoiceRateCycle: (() -> Void)? = nil
    var onTranscribe: (() -> Void)? = nil
    var onPollVote: ((String) -> Void)? = nil
    var onPollClose: (() -> Void)? = nil
    var onViewOnceOpen: ((WireChatMessage) -> Void)? = nil
    // ── Wave 7 - rich-object cards (red packet / game / tournament) ──
    var wave7: Wave7RoomActions? = nil
    // R3-A item 7 - roster names drive the @mention chips in body text.
    var memberNames: [String] = []
    // R3-A item 4 - tap a reaction chip → who-reacted roster sheet (web
    // chat-room.tsx ReactionChip tap → reactionInfo parity). nil = decorative.
    var onReactionChip: ((WireChatMessage, String) -> Void)? = nil
    // R47 - double-tap bubble fires the heart quick reaction (web chat-room.tsx:7265-7268
    // onDoubleClick parity). nil = gesture NOT attached (threads stay nil;
    // the hearts burst rides the VM react path, not here).
    var onDoubleTapHeart: (() -> Void)? = nil
    // R7 - web buildItems cluster verdict: sender name renders on HEAD rows
    // only (chat-room.tsx:7289 `!mine && isGroup && head` parity). Default
    // true keeps thread/decorative uses rendering exactly as before.
    var isClusterHead: Bool = true
    // R7 - swipe-bubble-to-reply (web chat-room.tsx:7248-7263): horizontal
    // drag past 28pt toward the reply side → beginReply. nil = gesture NOT
    // attached (threads/decorative uses keep their exact gesture graph).
    var onSwipeReply: (() -> Void)? = nil

    // R7 - live horizontal offset of the bubble during the reply swipe
    // (clamped ±64, spring-snapped back on release, web dragConstraints).
    @State private var swipeX: CGFloat = 0

    /// R47 - the double-tap action when the row is interactive (web
    /// `interactive = !deleted && !pending`, chat-room.tsx:7212). nil when
    /// no handler was wired - the gesture modifier is then never attached,
    /// so thread rows and decorative uses keep their exact old behavior.
    private var heartDoubleTapAction: (() -> Void)? {
        guard onDoubleTapHeart != nil, !isDeleted, !isPending else { return nil }
        return { onDoubleTapHeart?() }
    }

    // R7 - the swipe signal along the NATURAL reply direction (web towardX:
    // mine ? -x : x) - dragging "the wrong way" keeps the hint at opacity 0.
    private var swipeToward: CGFloat { mine ? -swipeX : swipeX }

    /// R7 - the horizontal reply drag (web chat-room.tsx:7248-7263):
    /// minimumDistance 14 keeps vertical scrolling AND the long-press
    /// contextMenu recognizing (the drag only claims predominantly horizontal
    /// moves); the count-2 double-tap still wins - it needs no 14pt travel.
    /// Clamp ±64 (web dragConstraints), toward > 28 → haptic + the wired
    /// reply path, then the bubble spring-snaps home (web dragSnapToOrigin).
    private var swipeReplyGesture: some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { value in
                swipeX = max(-64, min(64, value.translation.width))
            }
            .onEnded { value in
                let toward = mine ? -value.translation.width : value.translation.width
                if toward > 28 {
                    PulseHaptics.tap()
                    onSwipeReply?()
                }
                withAnimation(.spring(response: 0.35, dampingFraction: 0.78)) {
                    swipeX = 0
                }
            }
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isDeleted: Bool { message.deletedAt != nil }
    private var isSystem: Bool { message.kind == "system" }
    /// Wave 0 outbox - optimistic queued bubble (id "local_<clientId>").
    private var isPending: Bool { message.id.hasPrefix("local_") }

    // Wave 2 view-once - sender always renders normal; a row the viewer has
    // ALREADY opened renders NO image at all (anti-replay hardening, spec §1
    // row 6: no blurred original, no lightbox entry, no preview fetch).
    private var viewOnceGated: Bool {
        message.viewOnce == true && !mine && message.viewedAt == nil && message.imagePath != nil
    }
    private var viewOnceBurned: Bool {
        message.viewOnce == true && !mine && message.viewedAt != nil
    }

    /// Wave 8 - the corner token drives every radius. EMB-I moves the
    /// asymmetric 6 to the TOP corner of a cluster head (incoming = top
    /// leading, outgoing = top trailing); non-head rows stay fully rounded.
    private var bubbleShape: UnevenRoundedRectangle {
        let r = bubbleRadius.cornerRadius
        let head = isClusterHead
        return mine
            ? UnevenRoundedRectangle(topLeadingRadius: r, bottomLeadingRadius: r, bottomTrailingRadius: r, topTrailingRadius: head ? 6 : r)
            : UnevenRoundedRectangle(topLeadingRadius: head ? 6 : r, bottomLeadingRadius: r, bottomTrailingRadius: r, topTrailingRadius: r)
    }

    var body: some View {
        VStack(alignment: mine ? .trailing : .leading, spacing: 2) {
            if isSystem {
                CapsuleLabel(message.content)
                    .padding(.vertical, 4)
            } else {
                // R7 - sender name renders on HEAD rows of incoming group
                // messages only (web chat-room.tsx:7289 head gate; the little
                // color dot is the row's avatar stand-in - gated with it).
                // EMB-I - sender name 12pt ember glow (#FFB86B).
                if message.integrationLabel == nil && groupChat && !mine, isClusterHead, let sender = message.sender {
                    HStack(spacing: 5) {
                        Circle().fill(PulseTheme.color(named: sender.color)).frame(width: 6, height: 6)
                        Text(sender.name)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(PulseTheme.emberGlowTop)
                    }
                    .padding(.horizontal, 4)
                }

                HStack(alignment: .bottom, spacing: 4) {
                    if mine { Spacer(minLength: 44) }
                    bubble
                        .offset(x: swipeX)
                        .heartDoubleTapGesture(heartDoubleTapAction)
                        .swipeReplyDrag(swipeReplyGesture, active: onSwipeReply != nil)
                    if !mine { Spacer(minLength: 44) }
                }
                .overlay(alignment: mine ? .trailing : .leading) {
                    // R7 - reply-arrow hint while dragging (web hintOpacity =
                    // clamp((toward-4)/24, 0…1); both sides share one signal,
                    // so dragging "the wrong way" keeps the hint invisible).
                    if swipeX != 0 {
                        Image(systemName: mine ? "arrowshape.turn.up.right.fill" : "arrowshape.turn.up.left.fill")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(PulseTheme.emberGlowTop)
                            .padding(7)
                            .background(Circle().fill(PulseTheme.emberGlowTop.opacity(0.12)))
                            .opacity(min(CGFloat(1), max(CGFloat(0), (swipeToward - 4) / 24)))
                            .allowsHitTesting(false)
                    }
                }

                // Wave 1 - "N replies ↳" chip opens the thread sheet.
                if replyCount > 0 && message.parentId == nil && onOpenThread != nil {
                    Button {
                        onOpenThread?(message)
                    } label: {
                        Label("\(replyCount) \(replyCount == 1 ? "reply" : "replies")", systemImage: "arrow.turn.down.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(PulseTheme.emberGlowTop)
                    }
                    .buttonStyle(PulseButtonStyle())
                    .padding(.trailing, 6)
                }

                if mine && seen {
                    Text(seenLabel)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(PulseTheme.emberGlowTop)
                        .padding(.trailing, 6)
                } else if mine && !isPending && onLongPressActions {
                    Text("Sent")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(Color.white.opacity(0.40))
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
                Label("Message deleted", systemImage: "trash")
                    .font(.footnote)
                    .foregroundStyle(Color.white.opacity(0.50))
            } else {
                if let origin = message.integrationLabel {
                    Text(origin).font(.caption2.weight(.semibold)).foregroundStyle(PulseTheme.emberGlowTop)
                }
                if let label = PulseAttachmentDelivery.pendingLabel(message) {
                    Label(label, systemImage: "arrow.up.doc").font(.subheadline)
                    if !message.content.isEmpty { Text(message.content) }
                } else { content }
                // Wave 2 link preview - under the text, suppressed on poll
                // rows; nothing renders until the unfurl envelope lands.
                if message.poll == nil, let preview = message.linkPreview {
                    LinkPreviewCard(preview: preview)
                }
                // R1-W2B F-MD-06 - persisted LLM translation under the
                // original (web TranslationLine parity: italic secondary
                // strip with the globe glyph; chat-room.tsx:6913-6955).
                if let translations = message.translations, let first = translations.first {
                    translationStrip(first)
                }
            }

            HStack(spacing: 5) {
                // EMB-I - 10pt white 40% timestamp inside the bubble tail.
                Text(PulseFormat.clockTime(message.createdAt))
                    .font(.system(size: 10))
                    .monospacedDigit()
                    .foregroundStyle(Color.white.opacity(0.40))
                if isPending {
                    // Queued offline - still in the outbox, clock = not sent yet.
                    Image(systemName: "clock")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.45))
                }
                if message.pinnedAt != nil {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(Color.white.opacity(0.45))
                }
            }
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 9)
        .background(bubbleShape.fill(bubbleFill))
        .overlay(reactionChips, alignment: .bottom)
        .padding(.bottom, reactionChipsHeight())
        .overlay(
            // Jump-to-message flash ring (amber pulse, ~1.5s - web parity).
            RoundedRectangle(cornerRadius: bubbleRadius.cornerRadius, style: .continuous)
                .stroke(PulseTheme.amber, lineWidth: 2.5)
                .opacity(flashing ? 1 : 0)
                .animation(.easeInOut(duration: 0.45).repeatForever(autoreverses: true), value: flashing)
                .allowsHitTesting(false),
        )
    }

    @ViewBuilder
    private var content: some View {
        // Wave 2 polls - the card replaces the body text entirely.
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
                // R3-A items 6/7 - the web BubbleText outcome natively: jumbo
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
                load: { id in try await wave7.cardGameLoad(id) },
                onMove: { cell in await wave7.cardMove(gp.matchId, cell) },
                onJoin: { await wave7.cardJoinGame(gp.matchId) },
                // R7 - rematch POSTs /api/games against the finished match's
                // opponent; toast + carrier message ride the wave7 hub.
                wave7: wave7,
            )
            .id("\(viewerId ?? ""):game:\(gp.matchId)")
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
                load: { id in try await wave7.cardTournamentLoad(id) },
                onJoin: { await wave7.cardJoinTournament(tp.tournamentId) },
                onFinish: { await wave7.cardFinishTournament(tp.tournamentId) },
            )
            .id("\(viewerId ?? ""):season:\(tp.tournamentId)")
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

    /// Stored image render - AsyncImage against the gateway upload URL,
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
                            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    case .empty:
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .fill(Color.white.opacity(0.08))
                            .frame(width: 200, height: 140)
                            .overlay(ProgressView())
                    case .failure:
                        Label("Image unavailable", systemImage: "photo.badge.exclamationmark")
                            .font(.footnote)
                    @unknown default:
                        ProgressView()
                    }
                }
                .heartDoubleTapGesture(heartDoubleTapAction)
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
                        RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.white.opacity(0.08))
                    }
                    .frame(width: 200, height: 200)
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    // Blur the IMAGE only (spec) - the overlay stays crisp.
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

    /// Document bubble - name + humanized size, tap → download + QuickLook.
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
                .foregroundStyle(mine ? Color.white : PulseTheme.emberGlowTop)
            // Deterministic waveform bars from the message id (native mirror
            // of the web voice bubble, no emoji chrome).
            HStack(spacing: 2) {
                ForEach(0..<14, id: \.self) { index in
                    let seed = abs(message.id.hashValue)
                    let height = CGFloat(5 + (seed * (index + 7)) % 15)
                    Capsule()
                        .fill(mine ? Color.white.opacity(0.85) : PulseTheme.emberGlowTop.opacity(0.7))
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

    // ── R1-W2B F-MD-06 - translation strip ───────────────────

    /// The first persisted translation rendered under the original text -
    /// secondary italic with a globe glyph (web TranslationLine parity).
    private func translationStrip(_ translation: WireTranslation) -> some View {
        HStack(alignment: .top, spacing: 5) {
            Image(systemName: "globe")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(mine ? Color.white.opacity(0.85) : PulseTheme.emberGlowTop)
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
        .background(RoundedRectangle(cornerRadius: 9).fill(mine ? Color.white.opacity(0.14) : PulseTheme.emberGlowTop.opacity(0.06)))
    }

    // ── R1-W2B F-MD-07 - location card ───────────────────────

    /// kind "location" rows render the stylized map card (web LocationBubble
    /// parity - pure vector art, no tile servers); tapping opens Apple Maps
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
                            .fill(mine ? Color.white.opacity(0.18) : PulseTheme.emberGlowTop.opacity(0.10))
                        // Graticule grid - the pin's spot comes from the real
                        // coordinates (equirectangular projection, web parity).
                        VStack(spacing: 18) {
                            ForEach(0..<4, id: \.self) { _ in
                                Rectangle()
                                    .fill((mine ? Color.white : PulseTheme.emberGlowTop).opacity(0.16))
                                    .frame(height: 1)
                            }
                        }
                        HStack(spacing: 26) {
                            ForEach(0..<5, id: \.self) { _ in
                                Rectangle()
                                    .fill((mine ? Color.white : PulseTheme.emberGlowTop).opacity(0.16))
                                    .frame(width: 1)
                            }
                        }
                        VStack(spacing: 3) {
                            Image(systemName: "mappin.circle.fill")
                                .font(.system(size: 30))
                                .foregroundStyle(mine ? Color.white : PulseTheme.emberGlowTop)
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
            .accessibilityLabel("Location pin \(loc.label) - open in Maps")
        } else {
            // Corrupt/legacy payload - degrade to the plain content text.
            Text(message.content)
                .font(.body)
                .textSelection(.enabled)
        }
    }

    private var bubbleFill: some ShapeStyle {
        // EMB-I bubbles: outgoing #17110D, incoming #2E2824 (flat, no mint).
        if mine {
            return AnyShapeStyle(PulseTheme.emberBubbleOutgoing)
        }
        return AnyShapeStyle(PulseTheme.emberBubbleIncoming)
    }

    @ViewBuilder
    private func replyQuote(_ reply: WireReplySnippet) -> some View {
        TimelineView(.periodic(from: .now, by: 1)) { clock in
            let visible = reply.presentation(at: clock.date)
            VStack(alignment: .leading, spacing: 2) {
                Text(visible.senderName)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(mine ? Color.white : PulseTheme.emberGlowTop)
                Text(visible.content)
                    .font(.caption)
                    .foregroundStyle(Color.white.opacity(0.70))
                    .lineLimit(2)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 9).fill(Color.white.opacity(0.08)))
        }
    }

    private var reactions: [WireReactionGroup] { PulseReactionId.groups(message.reactions) }

    private var reactionChips: some View {
        HStack(spacing: 4) {
            ForEach(reactions, id: \.emoji) { group in
                // R3-A item 4 - the chip is a Button whenever the room wires
                // the who-reacted sheet (web ReactionChip long-press → drawer
                // parity, native affordance = tap). Without the callback the
                // chip stays decorative (threads).
                Button {
                    PulseHaptics.tap()
                    onReactionChip?(message, group.emoji)
                } label: {
                    // R19-b - the chip renders the NORMALIZED reaction's SF
                    // Symbol + count: the wire group key is never rendered raw.
                    HStack(spacing: 3) {
                        Image(systemName: PulseReactionId.normalize(group.emoji).symbolName)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(PulseTheme.emberGlowTop)
                        if group.count > 1 {
                            Text("\(group.count)")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(Color.white.opacity(0.55))
                        }
                    }
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    // EMB-I - glass circle chips: white 10% + white 12% ring.
                    .background(
                        Capsule()
                            .fill(Color.white.opacity(0.10))
                            .overlay(Capsule().strokeBorder(Color.white.opacity(0.12), lineWidth: 1)),
                    )
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    onReactionChip == nil
                        ? "\(group.count) reactions with \(PulseReactionId.normalize(group.emoji).label)"
                        : "Who reacted with \(PulseReactionId.normalize(group.emoji).label) - \(group.count) people",
                )
            }
        }
        .offset(y: 12)
    }

    private func reactionChipsHeight() -> CGFloat {
        reactions.isEmpty ? 0 : 14
    }
}

/// R3-A items 6/7 - the web BubbleText outcome natively. Runs come from the
/// tested pure logic (PulseBubbleTextLogic = buildMentionRuns + the ported
/// FORMAT_RE parser + the R4-A splitUrlSegments composition); the renderer:
///   • concatenates consecutive inline runs into ONE SwiftUI Text (perfect
///     flow for the common bold/italic/code/mention cases),
///   • renders ```pre``` blocks and ||spoiler|| runs as detached blocks -
///     spoilers blur + tap-reveal (web SpoilerSpan :6804-6834 parity),
///   • R4-A item 1 - url runs ride AttributedString `.link` so they render
///     as tappable links INSIDE the composed text (web renderPlain anchors,
///     :6873-6896). One attributed Text per inline chunk keeps every
///     per-run attribute (including links) intact - Text-concatenation
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

    /// R4-A item 1 - inline chunks assemble as ONE AttributedString so a
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
    /// strike, mono code chips, emerald mention chips - plus R4-A url runs
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
            piece.foregroundColor = .white
            piece.backgroundColor = PulseTheme.emberGlowBottom.opacity(0.24)
        case .url:
            // Web renderPlain :6876-6891 - href https://-prefixes www.,
            // underline, text-white on my bubbles / emerald-700 light and
            // emerald-400 dark otherwise. A token the URL parser cannot
            // promote to a URL degrades to styled text (honest, no crash).
            piece.link = Self.linkURL(for: run.text)
            piece.underlineStyle = .single
            piece.foregroundColor = mine ? Color.white : PulseTheme.emberGlowTop
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

    /// ```pre``` - block-level mono card (web :6917-6928).
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

    /// ||spoiler|| - blur-reveal (web SpoilerSpan: 5px blur + wash, tap once).
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
            .accessibilityLabel(revealed ? text : "Hidden spoiler - tap to reveal")
    }

    /// Spoiler ids must survive re-renders even when the SAME text appears
    /// twice - key on the run text (revealed state is per-row @State anyway).
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

/// Room state holder - transport lives in PulseSession; this owns messages,
/// drafts, replies, receipts and the live signal subscriptions.
@MainActor
final class RoomViewModel: ObservableObject {
    enum Phase: Equatable { case idle, loading, loaded, failed(String) }

    struct Typer: Equatable {
        let userId: String
        let userName: String
        let expiresAt: Date
    }

    /// Wave 1 attach flow - a staged photo/document awaiting upload + send.
    struct StagedMedia: Equatable {
        enum Kind { case image, file, audio }
        let kind: Kind
        let dataUrl: String
        let fileName: String?
        let fileSize: Int?
        /// Local preview for the staged card (images only).
        let previewURL: URL?
        /// Wave 2 view-once - image sends only (server: viewOnce needs imagePath).
        let viewOnce: Bool
        var durationMs: Double? = nil
        var previewData: Data? = nil
    }

    @Published private(set) var messages: [WireChatMessage] = [] {
        didSet { noteRiverChanged() }
    }
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var errorText: String?
    @Published private(set) var typers: [Typer] = []
    @Published private(set) var partnerLastReadAt: Date?
    @Published var draft = "" {
        didSet {
            if oldValue != draft {
                draftEditRevision &+= 1
                if editingTarget == nil { journalDraft(draft) }
            }
        }
    }
    private var draftEditRevision: UInt64 = 0
    private var draftBeforeEdit: String?
    private var replyBeforeEdit: WireChatMessage?
    private var editSaveTask: Task<Void, Never>?
    private var editGeneration = UUID()
    private var savingEdit = false
    @Published var replyTarget: WireChatMessage?

    // Wave 1 - pagination, threads, actions, search, jump, media.
    @Published private var historyWindow = PulseHistoryWindow()
    var hasMore: Bool { historyWindow.hasMore }
    @Published private(set) var loadingOlder = false
    @Published private(set) var replyCounts: [String: Int] = [:]
    private var nextReplyCountExpiry: Date?
    @Published private(set) var pins: [WireChatMessage] = []
    @Published private(set) var flashMessageId: String?
    @Published private(set) var jumpRequestRevision: UInt64 = 0
    @Published private(set) var jumpTargetId: String?
    @Published private(set) var jumpThreadRoot: WireChatMessage?
    private(set) var jumpThreadReplyId: String?
    func consumeThreadJump() { jumpThreadRoot = nil; jumpThreadReplyId = nil }
    @Published private(set) var searchResults: [WireChatMessage] = []
    @Published private(set) var searching = false
    @Published var editingTarget: WireChatMessage?
    @Published var staged: StagedMedia?
    @Published var caption = ""
    @Published private(set) var uploading = false

    // ── REM-B - scheduled / slow-mode / incognito / slash ──
    /// F-MS-18 - the viewer's pending scheduled rows for THIS room (the list
    /// feeds the composer banner chip; the manager sheet lists details).
    /// R5-A Item 6 - the chip now also surfaces the SOONEST non-cancelled
    /// row's relative stamp (web scheduledChip parity), so the full list
    /// replaces the bare count.
    @Published private(set) var scheduledItems: [WireScheduledItem] = []
    /// F-MS-18 - pending-row count for the chip (R5-A: derived from the list).
    var scheduledCount: Int { scheduledItems.filter { $0.cancelledAt == nil }.count }
    private var scheduledRevision = UUID()
    private var scheduling = false
    /// R5-A Item 6 - web scheduledChip parity: "{next} · {count} pending -
    /// tap to manage" where next = the soonest non-cancelled row's stamp
    /// (web formatListStamp(items[0]); the API sorts pending soonest-first).
    var scheduledChipText: String? {
        guard !scheduledItems.isEmpty else { return nil }
        let next = PulseFormat.listStamp(PulseScheduledChip.nextIso(in: scheduledItems))
        if next.isEmpty {
            return scheduledCount == 0 ? "Scheduled messages need attention - tap to manage" : "\(scheduledCount) pending - tap to manage"
        }
        return "\(next) · \(scheduledCount) pending - tap to manage"
    }
    /// F-MS-20 - slow-mode lockout: after a 429 the composer refuses sends
    /// until the server-suggested window elapses (countdown chip in the UI).
    @Published private(set) var slowModeLockUntil: Date?
    @Published private(set) var slowModeRemainingSeconds = 0
    /// F-MS-17 - incognito mask (groups only): sends ride anon:true and the
    /// optimistic bubble shows the deterministic FNV-1a alias.
    @Published var anonOn = false
    /// F-MS-22 - sheet command armed by the slash palette ('/poll' etc.) -
    /// the view consumes it in onChange to present the right surface.
    @Published var pendingSlashSheet: String?
    /// R1-W2B F-MS-29 - the viewer's quick phrases for the composer rail
    /// (server CRUD via /api/users/{id}/phrases; the manage sheet reloads
    /// this through loadQuickPhrases after every mutation).
    @Published private(set) var quickPhrases: [WireQuickPhrase] = []

    // ── R2-B - unread divider · jump-pill missed count · AI recap · veil ──
    /// R30-c - the tap-time unread anchor (web chats-tab.tsx:431-435 freeze):
    /// MY lastReadAt from the route-carried summary when the row showed an
    /// unread badge, else nil - nil means the divider never places.
    private let firstUnreadMessageId: String?
    private var topicUnreadAnchors: [String: String] = [:]
    private var capturedTopicAnchors: Set<String> = []

    private func captureTopicAnchor(_ id: String?) {
        guard let id, !capturedTopicAnchors.contains(id),
              let topic = topics.first(where: { $0.id == id }), topic.unreadCount != nil else { return }
        capturedTopicAnchors.insert(id)
        topicUnreadAnchors[id] = topic.firstUnreadMessageId
    }
    /// Count distinct incoming arrivals, never changes in rendered history length.
    @Published private(set) var missedCount = 0
    private var missedMessages = PulseMissedMessages()
    private var missedScopeRevision: UInt64 = 0
    /// Measured tail visibility owns badge clearing; tapping Jump alone does not.
    @Published var isTailVisible = false {
        didSet { if oldValue != isTailVisible { noteRiverChanged() } }
    }
    @Published var readSurfaceObscured = false { didSet { noteRiverChanged() } }

    private var readerMemberships: [String: String] = [:]
    private var visibleReadIDs: Set<String> = []

    func updateReadViewport(visibleIDs: Set<String>, tailVisible: Bool) {
        let changed = visibleReadIDs != visibleIDs
        visibleReadIDs = visibleIDs
        if isTailVisible != tailVisible { isTailVisible = tailVisible }
        else if changed { noteRiverChanged() }
    }

    func updateReadBoundary() {
        guard let session, session.sessionGeneration == readSessionGeneration else { return }
        let boundaries = readSurfaceObscured ? [] : messages.filter {
            visibleReadIDs.contains($0.id) && !$0.id.hasPrefix("local_") && $0.deletedAt == nil &&
                PulseRemediationLogic.isAlive($0.expiresAt)
        }.map(\.id)
        session.setVisibleReadBoundaries(conversationId: conversationId, messageIds: boundaries)
    }

    /// Web showJump - armed when a new tail lands while scrolled away,
    /// cleared at the bottom (never by the scroll position alone).
    @Published private(set) var showJumpPill = false
    /// R34-b - live recap card content; nil = no card. Loading while the
    /// LLM summarizes, then the returned summary; auto-dismisses after 15 s.
    enum RecapPhase: Equatable { case loading; case ready(text: String, basedOn: Int) }
    @Published private(set) var recap: RecapPhase?
    private var recapDismissTask: Task<Void, Never>?
    /// R38 - the room-wide screen-security switch (server conversation flag;
    /// nil = unknown / older relay → keep the inactive veil).
    @Published var roomScreenPrivacy: Bool?
    /// R47 - DM blocked-pair dead-end (wire detail `dmBlocked`, a UserBlock
    /// in EITHER direction; server 403 stays the enforcement - client is UX
    /// only). False until the room-open detail GET says otherwise.
    @Published private(set) var dmBlocked = false

    // Wave 2 - voice recording, playback, polls, transcription, topics.
    @Published private(set) var isRecording = false
    @Published private(set) var recordingElapsedMs: Double = 0
    @Published private(set) var sendingVoice = false
    /// D31 - live mic levels (0…1) sampled every 100 ms while held; the
    /// composer renders the last VoiceMath.liveWaveformBars as bars.
    @Published private(set) var recordingAmplitudes: [Double] = []
    @Published private(set) var transcriptionBusy: Set<String> = []
    @Published private(set) var topics: [WireTopic] = []
    /// nil = General = the WHOLE room unfiltered (spec §1 row 9).
    @Published var activeTopicId: String?
    @Published private(set) var voiceRate: Float

    /// W7 - carrier rows (game / tournament / red packet) land instantly from
    /// the flow that created them; the next window refresh reconciles.
    func injectCarrier(_ m: WireChatMessage) {
        guard !messages.contains(where: { $0.id == m.id }) else { return }
        messages.append(m)
    }

    private let conversationId: String
    fileprivate let readSessionGeneration: UUID
    private let isGroupRoom: Bool
    private weak var session: PulseSession?
    private var cancellables: Set<AnyCancellable> = []
    private var typingStopTask: Task<Void, Never>?
    private var draftSaveTask: Task<Void, Never>?
    private let draftJournal: PulseDraftJournal?
    private var flashClearTask: Task<Void, Never>?
    private var initialJumpMessageId: String?
    /// Member read watermarks (id → lastReadAt) - group-aware "Seen".
    private var memberWatermarks: [String: Date] = [:]
    private var receiptRevision = 0
    // Wave 2 voice plumbing - recorder + single active player + timers.
    private var voiceRecorder: VoiceRecorder?
    private var voicePermissionTask: Task<Void, Never>?
    private var voicePermissionGeneration = UUID()
    private var recordingStartedAt: Date?
    private var recordingTicker: AnyCancellable?
    /// D31 - the session that armed the hold; the auto-send-at-cap tick needs
    /// it when the finger is still down and cannot deliver it.
    private weak var voiceSession: PulseSession?
    private var topicTicker: AnyCancellable?
    private var voiceLocalFiles: [String: URL] = [:]
    private var voicePlaybackTask: Task<Void, Never>?
    private var voicePlaybackGeneration = UUID()
    /// One active voice-note player for the whole room (messageId-keyed).
    private(set) var playback = VoicePlaybackManager()

    var canSend: Bool {
        if staged != nil { return true }
        return !draft.trimmingCharacters(in: .whitespaces).isEmpty && !isSlowModeLocked
    }

    /// F-MS-20 - the composer is locked while the slow-mode window runs.
    var isSlowModeLocked: Bool {
        (slowModeLockUntil ?? .distantPast) > Date()
    }

    /// F-MS-18 - scheduled-row refresh (open + after arming/cancelling).
    func loadScheduled(session: PulseSession) {
        guard !scheduling else { return }
        let epoch = session.sessionGeneration, client = session.api, ticket = UUID()
        scheduledRevision = ticket
        Task { [weak self] in
            guard let self else { return }
            do {
                let rows = try await client.scheduledMessages(conversationId: conversationId)
                guard !Task.isCancelled, session.sessionGeneration == epoch, self.scheduledRevision == ticket else { return }
                self.scheduledItems = rows
            } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch, self.scheduledRevision == ticket else { return }
                if let failure = error as? PulseAPIClient.Failure, [.auth, .forbidden, .notFound].contains(failure.kind) { self.scheduledItems = [] }
                // A failed refresh is not an empty queue; the manager provides retry.
            }
        }
    }

    /// R30-b - header-badge count: the viewer's UNFIRED reminders (web
    /// upcomingReminderCount chat-room.tsx:2311-2313 `firedAt === null`
    /// parity - the GET is viewer-scoped, so this spans all rooms).
    @Published private(set) var upcomingReminderCount = 0

    /// R30-b - lightweight upcoming fetch on room open + after the reminders
    /// sheet closes (the sheet loads its own list; the badge needs the count
    /// only - loadPins pattern). A failed fetch keeps the last honest count.
    func loadUpcomingReminderCount(session: PulseSession) {
        Task { [weak self] in
            guard let self else { return }
            let page = try? await session.api.reminders(dueOnly: false)
            self.upcomingReminderCount = PulseRoomParityLogic.upcomingReminderCount(page?.items ?? [])
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

    init(conversation: WireConversationSummary, session: PulseSession, initialJumpMessageId: String? = nil, beginAutomaticWork: Bool = true) {
        self.conversationId = conversation.id
        self.readSessionGeneration = session.sessionGeneration
        self.draftJournal = session.store.map { PulseDraftJournal(store: $0, conversationId: conversation.id) }
        self.session = session
        self.isGroupRoom = conversation.isGroup
        self.initialJumpMessageId = initialJumpMessageId
        self.voiceRate = VoicePlaybackManager.storedRate()
        // R2-B R30-c - freeze the unread anchor from the list summary the
        // route carried (web freezes the same values at tap time).
        let viewerId = session.viewer?.id
        self.firstUnreadMessageId = conversation.firstUnreadMessageId
        self.roomScreenPrivacy = conversation.screenPrivacy
        for member in conversation.members where member.id != viewerId {
            readerMemberships[member.id] = member.readMembershipId
            if let stamp = member.lastReadAt, let at = PulseFormat.date(stamp), at.timeIntervalSince1970 > 0 {
                memberWatermarks[member.id] = at
            }
        }
        partnerLastReadAt = memberWatermarks.values.max()
        if beginAutomaticWork {
            observe(session: session)
            seedLocalState(conversation: conversation, session: session)
            loadPins(session: session)
            startTopicTicker()
            loadTopics(session: session)
            // REM-B F-MS-18 - pending scheduled count for the composer banner.
            loadScheduled(session: session)
            // R30-b - upcoming reminders for the header badge (one quiet GET).
            loadUpcomingReminderCount(session: session)
            requestRefresh(session: session)
        }
    }

    /// Wave 2 - a recording/playback must never outlive the room. Called
    /// from ChatRoomView.onDisappear (roomVisible off); a conversation
    /// change builds a NEW view model, so its deinit covers that path.
    func handleRoomDisappeared() {
        quickPhraseTask?.cancel()
        quickPhraseReads.stop()
        privacyReads.stop()
        cancelPhotoImport()
        journalDraft(draftBeforeEdit ?? draft)
        draftSaveTask?.cancel()
        typingStopTask?.cancel()
        refreshTask?.cancel()
        refreshGeneration = UUID()
        cancelHistory()
        riverReloadTask?.cancel()
        cancelVoiceRecording()
        cancelPendingVoicePlayback()
        playback.stop()
    }

    deinit {
        // Plain-class teardown only (no actor-isolated state touched).
        quickPhraseTask?.cancel()
        voiceRecorder?.cancel()
        voicePermissionTask?.cancel()
        photoImportTask?.cancel()
        voicePlaybackTask?.cancel()
    }

    /// Wave 0 offline rehydration - runs BEFORE the network fetch:
    ///   • messages rehydrate from the GRDB cache (relaunch works offline)
    ///   • the composer seeds from the local draft, falling back to myDraft
    private func seedLocalState(conversation: WireConversationSummary, session: PulseSession) {
        if let store = session.store {
            let cached = (try? store.messages(conversationId: conversationId)) ?? []
            if !cached.isEmpty {
                messages = riverRows(from: cached)
                let cursor = PulseHistoryCursor.oldest(cached)
                historyWindow.record(cursor: cursor, hasMore: cursor != nil) // cache alone cannot prove exhaustion
                phase = .loaded
            }
            draft = draftJournal?.snapshot ?? store.draftSnapshot(conversationId: conversationId) ?? (conversation.myDraft ?? "")
        } else {
            draft = conversation.myDraft ?? ""
        }
    }

    func observe(session: PulseSession) {
        self.session = session
        cancellables.removeAll()
        if let store = session.store {
            let epoch = session.sessionGeneration
            store.observeThreadReplyCounts(conversationId: conversationId)
                .receive(on: DispatchQueue.main)
                .removeDuplicates()
                .sink(receiveCompletion: { [weak self] completion in
                    guard let self, epoch == session.sessionGeneration else { return }
                    if case .failure = completion { self.errorText = self.errorText ?? "Couldn't update cached reply counts." }
                }, receiveValue: { [weak self] snapshot in
                    guard let self, epoch == session.sessionGeneration else { return }
                    if let deadline = snapshot.nextExpiry, deadline <= Date() {
                        self.seedReplyCounts(store: store) // delivery to the main queue may cross an expiry
                    } else {
                        self.replyCounts = snapshot.counts
                        self.nextReplyCountExpiry = snapshot.nextExpiry
                    }
                })
                .store(in: &cancellables)
        }
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
                        // Counts come from storage, so duplicate relay deliveries
                        // and later deletion/expiry cannot inflate the reply chip.
                        if message.senderId == session.viewer?.id { return }
                    }
                    upsert(message, countsAsArrival: true)
                    // REM-B F-MS-23 - effect-flagged rows burst full-screen.
                    fireIncomingEffectIfAny(message, viewerId: session.viewer?.id, session: session)
                    if message.senderId != session.viewer?.id {
                        Task { await session.markReadIfVisible(conversationId: self.conversationId) }
                    }
                case .messageDeleted(let convId, let raw):
                    guard convId == self.conversationId else { return }
                    if let message = PulseSession.decodeMessage(from: raw) {
                        let tombstone = message.deletedCopy()
                        pins.removeAll { $0.id == message.id }
                        searchResults.removeAll { $0.id == message.id }
                        upsert(tombstone)
                        try? session.store?.upsert(messages: [tombstone])
                    }
                case .messageReact(let convId, let raw):
                    guard convId == self.conversationId,
                          let message = PulseSession.decodeMessage(from: raw) else { return }
                    upsert(message)
                    try? session.store?.upsert(messages: [message])
                case .messageRead(let convId, let userId, let lastReadAt, let ids, _, let all):
                    guard convId == self.conversationId else { return }
                    loadTopics(session: session)
                    for id in ids {
                        if let message = try? session.store?.message(id: id) { upsert(message) }
                    }
                    if all { Task { await self.refresh(session: session) } }
                    // Missing or reordered receipts cannot invent/regress Seen.
                    guard memberWatermarks[userId] != nil,
                          let at = lastReadAt.flatMap({ PulseFormat.date($0) }), at.timeIntervalSince1970 > 0 else { return }
                    receiptRevision += 1
                    memberWatermarks[userId] = max(memberWatermarks[userId] ?? .distantPast, at)
                    partnerLastReadAt = max(partnerLastReadAt ?? .distantPast, at)
                case .typing(let convId, let userId, let userName, let isTyping):
                    guard convId == self.conversationId, userId != session.viewer?.id else { return }
                    var bucket = typers.filter { $0.userId != userId }
                    if isTyping {
                        bucket.append(Typer(userId: userId, userName: userName, expiresAt: Date().addingTimeInterval(4)))
                    }
                    typers = bucket
                case .messageEnvelope(_, let convId, let raw):
                    // Wave 2 - message:viewed / poll:voted / link:preview carry
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

        session.$connected.combineLatest(session.$isForeground)
            .removeDuplicates { $0.0 == $1.0 && $0.1 == $1.1 }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _, foreground in
                guard let self else { return }
                if foreground && session.activeRoomId == self.conversationId {
                    self.requestRefresh(session: session)
                } else {
                    self.refreshTask?.cancel()
                }
            }
            .store(in: &cancellables)

        // Wave 2 - realtimeRefreshTick bumps on every background mutation
        // (viewed/poll/link envelopes, outbox deliveries). Recompute the
        // active topic view so incoming topic-tagged rows land instantly
        // (the native improvement over web's 3.5 s cache poll, spec §1 row 9).
        session.$realtimeRefreshTick
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                refilterInPlace()
                captureTopicAnchor(activeTopicId)
            setActiveTopic(TopicHeal.healed(activeTopicId, topics: topics), session: session, feedback: false)
            }
            .store(in: &cancellables)

        // Wave 0 - the outbox engine swaps the queued placeholder for the real
        // row (web sendMessage.onSuccess dedupe parity), drops it on 4xx.
        session.outboxEvents
            .receive(on: DispatchQueue.main)
            .sink { [weak self] event in
                self?.handleOutboxEvent(event)
            }
            .store(in: &cancellables)

        session.messageVisibilityClock
            .sink { [weak self] now in
                guard let self, session.sessionGeneration == self.readSessionGeneration else { return }
                self.refilterInPlace()
                let projectedTopics = self.topics.map { $0.removingExpiredUnread(at: now) }
                if projectedTopics != self.topics { self.topics = projectedTopics }
                if let deadline = self.nextReplyCountExpiry, deadline <= now, let store = session.store {
                    self.seedReplyCounts(store: store)
                }
            }
            .store(in: &cancellables)

        // Typer expiry sweep - keeps the dots honest without server stop events.
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

    private var refreshTask: Task<Void, Never>?
    private var refreshGeneration = UUID()
    private var historyTask: Task<Void, Never>?
    private var riverReloadTask: Task<Void, Never>?
    private var historyGeneration = UUID()
    private var historyCursor: PulseHistoryCursor? { historyWindow.cursor }

    private func cancelHistory() {
        historyWindow.invalidateSeed()
        historyGeneration = UUID()
        historyTask?.cancel()
        historyTask = nil
        loadingOlder = false
    }

    func requestRefresh(session: PulseSession) {
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            guard let self else { return }
            await self.refresh(session: session)
        }
    }

    func refresh(session: PulseSession) async {
        guard session.ready, session.isForeground else { return }
        let boundaryTicket = historyWindow.revision
        let historyTopic = activeTopicId
        let arrivalScope = missedScopeRevision
        let epoch = session.sessionGeneration
        let ticket = UUID()
        refreshGeneration = ticket
        let client = session.api
        let store = session.store
        if messages.isEmpty, let store {
            messages = riverRows(from: (try? store.messages(conversationId: conversationId)) ?? [])
        }
        if let store { seedReplyCounts(store: store) }
        let catchUpBoundary = PulseMissedMessages.latestBoundary(messages)
        let cachedRows = (try? store?.messages(conversationId: conversationId)) ?? messages
        let real = cachedRows.filter { !$0.id.hasPrefix("local_") }
        let from = real.min {
            (PulseFormat.date($0.createdAt) ?? .distantPast) < (PulseFormat.date($1.createdAt) ?? .distantPast)
        }?.createdAt
        phase = messages.isEmpty ? .loading : phase
        var cursor: String?
        var seenCursors = Set<String>()
        do {
            repeat {
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch, refreshGeneration == ticket,
                      session.isForeground else { return }
                // Revisit the cached range to heal edits/deletions as well as
                // drain arbitrarily many new pages after a disconnection.
                let watermark = session.messageRevision
                let page = try await client.messages(conversationId: conversationId,
                    syncFrom: cursor == nil ? from : nil, syncCursor: cursor)
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch, refreshGeneration == ticket else { return }
                if from != nil && page.syncVersion != 1 {
                    throw PulseAPIClient.Failure(kind: .server, message: "Gateway needs the paged message-sync update")
                }
                let eligible = page.messages.filter { session.acceptsSyncMessage($0.id, startedAt: watermark) }
                let fresh = try store?.mergeMessages(eligible) ?? eligible
                if from != nil, let catchUpBoundary, arrivalScope == missedScopeRevision, historyTopic == activeTopicId {
                    missedMessages.receive(fresh, roomId: conversationId, viewerId: session.viewer?.id,
                        topicId: activeTopicId, after: catchUpBoundary)
                }
                messages = PulseTimelineMerge.merge(messages, incoming: fresh, topicId: activeTopicId)
                noteRiverChanged()
                if from == nil {
                    // A general refresh is not a topic history seed; a newer page or
                    // topic change owns its cursor even if this request started first.
                    if historyTopic == nil && activeTopicId == nil {
                        historyWindow.seed(cursor: PulseHistoryCursor.oldest(page.messages),
                            hasMore: page.hasMore, ifUnchanged: boundaryTicket)
                    }
                    break
                }
                if !page.hasMore { break }
                guard let next = page.nextSyncCursor, seenCursors.insert(next).inserted else {
                    throw PulseAPIClient.Failure(kind: .server, message: "Message sync returned a missing or repeated cursor")
                }
                cursor = next
            } while true
            phase = .loaded
            errorText = nil
            await session.markReadIfVisible(conversationId: conversationId)
            resolveInitialJump()
        } catch is CancellationError {
            // Navigation/background cancellation is not a failed room.
        } catch {
            guard session.sessionGeneration == epoch, refreshGeneration == ticket else { return }
            phase = .failed(RoomViewModel.describe(error))
            errorText = RoomViewModel.describe(error)
        }
    }

    /// The main river EXCLUDES thread replies (web parity) - they live in
    /// ThreadView and surface as "N replies" chips on the parent bubble.
    /// Wave 2 topics: activeTopicId == nil → General = the WHOLE room;
    /// otherwise only rows filed under that topic (spec §1 row 9).
    /// REM-B F-MS-19 - rows whose TTL already elapsed never render.
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

    /// Live topic-view hygiene - drops rows that stopped matching the active
    /// filter (e.g. an envelope re-mapped a row's topic). No-op on General.
    private func refilterInPlace() {
        let current = Dictionary(messages.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        let nextPins = pins.map { current[$0.id] ?? $0 }.filter {
            $0.pinnedAt != nil && PulseRemediationLogic.isVisiblePreview($0)
        }
        if nextPins != pins { pins = nextPins }
        let nextSearch = searchResults.map { current[$0.id] ?? $0 }.filter {
            PulseRemediationLogic.isVisiblePreview($0) &&
                $0.content.localizedCaseInsensitiveContains(activeSearchQuery)
        }

        if nextSearch != searchResults { searchResults = nextSearch }
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
        if let snapshot = try? store.threadReplyCountSnapshot(conversationId: conversationId) {
            replyCounts = snapshot.counts
            nextReplyCountExpiry = snapshot.nextExpiry
        }
    }

    /// Wave 1 pagination - older history page (`before` cursor), 40 rows,
    /// merged id-dedupe ascending. The view triggers from the oldest row.
    func loadOlder(session: PulseSession) {
        guard session.ready, hasMore, !loadingOlder,
              let initial = historyCursor ?? PulseHistoryCursor.oldest(messages) else { return }
        historyWindow.invalidateSeed()
        let epoch = session.sessionGeneration, ticket = historyGeneration
        let client = session.api, store = session.store
        let topic = activeTopicId
        loadingOlder = true
        historyTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.historyGeneration == ticket { self.loadingOlder = false } }
            var cursor = initial
            do {
                // Skip pages containing only hidden replies. A manual header
                // remains available if a long sparse range needs another batch.
                for _ in 0..<5 {
                    try Task.checkCancellation()
                    let watermark = session.messageRevision
                    let page = try await client.messages(conversationId: self.conversationId,
                        limit: 40, before: cursor.createdAt, query: nil, topicId: topic, beforeId: cursor.id)
                    try Task.checkCancellation()
                    guard session.sessionGeneration == epoch, self.historyGeneration == ticket,
                          self.activeTopicId == topic else { return }
                    guard let next = PulseHistoryCursor.oldest(page.messages) else {
                        self.historyWindow.record(cursor: self.historyCursor, hasMore: false)
                        return
                    }
                    guard next.precedes(cursor) else {
                        self.errorText = "History returned a repeated page. Try loading earlier messages again."
                        return
                    }
                    let eligible = page.messages.filter { session.acceptsSyncMessage($0.id, startedAt: watermark) }
                    let fresh = try store?.mergeMessages(eligible) ?? eligible
                    let previousIDs = Set(self.messages.map(\.id))
                    self.messages = PulseTimelineMerge.merge(self.messages, incoming: fresh, topicId: topic)
                    self.historyWindow.record(cursor: next, hasMore: page.hasMore)
                    cursor = next
                    if !page.hasMore || self.messages.contains(where: { !previousIDs.contains($0.id) }) { return }
                }
            } catch is CancellationError {
                // Cancellation is not a history error.
            } catch {
                guard self.historyGeneration == ticket, session.sessionGeneration == epoch else { return }
                self.errorText = "Couldn't load earlier messages. Your current history is kept."
            }
        }
    }

    // ── jump + flash (search hits, quote taps, pinned banner) ──

    func replyCount(for message: WireChatMessage) -> Int {
        replyCounts[message.id] ?? 0
    }

    func jumpTo(_ messageId: String) {
        guard let session, !messageId.isEmpty else { return }
        cancelHistory() // Even an in-window jump supersedes an older history request.
        jumpThreadRoot = nil
        let ticket = historyGeneration, epoch = session.sessionGeneration
        historyTask = Task {
            if activeTopicId != nil {
                riverReloadTask?.cancel()
                activeTopicId = nil
                resetMissedScope()
                historyWindow.record(cursor: nil, hasMore: false)
                await reloadRiver(session: session)
            }
            guard !Task.isCancelled, historyGeneration == ticket, session.sessionGeneration == epoch else { return }
            if let target = (try? session.store?.message(id: messageId)) ?? messages.first(where: { $0.id == messageId }) {
                await resolveJump(target, session: session)
            } else {
                await expandForJump(messageId, session: session, rounds: 0)
            }
        }
    }

    private func resolveJump(_ target: WireChatMessage, session: PulseSession) async {
        guard target.conversationId == conversationId, target.deletedAt == nil,
              PulseRemediationLogic.isAlive(target.expiresAt) else {
            session.toasts.show("This message is no longer available")
            return
        }
        guard let rootId = target.parentId else {
            messages = PulseTimelineMerge.merge(messages, incoming: [target], topicId: nil)
            scrollAndFlash(target.id)
            return
        }
        let ticket = historyGeneration, epoch = session.sessionGeneration, revision = session.messageRevision
        do {
            let page = try await session.validatedThread(rootId: rootId, roomId: conversationId)
            guard !Task.isCancelled, ticket == historyGeneration, epoch == session.sessionGeneration else { return }
            let fresh = ([page.parent] + page.replies).filter { session.acceptsSyncMessage($0.id, startedAt: revision) }
            try session.store?.upsert(messages: fresh)
            let root = fresh.first(where: { $0.id == rootId }) ?? (try? session.store?.message(id: rootId))
            guard let root, root.conversationId == conversationId, root.parentId == nil,
                  root.deletedAt == nil, PulseRemediationLogic.isAlive(root.expiresAt) else {
                session.toasts.show("This thread is no longer available")
                return
            }
            let currentReply = fresh.first(where: { $0.id == target.id }) ?? (try? session.store?.message(id: target.id))
            guard page.replies.contains(where: { $0.id == target.id }), let currentReply,
                  PulseThreadNavigation.target(in: [currentReply], roomId: conversationId, rootId: rootId, targetId: target.id) != nil else {
                session.toasts.show("This reply is no longer available")
                return
            }
            jumpThreadReplyId = target.id
            jumpThreadRoot = root
        } catch {
            guard !Task.isCancelled, ticket == historyGeneration, epoch == session.sessionGeneration else { return }
            if PulseThreadNavigation.mayUseOfflineCache(after: error), !session.threadAccessDenied(rootId: rootId),
               let saved = try? session.store?.cachedThreadTarget(roomId: conversationId, rootId: rootId, targetId: target.id) {
                jumpThreadReplyId = saved.reply.id
                jumpThreadRoot = saved.parent
                return
            }
            session.toasts.show("Couldn't open this message's thread. Try again.")
        }
    }

    private func resolveInitialJump() {
        guard let target = initialJumpMessageId else { return }
        initialJumpMessageId = nil
        jumpTo(target)
    }

    /// Bounded expansion for out-of-window targets (web ≤14 rounds parity).
    private func expandForJump(_ messageId: String, session: PulseSession, rounds: Int) async {
        guard !Task.isCancelled, session.ready else { return }
        let epoch = session.sessionGeneration, ticket = historyGeneration
        let client = session.api, store = session.store
        let topic = activeTopicId
        let watermark = session.messageRevision
        guard rounds < 14 else {
            session.toasts.show("Couldn't reach that message in this chat's history")
            return
        }
        guard historyWindow.canExpandForJump else {
            session.toasts.show("Couldn't reach that message in this chat's history")
            return
        }
        let oldest = historyCursor // nil intentionally requests a latest page for a cold jump
        historyWindow.invalidateSeed()
        loadingOlder = true
        defer { if historyGeneration == ticket { loadingOlder = false } }
        do {
            let page = try await client.messages(conversationId: conversationId, limit: 40,
                before: oldest?.createdAt, query: nil, topicId: topic, beforeId: oldest?.id)
            try Task.checkCancellation()
            guard session.sessionGeneration == epoch, historyGeneration == ticket,
                  activeTopicId == topic else { return }
            let next = PulseHistoryCursor.oldest(page.messages)
            if let next, let oldest {
                guard next.precedes(oldest) else {
                    session.toasts.show("History returned a repeated page")
                    return
                }
            }
            let eligible = page.messages.filter { session.acceptsSyncMessage($0.id, startedAt: watermark) }
            let fresh = try store?.mergeMessages(eligible) ?? eligible
            messages = PulseTimelineMerge.merge(messages, incoming: fresh, topicId: topic)
            historyWindow.record(cursor: next ?? oldest, hasMore: page.hasMore && next != nil)
            if page.messages.contains(where: { $0.id == messageId }) {
                if let target = fresh.first(where: { $0.id == messageId }) ?? (try? store?.message(id: messageId)) {
                    await resolveJump(target, session: session)
                } else { session.toasts.show("This message is no longer available") }
            } else if page.messages.isEmpty {
                session.toasts.show("Couldn't reach that message in this chat's history")
            } else {
                await expandForJump(messageId, session: session, rounds: rounds + 1)
            }
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch,
                  historyGeneration == ticket else { return }
            session.toasts.show("Couldn't reach that message in this chat's history")
        }
    }

    private func scrollAndFlash(_ messageId: String) {
        jumpTargetId = messageId
        jumpRequestRevision &+= 1
        flashMessageId = messageId
        flashClearTask?.cancel()
        flashClearTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            guard let self, !Task.isCancelled else { return }
            self.flashMessageId = nil
            self.jumpTargetId = nil
        }
    }

    // ── jump-pill arrivals (separate from server unread totals) ──

    func noteRiverChanged() {
        updateReadBoundary()
        guard let session, session.sessionGeneration == readSessionGeneration else { return }
        // An append can arrive before geometry updates. The old tail flag alone
        // must not clear an arrival whose new row has never entered the viewport.
        let latest = messages.last { !$0.id.hasPrefix("local_") && $0.deletedAt == nil && PulseRemediationLogic.isAlive($0.expiresAt) }
        let readingTail = isTailVisible && !readSurfaceObscured && session.isForeground &&
            session.activeRoomId == conversationId && latest.map { visibleReadIDs.contains($0.id) } == true
        missedMessages.reconcile(messages, roomId: conversationId, viewerId: session.viewer?.id,
            topicId: activeTopicId, readingTail: readingTail)
        let count = missedMessages.count
        if count > missedCount { PulseHaptics.tap() }
        if missedCount != count { missedCount = count }
        if showJumpPill != (count > 0) { showJumpPill = count > 0 }
    }

    private func resetMissedScope() {
        missedScopeRevision &+= 1
        missedMessages.reset()
        missedCount = 0
        showJumpPill = false
        noteRiverChanged() // seed already-visible IDs before a suspended topic reload
    }

    /// R30-c - the index the unread divider row sits BEFORE (web
    /// buildItems parity). Nil = anchor absent or nothing qualifies.
    func unreadDividerRow(viewerId: String?) -> Int? {
        let anchor = activeTopicId.flatMap { topicUnreadAnchors[$0] } ?? (activeTopicId == nil ? firstUnreadMessageId : nil)
        guard let anchor else { return nil }
        return messages.firstIndex { $0.id == anchor }
    }

    // ── R2-B R34-b - AI recap ──

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

    // ── R2-B R38/R42 - screen security (veil) ──

    /// R38/R47 - view surfaces hand back a fresh detail after a successful
    /// PATCH (privacy sheet, DM-block toggle): the river re-veils without a
    /// refetch. Same adoption rules as loadPrivacyState.
    func applyFreshDetail(_ detail: WireConversationSummary) {
        guard detail.id == conversationId else { return }
        privacyReads.invalidateReads()
        adoptReadMembers(detail)
        roomScreenPrivacy = detail.screenPrivacy
        dmBlocked = !detail.isGroup && detail.dmBlockedNow
    }

    private func adoptReadMembers(_ detail: WireConversationSummary) {
        readerMemberships = detail.members.reduce(into: [:]) { result, member in result[member.id] = member.readMembershipId }
        let previous = memberWatermarks
        memberWatermarks = detail.members.reduce(into: [String: Date]()) { stamps, member in
            guard member.id != session?.viewer?.id, let stamp = member.lastReadAt,
                  let at = PulseFormat.date(stamp), at.timeIntervalSince1970 > 0 else { return }
            // Room-info responses can predate a live Seen event. Preserve its
            // newer positive watermark, but do not retain hidden/absent readers:
            // the guards above deliberately remove revoked/missing timestamps.
            stamps[member.id] = max(previous[member.id] ?? .distantPast, at)
        }
        partnerLastReadAt = memberWatermarks.values.max()
    }

    private let privacyReads = PulseRoomOperations()
    struct PrivacyReadTicket {
        let snapshot: PulseRoomOperations.ReadTicket
        let readers: Int
    }
    func beginPrivacyRead() -> PrivacyReadTicket? {
        privacyReads.beginRead().map { PrivacyReadTicket(snapshot: $0, readers: receiptRevision) }
    }
    @discardableResult
    func applyPrivacyRead(_ ticket: PrivacyReadTicket, detail: WireConversationSummary) -> Bool {
        guard detail.id == conversationId, privacyReads.accepts(ticket.snapshot) else { return false }
        if receiptRevision == ticket.readers { adoptReadMembers(detail) }
        roomScreenPrivacy = detail.screenPrivacy
        dmBlocked = !detail.isGroup && detail.dmBlockedNow
        return true
    }

    /// One quiet detail GET on room open (web detail-query parity): refresh
    /// the room-wide flag + adopt the server's per-viewer flag into the
    /// prefs map. Local state stays authoritative until this lands.
    func loadPrivacyState(session: PulseSession) async {
        let viewerId = session.viewer?.id ?? ""
        let epoch = session.sessionGeneration
        guard !viewerId.isEmpty, epoch == readSessionGeneration, let ticket = beginPrivacyRead() else { return }
        let privacyRead = session.prefs?.screenPrivacyRead(conversationId: conversationId)
        let client = session.api
        guard let detail = try? await client.conversationDetail(id: conversationId, userId: viewerId),
              !Task.isCancelled, session.sessionGeneration == epoch,
              applyPrivacyRead(ticket, detail: detail) else { return }
        if let mine = detail.myScreenPrivacy, let privacyRead {
            session.prefs?.adoptServerScreenPrivacy(privacyRead, on: mine)
        }
    }

    // ── room search (server q= + local window filter) ───────

    private var searchTask: Task<Void, Never>?
    private var activeSearchQuery = ""

    func searchInRoom(_ query: String, session: PulseSession) {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        activeSearchQuery = trimmed
        let epoch = session.sessionGeneration
        guard trimmed.count >= 2 else {
            searchResults = []
            searching = false
            return
        }
        searching = true
        searchTask = Task { [weak self] in
            // 220 ms debounce - the room-search rhythm (web room-search-page;
            // the GLOBAL chats search keeps its own 250 ms).
            try? await Task.sleep(nanoseconds: 220_000_000)
            guard let self, !Task.isCancelled else { return }
            let watermark = session.messageRevision
            var hits: [WireChatMessage] = []
            if let page = try? await session.api.messages(conversationId: self.conversationId, limit: 100, before: nil, query: trimmed) {
                hits = page.messages
            }
            if Task.isCancelled || epoch != session.sessionGeneration { return }
            // Refresh existing window rows before it is used to reconcile
            // detached search selections on the visibility clock.
            let accepted = hits.filter { session.acceptsSyncMessage($0.id, startedAt: watermark) }
            try? session.store?.upsert(messages: accepted)
            let byId = Dictionary(accepted.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
            self.messages = self.messages.map { byId[$0.id] ?? $0 }
            // Merge the live loaded window (covers rows newer than the
            // server's scan window and the offline case).
            let local = self.messages.filter { $0.content.lowercased().contains(trimmed.lowercased()) }
            var seenIds = Set<String>()
            var merged: [WireChatMessage] = []
            for candidate in hits + local {
                let row: WireChatMessage?
                if session.acceptsSyncMessage(candidate.id, startedAt: watermark) { row = candidate }
                else { row = try? session.store?.message(id: candidate.id) }
                guard let row, row.deletedAt == nil, PulseRemediationLogic.isAlive(row.expiresAt),
                      row.content.localizedCaseInsensitiveContains(trimmed) else { continue }
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

    func upsert(_ message: WireChatMessage, countsAsArrival: Bool = false) {
        if countsAsArrival, let session, session.sessionGeneration == readSessionGeneration {
            missedMessages.receive([message], roomId: conversationId, viewerId: session.viewer?.id, topicId: activeTopicId)
        }
        let cached = try? session?.store?.message(id: message.id, now: .distantPast)
        let canonical = PulseMessageMerge.merge(cached, incoming: message)
        let river = PulseTimelineMerge.merge(messages, incoming: [canonical], topicId: activeTopicId)
        func project(_ rows: [WireChatMessage]) -> [WireChatMessage] {
            let local = PulseQuotePrivacy.project(rows, known: [canonical])
            return (try? session?.store?.projectCachedQuotes(local)) ?? local
        }
        messages = project(river)
        pins = project(pins)
        searchResults = project(searchResults)
    }

    func draftChanged(session: PulseSession) {
        guard editingTarget == nil else { return }
        // Wave 0 - 600ms debounced draft persistence (web pulse-drafts parity).
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

    private func journalDraft(_ text: String) {
        guard let draftJournal else { return }
        let revision = DispatchTime.now().uptimeNanoseconds
        // This finite write intentionally survives view disposal. The actor owns
        // the original store and never dereferences a replacement session.
        let write = draftJournal.submit(text, revision: revision)
        Task { [weak self] in
            do { try await write.value }
            catch { self?.errorText = "Draft could not be saved: check device storage" }
        }
    }

    private func scheduleDraftSave(session: PulseSession) {
        let text = draft
        draftSaveTask?.cancel()
        let client = session.api
        let epoch = session.sessionGeneration
        let room = conversationId
        guard let viewer = session.viewer else { return }
        draftSaveTask = Task {
            do { try await Task.sleep(nanoseconds: 600_000_000) }
            catch { return }
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
            try? await client.setDraft(conversationId: room, userId: viewer.id,
                draft: text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : text)
        }
    }

    func send(session: PulseSession) {
        guard staged == nil, editingTarget == nil else { return }
        guard let viewer = session.viewer, let store = session.store, session.ready else {
            errorText = "The account store is not ready. Your draft is kept."
            return
        }
        do {
            let message = try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: conversationId,
                content: draft, replyTo: replyTarget, topicId: activeTopicId, anon: isGroupRoom && anonOn, draftJournal: draftJournal)
            upsert(message)
            errorText = nil
            // Durable intent owns the quote/mask; a late ACK must not mutate
            // a newer draft or an independently rearmed mask.
            draft = ""
            replyTarget = nil
            if isGroupRoom { anonOn = false }
            typingStopTask?.cancel(); typingStopTask = nil
            scheduleDraftSave(session: session)
            session.emitTyping(conversationId: conversationId, recipients: [], isTyping: false)
            session.outbox?.reloadPending()
            session.flushOutbox()
        } catch { errorText = "Message was not queued. Your draft is kept. " + Self.describe(error) }
    }

    // ── REM-B F-MS-22 - slash commands ─────────────────────

    /// Interprets a leading '/' command from the draft. Returns TRUE when the
    /// draft was consumed (sheet opened / error shown / effect sent) - FALSE
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
            session.toasts.show("Try /me /shrug /roll /poll /schedule /sticker /effects confetti - type '/' for the full list")
            return true
        case .sheet(let name):
            pendingSlashSheet = name
            draft = ""
            return true
        case .topic(let name):
            createTopic(name: name, emoji: PulseTopicIconId.chat.rawValue, session: session)
            draft = ""
            return true
        case .remind:
            session.toasts.show("Reminders ride \"Remind me…\" on any message")
            return true
        case .recap:
            // R34-b - /recap rides the same requestRecap as the header entry.
            draft = ""
            requestRecap(session: session)
            return true
        case .effect(let name, let content):
            // R3-A item 8 - the web send path can never fire an effect with
            // an empty body (its send button is disabled on empty input), so
            // the machine mirrors that honestly instead of POSTing "".
            guard !content.isEmpty else {
                session.toasts.show("Type the message first - the effect rides your send")
                return true
            }
            sendWithEffect(effect: name, content: content, session: session)
            return true
        }
    }

    /// R3-A item 8 - attach-menu Effects entry: the CURRENT draft rides the
    /// EXISTING sendWithEffect engine (optimistic temp → payload {effect} →
    /// particle burst). An empty draft surfaces the honest toast instead of
    /// a silent no-op; reply/edit/staged state blocks like the plain send.
    func sendEffect(effect: String, session: PulseSession) {
        let body = draft.trimmingCharacters(in: .whitespaces)
        guard !body.isEmpty else {
            session.toasts.show("Type the message first - the effect rides your send")
            return
        }
        guard editingTarget == nil, staged == nil else { return }
        sendWithEffect(effect: effect, content: body, session: session)
    }

    // ── R3-A item 11 - Chanty-style message → task conversion ──

    /// Re-entry guard while a conversion POST is in flight (the context menu
    /// has no spinner; a second tap inside the window is ignored).
    private var convertingTaskIds: Set<String> = []

    /// Web convertToTask parity (chat-room.tsx:2100-2122): the message itself
    /// becomes a board card via POST /api/conversations/{id}/kanban with
    /// { userId, messageId } - the server derives the title (first 80 chars)
    /// and keeps sourceMessageId provenance. Honest toast on BOTH outcomes;
    /// the success burst mirrors web fireParticles({kind:'burst',count:40}).
    func convertMessageToTask(_ message: WireChatMessage, session: PulseSession) {
        PulseHaptics.tap()
        guard session.ready, let owner = session.viewer?.id, message.conversationId == conversationId,
              !convertingTaskIds.contains(message.id) else { return }
        let api = session.api, epoch = session.sessionGeneration
        convertingTaskIds.insert(message.id)
        Task { [weak self] in
            guard let self else { return }
            defer { self.convertingTaskIds.remove(message.id) }
            do {
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch, session.viewer?.id == owner else { return }
                let card = try await api.createKanbanCard(
                    conversationId: conversationId,
                    title: nil,
                    column: nil,
                    assigneeId: nil,
                    messageId: message.id,
                )
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch, session.viewer?.id == owner else { return }
                session.toasts.show("Task \"\(card.title ?? "")\" created from message")
                session.particles.fire(kind: .burst, count: 40)
            } catch is CancellationError { }
            catch {
                if session.sessionGeneration == epoch, session.viewer?.id == owner { session.toasts.show(Self.describe(error)) }
            }
        }
    }

    /// F-MS-22/23 - effect-flagged send: payload { effect } rides kind text;
    /// the sender's client fires the burst locally (web fireParticles parity).
    private func sendWithEffect(effect: String, content: String, session: PulseSession) {
        guard let viewer = session.viewer, let store = session.store, session.ready,
              editingTarget == nil, staged == nil else {
            errorText = "Message was not queued. Your draft is kept until the account store is ready."
            return
        }
        do {
            let message = try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: conversationId,
                content: content, effect: effect, replyTo: replyTarget, topicId: activeTopicId, anon: isGroupRoom && anonOn, draftJournal: draftJournal)
            upsert(message)
            draft = ""; replyTarget = nil
            if isGroupRoom { anonOn = false }
            typingStopTask?.cancel(); typingStopTask = nil
            scheduleDraftSave(session: session)
            session.emitTyping(conversationId: conversationId, recipients: [], isTyping: false)
            session.outbox?.reloadPending()
            session.flushOutbox()
        } catch {
            errorText = "Message was not queued. Your draft is kept; select the effect again. " + Self.describe(error)
        }
    }

    // ── REM-B F-MS-24 - stickers ───────────────────────────

    /// kind "sticker" + payload { emoji, pack } (web sticker-picker parity;
    /// the bubble renders the emoji big). Uses the shared durable dispatcher.
    func sendSticker(emoji: String, pack: String, session: PulseSession) {
        enqueueRich(kind: "sticker", content: emoji, payload: ["emoji": emoji, "pack": pack], session: session)
    }

    private func enqueueRich(kind: String, content: String, payload: [String: Any], session: PulseSession) {
        guard let viewer = session.viewer, let store = session.store, session.ready else {
            session.toasts.show("Local storage is not ready. Nothing was queued.")
            return
        }
        do {
            let message = try PulseTextDelivery.enqueueRich(store: store, viewer: viewer, conversationId: conversationId,
                kind: kind, content: content, payload: payload, replyTo: replyTarget, topicId: activeTopicId, anon: isGroupRoom && anonOn)
            upsert(message)
            replyTarget = nil
            if isGroupRoom { anonOn = false }
            session.outbox?.reloadPending()
            session.flushOutbox()
        } catch { session.toasts.show("Message was not queued. " + Self.describe(error)) }
    }

    // ── R1-W2B F-MD-06 - translate ───────────────────────

    /// POST /api/messages/{id}/translate (text rows only) - the fresh row
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

    // ── R1-W2B F-MD-07 - location send ───────────────────

    /// kind "location" + payload { lat, lng, label } (web sendLocation parity,
    /// chat-room.tsx:3120-3138; content rides empty like the web). Persisted
    /// before delivery, with the same ordering/recovery as text.
    func sendLocation(lat: Double, lng: Double, label: String, session: PulseSession) {
        enqueueRich(kind: "location", content: "", payload: ["lat": lat, "lng": lng, "label": label], session: session)
    }

    // ── R1-W2B F-MS-29 - quick phrases ───────────────────

    private let quickPhraseReads = PulseRoomOperations()
    private var quickPhraseTask: Task<Void, Never>?

    func loadQuickPhrases(session: PulseSession) {
        quickPhraseTask?.cancel()
        let epoch = session.sessionGeneration
        guard session.viewer != nil, epoch == readSessionGeneration else {
            quickPhraseReads.stop()
            quickPhrases = []
            return
        }
        guard let ticket = quickPhraseReads.beginRead() else { return }
        let client = session.api
        quickPhraseTask = Task { [weak self] in
            do {
                let rows = try await client.quickPhrases()
                guard let self, session.sessionGeneration == epoch, self.quickPhraseReads.accepts(ticket) else { return }
                self.quickPhrases = rows
            } catch {
                // Failed/cancelled refresh is not an empty private phrase list.
                // The manager sheet provides explicit retry/error feedback.
            }
        }
    }

    // ── REM-B F-MS-18 - schedule the composer draft ────────

    func scheduleDraft(for date: Date, session: PulseSession) async -> Bool {
        guard session.sessionGeneration == readSessionGeneration, !Task.isCancelled else { return false }
        guard !scheduling else { session.toasts.show("Scheduling is still in progress"); return false }
        let body = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else {
            session.toasts.show("Type the message first - scheduling sends the draft")
            return false
        }
        let iso = ScheduleSheet.isoFormatter.string(from: date)
        let revision = draftEditRevision
        let draftReceipt = draftJournal?.captureReceipt()
        let client = session.api
        let epoch = session.sessionGeneration
        scheduling = true; scheduledRevision = UUID()
        defer { if session.sessionGeneration == epoch { scheduling = false } }
        var accepted = false
        do {
            let item = try await client.scheduleMessage(conversationId: conversationId, content: body, scheduledAtIso: iso)
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return false }
            if draftEditRevision == revision, let draftReceipt, draftJournal?.matches(draftReceipt) == true {
                draft = ""
                scheduleDraftSave(session: session)
            }
            scheduledItems.removeAll { $0.id == item.id }
            scheduledItems.append(item)
            accepted = true
            try await client.acknowledgeScheduledCreation(item)
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return false }
            PulseHaptics.success()
            session.toasts.show("Scheduled - \(PulseFormat.dayLabel(iso)) \(PulseFormat.clockTime(iso))")
            return true
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch else { return false }
            session.toasts.show(accepted
                ? "Scheduled, but local confirmation could not be saved. Check Scheduled messages before retrying."
                : Self.describe(error))
            return accepted
        }
    }

    /// Wave 2 - fire-and-forget unfurl after OWN link-bearing send (spec §1
    /// row 8). A response from a retired session or older than a relay must
    /// not overwrite the current message cache.
    private func triggerUnfurl(for message: WireChatMessage, viewerId: String, session: PulseSession) {
        let api = session.api
        let store = session.store
        let epoch = session.sessionGeneration
        let revision = session.messageRevision
        Task { [weak self] in
            guard let row = try? await api.unfurl(messageId: message.id, userId: viewerId),
                  !Task.isCancelled, session.sessionGeneration == epoch,
                  session.acceptsSyncMessage(message.id, startedAt: revision) else { return }
            try? store?.upsert(messages: [row])
            self?.upsert(row)
        }
    }

    /// Only the acknowledged operation is retired. Identical sends are distinct.
    private func swapTemp(_ tempId: String, for message: WireChatMessage, session: PulseSession) {
        messages.removeAll { $0.id == tempId }
        upsert(message)
        let clientId = String(tempId.dropFirst("local_".count))
        do { try session.store?.resolveDelivery(clientId: clientId, message: message) }
        catch { self.errorText = "Delivered, but local storage could not be updated. Pending data was retained." }
    }

    private func handleOutboxEvent(_ event: PulseOutboxEvent) {
        switch event {
        case .delivered(let message, let conversationId, let clientId):
            guard conversationId == self.conversationId else { return }
            upsert(message)
            messages.removeAll { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }
            if message.parentId == nil, let session {
                loadTopics(session: session)
                if PulseRemediationLogic.effectOfPayload(message.payload) == nil {
                    session.particles.fire(kind: .burst, count: 22)
                }
                if let viewer = session.viewer, UnfurlTrigger.matches(message.content) {
                    triggerUnfurl(for: message, viewerId: viewer.id, session: session)
                }
            }
            if let session, let effect = PulseRemediationLogic.effectOfPayload(message.payload),
               let name = PulseRemediationLogic.particleKind(forEffect: effect) {
                let kind = Self.particleKind(named: name)
                session.particles.fire(kind: kind, count: Self.particleCount(for: kind), center: CGPoint(x: 0.5, y: 0.85))
            }
        case .dropped(let clientId, let conversationId):
            guard conversationId == self.conversationId else { return }
            messages.removeAll { $0.id == PulseOutboxEngine.tempMessageId(clientId: clientId) }
        }
    }

    func setReaction(_ message: WireChatMessage, emoji: String, present: Bool, session: PulseSession) async throws {
        guard session.sessionGeneration == readSessionGeneration, message.conversationId == conversationId else {
            throw CancellationError()
        }
        let epoch = session.sessionGeneration
        try await session.setReaction(message, emoji: emoji, present: present)
        guard !Task.isCancelled, session.sessionGeneration == epoch else { throw CancellationError() }
        // Re-read after the await; a socket event may have advanced the stored row.
        if let retained = try session.store?.message(id: message.id) { upsert(retained) }
        if present && emoji == PulseReactionId.heart.rawValue { session.particles.fire(kind: .hearts, count: 24) }
    }

    func react(_ message: WireChatMessage, emoji: String, session: PulseSession) {
        guard session.sessionGeneration == readSessionGeneration, let owner = session.viewer?.id else { return }
        let epoch = session.sessionGeneration
        let reaction = PulseReactionId.normalize(emoji)
        let present = !(message.reactions ?? []).contains { PulseReactionId.normalize($0.emoji) == reaction && $0.userIds.contains(owner) }
        PulseHaptics.tap()
        Task { [weak self] in
            guard let self, session.sessionGeneration == epoch else { return }
            do {
                try await setReaction(message, emoji: reaction.rawValue, present: present, session: session)
            } catch is CancellationError { return } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                errorText = Self.describe(error)
            }
        }
    }

    func beginReply(to message: WireChatMessage) {
        var preview = message
        if preview.viewOnce == true { preview.content = "View-once message" }
        replyTarget = preview
    }

    func cancelReply() {
        replyTarget = nil
    }

    func readLabel(_ message: WireChatMessage) -> String {
        let count = PulseRoomParityLogic.peerReadCount(watermarks: memberWatermarks,
            viewerId: session?.viewer?.id, createdAtIso: message.createdAt, memberships: readerMemberships, readByMembershipIds: message.readByMembershipIds ?? [])
        return isGroupRoom ? "Read by \(count)" : "Seen"
    }

    func isSeen(_ message: WireChatMessage) -> Bool {
        guard message.senderId == session?.viewer?.id,
              let last = messages.last(where: { $0.senderId == session?.viewer?.id }) else { return false }
        guard last.id == message.id else { return false }
        return PulseRoomParityLogic.peerReadCount(watermarks: memberWatermarks,
            viewerId: session?.viewer?.id, createdAtIso: message.createdAt, memberships: readerMemberships, readByMembershipIds: message.readByMembershipIds ?? []) > 0
    }

    // ── Wave 1 message actions ─────────────────────────────

    private var pinsGeneration = UUID()
    func loadPins(session: PulseSession) {
        guard let viewer = session.viewer else { return }
        let ticket = UUID(), epoch = session.sessionGeneration, watermark = session.messageRevision
        pinsGeneration = ticket
        Task { [weak self] in
            guard let self else { return }
            do {
                let fresh = try await session.api.pinnedMessages(conversationId: self.conversationId, userId: viewer.id)
                guard pinsGeneration == ticket, session.sessionGeneration == epoch else { return }
                let accepted = fresh.filter { session.acceptsSyncMessage($0.id, startedAt: watermark) }
                try session.store?.upsert(messages: accepted)
                let byId = Dictionary(accepted.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
                messages = messages.map { byId[$0.id] ?? $0 }
                pins = fresh.compactMap { row in
                    session.acceptsSyncMessage(row.id, startedAt: watermark) ? row : (try? session.store?.message(id: row.id))
                }.filter { $0.pinnedAt != nil && PulseRemediationLogic.isVisiblePreview($0) }
            } catch {
                guard pinsGeneration == ticket, session.sessionGeneration == epoch else { return }
                session.toasts.show("Couldn't refresh pinned messages")
            }
        }
    }

    func beginEdit(_ message: WireChatMessage) {
        editSaveTask?.cancel()
        editGeneration = UUID()
        savingEdit = false
        if editingTarget == nil {
            draftBeforeEdit = draft
            replyBeforeEdit = replyTarget
        }
        editingTarget = message
        replyTarget = nil
        draft = message.content
    }

    func cancelEdit() {
        editSaveTask?.cancel()
        editGeneration = UUID()
        savingEdit = false
        editingTarget = nil
        draft = draftBeforeEdit ?? ""
        replyTarget = replyBeforeEdit
        draftBeforeEdit = nil
        replyBeforeEdit = nil
    }

    func saveEdit(session: PulseSession) {
        guard let target = editingTarget, let viewer = session.viewer, !savingEdit else { return }
        let body = draft.trimmingCharacters(in: .whitespaces)
        guard !body.isEmpty else { return }
        let revision = draftEditRevision, ticket = editGeneration
        let epoch = session.sessionGeneration
        savingEdit = true
        editSaveTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.editGeneration == ticket { self.savingEdit = false } }
            do {
                guard session.sessionGeneration == epoch, self.editGeneration == ticket, session.viewer?.id == viewer.id else { return }
                let fresh = try await session.mutateMessage(target, action: .edit(body))
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch, self.editGeneration == ticket else { return }
                self.upsert(fresh)
                if self.draftEditRevision == revision && self.draftJournal?.isCurrentOwner == true {
                    self.cancelEdit() // restore the unsent composer, not an empty string
                    self.scheduleDraftSave(session: session)
                }
            } catch is CancellationError {
            } catch {
                guard self.editGeneration == ticket, session.sessionGeneration == epoch else { return }
                // Keep the edited text and target available for retry.
                self.errorText = Self.describe(error)
            }
        }
    }

    func delete(_ message: WireChatMessage, session: PulseSession) {
        let epoch = session.sessionGeneration
        Task { [weak self] in
            guard let self else { return }
            do {
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch else { return }
                let tombstone = try await session.mutateMessage(message, action: .delete)
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch else { return }
                guard tombstone.conversationId == message.conversationId else {
                    throw PulseAPIClient.Failure(kind: .validation, message: "Deletion returned another conversation.")
                }
                self.upsert(tombstone)
                PulseHaptics.tap()
            } catch is CancellationError {
            } catch {
                guard session.sessionGeneration == epoch else { return }
                self.errorText = Self.describe(error)
            }
        }
    }

    func togglePin(_ message: WireChatMessage, session: PulseSession) {
        guard session.viewer != nil else { return }
        let epoch = session.sessionGeneration
        PulseHaptics.tap()
        Task { [weak self] in
            guard let self else { return }
            do {
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch else { return }
                let fresh = try await session.mutateMessage(message, action: message.pinnedAt == nil ? .pin : .unpin)
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch else { return }
                self.upsert(fresh)
                self.loadPins(session: session)
                session.toasts.show(fresh.pinnedAt == nil ? "Unpinned" : "Pinned")
            } catch is CancellationError {
            } catch {
                guard session.sessionGeneration == epoch else { return }
                self.errorText = Self.describe(error)
            }
        }
    }

    func saveMessage(_ message: WireChatMessage, session: PulseSession) {
        let epoch = session.sessionGeneration
        PulseHaptics.tap()
        Task { [weak self] in
            guard let self else { return }
            do {
                try Task.checkCancellation()
                guard session.sessionGeneration == epoch else { return }
                try await session.setMessageSaved(message, saved: true)
                guard session.sessionGeneration == epoch else { return }
                session.toasts.show("Saved to your library")
            } catch is CancellationError {
            } catch {
                guard session.sessionGeneration == epoch else { return }
                self.errorText = Self.describe(error)
            }
        }
    }

    // ── Wave 1 media staging + upload (spec §1.1) ────────

    private var photoImportTask: Task<Void, Never>?
    private var photoImportGeneration = UUID()

    func acceptsAttachmentSession(_ candidate: PulseSession, conversationId: String) -> Bool {
        session === candidate && candidate.sessionGeneration == readSessionGeneration &&
            candidate.viewer != nil && self.conversationId == conversationId
    }

    private func cancelPhotoImport() {
        photoImportGeneration = UUID()
        photoImportTask?.cancel(); photoImportTask = nil
        uploading = false
    }

    func importPhoto(_ item: PhotosPickerItem, session: PulseSession) {
        guard acceptsAttachmentSession(session, conversationId: conversationId) else { return }
        cancelPhotoImport()
        guard !isRecording, staged?.kind != .audio else { errorText = "Finish or discard the voice note first."; return }
        let ticket = photoImportGeneration
        let epoch = session.sessionGeneration
        uploading = true
        photoImportTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if photoImportGeneration == ticket { uploading = false; photoImportTask = nil }
            }
            do {
                guard let photo = try await item.loadTransferable(type: PulsePickedPhoto.self) else { throw CocoaError(.fileReadCorruptFile) }
                guard !Task.isCancelled, photoImportGeneration == ticket, session.sessionGeneration == epoch else { return }
                stagePreparedImage(photo.jpeg)
            } catch {
                if !Task.isCancelled, photoImportGeneration == ticket, session.sessionGeneration == epoch {
                    errorText = "Couldn't read the selected photo. Any previous attachment is kept."
                }
            }
        }
    }

    func stageImage(_ data: Data) {
        guard let session, acceptsAttachmentSession(session, conversationId: conversationId) else { return }
        cancelPhotoImport()
        guard !isRecording, staged?.kind != .audio else { errorText = "Finish or discard the voice note first."; return }
        guard let jpeg = PulseMediaSupport.downscaledJPEGData(from: data) else {
            errorText = "Couldn't read that image"
            return
        }
        stagePreparedImage(jpeg)
    }

    func stageCameraImage(_ image: UIImage) {
        guard let session, acceptsAttachmentSession(session, conversationId: conversationId) else { return }
        cancelPhotoImport()
        guard !isRecording, staged?.kind != .audio else { errorText = "Finish or discard the voice note first."; return }
        guard let jpeg = PulseMediaSupport.downscaledJPEGData(from: image) else { errorText = "Couldn't read the camera photo"; return }
        stagePreparedImage(jpeg)
    }

    private func stagePreparedImage(_ jpeg: Data) {
        cancelPhotoImport()
        guard !isRecording, staged?.kind != .audio else { errorText = "Finish or discard the voice note first."; return }
        installPreparedAttachment(StagedMedia(
            kind: .image,
            dataUrl: PulseMediaSupport.dataUrl(mime: "image/jpeg", data: jpeg),
            fileName: nil,
            fileSize: jpeg.count,
            previewURL: nil,
            viewOnce: false,
            previewData: jpeg,
        ))
    }

    func stageDocument(url: URL) {
        guard let session, acceptsAttachmentSession(session, conversationId: conversationId) else { return }
        cancelPhotoImport()
        guard !isRecording, staged?.kind != .audio else { errorText = "Finish or discard the voice note first."; return }
        guard let mime = PulseMediaSupport.mime(forExtension: url.pathExtension) else {
            errorText = "That file type isn't supported (pdf, zip, txt, csv)"; return
        }
        let fileName = url.lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !fileName.isEmpty, fileName.utf16.count <= 120 else {
            errorText = "Rename the document to 1–120 characters, then choose it again. Any previous attachment is kept."; return
        }
        let ticket = photoImportGeneration, epoch = session.sessionGeneration
        uploading = true
        photoImportTask = Task { [weak self] in
            guard let self else { return }
            defer { if photoImportGeneration == ticket { uploading = false; photoImportTask = nil } }
            let prepared = Task.detached(priority: .userInitiated) {
                try Task.checkCancellation()
                let secured = url.startAccessingSecurityScopedResource()
                defer { if secured { url.stopAccessingSecurityScopedResource() } }
                let data = try PulseMediaSupport.readDocument(url: url)
                try Task.checkCancellation()
                return (PulseMediaSupport.dataUrl(mime: mime, data: data), data.count)
            }
            do {
                let (dataUrl, size) = try await withTaskCancellationHandler(operation: { try await prepared.value }, onCancel: { prepared.cancel() })
                guard !Task.isCancelled, photoImportGeneration == ticket, session.sessionGeneration == epoch else { return }
                installPreparedAttachment(StagedMedia(kind: .file, dataUrl: dataUrl, fileName: fileName,
                    fileSize: size, previewURL: nil, viewOnce: false))
            } catch {
                if !Task.isCancelled, photoImportGeneration == ticket, session.sessionGeneration == epoch {
                    errorText = "Couldn't read that document (10 MB maximum). Any previous attachment is kept."
                }
            }
        }
    }

    /// Prepared bytes have passed the caller's session/generation checks.
    /// Replacing media is not an instruction to erase the live caption or to
    /// turn a view-once image into a permanent image.
    func installPreparedAttachment(_ prepared: StagedMedia) {
        guard !isRecording, staged?.kind != .audio else { return }
        let once = prepared.kind == .image && (prepared.viewOnce || (staged?.kind == .image && staged?.viewOnce == true))
        staged = StagedMedia(kind: prepared.kind, dataUrl: prepared.dataUrl, fileName: prepared.fileName,
            fileSize: prepared.fileSize, previewURL: prepared.previewURL, viewOnce: once,
            durationMs: prepared.durationMs, previewData: prepared.previewData)
    }

    /// Wave 2 view-once - flips the staged card's burn flag (image sends).
    func setStagedViewOnce(_ on: Bool) {
        guard let current = staged, current.kind == .image else { return }
        staged = StagedMedia(
            kind: current.kind,
            dataUrl: current.dataUrl,
            fileName: current.fileName,
            fileSize: current.fileSize,
            previewURL: current.previewURL,
            viewOnce: on,
            durationMs: current.durationMs,
            previewData: current.previewData,
        )
    }

    func cancelStaged() {
        cancelPhotoImport()
        staged = nil
        caption = ""
        uploading = false
    }

    /// Commit bytes + immutable send metadata before clearing the staged card.
    /// The account outbox owns upload/retry, even after this screen disappears.
    func sendStaged(session: PulseSession) {
        guard acceptsAttachmentSession(session, conversationId: conversationId) else {
            errorText = "Return to the original account before sending this attachment."; return
        }
        guard let media = staged, !uploading else { return }
        guard let viewer = session.viewer, let store = session.store, session.ready else {
            errorText = "The account store is not ready. Your attachment is kept."
            return
        }
        do {
            let kind: String
            switch media.kind { case .image: kind = "image"; case .file: kind = "file"; case .audio: kind = "audio" }
            let message = try PulseAttachmentDelivery.enqueue(store: store, viewer: viewer, conversationId: conversationId,
                content: caption.trimmingCharacters(in: .whitespacesAndNewlines), dataUrl: media.dataUrl,
                envelope: PulseOutboxForward(kind: kind, durationMs: media.durationMs,
                    fileName: media.fileName, fileSize: media.fileSize, topicId: activeTopicId,
                    anon: isGroupRoom && anonOn, viewOnce: media.viewOnce), replyTo: replyTarget)
            upsert(message)
            staged = nil; caption = ""; replyTarget = nil
            if isGroupRoom { anonOn = false }
            session.outbox?.reloadPending()
            session.flushOutbox()
        } catch { errorText = Self.describe(error) }
    }

    // ── Wave 2 voice notes (spec §1 rows 1/11/12/15) ─────

    /// D31 hold-to-record - called the moment the mic slot receives a press.
    /// Mic TCC first; if the prompt swallows the finger lift the recording
    /// simply continues with the explicit bar (cancel X + slot tap to send).
    func startVoiceRecording(session: PulseSession) {
        guard !isRecording, !uploading, voiceRecorder == nil, voicePermissionTask == nil, staged == nil else { return }
        voiceSession = session
        let generation = UUID(); voicePermissionGeneration = generation
        let account = session.sessionGeneration
        voicePermissionTask = Task { [weak self] in
            guard let self else { return }
            defer { if voicePermissionGeneration == generation { voicePermissionTask = nil } }
            let granted = await VoiceRecorder.requestPermission()
            guard !Task.isCancelled, generation == voicePermissionGeneration,
                  account == session.sessionGeneration else { return }
            guard granted else {
                voiceSession = nil
                session.toasts.show("Microphone access is off - enable it in Settings to record voice notes")
                return
            }
            guard !isRecording, voiceRecorder == nil, staged == nil else { return }
            let recorder = VoiceRecorder()
            do { try recorder.start() }
            catch { voiceSession = nil; session.toasts.show("Couldn't start recording"); return }
            voiceRecorder = recorder
            recordingStartedAt = Date(); recordingElapsedMs = 0; recordingAmplitudes = []
            isRecording = true
            startRecordingTicker()
        }
    }

    /// D31 - hold finished: slide-away cancels (discard), plain release sends.
    func endVoiceHold(cancelled: Bool, session: PulseSession) {
        if cancelled {
            cancelVoiceRecording()
        } else {
            finishAndSendVoice(session: session)
        }
    }

    func cancelVoiceRecording() {
        voicePermissionGeneration = UUID()
        voicePermissionTask?.cancel(); voicePermissionTask = nil
        voiceSession = nil
        guard isRecording else { return }
        voiceRecorder?.cancel()
        teardownRecording()
    }

    /// Stop and stage the recording, then durably enqueue its bytes and metadata.
    /// A failed upload must not delete the only copy of the user's recording.
    func finishAndSendVoice(session: PulseSession) {
        guard isRecording, !sendingVoice, let startedAt = recordingStartedAt else { return }
        let elapsedMs = Date().timeIntervalSince(startedAt) * 1000
        let durationMs = min(VoiceMath.maximumSendMs, VoiceMath.roundedDurationMs(fromElapsedMs: elapsedMs))
        voiceRecorder?.stop()
        let fileURL = voiceRecorder?.fileURL
        teardownRecording()
        guard !VoiceMath.isTooShort(elapsedMs), let fileURL else {
            if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
            session.toasts.show("Too short - voice note discarded")
            return
        }
        do {
            let data = try Data(contentsOf: fileURL)
            staged = StagedMedia(kind: .audio, dataUrl: PulseMediaSupport.dataUrl(mime: "audio/mp4", data: data),
                fileName: "Voice note.m4a", fileSize: data.count, previewURL: nil, viewOnce: false, durationMs: durationMs)
            caption = ""
            // Only the temporary recorder file is removed; the staged bytes remain
            // on storage failure, or move atomically into the durable outbox.
            try? FileManager.default.removeItem(at: fileURL)
            sendStaged(session: session)
        } catch { errorText = "Could not read the recording. The source file was not deleted." }
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

    /// 100 ms sweep - wall-clock elapsed + LIVE metering (averagePower →
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

    private func cancelPendingVoicePlayback() {
        voicePlaybackGeneration = UUID()
        voicePlaybackTask?.cancel()
        voicePlaybackTask = nil
    }

    func toggleVoicePlayback(_ message: WireChatMessage, session: PulseSession) {
        cancelPendingVoicePlayback()
        if let state = playback.state, state.messageId == message.id {
            if state.playing {
                playback.pause()
            } else if state.progress < 0.999 {
                if !playback.resume() { session.toasts.show("Voice note could not play") }
            } else {
                if !playback.replay() { session.toasts.show("Voice note could not play") }
            }
            return
        }
        // Single active player - starting one message stops the previous one.
        playback.stop()
        startVoicePlayback(message, session: session)
    }

    private func startVoicePlayback(_ message: WireChatMessage, session: PulseSession) {
        guard let path = message.audioPath, let remote = PulseEndpoints.mediaURL(path) else {
            session.toasts.show("Voice note unavailable")
            return
        }
        let ticket = voicePlaybackGeneration
        let epoch = session.sessionGeneration
        voicePlaybackTask = Task { [weak self] in
            guard !Task.isCancelled, let self, voicePlaybackGeneration == ticket,
                  session.sessionGeneration == epoch else { return }
            let local: URL
            if let cached = voiceLocalFiles[message.id] {
                local = cached
            } else {
                do {
                    local = try await PulseMediaOpener.downloadForPreview(url: remote, fileName: "voice-\(message.id).m4a")
                    guard !Task.isCancelled, voicePlaybackGeneration == ticket,
                          session.sessionGeneration == epoch else { return }
                    voiceLocalFiles[message.id] = local
                } catch {
                    guard !Task.isCancelled, voicePlaybackGeneration == ticket,
                          session.sessionGeneration == epoch else { return }
                    session.toasts.show("Couldn't download the voice note")
                    return
                }
            }
            guard !Task.isCancelled, voicePlaybackGeneration == ticket,
                  session.sessionGeneration == epoch else { return }
            if !playback.start(messageId: message.id, localURL: local, rate: voiceRate) {
                session.toasts.show("Voice note could not play")
            }
        }
    }

    /// Speed chip 1x → 1.5x → 2x → 1x - global pref (UserDefaults
    /// "pulse.voiceRate"), applied live to the active player.
    func cycleVoiceRate() {
        let next = VoicePlaybackManager.nextRate(after: voiceRate)
        voiceRate = next
        UserDefaults.standard.set(next, forKey: VoicePlaybackManager.rateKey)
        playback.setRate(next)
    }

    /// POST /transcribe (kind "audio" rows only) - patches the store row AND
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
                return "Transcription service is down - try again later"
            }
            if let message = failure.message, !message.isEmpty {
                return message
            }
        }
        return "Transcription unavailable"
    }

    // ── Wave 2 view-once (spec §1 rows 5/6/7) ────────────

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
        guard !optionID.isEmpty else { return }
        updatePoll(pollID: pollID, optionID: optionID, session: session)
    }

    func closePoll(pollID: String, session: PulseSession) {
        updatePoll(pollID: pollID, optionID: nil, session: session)
    }

    private func updatePoll(pollID: String, optionID: String?, session: PulseSession) {
        guard !pollID.isEmpty, session.sessionGeneration == readSessionGeneration else { return }
        let epoch = session.sessionGeneration
        Task { [weak self] in
            guard let self, !Task.isCancelled, session.sessionGeneration == epoch else { return }
            do {
                let message = try await session.updatePoll(conversationId: conversationId, pollId: pollID, optionId: optionID)
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                guard message.conversationId == conversationId else {
                    throw PulseAPIClient.Failure(kind: .validation, message: "Poll returned another conversation.")
                }
                if let retained = try session.store?.message(id: message.id) { withAnimation { self.upsert(retained) } }
                if optionID == nil { session.toasts.show("Voting closed - results are final") }
            } catch is CancellationError { return } catch {
                guard !Task.isCancelled, session.sessionGeneration == epoch else { return }
                errorText = Self.describe(error)
            }
        }
    }

    // ── Wave 2 topics (spec §1 rows 9/10) ────────────────

    /// Open + after own sends + the 15 s ticker - api.topics → store upsert →
    /// read back (the cached rail renders even when the next fetch fails).
    private var topicsReadRevision = UUID()
    func loadTopics(session: PulseSession) {
        guard isGroupRoom, let viewer = session.viewer else { return }
        let epoch = session.sessionGeneration
        let client = session.api, store = session.store
        let revision = UUID()
        topicsReadRevision = revision
        Task { [weak self] in
            guard let self else { return }
            do {
                let fresh = try await client.topics(conversationId: conversationId, userId: viewer.id)
                guard !Task.isCancelled, session.sessionGeneration == epoch, topicsReadRevision == revision else { return }
                try? store?.upsert(topics: fresh, conversationId: conversationId)
            } catch {
                // Offline - the cached rail below still renders.
            }
            guard !Task.isCancelled, session.sessionGeneration == epoch, topicsReadRevision == revision else { return }
            if let store {
                topics = (try? store.topics(conversationId: conversationId)) ?? topics
            }
            setActiveTopic(TopicHeal.healed(activeTopicId, topics: topics), session: session, feedback: false)
        }
    }

    /// General (nil) = whole room; a topic refetches the river with topicId=
    /// and merges through the store (spec §1 row 9).
    func setActiveTopic(_ id: String?, session: PulseSession, feedback: Bool = true) {
        guard activeTopicId != id else { return }
        cancelHistory()
        historyWindow.record(cursor: nil, hasMore: false)
        activeTopicId = id
        captureTopicAnchor(id)
        resetMissedScope()
        // Do not leave another topic visible while the new request is suspended.
        let cached = (try? session.store?.messages(conversationId: conversationId)) ?? []
        messages = PulseTimelineMerge.merge(messages, incoming: cached, topicId: id)
        if feedback { PulseHaptics.tap() }
        riverReloadTask?.cancel()
        riverReloadTask = Task { [weak self] in
            guard let self else { return }
            await self.reloadRiver(session: session)
        }
    }

    private func reloadRiver(session: PulseSession) async {
        guard !Task.isCancelled, session.ready else { return }
        let boundaryTicket = historyWindow.revision
        let epoch = session.sessionGeneration
        let client = session.api, store = session.store
        let topic = activeTopicId
        let watermark = session.messageRevision
        do {
            let page = try await client.messages(conversationId: conversationId, topicId: topic)
            try Task.checkCancellation()
            guard session.sessionGeneration == epoch, activeTopicId == topic else { return }
            let eligible = page.messages.filter { session.acceptsSyncMessage($0.id, startedAt: watermark) }
            let fresh = try store?.mergeMessages(eligible) ?? eligible
            let cached = (try? store?.messages(conversationId: conversationId)) ?? messages
            let retained = PulseTimelineMerge.merge(messages, incoming: cached, topicId: topic)
            messages = PulseTimelineMerge.merge(retained, incoming: fresh, topicId: topic)
            historyWindow.seed(cursor: PulseHistoryCursor.oldest(page.messages),
                hasMore: page.hasMore, ifUnchanged: boundaryTicket)
            phase = .loaded
        } catch {
            guard !Task.isCancelled, session.sessionGeneration == epoch,
                  activeTopicId == topic else { return }
            // Offline - retain cached/pending rows in the captured topic.
            let cached = (try? store?.messages(conversationId: conversationId)) ?? messages
            messages = PulseTimelineMerge.merge(messages, incoming: cached, topicId: topic)
            let cursor = PulseHistoryCursor.oldest(cached.filter { topic == nil || $0.topicId == topic })
            historyWindow.seed(cursor: cursor, hasMore: cursor != nil, ifUnchanged: boundaryTicket)
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
                session.toasts.show("Filing to \(emoji) \(topic.name) - next send lands there")
            } catch {
                session.toasts.show(Self.describe(error))
            }
        }
    }

    /// Every 15 s while the room owns the screen (spec §1 row 9 - counts
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

    /// F-MS-23 - web EFFECT_PARTICLES name → native bus kind.
    /// R14 5-b - lasers/echo map to their OWN distinct canvases (rotating
    /// beams / expanding rings), no longer the generic burst.
    private static func particleKind(named name: String) -> ParticleBus.Kind {
        switch name {
        case "confetti": return .confetti
        case "stars": return .stars
        case "lasers": return .lasers
        case "echo": return .echo
        default: return .burst
        }
    }

    /// R14 5-b - the effect's native particle count: the lasers/echo
    /// canvases draw beams/rings (3-4 of them on the web), not bursts.
    private static func particleCount(for kind: ParticleBus.Kind) -> Int {
        switch kind {
        case .lasers: return 4
        case .echo: return 3
        default: return 90
        }
    }

    /// F-MS-23 - incoming effect-flagged rows fire the burst for rows OTHERS
    /// sent (own sends fire on the success path). messageNew + envelopes.
    private func fireIncomingEffectIfAny(_ message: WireChatMessage, viewerId: String?, session: PulseSession) {
        guard message.senderId != viewerId,
              let effect = PulseRemediationLogic.effectOfPayload(message.payload),
              let kindName = PulseRemediationLogic.particleKind(forEffect: effect) else { return }
        let kind = Self.particleKind(named: kindName)
        session.particles.fire(kind: kind, count: Self.particleCount(for: kind), center: CGPoint(x: 0.5, y: 0.85))
    }
}

// ─────────────────────────────────────────────────────────────
// R1-W2B F-FX-05 - per-conversation theme sheet (web conv-theme-picker.tsx
// parity). Wallpaper swatches reuse the exact global token set + the same
// wash previews; optional accent tint chips layer over the wallpaper;
// "Reset to default" removes the override entirely. Writes go through the
// settings PATCH funnel (prefs blob key chat.convThemes) - the same server
// path the web picker uses, so both surfaces converge.
// ─────────────────────────────────────────────────────────────
struct ConvThemeSheet: View {
    let conversationId: String
    @ObservedObject var prefs: PulsePrefs

    @Environment(\.dismiss) private var dismiss

    private var override: WireConvTheme? {
        prefs.convThemes[conversationId]
    }

    /// The EFFECTIVE wallpaper (override ?? global) - the selected swatch
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
                        Text("Layers a soft glow over the wallpaper - also visible on None.")
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

    /// Wallpaper swatch - the room's own wash preview (light + dark aware of
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
                            .strokeBorder(selected ? PulseTheme.emberGlowTop : PulseTheme.hairlineStrong, lineWidth: selected ? 2 : 1),
                    )
                    .overlay(alignment: .topTrailing) {
                        if isOverride {
                            Circle()
                                .fill(PulseTheme.emberGlowTop)
                                .frame(width: 6, height: 6)
                                .padding(4)
                        }
                    }
                Text(token.rawValue.capitalized)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(selected ? PulseTheme.emberGlowTop : PulseTheme.textSecondary)
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
// Wave 8 - native wallpaper washes (web WALLPAPERS preview parity,
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
