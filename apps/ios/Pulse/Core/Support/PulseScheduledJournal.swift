import Foundation

struct PulseScheduledCommand: Codable, Sendable {
    let owner: String
    let clientId: String
    let kind: String
    var conversationId: String = ""
    var text: String = ""
    var scheduledAt: String = ""
    var notice: String? = nil
    func sameInstructions(_ other: Self) -> Bool {
        owner == other.owner && clientId == other.clientId && kind == other.kind && conversationId == other.conversationId &&
            text.utf16.elementsEqual(other.text.utf16) && scheduledAt == other.scheduledAt
    }
    var key: String { "composer-schedule:v1|\(owner)|\(kind)|\(clientId)" }
    var heldKey: String { key.replacingOccurrences(of: "composer-schedule:",with: "composer-schedule-blocked:") }
    var ackKey: String { key.replacingOccurrences(of: "composer-schedule:",with: "composer-schedule-ack:") }
    var headKey: String { "composer-schedule-head:v1|\(owner)|\(conversationId)" }
}

@MainActor
enum PulseScheduledReplay {
    private static var busy = Set<String>()
    static func flush(store: PulseStore, client: PulseAPIClient, current: () -> Bool) async throws -> Int {
        guard busy.insert(store.storageIdentity).inserted else { return try store.pendingSchedules(owner: client.userId) }
        defer { busy.remove(store.storageIdentity) }
        for command in try store.scheduledCommandPage(owner: client.userId).prefix(25) {
            try Task.checkCancellation(); guard current() else { throw CancellationError() }
            do {
                let result: String
                if command.kind == "create" {
                    let item = try await client.scheduleMessage(conversationId: command.conversationId,content: command.text,
                        scheduledAtIso: command.scheduledAt,clientId: command.clientId)
                    result = String(decoding: try JSONEncoder().encode(item),as: UTF8.self)
                } else {
                    try await client.cancelScheduled(command.clientId)
                    result = "cancelled"
                }
                try Task.checkCancellation(); guard current() else { throw CancellationError() }
                try store.finishScheduledCommand(command,result: result)
            } catch is CancellationError { throw CancellationError() }
            catch {
                guard current() else { throw CancellationError() }
                if let failure = error as? PulseAPIClient.Failure,
                   [400,403,404,410,422].contains(failure.status ?? 0) || failure.code == "schedule_already_sent" || failure.kind == .validation {
                    try store.deferScheduledCommand(command,notice: failure.message ?? "These original scheduling instructions need review. Nothing was rescheduled.")
                } else { try store.deferScheduledCommand(command) }
                if let failure = error as? PulseAPIClient.Failure, failure.kind == .auth || failure.kind == .rateLimited { break }
            }
        }
        return try store.pendingSchedules(owner: client.userId)
    }
}
