import XCTest
@testable import Pulse

/// Section 13 item 8 - the durable profile-edit draft: UNSAVED intent (name,
/// bio, handle, staged photo) staged per account owner, restored when the
/// editor opens, cleared by a confirmed save or an explicit discard. A draft
/// never leaks across accounts - every read/write is keyed by the owner.
@MainActor
final class ProfileDraftJournalTests: XCTestCase {
    private var suiteName: String?

    override func setUp() {
        super.setUp()
        suiteName = "profile-draft-" + UUID().uuidString
    }
    override func tearDown() {
        if let suiteName { UserDefaults().removePersistentDomain(forName: suiteName) }
        suiteName = nil
        super.tearDown()
    }

    private func freshJournal() -> (journal: PulseProfileDraftJournal, defaults: UserDefaults) {
        let defaults = UserDefaults(suiteName: suiteName!)!
        defaults.removePersistentDomain(forName: suiteName!)
        return (PulseProfileDraftJournal(defaults: defaults), defaults)
    }

    func testStageDraftAndClearPerField() {
        let (journal, defaults) = freshJournal()
        let owner = "owner-a"
        journal.stage(owner: owner, field: .name, value: "Renamed")
        journal.stage(owner: owner, field: .about, value: "New bio")
        journal.stage(owner: owner, field: .username, value: "new_handle")
        journal.stage(owner: owner, field: .photo, value: "/api/uploads/staged.jpg")
        var draft = journal.draft(owner: owner)
        XCTAssertEqual(draft.name, "Renamed")
        XCTAssertEqual(draft.about, "New bio")
        XCTAssertEqual(draft.username, "new_handle")
        XCTAssertEqual(draft.photo, "/api/uploads/staged.jpg")
        // The draft is durable: a fresh journal over the same suite restores.
        let revived = PulseProfileDraftJournal(defaults: defaults)
        XCTAssertEqual(revived.draft(owner: owner), draft)
        // A confirmed save retires EXACTLY its fields; the rest survive.
        revived.clear(owner: owner, fields: [.name, .about])
        draft = revived.draft(owner: owner)
        XCTAssertNil(draft.name); XCTAssertNil(draft.about)
        XCTAssertEqual(draft.username, "new_handle")
        XCTAssertEqual(draft.photo, "/api/uploads/staged.jpg")
        // An explicit discard retires the whole draft for that owner.
        revived.clear(owner: owner, fields: Set(PulseProfileDraftJournal.Field.allCases))
        XCTAssertEqual(revived.draft(owner: owner), PulseProfileDraftJournal.Draft())
    }

    func testEmptyStageValueClearsTheFieldAndAnEmptyDraftLeavesNoBlob() {
        let (journal, defaults) = freshJournal()
        let owner = "owner-a"
        journal.stage(owner: owner, field: .name, value: "Renamed")
        journal.stage(owner: owner, field: .name, value: "") // edited back to nothing
        XCTAssertNil(journal.draft(owner: owner).name)
        journal.stage(owner: owner, field: .about, value: "Bio")
        journal.clear(owner: owner, fields: [.about])
        XCTAssertNil(defaults.data(forKey: "pulse.profile.draft.v1." + owner),
            "an empty draft removes its storage row instead of leaking an empty blob")
    }

    func testDraftsNeverLeakAcrossAccountsOrGateways() {
        let (journal, _) = freshJournal()
        journal.stage(owner: "owner-a", field: .about, value: "A's unsaved bio")
        journal.stage(owner: "owner-b", field: .name, value: "B's unsaved name")
        XCTAssertEqual(journal.draft(owner: "owner-a").name, nil, "B's draft is invisible to A")
        XCTAssertEqual(journal.draft(owner: "owner-b").about, nil, "A's draft is invisible to B")
        // Clearing one owner's draft never touches the other's.
        journal.clear(owner: "owner-a", fields: Set(PulseProfileDraftJournal.Field.allCases))
        XCTAssertEqual(journal.draft(owner: "owner-b").name, "B's unsaved name")
        // A brand-new owner starts from an empty draft.
        XCTAssertEqual(journal.draft(owner: "owner-c"), PulseProfileDraftJournal.Draft())
    }

    func testStaleDraftBlobDegradesToAnEmptyDraft() {
        let (journal, defaults) = freshJournal()
        let owner = "owner-a"
        defaults.set("unreadable", forKey: "pulse.profile.draft.v1." + owner)
        XCTAssertEqual(journal.draft(owner: owner), PulseProfileDraftJournal.Draft(),
            "a corrupt draft is an empty draft, never a crash or a cross-account read")
        // And staging over it heals the row.
        journal.stage(owner: owner, field: .name, value: "Healed")
        XCTAssertEqual(journal.draft(owner: owner).name, "Healed")
    }
}
