import XCTest
@testable import Pulse

final class RequestURLTests: XCTestCase {
    func testAlreadyEscapedQueriesAndPathSegmentsAreNotEscapedTwice() throws {
        let url = PulseAPIClient.requestURL(baseURL: URL(string: "https://gateway.test/pulse/")!,
            path: "/api/stories/story%2Fpart?requesterId=me%26peer&q=tea%2Bcoffee%20%F0%9F%98%80")
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.percentEncodedPath, "/pulse/api/stories/story%2Fpart")
        XCTAssertEqual(components.queryItems?.first?.value, "me&peer")
        XCTAssertEqual(components.queryItems?.last?.value, "tea+coffee 😀")
        XCTAssertFalse(url.absoluteString.contains("%2526"))
    }
    func testLiteralPercentValueIsDecodedOnlyOnceAndRawLegacyTextIsSafe() throws {
        let base = URL(string: "https://gateway.test")!
        let encoded = PulseAPIClient.requestURL(baseURL: base, path: "/api/search?q=literal%2526")
        XCTAssertEqual(URLComponents(url: encoded, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, "literal%26")
        let raw = PulseAPIClient.requestURL(baseURL: base, path: "/api/search?q=100% café 😀&userId=u")
        let components = try XCTUnwrap(URLComponents(url: raw, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.queryItems?.first?.value, "100% café 😀")
        XCTAssertEqual(components.queryItems?.count, 2)
    }
    func testBaseQueryAndFragmentCannotBleedIntoAnUnrelatedRequest() {
        let url = PulseAPIClient.requestURL(baseURL: URL(string: "https://gateway.test/pulse/?stale=1#old")!, path: "/api/users")
        XCTAssertEqual(url.absoluteString, "https://gateway.test/pulse/api/users")
    }
}
