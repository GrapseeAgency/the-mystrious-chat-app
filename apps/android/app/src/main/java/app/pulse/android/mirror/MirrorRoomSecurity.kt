package app.pulse.android.mirror

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalWindowInfo
import app.pulse.ui.security.SecureWindowProtection

/** Used by the mounted Mirror room, not just the alternate chat feature. */
@Composable
internal fun MirrorRoomSecurity(enabled: Boolean, content: @Composable () -> Unit) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val hidden = enabled && (!focused || !lifecycle.isAtLeast(Lifecycle.State.RESUMED))
    SecureWindowProtection(enabled)
    Box(Modifier.fillMaxSize().drawWithContent {
        if (hidden) drawRect(MirrorArt.Bg) else drawContent()
    }.then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
}
