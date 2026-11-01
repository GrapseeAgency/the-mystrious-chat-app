package app.pulse.protocol

import kotlinx.serialization.Serializable

/**
 * Wave 7 - Collaboration & Hub wire DTOs (red packets, whiteboard, kanban,
 * events, reminders, games, tournaments, leaderboard, hub economy).
 * Tolerant house style: every field defaulted so shape drift can never
 * crash a surface (stories rule).
 *
 * Wire sources (repo-root web app, verified file:line in worklog 7-a):
 *   POST /api/redpackets                → { message, packet }
 *   GET  /api/redpackets/[id]           → { packet, senderName, grabs, myGrab, isMine }
 *   POST /api/redpackets/[id]/grab      → { amount, grabbed, count }
 *   GET  /api/conversations/[id]/whiteboard → { strokes, serverTime, resetAt }
 *   POST /api/conversations/[id]/whiteboard → { ids, created, serverTime } | { success, removedId, serverTime }
 *   DELETE same                          → { success, reset, at, cleared, serverTime }
 *   GET|POST /api/conversations/[id]/kanban → { cards } | { card }
 *   PATCH|DELETE /api/kanban/[cardId]   → { card } | { ok }
 *   GET|POST /api/conversations/[id]/events → { events } | { event }
 *   DELETE /api/events/[id]             → { ok }
 *   POST /api/events/[id]/rsvp          → { rsvp, counts }
 *   POST /api/events/[id]/checkin       → { checkIn, xpAwarded, checkedInCount, alreadyCheckedIn }
 *   GET|POST /api/reminders             → { items } | { item }
 *   PATCH|DELETE /api/reminders/[id]    → { ok, firedAt? } | { ok }
 *   POST|GET /api/games                 → { match, message } | { matches }
 *   GET  /api/games/[id] | move | join  → { match, playerX, playerO }
 *   POST|GET /api/tournaments           → { tournament, message } | { tournaments }
 *   GET|PATCH /api/tournaments/[id]     → { tournament }
 *   POST /api/tournaments/[id]/join     → { entry }
 *   GET  /api/leaderboard               → { rows }
 *   GET  /api/hub/wallet                → { wallet, ledger }
 *   POST /api/hub/wallet/checkin        → { wallet, reward, streak } (409 body: { error, wallet })
 *   POST /api/hub/wallet/transfer       → { wallet, to }
 *   GET|POST /api/hub/swap              → { rates, stats } | { wallet, note }
 *   GET|POST /api/hub/tasks             → { tasks } | { task }
 *   PATCH|DELETE /api/hub/tasks/[id]    → { task } | { ok }
 *   GET|POST /api/hub/market            → { listings } | { listing }
 *   POST /api/hub/market/[id]/buy       → { ok, wallet }
 *   GET  /api/hub/logs                  → { logs }
 *   GET|POST|DELETE /api/hub/apps/[appId]/install → { installed, status, installedAt, installs, installers } …
 *   GET|POST /api/hub/apps/[appId]/community → { conversation, memberCount, joined }
 *   Section 11: market pages add hasMore/nextCursor, the listing receipt adds
 *   clientId/replayed, installs add installsRev + a batch snapshot
 *   (GET /api/hub/apps/installs), communities add roster paging, and the
 *   games/tournaments lists add hasMore/nextCursor under limit/cursor.
 */

// Red packets (F-RO-02)

@Serializable
data class RedPacketStubDto(
    val id: String = "",
    val total: Long = 0,
    val count: Int = 0,
    val grabbed: Int = 0,
    val note: String? = null,
    val expiresAt: String? = null,
    /** open | exhausted | expired - only present on the detail endpoint. */
    val status: String? = null,
    val senderId: String? = null,
)

@Serializable
data class RedPacketCreateResultDto(
    val message: ChatMessageDto? = null,
    val packet: RedPacketStubDto? = null,
    // Section 10 item 13: replay receipt for the durable creation identity.
    val replayed: Boolean = false,
)

@Serializable
data class RedPacketGrabRowDto(
    val userId: String = "",
    val name: String = "",
    val amount: Long = 0,
    val createdAt: String? = null,
)

@Serializable
data class RedPacketDetailDto(
    val packet: RedPacketStubDto? = null,
    val senderName: String = "Unknown",
    val grabs: List<RedPacketGrabRowDto> = emptyList(),
    val myGrab: Long? = null,
    val isMine: Boolean = false,
)

