/// Unknown policy must not expose a room while its owner-specific detail is
/// loading. Both flags must explicitly be off to remove the inactive cover.
enum PulseScreenSecurityPolicy {
    static func requiresProtection(room: Bool?, personal: Bool?) -> Bool {
        room != false || personal != false
    }
    static func shouldCover(room: Bool?, personal: Bool?, active: Bool) -> Bool {
        !active && requiresProtection(room: room, personal: personal)
    }
}
