import Foundation

/// R105 - the artboard mirror audit gate: ONE device-local UserDefaults
/// flag. The key is the contract MirrorRootView documented since R59
/// ("ui.mirrorAudit"); this round makes the gate real:
///   - Settings > Appearance "Artboard preview" turns it ON,
///   - the mirror's Profile > Settings "Exit artboard preview" row turns
///     it OFF (nobody gets trapped in the preview),
///   - RootView mounts MirrorRootView while it reads true.
/// Device-local on purpose: a per-device preview switch that never syncs
/// to the account. The raw string key stays public here so every writer
/// shares one spelling and the tests pin it.
public enum PulseMirrorAuditGate {
    /// The exact R59-documented UserDefaults key.
    public static let key = "ui.mirrorAudit"
    /// A clean domain reads the native feature shell - the mirror is
    /// always an explicit opt-in, never a surprise default.
    public static let defaultValue = false

    public static func read(from defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: key)
    }

    public static func write(_ value: Bool, to defaults: UserDefaults = .standard) {
        defaults.set(value, forKey: key)
    }
}
