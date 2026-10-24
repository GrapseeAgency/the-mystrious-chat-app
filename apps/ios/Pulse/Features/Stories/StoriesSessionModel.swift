import Combine
import Foundation

/// Activity-scoped owner of the stories feed (Wave 4) - the Chats tray, the
/// dock Stories sheet, the viewer and the composer all share ONE instance
/// (owned by [PulseSession] like the call engine), so optimistic updates
/// (seen marks, deletes, publishes) show up everywhere at once.
///
/// Transport = REST only (web parity: TanStack 60s refetchInterval + query
/// invalidations - there are ZERO socket events for stories). Local
/// persistence = the GRDB v6 storyCache snapshot: the tray renders instantly
/// on cold start/offline and the next successful fetch overwrites it.
@MainActor
public final class StoriesSessionModel: ObservableObject {
    /// D5 (web defect fixed): error ≠ empty - `lastError != nil` with no
    /// groups means the transport failed (retry offered); nil means an
    /// honestly empty feed.
    public struct Flags: Equatable {
        public var loading = false
        public var loadedOnce = false
        public var lastError: String?
    }

    @Published public private(set) var groups: [WireStoryGroup] = []
    @Published public private(set) var flags = Flags()
    @Published public private(set) var viewers: [WireStoryViewer] = []
    @Published public private(set) var viewersLoading = false
    @Published public private(set) var viewersError: String?
    private var viewersStoryId: String?

    private var viewersGeneration = UUID()
    private var refreshGeneration = UUID()
    private unowned let session: PulseSession
    private let ownerEpoch: UUID
    private var cacheKey: String { "stories:\(session.viewer?.id ?? "")" }

    public init(session: PulseSession) {
        self.session = session
        self.ownerEpoch = session.sessionGeneration
        // Cold-start rehydrate - instant tray from the last good snapshot.
        if let store = session.store,
           let cached = try? store.loadStoryCache(key: cacheKey),
           let data = cached.groupsJson.data(using: .utf8),
           let page = try? JSONDecoder().decode(WireStoriesPage.self, from: data) {
            groups = Self.liveGroups(page.groups ?? [], nowMs: nowMs())
            flags.loadedOnce = true
        }
    }

    /// Pull the feed. quiet=false flips the loading flag (first load).
    public func refresh(quiet: Bool = false) async {
        guard !Task.isCancelled, session.viewer != nil, session.sessionGeneration == ownerEpoch else { return }
        groups = Self.liveGroups(groups, nowMs: nowMs())
        let ticket = UUID()
        refreshGeneration = ticket
        let epoch = session.sessionGeneration
        let api = session.api
        if !quiet { flags.loading = true; flags.lastError = nil }
        let response = await api.stories()
        guard !Task.isCancelled, refreshGeneration == ticket, session.sessionGeneration == epoch else { return }
        if let page = response {
            let fresh = Self.liveGroups(page.groups ?? [], nowMs: nowMs())
            groups = fresh
            flags.loadedOnce = true
            flags.lastError = nil
            persistCache(page)
        } else if groups.isEmpty {
            // Nothing cached to serve - honest transport failure (D5).
            flags.loadedOnce = true
            flags.lastError = ChatsViewModel.describe(
                PulseAPIClient.Failure(kind: .network, message: nil)
            )
        }
        flags.loading = false
    }