@Serializable
data class RedPacketGrabResultDto(
    val amount: Long = 0,
    val grabbed: Int = 0,
    val count: Int = 0,
    val alreadyGrabbed: Boolean = false,
)

/** Parses a `kind=="redpacket"` message payload string: {packetId,total,count,note}. */
@Serializable
data class RedPacketPayloadDto(
    val packetId: String = "",
    val total: Long = 0,
    val count: Int = 0,
    val note: String? = null,
)

/** R1-W2A - `kind=="sticker"` payload string: {emoji, pack} (web parseSticker shape). */
@Serializable
data class StickerPayloadDto(
    val emoji: String = "",
    /** Unknown packs degrade to the default pack name (web parity). */
    val pack: String = "Pulse",
)

/** R1-W2A - effect-carried `kind=="text"` payload string: { effect } (F-MS-23/D29). */
@Serializable
data class EffectPayloadDto(
    val effect: String = "",
)

// Whiteboard (F-RO-03)

@Serializable
data class WhiteboardStrokeDto(
    val id: String = "",
    val userId: String = "",
    val color: String = "#000000",
    val width: Double = 3.0,
    /** Normalized [x,y] pairs, each 0..1. */
    val points: List<List<Double>> = emptyList(),
    val createdAt: String? = null,
)

