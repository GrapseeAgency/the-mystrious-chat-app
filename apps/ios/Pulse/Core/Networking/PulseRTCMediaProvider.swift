import Foundation
import AVFoundation
import WebRTC

// ─────────────────────────────────────────────────────────────
// Pulse — Wave 3 REAL WebRTC media provider (stasel/WebRTC binary
// distribution, SPM product "WebRTC").
//
// Wave R1-W2D — REAL video: a kind 'video' offer (checked on the WIRE kind
// AND the offer SDP's m=video line) is answered WITH a real camera track
// (RTCCameraVideoCapturer, front camera default) instead of audio-only
// (defect D21 fixed). No usable camera / denied permission ⇒ the audio-only
// fallback keeps the call alive (web call-overlay.tsx parity). The capture
// pipeline itself is a HARDWARE gate (Wave 3-HW stays OPEN).
//
// Audio-only media path (kind 'voice' fully implemented):
//
// Actor-isolation map (kept boring on purpose — no local compile):
//   • PulseCallMediaProviding is @MainActor (engine + provider both live on
//     the main actor).
//   • PulseCallPeerConnecting / PulseCallPeerDelegate are PLAIN protocols —
//     the RTCPeerConnection adapter is a nonisolated NSObject (its Obj-C
//     delegate callbacks fire on WebRTC threads), so actor-qualifying the
//     protocol would poison the conformance.
//   • Every WebRTC callback is converted to plain values FIRST, then hops
//     to the main actor via Task { @MainActor in ... } before touching the
//     delegate (which the engine owns).
// ─────────────────────────────────────────────────────────────

/// One-time SSL bootstrap (process-wide, idempotent).
enum PulseRTCBootstrap {
    static let initialize: Void = {
        _ = RTCInitializeSSL()
    }()
}

/// ICE/PC state as the engine's state machine understands it (plain values —
/// safe to carry across actor boundaries).
public enum PulseCallConnectionState: Equatable, Sendable {
    case connecting
    case connected
    case disconnected
    case failed
    case closed
}

/// Delegate the peer adapter notifies (always invoked ON the main actor).
public protocol PulseCallPeerDelegate: AnyObject {
    /// Trickle ICE — one local candidate per callback (plain values only).
    func peerDidProduceLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int32?)
    /// ICE connection state mapping (see PulseRTCPeerAdapter.mapIceState).
    func peerConnectionStateDidChange(_ state: PulseCallConnectionState)
    /// Remote video track arrived over the negotiated m=video line
    /// (default no-op — only the call engine cares).
    func peerDidReceiveRemoteVideoTrack(_ track: RTCVideoTrack)
}

public extension PulseCallPeerDelegate {
    func peerDidReceiveRemoteVideoTrack(_ track: RTCVideoTrack) {}
}

/// The engine's view of one live peer connection (fake-able in tests).
public protocol PulseCallPeerConnecting: AnyObject {
    func createOffer() async throws -> String
    func createAnswer() async throws -> String
    func setLocalDescription(sdp: String) async throws
    func setRemoteDescription(sdp: String, type: String) async throws
    func addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int32?) async throws
    /// Mute = audio track enabled toggle (web parity: track.enabled).
    func setAudioEnabled(_ enabled: Bool)
    /// Wave R1-W2D — REAL camera path. Creates + attaches a front-camera
    /// RTCCameraVideoCapturer track (1280×720@30 preferred) BEFORE the SDP
    /// dance. Returns false when no usable camera exists → audio-only.
    func enableLocalVideoCapture() -> Bool
    /// Camera (video) toggle — RTCVideoTrack.isEnabled flip (web parity).
    func setVideoEnabled(_ enabled: Bool)
    /// Front ⇄ back camera flip.
    func switchCamera()
    /// The local camera track once capture is live (nil = audio-only).
    func localVideoTrack() -> RTCVideoTrack?
    /// The remote video track once it arrives (nil = not yet / audio-only).
    func remoteVideoTrack() -> RTCVideoTrack?
    func close()
}

