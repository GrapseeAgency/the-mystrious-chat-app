import Foundation

// ─────────────────────────────────────────────────────────────
// Pulse — W5-f voice room / stage / space PURE state machines.
//
// Value types only — no AVFoundation, no sockets, no @Published.
// VoiceRoomSessionModel (the @MainActor owner) drives them from
// relay signals; VoiceRoomMachineTests drives them directly.
//
// The five defect fixes encoded here (docs/WAVE5-VOICE-SPACES-PARITY-SPEC.md):
//   #1 chunker proof-test        → VoicePcmChunker.swift
//   #2 roster-drop seq reset     → VoiceRoomModel.applyRoster
//   #3 voice seat for ALL roles  → StageModel.needsVoiceSeat (+ re-arm)
//   #4 80 ms throttle + reconcile→ SpaceModel.localMove/apply
//   #5 honest space error state  → SpaceModel.reconnectAttempts
// ─────────────────────────────────────────────────────────────

// ══════════════════════════════════════════════════════════════
// VOICE ROOM (walkie-talkie PTT) — spec §1.1
// ══════════════════════════════════════════════════════════════

public struct VoiceRoomModel: Equatable, Sendable {
    public enum Status: Equatable, Sendable { case idle, joining, joined, error }

    /// Per-peer playback bookkeeping (VR-5): { lastSeq, nextAt }.
    public struct PeerPlayback: Equatable, Sendable {
        public var lastSeq: Int
        /// Wall-clock ms the NEXT chunk of this peer may start at.
        public var nextAtMs: Double

        public init(lastSeq: Int = 0, nextAtMs: Double = 0) {
            self.lastSeq = lastSeq
            self.nextAtMs = nextAtMs
        }
    }

    /// Playback pre-roll (web JITTER_BUFFER_S = 0.085).
    public static let jitterMs: Double = 85
    /// Missing-me roster resync is rate-limited to 2 s (VR-8).
    public static let resyncIntervalMs: Double = 2000

    public var status: Status
    public var conversationId: String?
    /// True ONLY for the user's own walkie-talkie join (VR-1) — the
    /// stage/space voice seats (FIX #3) ride the same relay seat without
    /// flipping this, so stage teardown knows whether to release it.
    public var userJoinedVoice: Bool
    /// Server truth — REPLACED wholesale on every voice:roster (VR-2).
    public var roster: [WireVoicePeer]
    /// Ids currently transmitting (voice:ptt on) — pruned to live peers.
    public var speakingIds: Set<String>
    /// Software gate is the source of truth (VR-6).
    public var micMuted: Bool
    public var transmitting: Bool
    public var connected: Bool
    public var errorText: String?
    public var playback: [String: PeerPlayback]
    public var lastResyncMs: Double?

    public init(
        status: Status = .idle,
        conversationId: String? = nil,
        userJoinedVoice: Bool = false,
        roster: [WireVoicePeer] = [],
        speakingIds: Set<String> = [],
        micMuted: Bool = false,
        transmitting: Bool = false,
        connected: Bool = false,
        errorText: String? = nil,
        playback: [String: PeerPlayback] = [:],
        lastResyncMs: Double? = nil,
    ) {
        self.status = status
        self.conversationId = conversationId
        self.userJoinedVoice = userJoinedVoice
        self.roster = roster
        self.speakingIds = speakingIds
        self.micMuted = micMuted
        self.transmitting = transmitting
        self.connected = connected
        self.errorText = errorText
        self.playback = playback
        self.lastResyncMs = lastResyncMs
    }

    // ── lifecycle ────────────────────────────────────────

    public mutating func beginJoin(conversationId: String) {
        self.conversationId = conversationId
        status = .joining
        userJoinedVoice = true
        errorText = nil
    }

    public mutating func markJoined() {
        status = .joined
        errorText = nil
    }

    public mutating func fail(_ text: String) {
        status = .error
        errorText = text
    }

    /// Explicit leave (VR-9): reset seq bookkeeping, clear playback +
    /// speaking state; the session resets the chunker + engine separately.
    public mutating func leave() {
        status = .idle
        conversationId = nil
        userJoinedVoice = false
        roster = []
        speakingIds = []
        transmitting = false
        errorText = nil
        playback = [:]
        lastResyncMs = nil
    }

    public mutating func setConnected(_ connected: Bool) {
        self.connected = connected
    }

