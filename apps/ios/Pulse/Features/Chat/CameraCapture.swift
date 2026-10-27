import AVFoundation
import SwiftUI
import UIKit

/// D30 camera capture - the system camera app wrapped for SwiftUI. Captured
/// full-resolution shots flow through the SAME pipeline the PhotosPicker path
/// uses: `stageImage(data)` → ≤1280px JPEG q0.82 re-encode → staged card →
/// POST /api/uploads → message with imagePath (wire-identical to web).
struct CameraPicker: UIViewControllerRepresentable {
    var onCapture: (UIImage) -> Void
    var onCancel: () -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIViewController {
        guard Self.isAvailable else {
            DispatchQueue.main.async { onCancel(); dismiss() }
            return UIViewController()
        }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let parent: CameraPicker

        init(_ parent: CameraPicker) {
            self.parent = parent
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any],
        ) {
            // Original (full-resolution) JPEG - the staged pipeline downscales.
            if let image = info[.originalImage] as? UIImage {
                parent.onCapture(image)
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.onCancel()
            parent.dismiss()
        }
    }

    /// TCC gate - NSCameraUsageDescription already ships in project.yml
    /// (infoProperties). Denied callers surface the inline explainer state.
    @MainActor static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    @MainActor static func requestAccess(_ completion: @escaping (Bool) -> Void) {
        guard isAvailable else { completion(false); return }
        AVCaptureDevice.requestAccess(for: .video) { granted in
            completion(granted)
        }
    }
}