    /// 60s poll while a surface is active (web refetchInterval parity).
    private var pollTask: Task<Void, Never>?
    public func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 60_000_000_000) } catch { break }
                guard !Task.isCancelled else { break }
                guard let self, self.session.sessionGeneration == self.ownerEpoch else { break }
                await self.refresh(quiet: true)
            }
        }
    }
    public func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    // publish (composer)

    /// POST /api/stories → refresh. Returns the server's error copy on failure.
    @discardableResult
    public func publish(caption: String, background: String?, imagePath: String?) async -> String? {
        guard session.sessionGeneration == ownerEpoch else { return "The original story session ended." }
        let epoch = session.sessionGeneration
        let api = session.api
        do {
            try Task.checkCancellation()
            _ = try await api.postStory(caption: caption, background: background, imagePath: imagePath)
            try Task.checkCancellation()
            guard session.sessionGeneration == epoch else { return "The original story session ended." }
            // Feed refresh is not part of the publication acknowledgement.
            Task { [weak self] in
                guard let self, self.session.sessionGeneration == epoch else { return }
                await self.refresh(quiet: true)
            }
            return nil
        } catch let failure as PulseAPIClient.Failure {
            return failure.message ?? "Could not post your status"
        } catch {
            return "Could not post your status"
        }
    }

    // viewer actions

    /// Apply a confirmed view receipt to the shared feed. A presentation's
    /// failed/cancelled attempt must not publish a successful viewed state.
    public func markViewedLocally(storyId: String, viewCount: Int?) {
        guard !Task.isCancelled, session.sessionGeneration == ownerEpoch else { return }
        groups = groups.map { g in
            let stories = (g.stories ?? []).map { s in
                s.id == storyId
                    ? WireStoryItemCopy(s, viewedByMe: true, viewCount: viewCount ?? (s.viewCount ?? 0))
                    : s
            }
            let allSeen = g.mine == true ? g.allSeen : stories.allSatisfy { $0.viewedByMe == true }
            return WireStoryGroupCopy(g, allSeen: allSeen, stories: stories)
        }
    }

    /// Only a confirmed deletion removes the story and advances its viewer.
    /// An older feed read cannot restore the row after that acknowledgement.
    public func deleteStory(id: String) async -> Bool {
        guard !Task.isCancelled, session.sessionGeneration == ownerEpoch else { return false }
        let api = session.api
        do {
            try await api.deleteStory(id: id)
            guard !Task.isCancelled, session.sessionGeneration == ownerEpoch else { return false }
            refreshGeneration = UUID()
            removeLocal(id)
            persistCache(WireStoriesPage(groups: groups))
            return true
        } catch {
            return false
        }
    }

    // owner viewers sheet (D7)

    /// One fetch - the sheet re-issues it every ≤5s while open.
    public func loadViewers(storyId: String) async {
        guard !Task.isCancelled, session.sessionGeneration == ownerEpoch else { return }
        if viewersStoryId != storyId { resetViewers(); viewersStoryId = storyId }
        let ticket = UUID()
        viewersGeneration = ticket
        let api = session.api
        viewersLoading = true
        do {
            let list = try await api.storyViewers(id: storyId)
            guard !Task.isCancelled, viewersGeneration == ticket, session.sessionGeneration == ownerEpoch else { return }
            viewers = list; viewersError = nil
        } catch {
            guard !Task.isCancelled, viewersGeneration == ticket, session.sessionGeneration == ownerEpoch else { return }
            if Self.discardsViewers(after: error) { viewers = [] }
            viewersError = (error as? PulseAPIClient.Failure)?.message ?? "Viewers couldn't be loaded. Reconnect to retry."
        }
        viewersLoading = false
    }

    static func discardsViewers(after error: Error) -> Bool {
        guard let failure = error as? PulseAPIClient.Failure else { return false }
        return [.auth, .forbidden, .notFound].contains(failure.kind)
    }

    public func resetViewers() {
        viewersGeneration = UUID()
        viewersStoryId = nil
        viewers = []
        viewersError = nil
        viewersLoading = false
    }

    // internals

    private func removeLocal(_ storyId: String) {
        groups = groups.compactMap { g in
            let stories = (g.stories ?? []).filter { $0.id != storyId }
            if stories.isEmpty { return nil }
            return WireStoryGroupCopy(g, stories: stories)
        }
    }

    private func persistCache(_ page: WireStoriesPage) {
        guard let store = session.store,
              let data = try? JSONEncoder().encode(page),
              let json = String(data: data, encoding: .utf8) else { return }
        try? store.saveStoryCache(key: cacheKey, groupsJson: json, updatedAt: nowMs())
    }

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// D2 (web defect fixed): expired stories are dropped OFFLINE before any
    /// grouping - no network probes; a group left empty disappears entirely.
    public static func liveGroups(_ groups: [WireStoryGroup], nowMs: Int64) -> [WireStoryGroup] {
        groups.compactMap { g in
            let stories = (g.stories ?? []).filter { (storyEpochMs($0.expiresAt)) > nowMs }
            guard !stories.isEmpty else { return nil }
            return WireStoryGroupCopy(g, stories: stories)
        }
    }
}
