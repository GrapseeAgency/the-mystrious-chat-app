package app.pulse.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * R10-a — system share target ("Share into Pulse"). A thin, invisible hop:
 * the system hands us the ACTION_SEND payload, we validate it, re-address it
 * to MainActivity under a clearly-named extra pair, and finish. All real
 * work (conversation picker sheet, send/staging, lock gate) lives in the
 * shell — this activity owns NO screens.
 *
 * Manifest: exported=true (the system must reach it), translucent theme,
 * excludeFromRecents, allowTaskReparenting=false. Filters: text/plain and
 * the image MIME family only — anything else is honestly refused with a toast.
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
                // Unsupported MIME — fall through with both nulls → honest toast.
            }
        }

        val readable = !text.isNullOrBlank() || stream != null
        if (readable) {
            val forward = Intent(this, MainActivity::class.java).apply {
                setAction(ACTION_SHARE_IN)
                putExtra(EXTRA_SHARE_TEXT, text)
                putExtra(EXTRA_SHARE_STREAM, stream)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
            startActivity(forward)
        } else {
            Toast.makeText(this, "Nothing here Pulse can share", Toast.LENGTH_SHORT).show()
        }
        // The hop is done either way — never linger (NoDisplay-style).
        finish()
    }

    companion object {
        /** MainActivity consumes this action + the two payload extras. */
        const val ACTION_SHARE_IN = "app.pulse.android.action.SHARE_IN"
        const val EXTRA_SHARE_TEXT = "pulse.share_in.text"
        const val EXTRA_SHARE_STREAM = "pulse.share_in.stream"
    }
}
