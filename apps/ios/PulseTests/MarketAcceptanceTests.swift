import XCTest
@testable import Pulse

private final class MarketReceiptProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "market-receipt.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        let seller = mode == "seller" ? "" : (mode == "seller-other" || mode == "foreign-sold") ? "other" : "me"
        var row: [String: Any] = ["id":"item", "title":mode == "title" ? "Ignored input" : "Thing",
            "price":mode == "price" ? 0 : mode == "price-mismatch" ? 26 : 25,
            "asset":mode == "asset" ? "GEM" : "PC", "status":mode == "status" ? "reserved" : ["sold-deleted","foreign-sold"].contains(mode) ? "sold" : "open",
            "mine":mode != "mine" && mode != "seller-other" && mode != "foreign-sold", "createdAt":"2026-10-22T00:00:00Z",
            "seller":["id":seller,"name":"Me"]]
        if mode == "stamp" { row.removeValue(forKey:"createdAt") }
        if mode == "buyer" { row["buyer"] = ["id":"other"] }
        var wallet: [String: Any] = ["userId":mode == "wallet-owner" ? "other" : "me", "coins":mode == "negative" ? -1 : 10, "gems":0]
        if mode == "gems" { wallet.removeValue(forKey:"gems") }
        var body: [String: Any] = ["marketVersion":mode == "version" ? 0 : 1, "userId":mode == "owner" ? "other" : "me",
            "listing":row, "listings":mode == "duplicate" ? [row,row] : [row], "listingId":mode == "id" ? "other" : "item",
            "ok":mode != "ok", "wallet":wallet]
        if mode == "empty" { body = [:] }
        client?.urlProtocol(self, didReceive:HTTPURLResponse(url:request.url!, statusCode:200, httpVersion:nil,
            headerFields:["Content-Type":"application/json"])!, cacheStoragePolicy:.notAllowed)
        client?.urlProtocol(self, didLoad:try! JSONSerialization.data(withJSONObject:body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class MarketAcceptanceTests: XCTestCase {
    func testMarketReadsAndWritesCannotAcceptPartialForeignOrUnrelatedSuccess() async throws {
        for action in ["list","create","buy"] {
            var modes = ["valid","empty","version","owner"]
            if action == "buy" { modes += ["id","ok","wallet-owner","negative","gems"] }
            else {
                modes += ["seller","stamp","price","asset","mine","status","buyer"]
                modes += action == "list" ? ["duplicate","sold-deleted","foreign-sold"] : ["title","price-mismatch","seller-other"]
            }
            for mode in modes {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [MarketReceiptProtocol.self]; config.httpAdditionalHeaders = ["X-Mode":mode]
                let transport = URLSession(configuration:config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL:URL(string:"https://market-receipt.test")!, userId:"me", session:transport)
                do {
                    switch action {
                    case "list": _ = try await api.market()
                    case "create": _ = try await api.createListing(title:"Thing", description:nil, price:25)
                    default: _ = try await api.buyListing("item")
                    }
                    XCTAssertTrue(["valid","sold-deleted"].contains(mode),"\(action):\(mode)")
                } catch { XCTAssertFalse(["valid","sold-deleted"].contains(mode),"\(action) valid receipt failed: \(error)") }
            }
        }
    }
}
