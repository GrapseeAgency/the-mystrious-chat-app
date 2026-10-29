import SwiftUI
import UIKit
import PhotosUI
import UniformTypeIdentifiers
import ImageIO
import CoreTransferable

/// Wave 1 media messaging support - the pure helpers behind the composer's
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
    /// Document ceiling - the server rejects heavier payloads (spec §1.1).
    static let maxDocumentBytes = 10 * 1024 * 1024
    /// Caption ceiling for staged media sends.
    /// R42 parity: captions ride the same 2000-character message limit the
    /// server, the Android mirror composer and AttachmentValidation enforce.
    static let maxCaptionLength = 2000

    // images

    static let maxImageSourceBytes = 64 * 1024 * 1024

    /// R43 - peak-memory bound for the UNBOUNDED path: ONE source decode +
    /// JPEG re-encode at a time for background callers (the Photos transfer,
    /// fileImporter, the share-in sheet). Main-thread callers skip the lock:
    /// the main actor already serializes them, blocking main on a background
    /// decode would jank the UI, and the camera path's extra allocation is
    /// the bounded ≤1280px render, never a source decode. Android holds the
    /// same single-flight permit (ImagePreparation.withPreparationPermit).
    private static let decodeLock = NSLock()

    static func withDecodeGate<T>(_ body: () throws -> T) rethrows -> T {
        if Thread.isMainThread { return try body() }
        decodeLock.lock()
        defer { decodeLock.unlock() }
        return try body()
    }

    /// Camera already supplies a UIImage. Render only the bounded output instead
    /// of allocating another full-resolution JPEG and decoding it again.
    @MainActor
    static func downscaledJPEGData(from image: UIImage) -> Data? {
        let width = image.size.width * image.scale, height = image.size.height * image.scale
        guard width.isFinite, height.isFinite, width > 0, height > 0 else { return nil }
        let scale = min(1, maxImageDimension / max(width, height))
        let size = CGSize(width: max(1, (width * scale).rounded(.down)), height: max(1, (height * scale).rounded(.down)))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1; format.opaque = true
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill(); context.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return rendered.jpegData(compressionQuality: imageQuality)
    }

    static func downscaledJPEGData(from data: Data) -> Data? {
        guard data.count <= maxImageSourceBytes,
              let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return withDecodeGate { downscaledJPEGData(source: source) }
    }

    /// PhotosPicker supplies a temporary file. Decode it inside the transfer closure,
    /// while its access is valid, without loading a full-resolution Data/UIImage first.
    static func downscaledJPEGData(from url: URL) -> Data? {
        guard url.isFileURL,
              let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size <= maxImageSourceBytes,
              let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return withDecodeGate { downscaledJPEGData(source: source) }
    }

    private static func downscaledJPEGData(source: CGImageSource) -> Data? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxImageDimension),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              image.width > 0, image.height > 0,
              image.width <= Int(maxImageDimension), image.height <= Int(maxImageDimension),
              let context = CGContext(data: nil, width: image.width, height: image.height,
                bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        // Match Android's explicit white background for transparent PNG/HEIC input.
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        let rect = CGRect(x: 0, y: 0, width: CGFloat(image.width), height: CGFloat(image.height))
        context.fill(rect)
        context.draw(image, in: rect)
        guard let opaque = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, opaque, [kCGImageDestinationLossyCompressionQuality: imageQuality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    // documents

    /// fileImporter whitelist - pdf/zip/txt/csv only (the mime map below is
    /// the full upload contract; UTType(filenameExtension:) is failable so
    /// the array is built with compactMap).
    static var documentTypes: [UTType] {
        [.pdf, .zip, .plainText, .commaSeparatedText, UTType(filenameExtension: "csv")].compactMap { $0 }
    }

    /// R43 - a hung document provider (an iCloud placeholder, a stalled file
    /// provider) blocks INSIDE stream.read, past every checkCancellation in
    /// the chunk loop - the exact "Blocked OS reads" checklist gap. Closing
    /// the stream unblocks pipe-backed provider reads on the spot, so this
    /// read races cancellation AND the deadline, and whichever fires first
    /// closes the stream. (A provider that ignores close stays wedged until
    /// the OS gives up - a documented platform limit, not a silent hang.)
    static let documentReadDeadline: TimeInterval = 120

    static func readDocument(url: URL) async throws -> Data {
        guard url.isFileURL else { throw CocoaError(.fileReadUnsupportedScheme) }
        guard let stream = InputStream(url: url) else { throw CocoaError(.fileReadUnknown) }
        return try await readDocument(stream: stream)
    }

    static func readDocument(stream: InputStream, deadline: TimeInterval = documentReadDeadline) async throws -> Data {
        // An already-cancelled caller fails here and now (the bridge below
        // only propagates cancellation that lands mid-read).
        try Task.checkCancellation()
        let lock = NSLock()
        var closed = false
        func closeOnce() {
            lock.lock(); defer { lock.unlock() }
            if !closed { closed = true; stream.close() }
        }
        let watchdog = DispatchWorkItem { closeOnce() }
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + deadline, execute: watchdog)
        defer { watchdog.cancel(); closeOnce() }
        let read: () throws -> Data = {
            stream.open()
            defer { closeOnce() }
            var result = Data()
            var buffer = [UInt8](repeating: 0, count: 64 * 1024)
            while true {
                try Task.checkCancellation()
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count < 0 { throw stream.streamError ?? CocoaError(.fileReadUnknown) }
                if count == 0 { break }
                guard result.count + count <= maxDocumentBytes else {
                    throw PulseAPIClient.Failure(kind: .validation, message: "Documents are limited to 10 MB.")
                }
                result.append(contentsOf: buffer.prefix(count))
            }
            try Task.checkCancellation()
            guard !result.isEmpty else { throw PulseAPIClient.Failure(kind: .validation, message: "The selected document is empty.") }
            return result
        }
        // The blocking loop runs on a GLOBAL GCD thread, never on the
        // cooperative pool (a hung provider would otherwise pin a pool
        // thread for the whole 120s deadline window), and the caller's
        // cancellation is propagated by hand: outer cancel -> inner cancel
        // -> closeOnce() unblocks the wedged provider read. The inner
        // closure is explicitly async so the cancellation-handler overload
        // resolves cleanly (a sync closure inside picked the async overload).
        let inner = Task.detached(priority: .userInitiated) { () async throws -> Data in
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
                    DispatchQueue.global(qos: .userInitiated).async {
                        cont.resume(with: Result { try read() })
                    }
                }
            } onCancel: {
                closeOnce()
            }
        }
        return try await withTaskCancellationHandler {
            try await inner.value
        } onCancel: {
            inner.cancel()
        }
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

