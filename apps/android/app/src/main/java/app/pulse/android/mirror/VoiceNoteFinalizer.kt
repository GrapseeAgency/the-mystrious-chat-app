package app.pulse.android.mirror

import java.io.File

/** File consumers run only after stop + release; failed captures are never uploaded. */
internal fun finalizeVoiceNote(file: File, stop: () -> Unit, release: () -> Unit): Result<Unit> =
    runCatching {
        try { stop() } finally { release() }
        check(file.isFile && file.length() > 0L) { "Recording could not be finalized" }
    }.onFailure { runCatching { file.delete() } }