    // ── roster + ptt ─────────────────────────────────────

    /// voice:roster — replace wholesale; prune speaking to live peers;
    /// DEFECT FIX #2: a peer that vanished loses its playback state
    /// (lastSeq → 0, queue cleared) so its REJOIN restarts audio
    /// immediately instead of blackholing behind a stale lastSeq.
    public mutating func applyRoster(_ roster: WireVoiceRoster) {
        self.roster = roster.peers ?? []
        let liveIds = Set(self.roster.map(\.id))
        speakingIds = speakingIds.filter { liveIds.contains($0) }
        for staleId in playback.keys where !liveIds.contains(staleId) {
            playback[staleId] = nil
        }
        // A roster that contains me after a drop means the seat is live again.
        status = .joined
    }

    /// voice:ptt — echoed to the sender too, so self rings glow (VR-3).
    public mutating func applyPtt(userId: String, on: Bool) {
        if on {
            speakingIds.insert(userId)
        } else {
            speakingIds.remove(userId)
        }
    }

    // ── mute (VR-6) ──────────────────────────────────────

    /// Software gate truth. Returns true when a LIVE transmission had to
    /// be force-stopped (the session must also emit voice:ptt off and
    /// gate the capture engine).
    @discardableResult
    public mutating func setMicMuted(_ muted: Bool) -> Bool {
        micMuted = muted
        if muted && transmitting {
            transmitting = false
            return true
        }
        return false
    }

    // ── playback playhead (VR-5) ─────────────────────────

    public enum ChunkVerdict: Equatable, Sendable {
        /// Stale/duplicate/corrupt — drop silently.
        case drop
        /// Schedule playback at this wall-clock ms.
        case schedule(atMs: Double)
    }

    /// The jitter playhead: drop `seq <= lastSeq`; otherwise schedule at
    /// `max(now + 85 ms, nextAt)` and chain `nextAt = at + duration`.
    public mutating func decide(seq: Int, userId: String, nowMs: Double, chunkDurationMs: Double = 250) -> ChunkVerdict {
        var state = playback[userId] ?? PeerPlayback()
        guard seq > state.lastSeq else { return .drop }
        let at = max(nowMs + Self.jitterMs, state.nextAtMs)
        state.lastSeq = seq
        state.nextAtMs = at + chunkDurationMs
        playback[userId] = state
        return .schedule(atMs: at)
    }

    /// Roster-drop reset for ONE peer (the FIX #2 primitive) — also used
    /// when the audio engine tears a peer node down.
    public mutating func resetPeer(_ userId: String) {
        playback[userId] = nil
    }

    // ── resync (VR-8) ────────────────────────────────────

    /// Rate-limited missing-me resync: a joined client absent from a fresh
    /// roster may re-emit voice:join at most once per 2 s.
    public func mayResync(nowMs: Double) -> Bool {
        guard let last = lastResyncMs else { return true }
        return nowMs - last >= Self.resyncIntervalMs
    }

    public mutating func noteResync(nowMs: Double) {
        lastResyncMs = nowMs
    }

    // ── honest status line (VR-8 / VR-10) ────────────────

    public var statusLine: String {
        if status == .error { return errorText ?? "Voice room unavailable" }
        if status == .idle { return "Standby" }
        if status == .joining { return "Connecting…" } // first dial, not a drop
        if !connected { return "Reconnecting…" } // joined but the relay link is down
        if roster.isEmpty { return "Syncing roster…" }
        return "Connected"
    }
}

// ══════════════════════════════════════════════════════════════
// STAGE (Clubhouse hierarchy) — spec §1.2
// ══════════════════════════════════════════════════════════════

public struct StageModel: Equatable, Sendable {
    public enum Role: Equatable, Sendable { case host, speaker, listener, audience }

    /// Missing-me stage resync is rate-limited to 2500 ms (ST-8).
    public static let resyncIntervalMs: Double = 2500

    public let myId: String
    public var conversationId: String?
    /// Last stage:state — nil until the first one lands ("Syncing stage…").
    public var state: WireStageState?
    public var joined: Bool
    public var syncing: Bool
    /// Remembered across reconnects (ST-8: re-join keeps the role).
    public var wasHost: Bool
    /// ST-5 teardown flag — surface closes + full local teardown.
    public var ended: Bool
    /// Optimistic raise; reconciled by every stage:state.
    public var optimisticHand: Bool
    /// DEFECT FIX #3 — EVERY joined stage member (host/speaker/listener)
    /// must hold a voice seat. Re-armed on join, on demotion, and on
    /// forced removal (the voice roster drops me while stage-joined).
    public var needsVoiceSeat: Bool
    public var lastResyncMs: Double?

