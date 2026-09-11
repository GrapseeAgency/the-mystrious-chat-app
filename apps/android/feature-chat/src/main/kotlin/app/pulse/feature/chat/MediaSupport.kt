package app.pulse.feature.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pulse.core.PulseEndpoints
import app.pulse.core.media.PulseMedia
import app.pulse.domain.model.Message
import app.pulse.ui.PulsePalette
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wave 1 media pipeline — the Android half of [PulseMedia]: bitmap downscale
 * to the wire's ≤1280px JPEG q0.82 policy, data-URL encoding, document reads
 * with the ≤10MB client gate, FileProvider open/share plumbing and the media
 * BUBBLE renderers (Coil image + document card + fullscreen lightbox).
 */
object MediaSupport {

    /**
     * Image → downscaled JPEG data-URL. Two-pass BitmapFactory decode with
     * inSampleSize (memory-safe), then a bounds-level ≤1280px scale, then
     * JPEG q0.82 — exactly the spec §1.1 upload policy.
     */
    suspend fun imageToDataUrl(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: throw IllegalStateException("Could not read the selected image")
            check(bounds.outWidth > 0 && bounds.outHeight > 0, lazyMessage = { "Not a readable image" })

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= PulseMedia.MAX_IMAGE_DIMENSION_PX ||
                bounds.outHeight / (sample * 2) >= PulseMedia.MAX_IMAGE_DIMENSION_PX
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: throw IllegalStateException("Could not decode the selected image")

            val bitmap = if (decoded.width > PulseMedia.MAX_IMAGE_DIMENSION_PX || decoded.height > PulseMedia.MAX_IMAGE_DIMENSION_PX) {
                val scale = minOf(
                    PulseMedia.MAX_IMAGE_DIMENSION_PX.toFloat() / decoded.width,
                    PulseMedia.MAX_IMAGE_DIMENSION_PX.toFloat() / decoded.height,
                )
                val scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== decoded) decoded.recycle()
                scaled
            } else {
                decoded
            }

            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, PulseMedia.IMAGE_JPEG_QUALITY, out)
                out.toByteArray()
            }
            bitmap.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    /**
     * Recorded voice note → data-URL. Voice is already an encoded AAC/MPEG_4
     * stream (MediaRecorder output) — no bitmap pass, just the same base64
     * chunking shape as [imageToDataUrl] so the wire regex
     * `audio/(webm|mpeg|ogg|wav|mp4|aac)` accepts it.
     */
    suspend fun audioToDataUrl(context: Context, file: File, mime: String = "audio/mp4"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.takeIf { it.exists() && it.length() > 0 }?.readBytes()
                    ?: throw IllegalStateException("Recording file is gone")
                "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }

    data class Document(
        val dataUrl: String,
        val fileName: String,
        val mime: String,
        val sizeBytes: Long,
    )

    /** Document → data-URL with the ≤10MB client gate (spec §1.1). */
    suspend fun documentToDataUrl(context: Context, uri: Uri): Result<Document> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Could not read the selected document")
            if (!PulseMedia.documentFits(bytes.size.toLong())) {
                throw IllegalStateException("Documents are limited to 10 MB")
            }
            val name = queryDisplayName(context, uri)
            val mime = resolver.getType(uri) ?: PulseMedia.mimeForFileName(name)
            val size = bytes.size.toLong()
            Document(
                dataUrl = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                fileName = name,
                mime = mime,
                sizeBytes = size,
            )
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val cursor = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()
        if (cursor != null) {
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment ?: "document"
    }

    /** Media URL for message paths — {gateway}/api/uploads/{path} (spec §1.1). */
    fun mediaUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() }?.let { PulseEndpoints.http("/api/uploads/" + it.removePrefix("/")) }

    /**
     * Absolute-or-relative URL resolver for link-preview thumbnails — the
     * unfurler stores whatever the OG tags carried (relative paths resolve
     * against the gateway origin, absolute URLs pass through untouched).
     */
    fun anyMediaUrl(url: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else PulseEndpoints.http(it)
        }

    /** Mime for a downloaded file — extension map with the stored name. */
    fun mimeForFile(fileName: String?): String = PulseMedia.mimeForFileName(fileName)

    /** FileProvider VIEW intent — opens a downloaded document/photo outside the app. */
    fun buildOpenIntent(context: Context, absolutePath: String, mime: String): Intent {
        val uri = fileUri(context, absolutePath)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(intent, "Open with")
    }

    /** FileProvider SEND intent — the long-press "Share" alternative. */
    fun buildShareIntent(context: Context, absolutePath: String, mime: String, text: String?): Intent {
        val uri = fileUri(context, absolutePath)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Share")
    }

    private fun fileUri(context: Context, absolutePath: String): Uri {
        val file = java.io.File(absolutePath)
        val authority = context.packageName + ".files"
        return androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    }
}

