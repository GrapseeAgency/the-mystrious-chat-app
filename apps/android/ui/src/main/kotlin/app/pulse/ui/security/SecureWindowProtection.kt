package app.pulse.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import app.pulse.core.security.SurfaceProtectionLeases

private val leases = SurfaceProtectionLeases()
private fun Context.activityWindow(): android.view.Window? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return (current as? Activity)?.window
}

@Composable
fun SecureWindowProtection(enabled: Boolean) {
    val window = LocalContext.current.activityWindow()
    SecureSurface(window, enabled)
}

/**
 * Section 13 item 11 - FLAG_SECURE for a COMPOSE DIALOG's own window. A
 * `Dialog` / material3 `AlertDialog` owns a SEPARATE window: the flag set on
 * the activity window does not cover it, so content-bearing dialogs (message
 * content, attachments, the media viewer) must hold their own lease. Resolving
 * the window via `LocalView.parent as? DialogWindowProvider` is the same
 * pattern NativeReadMenus uses; the lease rides the dialog window's lifetime
 * and shares the [SurfaceProtectionLeases] registry with the room surface, so
 * refcounting stays correct when both hold protection at once.
 */
@Composable
fun SecureDialogWindowProtection(enabled: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    SecureSurface(window, enabled)
}

/** One lease per (window, enabled) composition - shared by both entry points. */
@Composable
private fun SecureSurface(window: Window?, enabled: Boolean) {
    DisposableEffect(window, enabled) {
        val release = if (enabled && window != null) leases.acquire(window,
            isProtected = { window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 },
            setProtected = { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }) else null
        onDispose { release?.invoke() }
    }
}