    public init(myId: String) {
        self.myId = myId
        conversationId = nil
        state = nil
        joined = false
        syncing = false
        wasHost = false
        ended = false
        optimisticHand = false
        needsVoiceSeat = false
        lastResyncMs = nil
    }

    // ── derivations (ST-2) ───────────────────────────────

    public var myRole: Role {
        guard let state else { return .audience }
        if state.host?.id == myId { return .host }
        if state.speakerList.contains(where: { $0.id == myId }) { return .speaker }
        if state.listenerList.contains(where: { $0.id == myId }) { return .listener }
        return joined ? .listener : .audience
    }

    /// My hand is raised when the server queue says so (or an optimistic
    /// raise is still in flight).
    public var handRaised: Bool {
        if state?.handList.contains(where: { $0.id == myId }) == true { return true }
        return optimisticHand
    }

    /// ST-7 — the host seat is empty and I am joined: "Claim host".
    public var canClaimHost: Bool {
        joined && state?.host == nil
    }

    public var canRaiseHand: Bool {
        guard joined else { return false }
        guard let state else { return true } // a fresh joiner IS a listener until the first stage:state
        return myRole == .listener
    }
    /// Host + speakers arm the mic on PTT (ST-6).
    public var canSpeak: Bool { myRole == .host || myRole == .speaker }
    public var canEnd: Bool { myRole == .host }
    public var isHost: Bool { myRole == .host }

    public var handQueue: [WireStagePerson] { state?.handList ?? [] }
    public var speakerTiles: [WireStagePerson] { state?.speakerList ?? [] }
    public var listenerRow: [WireStagePerson] { state?.listenerList ?? [] }
    public var listenerTotal: Int { state?.listenerTotal ?? 0 }

    // ── lifecycle ────────────────────────────────────────

    /// ST-1 — join ALWAYS as listener (asHost only via Claim host / the
    /// remembered wasHost on resync). Optimistic joined + syncing until
    /// the first stage:state.
    public mutating func beginJoin(conversationId: String, asHost: Bool) {
        self.conversationId = conversationId
        joined = true
        syncing = true
        ended = false
        wasHost = asHost
        optimisticHand = false
        needsVoiceSeat = true // FIX #3 — the seat is claimed with the join
    }

    public mutating func apply(state: WireStageState) {
        let previousRole = myRole
        self.state = state
        syncing = false
        // The optimistic hand reconciles to server truth on EVERY state —
        // a raise the server never confirmed must not glow forever.
        optimisticHand = state.handList.contains { $0.id == myId }
        if state.host?.id == myId { wasHost = true }
        // FIX #3 seam — a demotion (speaker → listener) means the server
        // force-removed my voice seat (stage:mute); re-arm the request so
        // the session re-emits voice:join and I keep HEARING.
        if joined, previousRole == .speaker, myRole == .listener {
            needsVoiceSeat = true
        }
    }

    /// FIX #3 re-arm — the voice roster arrived without me while
    /// stage-joined (forced removal / drop): request the seat again.
    public mutating func markVoiceSeatRemoved() {
        guard joined else { return }
        needsVoiceSeat = true
    }

    /// The session re-emitted voice:join → the request is consumed.
    @discardableResult
    public mutating func consumeVoiceSeatRequest() -> Bool {
        let pending = needsVoiceSeat
        needsVoiceSeat = false
        return pending
    }

    public mutating func setHandRaised(_ raised: Bool) {
        optimisticHand = raised
    }

    public func mayResync(nowMs: Double) -> Bool {
        guard let last = lastResyncMs else { return true }
        return nowMs - last >= Self.resyncIntervalMs
    }

    public mutating func noteResync(nowMs: Double) {
        lastResyncMs = nowMs
    }

    /// ST-5 — stage:ended: full local teardown (the surface closes + toast
    /// ride in the session model).
    public mutating func applyEnded() {
        ended = true
        joined = false
        syncing = false
        state = nil
        optimisticHand = false
        needsVoiceSeat = false
        wasHost = false
    }

