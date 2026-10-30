import Foundation
import AVFoundation

// Pulse - W5-f voice room audio engine (AVFoundation).
//
// Capture (VR-4): AVAudioEngine input tap (bus 0, 1024 frames) →
// AVAudioConverter → 16 kHz mono Int16 → VoicePcmChunker (the pure
// machine - WEB DEFECT FIX #1: this IS the armed capture path; every
// gated sample flows into it and leaves as a voice:chunk payload).
//
// Playback (VR-5): one AVAudioPlayerNode per peer; a fresh node is
// primed with a short 85 ms silent buffer (the jitter pre-roll) and
// real buffers are chained right behind it; later chunks schedule at
// the playhead time from VoiceRoomModel.decide (dropped chunks never
// reach the engine). Roster drops reset the peer node + bookkeeping
// (FIX #2 seam: VoiceRoomModel.resetPeer + engine.resetPeer).
//
// The software gate (transmitting && !muted, VR-6) is checked inside
// the tap - muted/late means samples never enter the chunker.
//
// Audio session: a DEDICATED slim wrapper (playAndRecord + voiceChat
// + defaultToSpeaker + allowBluetooth, saving/restoring the prior
// category) - PulseCallAudioSession is NOT modified and not shared
// (a call and a voice room never own the session at once; the rooms
// wrapper only touches the session while a room is joined).
//
// Hardware honesty (spec §3): capture/playback quality, route
// switching and background behaviour are PHYSICAL DEVICE: PENDING -
// the simulator answers these APIs with defaults.

/// Slim per-room AVAudioSession owner - save/restore mirrors the
/// verified PulseCallAudioSession pattern (PulseCallAudioSession.swift
/// L51-86) without touching that type.
final class VoiceRoomAudioSession {
    private struct Saved {
        let category: AVAudioSession.Category
        let options: AVAudioSession.CategoryOptions
        let mode: AVAudioSession.Mode
    }

    private var saved: Saved?
    private let ownershipToken = UUID()
    private var recordingMode = false
    private(set) var isActive = false

    /// R56 (section 7, item 11): OS-interruption parity with Android's
    /// focus-loss policy - the OS taking the session (phone call, Siri,
    /// alarm) is surfaced to the owner, which leaves the room honestly.
    /// Fired on the MAIN thread for interruption type .began only.
    var onInterruption: (() -> Void)?
    private var interruptionObserver: NSObjectProtocol?

    init() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let info = notification.userInfo,
                  let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw),
                  type == .began else { return }
            self?.onInterruption?()
        }
    }

    deinit {
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
    }

    /// Snapshot + apply the rooms configuration. Returns false when the
    /// hardware refuses (honest degradation - the room still joins).
    func activate(recording: Bool = true) -> Bool {
        guard PulseAudioOwnership.shared.acquire(ownershipToken) else { return false }
        if isActive && recordingMode == recording { return true }
        let session = AVAudioSession.sharedInstance()
        if saved == nil { saved = Saved(category: session.category, options: session.categoryOptions, mode: session.mode) }
        do {
            try session.setCategory(
                recording ? .playAndRecord : .playback,
                mode: recording ? .voiceChat : .default,
                options: recording ? [.allowBluetooth, .defaultToSpeaker] : [],
            )
            try session.setActive(true)
            isActive = true
            recordingMode = recording
            return true
        } catch {
            deactivate() // roll back a category change even if setActive failed
            return false
        }
    }

    /// Restore the pre-room category and let other audio resume.
    func deactivate() {
        // Never deactivate another audio owner's session if this wrapper never acquired it.
        guard saved != nil || isActive else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(.none)
        if let saved {
            try? session.setCategory(saved.category, mode: saved.mode, options: saved.options)
        }
        try? session.setActive(false, options: [.notifyOthersOnDeactivation])
        saved = nil
        isActive = false
        PulseAudioOwnership.shared.release(ownershipToken)
    }

    /// Mic permission - the SAME AVAudioSession.requestRecordPermission
    /// pattern PulseCallAudioSession.requestMicPermission() uses
    /// (verified: granted / denied / undetermined + continuation).
    func requestMicPermission() async -> Bool {
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                session.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }
}

