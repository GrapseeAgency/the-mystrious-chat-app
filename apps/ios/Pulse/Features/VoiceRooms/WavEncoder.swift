import Foundation

// ─────────────────────────────────────────────────────────────
// Pulse — W5-f WAV encoder (PURE). Builds the 44-byte RIFF header
// for mono / 16 kHz / 16-bit PCM followed by the sample payload —
// the exact envelope POST /api/voice/transcribe expects for caption
// windows (web parity: one 4 s window ≈ 128 KB PCM + 44 bytes).
// Header layout verified against the canonical RIFF/WAVE spec and
// byte-tested in VoiceRoomMachineTests.
// ─────────────────────────────────────────────────────────────

public enum WavEncoder {
    /// Canonical PCM WAV header size for the mono/16-bit layout.
    public static let headerByteCount = 44

    /// Encodes Int16 samples as a complete WAV file payload.
    /// - Parameters:
    ///   - samples: interleaved PCM frames (mono → one per frame).
    ///   - sampleRate: 16 kHz for the rooms caption pipeline.
    ///   - channels: 1 (mono) for the rooms caption pipeline.
    public static func wavData(samples: [Int16], sampleRate: Int = 16_000, channels: Int = 1) -> Data {
        let bitsPerSample = 16
        let blockAlign = channels * (bitsPerSample / 8)
        let byteRate = sampleRate * blockAlign
        let payload = pcmPayload(samples)
        let dataByteCount = payload.count

        var data = Data(capacity: headerByteCount + dataByteCount)
        // "RIFF" + chunk size (36 + data size) + "WAVE"
        data.append(contentsOf: Array("RIFF".utf8))
        appendUInt32(&data, UInt32(36 + dataByteCount))
        data.append(contentsOf: Array("WAVE".utf8))
        // "fmt " chunk — 16 bytes of PCM format description
        data.append(contentsOf: Array("fmt ".utf8))
        appendUInt32(&data, 16)                       // fmt chunk size
        appendUInt16(&data, 1)                        // audio format: PCM (1)
        appendUInt16(&data, UInt16(channels))         // channel count
        appendUInt32(&data, UInt32(sampleRate))       // sample rate
        appendUInt32(&data, UInt32(byteRate))         // byte rate
        appendUInt16(&data, UInt16(blockAlign))       // block align
        appendUInt16(&data, UInt16(bitsPerSample))    // bits per sample
        // "data" chunk
        data.append(contentsOf: Array("data".utf8))
        appendUInt32(&data, UInt32(dataByteCount))
        data.append(payload)
        return data
    }

    /// Base64 of the whole WAV (the wire `audioBase64` value).
    public static func base64Wav(samples: [Int16], sampleRate: Int = 16_000, channels: Int = 1) -> String {
        wavData(samples: samples, sampleRate: sampleRate, channels: channels).base64EncodedString()
    }

    /// Little-endian Int16 payload.
    private static func pcmPayload(_ samples: [Int16]) -> Data {
        var bytes = Data(capacity: samples.count * 2)
        for sample in samples {
            var le = sample.littleEndian
            withUnsafeBytes(of: &le) { bytes.append(contentsOf: $0) }
        }
        return bytes
    }

    private static func appendUInt16(_ data: inout Data, _ value: UInt16) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }

    private static func appendUInt32(_ data: inout Data, _ value: UInt32) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }
}
