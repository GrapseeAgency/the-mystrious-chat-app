import Foundation

// ─────────────────────────────────────────────────────────────
// Wave 7 — collaboration & hub wire DTOs (F-RO / F-HB).
// Mirror of the live gateway JSON routes audited in worklog
// "Task ID: 7-a" — red packets, whiteboard, kanban, events,
// reminders, games, tournaments, leaderboard, hub economy.
// Tolerant decode: only id-critical fields stay required;
// everything else decodes as nil/default so older relays never
// crash the surface.
// ─────────────────────────────────────────────────────────────

// MARK: - Red packets (F-RO-02)

public struct WireRedPacketStub: Codable, Hashable, Sendable {
    public let id: String
    public let total: Int?
    public let count: Int?
    public let grabbed: Int?
    public let note: String?
    public let expiresAt: String?
    public let status: String?
    public let senderId: String?
}

public struct WireRedPacketCreateResult: Codable, Sendable {
    public let message: WireChatMessage?
    public let packet: WireRedPacketStub?
}

public struct WireRedPacketGrabRow: Codable, Hashable, Sendable {
    public let userId: String?
    public let name: String?
    public let amount: Int?
    public let createdAt: String?
}

public struct WireRedPacketDetail: Codable, Hashable, Sendable {
    public let packet: WireRedPacketStub?
    public let senderName: String?
    public let grabs: [WireRedPacketGrabRow]?
    public let myGrab: Int?
    public let isMine: Bool?
}

public struct WireRedPacketGrabResult: Codable, Hashable, Sendable {
    public let amount: Int
    public let grabbed: Int?
    public let count: Int?
}

/// kind == "redpacket" payload JSON: {packetId, total, count, note}.
public struct WireRedPacketPayload: Codable, Hashable, Sendable {
    public let packetId: String
    public let total: Int?
    public let count: Int?
    public let note: String?
}

// MARK: - Whiteboard (F-RO-03)

public struct WireWhiteboardStroke: Codable, Hashable, Sendable {
    public let id: String
    public let userId: String?
    public let color: String?
    public let width: Double?
    /// Normalized [x, y] pairs, each 0..1.
    public let points: [[Double]]?
    public let createdAt: String?
}

public struct WireWhiteboardPage: Codable, Sendable {
    public let strokes: [WireWhiteboardStroke]?
    public let serverTime: Int64?
    public let resetAt: Int64?
}

public struct WireWhiteboardStrokePost: Codable, Sendable {
    public let color: String
    public let width: Double
    public let points: [[Double]]
}

public struct WireWhiteboardPostResult: Codable, Sendable {
    public let ids: [String]?
    public let created: Int?
    public let serverTime: Int64?
}

public struct WireWhiteboardUndoResult: Codable, Sendable {
    public let success: Bool?
    public let removedId: String?
    public let serverTime: Int64?
}

public struct WireWhiteboardClearResult: Codable, Sendable {
    public let success: Bool?
    public let reset: Bool?
    public let at: Int64?
    public let cleared: Int?
    public let serverTime: Int64?
}

// MARK: - Kanban (F-RO-04)

public struct WireKanbanCard: Codable, Hashable, Sendable {
    public let id: String
    public let conversationId: String?
    public let title: String?
    public let column: String?
    public let position: Int?
    public let assigneeId: String?
    public let assigneeName: String?
    public let createdById: String?
    public let createdByName: String?
    public let createdAt: String?
    public let updatedAt: String?
    public let sourceMessageId: String?
}

public struct WireKanbanPage: Codable, Sendable {
    public let cards: [WireKanbanCard]
}

public struct WireKanbanCardEnvelope: Codable, Sendable {
    public let card: WireKanbanCard?
}

// MARK: - Events (F-RO-05)

public struct WireEventRsvp: Codable, Hashable, Sendable {
    public let userId: String?
    public let name: String?
    public let status: String?
    public let checkedInAt: String?
}

public struct WireEventCounts: Codable, Hashable, Sendable {
    public let going: Int?
    public let maybe: Int?
    public let no: Int?
}

public struct WireGroupEvent: Codable, Hashable, Sendable {
    public let id: String
    public let title: String?
    public let description: String?
    public let location: String?
    public let startsAt: String?
    public let createdById: String?
    public let createdByName: String?
    public let rsvps: [WireEventRsvp]?
    public let counts: WireEventCounts?
    public let myStatus: String?
}

