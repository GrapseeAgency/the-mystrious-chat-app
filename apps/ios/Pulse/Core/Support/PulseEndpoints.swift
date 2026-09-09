import Foundation

/// Gateway endpoints — iOS mirror of Android `PulseEndpoints`.
/// The default base is the repo's public HTTPS CDN: reachable from ANY device
/// (the old `http://localhost:81` default only ever worked on the simulator).
/// Bake a real gateway by swapping these constants at build time.
public enum PulseEndpoints {
    /// REST gateway base (Next.js API routes through the gateway).
    public static var gatewayURL: URL {
        URL(string: "https://raw.githubusercontent.com/GrapseeAgency/the-mystrious-chat-app/main")!
    }

    /// Socket.IO relay — nil = realtime disabled (offline-first client, zero
    /// reconnect spam against a dead address). Set a live relay URL here once
    /// one is deployed.
    public static var socketURL: URL? { nil }
}
