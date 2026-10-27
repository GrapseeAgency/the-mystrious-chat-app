package app.pulse.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import app.pulse.core.media.PulseMedia
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Shared gallery/camera/story preparation. Never decode the full source just to shrink it. */
object ImagePreparation {
    const val MAX_SOURCE_BYTES: Long = 64L * 1024 * 1024

    suspend fun imageToDataUrl(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val owned = mutableListOf<Bitmap>()
        fun own(bitmap: Bitmap): Bitmap = bitmap.also { owned.add(it) }
        var source: File? = null
        try {
            coroutineContext.ensureActive()
            // Read a provider only once. Bounds, EXIF and pixels must describe the same bytes,
            // even for a cloud/third-party provider whose subsequent streams could differ.
            val file = File.createTempFile("pulse-photo-", ".source", context.cacheDir)
            source = file
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not read the selected image")
            input.use { stream ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw IOException("Photo provider returned no data")
                        total += count
                        require(total <= MAX_SOURCE_BYTES) { "Choose a source photo smaller than 64 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // Bounds-only decoding MUST return null; inspect the populated dimensions instead.
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a readable image" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PulseMedia.MAX_IMAGE_DIMENSION_PX) sample *= 2
            coroutineContext.ensureActive()
            var bitmap = own(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("Could not decode the selected image"))
            val edge = maxOf(bitmap.width, bitmap.height)
            if (edge > PulseMedia.MAX_IMAGE_DIMENSION_PX) {
                val scale = PulseMedia.MAX_IMAGE_DIMENSION_PX.toDouble() / edge
                val scaled = own(Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true))
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            val orientation = try {
                ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) {
                val oriented = own(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true))
                if (oriented !== bitmap) bitmap.recycle()
                bitmap = oriented
            }
            // JPEG has no alpha. Flatten explicitly instead of turning transparent pixels black.
            val opaque = own(Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888))
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            bitmap.recycle()
            coroutineContext.ensureActive()
            val bytes = ByteArrayOutputStream().use { output ->
                check(opaque.compress(Bitmap.CompressFormat.JPEG, PulseMedia.IMAGE_JPEG_QUALITY, output)) { "Could not encode the image" }
                output.toByteArray()
            }
            coroutineContext.ensureActive()
            Result.success("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            owned.forEach { if (!it.isRecycled) it.recycle() }
            source?.delete()
        }
    }
}
