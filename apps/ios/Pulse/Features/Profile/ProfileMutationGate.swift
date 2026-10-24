import Foundation

/// One mounted editor, one account/session, one mutation at a time. A server
/// write already in flight may finish; stale replies must never replace another
/// account's preferences or silently replace a newer editor operation.
struct ProfileMutationGate {
    struct Owner: Equatable {
        let userId: String
        let session: UUID
    }
    struct Ticket: Equatable {
        let owner: Owner
        fileprivate let id: UUID
    }
    private(set) var owner: Owner?
    private(set) var active: Ticket?

    mutating func bind(_ next: Owner) {
        if owner != next { owner = next; active = nil }
    }
    mutating func begin(current: Owner?) -> Ticket? {
        guard let owner, current == owner, active == nil else { return nil }
        let ticket = Ticket(owner: owner, id: UUID())
        active = ticket
        return ticket
    }
    func accepts(_ ticket: Ticket, current: Owner?) -> Bool {
        owner == ticket.owner && current == ticket.owner && active == ticket
    }
    mutating func finish(_ ticket: Ticket) {
        if active == ticket { active = nil }
    }
    mutating func invalidate() { owner = nil; active = nil }
}

/// Match the gateway's UTF-16 budgets without cutting a grapheme in half.
enum ProfileTextLimit {
    static func clip(_ text: String, maximum: Int) -> String {
        var remaining = max(0, maximum)
        return String(text.prefix { character in
            let count = String(character).utf16.count
            guard count <= remaining else { return false }
            remaining -= count
            return true
        })
    }
}
