import Foundation

// ─────────────────────────────────────────────────────────────
// Pulse — W5-f voice room PCM chunker (PURE, no AVFoundation).
//
// The capture contract (spec §1.1 VR-4, web voice-room-sheet.tsx):
//   · 16 kHz mono Int16 input
//   · full chunks every 250 ms = 4000 samples, base64(Int16LE bytes)
//   · seq starts at 1 and increments per emitted chunk
//   · a partial block (< 4000 samples) is flushed PROPORTIONALLY on
//     release — the emitted chunk simply carries the remaining
//     samples (its duration is the proportional fraction of 250 ms),
//     never padded, never dropped
//   · reset() returns the machine to the cold state (seq = 1)
//
// WEB DEFECT FIX #1: the web sheet armed `blockRef.buf` but never fed
// it from the capture callback, so NO voice:chunk ever left the
// client. This chunker is the single feed path for captured samples
// and its chunk/seq/partial/reset semantics are proof-tested in
// VoiceRoomMachineTests (the fix is proven by test, per spec VR-4).
// ─────────────────────────────────────────────────────────────

/// One emitted chunk — the exact C→S `voice:chunk` fields.
public struct VoicePcmChunkOut: Equatable, Sendable {
    public let seq: Int
    public let base64: String

    public init(seq: Int, base64: String) {
        self.seq = seq
        self.base64 = base64
    }
}

/// Pure block machine — feed Int16 samples, receive base64 chunks.
/// A value type: the audio engine owns one instance and mutates it on
/// its capture queue; unit tests drive it directly.
public struct VoicePcmChunker: Sendable {
    /// Samples per full chunk — 16 kHz × 250 ms.
    public let chunkSamples: Int
    private var buffer: [Int16] = []
    private var nextSeq: Int

    /// Cold state: empty buffer, first chunk will carry seq 1.
    public init(chunkSamples: Int = 4000) {
        self.chunkSamples = chunkSamples
        self.nextSeq = 1
    }

    /// Samples currently held (not yet part of an emitted chunk).
    public var pendingCount: Int { buffer.count }

    /// Next seq the machine would emit (1 before the first chunk).
    public var upcomingSeq: Int { nextSeq }

    /// True when at least one full chunk is waiting to be drained.
    public var hasFullChunk: Bool { buffer.count >= chunkSamples }

    /// Feeds samples and drains every COMPLETED block. Order is
    /// guaranteed: chunks come out in seq order, one per 4000 samples.
    public mutating func feed(_ samples: [Int16]) -> [VoicePcmChunkOut] {
        guard !samples.isEmpty else { return [] }
        buffer.append(contentsOf: samples)
        var out: [VoicePcmChunkOut] = []
        while buffer.count >= chunkSamples {
            let block = Array(buffer.prefix(chunkSamples))
            buffer.removeFirst(chunkSamples)
            out.append(Self.encode(seq: nextSeq, samples: block))
            nextSeq += 1
        }
        return out
    }

    /// Proportional partial flush on PTT release — emits whatever is
    /// left (< one full block) as the FINAL chunk of the burst. Nil
    /// when nothing is pending (the burst ended on a block boundary).
    public mutating func flushPartial() -> VoicePcmChunkOut? {
        guard !buffer.isEmpty else { return nil }
        let chunk = Self.encode(seq: nextSeq, samples: buffer)
        buffer.removeAll()
        nextSeq += 1
        return chunk
    }

    /// Teardown (VR-9): the next burst starts from seq 1 again.
    public mutating func reset() {
        buffer.removeAll()
        nextSeq = 1
    }

    /// Int16LE little-endian bytes → base64 (the wire `data` field).
    private static func encode(seq: Int, samples: [Int16]) -> VoicePcmChunkOut {
        var bytes = Data(capacity: samples.count * 2)
        for sample in samples {
            var le = sample.littleEndian
            withUnsafeBytes(of: &le) { bytes.append(contentsOf: $0) }
        }
        return VoicePcmChunkOut(seq: seq, base64: bytes.base64EncodedString())
    }
}
