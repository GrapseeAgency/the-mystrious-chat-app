import Foundation

/// R42 - the main-app side of the share extension's App Group inbox.
/// The extension prepares shared content (image → ≤1280px JPEG q0.82, or
/// text) and writes one JSON entry per share; the main app scans the inbox
/// on launch and each foreground, presents its picker, and delivers through
/// the SAME durable outbox as in-app sends. Only a durable queue success or
/// the user's explicit Remove consumes an entry; a process death anywhere in
/// between restores it on the next scan.
enum ShareInInbox {
    static let appGroupIdentifier = "group.app.pulse.share"
    private static let inboxDirectoryName = "share-in"

    struct Entry: Identifiable, Equatable, Sendable {
        let id: String
        let kind: String       // "text" | "image"
        let text: String?
        let dataUrl: String?
        let fileName: String?
        let fileSize: Int?
        let receivedAt: Double
    }

    /// Wire shape shared with the extension target's ShareViewController.
    /// Both sides must keep these field names byte-identical.
    struct InboxFile: Codable, Equatable, Sendable {
        let kind: String
        let text: String?
        let dataUrl: String?
        let fileName: String?
        let fileSize: Int?
        let receivedAt: Double
    }

    static var inboxDirectory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)?
            .appendingPathComponent(inboxDirectoryName, isDirectory: true)
    }

    /// Oldest first. Corrupt or unsupported entries are dropped by the scan,
    /// never crash it.
    static func scan() -> [Entry] {
        guard let dir = inboxDirectory,
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return [] }
        return files
            .filter { $0.pathExtension == "json" }
            .compactMap { url -> Entry? in
                guard let data = try? Data(contentsOf: url),
                      let decoded = try? JSONDecoder().decode(InboxFile.self, from: data) else { return nil }
                guard decoded.kind == "text" || decoded.kind == "image" else { return nil }
                if decoded.kind == "text", (decoded.text ?? "").isEmpty { return nil }
                if decoded.kind == "image", (decoded.dataUrl ?? "").isEmpty { return nil }
                return Entry(id: url.deletingPathExtension().lastPathComponent, kind: decoded.kind,
                             text: decoded.text, dataUrl: decoded.dataUrl, fileName: decoded.fileName,
                             fileSize: decoded.fileSize, receivedAt: decoded.receivedAt)
            }
            .sorted { $0.receivedAt < $1.receivedAt }
    }

    /// Consumption: only a durable queue success or the user's explicit Remove.
    static func remove(id: String) {
        guard let dir = inboxDirectory, !id.isEmpty,
              id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else { return }
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(id).json"))
    }

    /// Test seam: decode entries from raw file data (same contract as scan).
    static func entry(id: String, fileData: Data) -> Entry? {
        guard let decoded = try? JSONDecoder().decode(InboxFile.self, from: fileData) else { return nil }
        guard decoded.kind == "text" || decoded.kind == "image" else { return nil }
        if decoded.kind == "text", (decoded.text ?? "").isEmpty { return nil }
        if decoded.kind == "image", (decoded.dataUrl ?? "").isEmpty { return nil }
        return Entry(id: id, kind: decoded.kind, text: decoded.text, dataUrl: decoded.dataUrl,
                     fileName: decoded.fileName, fileSize: decoded.fileSize, receivedAt: decoded.receivedAt)
    }
}
