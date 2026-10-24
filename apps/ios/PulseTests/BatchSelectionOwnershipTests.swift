import XCTest
@testable import Pulse

@MainActor
final class BatchSelectionOwnershipTests: XCTestCase {
    func testOnlyConfirmedRowsAreRemovedAndNewSelectionIsKept() {
        let model = ChatsViewModel()
        model.enterSelect("confirmed")
        model.toggleSelect("failed")
        let epoch = model.selectionEpoch
        model.toggleSelect("new")
        model.acknowledgeBatchSelection(["confirmed"], epoch: epoch)
        XCTAssertEqual(model.selection, ["failed", "new"])
        XCTAssertTrue(model.selectMode)
        model.acknowledgeBatchSelection(["failed"], epoch: epoch)
        XCTAssertEqual(model.selection, ["new"])
        model.acknowledgeBatchSelection(["new"], epoch: epoch)
        XCTAssertTrue(model.selection.isEmpty); XCTAssertFalse(model.selectMode)
    }
    func testLateBatchCannotClearAReopenedSelectionOfTheSameRoom() {
        let model = ChatsViewModel()
        model.enterSelect("room")
        let old = model.selectionEpoch
        model.exitSelect()
        model.enterSelect("room")
        model.acknowledgeBatchSelection(["room"], epoch: old)
        XCTAssertEqual(model.selection, ["room"])
        XCTAssertTrue(model.selectMode)
    }
}
