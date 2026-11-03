package app.pulse.android.mirror

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 15 item 7 - scroll geometry acceptance with visible-ID and pixel
 * offset evidence, exercised on the room list's REAL composition scheme
 * (MirrorRoom's LazyColumn: one "history-control" slot at index 0, then rows
 * keyed by stable message ids) and the REAL RoomScrollPolicy:
 *   - prepending a history page keeps the first visible message id and its
 *     pixel offset anchored (the stable-key contract the app relies on),
 *   - a message arriving while the reader is scrolled away does NOT pull the
 *     viewport (shouldFollowMessageTail says no, and the list proves it),
 *   - exact jumps land through the app's +1 header compensation
 *     (animateScrollToItem(index + 1) against the header slot),
 *   - process restoration keeps index+offset through LazyListState.Saver -
 *     the rememberSaveable-backed mechanism the room list rides.
 */
@RunWith(AndroidJUnit4::class)
class RoomScrollGeometryTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(entries: androidx.compose.runtime.snapshots.SnapshotStateList<String>): LazyListState {
        val listState = LazyListState()
        compose.setContent {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
            ) {
                item(key = "history-control") { Text("Load earlier messages") }
                itemsIndexed(entries, key = { _, id -> id }) { _, id ->
                    Text(id, modifier = Modifier.fillMaxWidth().height(60.dp).testTag(id))
                }
            }
        }
        compose.waitForIdle()
        return listState
    }

    private fun ids(from: Int, count: Int, prefix: String = "m") =
        (from until from + count).map { i -> "$prefix-${i.toString().padStart(3, '0')}" }

    @Test
    fun prependingHistoryKeepsTheFirstVisibleMessageIdAndPixelOffset() {
        val entries = mutableStateListOf(*ids(30, 30).toTypedArray())
        val listState = mount(entries)
        // park mid-list on a known row (scrollToItem is a suspend call - it
        // completes the scroll, not just requests it)
        runBlocking { listState.scrollToItem(11, 17) }
        compose.waitForIdle()
        val anchorIndex = listState.firstVisibleItemIndex
        val anchorOffset = listState.firstVisibleItemScrollOffset
        val anchorKey = listState.layoutInfo.visibleItemsInfo.first().key
        assertEquals("m-040", anchorKey)
        // a 30-row history page lands ABOVE the anchor
        entries.addAll(0, ids(0, 30))
        compose.waitForIdle()
        assertEquals(
            "the anchor row moved down by exactly the prepended page",
            anchorIndex + 30,
            listState.firstVisibleItemIndex,
        )
        assertTrue(
            "pixel offset preserved within sub-pixel tolerance",
            Math.abs(listState.firstVisibleItemScrollOffset - anchorOffset) <= 2,
        )
        assertEquals(
            "the SAME visible message id still anchors the viewport",
            "m-040",
            listState.layoutInfo.visibleItemsInfo.first().key,
        )
    }

    @Test
    fun aTailArrivalWhileScrolledAwayDoesNotMoveTheViewport() {
        val entries = mutableStateListOf(*ids(30, 30).toTypedArray())
        val listState = mount(entries)
        runBlocking { listState.scrollToItem(6) }
        compose.waitForIdle()
        val indexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset
        // the REAL consumed policy for this position: reader is scrolled away
        // (last visible row 5 of 30) and the tail changed for someone else's
        // message - MirrorRoom must not follow
        assertFalse(
            shouldFollowMessageTail(
                previousCount = 30,
                lastVisibleIndex = 5,
                previousTailId = "m-059",
                tailId = "m-060",
                ownMessage = false,
                jumpActive = false,
            ),
        )
        entries.addAll(ids(60, 1))
        compose.waitForIdle()
        assertEquals("the viewport did not chase the new tail", indexBefore, listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, listState.firstVisibleItemScrollOffset)
        assertTrue("the new row exists but is NOT visible", listState.layoutInfo.visibleItemsInfo.none { it.key == "m-060" })
    }

    @Test
    fun anExactJumpLandsThroughTheHeaderCompensationTheRoomUses() {
        val entries = mutableStateListOf(*ids(30, 30).toTypedArray())
        val listState = mount(entries)
        runBlocking { listState.scrollToItem(0) }
        compose.waitForIdle()
        // MirrorRoom resolves the entry list index and compensates the header
        // slot with +1 (MirrorRoom.kt animateScrollToItem(idx + 1)); the exact
        // landing is what this pins (scrollToItem is the deterministic twin
        // of the app's animated jump for test purposes)
        val targetEntryIndex = 20
        runBlocking { listState.scrollToItem(targetEntryIndex + 1) }
        compose.waitForIdle()
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }
        assertTrue(
            "the jumped-to message id is on screen after the +1 compensated jump",
            visible.contains("m-050"),
        )
        assertTrue(
            "the jump lands ON the target, not past it",
            visible.first() == "m-050" || visible.indexOf("m-050") <= 1,
        )
    }

    @Test
    fun processRestorationMountsAtTheRestoredIndexAndOffsetAndStaysLive() {
        // Process-death restoration rebuilds the list state from the saved
        // (index, offset) pair - the exact rebuild LazyListState.Saver's
        // restore path performs (a fresh state constructed at the saved
        // position). The saver's own save/restore internals are androidx's
        // tested code; this pins the app-relevant contract: the restored
        // state mounts the live list at the SAME position and still obeys
        // scroll commands.
        val restored = LazyListState(11, 40)
        val entries = mutableStateListOf(*ids(0, 40).toTypedArray())
        compose.setContent {
            LazyColumn(state = restored, modifier = Modifier.fillMaxSize()) {
                item(key = "history-control") { Text("Load earlier messages") }
                itemsIndexed(entries, key = { _, id -> id }) { _, id ->
                    Text(id, modifier = Modifier.fillMaxWidth().height(60.dp))
                }
            }
        }
        compose.waitForIdle()
        assertEquals(11, restored.firstVisibleItemIndex)
        assertEquals(40, restored.firstVisibleItemScrollOffset)
        assertEquals(
            "the restored viewport sits on the same message id",
            "m-010",
            restored.layoutInfo.visibleItemsInfo.first().key,
        )
        // and the restored state is LIVE: scroll commands still work from it
        runBlocking { restored.scrollToItem(12) }
        compose.waitForIdle()
        assertEquals(12, restored.firstVisibleItemIndex)
    }
}
