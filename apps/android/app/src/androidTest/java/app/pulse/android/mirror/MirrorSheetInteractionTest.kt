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
        // The scrim dismissal completes only after the 170ms exit tween inside the
        // sheet's LaunchedEffect. A single idle pass can observe the UI "idle" in
        // the window before the exit Animatable registers its first frame callback
        // (runner load widens that window - CI strikes: Oct 26 api-30 run 40247380437
        // and Oct 30 api-34 run 41075181324, both expected:<1> but was:<0>), so the
        // dismissal is awaited bounded. A genuinely broken scrim still fails by
        // timeout instead of passing on a lost race.
        compose.waitUntil(5000) { dismissals >= 1 }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun systemBackDismissesTheSheetRatherThanTheUnderlyingScreen() {
        var dismissals = 0
        compose.setContent { MirrorSheet("Draft", onDismiss = { dismissals++ }) { Text("Keep this draft") } }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // Same race as the scrim case: BackHandler only sets leaving=true and the
        // onDismiss lands after the exit tween finishes, so a bounded wait replaces
        // the single idle pass. A genuinely broken back path (sheet ignoring system
        // back, or the underlying screen going down instead) still times out and
        // fails with dismissals observed at 0.
        compose.waitUntil(5000) { dismissals >= 1 }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
