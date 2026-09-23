import XCTest
@testable import Pulse

/// Wave 8 — wire tests (platform & hardening): the settings blob decode
/// (GET/PATCH /api/settings), the session-token envelopes ({ user, token }
/// from create + login, 404 error body verbatim), the Bearer attach seam on
/// the API client's request-building path, and the tolerant prefs decode
/// with unknown/missing fields. Pure XCTest — no network, no Keychain.
final class Wave8WireTests: XCTestCase {

    private func decode<T: Decodable>(_ type: T.Type, _ json: String, file: StaticString = #filePath, line: UInt = #line) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    // ── settings blob (GET/PATCH /api/settings → { preferences }) ──

    /// The live server merges defaults over the stored blob, so every key
    /// is present in practice — but the DTO must decode the full shape.
    func testPrefsEnvelopeDecodesFullShape() throws {
        let envelope = try decode(WirePrefsEnvelope.self, """
        {"preferences":{"bubbleRadius":"pill","density":"compact","wallpaper":"aurora","notifPreviews":false,"notifSound":true,"notifVibrate":true,"lastSeenVisible":false,"readReceipts":false,"typingVisible":false,"reducedMotion":true}}
        """)
        let prefs = envelope.preferences
        XCTAssertNotNil(prefs)
        XCTAssertEqual(prefs?.bubbleRadius, "pill")
        XCTAssertEqual(prefs?.density, "compact")
        XCTAssertEqual(prefs?.wallpaper, "aurora")
        XCTAssertEqual(prefs?.notifPreviews, false)
        XCTAssertEqual(prefs?.notifSound, true)
        XCTAssertEqual(prefs?.notifVibrate, true)
        XCTAssertEqual(prefs?.lastSeenVisible, false)
        XCTAssertEqual(prefs?.readReceipts, false)
        XCTAssertEqual(prefs?.typingVisible, false)
        XCTAssertEqual(prefs?.reducedMotion, true)
    }

    /// Tolerance: an empty/absent blob decodes with every field nil (the
    /// client then keeps its local values; PulseWave8Logic clamps defaults).
    func testPrefsEnvelopeToleratesMissingFields() throws {
        let empty = try decode(WirePrefsEnvelope.self, "{}")
        XCTAssertNil(empty.preferences)

        let partial = try decode(WirePrefsEnvelope.self, #"{"preferences":{"wallpaper":"forest"}}"#)
        XCTAssertEqual(partial.preferences?.wallpaper, "forest")
        XCTAssertNil(partial.preferences?.bubbleRadius)
        XCTAssertNil(partial.preferences?.density)
        XCTAssertNil(partial.preferences?.notifPreviews)
        XCTAssertNil(partial.preferences?.reducedMotion)
    }

    /// Unknown fields (a newer server, an optional extension like
    /// 'chat.convThemes') must never fail the decode.
    func testPrefsDecodeIgnoresUnknownFields() throws {
        let prefs = try decode(WirePulsePrefs.self, """
        {"bubbleRadius":"md","density":"cozy","wallpaper":"dusk","unknownFutureField":42,"chat.convThemes":{"c1":{"wallpaper":"rose"}},"notifPreviews":true}
        """)
        XCTAssertEqual(prefs.bubbleRadius, "md")
        XCTAssertEqual(prefs.density, "cozy")
        XCTAssertEqual(prefs.wallpaper, "dusk")
        XCTAssertEqual(prefs.notifPreviews, true)
    }

    /// PATCH body: only the non-nil fields ride { userId, preferences } —
    /// a nil field would clobber the server's stored value on merge.
    func testPatchBodyCarriesOnlySetFields() {
        let single = WirePulsePrefs(notifSound: false)
        let singleBody = single.asPatchBody()
        XCTAssertEqual(singleBody.count, 1)
        XCTAssertEqual(singleBody["notifSound"] as? Bool, false)

        let many = WirePulsePrefs(bubbleRadius: "md", density: "compact", wallpaper: "mono", lastSeenVisible: false, reducedMotion: true)
        let body = many.asPatchBody()
        XCTAssertEqual(body.count, 5)
        XCTAssertEqual(body["bubbleRadius"] as? String, "md")
        XCTAssertEqual(body["density"] as? String, "compact")
        XCTAssertEqual(body["wallpaper"] as? String, "mono")
        XCTAssertEqual(body["lastSeenVisible"] as? Bool, false)
        XCTAssertEqual(body["reducedMotion"] as? Bool, true)

        let none = WirePulsePrefs()
        XCTAssertTrue(none.asPatchBody().isEmpty)
    }

    // ── session tokens (POST /api/users, POST /api/users/login) ──

    /// 201 { user, token } — the raw 32-byte hex token rides the create
    /// envelope and must survive intact (64 hex chars).
    func testCreateEnvelopeDecodesUserAndToken() throws {
        let token = String(repeating: "a1b2c3d4", count: 8) // 64 hex chars
        let envelope = try decode(WireAuthEnvelope.self, """
        {"user":{"id":"u9","name":"Alice Chen","username":"alice_chen","about":null,"color":"emerald","avatar":null,"statusEmoji":null,"statusText":null,"createdAt":"2026-01-05T10:00:00.000Z","lastSeenAt":null,"verified":false},"token":"\(token)"}
        """)
        XCTAssertEqual(envelope.user.id, "u9")
        XCTAssertEqual(envelope.user.name, "Alice Chen")
        XCTAssertEqual(envelope.user.username, "alice_chen")
        XCTAssertEqual(envelope.token?.count, 64)
        XCTAssertEqual(envelope.token, token)
    }

    /// Tolerance: a pre-token gateway (or a server that withholds the raw
    /// secret) omits `token` — the envelope still decodes, token nil.
    func testAuthEnvelopeToleratesMissingToken() throws {
        let envelope = try decode(WireAuthEnvelope.self, #"{"user":{"id":"u1","name":"Bob"}}"#)
        XCTAssertEqual(envelope.user.id, "u1")
        XCTAssertNil(envelope.token)
    }

    /// The honest 404 error body — copy verbatim from the live route:
    /// POST /api/users/login → 404 { error: "No identity with that name
    /// on this Pulse." } (Failure.message surfaces it untouched).
    func testLoginErrorBodyVerbatim() throws {
        let body = try decode(WireErrorBody.self, #"{"error":"No identity with that name on this Pulse."}"#)
        XCTAssertEqual(body.error, "No identity with that name on this Pulse.")
        XCTAssertNil(body.code)

        let missingName = try decode(WireErrorBody.self, #"{"error":"Name is required."}"#)
        XCTAssertEqual(missingName.error, "Name is required.")
    }

    /// 401 rotation rejection body — the typed failure the session layer
    /// reacts to (clear token + surface re-login).
    func testRotationRejectionMapsToAuthKind() {
        XCTAssertEqual(PulseAPIClient.kind(for: 401), .auth)
        let body = try? decode(WireErrorBody.self, #"{"error":"Session token is invalid or has been rotated. Log in again."}"#)
        XCTAssertEqual(body?.error, "Session token is invalid or has been rotated. Log in again.")
    }

    // ── Bearer attach seam (the request-building path) ──────

    /// Every request built by the client with a session token carries
    /// Authorization: Bearer <token> — asserted on the same static seam
    /// the transport funnels through (PulseAPIClient.send).
    func testBearerHeaderAttachedWhenTokenPresent() {
        var request = URLRequest(url: URL(string: "https://pulse.example/api/settings?userId=u1")!)
        request.httpMethod = "GET"
        let authorized = PulseAPIClient.authorized(request, token: String(repeating: "f", count: 64))
        XCTAssertEqual(authorized.value(forHTTPHeaderField: "Authorization"), "Bearer \(String(repeating: "f", count: 64))")

        // The header survives body-building too (POST /api/users/login).
        var post = URLRequest(url: URL(string: "https://pulse.example/api/users/login")!)
        post.httpMethod = "POST"
        post.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let authorizedPost = PulseAPIClient.authorized(post, token: "tok")
        XCTAssertEqual(authorizedPost.value(forHTTPHeaderField: "Authorization"), "Bearer tok")
        XCTAssertEqual(authorizedPost.value(forHTTPHeaderField: "Content-Type"), "application/json")
    }

    /// Token-less clients (onboarding, post-rotation degraded mode, the
    /// optional-verify proxy contract) must NOT attach a bogus header.
    func testBearerHeaderAbsentWithoutToken() {
        var request = URLRequest(url: URL(string: "https://pulse.example/api/users")!)
        request.httpMethod = "GET"

        let noToken = PulseAPIClient.authorized(request, token: nil)
        XCTAssertNil(noToken.value(forHTTPHeaderField: "Authorization"))

        let emptyToken = PulseAPIClient.authorized(request, token: "")
        XCTAssertNil(emptyToken.value(forHTTPHeaderField: "Authorization"))

        // The client structs expose the token they would attach.
        let plain = PulseAPIClient(baseURL: URL(string: "https://pulse.example")!)
        XCTAssertNil(plain.authToken)
        let bound = PulseAPIClient(baseURL: URL(string: "https://pulse.example")!, userId: "u1", authToken: "tok")
        XCTAssertEqual(bound.authToken, "tok")
    }
}
