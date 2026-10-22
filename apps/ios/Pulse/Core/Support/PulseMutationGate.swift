import Foundation

/// Single-flight UI writes. A retired task cannot publish or release a successor.
struct PulseMutationGate {
    struct Request: Equatable {
        let id: UUID
        let owner: UUID
        let resource: String
    }
    private var active: Request?
    var isBusy: Bool { active != nil }

    mutating func begin(owner: UUID, resource: String) -> Request? {
        guard active == nil else { return nil }
        let request = Request(id: UUID(), owner: owner, resource: resource)
        active = request
        return request
    }
    func isCurrent(_ request: Request, owner: UUID, resource: String) -> Bool {
        active == request && request.owner == owner && request.resource == resource
    }
    @discardableResult
    mutating func finish(_ request: Request) -> Bool {
        guard active == request else { return false }
        active = nil
        return true
    }
    mutating func cancel() { active = nil }
}