@Serializable
data class WhiteboardPageDto(
    val operationsVersion: Int = 0,
    val complete: Boolean = true,
    val strokes: List<WhiteboardStrokeDto> = emptyList(),
    val serverTime: Long? = null,
    val resetAt: Long? = null,
    // Section 10 item 10: bounded stroke pages past the 4000-stroke snapshot.
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
data class WhiteboardStrokePostDto(
    val clientId: String? = null,
    val baseResetAt: Long? = null,
    val color: String = "#000000",
    val width: Double = 3.0,
    val points: List<List<Double>> = emptyList(),
)

@Serializable
data class WhiteboardPostResultDto(
    val ids: List<String> = emptyList(),
    val created: Int = 0,
    val serverTime: Long? = null,
)

@Serializable
data class WhiteboardUndoResultDto(
    val success: Boolean = false,
    val removedId: String? = null,
    val serverTime: Long? = null,
)

@Serializable
data class WhiteboardClearResultDto(
    val success: Boolean = false,
    val reset: Boolean = false,
    val at: Long? = null,
    val cleared: Int = 0,
    val serverTime: Long? = null,
)

// Kanban (F-RO-04)

@Serializable
data class KanbanCardDto(
    val id: String = "",
    val conversationId: String = "",
    val title: String = "",
    /** todo | doing | done */
    val column: String = "todo",
    val position: Long = 0,
    val assigneeId: String? = null,
    val assigneeName: String? = null,
    val createdById: String? = null,
    val createdByName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Present when the card was converted from a chat message. */
    val sourceMessageId: String? = null,
)

@Serializable
data class KanbanPageDto(
    val boardReceiptVersion: Int,
    val userId: String,
    val conversationId: String,
    val cards: List<KanbanCardDto>,
    // Section 10 items 7/8: board revision + bounded board pages.
    val boardRev: Int = 0,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
data class KanbanCardReceiptDto(
    val boardReceiptVersion: Int,
    val userId: String,
    val conversationId: String,
    val card: KanbanCardDto,
    // Section 10 item 6: replay receipt - true = the SAME journaled identity
    // had already been accepted (the same card, never a duplicate).
    val replayed: Boolean = false,
)

@Serializable
data class KanbanDeleteReceiptDto(val boardReceiptVersion: Int, val userId: String, val conversationId: String, val cardId: String, val ok: Boolean)

// Events (F-RO-05)

@Serializable
data class EventRsvpDto(
    val id: String? = null,
    val eventId: String? = null,
    val userId: String = "",
    val name: String = "",
    /** going | maybe | no */
    val status: String = "going",
    val checkedInAt: String? = null,
)

@Serializable
data class EventCountsDto(
    val going: Int = 0,
    val maybe: Int = 0,
    val no: Int = 0,
)

@Serializable
data class GroupEventDto(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    val location: String? = null,
    val startsAt: String? = null,
    val createdById: String? = null,
    val createdByName: String? = null,
    val rsvps: List<EventRsvpDto> = emptyList(),
    val counts: EventCountsDto = EventCountsDto(),
    val myStatus: String? = null,
)

@Serializable
data class EventsPageDto(
    val events: List<GroupEventDto> = emptyList(),
    // Section 10 items 2/3: per-room revision + bounded history continuation.
    val eventsRev: Int = 0,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
data class RsvpResultDto(
    val eventReceiptVersion: Int,
    val eventId: String,
    val userId: String,
    val rsvp: EventRsvpDto? = null,
    val counts: EventCountsDto = EventCountsDto(),
)

@Serializable
data class CheckinStampDto(
    val id: String = "",
    val eventId: String = "",
    val userId: String = "",
    val status: String? = null,
    val checkedInAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class CheckinResultDto(
    val eventReceiptVersion: Int,
    val eventId: String,
    val userId: String,
    val checkIn: CheckinStampDto?,
    val xpAwarded: Boolean,
    val checkedInCount: Int,
    val alreadyCheckedIn: Boolean,
)

@Serializable
data class EventDeleteReceiptDto(val eventReceiptVersion: Int, val eventId: String, val userId: String, val ok: Boolean)

// Reminders (F-RO-06)

@Serializable
data class ReminderConversationDto(
    val id: String = "",
    val name: String = "",
    val isGroup: Boolean = false,
)

@Serializable
data class ReminderItemDto(
    val id: String = "",
    val conversationId: String = "",
    val messageId: String? = null,
    val note: String = "",
    val remindAt: String? = null,
    val firedAt: String? = null,
    val createdAt: String? = null,
    val conversation: ReminderConversationDto = ReminderConversationDto(),
    val snippet: String? = null,
)

@Serializable
data class RemindersPageDto(val items: List<ReminderItemDto> = emptyList(), val nextCursor: String? = null)

// Quick phrases (F-MS-29) - GET/POST/DELETE /api/users/{id}/phrases

/** GET → { phrases: [{id,text,position}] } · POST → { phrase: {…} }. */
@Serializable
data class QuickPhraseDto(
    val id: String = "",
    val text: String = "",
    val position: Int = 0,
)

@Serializable
data class PhrasesPageDto(
    val phrases: List<QuickPhraseDto> = emptyList(),
    /** Section 8 item 10: the rail's monotonic list revision. */
    val listRev: Int = 0,
)

@Serializable
data class PhraseEnvelopeDto(
    val phrase: QuickPhraseDto? = null,
    /** Section 8 item 10: receipt ordering + lost-ACK replay flag. */
    val listRev: Int = 0,
    val replayed: Boolean = false,
)

@Serializable
data class ReminderResolveDto(
    val ok: Boolean = false,
    val firedAt: String? = null,
)

// Games (F-RO-07)

@Serializable
data class GamePlayerDto(
    val id: String = "",
    val name: String = "Player",
    val color: String = "emerald",
)

/** 9-char board string (' ' | 'X' | 'O', padEnd(' ')), index = cell 0..8. */
@Serializable
data class GameMatchDto(
    val id: String = "",
    val conversationId: String = "",
    val game: String = "tictactoe",
    val playerXId: String = "",
    val playerOId: String? = null,
    val board: String = "         ",
    /** 'X' | 'O' */
    val turn: String = "X",
    /** active | x_won | o_won | draw */
    val status: String = "active",
    val winnerId: String? = null,
    val winLine: List<Int>? = null,
    val moveCount: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class GameDetailDto(
    val match: GameMatchDto = GameMatchDto(),
    val playerX: GamePlayerDto = GamePlayerDto(),
    val playerO: GamePlayerDto? = null,
)

@Serializable
data class GameMatchCreateResultDto(
    val clientId: String? = null,
    val replayed: Boolean = false,
    val match: GameMatchDto? = null,
    val message: ChatMessageDto? = null,
)

@Serializable
data class GamesPageDto(
    val matches: List<GameMatchDto> = emptyList(),
    // Section 11 item 7: bounded keyset continuation (present only when the
    // request carried limit/cursor; legacy pages keep the bare shape).
    val hasMore: Boolean? = null,
    val nextCursor: String? = null,
)

/** Parses a `kind=="game"` message payload string: {matchId, game}. */
@Serializable
data class GamePayloadDto(
    val matchId: String = "",
    val game: String = "tictactoe",
)

// Tournaments (F-RO-08)

@Serializable
data class TournamentSummaryDto(
    val id: String = "",
    val conversationId: String? = null,
    val name: String = "",
    val game: String = "tictactoe",
    /** running | finished */
    val status: String = "running",
    val joined: Boolean? = null,
    val playerCount: Int? = null,
    val createdAt: String? = null,
    val endsAt: String? = null,
    val entries: List<TournamentEntryDto> = emptyList(),
)

@Serializable
data class TournamentEntryDto(
    val userId: String = "",
    val name: String = "Player",
    val color: String = "emerald",
    val points: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val joinedAt: String? = null,
    val id: String = "",
    val tournamentId: String = "",
)

@Serializable
data class TournamentsPageDto(
    val tournaments: List<TournamentSummaryDto> = emptyList(),
    // Section 11 item 7: bounded keyset continuation (present only when the
    // request carried limit/cursor; legacy pages keep the bare shape).
    val hasMore: Boolean? = null,
    val nextCursor: String? = null,
)

@Serializable
data class TournamentCreateResultDto(
    val clientId: String? = null,
    val replayed: Boolean = false,
    val tournament: TournamentSummaryDto? = null,
    val message: ChatMessageDto? = null,
)

@Serializable
data class TournamentJoinResultDto(val entry: TournamentEntryDto? = null)

/** Parses a `kind=="tournament"` message payload string: {tournamentId,name,game}. */
@Serializable
data class TournamentPayloadDto(
    val tournamentId: String = "",
    val name: String = "",
    val game: String = "tictactoe",
)

// Leaderboard (F-RO-09)

@Serializable
data class LeaderboardRowDto(
    val userId: String = "",
    val name: String = "",
    val color: String = "emerald",
    val xp: Long = 0,
    val messageCount: Long = 0,
    val gameWins: Long = 0,
    val tournamentPoints: Long = 0,
)

@Serializable
data class LeaderboardPageDto(val rows: List<LeaderboardRowDto> = emptyList())

// Hub economy (F-HB-01…10)

@Serializable
data class WalletDto(
    val userId: String = "",
    val coins: Long = 0,
    val gems: Long = 0,
    val streak: Int = 0,
    val lastCheckIn: String? = null,
    /** Present on GET /wallet; absent on raw checkin rows - treat null as unknown. */
    val checkedInToday: Boolean? = null,
)

@Serializable
data class LedgerEntryDto(
    val id: String = "",
    val kind: String = "",
    /** PC | GEM */
    val asset: String = "PC",
    val amount: Long = 0,
    val note: String? = null,
    val counterpartyId: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class WalletPageDto(
    val wallet: WalletDto = WalletDto(),
    val ledger: List<LedgerEntryDto> = emptyList(),
)

@Serializable
data class CheckinWalletResultDto(
    val wallet: WalletDto = WalletDto(),
    val reward: Long = 0,
    val streak: Int = 0,
)

@Serializable
data class TransferToDto(
    val id: String = "",
    val name: String = "",
    val username: String? = null,
)

@Serializable
data class TransferResultDto(
    val wallet: WalletDto = WalletDto(),
    val to: TransferToDto = TransferToDto(),
    val clientId: String? = null,
)

@Serializable
data class SwapRatesDto(
    val pcPerGemBuy: Int = 100,
    val pcPerGemSell: Int = 80,
)

@Serializable
data class SwapStatsDto(
    val swaps: Long = 0,
    val pcBought: Long = 0,
    val pcSold: Long = 0,
    val circulatingCoins: Long = 0,
    val circulatingGems: Long = 0,
)

@Serializable
data class SwapPageDto(
    val rates: SwapRatesDto = SwapRatesDto(),
    val stats: SwapStatsDto = SwapStatsDto(),
)

@Serializable
data class SwapResultDto(
    val wallet: WalletDto = WalletDto(),
    val note: String? = null,
    val clientId: String? = null,
)

@Serializable
data class HubTaskDto(
    val id: String = "",
    val title: String = "",
    /** todo | doing | done */
    val status: String = "todo",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val ownerId: String? = null,
)

@Serializable
data class HubTasksPageDto(
    val userId: String,
    val tasks: List<HubTaskDto>,
    // Section 10 item 5: bounded task history continuation.
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
data class MarketSellerDto(
    val id: String = "",
    val name: String = "",
    val username: String? = null,
    val color: String? = null,
)

@Serializable
data class MarketListingDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val price: Long,
    val asset: String,
    val status: String,
    val createdAt: String,
    val seller: MarketSellerDto,
    val buyer: MarketSellerDto? = null,
    val mine: Boolean,
)

@Serializable
data class MarketPageDto(
    val marketVersion: Int,
    val userId: String,
    val listings: List<MarketListingDto>,
    // Section 11 item 3: bounded board continuation (present only when the
    // request carried limit/cursor; legacy pages keep the bare 60-row shape).
    val hasMore: Boolean? = null,
    val nextCursor: String? = null,
)

@Serializable
data class MarketListingEnvelopeDto(
    val marketVersion: Int,
    val userId: String,
    val listing: MarketListingDto,
    // Section 11 item 4: durable creation identity - the echoed clientId plus
    // the lost-ACK replay flag (true = the SAME journaled identity accepted).
    val clientId: String? = null,
    val replayed: Boolean? = null,
)

@Serializable
data class MarketBuyResultDto(
    val marketVersion: Int,
    val userId: String,
    val listingId: String,
    val ok: Boolean,
    val wallet: WalletDto,
)

@Serializable
data class HubLogUserDto(
    val id: String = "",
    val name: String = "",
    val username: String? = null,
    val color: String? = null,
)

@Serializable
data class HubLogDto(
    val id: String = "",
    val kind: String = "",
    val message: String = "",
    /** RAW JSON string on the wire - never parsed server-side. */
    val meta: String? = null,
    val createdAt: String? = null,
    val user: HubLogUserDto? = null,
)

@Serializable
data class HubLogsPageDto(val activityVersion: Int, val userId: String, val logs: List<HubLogDto>)

@Serializable
data class AppInstallStateDto(
    val appVersion: Int,
    val appId: String,
    val userId: String,
    val installed: Boolean,
    /** 'connected' | null */
    val status: String? = null,
    val installedAt: String? = null,
    val installs: Int,
    val installers: List<HubLogUserDto>,
    // Section 11 item 8: the owner's install revision + lost-ACK replay flag.
    val installsRev: Int? = null,
    val replayed: Boolean? = null,
)

@Serializable
data class AppInstallResultDto(
    val appVersion: Int,
    val appId: String,
    val userId: String,
    val installed: Boolean,
    val status: String? = null,
    val installs: Int,
    val installedAt: String? = null,
    val installers: List<HubLogUserDto> = emptyList(),
    // Section 11 item 8: replay receipt - a replayed connect/disconnect is an
    // ACKNOWLEDGEMENT of the same identity, never a duplicate to retry.
    val installsRev: Int? = null,
    val replayed: Boolean? = null,
)

/** One catalog row inside the Section 11 item 6 batch install snapshot
 * (GET /api/hub/apps/installs): the batch carries only the connected flag
 * per app - full per-app state still lives on the per-app route. */
@Serializable
data class AppInstallBatchRowDto(
    val appId: String = "",
    /** Catalog position (MATRIX index) - informational ordering. */
    val order: Int = 0,
    val installed: Boolean = false,
    val installedAt: String? = null,
)

/** Section 11 item 6 - ONE request for the whole catalog:
 * GET /api/hub/apps/installs?userId= → { appVersion:2, userId, installsRev, apps }. */
@Serializable
data class AppInstallsBatchDto(
    val appVersion: Int,
    val userId: String,
    // The owner's install revision (null on gateways predating item 8).
    val installsRev: Int? = null,
    val apps: List<AppInstallBatchRowDto> = emptyList(),
)

@Serializable
data class AppCommunityDto(
    val appVersion: Int,
    val appId: String,
    val userId: String,
    /** Existing conversation-detail shape (same serializer as GET /api/conversations/{id}). */
    val conversation: ConversationSummaryDto? = null,
    val memberCount: Long,
    val joined: Boolean,
    // Section 11 item 5: bounded roster continuation (present only when the
    // request carried limit/cursor; legacy reads keep the full roster).
    val rosterHasMore: Boolean? = null,
    val rosterNextCursor: String? = null,
)