/// The capture/playback engine. Constructed on the main actor by the
/// session model but deliberately NOT @MainActor: the tap runs on a
/// realtime audio thread, so the gate is lock-guarded and callbacks
/// fire on that thread (the session model hops to main).
protocol VoiceRoomAudioDriving: AnyObject {
    var onChunk: ((VoicePcmChunkOut) -> Void)? { get set }
    var onSamples: (([Int16]) -> Void)? { get set }
    var onError: ((String) -> Void)? { get set }
    /// R56 (item 11): the OS took the audio session mid-room. Main thread.
    var onInterruption: (() -> Void)? { get set }
    /// Whether the rooms wrapper currently owns the AVAudioSession.
    var isSessionActive: Bool { get }
    func requestMicPermission() async -> Bool
    func startCapture(micGranted: Bool) -> String?
    func startPlayback() -> String?
    func stopMicrophone() -> String?
    func stopCapture()
    func resetChunker()
    func setGate(transmitting: Bool, muted: Bool)
    func flushPartial()
    func resetPeer(_ userId: String)
    func play(userId: String, base64: String, atMs: Double, nowMs: Double)
}

final class VoiceRoomAudioEngine: VoiceRoomAudioDriving {
    /// Emitted on the audio tap queue - one VoicePcmChunkOut per 250 ms
    /// block (+ the proportional partial flush on release).
    var onChunk: ((VoicePcmChunkOut) -> Void)?
    /// The same gated samples, for the caption accumulator (VR-7).
    var onSamples: (([Int16]) -> Void)?
    /// Honest hardware failure copy (mic absent/busy/refused).
    var onError: ((String) -> Void)?
    /// R56 (item 11): the OS took the audio session mid-room (main thread).
    var onInterruption: (() -> Void)?
    /// Whether the rooms wrapper currently owns the AVAudioSession.
    var isSessionActive: Bool { audioSession.isActive }

    /// Mic permission - the SAME AVAudioSession.requestRecordPermission
    /// pattern PulseCallAudioSession.requestMicPermission() uses
    /// (verified: granted / denied / undetermined + continuation). The
    /// session model awaits this BEFORE startCapture(micGranted:).
    func requestMicPermission() async -> Bool {
        await audioSession.requestMicPermission()
    }

    static let sampleRate: Double = 16_000
    static let tapFrames: AVAudioFrameCount = 1024
    /// 85 ms priming silence in frames @ 16 kHz.
    static let primeFrames: AVAudioFrameCount = AVAudioFrameCount(16_000 * 0.085)

    private let engine = AVAudioEngine()
    private let audioSession = VoiceRoomAudioSession()
    private var converter: AVAudioConverter?
    /// 16 kHz mono Int16 - the wire format for every buffer.
    private let wireFormat: AVAudioFormat? = AVAudioFormat(
        commonFormat: .pcmFormatInt16,
        sampleRate: VoiceRoomAudioEngine.sampleRate,
        channels: 1,
        interleaved: true,
    )

    private let lock = NSLock()
    private var gateTransmitting = false
    private var gateMuted = false
    private var chunker = VoicePcmChunker()
    private var captureConfigured = false
    private var tapGeneration = UUID()

    private let playbackLock = NSLock()
    private var peerNodes: [String: AVAudioPlayerNode] = [:]

    // lifecycle

    /// Graph + session + engine start. `micGranted` comes from the
    /// awaited requestMicPermission() - returns an honest failure copy
    /// instead of throwing (VR-10).
    func startCapture(micGranted: Bool) -> String? {
        // 1. Permission (the TCC prompt already happened in the await).
        guard micGranted else {
            return "Microphone access is off - enable it for Pulse in Settings."
        }
        // Stop before reconfiguring category/input format; restart via the
        // playback fallback if activation fails.
        engine.stop()
        // 2. Session category (saved/restored by the rooms wrapper).
        if !audioSession.activate() {
            return "Audio hardware refused the room session."
        }
        // 3. Graph - input tap → converter → chunker; playback nodes hang
        //    off the main mixer.
        do {
            engine.stop()
            resetAllPeers()
            try configureGraphIfNeeded()
            engine.prepare()
            try engine.start()
            return nil
        } catch {
            stopCapture()
            return "Microphone is busy or unavailable."
        }
    }