/// The engine's view of the media layer: mic/camera permission + peer
/// connection factory (fake-able in tests). The AVAudioSession lifecycle
/// lives in PulseCallAudioSession (engine-owned, MainActor → MainActor).
@MainActor
public protocol PulseCallMediaProviding: AnyObject {
    func requestMicPermission() async -> Bool
    /// Wave R1-W2D — camera permission (requests when .notDetermined).
    func requestCameraPermission() async -> Bool
    /// Sync capability probe: camera authorized AND ≥1 capture device.
    func canCaptureVideo() -> Bool
    /// Peer connection with the local mic track attached (video rides its
    /// own m-line once enableLocalVideoCapture() runs on the connection).
    func makePeerConnection(delegate: (any PulseCallPeerDelegate)?) -> (any PulseCallPeerConnecting)?
}

// ── real provider ────────────────────────────────────────────

@MainActor
public final class PulseRTCMediaProvider: PulseCallMediaProviding {
    private let factory: RTCPeerConnectionFactory

    public init() {
        PulseRTCBootstrap.initialize
        factory = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory(),
        )
    }

    public func requestMicPermission() async -> Bool {
        await PulseCallAudioSession.shared.requestMicPermission()
    }

    /// Camera permission — requests on first use, honest about denial.
    public func requestCameraPermission() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: .video)
        default:
            return false
        }
    }

    /// Sync probe: authorized AND at least one real capture device exists.
    public func canCaptureVideo() -> Bool {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return false }
        return !RTCCameraVideoCapturer.captureDevices().isEmpty
    }

    public func makePeerConnection(delegate: (any PulseCallPeerDelegate)?) -> (any PulseCallPeerConnecting)? {
        PulseRTCPeerAdapter(factory: factory, delegate: delegate)
    }
}

// ── real peer adapter ────────────────────────────────────────

/// Thread-safe weak delegate holder — WebRTC callbacks fire off-main; the
/// box is read there, the main-actor hop happens in the adapter methods.
final class PulseRTCDelegateBox: @unchecked Sendable {
    private let lock = NSLock()
    private weak var delegate: (any PulseCallPeerDelegate)?

    init(_ delegate: (any PulseCallPeerDelegate)?) {
        self.delegate = delegate
    }

    func current() -> (any PulseCallPeerDelegate)? {
        lock.lock()
        defer { lock.unlock() }
        return delegate
    }
}

enum PulseRTCError: Error {
    case sdpFailed
}

/// RTCPeerConnection adapter. Conformance methods are nonisolated by design:
/// they convert WebRTC objects into plain values FIRST, then hop to the main
/// actor before notifying the delegate. Candidate-generation implements BOTH
/// historical selector spellings (didGenerateIceCandidate: modern,
/// didGenerate: legacy) — whichever the pinned framework build declares, one
/// witness matches and neither can fail to compile (delegate members are
/// @optional Obj-C, extra methods are inert).
final class PulseRTCPeerAdapter: NSObject, RTCPeerConnectionDelegate, PulseCallPeerConnecting {
    private let pc: RTCPeerConnection
    private let factory: RTCPeerConnectionFactory
    private let audioTrack: RTCAudioTrack?
    private let box: PulseRTCDelegateBox

    // ── video handles (Wave R1-W2D — REAL camera path) ──
    private var videoSource: RTCVideoSource?
    private var videoCapturer: RTCCameraVideoCapturer?
    private var localVideo: RTCVideoTrack?
    private var remoteVideo: RTCVideoTrack?

    /// The device currently feeding the capturer (camera-flip bookkeeping;
    /// main-actor-confined call flow in practice).
    static var currentDevice: AVCaptureDevice?

