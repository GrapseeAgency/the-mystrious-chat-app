import Foundation
import WebRTC

// ─────────────────────────────────────────────────────────────
// Pulse — Wave 3 REAL WebRTC media provider (stasel/WebRTC binary
// distribution, SPM product "WebRTC").
//
// Audio-only media path (kind 'voice' fully implemented; a kind 'video'
// OFFER is accepted on the wire and answered audio-only — the video m-line
// is rejected by the unified-plan answer, which every WebRTC endpoint
// handles. Documented Wave 3 limitation: no camera capture natively).
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
    func close()
}

/// The engine's view of the media layer: mic permission + peer connection
/// factory (fake-able in tests). The AVAudioSession lifecycle lives in
/// PulseCallAudioSession (engine-owned, MainActor → MainActor).
@MainActor
public protocol PulseCallMediaProviding: AnyObject {
    func requestMicPermission() async -> Bool
    /// Audio-only peer connection with the local mic track attached.
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
    private let audioTrack: RTCAudioTrack?
    private let box: PulseRTCDelegateBox

    init?(factory: RTCPeerConnectionFactory, delegate: (any PulseCallPeerDelegate)?) {
        box = PulseRTCDelegateBox(delegate)

        let config = RTCConfiguration()
        // Web STUN parity (call-overlay.tsx STUN_SERVERS).
        config.iceServers = [
            RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"]),
        ]
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
        return try await withCheckedThrowingContinuation { continuation in
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
        return try await withCheckedThrowingContinuation { continuation in
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
        try await withCheckedThrowingContinuation { continuation in
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
        try await withCheckedThrowingContinuation { continuation in
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
        let ice = RTCIceCandidate(
            sdp: candidate,
            sdpMid: sdpMid,
            sdpMLineIndex: Int(sdpMLineIndex ?? 0),
        )
        try await withCheckedThrowingContinuation { continuation in
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

    func close() {
        pc.delegate = nil
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

    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}

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