/// Only the small prepared JPEG escapes the temporary-file transfer scope.
struct PulsePickedPhoto: Transferable {
    let jpeg: Data
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            try Task.checkCancellation()
            guard let jpeg = PulseMediaSupport.downscaledJPEGData(from: received.file) else { throw CocoaError(.fileReadCorruptFile) }
            try Task.checkCancellation()
            return PulsePickedPhoto(jpeg: jpeg)
        }
    }
}

/// Identifiable wrappers so `.fullScreenCover(item:)` can drive the media
/// surfaces (image lightbox, QuickLook document preview) from room state.
struct MediaLightboxTarget: Identifiable {
    let id = UUID()
    let url: URL
    let caption: String
    var expiresAt: String? = nil
    var singleUse = false
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
    /// Throws on network/write failure - callers surface the error honestly.
    static func downloadForPreview(url: URL, fileName: String?) async throws -> URL {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        let (download, response) = try await URLSession.shared.download(for: request)
        defer { try? FileManager.default.removeItem(at: download) }
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw PulseAPIClient.Failure(kind: .server, message: "The attachment could not be downloaded.", status: (response as? HTTPURLResponse)?.statusCode)
        }
        let size = try download.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= PulseMediaSupport.maxDocumentBytes else {
            throw PulseAPIClient.Failure(kind: .validation, message: "The attachment is empty or exceeds the download limit.")
        }
        // A remote name is display metadata, never a shared filesystem address.
        // Unique folders prevent same-name downloads from replacing an open preview.
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("pulse-preview-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appendingPathComponent(safePreviewName(fileName ?? url.lastPathComponent))
        do {
            try FileManager.default.copyItem(at: download, to: target)
            try Task.checkCancellation()
            return target
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
    }

    static func safePreviewName(_ name: String) -> String {
        let clean = name.components(separatedBy: .controlCharacters).joined()
            .replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: "\\", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean != ".", clean != ".." else { return "attachment" }
        var bounded = String(clean.prefix(120))
        while bounded.utf8.count > 200 { bounded.removeLast() }
        return bounded.isEmpty ? "attachment" : bounded
    }

    /// Resolves a message's media URL: images win, then stored documents.
    static func url(for message: WireChatMessage) -> URL? {
        if message.imagePath != nil {
            return PulseEndpoints.mediaURL(message.imagePath)
        }
        return PulseEndpoints.mediaURL(message.filePath)
    }
}

/// Full-screen image lightbox - black stage, pinch + double-tap zoom,
/// caption, Done. Native mirror of the web lightbox dialog (no DOM).
struct MediaLightboxView: View {
    let url: URL?
    let caption: String
    var expiresAt: String? = nil

