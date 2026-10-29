import SwiftUI
import UIKit

/// R44 section-5 "Media URL authorization/revocation" - the authenticated
/// image fetch for every /api/uploads/ surface. SwiftUI's AsyncImage cannot
/// attach request headers, so previously-reachable media URLs were fetched
/// anonymously and could never be revoked per membership/lifetime. This
/// component mirrors AsyncImage's ergonomics (url + placeholder/content
/// closures) but rides a URLSession whose requests carry
/// `Authorization: Bearer <session token>` when one exists. Header-less
/// requests keep the documented migration window (web + pre-token natives).
public struct PulseMediaImage<Placeholder: View, Content: View>: View {
    private let url: URL?
    private let content: (UIImage) -> Content
    private let placeholder: () -> Placeholder
    @State private var image: UIImage?

    public init(url: URL?, @ViewBuilder content: @escaping (UIImage) -> Content,
                @ViewBuilder placeholder: @escaping () -> Placeholder) {
        self.url = url
        self.content = content
        self.placeholder = placeholder
    }

    public var body: some View {
        Group {
            if let image {
                content(image)
            } else {
                placeholder()
            }
        }
        .task(id: url) {
            guard let url, image == nil else { return }
            let fetched = await Self.fetch(url)
            if !Task.isCancelled { image = fetched }
        }
    }

    /// One shared session; the token is read per request so rotations apply
    /// immediately. Never throws for missing tokens (header-less window).
    public static func fetch(_ url: URL) async -> UIImage? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        if let token = PulseKeychain.shared.loadSessionToken(), !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
        return UIImage(data: data)
    }
}
