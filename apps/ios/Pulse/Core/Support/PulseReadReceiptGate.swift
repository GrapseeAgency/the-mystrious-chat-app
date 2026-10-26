import Foundation

/// Visibility dwell and response ownership, independent of transport scheduling.
/// `now` is monotonic uptime, never the device's adjustable wall clock.
struct PulseReadReceiptGate {
    struct Boundary: Hashable {
        let roomId: String
        let messageId: String
    }
    struct Request: Equatable {
        let id: UUID
        let revision: UInt64
        let boundary: Boundary
    }
    private var candidates: [Boundary] = []
    var boundary: Boundary? {
        candidates.filter { !acknowledged.contains($0) }.min {
            (eligibleAt[$0] ?? .infinity) < (eligibleAt[$1] ?? .infinity)
        }
    }
    private(set) var revision: UInt64 = 0
    private var eligibleAt: [Boundary: TimeInterval] = [:]
    private var acknowledged: Set<Boundary> = []
    private var inFlight: Request?
    private var intents: [Boundary: String] = [:]
    private var sessionIntent: String?
    func intent(for request: Request) -> String? { intents[request.boundary] ?? sessionIntent }
    mutating func retainIntent(_ intent: String, for request: Request) -> Bool {
        guard request.revision == revision, request == inFlight else { return false }
        if sessionIntent == nil { sessionIntent = intent }
        intents[request.boundary] = sessionIntent
        return true
    }
    static let dwell: TimeInterval = 0.6

    mutating func reset() {
        intents.removeAll()
        sessionIntent = nil
        candidates = []
        acknowledged = []
        eligibleAt.removeAll()
        revision &+= 1
        inFlight = nil
    }

    @discardableResult
    mutating func setBoundary(roomId: String, messageId: String?, now: TimeInterval) -> Bool {
        setBoundaries(roomId: roomId, messageIds: messageId.map { [$0] } ?? [], now: now)
    }

    @discardableResult
    mutating func setBoundaries(roomId: String, messageIds: [String], now: TimeInterval) -> Bool {
        let next = Set(messageIds).sorted().map { Boundary(roomId: roomId, messageId: $0) }
        guard next != candidates else { return false }
        let retained = Set(next)
        eligibleAt = eligibleAt.filter { retained.contains($0.key) }
        for candidate in next where eligibleAt[candidate] == nil {
            eligibleAt[candidate] = now + Self.dwell
        }
        candidates = next
        revision &+= 1
        inFlight = nil
        return true
    }

    mutating func restartDwell(now: TimeInterval) {
        revision &+= 1
        inFlight = nil
        for candidate in candidates { eligibleAt[candidate] = now + Self.dwell }
    }

    mutating func deferAttempt(until deadline: TimeInterval) {
        guard let boundary else { return }
        eligibleAt[boundary] = max(eligibleAt[boundary] ?? .infinity, deadline)
    }

    func nextDelay(now: TimeInterval) -> TimeInterval? {
        guard let boundary, let deadline = eligibleAt[boundary], deadline.isFinite else { return nil }
        return max(0, deadline - now)
    }

    mutating func begin(roomId: String, now: TimeInterval) -> Request? {
        guard let boundary, boundary.roomId == roomId, !acknowledged.contains(boundary),
              now >= (eligibleAt[boundary] ?? .infinity), inFlight == nil else { return nil }
        let request = Request(id: UUID(), revision: revision, boundary: boundary)
        inFlight = request
        return request
    }

    /// A cancelled old request cannot clear a replacement request or acknowledge its boundary.
    @discardableResult
    mutating func finish(_ request: Request, succeeded: Bool) -> Bool {
        guard request.revision == revision, request == inFlight else { return false }
        inFlight = nil
        if succeeded { acknowledged.insert(request.boundary) }
        return true
    }
}


/// Navigation identity outlives temporary presentation visibility. In particular,
/// an old same-room destination cannot retire its replacement during a transition.
struct PulseRoomReadVisit {
    struct Owner: Equatable { let entryID: UUID; let roomID: String }
    private(set) var owner: Owner?
    private(set) var active = false

    mutating func activate(entryID: UUID, roomID: String) -> Bool {
        let next = Owner(entryID: entryID, roomID: roomID)
        let changed = owner != next
        owner = next
        active = true
        return changed
    }
    func owns(entryID: UUID, roomID: String) -> Bool {
        active && owner == Owner(entryID: entryID, roomID: roomID)
    }
    mutating func suspend(entryID: UUID, roomID: String) -> Bool {
        guard owns(entryID: entryID, roomID: roomID) else { return false }
        active = false
        return true
    }
    mutating func reset() { owner = nil; active = false }
}

