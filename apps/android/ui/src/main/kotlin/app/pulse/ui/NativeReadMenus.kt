package app.pulse.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ActionMode
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Floating text-selection action modes need not take window focus. Observe the
 * actual host window, including a thread dialog, without replacing text controls
 * or their native edit actions. All access is on the UI thread. */
@Composable
fun rememberNativeReadMenus(): State<NativeReadMenuState> {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.activityWindow()
    return remember(window) {
        window?.let { NativeReadMenuWindows.observe(it).state } ?: mutableStateOf(NativeReadMenuState())
    }
}

data class NativeReadMenuState(val obscured: Boolean = false, val revision: Long = 0)

private fun Context.activityWindow(): Window? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return (current as? Activity)?.window
}

private object NativeReadMenuWindows {
    // Weak values too: the callback's delegate can itself own the window. A
    // strong value in a WeakHashMap would otherwise retain dismissed dialogs.
    private val observers = WeakHashMap<Window, WeakReference<NativeReadMenuCallback>>()

    fun observe(window: Window): NativeReadMenuCallback {
        observers[window]?.get()?.let { return it }
        return NativeReadMenuCallback(window.callback).also {
            window.callback = it
            observers[window] = WeakReference(it)
        }
    }
}

/** One callback for the WINDOW lifetime, not each composition. A menu can span
 * a room transition; unmounting the old reader must not forget it. */
internal class NativeReadMenuCallback(private val delegate: Window.Callback) : Window.Callback by delegate {
    private val modes = Collections.newSetFromMap(IdentityHashMap<ActionMode, Boolean>())
    private val snapshot = mutableStateOf(NativeReadMenuState())
    val state: State<NativeReadMenuState> get() = snapshot

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        // Preserve the interruption even when focus is lost and restored before
        // Compose observes a frame. WindowInfo still owns current focus truth.
        snapshot.value = snapshot.value.copy(revision = snapshot.value.revision + 1)
        delegate.onWindowFocusChanged(hasFocus)
    }

    override fun onActionModeStarted(mode: ActionMode) {
        modes.add(mode)
        snapshot.value = NativeReadMenuState(true, snapshot.value.revision + 1)
        delegate.onActionModeStarted(mode)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        try {
            delegate.onActionModeFinished(mode)
        } finally {
            modes.remove(mode)
            snapshot.value = NativeReadMenuState(modes.isNotEmpty(), snapshot.value.revision + 1)
        }
    }
}
