/// Unknown policy must not expose a room while its owner-specific detail is
/// loading. Both flags must explicitly be off to remove the inactive cover.
enum PulseScreenSecurityPolicy {
    static func shouldCover(room: Bool?, personal: Bool?, active: Bool) -> Bool {
        !active && (room != false || personal != false)
    }
}
