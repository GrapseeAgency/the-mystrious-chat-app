package app.pulse.ui.text

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Section-5 "Copies outside message storage": message text handed to the
 * system clipboard is a copy the app no longer controls. On Android 13+ the
 * clip is marked sensitive so previews and cross-app overlays hide it; older
 * releases keep the plain copy (the platform offers no equivalent flag).
 */
object SensitiveClipboard {
    fun copy(context: Context, label: String, text: CharSequence) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val description = ClipDescription(label, arrayOf("text/plain")).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
        }
        manager.setPrimaryClip(ClipData(description, ClipData.Item(text)))
    }
}