    /// Listening never installs an input tap and never asks for microphone permission.
    func startPlayback() -> String? {
        if engine.isRunning { return nil }
        guard audioSession.activate(recording: false) else { return "Audio is in use by another session." }
        do {
            _ = engine.mainMixerNode
            engine.prepare()
            try engine.start()
            return nil
        } catch {
            stopCapture()
            return "Could not start room playback."
        }
    }

    /// Demotion releases the physical input, not just the PTT indicator.
    func stopMicrophone() -> String? {
        setGate(transmitting: false, muted: false)
        engine.stop()
        if captureConfigured { engine.inputNode.removeTap(onBus: 0) }
        captureConfigured = false
        converter = nil
        lock.lock()
        chunker.discardPartial()
        lock.unlock()
        resetAllPeers()
        return startPlayback()
    }

    func stopCapture() {
        setGate(transmitting: false, muted: false)
        engine.stop()
        if captureConfigured { engine.inputNode.removeTap(onBus: 0) }
        captureConfigured = false
        converter = nil
        lock.lock()
        chunker.reset()
        lock.unlock()
        resetAllPeers()
        audioSession.deactivate()
    }

    /// The software gate (VR-6) - the ONLY thing the tap consults.
    func setGate(transmitting: Bool, muted: Bool) {
        lock.lock()
        gateTransmitting = transmitting
        gateMuted = muted
        lock.unlock()
    }

    /// PTT released - flush the proportional partial block (VR-4).
    func flushPartial() {
        lock.lock()
        let chunk = chunker.flushPartial()
        lock.unlock()
        if let chunk {
            onChunk?(chunk)
        }
    }

    /// Teardown: the next burst starts from seq 1 (VR-9).
    func resetChunker() {
        lock.lock()
        chunker.reset()
        lock.unlock()
    }

    var captureRunning: Bool {
        captureConfigured && engine.isRunning
    }

    // capture internals

    private func configureGraphIfNeeded() throws {
        guard !captureConfigured else { return }
        guard let wireFormat else {
            throw PulseVoiceAudioError.formatUnavailable
        }
        let input = engine.inputNode
        let inputFormat = input.inputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            throw PulseVoiceAudioError.noInput
        }
        guard let converter = AVAudioConverter(from: inputFormat, to: wireFormat) else {
            throw PulseVoiceAudioError.formatUnavailable
        }
        self.converter = converter