    /// Explicit stage:leave (close = leave; reopening = plain listener).
    public mutating func leave() {
        joined = false
        syncing = false
        state = nil
        conversationId = nil
        optimisticHand = false
        needsVoiceSeat = false
        wasHost = false
    }
}

/// ST-5 — host-only two-tap End with a 2600 ms reset window (pure so the
/// confirm semantics stay unit-testable; the view only renders it).
public struct StageEndConfirm: Equatable, Sendable {
    public static let resetMs: Double = 2600

    private var firstTapMs: Double?

    public init() {}

    /// First tap arms (returns false — show "Tap again to end"); a second
    /// tap inside the window fires the end (returns true); past the window
    /// the arm resets.
    public mutating func tap(nowMs: Double) -> Bool {
        if let armedAt = firstTapMs, nowMs - armedAt <= Self.resetMs {
            firstTapMs = nil
            return true
        }
        firstTapMs = nowMs
        return false
    }

    public mutating func reset() {
        firstTapMs = nil
    }

    public var armed: Bool { firstTapMs != nil }
}

// ══════════════════════════════════════════════════════════════
// SPACE (Gather-style spatial presence) — spec §1.3
// ══════════════════════════════════════════════════════════════

public struct SpaceModel: Equatable, Sendable {
    public struct Player: Equatable, Sendable, Identifiable {
        public let id: String
        public let name: String
        public let color: String?
        public let x: Double
        public let y: Double

        public init(id: String, name: String, color: String?, x: Double, y: Double) {
            self.id = id
            self.name = name
            self.color = color
            self.x = x
            self.y = y
        }
    }

    public enum Status: Equatable, Sendable { case connecting, connected, error }

    public enum MoveDecision: Equatable, Sendable {
        case emit(x: Double, y: Double)
        case throttle
    }

    public enum ReconcileVerdict: Equatable, Sendable {
        case keep
        case adopt(x: Double, y: Double)
    }

    /// Client move throttle — 80 ms (WEB DEFECT FIX: web sent 90 vs the
    /// server's 80; native matches the server).
    public static let throttleMs: Double = 80
    /// Server self-position overwrites the optimistic target only after
    /// 300 ms of finger idle (FIX #4).
    public static let reconcileIdleMs: Double = 300
    /// Euclidean "nearby" ring (SP-4).
    public static let nearbyRadius: Double = 0.18
    /// Honest error after this many reconnect attempts (FIX #5).
    public static let maxReconnectAttempts = 6

    public let myId: String
    public var status: Status
    public var joined: Bool
    public var players: [Player]
    /// Optimistic self target (tap/drag destination), clamped 0..1.
    public var targetX: Double
    public var targetY: Double
    public var lastLocalMoveMs: Double?
    public var lastEmitMs: Double?
    public var reconnectAttempts: Int
    public var errorText: String?

    public init(myId: String) {
        self.myId = myId
        status = .connecting
        joined = false
        players = []
        targetX = 0.5
        targetY = 0.5
        lastLocalMoveMs = nil
        lastEmitMs = nil
        reconnectAttempts = 0
        errorText = nil
    }

    public var selfPlayer: Player? {
        players.first { $0.id == myId }
    }

    public var nearbyPlayers: [Player] {
        nearby(x: targetX, y: targetY, excluding: myId)
    }

    /// SP-4 — Euclidean distance ≤ 0.18 (self excluded).
    public func nearby(x: Double, y: Double, excluding anchorId: String) -> [Player] {
        players.filter { player in
            guard player.id != anchorId else { return false }
            let dx = player.x - x
            let dy = player.y - y
            return (dx * dx + dy * dy) <= Self.nearbyRadius * Self.nearbyRadius
        }
    }

    // ── lifecycle ────────────────────────────────────────

    public mutating func beginJoin() {
        joined = true
        status = .connecting
        errorText = nil
    }

    /// space:state — FULL state replace (SP-5: stale players self-heal).
    /// FIX #4: with the finger idle ≥ 300 ms, the server's self-position
    /// becomes the new optimistic target (drift + relays reconcile).
    public mutating func apply(state: WireSpaceState, nowMs: Double) -> ReconcileVerdict {
        players = (state.players ?? []).map { player in
            Player(
                id: player.id,
                name: player.name ?? "Someone",
                color: player.color,
                x: Self.clamp01(player.x ?? 0.5),
                y: Self.clamp01(player.y ?? 0.5),
            )
        }
        status = joined ? .connected : status
        let idle = lastLocalMoveMs.map { nowMs - $0 >= Self.reconcileIdleMs } ?? true
        if idle, let me = selfPlayer, me.x != targetX || me.y != targetY {
            targetX = me.x
            targetY = me.y
            return .adopt(x: me.x, y: me.y)
        }
        return .keep
    }

