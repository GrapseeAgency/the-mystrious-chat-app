import AVFoundation
import SwiftUI

/// Wave 2 voice notes (spec §1 rows 1/11/12/13/15) — everything behind the
/// composer's mic surface and the interactive voice bubble:
///   • `VoiceMath` — the pure duration/rounding contract (unit-tested).
///   • `VoiceRecorder` — tap-to-start AAC recorder → cache file
///     voice-<uuid>.m4a (44.1 kHz mono, kind "audio" so /transcribe works —
///     the web bug this mirrors is sending kind text).
///   • `VoicePlaybackManager` — ONE active AVAudioPlayer keyed by messageId,
///     100 ms Timer recomputing progress (no CADisplayLink), persisted rate
///     chip 1x → 1.5x → 2x (UserDefaults "pulse.voiceRate").
///   • `VoiceBubble` / `VoiceRecordingBar` — the two UI surfaces.
/// Neither class is actor-isolated: AVAudioRecorder/AVAudioPlayer tolerate
/// main-actor driving, and the owning RoomViewModel deallocates on the main
/// thread — a plain deinit teardown keeps Swift 5.10 isolation rules happy.
enum VoiceMath {
    /// Recording floor — shorter takes are discarded (spec §1 row 11).
    static let minimumSendMs: Double = 600

    /// `max(1, Int((elapsed/100).rounded()*100))` — the exact web rounding:
    /// quantize to 100 ms, never send 0.
    static func roundedDurationMs(fromElapsedMs elapsed: Double) -> Double {
        let quantized = Int((elapsed / 100).rounded() * 100)
        return Double(max(1, quantized))
    }

    /// Whether a take must be discarded instead of sent.
    static func isTooShort(_ elapsedMs: Double) -> Bool {
        elapsedMs < minimumSendMs
    }
}

enum VoiceRecorderError: LocalizedError {
    case startFailed

    var errorDescription: String? {
        switch self {
        case .startFailed: return "Recording could not start"
        }
    }
}

/// Tap-to-start recorder — the mic button STARTS immediately (web parity,
/// no hold-to-talk). Files land in tmp; the send path reads + deletes them.
final class VoiceRecorder {
    private(set) var fileURL: URL?
    private var recorder: AVAudioRecorder?

    /// Mic permission — TCC prompt on first use (NSMicrophoneUsageDescription
    /// already ships in project.yml). `requestRecordPermission` is soft-
    /// deprecated on iOS 17 in favor of AVAudioApplication; the legacy call
    /// keeps the tree compiling across every runner SDK.
    static func requestPermission() async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    /// Configures the session (playAndRecord so the player keeps working),
    /// builds the AAC recorder and starts metering. Throws on TCC/session
    /// failure — the caller toasts honestly.
    func start() throws {
        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.playAndRecord, mode: .default)
        try audio.setActive(true)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        guard recorder.record() else {
            throw VoiceRecorderError.startFailed
        }
        self.recorder = recorder
        fileURL = url
    }

    /// Stops metering and KEEPS the file (send path).
    func stop() {
        recorder?.stop()
        recorder = nil
    }

    /// Stops metering and DELETES the file (cancel / too-short paths).
    func cancel() {
        recorder?.stop()
        recorder = nil
        if let fileURL {
            try? FileManager.default.removeItem(at: fileURL)
        }
        fileURL = nil
    }

    deinit {
        recorder?.stop()
    }
}

/// RoomViewModel-owned playback — exactly one voice note sounds at a time;
/// starting another message stops the previous one. `state` drives the
/// waveform recolor + play/pause glyph; the rate chip persists globally
/// (spec §1 row 12 — UserDefaults "pulse.voiceRate").
final class VoicePlaybackManager: ObservableObject {
    struct PlaybackState: Equatable {
        let messageId: String
        /// 0…1 through the clip (currentTime / duration).
        let progress: Double
        let playing: Bool
        let rate: Float
    }

    static let rateKey = "pulse.voiceRate"
    static let rates: [Float] = [1.0, 1.5, 2.0]
    static let defaultRate: Float = 1.0

    @Published private(set) var state: PlaybackState?

    private var player: AVAudioPlayer?
    private var activeId: String?
    private var ticker: Timer?

    static func storedRate(defaults: UserDefaults = .standard) -> Float {
        let raw = defaults.float(forKey: rateKey)
        return rates.contains(raw) ? raw : defaultRate
    }

    /// 1x → 1.5x → 2x → 1x (spec §1 row 12).
    static func nextRate(after rate: Float) -> Float {
        let index = rates.firstIndex(of: rate) ?? 0
        return rates[(index + 1) % rates.count]
    }

