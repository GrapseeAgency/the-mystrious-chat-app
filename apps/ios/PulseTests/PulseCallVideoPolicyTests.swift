import XCTest
@testable import Pulse

/// Wave R1-W2D — the PURE video decision contract (web useCallSession parity,
/// src/components/chat/call-overlay.tsx + src/lib/call-types.ts):
///   • the wire video flag is the call's `kind` ("voice" | "video");
///   • a wanted video call degrades to voice BEFORE the offer without a
///     camera (acquireMedia NotFound/Overconstrained/NotReadable fallback);
///   • the callee only opens the camera when the offer SDP really declares a
///     usable m=video line.
/// Pure Foundation — no WebRTC, no device hardware (capture stays Wave 3-HW).
final class PulseCallVideoPolicyTests: XCTestCase {

    // ── PulseCallSdp.hasVideo ────────────────────────────────────

    private let audioOnlySdp =
        "v=0\r\no=- 46117317 2 IN IP4 127.0.0.1\r\n" +
        "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
        "a=sendrecv\r\na=mid:0\r\n"

    private let videoSdp =
        "v=0\r\no=- 46117317 2 IN IP4 127.0.0.1\r\n" +
        "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
        "a=sendrecv\r\na=mid:0\r\n" +
        "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
        "a=sendrecv\r\na=mid:1\r\n"

    func testAudioOnlyOfferHasNoVideoLine() {
        XCTAssertFalse(PulseCallSdp.hasVideo(audioOnlySdp))
    }

    func testOfferWithActiveVideoMLineIsDetected() {
        let lines = videoSdp.split(omittingEmptySubsequences: true, whereSeparator: { $0 == "\r" || $0 == "\n" })
        let vline = lines.first { $0.hasPrefix("m=video") }
        let toks = vline.map { $0.split(separator: " ") } ?? []
        let vlineText: String = vline.map(String.init) ?? "none"
        let toksText: String = toks.map(String.init).joined(separator: "|")
        let eq: Bool = toks.first == "m=video"
        let result: Bool = PulseCallSdp.hasVideo(videoSdp)
        let message = "DIAG lines=" + String(lines.count)
            + " vline=" + vlineText
            + " toks=" + toksText
            + " eq=" + String(eq)
            + " hasVideo=" + String(result)
        XCTAssertTrue(result, message)
    }

    func testRejectedVideoMLinePortZeroIsNotVideo() {
        let rejected = videoSdp.replacingOccurrences(of: "m=video 9 ", with: "m=video 0 ")
        XCTAssertFalse(PulseCallSdp.hasVideo(rejected))
    }

    func testInactiveVideoMLineIsNotUsableVideo() {
        let paused = videoSdp.replacingOccurrences(of: "a=mid:1", with: "a=inactive\r\na=mid:1")
        XCTAssertFalse(PulseCallSdp.hasVideo(paused))
    }

    func testLFOnlyLineEndingsParseTheSameAsCRLF() {
        XCTAssertTrue(PulseCallSdp.hasVideo(videoSdp.replacingOccurrences(of: "\r\n", with: "\n")))
    }

    func testNilEmptyAndGarbageSdpsAreHonestlyVideoFree() {
        XCTAssertFalse(PulseCallSdp.hasVideo(nil))
        XCTAssertFalse(PulseCallSdp.hasVideo(""))
        XCTAssertFalse(PulseCallSdp.hasVideo("   "))
        XCTAssertFalse(PulseCallSdp.hasVideo("v=0\r\nbroken-line"))
    }

    // ── kind resolution (web acquireMedia fallback) ─────────────

    func testWantedVideoWithNoCameraDegradesToVoice() {
        XCTAssertEqual(.voice, PulseCallVideoPolicy.resolveOutgoingKind(.video, cameraCapable: false))
    }

    func testWantedVideoWithCameraStaysVideo() {
        XCTAssertEqual(.video, PulseCallVideoPolicy.resolveOutgoingKind(.video, cameraCapable: true))
    }

    func testVoiceCallsNeverBecomeVideoAndNeverDegrade() {
        XCTAssertEqual(.voice, PulseCallVideoPolicy.resolveOutgoingKind(.voice, cameraCapable: false))
        XCTAssertEqual(.voice, PulseCallVideoPolicy.resolveOutgoingKind(.voice, cameraCapable: true))
    }

    // ── shouldAttachVideo — the callee/caller camera gate ───────

    func testCalleeAttachesVideoOnlyForVideoKindAndUsableMLineAndCamera() {
        let hasVideo: Bool = PulseCallSdp.hasVideo(videoSdp)
        let lfHasVideo: Bool = PulseCallSdp.hasVideo(videoSdp.replacingOccurrences(of: "\r\n", with: "\n"))
        let kindVideo: Bool = CallKind.video == .video
        let result: Bool = PulseCallVideoPolicy.shouldAttachVideo(kind: .video, offerSdp: videoSdp, cameraCapable: true)
        let message = "DIAG kindVideo=" + String(kindVideo)
            + " hasVideo=" + String(hasVideo)
            + " lfHasVideo=" + String(lfHasVideo)
        XCTAssertTrue(result, message)
    }

    func testCalleeWithNoCameraStaysAudioOnlyEvenForVideoOffers() {
        XCTAssertFalse(
            PulseCallVideoPolicy.shouldAttachVideo(kind: .video, offerSdp: videoSdp, cameraCapable: false)
        )
    }

    func testCalleeDoesNotOpenTheCameraForAudioOnlyOffers() {
        XCTAssertFalse(
            PulseCallVideoPolicy.shouldAttachVideo(kind: .voice, offerSdp: audioOnlySdp, cameraCapable: true)
        )
    }

    func testWireVideoWithNoUsableVideoMLineKeepsTheCameraClosed() {
        // Caller fell back to voice after dispatching kind 'video' — the
        // honest callee answer keeps its camera closed.
        XCTAssertFalse(
            PulseCallVideoPolicy.shouldAttachVideo(kind: .video, offerSdp: audioOnlySdp, cameraCapable: true)
        )
        XCTAssertFalse(
            PulseCallVideoPolicy.shouldAttachVideo(kind: .video, offerSdp: nil, cameraCapable: true)
        )
    }
}
