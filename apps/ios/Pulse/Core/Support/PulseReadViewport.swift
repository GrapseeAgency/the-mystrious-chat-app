import SwiftUI

/// Lazy view creation is not visibility. Require a meaningful on-screen slice;
/// very short rows must fit entirely, tall rows need at least 44 points.
enum PulseReadViewport {
    static func visibleIDs(_ frames: [String: CGRect], height: CGFloat) -> Set<String> {
        guard height.isFinite, height > 0 else { return [] }
        return Set(frames.compactMap { id, frame in
            guard frame.minY.isFinite, frame.maxY.isFinite, frame.height > 0 else { return nil }
            let shown = max(0, min(frame.maxY, height) - max(frame.minY, 0))
            return shown >= min(44, frame.height) ? id : nil
        })
    }

    static func tailVisible(_ frame: CGRect?, height: CGFloat) -> Bool {
        guard let frame, height.isFinite, height > 0, frame.height > 0 else { return false }
        return frame.minY >= 0 && frame.maxY <= height
    }
}

struct PulseRoomViewport: Equatable {
    var messages: [String: CGRect] = [:]
    var tail: CGRect?
}

struct PulseRoomViewportKey: PreferenceKey {
    static var defaultValue = PulseRoomViewport()
    static func reduce(value: inout PulseRoomViewport, nextValue: () -> PulseRoomViewport) {
        let next = nextValue()
        value.messages.merge(next.messages, uniquingKeysWith: { _, newer in newer })
        if let tail = next.tail { value.tail = tail }
    }
}
