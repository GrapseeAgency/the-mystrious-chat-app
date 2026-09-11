import SwiftUI
import UIKit
import PhotosUI
import UniformTypeIdentifiers

/// Wave 1 media messaging support — the pure helpers behind the composer's
/// attach flow (spec §1.1 media upload contract):
///   • images are downscaled to ≤1280px and re-encoded JPEG q0.82 BEFORE the
///     base64 data-URL upload (web pulse-send parity),
///   • documents resolve to the exact mime whitelist the gateway accepts
///     (pdf/zip/txt/csv) and stay under the 10 MB ceiling,
///   • sizes humanize through ByteCountFormatter (web formatter parity).
enum PulseMediaSupport {
    /// Max image edge before the JPEG re-encode (spec §1.1, web parity).
    static let maxImageDimension: CGFloat = 1280
    static let imageQuality: CGFloat = 0.82
    /// Document ceiling — the server rejects heavier payloads (spec §1.1).
    static let maxDocumentBytes = 10 * 1024 * 1024
    /// Caption ceiling for staged media sends (≤500 chars, web parity).
    static let maxCaptionLength = 500

    // ── images ───────────────────────────────────────────────

    /// Re-renders the image so its longest edge is ≤ maxImageDimension and
    /// encodes it as JPEG q0.82 — the exact bytes the data-URL carries.
    static func downscaledJPEGData(from data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let longest = max(image.size.width, image.size.height)
        let scale = longest > maxImageDimension ? maxImageDimension / longest : 1
        let target = CGSize(width: (image.size.width * scale).rounded(.down), height: (image.size.height * scale).rounded(.down))
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let rendered = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return rendered.jpegData(compressionQuality: imageQuality)
    }

    // ── documents ────────────────────────────────────────────

    /// fileImporter whitelist — pdf/zip/txt/csv only (the mime map below is
    /// the full upload contract; UTType(filenameExtension:) is failable so
    /// the array is built with compactMap).
    static var documentTypes: [UTType] {
        [.pdf, .zip, .plainText, .commaSeparatedText, UTType(filenameExtension: "csv")].compactMap { $0 }
    }

    /// Extension → mime, the exact map the gateway's /api/uploads accepts
    /// for documents (unknown extensions are rejected before upload).
    static func mime(forExtension ext: String) -> String? {
        switch ext.lowercased() {
        case "pdf": return "application/pdf"
        case "zip": return "application/zip"
        case "txt": return "text/plain"
        case "csv": return "text/csv"
        default: return nil
        }
    }

    /// Base64 data-URL for the POST /api/uploads JSON body (NOT multipart).
    static func dataUrl(mime: String, data: Data) -> String {
        "data:\(mime);base64,\(data.base64EncodedString())"
    }

    /// ByteCountFormatter humanization for the file bubble ("1.2 MB").
    static func humanized(bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

/// Identifiable wrappers so `.fullScreenCover(item:)` can drive the media
/// surfaces (image lightbox, QuickLook document preview) from room state.
struct MediaLightboxTarget: Identifiable {
    let id = UUID()
    let url: URL
    let caption: String
}

struct QuickLookTarget: Identifiable {
    let id = UUID()
    let url: URL
}

/// Shared media-open plumbing for the river and threads: a document tap
/// downloads the stored file to tmp (QuickLook previews local URLs) and the
/// image path resolves through the gateway helper.
enum PulseMediaOpener {
    /// Downloads a message document to the tmp directory for QuickLook.
    /// Throws on network/write failure — callers surface the error honestly.
    static func downloadForPreview(url: URL, fileName: String?) async throws -> URL {
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        let (data, _) = try await URLSession.shared.data(for: request)
        let safeName = (fileName ?? url.lastPathComponent)
            .replacingOccurrences(of: "/", with: "-")
        guard !safeName.isEmpty else { return url }
        let target = FileManager.default.temporaryDirectory.appendingPathComponent(safeName)
        try? FileManager.default.removeItem(at: target)
        try data.write(to: target)
        return target
    }

    /// Resolves a message's media URL: images win, then stored documents.
    static func url(for message: WireChatMessage) -> URL? {
        if message.imagePath != nil {
            return PulseEndpoints.mediaURL(message.imagePath)
        }
        return PulseEndpoints.mediaURL(message.filePath)
    }
}

/// Full-screen image lightbox — black stage, pinch + double-tap zoom,
/// caption, Done. Native mirror of the web lightbox dialog (no DOM).
struct MediaLightboxView: View {
    let url: URL?
    let caption: String

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var zoom: CGFloat = 1
    @State private var pinchBase: CGFloat = 1

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 12) {
                Spacer(minLength: 0)
                if let url {
                    ScrollView([.horizontal, .vertical]) {
                        AsyncImage(url: url) { phase in
                            if let image = phase.image {
                                image.resizable().scaledToFit()
                            } else {
                                ProgressView().tint(.white)
                                    .frame(width: 240, height: 240)
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .scaleEffect(zoom)
                        .gesture(pinchGesture)
                        .onTapGesture(count: 2) { toggleZoom() }
                    }
                    .ignoresSafeArea(edges: .bottom)
                } else {
                    Label("Unavailable", systemImage: "photo.badge.exclamationmark")
                        .foregroundStyle(.white.opacity(0.7))
                }
                Spacer(minLength: 0)
                if !caption.isEmpty {
                    Text(caption)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.85))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 12)
                }
            }
        }
        .overlay(alignment: .topTrailing) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(.white.opacity(0.16)))
                    .contentShape(Circle())
            }
            .buttonStyle(PulseButtonStyle())
            .padding(.trailing, 16)
            .padding(.top, 8)
            .accessibilityLabel("Close")
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: zoom)
    }

    private var pinchGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                zoom = max(1, pinchBase * value)
            }
            .onEnded { _ in
                pinchBase = zoom
                if zoom < 1 { resetZoom() }
            }
    }

    private func toggleZoom() {
        if zoom > 1 {
            resetZoom()
        } else {
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                zoom = 2.5
                pinchBase = 2.5
            }
        }
    }

    private func resetZoom() {
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
            zoom = 1
            pinchBase = 1
        }
    }
}
