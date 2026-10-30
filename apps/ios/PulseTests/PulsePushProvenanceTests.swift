import XCTest
@testable import Pulse

/// Section 6 - cross-gateway push provenance + retraction identity kernels.
/// The delegate/background-wake glue (willPresent / didReceiveRemoteNotification)
/// is UIKit lifecycle code CI compiles but cannot execute here; these tests pin
/// the pure halves those paths depend on.
final class PulsePushProvenanceTests: XCTestCase {

    // MARK: - origin gate

    func testUnstampedLegacyPayloadsAreAlwaysAccepted() {
        XCTAssertFalse(PulsePushProvenance.isForeign(stamped: nil, configured: "https://gateway.test"))
        XCTAssertFalse(PulsePushProvenance.isForeign(stamped: "", configured: "https://gateway.test"))
        XCTAssertFalse(PulsePushProvenance.isForeign(stamped: "   ", configured: "https://gateway.test"))
    }

    func testMatchingOriginIsAcceptedWithNormalization() {
        XCTAssertTrue(PulsePushProvenance.isForeign(stamped: "https://gateway.test", configured: nil))
        XCTAssertFalse(PulsePushProvenance.isForeign(stamped: "https://gateway.test", configured: "https://gateway.test"))
        // Trailing slash + case normalization never turns a match into a drop.
        XCTAssertFalse(PulsePushProvenance.isForeign(stamped: "https://Gateway.test/", configured: "https://gateway.test"))
        XCTAssertFalse(PulsePushProvenance.isForeign(stamped: "https://gateway.test", configured: "https://GATEWAY.test/"))
        XCTAssertFalse(PulsePushProvenance.isForeign(stamped: "http://gw.test:3000", configured: "http://gw.test:3000/"))
    }

    func testForeignDeploymentOriginIsDropped() {
        XCTAssertTrue(PulsePushProvenance.isForeign(stamped: "https://other.test", configured: "https://gateway.test"))
        XCTAssertTrue(PulsePushProvenance.isForeign(stamped: "https://gateway.test:8443", configured: "https://gateway.test"))
        XCTAssertTrue(PulsePushProvenance.isForeign(stamped: "http://gateway.test", configured: "https://gateway.test"))
        XCTAssertTrue(PulsePushProvenance.isForeign(stamped: "https://gateway.test", configured: nil))
    }

    // MARK: - retraction identity (APNs collapse id parity)

    func testMessageAlertIdentifierMatchesTheTransportTagFormat() {
        // Byte-identical with the server transport (sendIos apns-collapse-id)
        // and Android's MessageNotificationIdentity.tag:
        // pulse-msg-v1/<encodeURIComponent(recipient)>/<encodeURIComponent(conv)>/<encodeURIComponent(msg)>
        let identifier = PulsePushProvenance.messageAlertIdentifier(
            recipient: "user-1", conversationId: "room 42", messageId: "m/3")
        XCTAssertEqual(identifier,
            "pulse-msg-v1/user-1/room%2042/m%2F3")
    }

    func testJavaScriptEncodeURIComponentParity() {
        // The exact character set JS encodeURIComponent leaves untouched.
        XCTAssertEqual(PulsePushProvenance.encodeURIComponent("abcXYZ019-_.!~*'()"), "abcXYZ019-_.!~*'()")
        XCTAssertEqual(PulsePushProvenance.encodeURIComponent(" "), "%20")
        XCTAssertEqual(PulsePushProvenance.encodeURIComponent("/"), "%2F")
        XCTAssertEqual(PulsePushProvenance.encodeURIComponent("é"), "%C3%A9")
    }

    func testBlankComponentsProduceNoIdentity() {
        XCTAssertNil(PulsePushProvenance.messageAlertIdentifier(recipient: "", conversationId: "room", messageId: "m1"))
        XCTAssertNil(PulsePushProvenance.messageAlertIdentifier(recipient: "u1", conversationId: "  ", messageId: "m1"))
        XCTAssertNil(PulsePushProvenance.messageAlertIdentifier(recipient: "u1", conversationId: "room", messageId: ""))
    }

    // MARK: - APNs environment declaration

    func testDeclaredApnsEnvironmentIsAValidServerValue() {
        // Build-config declaration (DEBUG → sandbox, release → production);
        // the server rejects any value outside {sandbox, production}.
        XCTAssertTrue(["sandbox", "production"].contains(PulsePushRegistrationCenter.declaredApnsEnvironment))
    }
}
