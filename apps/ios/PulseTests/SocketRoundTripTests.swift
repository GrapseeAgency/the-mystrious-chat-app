import XCTest
@testable import Pulse

/// Wave 0 — REAL Socket.IO round trip. Spawns the committed node fixture
/// (PulseTests/Fixtures/server.js, `npm install` on first run) as a host
/// process, then drives PulseSocketClient against it:
///   1. connect → join → `joined` ack received
///   2. second participant joins → presence:snapshot contains BOTH ids
///   3. typing emitted from the peer → relay arrives
///   4. POST /notify → message:new envelope arrives (decode parity asserted)
/// Skips honestly when node/npm are missing (the macOS runner has both).
final class SocketRoundTripTests: XCTestCase {
    private let viewerId = "ios-w0-a"
    private let peerId = "peer-b"
    private let conversationId = "conv-rt"

    private var server: Process?

    override func tearDown() {
        Self.stop(server)
        server = nil
        super.tearDown()
    }

    // ── environment discovery ────────────────────────────────

    /// ONE robust location strategy: the fixture lives next to this test
    /// source file, and #filePath is baked in at compile time — valid on the
    /// same machine that runs the tests (CI checks out the repo there).
    private static func fixtureDirectory() -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Fixtures", isDirectory: true)
    }

    private static func locateExecutable(named name: String) -> URL? {
        let candidates = ["/usr/local/bin/\(name)", "/opt/homebrew/bin/\(name)", "/usr/bin/\(name)"]
        for candidate in candidates where FileManager.default.isExecutableFile(atPath: candidate) {
            return URL(fileURLWithPath: candidate)
        }
        let searchPath = ProcessInfo.processInfo.environment["PATH"] ?? "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"
        for segment in searchPath.split(separator: ":") {
            let path = URL(fileURLWithPath: String(segment)).appendingPathComponent(name).path
            if FileManager.default.isExecutableFile(atPath: path) {
                return URL(fileURLWithPath: path)
            }
        }
        return nil
    }

    // ── process plumbing ─────────────────────────────────────

    private static func stop(_ process: Process?) {
        guard let process, process.isRunning else { return }
        process.terminate()
    }

    private static func waitUntilHealthy(port: Int, timeout: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        guard let url = URL(string: "http://127.0.0.1:\(port)/health") else { return false }
        while Date() < deadline {
            var request = URLRequest(url: url)
            request.timeoutInterval = 2
            if let (_, response) = try? await URLSession.shared.data(for: request),
               let http = response as? HTTPURLResponse,
               http.statusCode == 200 {
                return true
            }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return false
    }

    private func installDependencies(npmURL: URL, fixtureDir: URL) async throws {
        let modulesDir = fixtureDir.appendingPathComponent("node_modules/socket.io")
        guard !FileManager.default.fileExists(atPath: modulesDir.path) else { return }

        let install = Process()
        install.executableURL = npmURL
        install.arguments = ["install", "--no-audit", "--no-fund", "--prefix", fixtureDir.path]
        install.currentDirectoryURL = fixtureDir
        install.standardOutput = FileHandle.nullDevice
        install.standardError = FileHandle.nullDevice
        try install.run()
        let deadline = Date().addingTimeInterval(240)
        while install.isRunning && Date() < deadline {
            try await Task.sleep(nanoseconds: 500_000_000)
        }
        Self.stop(install)
        guard !install.isRunning else { throw XCTSkip("npm install exceeded 240s (environmental)") }
        // npm can fail for environmental reasons (offline runner, registry
        // outage) — skip honestly; a HEALTHY install followed by a broken
        // server is still a hard XCTFail below (that would be a fixture bug).
        if !FileManager.default.fileExists(atPath: modulesDir.path) {
            throw XCTSkip("npm install failed in this environment (node_modules/socket.io missing)")
        }
    }

    private func startServer(nodeURL: URL, fixtureDir: URL) async -> (Process, Int)? {
        for _ in 0..<3 {
            let port = Int.random(in: 39_000...59_000)
            let process = Process()
            process.executableURL = nodeURL
            process.arguments = [fixtureDir.appendingPathComponent("server.js").path, String(port)]
            process.standardOutput = FileHandle.nullDevice
            process.standardError = FileHandle.nullDevice
            do {
                try process.run()
            } catch {
                continue
            }
            if await Self.waitUntilHealthy(port: port, timeout: 10) {
                return (process, port)
            }
            Self.stop(process)
        }
        return nil
    }

    // ── the round trip ───────────────────────────────────────

    func testJoinPresenceTypingAndNotifyRoundTrip() async throws {
        guard let nodeURL = Self.locateExecutable(named: "node") else {
            throw XCTSkip("node is not installed on this machine")
        }
        guard let npmURL = Self.locateExecutable(named: "npm") else {
            throw XCTSkip("npm is not installed on this machine")
        }
        let fixtureDir = Self.fixtureDirectory()
        guard FileManager.default.fileExists(atPath: fixtureDir.appendingPathComponent("server.js").path) else {
            throw XCTSkip("socket fixture missing from the source tree")
        }

        try await installDependencies(npmURL: npmURL, fixtureDir: fixtureDir)
        guard let (process, port) = await startServer(nodeURL: nodeURL, fixtureDir: fixtureDir) else {
            XCTFail("pulse-socket fixture never became healthy — server.js may be broken")
            return
        }
        server = process
        defer { Self.stop(server) }

        let base = URL(string: "http://127.0.0.1:\(port)")!

        // 1. Client A — join ack.
        let joinedA = expectation(description: "client A received joined ack")
        let presenceBoth = expectation(description: "presence snapshot contains both ids")
        let typingRelay = expectation(description: "typing relay arrived from peer")
        let notifyArrived = expectation(description: "POST /notify message:new arrived")

        let clientA = PulseSocketClient(socketURL: base)
        // Explicit `self.x` capture expressions — no ambiguity about whether
        // a bare `[prop]` capture-list shorthand resolves instance properties,
        // and the closure retains only the values (not the test case).
        clientA.signals = { [viewerId = self.viewerId, peerId = self.peerId, conversationId = self.conversationId] signal in
            switch signal {
            case .joined(let ids):
                if ids.contains(viewerId) { joinedA.fulfill() }
            case .presenceSnapshot(let ids):
                if ids.contains(viewerId) && ids.contains(peerId) { presenceBoth.fulfill() }
            case .typing(let convId, let userId, _, let isTyping):
                if convId == conversationId && userId == peerId && isTyping { typingRelay.fulfill() }
            case .messageNew(let convId, let raw):
                guard convId == conversationId else { return }
                if let message = PulseSession.decodeMessage(from: raw) {
                    XCTAssertEqual(message.content, "notify-hello")
                    XCTAssertEqual(message.senderId, peerId)
                    notifyArrived.fulfill()
                }
            default:
                break
            }
        }
        clientA.connect(userId: viewerId)
        await fulfillment(of: [joinedA], timeout: 10)

        // 2. Client B — second participant shows up in presence.
        let clientB = PulseSocketClient(socketURL: base)
        clientB.connect(userId: peerId)
        await fulfillment(of: [presenceBoth], timeout: 10)

        // 3. Typing relay B → A.
        clientB.emitTyping(
            recipients: [viewerId],
            conversationId: conversationId,
            userId: peerId,
            userName: "Peer B",
            isTyping: true,
        )
        await fulfillment(of: [typingRelay], timeout: 10)

        // 4. HTTP /notify relay — the exact contract the Next.js API uses.
        let payload: [String: Any] = [
            "event": "message:new",
            "recipients": [viewerId],
            "payload": [
                "type": "message:new",
                "conversationId": conversationId,
                "recipientIds": [viewerId],
                "message": [
                    "id": "m-notify-1",
                    "conversationId": conversationId,
                    "senderId": peerId,
                    "content": "notify-hello",
                    "kind": "text",
                    "createdAt": "2026-09-07T12:26:36.991Z",
                ],
            ],
        ]
        var request = URLRequest(url: base.appendingPathComponent("notify"))
        request.httpMethod = "POST"
        request.timeoutInterval = 6
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        await fulfillment(of: [notifyArrived], timeout: 10)

        clientA.disconnect()
        clientB.disconnect()
        Self.stop(server)
        server = nil
    }
}
