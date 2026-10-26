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
    var boundary: Boundary? { candidates.first { !acknowledged.contains($0) } }
    private(set) var revision: UInt64 = 0
    private var eligibleAt: TimeInterval = .infinity
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
        eligibleAt = .infinity
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
        candidates = next
        restartDwell(now: now)
        return true
    }

    mutating func restartDwell(now: TimeInterval) {
        revision &+= 1
        inFlight = nil
        eligibleAt = boundary == nil ? .infinity : now + Self.dwell
    }

    mutating func deferAttempt(until deadline: TimeInterval) {
        eligibleAt = max(eligibleAt, deadline)
    }

    mutating func begin(roomId: String, now: TimeInterval) -> Request? {
        guard let boundary, boundary.roomId == roomId, !acknowledged.contains(boundary),
              now >= eligibleAt, inFlight == nil else { return nil }
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
struct PulseQueuedRead: Codable, Sendable {
    let owner: String
    let room: String
    let message: String
    let minimumReadAt: String
    let intent: String
    var key: String { "read-journal:v1|\(owner)|\(intent.split(separator: ".").last.map(String.init) ?? intent)|\(message)" }
}
