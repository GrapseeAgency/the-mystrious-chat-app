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