    var singleUse = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var captureActive = UIScreen.main.isCaptured

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
                        Group {
                            if singleUse {
                                if scenePhase == .active && !captureActive { SingleUsePhoto(url: url) }
                            } else {
                                AsyncImage(url: url) { phase in
                                    if let image = phase.image { image.resizable().scaledToFit() }
                                    else if phase.error != nil { Text("Photo unavailable").foregroundStyle(.white) }
                                    else { ProgressView().tint(.white).frame(width: 240, height: 240) }
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .scaleEffect(zoom)
                        .gesture(pinchGesture)
                        .onTapGesture(count: 2) { toggleZoom() }
                    }
                    .ignoresSafeArea(edges: .bottom)
                } else {
                    Label("Unavailable", systemImage: "exclamationmark.circle.fill")
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
            }.opacity(singleUse && (scenePhase != .active || captureActive) ? 0 : 1)
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
        .onChange(of: scenePhase) { _, phase in if singleUse && phase != .active { dismiss() } }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            captureActive = UIScreen.main.isCaptured
            if singleUse && captureActive { dismiss() }
        }
        .onAppear { if singleUse && captureActive { dismiss() } }
        .task(id: expiresAt) {
            guard let stamp = expiresAt, let deadline = PulseFormat.date(stamp) else { return }
            while !Task.isCancelled {
                let remaining = deadline.timeIntervalSinceNow
                if remaining <= 0 { dismiss(); return }
                do { try await Task.sleep(nanoseconds: UInt64(min(remaining, 30) * 1_000_000_000)) }
                catch { return }
            }
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

/// No shared URL/image cache for a granted one-time photo. The bytes live only in this view.
private struct SingleUsePhoto: View {
    let url: URL
    @State private var image: UIImage?
    @State private var failed = false
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFit() }
            else if failed { Text("Photo couldn't load. One-time access was already consumed.").foregroundStyle(.white) }
            else { ProgressView().tint(.white).frame(width: 240, height: 240) }
        }
        .task(id: url) {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.urlCache = nil
            let client = URLSession(configuration: configuration)
            defer { client.invalidateAndCancel() }
            do {
                let (data, response) = try await client.data(for: URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData))
                try Task.checkCancellation()
                guard (response as? HTTPURLResponse)?.statusCode == 200, let decoded = UIImage(data: data) else {
                    failed = true; return
                }
                image = decoded
            } catch { if !Task.isCancelled { failed = true } }
        }
        .onDisappear { image = nil }
    }
}