/// The presented thread owns visibility while the obscured room keeps its
/// latest base boundary. Dismissal order must not let either erase the other.
struct PulseReadSurfaces {
    struct Owner: Equatable {
        let id = UUID()
        let roomId: String
        let rootId: String?
    }
    private var primary: [PulseReadReceiptGate.Boundary] = []
    private var overlay: [PulseReadReceiptGate.Boundary] = []
    private(set) var owner: Owner?
    var boundaries: [PulseReadReceiptGate.Boundary] { owner == nil ? primary : overlay }
    var boundary: PulseReadReceiptGate.Boundary? { boundaries.first }

    mutating func reset() { primary = []; overlay = []; owner = nil }
    mutating func setPrimary(roomId: String, messageId: String?) {
        setPrimary(roomId: roomId, messageIds: messageId.map { [$0] } ?? [])
    }
    mutating func setPrimary(roomId: String, messageIds: [String]) {
        primary = messageIds.map { .init(roomId: roomId, messageId: $0) }
    }
    // Root PiP is below a presented thread sheet, but above the primary room.
    func obscuredByPip(isOpen: Bool) -> Bool { isOpen && owner == nil }
    func isObscured(shellPresented: Bool, pipOpen: Bool) -> Bool {
        shellPresented || obscuredByPip(isOpen: pipOpen)
    }

    mutating func begin(roomId: String, rootId: String? = nil) -> Owner {
        let next = Owner(roomId: roomId, rootId: rootId)
        owner = next
        overlay = []
        return next
    }
    mutating func setOverlay(_ token: Owner, messageId: String?) {
        guard owner == token else { return }
        setOverlay(token, messageIds: messageId.map { [$0] } ?? [])
    }
    mutating func setOverlay(_ token: Owner, messageIds: [String]) {
        guard owner == token else { return }
        overlay = messageIds.map { .init(roomId: token.roomId, messageId: $0) }
    }
    @discardableResult
    mutating func end(_ token: Owner) -> Bool {
        guard owner == token else { return false }
        owner = nil
        overlay = []
        return true
    }
}


/// A temporary outage must not permanently exhaust Seen acknowledgements while
/// the user remains on the same visible boundary. Visibility owns the task.
enum PulseVisibleReadRetry {
    static func delay(after error: Error, failures: Int) -> TimeInterval? {
        if error is CancellationError || (error as? URLError)?.code == .cancelled { return nil }
        let failure = error as? PulseAPIClient.Failure
        if let status = failure?.status {
            if (400..<500).contains(status) && ![408, 409, 425, 429].contains(status) { return nil }
        } else if let kind = failure?.kind, [.auth, .forbidden, .notFound, .validation].contains(kind) {
            return nil
        }
        let backoff = min(60, 1.5 * pow(2, Double(min(7, max(1, failures)) - 1)))
        return max(backoff, Double(min(86_400, max(0, failure?.retryAfter ?? 0))))
    }
}

/// A dwell-qualified observation, not a new read intention at delivery time.
struct PulseQueuedRead: Codable, Sendable, Equatable {
    let owner: String
    let room: String
    let message: String
    let minimumReadAt: String
    let intent: String
    var legacyKey: String { "read-journal:v1|\(owner)|\(intent.split(separator: ".").last.map(String.init) ?? intent)|\(message)" }
    // A new dwell with genuinely newer cached authority must not be swallowed
    // by an earlier offline observation of the same message.
    var key: String { legacyKey + "|" + intent }
}

/// One cancellation-aware background pass over ORIGINAL durable observations.
/// Used by both a live account and a cold BGAppRefresh launch; no UI or fresh
/// read-intent preparation is allowed here. Unconfirmed records stay on disk.
@MainActor
enum PulseReadJournalReplay {
    static func flush(store: PulseStore, owner: String,
                             isCurrent: () -> Bool,
                             send: (PulseQueuedRead) async throws -> Void) async -> Bool {
        do {
            guard !Task.isCancelled, isCurrent() else { return false }
            for read in try store.pendingReads(owner: owner) {
                guard !Task.isCancelled, isCurrent(), read.owner == owner else { return false }
                do {
                    try await send(read)
                    try Task.checkCancellation()
                    guard isCurrent() else { return false }
                    try store.acknowledgeRead(read)
                } catch {
                    guard !Task.isCancelled, isCurrent(),
                          [400, 403, 404, 410, 422].contains((error as? PulseAPIClient.Failure)?.status ?? 0) else { return false }
                    try store.acknowledgeRead(read)
                }
            }
            let empty = try store.pendingReads(owner: owner).isEmpty
            return isCurrent() && empty
        } catch { return false }
    }
}
