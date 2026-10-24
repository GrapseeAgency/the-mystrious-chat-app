package app.pulse.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * R10-a - system share target ("Share into Pulse"). A thin, invisible hop:
 * the system hands us the ACTION_SEND payload, we validate it, re-address it
 * to MainActivity under a clearly-named extra pair, and finish. All real
 * work (conversation picker sheet, send/staging, lock gate) lives in the
 * shell - this activity owns NO screens.
 *
 * Manifest: exported=true (the system must reach it), translucent theme,
 * excludeFromRecents, allowTaskReparenting=false. Filters: text/plain and
 * the image MIME family only - anything else is honestly refused with a toast.
 */
class ShareInActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val type = intent?.type
        var text: String? = null
        var stream: Uri? = null

        if (action == Intent.ACTION_SEND && type != null) {
            when {
                type == "text/plain" ->
                    text = intent.getStringExtra(Intent.EXTRA_TEXT)
                type.startsWith("image/") ->
                    stream = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    }
                // Unsupported MIME - fall through with both nulls → honest toast.
            }
        }

        // Only delegated content URIs may cross this exported entry point.
        stream = stream?.takeIf { it.scheme == "content" && !it.authority.isNullOrBlank() &&
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 }
        val readable = !text.isNullOrBlank() || stream != null
        if (readable) {
            val forward = forwardIntent(this, text, stream)
            startActivity(forward)
        } else {
            Toast.makeText(this, "Nothing here Pulse can share", Toast.LENGTH_SHORT).show()
        }
        // The hop is done either way - never linger (NoDisplay-style).
        finish()
    }

    companion object {
        internal fun forwardIntent(context: android.content.Context, text: String?, stream: Uri?): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_SHARE_IN
                putExtra(EXTRA_SHARE_TEXT, text)
                putExtra(EXTRA_SHARE_STREAM, stream)
                stream?.let {
                    clipData = android.content.ClipData.newRawUri("Shared photo", it)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        /** Custom exported launch actions must carry the same delegated URI in ClipData. */
        internal fun forwardedStream(intent: Intent): Uri? {
            val raw = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_SHARE_STREAM, Uri::class.java)
                else { @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_SHARE_STREAM) as? Uri) }
            return raw?.takeIf { uri -> uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
                intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                intent.clipData?.let { clip -> (0 until clip.itemCount).any { clip.getItemAt(it).uri == uri } } == true }
        }

        /** MainActivity consumes this action + the two payload extras. */
        const val ACTION_SHARE_IN = "app.pulse.android.action.SHARE_IN"
        const val EXTRA_SHARE_TEXT = "pulse.share_in.text"
        const val EXTRA_SHARE_STREAM = "pulse.share_in.stream"
    }
}
