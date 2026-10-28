import Foundation
import CryptoKit

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
    var requestHash: String {
        let input = [owner,clientId,kind,conversationId,text,scheduledAt].map { "\($0.utf16.count):\($0)" }.joined()
        return SHA256.hash(data: Data(input.utf8)).map { String(format: "%02x",$0) }.joined()
    }
    var key: String { "composer-schedule:v1|\(owner)|\(kind)|\(clientId)" }
    var heldKey: String { key.replacingOccurrences(of: "composer-schedule:",with: "composer-schedule-blocked:") }
    var ackKey: String { key.replacingOccurrences(of: "composer-schedule:",with: "composer-schedule-ack:") }
    var headKey: String { "composer-schedule-head:v1|\(owner)|\(conversationId)" }
}

@MainActor
enum PulseScheduledReplay {
    private static var busy = Set<String>()
    static func flush(store: PulseStore, client: PulseAPIClient, current: @MainActor () -> Bool) async throws -> Int {
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
                   [400,403,404,410,422].contains(failure.status ?? 0) || ["schedule_already_sent", "schedule_duplicate"].contains(failure.code ?? "") || failure.kind == .validation {
                    try store.deferScheduledCommand(command,notice: failure.message ?? "These original scheduling instructions need review. Nothing was rescheduled.")
                } else { try store.deferScheduledCommand(command) }
                if let failure = error as? PulseAPIClient.Failure, failure.kind == .auth || failure.kind == .rateLimited { break }
            }
        }
        return try store.pendingSchedules(owner: client.userId)
    }
}

struct PulseScheduledCommandAck: Codable, Sendable {
    var version = 1
    let owner: String
    let clientId: String
    let kind: String
    let requestHash: String
    let item: WireScheduledItem?
    var cancelled: Bool? = nil
    func validate(_ command: PulseScheduledCommand) throws {
        guard cancelled != true else { throw PulseAPIClient.Failure(kind: .validation,message: "The original schedule was cancelled. Choose a different time for a new send.") }
        guard version == 1, owner == command.owner, clientId == command.clientId, kind == command.kind,
              requestHash == command.requestHash else { throw CocoaError(.coderInvalidValue) }
    }
}
