package app.pulse.android.mirror

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MirrorSheetInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blankPanelTapDoesNotDiscardDraftButScrimDoesDismiss() {
        var dismissals = 0
        compose.setContent {
            MirrorSheet("Draft", onDismiss = { dismissals++ }) {
                Text("Keep this draft")
                Box(Modifier.fillMaxWidth().height(96.dp).testTag("blank-panel"))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("blank-panel").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onRoot().performTouchInput { click(Offset(center.x, 20f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun systemBackDismissesTheSheetRatherThanTheUnderlyingScreen() {
        var dismissals = 0
        compose.setContent { MirrorSheet("Draft", onDismiss = { dismissals++ }) { Text("Keep this draft") } }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
