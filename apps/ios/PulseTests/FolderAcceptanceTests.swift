import XCTest
@testable import Pulse

private final class FolderProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "folder.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        let action = request.value(forHTTPHeaderField: "X-Action") ?? "list"
        var row: [String: Any] = ["id":"folder", "name": mode == "wrong" && ["create","update"].contains(action) ? "Other" : "Work",
            "emoji":"folder", "position":0, "conversationIds":mode == "wrong" && action == "members" ? [] : ["room"]]
        if mode == "missing" { row.removeValue(forKey: "conversationIds") }
        var body: [String:Any] = ["folderVersion":mode == "version" ? 0 : 1, "userId":mode == "owner" ? "other" : "me"]
        if mode == "stringVersion" { body["folderVersion"] = "1" }
        if mode != "missing" {
            body["folder"] = row; body["folders"] = mode == "wrong" ? [row,row] : [row]
            body["folderId"] = mode == "wrong" ? "other" : "folder"; body["deleted"] = true; body["ok"] = true
        }
        if mode == "empty" { body = [:] }
        client?.urlProtocol(self,didReceive:HTTPURLResponse(url:request.url!,statusCode:200,httpVersion:nil,headerFields:["Content-Type":"application/json"])!,cacheStoragePolicy:.notAllowed)
        client?.urlProtocol(self,didLoad:try! JSONSerialization.data(withJSONObject:body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
@MainActor
final class FolderAcceptanceTests: XCTestCase {
    func testFoldersRequireOwnedExactReadAndMutationReceipts() async throws {
        for action in ["list","create","update","members","delete"] {
            for mode in ["valid","empty","owner","version","stringVersion","missing","wrong"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [FolderProtocol.self]; config.httpAdditionalHeaders = ["X-Mode":mode,"X-Action":action]
                let session = URLSession(configuration:config); defer { session.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL:URL(string:"https://folder.test")!,userId:"me",session:session)
                do {
                    switch action {
                    case "list": _ = try await api.folderSnapshot()
                    case "create": _ = try await api.createFolder(name:"Work",emoji:"folder")
                    case "update": _ = try await api.updateFolder(id:"folder",name:"Work",emoji:"folder",position:0)
                    case "members": _ = try await api.saveFolderMembership(folderId:"folder",conversationIds:["room"])
                    default: try await api.deleteFolder(id:"folder")
                    }
                    XCTAssertEqual(mode,"valid","\(action):\(mode)")
                } catch { XCTAssertNotEqual(mode,"valid","\(action):\(error)") }
            }
        }
    }
}
