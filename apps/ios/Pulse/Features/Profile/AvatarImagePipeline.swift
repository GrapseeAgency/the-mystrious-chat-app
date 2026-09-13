import UIKit

/// Wave 6 avatar pipeline — PhotosPicker bytes → SQUARE CENTER-CROP ≤512px
/// JPEG q0.85 (web avatar-editor parity) → base64 data-URL for
/// POST /api/uploads. Used by the profile editor AND the new-channel photo.
public enum PulseAvatarImage {
    public static let maxSide: CGFloat = 512
    public static let quality: CGFloat = 0.85

    /// Re-renders the image as a centered square whose edge is ≤ maxSide,
    /// then encodes JPEG q0.85. Nil when the source bytes are not an image.
    public static func jpegData(from data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let side = min(image.size.width, image.size.height)
        guard side > 0 else { return nil }
        let scale = min(1, maxSide / side)
        let target = CGSize(width: (side * scale).rounded(.down), height: (side * scale).rounded(.down))
        // Center-crop rect in source points, drawn into a square canvas.
        let cropOrigin = CGPoint(
            x: (image.size.width - side) / 2,
            y: (image.size.height - side) / 2,
        )
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let rendered = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: CGPoint(x: -cropOrigin.x * scale, y: -cropOrigin.y * scale), size: image.size.applying(.init(scaleX: scale, y: scale))))
        }
        return rendered.jpegData(compressionQuality: quality)
    }

    /// Wire data-URL (`data:image/jpeg;base64,…`) for POST /api/uploads.
    public static func dataUrl(_ jpeg: Data) -> String {
        PulseMediaSupport.dataUrl(mime: "image/jpeg", data: jpeg)
    }
}
