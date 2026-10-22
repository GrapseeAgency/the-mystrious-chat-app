import XCTest
@testable import Pulse

private final class ConversationCreationProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "creation.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var bytes = request.httpBody ?? Data()
        if bytes.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            while true {
                let count = stream.read(&buffer, maxLength: 1024)
                if count <= 0 { break }
                bytes.append(contentsOf: buffer.prefix(count))
            }
        }
        let body = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any] ?? [:]
        let id = body["clientId"] as? String ?? ""
        let mode = request.value(forHTTPHeaderField: "X-Creation-Mode") ?? "valid"
        let isChannel = request.url?.path == "/api/channels/create-v2"
        let validPath = isChannel || request.url?.path == "/api/conversations/create-v2"
        let members = isChannel || mode == "replay" ? ["me"] : ["me", "a", "b"]
        let room: [String: Any] = ["id": "room-" + id, "isGroup": true, "broadcastMode": isChannel,
            "members": members.map { ["id": $0, "name": $0] }]
        var result: [String: Any] = ["clientId": mode == "bad-receipt" ? "wrong" : id,
            "replayed": mode == "replay", "conversation": room]
        if isChannel {
            result["channel"] = ["id": "room-" + id, "conversationId": "room-" + id,
                "name": "Autumn", "isSubscribed": true, "memberCount": 1]
        }
        let status = validPath && !id.isEmpty && mode != "unsupported" ? 200 : 404
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: result))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class ConversationCreationReceiptTests: XCTestCase {
    private func transport(_ mode: String) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ConversationCreationProtocol.self]
        config.httpAdditionalHeaders = ["X-Creation-Mode": mode]
        return URLSession(configuration: config)
    }
    private func client(_ transport: URLSession, directory: URL) -> PulseAPIClient {
        PulseAPIClient(baseURL: URL(string: "https://creation.test")!, userId: "me", session: transport,
            creationOperations: PulseWalletOperations(directory: directory))
    }
    private func savedID(_ directory: URL) throws -> String {
        let file = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil).first)
        return try String(contentsOf: file, encoding: .utf8)
    }

    func testGroupReceiptSurvivesReopenAndOnlyAcceptedIdentityRetiresIt() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let bad = transport("bad-receipt"), good = transport("replay")
        defer { bad.invalidateAndCancel(); good.invalidateAndCancel() }
        do {
            _ = try await client(bad, directory: directory).createConversation(memberIds: ["a", "b"], isGroup: true, name: "Autumn")
            XCTFail("Mismatched acknowledgement must remain pending")
        } catch {}
        let pending = try savedID(directory)
        let api = client(good, directory: directory)
        let recovered = try await api.createConversation(memberIds: ["b", "a"], isGroup: true, name: " Autumn ")
        XCTAssertEqual(recovered.creationClientId, pending)
        // Replayed membership can have changed since the original confirmed creation.
        let navigable = try await PulseConversationNavigation.create(viewerId: "me", memberIds: ["a", "b"], isGroup: true) { recovered }
        XCTAssertEqual(navigable.id, recovered.id)
        XCTAssertEqual(try savedID(directory), pending)
        try await api.acknowledgeGroupCreation(memberIds: ["a", "b"], name: "Autumn", receipt: recovered)
        let deliberate = try await api.createConversation(memberIds: ["a", "b"], isGroup: true, name: "Autumn")
        XCTAssertNotEqual(deliberate.creationClientId, pending)
        try await api.acknowledgeGroupCreation(memberIds: ["a", "b"], name: "Autumn", receipt: recovered)
        XCTAssertEqual(try savedID(directory), deliberate.creationClientId)
    }

    func testChannelReceiptsStayPendingUntilHistoryIsAcceptedAndNeverDowngrade() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let old = transport("unsupported"), good = transport("valid")
        defer { old.invalidateAndCancel(); good.invalidateAndCancel() }
        do {
            _ = try await client(old, directory: directory).createChannel(name: "Autumn", description: "", photoPath: nil)
            XCTFail("An old gateway must refuse rather than silently create without a receipt")
        } catch let failure as PulseAPIClient.Failure { XCTAssertEqual(failure.status, 404) }
        let pending = try savedID(directory)
        let api = client(good, directory: directory)
        let channel = try await api.createChannel(name: "Autumn", description: "", photoPath: nil)
        XCTAssertEqual(channel.creationClientId, pending)
        XCTAssertEqual(channel.creationConversation?.id, channel.id)
        XCTAssertEqual(try savedID(directory), pending)
        try await api.acknowledgeChannelCreation(name: "Autumn", description: "", photoPath: nil, receipt: channel)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
    }
}
