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
        // Section 9 items 1/4/5/9 - restore-time flush of the durable story
        // journal (publish replays, view marks, delete tombstones).
        Task { [weak self] in await self?.flushStoryIntents() }
    }

    /// Replays every pending story intent with its ORIGINAL identity. A
    /// publication replay returns the SAME server row (never a duplicate);
    /// view marks are idempotent by (storyId, userId); a delete tombstone
    /// re-purges the cache, which is exactly why the row survived.
    public func flushStoryIntents() async {
        guard session.viewer != nil, session.sessionGeneration == ownerEpoch else { return }
        let journal = PulseSection8Journal.shared
        let owner = session.api.userId
        for intent in journal.pending(owner: owner) where intent.kind.hasPrefix("story-") {
            switch intent.kind {
            case "story-publish":
                do {
                    _ = try await session.api.postStory(
                        caption: intent.payload["caption"] ?? "",
                        background: intent.payload["background"],
                        imagePath: intent.payload["imagePath"],
                        clientId: intent.clientId)
                    journal.confirm(owner: owner, clientId: intent.clientId)
                } catch let failure as PulseAPIClient.Failure where failure.kind == .validation {
                    journal.confirm(owner: owner, clientId: intent.clientId) // permanent refusal
                } catch { break } // transport-class: keep for the next flush
            case "story-view":
                do {
                    _ = try await session.api.markStoryViewed(id: intent.targetId)
                    journal.confirm(owner: owner, clientId: intent.clientId)
                } catch let failure as PulseAPIClient.Failure
                      where failure.kind == .notFound || failure.kind == .forbidden || failure.kind == .validation {
                    journal.confirm(owner: owner, clientId: intent.clientId) // story gone: retire honestly
                } catch { break }
            case "story-delete":
                do {
                    try await session.api.deleteStory(id: intent.targetId)
                    removeLocal(intent.targetId)
                    if persistCache(WireStoriesPage(groups: groups)) {
                        journal.confirm(owner: owner, clientId: intent.clientId)
                    }
                } catch let failure as PulseAPIClient.Failure where failure.kind == .notFound {
                    // Already gone server-side: the purge is the remaining duty.
                    removeLocal(intent.targetId)
                    if persistCache(WireStoriesPage(groups: groups)) {
                        journal.confirm(owner: owner, clientId: intent.clientId)
                    }
                } catch let failure as PulseAPIClient.Failure where failure.kind == .forbidden || failure.kind == .validation {
                    journal.confirm(owner: owner, clientId: intent.clientId)
                } catch { break }
            default: break
            }
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
        do {
            // Section 9 item 2: bounded keyset pages walked to completion (the
            // 24h TTL caps the feed); every request stays <= 60 story rows.
            var collected: [WireStoryGroup] = []
            var cursor: String? = nil
            var pages = 0
            while pages < 5 {
                let page = try await api.storiesPage(limit: 60, cursor: cursor)
                collected += page.groups ?? []
                pages += 1
                guard page.hasMore == true, let next = page.nextCursor, !next.isEmpty else { break }
                cursor = next
            }
            // The same identity guards the legacy snapshot enforced.
            let rows = collected.flatMap { $0.stories ?? [] }
            guard collected.allSatisfy({ !($0.user?.id ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.stories != nil }),
                  Set(collected.compactMap { $0.user?.id }).count == collected.count,
                  rows.allSatisfy({ !($0.id ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }),
                  Set(rows.compactMap(\.id)).count == rows.count else {
                throw PulseAPIClient.Failure(kind: .server, message: "The gateway did not return a valid story feed.")
            }
            guard !Task.isCancelled, refreshGeneration == ticket, session.sessionGeneration == epoch else { return }
            groups = Self.liveGroups(PulseStoryPages.merged(groups: collected), nowMs: nowMs())
            flags.loadedOnce = true; flags.lastError = nil
            _ = persistCache(WireStoriesPage(groups: groups))
        } catch {
            guard !Task.isCancelled, refreshGeneration == ticket, session.sessionGeneration == epoch else { return }
            if Self.discardsViewers(after: error) {
                groups = []; resetViewers()
                persistCache(WireStoriesPage(groups: []))
            }
            flags.loadedOnce = true
            flags.lastError = ChatsViewModel.describe(error)
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
    /// Section 9 item 1: the publication identity is journaled BEFORE the
    /// wire; a lost ACK (or a process death between acceptance and the cache
    /// refresh - item 4) replays the SAME clientId and converges on the SAME
    /// server row instead of duplicating.
    @discardableResult
    public func publish(caption: String, background: String?, imagePath: String?) async -> String? {
        guard session.sessionGeneration == ownerEpoch else { return "The original story session ended." }
        let epoch = session.sessionGeneration
        let api = session.api
        let journal = PulseSection8Journal.shared
        var payload: [String: String] = [:]
        let trimmedCaption = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedCaption.isEmpty { payload["caption"] = trimmedCaption }
        if let background { payload["background"] = background }
        if let imagePath { payload["imagePath"] = imagePath }
        let intent = journal.enqueue(owner: api.userId, kind: "story-publish", targetId: "", payload: payload)
        do {
            try Task.checkCancellation()
            _ = try await api.postStory(caption: caption, background: background, imagePath: imagePath, clientId: intent.clientId)
            try Task.checkCancellation()
            guard session.sessionGeneration == epoch else { return "The original story session ended." }
            journal.confirm(owner: api.userId, clientId: intent.clientId)
            // Feed refresh is not part of the publication acknowledgement.
            Task { [weak self] in
                guard let self, self.session.sessionGeneration == epoch else { return }
                await self.refresh(quiet: true)
            }
            return nil
        } catch let failure as PulseAPIClient.Failure {
            if failure.kind == .validation {
                journal.confirm(owner: api.userId, clientId: intent.clientId) // permanent refusal
            }
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
        refreshGeneration = UUID()
        flags.loading = false
        groups = groups.map { g in
            let stories = (g.stories ?? []).map { s in
                s.id == storyId
                    ? WireStoryItemCopy(s, viewedByMe: true, viewCount: viewCount ?? (s.viewCount ?? 0))
                    : s
            }
            let allSeen = g.mine == true ? g.allSeen : stories.allSatisfy { $0.viewedByMe == true }
            return WireStoryGroupCopy(g, allSeen: allSeen, stories: stories)
        }
        persistCache(WireStoriesPage(groups: groups))
    }

    /// Only a confirmed deletion removes the story and advances its viewer.
    /// An older feed read cannot restore the row after that acknowledgement.
    public func deleteStory(id: String) async -> Bool {
        guard !Task.isCancelled, session.sessionGeneration == ownerEpoch else { return false }
        let api = session.api
        // Section 9 item 5: the delete intent is durable; the journal row
        // leaves ONLY after BOTH the server ack AND the local cache purge
        // persisted - a broken/full disk keeps the tombstone for the restore
        // flush (which 404s safely on an already-deleted story).
        let journal = PulseSection8Journal.shared
        let intent = journal.enqueue(owner: api.userId, kind: "story-delete", targetId: id)
        do {
            try await api.deleteStory(id: id)
            guard !Task.isCancelled, session.sessionGeneration == ownerEpoch else { return false }
            refreshGeneration = UUID()
            flags.loading = false
            removeLocal(id)
            if persistCache(WireStoriesPage(groups: groups)) {
                journal.confirm(owner: api.userId, clientId: intent.clientId)
            }
            return true
        } catch let failure as PulseAPIClient.Failure
              where failure.kind == .notFound || failure.kind == .forbidden {
            // Already gone / permanently refused: the deletion is the truth.
            removeLocal(id)
            if persistCache(WireStoriesPage(groups: groups)) {
                journal.confirm(owner: api.userId, clientId: intent.clientId)
            }
            return false
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
            // Section 9 item 2: bounded viewer pages walked to completion; a
            // huge audience never lands in one unbounded response.
            var collected: [WireStoryViewer] = []
            var cursor: String? = nil
            var pages = 0
            while pages < 20 {
                let page = try await api.storyViewersPage(id: storyId, limit: 100, cursor: cursor)
                collected += page.viewers ?? []
                pages += 1
                guard page.hasMore == true, let next = page.nextCursor, !next.isEmpty else { break }
                cursor = next
            }
            guard collected.allSatisfy({ !($0.userId ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }),
                  Set(collected.compactMap(\.userId)).count == collected.count else {
                throw PulseAPIClient.Failure(kind: .server, message: "The gateway did not return a valid viewers list.")
            }
            guard !Task.isCancelled, viewersGeneration == ticket, session.sessionGeneration == ownerEpoch else { return }
            viewers = collected; viewersError = nil
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

    /// False = the purge/write did NOT persist (full or broken disk) - the
    /// caller keeps its durable journal row so the restore flush retries.
    @discardableResult
    private func persistCache(_ page: WireStoriesPage) -> Bool {
        guard let store = session.store,
              let data = try? JSONEncoder().encode(page),
              let json = String(data: data, encoding: .utf8) else { return false }
        do { try store.saveStoryCache(key: cacheKey, groupsJson: json, updatedAt: nowMs()); return true }
        catch { return false }
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
