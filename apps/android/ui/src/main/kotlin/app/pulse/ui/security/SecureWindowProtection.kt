package app.pulse.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
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
