import UIKit
import UniformTypeIdentifiers

/// R42 - the system share extension: a thin intake. Reads the shared image
/// or text from the extension context, prepares it (image → ≤1280px JPEG
/// q0.82, exactly the main app's upload policy), writes one entry into the
/// App Group share-in inbox, and completes. No compose UI, no network, no
/// account access: the main app's conversation picker and durable outbox
/// own every later step. The inbox survives process death; only a durable
/// queue success or the user's explicit Remove consumes an entry.
@objc(ShareViewController)
final class ShareViewController: UIViewController {

    private static let appGroupIdentifier = "group.app.pulse.share"
    private static let inboxDirectoryName = "share-in"
    /// Mirrors the main app's media ceiling (4,500,000 base64 chars).
    private static let maxPreparedDataUrlLength = 4_500_000
    private static let maxImageDimension: CGFloat = 1280
    private static let imageQuality: CGFloat = 0.82

    // MARK: - Intake

    override func viewDidLoad() {
        super.viewDidLoad()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        intake(providers: providers)
    }

    private func intake(providers: [NSItemProvider]) {
        // Text first (a share sheet often offers both text + URL); then image.
        if let text = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) }) {
            text.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { [weak self] item, _ in
                let body = (item as? String) ?? (item as? NSURL)?.absoluteString
                self?.finish(text: body)
            }
            return
        }
        if let image = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.image.identifier) }) {
            image.loadItem(forTypeIdentifier: UTType.image.identifier, options: nil) { [weak self] item, _ in
                self?.finish(imageItem: item)
            }
            return
        }
        // Unsupported payload: complete without writing an entry.
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    private func finish(text: String?) {
        let body = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !body.isEmpty else {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }
        store(entry: Entry(kind: "text", text: body, dataUrl: nil, fileName: nil, fileSize: nil))
    }

    private func finish(imageItem: NSSecureCoding?) {
        let image: UIImage?
        if let data = imageItem as? Data {
            image = UIImage(data: data)
        } else if let url = imageItem as? URL {
            image = UIImage(contentsOfFile: url.path)
        } else if let ui = imageItem as? UIImage {
            image = ui
        } else {
            image = nil
        }
        guard let image, let jpeg = Self.preparedJPEG(image) else {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }
        store(entry: Entry(kind: "image", text: nil,
            dataUrl: "data:image/jpeg;base64," + jpeg.base64EncodedString(),
            fileName: nil, fileSize: jpeg.count))
    }

    /// The same ≤1280px JPEG q0.82 policy the main app applies.
    private static func preparedJPEG(_ image: UIImage) -> Data? {
        let maxSide = max(image.size.width, image.size.height)
        let scale = maxSide > maxImageDimension ? maxImageDimension / maxSide : 1
        let targetSize = CGSize(width: floor(image.size.width * scale), height: floor(image.size.height * scale))
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let rendered = UIGraphicsImageRenderer(size: targetSize, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        guard let data = rendered.jpegData(compressionQuality: imageQuality), !data.isEmpty else { return nil }
        // The base64 form must stay under the inbox ceiling; refuse honestly.
        let prefix = "data:image/jpeg;base64,".utf8.count
        guard (data.count + 2) / 3 * 4 + prefix <= maxPreparedDataUrlLength else { return nil }
        return data
    }

    // MARK: - App Group inbox (JSON per entry, atomic publish)

    private struct Entry: Codable {
        let kind: String
        let text: String?
        let dataUrl: String?
        let fileName: String?
        let fileSize: Int?
        let receivedAt: Double
    }

    private func store(entry: Entry) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            _ = Self.write(entry)
            DispatchQueue.main.async {
                // An inbox write failure completes quietly: the share is not
                // silently claimed, and the system returns to the sender app.
                self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            }
        }
    }

    private static func write(_ entry: Entry) -> Bool {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) else {
            return false
        }
        let dir = container.appendingPathComponent(inboxDirectoryName, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let id = UUID().uuidString
            let encoded = try JSONEncoder().encode(entry)
            // Atomic publish: a process death mid-write never yields a half entry.
            let tmp = dir.appendingPathComponent("\(id).json.tmp")
            let final = dir.appendingPathComponent("\(id).json")
            try encoded.write(to: tmp, options: .atomic)
            try FileManager.default.moveItem(at: tmp, to: final)
            return true
        } catch {
            return false
        }
    }
}