    /// SP-3 — tap/drag destination: clamp 0..1 client-side, optimistic
    /// target moves instantly, and the emit is throttled to one per 80 ms.
    public mutating func localMove(x: Double, y: Double, nowMs: Double) -> MoveDecision {
        let clampedX = Self.clamp01(x)
        let clampedY = Self.clamp01(y)
        targetX = clampedX
        targetY = clampedY
        lastLocalMoveMs = nowMs
        if let last = lastEmitMs, nowMs - last < Self.throttleMs {
            return .throttle
        }
        lastEmitMs = nowMs
        return .emit(x: clampedX, y: clampedY)
    }

    /// Connection bookkeeping (FIX #5): every transport drop counts; the
    /// error state is honest and REACHABLE after 6 attempts.
    public mutating func noteDisconnected() {
        guard joined, status != .error else { return }
        reconnectAttempts += 1
        if reconnectAttempts >= Self.maxReconnectAttempts {
            status = .error
            errorText = "Can't reach the room right now."
        } else {
            status = .connecting
        }
    }

    public mutating func noteConnected() {
        reconnectAttempts = 0
        if joined, status != .error {
            status = .connected
        }
    }

    public mutating func fail(_ text: String) {
        status = .error
        errorText = text
    }

    public mutating func leave() {
        joined = false
        players = []
        status = .connecting
        targetX = 0.5
        targetY = 0.5
        lastLocalMoveMs = nil
        lastEmitMs = nil
        reconnectAttempts = 0
        errorText = nil
    }

    public static func clamp01(_ value: Double) -> Double {
        min(1, max(0, value))
    }
}

// ══════════════════════════════════════════════════════════════
// CAPTIONS (VR-7) — window accumulator (pure)
// ══════════════════════════════════════════════════════════════

/// Accumulates 16 kHz samples while transmitting + captions on:
///   · ≥ 64 000 samples (4 s) → flush a window (single-flight: while an
///     ASR call is in flight, keep accumulating — the next feed flushes)
///   · PTT release → the tail becomes the final window only when it
///     carries ≥ 16 000 samples (~1 s); shorter tails are dropped
///   · turning captions OFF clears pending audio immediately
///   · failures = honest silence (a dropped window is never retried)
public struct CaptionWindowAccumulator: Equatable, Sendable {
    public static let windowSamples = 64_000
    public static let minTailSamples = 16_000

    public var enabled: Bool
    private var samples: [Int16] = []
    private var busy = false

    public init(enabled: Bool = false) {
        self.enabled = enabled
    }

    public var pendingCount: Int { samples.count }
    public var isBusy: Bool { busy }

    /// Clear-on-off: flipping the toggle discards pending audio.
    public mutating func setEnabled(_ on: Bool) {
        enabled = on
        if !on {
            samples.removeAll()
            busy = false
        }
    }

    /// Feed while transmitting; returns a full window when ready and no
    /// ASR call is in flight (single-flight — otherwise keep accumulating).
    public mutating func feed(_ chunk: [Int16]) -> [Int16]? {
        guard enabled else { return nil }
        samples.append(contentsOf: chunk)
        guard !busy, samples.count >= Self.windowSamples else { return nil }
        let window = Array(samples.prefix(Self.windowSamples))
        samples.removeFirst(Self.windowSamples)
        busy = true
        return window
    }

    /// PTT released — final window when the tail qualifies; when an ASR
    /// call is still in flight the tail is dropped (honest silence).
    public mutating func takeTail() -> [Int16]? {
        guard enabled else {
            samples.removeAll()
            return nil
        }
        defer { samples.removeAll() }
        guard !busy, samples.count >= Self.minTailSamples else { return nil }
        busy = true
        return samples
    }

    /// The transcribe call settled — single-flight opens again.
    public mutating func endFlush() {
        busy = false
    }

    /// Teardown (leave / disable) — clears everything.
    public mutating func clear() {
        samples.removeAll()
        busy = false
    }
}
