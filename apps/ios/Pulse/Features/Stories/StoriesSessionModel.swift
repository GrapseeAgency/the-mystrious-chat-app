import Combine
import Foundation

/// Activity-scoped owner of the stories feed (Wave 4) — the Chats tray, the
/// dock Stories sheet, the viewer and the composer all share ONE instance
/// (owned by [PulseSession] like the call engine), so optimistic updates
/// (seen marks, deletes, publishes) show up everywhere at once.
///
/// Transport = REST only (web parity: TanStack 60s refetchInterval + query
/// invalidations — there are ZERO socket events for stories). Local
/// persistence = the GRDB v6 storyCache snapshot: the tray renders instantly
/// on cold start/offline and the next successful fetch overwrites it.
@MainActor
final class StoriesSessionModel: ObservableObject {
    /// D5 (web defect fixed): error ≠ empty — `lastError != nil` with no
    /// groups means the transport failed (retry offered); nil means an
    /// honestly empty feed.
    struct Flags: Equatable {
        var loading = false
        var loadedOnce = false
        var lastError: String?
    }

    @Published private(set) var groups: [WireStoryGroup] = []
    @Published private(set) var flags = Flags()
    @Published private(set) var viewers: [WireStoryViewer] = []
    @Published private(set) var viewersLoading = false

    private unowned let session: PulseSession
    private var cacheKey: String { "stories:\(session.viewer?.id ?? "")" }

    init(session: PulseSession) {
        self.session = session
        // Cold-start rehydrate — instant tray from the last good snapshot.
        if let store = session.store,
           let cached = try? store.loadStoryCache(key: cacheKey),
           let data = cached.groupsJson.data(using: .utf8),
           let page = try? JSONDecoder().decode(WireStoriesPage.self, from: data) {
            groups = Self.liveGroups(page.groups ?? [], nowMs: nowMs())
            flags.loadedOnce = true
        }
    }

    /// Pull the feed. quiet=false flips the loading flag (first load).
    func refresh(quiet: Bool = false) async {
        guard session.viewer != nil else { return }
        if !quiet { flags.loading = true; flags.lastError = nil }
        if let page = await session.api.stories() {
            let fresh = Self.liveGroups(page.groups ?? [], nowMs: nowMs())
            groups = fresh
            flags.loadedOnce = true
            flags.lastError = nil
            persistCache(page)
        } else if groups.isEmpty {
            // Nothing cached to serve — honest transport failure (D5).
            flags.loadedOnce = true
            flags.lastError = ChatsViewModel.describe(
                PulseAPIClient.Failure(kind: .network, message: nil)
            )
        }
        if !quiet { flags.loading = false }
    }

    /// 60s poll while a surface is active (web refetchInterval parity).
    private var pollTask: Task<Void, Never>?
    func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                await self?.refresh(quiet: true)
            }
        }
    }
    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    // ── publish (composer) ──────────────────────────────────

    /// POST /api/stories → refresh. Returns the server's error copy on failure.
    @discardableResult
    func publish(caption: String, background: String?, imagePath: String?) async -> String? {
        do {
            _ = try await session.api.postStory(caption: caption, background: background, imagePath: imagePath)
            await refresh(quiet: true)
            return nil
        } catch let failure as PulseAPIClient.Failure {
            return failure.message ?? "Could not post your status"
        } catch {
            return "Could not post your status"
        }
    }

    // ── viewer actions ──────────────────────────────────────

    /// Optimistic seen-flip (D6) — the POST result lands here; local update
    /// happens immediately so rings react instantly, server truth reconciles
    /// on the next fetch.
    func markViewedLocally(storyId: String, viewCount: Int?) {
        groups = groups.map { g in
            var group = g
            let stories = group.stories?.map { s in
                s.id == storyId
                    ? WireStoryItemCopy(s, viewedByMe: true, viewCount: viewCount ?? (s.viewCount ?? 0))
                    : s
            }
            group.stories = stories
            if group.mine != true {
                let all = (stories ?? []).allSatisfy { $0.viewedByMe == true }
                group.allSeen = all
            }
            return group
        }
    }

    /// Optimistic removal; the viewer's machine auto-advances (D3) when the
    /// refreshed feed reports the story gone.
    func deleteStory(id: String) async -> Bool {
        removeLocal(id)
        do {
            try await session.api.deleteStory(id: id)
            await refresh(quiet: true)
            return true
        } catch {
            await refresh(quiet: true)
            return false
        }
    }

    // ── owner viewers sheet (D7) ────────────────────────────

    /// One fetch — the sheet re-issues it every ≤5s while open.
    func loadViewers(storyId: String) async {
        viewersLoading = true
        if let list = try? await session.api.storyViewers(id: storyId) {
            viewers = list
        }
        viewersLoading = false
    }

    func resetViewers() {
        viewers = []
        viewersLoading = false
    }

    // ── internals ───────────────────────────────────────────

    private func removeLocal(_ storyId: String) {
        groups = groups.compactMap { g in
            var group = g
            group.stories = group.stories?.filter { $0.id != storyId }
            if (group.stories ?? []).isEmpty { return nil }
            return group
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
    /// grouping — no network probes; a group left empty disappears entirely.
    static func liveGroups(_ groups: [WireStoryGroup], nowMs: Int64) -> [WireStoryGroup] {
        groups.compactMap { g in
            var group = g
            group.stories = (g.stories ?? []).filter { (storyEpochMs($0.expiresAt)) > nowMs }
            guard !(group.stories ?? []).isEmpty else { return nil }
            return group
        }
    }
}
