package app.pulse.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.domain.model.Message
import app.pulse.domain.sync.VisibleReadIntent
import app.pulse.feature.chat.ThreadReadReceipts
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose viewport/lifecycle; only window focus and transport are controlled. */
@RunWith(AndroidJUnit4::class)
class ReadAttentionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun floatingEditMenuStopsReadsWithoutLosingWindowFocus() = nativeEditMenuInterruption(1000)
    @Test fun editMenuOpenedAndClosedInOneFrameStillRestartsDwell() = nativeEditMenuInterruption(0)
    @Test fun windowFocusLostAndRestoredInOneFrameStillRestartsDwell() = nativeEditMenuInterruption(0, focusCycle = true)

    @Test fun threadDialogEditMenuUsesItsOwnWindow() = nativeEditMenuInterruption(1000, inDialog = true)

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun nativeEditMenuInterruption(holdFor: Long, inDialog: Boolean = false, focusCycle: Boolean = false) {
        val view = java.util.concurrent.atomic.AtomicReference<android.view.View>()
        val window = java.util.concurrent.atomic.AtomicReference<WindowInfo>()
        val observed = CopyOnWriteArrayList<String>()
        val root = Message("menu-root", "room", "peer", "Peer", Message.Kind.TEXT, "Root", "2026-10-26T00:00:00Z")
        var mode: android.view.ActionMode? = null
        var focusWindow: android.view.Window? = null
        var enabled by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val reader: @androidx.compose.runtime.Composable () -> Unit = {
                val host = androidx.compose.ui.platform.LocalView.current
                val actualWindow = LocalWindowInfo.current
                androidx.compose.runtime.SideEffect { view.set(host); window.set(actualWindow) }
                val list = rememberLazyListState()
                LazyColumn(state = list, modifier = Modifier.height(120.dp).fillMaxWidth()) {
                    item(key = "thread-parent") { Text("Root", Modifier.height(64.dp)) }
                }
                ThreadReadReceipts(ownerKey = "native-edit-menu", enabled = enabled, connected = true,
                    root = root, replies = emptyList(), listState = list,
                    onRead = { id -> VisibleReadIntent({ Result.success("original.member") }, {
                        observed.add(id); Result.success(Unit)
                    }) })
            }
            if (inDialog) {
                androidx.compose.material3.ModalBottomSheet(onDismissRequest = {},
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    reader()
                }
            } else reader()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000) // settle a dialog before starting the observed dwell
        compose.waitUntil(5000) { window.get()?.isWindowFocused == true }
        compose.runOnIdle { enabled = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        try {
            compose.runOnIdle {
                if (focusCycle) {
                    var context: android.content.Context = view.get().context
                    while (context is android.content.ContextWrapper && context !is android.app.Activity) {
                        context = context.baseContext
                    }
                    focusWindow = (context as android.app.Activity).window
                    focusWindow!!.callback.onWindowFocusChanged(false)
                    focusWindow!!.callback.onWindowFocusChanged(true)
                    return@runOnIdle
                }
                mode = view.get().startActionMode(object : android.view.ActionMode.Callback2() {
                    override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                        menu.add("Copy"); return true
                    }
                    override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
                    override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
                    override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
                    override fun onGetContentRect(mode: android.view.ActionMode, view: android.view.View, rect: android.graphics.Rect) {
                        rect.set(0, 64, 240, 128)
                    }
                }, android.view.ActionMode.TYPE_FLOATING)
                assertNotNull("Use an actual platform floating edit menu", mode)
                if (holdFor == 0L) mode?.finish()
            }
            if (holdFor > 0) {
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(holdFor)
                compose.runOnIdle {
                    assertTrue("Floating menu should not rely on focus loss", window.get().isWindowFocused)
                    assertTrue("Visible edit menu must prevent Seen", observed.isEmpty())
                    mode?.finish()
                }
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle { assertTrue("Dismissal requires a full new dwell", observed.isEmpty()) }
            compose.mainClock.advanceTimeBy(300)
            compose.waitUntil(5000) { observed.contains(root.id) }
        } finally { compose.runOnIdle {
            mode?.finish()
            focusWindow?.callback?.onWindowFocusChanged(true)
        } }
    }

    @Test fun systemWindowOcclusionAndSheetInterruptionsRestartDwellWithoutReadingOffscreenRows() {
        var focused by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        val window = object : WindowInfo {
            override val isWindowFocused: Boolean
                get() = focused
        }
        val root = Message("root", "room", "peer", "Peer", Message.Kind.TEXT, "Root", "2026-10-26T00:00:00Z")
        val reply = root.copy(id = "reply", threadRootId = "root")
        val hidden = reply.copy(id = "hidden")
        val observed = CopyOnWriteArrayList<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                val list = rememberLazyListState()
                LazyColumn(state = list, modifier = Modifier.height(180.dp).fillMaxWidth()) {
                    item(key = "thread-parent") { Text("Root", Modifier.height(64.dp)) }
                    item(key = "reply") { Text("Reply", Modifier.height(64.dp)) }
                    item(key = "spacer") { Box(Modifier.height(600.dp)) }
                    item(key = "hidden") { Text("Not visible", Modifier.height(64.dp)) }
                }
                ThreadReadReceipts(ownerKey = "test", enabled = enabled, connected = true,
                    root = root, replies = listOf(reply, hidden), listState = list,
                    onRead = { id -> VisibleReadIntent({ Result.success("original.member") }, {
                        observed.add(id); Result.success(Unit)
                    }) })
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertTrue(observed.isEmpty()); focused = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertTrue(observed.isEmpty()); enabled = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(5000) { observed.contains("reply") }
        compose.runOnIdle {
            assertEquals(setOf("root", "reply"), observed.toSet())
            assertFalse(observed.contains("hidden"))
        }
    }
    @Test fun arrivingViewportRowDoesNotRestartAnAlreadyVisibleRowsDwell() {
        var showReply by mutableStateOf(false)
        val window = object : WindowInfo {
            override val isWindowFocused = true
        }
        val root = Message("stable-root", "room", "peer", "Peer", Message.Kind.TEXT, "Root", "2026-10-26T00:00:00Z")
        val reply = root.copy(id = "arriving-reply", threadRootId = root.id)
        val observed = CopyOnWriteArrayList<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                val list = rememberLazyListState()
                LazyColumn(state = list, modifier = Modifier.height(180.dp).fillMaxWidth()) {
                    item(key = "thread-parent") { Text("Root", Modifier.height(64.dp)) }
                    if (showReply) item(key = reply.id) { Text("Reply", Modifier.height(64.dp)) }
                }
                ThreadReadReceipts(ownerKey = "changing-viewport", enabled = true, connected = true,
                    root = root, replies = if (showReply) listOf(reply) else emptyList(), listState = list,
                    onRead = { id -> VisibleReadIntent({ Result.success("original.member") }, {
                        observed.add(id); Result.success(Unit)
                    }) })
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { showReply = true }
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle {
            assertTrue("Stable row should complete its own dwell", observed.contains(root.id))
            assertFalse("Arriving row still needs its own full dwell", observed.contains(reply.id))
        }
        compose.mainClock.advanceTimeBy(350)
        compose.waitUntil(5000) { observed.contains(reply.id) }
    }

    @Test fun realNotificationShadeInterruptsTheDwellUntilTheActualWindowRegainsFocus() {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val window = java.util.concurrent.atomic.AtomicReference<WindowInfo>()
        val observed = CopyOnWriteArrayList<String>()
        val root = Message("shade-root", "room", "peer", "Peer", Message.Kind.TEXT, "Root", "2026-10-26T00:00:00Z")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val actualWindow = LocalWindowInfo.current
            androidx.compose.runtime.SideEffect { window.set(actualWindow) }
            val list = rememberLazyListState()
            LazyColumn(state = list, modifier = Modifier.height(120.dp).fillMaxWidth()) {
                item(key = "thread-parent") { Text("Root", Modifier.height(64.dp)) }
            }
            ThreadReadReceipts(ownerKey = "real-window", enabled = true, connected = true,
                root = root, replies = emptyList(), listState = list,
                onRead = { id -> VisibleReadIntent({ Result.success("original.member") }, {
                    observed.add(id); Result.success(Unit)
                }) })
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { window.get()?.isWindowFocused == true }
        compose.mainClock.advanceTimeBy(200)
        try {
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            compose.waitUntil(5000) { window.get()?.isWindowFocused == false }
            compose.mainClock.advanceTimeBy(1000)
            assertTrue("Notification shade must not acknowledge the covered row", observed.isEmpty())
        } finally {
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
        compose.waitUntil(5000) { window.get()?.isWindowFocused == true }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(5000) { observed.contains("shade-root") }
    }

}
