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
///
/// R45 re-audit: the bearer token attaches ONLY when the URL targets the
/// configured gateway origin. `PulseEndpoints.mediaURL`/
/// `PulseTheme.photoURL` pass absolute external http(s) URLs straight
/// through (link-preview images, legacy absolute avatar paths), and a
/// host-blind token would leak the session credential to third-party
/// servers. External URLs always ride headerless.
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
            let fetched = await PulseMediaFetch.fetch(url)
            if !Task.isCancelled { image = fetched }
        }
    }
}

/// R45 re-audit: the fetch helpers live on a NON-generic namespace enum -
/// referencing a static member directly on the generic `PulseMediaImage`
/// struct leaves Placeholder/Content uninferred (CI round 1 of this round).
public enum PulseMediaFetch {
    /// One shared session; the token is read per request so rotations apply
    /// immediately. Never throws for missing tokens (header-less window).
    public static func fetch(_ url: URL) async -> UIImage? {
        let request = authorizedRequest(for: url)
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
        return UIImage(data: data)
    }

    /// R45 re-audit: build the request with the bearer token attached ONLY
    /// when the URL targets the configured gateway origin (scheme + host +
    /// port). Shared by every authenticated media surface, including callers
    /// that need their own URLSession configuration (SingleUsePhoto keeps an
    /// ephemeral no-cache client so view-once bytes never hit the shared URL
    /// cache).
    public static func authorizedRequest(for url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        let gateway = PulseEndpoints.gatewayURL
        if let token = PulseKeychain.shared.loadSessionToken(), !token.isEmpty,
           url.scheme == gateway.scheme, url.host == gateway.host, url.port == gateway.port {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }
}