    /// Starts (or restarts from zero) playback of a downloaded local file.
    /// enableRate must be set BEFORE prepareToPlay for the rate chip to work.
    func start(messageId: String, localURL: URL, rate: Float) {
        stopTicker()
        let player: AVAudioPlayer
        do {
            player = try AVAudioPlayer(contentsOf: localURL)
        } catch {
            self.player = nil
            activeId = nil
            state = nil
            return
        }
        player.enableRate = true
        player.rate = rate
        player.prepareToPlay()
        player.play()
        self.player = player
        activeId = messageId
        state = PlaybackState(messageId: messageId, progress: 0, playing: true, rate: rate)
        startTicker()
    }

    func pause() {
        player?.pause()
        if let state {
            self.state = PlaybackState(messageId: state.messageId, progress: state.progress, playing: false, rate: state.rate)
        }
    }

    func resume() {
        player?.play()
        if let state {
            self.state = PlaybackState(messageId: state.messageId, progress: state.progress, playing: true, rate: state.rate)
        }
        startTicker()
    }

    /// Mid-clip rate change (chip tap) — takes effect immediately on AVAudioPlayer.
    func setRate(_ rate: Float) {
        player?.rate = rate
        if let state {
            self.state = PlaybackState(messageId: state.messageId, progress: state.progress, playing: state.playing, rate: rate)
        }
    }

    /// Finished clip → replay from the top without re-downloading.
    func replay() {
        guard let player, let activeId else { return }
        player.currentTime = 0
        player.play()
        state = PlaybackState(messageId: activeId, progress: 0, playing: true, rate: state?.rate ?? Self.defaultRate)
        startTicker()
    }

    /// Halts everything and clears the active state (message switch, room exit).
    func stop() {
        stopTicker()
        player?.stop()
        player = nil
        activeId = nil
        state = nil
    }

    // 100 ms sweep — recomputes progress while playing; on natural end the
    // bars stay fully painted (progress 1) and the ticker halts.
    private func startTicker() {
        stopTicker()
        let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(timer, forMode: .common)
        ticker = timer
    }

    private func stopTicker() {
        ticker?.invalidate()
        ticker = nil
    }

    private func tick() {
        guard let player, let activeId else {
            stop()
            return
        }
        let duration = max(player.duration, 0.0001)
        let progress = min(1, max(0, player.currentTime / duration))
        let playing = player.isPlaying
        if !playing && progress >= 0.999 {
            state = PlaybackState(messageId: activeId, progress: 1, playing: false, rate: state?.rate ?? Self.defaultRate)
            stopTicker()
        } else {
            state = PlaybackState(messageId: activeId, progress: progress, playing: playing, rate: state?.rate ?? Self.defaultRate)
        }
    }

    deinit {
        ticker?.invalidate()
        player?.stop()
    }
}

/// The interactive voice bubble (room surface) — play/pause, deterministic
/// 26-bar waveform recolored by progress, duration, global rate chip, and
/// the transcript strip (italic under a hairline, or the Transcribe pill —
/// spec §1 row 15, never on optimistic rows).
struct VoiceBubble: View {
    let message: WireChatMessage
    let mine: Bool
    let playback: VoicePlaybackManager.PlaybackState?
    let rate: Float
    let onPlayToggle: (() -> Void)?
    let onRateCycle: (() -> Void)?
    let onTranscribe: (() -> Void)?

    private var isPlaying: Bool { playback?.playing == true }
    private var progress: Double { playback?.progress ?? 0 }

    // Deterministic id-hashed bars (web parity — decorative, spec §1 row 13).
    private var bars: [CGFloat] {
        let seed = abs(message.id.hashValue)
        return (0..<26).map { index in CGFloat(5 + (seed * (index + 7)) % 15) }
    }

    private var rateLabel: String {
        if rate == rate.rounded() { return "\(Int(rate))x" }
        return "\(rate)x"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Button {
                    onPlayToggle?()
                } label: {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(mine ? Color.white.opacity(0.18) : PulseTheme.emerald.opacity(0.12)))
                }
                .buttonStyle(PulseButtonStyle())
                .disabled(onPlayToggle == nil)
                .accessibilityLabel(isPlaying ? "Pause voice note" : "Play voice note")

                HStack(spacing: 2) {
                    ForEach(0..<26, id: \.self) { index in
                        Capsule()
                            .fill(barColor(played: Double(index + 1) / 26.0 <= progress))
                            .frame(width: 2.5, height: bars[index])
                    }
                }

                Spacer(minLength: 4)

                if let durationMs = message.durationMs {
                    Text(PulseFormat.duration(durationMs))
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(mine ? Color.white.opacity(0.85) : .secondary)
                }

