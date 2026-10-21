import Foundation

/// Visibility dwell and response ownership, independent of transport scheduling.
/// `now` is monotonic uptime, never the device's adjustable wall clock.
struct PulseReadReceiptGate {
    struct Boundary: Equatable {
        let roomId: String
        let messageId: String
    }
    struct Request: Equatable {
        let id: UUID
        let revision: UInt64
        let boundary: Boundary
    }
    private(set) var boundary: Boundary?
    private(set) var revision: UInt64 = 0
    private var eligibleAt: TimeInterval = .infinity
    private var acknowledged: Boundary?
    private var inFlight: Request?
    static let dwell: TimeInterval = 0.6

    mutating func reset() {
        boundary = nil
        acknowledged = nil
        eligibleAt = .infinity
        revision &+= 1
        inFlight = nil
    }

    @discardableResult
    mutating func setBoundary(roomId: String, messageId: String?, now: TimeInterval) -> Bool {
        let next = messageId.map { Boundary(roomId: roomId, messageId: $0) }
        guard next != boundary else { return false }
        boundary = next
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
        guard let boundary, boundary.roomId == roomId, boundary != acknowledged,
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
        if succeeded { acknowledged = request.boundary }
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
    private var primary: PulseReadReceiptGate.Boundary?
    private var overlay: PulseReadReceiptGate.Boundary?
    private(set) var owner: Owner?
    var boundary: PulseReadReceiptGate.Boundary? { owner == nil ? primary : overlay }

    mutating func reset() { primary = nil; overlay = nil; owner = nil }
    mutating func setPrimary(roomId: String, messageId: String?) {
        primary = messageId.map { .init(roomId: roomId, messageId: $0) }
    }
    mutating func begin(roomId: String, rootId: String? = nil) -> Owner {
        let next = Owner(roomId: roomId, rootId: rootId)
        owner = next
        overlay = nil
        return next
    }
    mutating func setOverlay(_ token: Owner, messageId: String?) {
        guard owner == token else { return }
        overlay = messageId.map { .init(roomId: token.roomId, messageId: $0) }
    }
    @discardableResult
    mutating func end(_ token: Owner) -> Bool {
        guard owner == token else { return false }
        owner = nil
        overlay = nil
        return true
    }
}