    init?(factory: RTCPeerConnectionFactory, delegate: (any PulseCallPeerDelegate)?) {
        box = PulseRTCDelegateBox(delegate)
        self.factory = factory

        let config = RTCConfiguration()
        // ICE: deployment-manifest TURN override (Wave 3-HW) when adopted;
        // otherwise Web STUN parity (call-overlay.tsx STUN_SERVERS).
        if let raw = PulseEndpoints.manifestIceJSON,
           let data = raw.data(using: .utf8),
           let parsed = try? JSONDecoder().decode([PulseIceServer].self, from: data),
           !parsed.isEmpty {
            config.iceServers = parsed.map { ice in
                RTCIceServer(
                    urlStrings: ice.urls,
                    username: ice.username ?? "",
                    credential: ice.credential ?? "",
                )
            }
        } else {
            config.iceServers = [
                RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"]),
            ]
        }
        config.sdpSemantics = .unifiedPlan

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let pc = factory.peerConnection(with: config, constraints: constraints, delegate: nil) else {
            return nil
        }
        self.pc = pc

        // Audio-only: one sendrecv audio transceiver carries the whole call
        // (the offer's m-line is sendrecv, so both sides both send + receive).
        let source = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        let track = factory.audioTrack(with: source, trackId: "pulse-audio0")
        pc.add(track, streamIds: ["pulse-stream0"])
        audioTrack = track

        super.init()
        pc.delegate = self
    }

    // ── PulseCallPeerConnecting ──────────────────────────────