/** Coil image bubble — fixed max size, crossfade, tap → lightbox. */
@Composable
internal fun ImageBubble(
    message: Message,
    mine: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = MediaSupport.mediaUrl(message.imagePath)
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = if (mine) PulsePalette.EmeraldDeep.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(5.dp)) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = message.body.ifBlank { "Photo" },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .aspectRatio(4f / 3f)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onOpen),
            )
            if (message.body.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    message.body,
                    color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Dashed rounded border (draw-behind) — the view-once tombstone and the topic
 * "+" chip share the hand-drawn dashed stroke (Compose border() can't dash).
 */
internal fun Modifier.pulseDashedBorder(color: Color, width: Dp = 1.5.dp, cornerRadius: Dp = 12.dp): Modifier =
    drawBehind {
        val stroke = Stroke(width = width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke.width / 2, stroke.width / 2),
            size = Size(size.width - stroke.width, size.height - stroke.width),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style = stroke,
        )
    }

/**
 * Wave 2 view-once GATE (spec §1 row 6, unopened leg): blurred Coil image +
 * darkened overlay + EyeOff + "Tap to view once". Tap consumes the photo
 * (repo.markMessageViewed fire-and-forget) and opens the lightbox instantly —
 * reveal is NOT gated on the POST (web parity).
 */
@Composable
internal fun ViewOnceGateBubble(
    imagePath: String?,
    mine: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (mine) PulsePalette.EmeraldDeep.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        Box(Modifier.padding(5.dp)) {
            Box(
                Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(min = 200.dp)
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            // Anti-leak gate (spec row 6): NO image is fetched/rendered at all
            // before consumption — a dark scrim + glyph instead of the web
            // blur (Modifier.blur no-ops below API 31; zero-leak beats parity).
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulseEyeOff(tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap to view once",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.18f))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "it disappears after opening",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * Eye-off glyph drawn natively — material-icons-extended is deliberately off
 * the classpath (same reason as PulseCheckCheck in MessageSheets.kt).
 */
@Composable
internal fun PulseEyeOff(
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Canvas(modifier.semantics { if (contentDescription != null) this.contentDescription = contentDescription }) {
        val stroke = Stroke(width = size.width * 0.085f, cap = StrokeCap.Round)
        val cy = size.height / 2f
        val eye = Path().apply {
            moveTo(0f, cy)
            cubicTo(
                size.width * 0.25f, cy - size.height * 0.46f,
                size.width * 0.75f, cy - size.height * 0.46f,
                size.width, cy,
            )
            cubicTo(
                size.width * 0.75f, cy + size.height * 0.46f,
                size.width * 0.25f, cy + size.height * 0.46f,
                0f, cy,
            )
            close()
        }
        drawPath(eye, tint, style = stroke)
        drawCircle(tint, radius = size.width * 0.13f, center = Offset(size.width / 2f, cy), style = stroke)
        drawLine(
            tint,
            start = Offset(size.width * 0.12f, size.height * 0.88f),
            end = Offset(size.width * 0.88f, size.height * 0.12f),
            strokeWidth = size.width * 0.085f,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Wave 2 view-once BURN tombstone (spec §1 row 6 anti-replay hardening):
 * once viewedAt != null the row NEVER renders the image — no blurred original,
 * no lightbox entry, no preview fetch. Dashed placeholder only.
 */
@Composable
internal fun BurnedPhotoBubble(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(width = 168.dp, height = 190.dp)
                .pulseDashedBorder(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PulseEyeOff(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    "Photo opened",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "gone forever",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** Document bubble — icon, name, human size, tap → download + open. */
@Composable
internal fun FileBubble(
    message: Message,
    mine: Boolean,
    downloading: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (mine) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        modifier = modifier.clickable(enabled = message.filePath != null, onClick = onOpen),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (mine) {
                            SolidColor(Color.White.copy(alpha = 0.18f))
                        } else {
                            Brush.linearGradient(listOf(PulsePalette.Emerald.copy(alpha = 0.16f), PulsePalette.Teal.copy(alpha = 0.16f)))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.InsertDriveFile, contentDescription = "Document", tint = contentColor, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f, fill = false).widthIn(min = 120.dp)) {
                Text(
                    message.fileName ?: message.body.ifBlank { "Document" },
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val size = PulseMedia.humanFileSize(message.fileSize)
                    if (size.isNotEmpty()) {
                        Text(
                            size,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = if (downloading) "Downloading" else "Download",
                        tint = if (downloading) PulsePalette.Amber else contentColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp),
                    )
                    if (downloading) {
                        Text(
                            "Saving…",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Fullscreen lightbox — black stage, tap-dismiss, caption below (spec D.4). */
@Composable
internal fun ImageLightbox(
    message: Message,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .clickable(onClick = onDismiss)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = MediaSupport.mediaUrl(message.imagePath),
                contentDescription = message.body.ifBlank { "Photo" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss),
            )
            if (message.body.isNotBlank()) {
                Text(
                    message.body,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