public struct WireEventsPage: Codable, Sendable {
    public let events: [WireGroupEvent]
}

public struct WireEventEnvelope: Codable, Sendable {
    public let event: WireGroupEvent?
}

public struct WireRsvpResult: Codable, Sendable {
    public let rsvp: WireEventRsvp?
    public let counts: WireEventCounts?
}

public struct WireCheckinStamp: Codable, Hashable, Sendable {
    public let id: String?
    public let eventId: String?
    public let userId: String?
    public let status: String?
    public let checkedInAt: String?
}

public struct WireCheckinResult: Codable, Sendable {
    public let checkIn: WireCheckinStamp?
    public let xpAwarded: Bool?
    public let checkedInCount: Int?
    public let alreadyCheckedIn: Bool?
}

// MARK: - Reminders (F-RO-06)

public struct WireReminderConversation: Codable, Hashable, Sendable {
    public let id: String?
    public let name: String?
    public let isGroup: Bool?
}

public struct WireReminderItem: Codable, Hashable, Sendable {
    public let id: String
    public let conversationId: String?
    public let messageId: String?
    public let note: String?
    public let remindAt: String?
    public let firedAt: String?
    public let createdAt: String?
    public let conversation: WireReminderConversation?
    public let snippet: String?
}

public struct WireRemindersPage: Codable, Sendable {
    public let items: [WireReminderItem]
}

public struct WireReminderEnvelope: Codable, Sendable {
    public let item: WireReminderItem?
}

public struct WireReminderResolve: Codable, Sendable {
    public let ok: Bool?
    public let firedAt: String?
}

// MARK: - Games (F-RO-07)

public struct WireGamePlayer: Codable, Hashable, Sendable {
    public let id: String
    public let name: String?
    public let color: String?
}

public struct WireGameMatch: Codable, Hashable, Sendable {
    public let id: String
    public let conversationId: String?
    public let game: String?
    public let playerXId: String?
    public let playerOId: String?
    /// 9-char board string (' ' | 'X' | 'O').
    public let board: String?
    public let turn: String?
    public let status: String?
    public let winnerId: String?
    public let winLine: [Int]?
    public let moveCount: Int?
    public let createdAt: String?
    public let updatedAt: String?
}

public struct WireGameDetail: Codable, Sendable {
    public let match: WireGameMatch?
    public let playerX: WireGamePlayer?
    public let playerO: WireGamePlayer?
}

public struct WireGameMatchCreateResult: Codable, Sendable {
    public let match: WireGameMatch?
    public let message: WireChatMessage?
}

public struct WireGamesPage: Codable, Sendable {
    public let matches: [WireGameMatch]
}

/// kind == "game" payload JSON: {matchId, game}.
public struct WireGamePayload: Codable, Hashable, Sendable {
    public let matchId: String
    public let game: String?
}

// MARK: - Tournaments (F-RO-08)

public struct WireTournamentEntry: Codable, Hashable, Sendable {
    public let userId: String?
    public let name: String?
    public let color: String?
    public let points: Int?
    public let wins: Int?
    public let losses: Int?
    public let draws: Int?
    public let joinedAt: String?
    public let id: String?
    public let tournamentId: String?
}

public struct WireTournamentSummary: Codable, Hashable, Sendable {
    public let id: String
    public let conversationId: String?
    public let name: String?
    public let game: String?
    public let status: String?
    public let playerCount: Int?
    public let createdAt: String?
    public let endsAt: String?
    public let entries: [WireTournamentEntry]?
}

public struct WireTournamentsPage: Codable, Sendable {
    public let tournaments: [WireTournamentSummary]
}

public struct WireTournamentEnvelope: Codable, Sendable {
    public let tournament: WireTournamentSummary?
}

public struct WireTournamentCreateResult: Codable, Sendable {
    public let tournament: WireTournamentSummary?
    public let message: WireChatMessage?
}

public struct WireTournamentJoinResult: Codable, Sendable {
    public let entry: WireTournamentEntry?
}

/// kind == "tournament" payload JSON: {tournamentId, name, game}.
public struct WireTournamentPayload: Codable, Hashable, Sendable {
    public let tournamentId: String
    public let name: String?
    public let game: String?
}

// MARK: - Leaderboard (F-RO-09)

public struct WireLeaderboardRow: Codable, Hashable, Sendable {
    public let userId: String?
    public let name: String?
    public let color: String?
    public let xp: Int?
    public let messageCount: Int?
    public let gameWins: Int?
    public let tournamentPoints: Int?
}

