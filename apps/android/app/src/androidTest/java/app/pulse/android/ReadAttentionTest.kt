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
}
