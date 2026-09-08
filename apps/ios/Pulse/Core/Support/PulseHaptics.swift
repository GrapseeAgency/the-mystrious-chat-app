import UIKit

/// Tactile feedback — light impact for taps, success notice for sends,
/// mirroring the web's press micro-interaction feel.
public enum PulseHaptics {
    public static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    public static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    public static func warning() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
}