public struct WireLeaderboardPage: Codable, Sendable {
    public let rows: [WireLeaderboardRow]
}

// MARK: - Hub economy (F-HB-01…10)

public struct WireLedgerEntry: Codable, Hashable, Sendable {
    public let id: String?
    public let kind: String?
    public let asset: String?
    public let amount: Int?
    public let note: String?
    public let counterpartyId: String?
    public let createdAt: String?
}

public struct WireCheckinWalletResult: Codable, Sendable {
    public let wallet: WireWallet?
    public let reward: Int?
    public let streak: Int?
}

public struct WireTransferTo: Codable, Hashable, Sendable {
    public let id: String?
    public let name: String?
    public let username: String?
}

public struct WireTransferResult: Codable, Sendable {
    public let wallet: WireWallet?
    public let to: WireTransferTo?
}

public struct WireSwapRates: Codable, Hashable, Sendable {
    public let pcPerGemBuy: Int?
    public let pcPerGemSell: Int?
}

public struct WireSwapStats: Codable, Hashable, Sendable {
    public let swaps: Int?
    public let pcBought: Int?
    public let pcSold: Int?
    public let circulatingCoins: Int?
    public let circulatingGems: Int?
}

public struct WireSwapPage: Codable, Sendable {
    public let rates: WireSwapRates?
    public let stats: WireSwapStats?
}

public struct WireSwapResult: Codable, Sendable {
    public let wallet: WireWallet?
    public let note: String?
}

public struct WireHubTask: Codable, Hashable, Sendable {
    public let id: String
    public let title: String?
    public let status: String?
    public let createdAt: String?
    public let updatedAt: String?
}

public struct WireHubTasksPage: Codable, Sendable {
    public let tasks: [WireHubTask]
}

public struct WireHubTaskEnvelope: Codable, Sendable {
    public let task: WireHubTask?
}

public struct WireMarketSeller: Codable, Hashable, Sendable {
    public let id: String?
    public let name: String?
    public let username: String?
    public let color: String?
}

public struct WireMarketListing: Codable, Hashable, Sendable {
    public let id: String
    public let title: String?
    public let description: String?
    public let price: Int?
    public let asset: String?
    public let status: String?
    public let createdAt: String?
    public let seller: WireMarketSeller?
    public let buyer: WireMarketSeller?
    public let mine: Bool?
}

public struct WireMarketPage: Codable, Sendable {
    public let listings: [WireMarketListing]
}

public struct WireMarketListingEnvelope: Codable, Sendable {
    public let listing: WireMarketListing?
}

public struct WireMarketBuyResult: Codable, Sendable {
    public let ok: Bool?
    public let wallet: WireWallet?
}

public struct WireHubLogUser: Codable, Hashable, Sendable {
    public let id: String?
    public let name: String?
    public let username: String?
    public let color: String?
}

public struct WireHubLog: Codable, Hashable, Sendable {
    public let id: String?
    public let kind: String?
    public let message: String?
    /// RAW JSON string on the wire — never parsed server-side.
    public let meta: String?
    public let createdAt: String?
    public let user: WireHubLogUser?
}

public struct WireHubLogsPage: Codable, Sendable {
    public let logs: [WireHubLog]
}

public struct WireAppInstallState: Codable, Hashable, Sendable {
    public let installed: Bool?
    public let status: String?
    public let installedAt: String?
    public let installs: Int?
    public let installers: [WireHubLogUser]?
}

public struct WireAppInstallResult: Codable, Sendable {
    public let installed: Bool?
    public let status: String?
    public let installs: Int?
}

public struct WireAppCommunity: Codable, Sendable {
    public let conversation: WireConversationSummary?
    public let memberCount: Int?
    public let joined: Bool?
}

// MARK: - Hub catalog (bundled JSON, generated from src/lib/hub-catalog.ts)

public struct HubCatalogApp: Codable, Hashable, Sendable {
    public let n: Int
    public let name: String
    public let nav: String?
    public let input: String?
    public let category: String?
    public let secret: String?
}

public struct HubCatalogCategory: Codable, Hashable, Sendable {
    public let slug: String
    public let label: String
    public let blurb: String?
    public let from: String?
    public let to: String?
}

public struct HubCatalog: Codable, Sendable {
    public let apps: [HubCatalogApp]
    public let categories: [HubCatalogCategory]
    public let taglines: [String: String]
}
