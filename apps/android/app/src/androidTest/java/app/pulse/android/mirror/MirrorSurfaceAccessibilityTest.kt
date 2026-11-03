package app.pulse.android.mirror

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 15 items 8 + 9 - accessibility and layout/input acceptance on the
 * repaired surfaces, asserted where CI can actually see it:
 *   - the sheet's icon-only close button exposes its TalkBack label AND its
 *     click action (label without action is as useless as action without
 *     label to a screen reader),
 *   - decorative icons carry NO label (TalkBack skips them instead of
 *     announcing "unlabeled"),
 *   - the whole surface renders and still dismisses under RTL layout and
 *     under large font scaling (2.0x) without crashing or losing the
 *     dismissal contract.
 * VoiceOver/TalkBack full-pass reading order and IME interaction on real
 * devices remain the documented device gates; these are the compile-time and
 * emulator-verifiable slices.
 */
@RunWith(AndroidJUnit4::class)
class MirrorSurfaceAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sheetCloseIsALabeledClickableForTalkBack() {
        var dismissals = 0
        compose.setContent {
            MirrorSheet("Draft", onDismiss = { dismissals++ }) { Text("Keep this draft") }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close").assertExists()
        compose.onNodeWithContentDescription("Close").assertHasClickAction()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(5000) { dismissals >= 1 }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun decorativeIconsCarryNoScreenReaderLabel() {
        compose.setContent {
            MirrorLucideIcon("LX", tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
        }
        compose.waitForIdle()
        // null contentDescription means the glyph is invisible to TalkBack -
        // exactly what a decorative icon must be
        compose.onNodeWithContentDescription("LX", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theSheetRendersAndDismissesUnderRtlLayout() {
        var dismissals = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MirrorSheet("Draft", onDismiss = { dismissals++ }) { Text("Keep this draft") }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Draft").assertExists()
        compose.onNodeWithText("Keep this draft").assertExists()
        compose.onNodeWithContentDescription("Close").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(5000) { dismissals >= 1 }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun theSheetRendersAndDismissesAtDoubleFontScale() {
        var dismissals = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = androidx.compose.ui.platform.LocalDensity.current.density, fontScale = 2f),
            ) {
                MirrorSheet("Draft", onDismiss = { dismissals++ }) { Text("Keep this draft") }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Draft").assertExists()
        compose.onNodeWithContentDescription("Close").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(5000) { dismissals >= 1 }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
