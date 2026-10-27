import XCTest
@testable import Pulse

private final class PhraseProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "phrase.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField:"X-Mode") ?? "valid"
        let row: [String:Any] = ["id":"p1","text":mode == "wrong" ? "Wrong" : "Hello","position":0]
        var body: [String:Any] = ["phrase":row,"phrases":[row],"ok":true,"deleted":true,"userId":"me","phraseId":mode == "wrong" ? "other" : "p1"]
        if mode == "empty" { body = [:] }
        if mode == "bare" { body = row }
        client?.urlProtocol(self,didReceive:HTTPURLResponse(url:request.url!,statusCode:200,httpVersion:nil,headerFields:["Content-Type":"application/json"])!,cacheStoragePolicy:.notAllowed)
        client?.urlProtocol(self,didLoad:try! JSONSerialization.data(withJSONObject:body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
@MainActor
final class PhraseAcceptanceTests: XCTestCase {
    func testCreationAndDeletionMustConfirmTheActualPhrase() async throws {
        for action in ["create","delete","list"] {
            for mode in ["valid","empty","bare","wrong"] {
                let expected = mode == "valid" || (mode == "wrong" && action == "list")
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [PhraseProtocol.self]; config.httpAdditionalHeaders = ["X-Mode":mode]
                let session = URLSession(configuration:config); defer { session.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL:URL(string:"https://phrase.test")!,userId:"me",session:session)
                do {
                    switch action {
                    case "create":
                        let phrase = try await api.createQuickPhrase(text:"Hello")
                        XCTAssertEqual(phrase.id,"p1")
                    case "delete": try await api.deleteQuickPhrase("p1")
                    default: _ = try await api.quickPhrases()
                    }
                    XCTAssertTrue(expected,"\(action):\(mode)")
                } catch { XCTAssertFalse(expected,"\(action):\(error)") }
            }
        }
    }
}