    func createOffer() async throws -> String {
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            pc.offer(for: constraints) { sdp, error in
                if let sdp = sdp?.sdp {
                    continuation.resume(returning: sdp)
                } else {
                    continuation.resume(throwing: error ?? PulseRTCError.sdpFailed)
                }
            }
        }
    }

    func createAnswer() async throws -> String {
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            pc.answer(for: constraints) { sdp, error in
                if let sdp = sdp?.sdp {
                    continuation.resume(returning: sdp)
                } else {
                    continuation.resume(throwing: error ?? PulseRTCError.sdpFailed)
                }
            }
        }
    }

    func setLocalDescription(sdp: String) async throws {
        // The local type follows the negotiation role: no remote offer yet
        // (caller) → local offer; remote offer seen (callee) → local answer.
        let type: RTCSdpType = pc.remoteDescription?.type == .offer ? .answer : .offer
        let description = RTCSessionDescription(type: type, sdp: sdp)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pc.setLocalDescription(description) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func setRemoteDescription(sdp: String, type: String) async throws {
        let sdpType: RTCSdpType = type == "answer" ? .answer : .offer
        let description = RTCSessionDescription(type: sdpType, sdp: sdp)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pc.setRemoteDescription(description) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int32?) async throws {
        // ObjC import order: sdp → sdpMLineIndex → sdpMid.
        let ice = RTCIceCandidate(
            sdp: candidate,
            sdpMLineIndex: Int32(sdpMLineIndex ?? 0),
            sdpMid: sdpMid,
        )
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pc.add(ice) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    func setAudioEnabled(_ enabled: Bool) {
        audioTrack?.isEnabled = enabled
    }

    // ── REAL camera path (Wave R1-W2D) ──────────────────

    /// Creates + attaches a REAL front-camera video track (back camera as
    /// fallback, any device last). MUST run BEFORE the SDP dance so the
    /// unified-plan transceiver associates with the peer's m=video line
    /// (web callee parity: getUserMedia BEFORE setRemoteDescription).
    /// Returns false when no usable camera exists — audio-only fallback.
    func enableLocalVideoCapture() -> Bool {
        if localVideo != nil { return true }
        let devices = RTCCameraVideoCapturer.captureDevices()
        let device = devices.first(where: { $0.position == .front })
            ?? devices.first(where: { $0.position == .back })
            ?? devices.first
        guard let device else { return false }
        Self.currentDevice = device
        guard let format = Self.bestVideoFormat(for: device) else { return false }
        let source = factory.videoSource()
        let capturer = RTCCameraVideoCapturer(delegate: source)
        let track = factory.videoTrack(with: source, trackId: "pulse-video0")
        pc.add(track, streamIds: ["pulse-stream0"])
        localVideo = track
        videoSource = source
        videoCapturer = capturer
        // 30fps ceiling — the web profile is 1280×720 ideal; the chosen
        // format's own frame-rate range is respected when lower.
        // NOTE: RTCCameraVideoCapturer.startCapture takes (device, format, fps)
        // — fps as Int (stasel/WebRTC 125 ObjC bridge).
        let fps = max(1, min(Int(CallVideoConstants.targetFps), Int(Self.fpsCeiling(of: format))))
        capturer.startCapture(with: device, format: format, fps: fps) { error in
            if let error {
                // Async capture failure (device yanked mid-call) — the call
                // stays alive audio/video-black; honest hardware-gate territory.
                NSLog("[call] startCapture failed: %@", error.localizedDescription)
            }
        }
        return true
    }

    func setVideoEnabled(_ enabled: Bool) {
        localVideo?.isEnabled = enabled
    }

    func switchCamera() {
        // RTCCameraVideoCapturer has no switchCamera in this build — flip =
        // stop, then re-start on the opposite-position device (same pipeline).
        guard let capturer = videoCapturer else { return }
        let devices = RTCCameraVideoCapturer.captureDevices()
        let current = Self.currentDevice
        let next = devices.first(where: { $0.position == .front && current?.position != .front })
            ?? devices.first(where: { $0.position == .back && current?.position != .back })
        guard let device = next, let format = Self.bestVideoFormat(for: device) else { return }
        Self.currentDevice = device
        capturer.stopCapture()
        let fps = max(1, min(Int(CallVideoConstants.targetFps), Int(Self.fpsCeiling(of: format))))
        capturer.startCapture(with: device, format: format, fps: fps) { error in
            if let error {
                NSLog("[call] camera flip failed: %@", error.localizedDescription)
            }
        }
    }

    func localVideoTrack() -> RTCVideoTrack? {
        localVideo
    }

    func remoteVideoTrack() -> RTCVideoTrack? {
        remoteVideo
    }

    /// Closest-to-target format (web ideal 1280×720), then closest fps (30).
    /// AVCaptureDevice.Format carries NO single frameRate — the supported
    /// ranges (videoSupportedFrameRateRanges) supply the ceiling.
    private static func bestVideoFormat(for device: AVCaptureDevice) -> AVCaptureDevice.Format? {
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        return formats.min { lhs, rhs in
            let (lw, lh) = dimensions(lhs)
            let (rw, rh) = dimensions(rhs)
            let dl = abs(lw - CallVideoConstants.targetWidth) + abs(lh - CallVideoConstants.targetHeight)
            let dr = abs(rw - CallVideoConstants.targetWidth) + abs(rh - CallVideoConstants.targetHeight)
            if dl != dr { return dl < dr }
            return abs(fpsCeiling(of: lhs) - Double(CallVideoConstants.targetFps))
                < abs(fpsCeiling(of: rhs) - Double(CallVideoConstants.targetFps))
        }
    }

    /// Highest frame rate the format supports (first range wins — all ranges
    /// of a live format share the ceiling in practice).
    private static func fpsCeiling(of format: AVCaptureDevice.Format) -> Double {
        format.videoSupportedFrameRateRanges.first?.maxFrameRate ?? Double(CallVideoConstants.targetFps)
    }

    private static func dimensions(_ format: AVCaptureDevice.Format) -> (Int, Int) {
        // formatDescription is non-optional in the current SDK surface.
        let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        return (Int(dims.width), Int(dims.height))
    }

    func close() {
        pc.delegate = nil
        // Stop the camera BEFORE the pc (capture thread must outlive nothing).
        if let capturer = videoCapturer {
            capturer.stopCapture()
        }
        videoCapturer = nil
        localVideo = nil
        remoteVideo = nil
        videoSource = nil
        pc.close()
    }

    // ── RTCPeerConnectionDelegate (off-main; hop with plain values) ──
    // Signatures verified against the PINNED binary's headers
    // (stasel/WebRTC 125.0.0): the nine pre-@optional methods are REQUIRED
    // and must match the ObjC selectors exactly — that mismatch was the
    // first CI round's conformance failure.

    // REQUIRED: peerConnection:didChangeSignalingState: → Swift drops the
    // RTCSignalingState type suffix from the label.
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        // Signaling churn is not a state-machine input (offer/answer drive it).
    }

    // REQUIRED: peerConnection:didAddStream: (UnifiedPlan — inert)
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}

    // REQUIRED: peerConnection:didRemoveStream: (UnifiedPlan — inert)
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    // REQUIRED: peerConnectionShouldNegotiate:
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {
        // Renegotiation is not part of the Wave 3 flow; the machine's stale
        // timer is the honest backstop if the peer expects a renegotiation.
    }

    // REQUIRED: peerConnection:didChangeIceConnectionState:
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        emitConnectionState(Self.mapIceState(newState))
    }

    // REQUIRED: peerConnection:didChangeIceGatheringState:
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        // Gathering progress carries no machine input (trickle ICE already
        // streams candidates individually).
    }

    // REQUIRED: peerConnection:didGenerateIceCandidate:
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        emitLocalCandidate(candidate)
    }

    // REQUIRED: peerConnection:didRemoveIceCandidates:
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    // REQUIRED: peerConnection:didOpenDataChannel:
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    // ── @optional — the richer UNIFIED_PLAN peer-connection state ──
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        // Same truth as the ICE mapping, idempotent at the state machine
        // (ready-after-ready is a no-op).
        emitConnectionState(Self.mapPcState(newState))
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChangeStandardizedIceConnectionState newState: RTCIceConnectionState) {
        // Covered by didChangeIceConnectionState — ignore to stay idempotent.
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        // UNIFIED_PLAN remote-track intake — video m-lines promote the
        // receiver's track into the render flow (engine publishes it).
        guard transceiver.mediaType == .video else { return }
        guard let track = transceiver.receiver.track as? RTCVideoTrack else { return }
        remoteVideo = track
        let box = self.box
        Task { @MainActor in
            box.current()?.peerDidReceiveRemoteVideoTrack(track)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove receiver: RTCRtpReceiver) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange local: RTCIceCandidate, remoteCandidate remote: RTCIceCandidate) {}

    private func emitLocalCandidate(_ candidate: RTCIceCandidate) {
        let candidateString = candidate.sdp
        let mid = candidate.sdpMid
        let lineIndex = Int32(candidate.sdpMLineIndex)
        let box = self.box
        Task { @MainActor in
            box.current()?.peerDidProduceLocalCandidate(
                candidate: candidateString,
                sdpMid: mid,
                sdpMLineIndex: lineIndex,
            )
        }
    }

    private func emitConnectionState(_ state: PulseCallConnectionState) {
        let box = self.box
        Task { @MainActor in
            box.current()?.peerConnectionStateDidChange(state)
        }
    }

    /// RTCIceConnectionState → engine state (default = still negotiating).
    static func mapIceState(_ state: RTCIceConnectionState) -> PulseCallConnectionState {
        switch state {
        case .connected, .completed: return .connected
        case .disconnected: return .disconnected
        case .failed: return .failed
        case .closed: return .closed
        default: return .connecting
        }
    }

    /// RTCPeerConnectionState → engine state.
    static func mapPcState(_ state: RTCPeerConnectionState) -> PulseCallConnectionState {
        switch state {
        case .connected: return .connected
        case .disconnected: return .disconnected
        case .failed: return .failed
        case .closed: return .closed
        default: return .connecting
        }
    }
}