                Button {
                    onRateCycle?()
                } label: {
                    Text(rateLabel)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(mine ? Color.white.opacity(0.18) : PulseTheme.emerald.opacity(0.12)))
                }
                .buttonStyle(PulseButtonStyle())
                .disabled(onRateCycle == nil)
                .accessibilityLabel("Playback speed")
            }
            transcriptStrip
        }
    }

    private func barColor(played: Bool) -> Color {
        if mine {
            return Color.white.opacity(played ? 1.0 : 0.45)
        }
        return played ? PulseTheme.emerald : PulseTheme.emerald.opacity(0.35)
    }

    @ViewBuilder
    private var transcriptStrip: some View {
        if let transcript = message.transcript, !transcript.isEmpty {
            Divider()
                .background(mine ? Color.white.opacity(0.35) : Color.primary.opacity(0.1))
            Text(transcript)
                .font(.caption.italic())
                .foregroundStyle(mine ? Color.white.opacity(0.9) : .secondary)
                .fixedSize(horizontal: false, vertical: true)
        } else if onTranscribe != nil && !message.id.hasPrefix("local_") {
            Button {
                onTranscribe?()
            } label: {
                Label("Transcribe", systemImage: "text.bubble")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(mine ? Color.white : PulseTheme.emerald)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(mine ? Color.white.opacity(0.16) : PulseTheme.emerald.opacity(0.1)))
            }
            .buttonStyle(PulseButtonStyle())
        }
    }
}

/// Composer replacement while recording — cancel X, pulsing red dot, live
/// m:ss timer, send (spec §1 row 11).
struct VoiceRecordingBar: View {
    let elapsedText: String
    let onCancel: () -> Void
    let onSend: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button {
                onCancel()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Discard recording")

            PulsingRedDot()

            Text(elapsedText)
                .font(.callout.monospacedDigit().weight(.semibold))
                .foregroundStyle(.primary)
                .frame(minWidth: 44, alignment: .leading)

            Spacer(minLength: 0)

            Button {
                onSend()
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(PulseTheme.gradient(named: "emerald"))
            }
            .buttonStyle(PulseButtonStyle())
            .accessibilityLabel("Send voice note")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

/// Red pulsing dot on the recording bar (Reduce Motion respected by the
/// plain opacity fallback — the dot is content signaling, like TypingDots).
struct PulsingRedDot: View {
    @State private var pulsing = false

    var body: some View {
        Circle()
            .fill(Color.red)
            .frame(width: 10, height: 10)
            .opacity(pulsing ? 0.35 : 1)
            .scaleEffect(pulsing ? 0.8 : 1)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                    pulsing = true
                }
            }
    }
}

// ── UI-side copy helpers (data layer rows are immutable; the UI patches ──
// ── exactly one field after a POST verdict lands).                    ──

extension WireChatMessage {
    /// POST /transcribe verdict → patched river row (store uses
    /// updateTranscription(messageId:transcript:transcribedAt:)).
    func withTranscript(_ text: String, transcribedAt stamp: String?) -> WireChatMessage {
        WireChatMessage(
            id: id,
            conversationId: conversationId,
            senderId: senderId,
            content: content,
            kind: kind,
            createdAt: createdAt,
            editedAt: editedAt,
            deletedAt: deletedAt,
            sender: sender,
            reactions: reactions,
            replyTo: replyTo,
            parentId: parentId,
            imagePath: imagePath,
            audioPath: audioPath,
            durationMs: durationMs,
            filePath: filePath,
            fileName: fileName,
            fileSize: fileSize,
            pinnedAt: pinnedAt,
            viewOnce: viewOnce,
            anon: anon,
            anonAlias: anonAlias,
            viewedAt: viewedAt,
            viewedBy: viewedBy,
            transcript: text,
            transcribedAt: stamp ?? transcribedAt,
            topicId: topicId,
            linkUrl: linkUrl,
            linkPreview: linkPreview,
            poll: poll
        )
    }

    /// POST /viewed burn stamp → patched river row (anti-replay: every
    /// render path keys off viewedAt).
    func withViewedAt(_ stamp: String) -> WireChatMessage {
        WireChatMessage(
            id: id,
            conversationId: conversationId,
            senderId: senderId,
            content: content,
            kind: kind,
            createdAt: createdAt,
            editedAt: editedAt,
            deletedAt: deletedAt,
            sender: sender,
            reactions: reactions,
            replyTo: replyTo,
            parentId: parentId,
            imagePath: imagePath,
            audioPath: audioPath,
            durationMs: durationMs,
            filePath: filePath,
            fileName: fileName,
            fileSize: fileSize,
            pinnedAt: pinnedAt,
            viewOnce: viewOnce,
            anon: anon,
            anonAlias: anonAlias,
            viewedAt: stamp,
            viewedBy: viewedBy,
            transcript: transcript,
            transcribedAt: transcribedAt,
            topicId: topicId,
            linkUrl: linkUrl,
            linkPreview: linkPreview,
            poll: poll
        )
    }
}
