import Foundation

/// In-process exclusion for audio-session configuration. An old teardown must
/// not restore/deactivate a newer owner's session. OS interruptions are separate.
final class PulseAudioOwnership {
    static let shared = PulseAudioOwnership()
    private let lock = NSLock()
    private var owner: UUID?

    func acquire(_ token: UUID) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard owner == nil || owner == token else { return false }
        owner = token
        return true
    }
    func release(_ token: UUID) {
        lock.lock(); defer { lock.unlock() }
        if owner == token { owner = nil }
    }
}
