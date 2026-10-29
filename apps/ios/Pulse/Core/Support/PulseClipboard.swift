import Foundation
import UniformTypeIdentifiers
import UIKit

/// Section-5 "Copies outside message storage": message text handed to the
/// system pasteboard is a copy the app no longer controls. Items carry a
/// short expiration date so other apps stop reading the copy after a minute;
/// the device-local clipboard slot itself is the platform's to expire (there
/// is no API to purge it remotely).
enum PulseClipboard {
    static func copyText(_ text: String) {
        UIPasteboard.general.setItems(
            [[UTType.utf8PlainText.identifier: text]],
            options: [.expirationDate: Date().addingTimeInterval(60)],
        )
    }
}
