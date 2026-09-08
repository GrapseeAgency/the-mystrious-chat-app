import Foundation

/// Gateway endpoints — iOS mirror of Android `PulseEndpoints`.
/// Sandbox defaults match the monorepo dev topology: Next.js gateway on :81,
/// Socket.IO relay on :3003 (the simulator reaches the host via localhost).
/// ATS: project.yml declares NSAllowsLocalNetworking so plain http to the
/// local gateway is allowed.
public enum PulseEndpoints {
    /// REST gateway (Next.js API routes).
    public static var gatewayURL: URL { URL(string: "http://localhost:81")! }
    /// Socket.IO relay (mini-services/pulse-socket).
    public static var socketURL: URL { URL(string: "http://localhost:3003")! }
}