        let ticket = UUID()
        lock.lock()
        tapGeneration = ticket
        lock.unlock()
        let chunkCallback = onChunk
        let samplesCallback = onSamples
        input.installTap(onBus: 0, bufferSize: Self.tapFrames, format: inputFormat) { [weak self] buffer, _ in
            self?.handleTap(buffer: buffer, converter: converter, ticket: ticket,
                chunkCallback: chunkCallback, samplesCallback: samplesCallback)
        }
        captureConfigured = true
    }

    /// Runs on the AVAudioEngine tap thread.
    private func handleTap(buffer: AVAudioPCMBuffer, converter: AVAudioConverter, ticket: UUID,
                           chunkCallback: ((VoicePcmChunkOut) -> Void)?, samplesCallback: (([Int16]) -> Void)?) {
        lock.lock()
        let gateOn = gateTransmitting && !gateMuted && tapGeneration == ticket
        lock.unlock()
        guard gateOn, let samples = convertTo16kMono(buffer, converter: converter), !samples.isEmpty else { return }

        // Conversion can overlap a stop/demotion/new graph. Check again before
        // feeding the sequence buffer, and deliver only to this tap's owner.
        lock.lock()
        guard gateTransmitting && !gateMuted && tapGeneration == ticket else { lock.unlock(); return }
        let chunks = chunker.feed(samples)
        lock.unlock()
        samplesCallback?(samples)
        chunks.forEach { chunkCallback?($0) }
    }

    /// AVAudioConverter → 16 kHz mono Int16 samples (linear resample
    /// handled by the converter). The input block is passed per
    /// `convert(to:error:withInputFrom:)` call - AVAudioConverter has no
    /// settable inputBlock property.
    private func convertTo16kMono(_ input: AVAudioPCMBuffer, converter: AVAudioConverter) -> [Int16]? {
        guard input.frameLength > 0 else { return nil }

        var collected: [Int16] = []
        var source: AVAudioPCMBuffer? = input

        let ratio = Self.sampleRate / max(input.format.sampleRate, 1)
        let capacity = max(AVAudioFrameCount((Double(input.frameLength) * ratio).rounded(.up)) + 64, 64)

        while true {
            guard let out = AVAudioPCMBuffer(pcmFormat: converter.outputFormat, frameCapacity: capacity) else { break }
            var conversionError: NSError?
            let result = converter.convert(to: out, error: &conversionError) { _, status in
                if let current = source {
                    source = nil
                    status.pointee = .haveData
                    return current
                }
                status.pointee = .noDataNow
                return nil
            }
            if result == .haveData, out.frameLength > 0, let channel = out.int16ChannelData?[0] {
                collected.append(contentsOf: UnsafeBufferPointer(start: channel, count: Int(out.frameLength)))
            }
            if result != .haveData {
                break
            }
            if Int(out.frameLength) < Int(capacity) {
                break // input drained
            }
        }
        return collected.isEmpty ? nil : collected
    }

    // playback (VR-5)

    /// Schedules one decoded chunk for a peer. Fresh nodes get the 85 ms
    /// silent prime + start; future playheads schedule at the exact host
    /// time; the rest chain immediately behind the node's queue.
    func play(userId: String, base64: String, atMs: Double, nowMs: Double) {
        guard engine.isRunning, let wireFormat else { return }
        guard let samples = Self.decode(base64: base64), !samples.isEmpty else { return } // corrupt → drop silently
        guard let buffer = Self.buffer(samples: samples, format: wireFormat) else { return }

        playbackLock.lock()
        var node = peerNodes[userId]
        let fresh = (node == nil)
        if node == nil {
            let created = AVAudioPlayerNode()
            engine.attach(created)
            engine.connect(created, to: engine.mainMixerNode, format: wireFormat)
            peerNodes[userId] = created
            node = created
        }
        playbackLock.unlock()

        guard let node else { return }

        if fresh {
            // Short silent chain = the 85 ms pre-roll (smooth start).
            if let prime = Self.buffer(samples: [Int16](repeating: 0, count: Int(Self.primeFrames)), format: wireFormat) {
                node.scheduleBuffer(prime, at: nil, options: [])
            }
            node.play()
        }

        // Playhead: schedule at the wall-clock slot when it is meaningfully
        // in the future; otherwise chain right behind the pending buffers.
        let delayMs = atMs - nowMs
        if delayMs > 30 {
            let hostTime = mach_absolute_time() + AVAudioTime.hostTime(forSeconds: delayMs / 1000.0)
            let time = AVAudioTime(hostTime: hostTime, sampleTime: 0, atRate: 0)
            node.scheduleBuffer(buffer, at: time, options: [])
        } else {
            node.scheduleBuffer(buffer, at: nil, options: [])
        }
    }

    /// Roster drop / rejoin - stop the node, drop its queue, forget it
    /// (FIX #2 seam; the model clears its bookkeeping in resetPeer).
    func resetPeer(_ userId: String) {
        playbackLock.lock()
        let node = peerNodes.removeValue(forKey: userId)
        playbackLock.unlock()
        guard let node else { return }
        node.stop()
        engine.detach(node)
    }

    func resetAllPeers() {
        playbackLock.lock()
        let nodes = Array(peerNodes.values)
        peerNodes.removeAll()
        playbackLock.unlock()
        nodes.forEach {
            $0.stop()
            engine.detach($0)
        }
    }

    // helpers

    /// base64(Int16LE PCM) → samples; nil for corrupt payloads (dropped
    /// silently, never crashing on wire data).
    static func decode(base64: String) -> [Int16]? {
        guard !base64.isEmpty, let data = Data(base64Encoded: base64), !data.isEmpty else { return nil }
        guard data.count % 2 == 0 else { return nil }
        return data.withUnsafeBytes { raw in
            let count = raw.count / 2
            let buffer = raw.bindMemory(to: Int16.self)
            return (0..<count).map { buffer[$0].littleEndian }
        }
    }

    static func buffer(samples: [Int16], format: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard !samples.isEmpty,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)) else { return nil }
        let written: Bool = samples.withUnsafeBufferPointer { source in
            guard let base = source.baseAddress, let channel = buffer.int16ChannelData?[0] else { return false }
            channel.update(from: base, count: samples.count)
            return true
        }
        guard written else { return nil }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        return buffer
    }

    private enum PulseVoiceAudioError: Error {
        case formatUnavailable
        case noInput
    }
}
