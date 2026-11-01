import Foundation

/// Section 9 item 2 - pure story-feed page merge (no transport, no UI).
///
/// The keyset walk paginates STORY ROWS, so one author's stories can split
/// across a page boundary. This folds the walked pages back into the exact
/// wire contract the tray renders: mine first, then authors by freshest
/// story, stories inside each group oldest-first (viewer playback order).
public enum PulseStoryPages {

    public static func merged(groups: [WireStoryGroup]) -> [WireStoryGroup] {
        // Fold stories per author; groups arrive newest-row-first across pages.
        var order: [String] = []
        var byUser: [String: WireStoryGroup] = [:]
        for group in groups {
            guard let user = group.user, let userId = user.id, !userId.isEmpty else { continue }
            if byUser[userId] == nil {
                order.append(userId)
                byUser[userId] = WireStoryGroupCopy(group, stories: [])
            }
            let existing = byUser[userId]
            let stories = (existing?.stories ?? []) + (group.stories ?? [])
            // allSeen folds as AND: a user is fully seen only when every
            // page's slice was seen (each page reports its own slice).
            let allSeen = (existing?.allSeen ?? true) && (group.allSeen ?? true)
            byUser[userId] = WireStoryGroupCopy(group, allSeen: allSeen, stories: stories)
        }
        var mine: WireStoryGroup?
        var others: [(group: WireStoryGroup, newest: Int64)] = []
        for userId in order {
            guard let group = byUser[userId] else { continue }
            let stories = (group.stories ?? []).sorted { lhs, rhs in
                let l = storyEpochMs(lhs.createdAt)
                let r = storyEpochMs(rhs.createdAt)
                return l == r ? (lhs.id ?? "") < (rhs.id ?? "") : l < r
            }
            guard !stories.isEmpty else { continue }
            let folded = WireStoryGroupCopy(group, stories: stories)
            let newest = storyEpochMs(stories.last?.createdAt)
            if folded.mine == true { mine = mine ?? folded } else { others.append((folded, newest)) }
        }
        others.sort { $0.newest > $1.newest }
        var result: [WireStoryGroup] = []
        if let mine { result.append(mine) }
        result.append(contentsOf: others.map(\.group))
        return result
    }
}
