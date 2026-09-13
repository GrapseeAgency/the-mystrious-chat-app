import Foundation

/// Safety-number client split — mirrors the web's safety-sheet.tsx
/// `safetyGroups` exactly: the SERVER computes the deterministic 60-digit
/// number (sha256("minId|maxId|pulse-safety-pepper-v1") mod 10^60, wire
/// format "12×5 digits space-joined"); the client only re-splits the
/// string into its 12 five-digit tiles. Digits-only + zero-pad to 60 so
/// any server drift still renders a full grid (never a partial one).
public enum PulseSafetyNumber {
    public static let digitCount = 60
    public static let groupSize = 5
    public static let groupCount = 12

    /// "12345 67890 …" → ["12345", "67890", …] (exactly 12 groups of 5).
    public static func groups(_ safetyNumber: String) -> [String] {
        var digits = ""
        digits.reserveCapacity(digitCount)
        for scalar in safetyNumber.unicodeScalars where scalar >= "0" && scalar <= "9" {
            if digits.count < digitCount { digits.unicodeScalars.append(scalar) }
        }
        if digits.count < digitCount {
            digits += String(repeating: "0", count: digitCount - digits.count)
        }
        var out: [String] = []
        out.reserveCapacity(groupCount)
        var start = digits.startIndex
        for _ in 0..<groupCount {
            let end = digits.index(start, offsetBy: groupSize)
            out.append(String(digits[start..<end]))
            start = end
        }
        return out
    }
}
