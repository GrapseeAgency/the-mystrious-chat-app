import XCTest
@testable import Pulse

/// Section 13 items 1-3 acceptance (iOS data layer): the staged settings
/// write carries the durable server contract - a per-patch clientId (UUID
/// without dashes), a patch-level intentAt and per-field fieldTimes; the
/// SAME identity survives a process restart (a fresh journal over the same
/// suite rebuilds the identical ticket); a `superseded` verdict ADOPTS the
/// server's winning value and retires the local intent so it can never
/// resurface; `replayed: true` confirms like normal; and every new wire
/// field is tolerant of (and tolerated by) pre-Section-13 relays.
@MainActor
final class Section13SettingsDurabilityTests: XCTestCase {
    private final class Stub: URLProtocol {
        static var responses: [String: (Int, [String: Any])] = [:]
        static var bodies: [String] = []
        override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "s13.test" }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            if let data = request.httpBody, let text = String(data: data, encoding: .utf8) {
                Self.bodies.append(text)
            }
            let (status, payload) = Self.responses[request.url!.path] ?? (200, [:])
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
                httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: payload))
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    private func api() -> PulseAPIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        let session = URLSession(configuration: config)
        return PulseAPIClient(baseURL: URL(string: "https://s13.test")!, userId: "me", session: session)
    }

    override func setUp() {
        super.setUp()
        Stub.responses = [:]; Stub.bodies = []
    }

    private func freshJournal(_ name: String) -> (journal: PulseSettingsJournal, defaults: UserDefaults) {
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return (PulseSettingsJournal(defaults: defaults), defaults)
    }

    // MARK: ticket identity + stamps

    func testStageMintsDurableIdentityAndPerFieldStamps() throws {
        let (journal, _) = freshJournal("s13-durability-" + UUID().uuidString)
        let before = Date()
        let ticket = try journal.stage(WirePulsePrefs(readReceipts: false, density: "compact"), owner: "me")
        XCTAssertEqual(ticket.clientId?.count, 32, "the replay identity is 128 bits of hex")
        XCTAssertFalse(ticket.clientId?.contains("-") ?? true, "URL-safe: no dashes")
        XCTAssertNotNil(ticket.intentAt)
        XCTAssertNotNil(PulseFormat.date(ticket.intentAt), "intentAt is an ISO stamp")
        XCTAssertEqual(Set(ticket.fieldTimes.keys), Set(["readReceipts", "density"]))
        for (_, stamp) in ticket.fieldTimes {
            let at = try XCTUnwrap(PulseFormat.date(stamp))
            XCTAssertGreaterThanOrEqual(at.timeIntervalSince1970, before.timeIntervalSince1970 - 1)
        }
    }

    func testDurableIdentitySurvivesARestartAndReplaysTheSameClientId() throws {
        let suite = "s13-durability-" + UUID().uuidString
        let (journal, defaults) = freshJournal(suite)
        let staged = try journal.stage(WirePulsePrefs(readReceipts: false), owner: "me")
        // "Restart": a brand-new journal over the same suite rebuilds the
        // SAME identity + instructions - a post-crash replay converges on the
        // server's stored operation instead of minting a second one.
        let revived = PulseSettingsJournal(defaults: defaults)
        let pending = try revived.pendingTicket(owner: "me")
        XCTAssertEqual(pending.clientId, staged.clientId)
        XCTAssertEqual(pending.patch.readReceipts, false)
        XCTAssertEqual(pending.fieldTimes, staged.fieldTimes)
        XCTAssertNotNil(pending.intentAt)
    }

    func testMixedPendingStagesGoOutIdentitylessInsteadOfRiskingA409() throws {
        let (journal, defaults) = freshJournal("s13-durability-" + UUID().uuidString)
        _ = try journal.stage(WirePulsePrefs(readReceipts: false), owner: "me")
        _ = try journal.stage(WirePulsePrefs(density: "compact"), owner: "me")
        let pending = try PulseSettingsJournal(defaults: defaults).pendingTicket(owner: "me")
        // Two stages = two stored operations. The merged retry patch is not
        // the instruction set either clientId stored, so it must not reuse
        // one - the honest legacy shape goes out instead (no fabricated
        // durability).
        XCTAssertNil(pending.clientId)
        XCTAssertNotNil(pending.patch.readReceipts)
        XCTAssertNotNil(pending.patch.density)
    }

    func testOldJournalBlobWithoutIdentityStillDecodesAndConfirms() throws {
        let suite = "s13-durability-" + UUID().uuidString
        let (journal, defaults) = freshJournal(suite)
        let staged = try journal.stage(WirePulsePrefs(readReceipts: false), owner: "me")
        let key = "pulse.settings.pending.v1.me"
        // Simulate a pre-Section-13 blob: strip the new keys from the stored
        // JSON - old builds wrote neither clientId nor stagedAt.
        let raw = try XCTUnwrap(defaults.data(forKey: key))
        var blob = try XCTUnwrap(try JSONSerialization.jsonObject(with: raw) as? [String: [String: Any]])
        for (_, entry) in blob { XCTAssertNotNil(entry["clientId"]) }
        blob["readReceipts"]?.removeValue(forKey: "clientId")
        blob["readReceipts"]?.removeValue(forKey: "stagedAt")
        defaults.set(try JSONSerialization.data(withJSONObject: blob), forKey: key)
        let revived = PulseSettingsJournal(defaults: defaults)
        let pending = try revived.pendingTicket(owner: "me")
        XCTAssertNil(pending.clientId, "legacy blobs carry no identity - the legacy funnel applies")
        XCTAssertEqual(pending.patch.readReceipts, false)
        // And the legacy acknowledge still retires the intent end to end.
        var applied = WirePulsePrefs()
        try revived.acknowledge(pending, sent: pending.patch, server: WirePulsePrefs(readReceipts: false)) { applied = $0 }
        XCTAssertEqual(applied.readReceipts, false)
        XCTAssertFalse(try revived.hasPending(owner: "me"))
        _ = staged
    }

    // MARK: superseded (lost LWW) adoption

    func testSupersededFieldsAdoptServerTruthAndNeverResurface() throws {
        let (journal, _) = freshJournal("s13-durability-" + UUID().uuidString)
        let ticket = try journal.stage(WirePulsePrefs(readReceipts: false, density: "compact"), owner: "me")
        // The server applied density but readReceipts LOST the LWW race
        // against another device's newer intent: preferences carries the
        // winning value (true) and superseded names the field.
        var applied = WirePulsePrefs()
        try journal.acknowledge(ticket, sent: ticket.patch,
            server: WirePulsePrefs(readReceipts: true, density: "compact"),
            superseded: ["readReceipts"]) { applied = $0 }
        XCTAssertEqual(applied.density, "compact", "the applied field confirms normally")
        XCTAssertEqual(applied.readReceipts, true, "the server's winning value is ADOPTED")
        XCTAssertFalse(try journal.hasPending(owner: "me"), "the lost intent retires - it cannot resurface")
        // A restore/retry finds nothing: applySnapshot must not re-apply it.
        var overlaid = WirePulsePrefs()
        try journal.applySnapshot(WirePulsePrefs(), owner: "me") { overlaid = $0 }
        XCTAssertNil(overlaid.readReceipts)
    }

    func testSupersededForeignTokensDoNotTouchANewerLocalIntent() throws {
        let (journal, _) = freshJournal("s13-durability-" + UUID().uuidString)
        let ticket = try journal.stage(WirePulsePrefs(readReceipts: false), owner: "me")
        // A newer local stage replaces the pending token before the reply
        // lands: the superseded verdict for the OLD token must not retire it.
        _ = try journal.stage(WirePulsePrefs(readReceipts: true), owner: "me")
        var applied = WirePulsePrefs()
        try journal.acknowledge(ticket, sent: ticket.patch,
            server: WirePulsePrefs(readReceipts: true), superseded: ["readReceipts"]) { applied = $0 }
        XCTAssertTrue(try journal.hasPending(owner: "me"))
        let current = try journal.pendingTicket(owner: "me")
        XCTAssertEqual(current.patch.readReceipts, true, "the newer local intent survives")
    }

    // MARK: wire shape + tolerant receipts

    func testDurablePatchCarriesIdentityAtTheBodyRootAndAcceptsTheReceipt() async throws {
        Stub.responses["/api/settings"] = (200, ["preferences": ["readReceipts": false],
            "revision": 41, "applied": ["readReceipts"], "superseded": [], "replayed": false])
        let receipt = try await api().updateSettingsDurable(patch: WirePulsePrefs(readReceipts: false),
            clientId: "client000000000000001", intentAt: "2026-02-01T10:00:00Z",
            fieldTimes: ["readReceipts": "2026-02-01T10:00:00Z"])
        XCTAssertEqual(receipt.revision, 41)
        XCTAssertEqual(receipt.applied, ["readReceipts"])
        XCTAssertEqual(receipt.replayed, false)
        let body = try XCTUnwrap(Stub.bodies.last)
        XCTAssertTrue(body.contains("\"clientId\":\"client000000000000001\""), "the identity rides the BODY ROOT")
        XCTAssertTrue(body.contains("\"intentAt\":\"2026-02-01T10:00:00Z\""))
        XCTAssertTrue(body.contains("fieldTimes"), "per-field stamps ride the body root")
        XCTAssertTrue(body.contains("\"preferences\":{"), "the patch stays inside preferences")
    }

    func testReplayedReceiptConfirmsLikeNormal() async throws {
        Stub.responses["/api/settings"] = (200, ["preferences": ["readReceipts": false],
            "revision": 42, "applied": ["readReceipts"], "superseded": [], "replayed": true])
        let receipt = try await api().updateSettingsDurable(patch: WirePulsePrefs(readReceipts: false),
            clientId: "client000000000000002")
        XCTAssertEqual(receipt.replayed, true)
        XCTAssertEqual(receipt.preferences?.readReceipts, false)
    }

    func testLegacyServerWithoutNewFieldsStillDecodes() async throws {
        // A pre-Section-13 relay answers plain { preferences } - every new
        // member decodes nil and the funnel confirms like it always did.
        Stub.responses["/api/settings"] = (200, ["preferences": ["readReceipts": false]])
        let receipt = try await api().updateSettingsDurable(patch: WirePulsePrefs(readReceipts: false),
            clientId: "client000000000000003")
        XCTAssertEqual(receipt.preferences?.readReceipts, false)
        XCTAssertNil(receipt.revision); XCTAssertNil(receipt.applied)
        XCTAssertNil(receipt.superseded); XCTAssertNil(receipt.replayed)
    }

    func testSettingsReadCarriesRevisionAndFieldStampsTolerantly() async throws {
        Stub.responses["/api/settings"] = (200, ["preferences": ["density": "compact"], "revision": 7,
            "fieldStamps": ["density": ["rev": 7, "intentAt": "2026-02-01T10:00:00Z", "writer": "web-1"]]])
        let envelope = try await api().settingsRead()
        XCTAssertEqual(envelope.revision, 7)
        XCTAssertEqual(envelope.fieldStamps?["density"]?.writer, "web-1")
        // Old relay: everything new decodes nil.
        Stub.responses["/api/settings"] = (200, ["preferences": ["density": "cozy"]])
        let legacy = try await api().settingsRead()
        XCTAssertNil(legacy.revision); XCTAssertNil(legacy.fieldStamps)
    }

    // MARK: prefs-level funnel

    func testPrefsAcknowledgeStoresRevisionAndAdoptsSuperseded() throws {
        let suite = "s13-durability-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "me", name: "Me"))
        var tickets: [PulseSettingsTicket] = []
        prefs.patchRemote = { tickets.append($0) }
        prefs.setReadReceipts(false)
        let ticket = try XCTUnwrap(tickets.last)
        try prefs.acknowledgeSettings(ticket, sent: ticket.patch,
            server: WirePulsePrefs(readReceipts: true), superseded: ["readReceipts"], revision: 9)
        XCTAssertEqual(prefs.readReceipts, true, "server truth adopted")
        XCTAssertEqual(prefs.settingsRevision, 9)
        XCTAssertFalse(prefs.pendingSettingsExist)
    }
}
