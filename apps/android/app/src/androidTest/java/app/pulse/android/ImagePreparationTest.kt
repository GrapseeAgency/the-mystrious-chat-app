package app.pulse.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse.feature.chat.MediaSupport
import app.pulse.feature.stories.StoryMedia
import app.pulse.ui.media.ImagePreparation
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePreparationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun decode(value: String): Bitmap {
        assertTrue(value.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.decode(value.substringAfter(','), Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    private fun input(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val file = File.createTempFile("photo-test-", ".image", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        try { file.outputStream().use { assertTrue(bitmap.compress(format, 95, it)) } }
        finally { bitmap.recycle() }
        return file
    }
    @Test fun boundsOnlyNullDoesNotRejectPhotosInEitherFeature() = runBlocking {
        val source = input(3000, 1500)
        try {
            for (result in listOf(MediaSupport.imageToDataUrl(context, Uri.fromFile(source)), StoryMedia.imageToDataUrl(context, Uri.fromFile(source)))) {
                val bitmap = decode(result.getOrThrow())
                try {
                    assertEquals(1280, bitmap.width)
                    assertEquals(640, bitmap.height)
                    assertTrue(Color.red(bitmap.getPixel(10, 10)) > 245) // explicit white, not black alpha
                } finally { bitmap.recycle() }
            }
        } finally { source.delete() }
    }
    @Test fun allEightExifOrientationsAreBakedIntoPixels() = runBlocking {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected = listOf(listOf(0,1,2,3), listOf(1,0,3,2), listOf(3,2,1,0), listOf(2,3,0,1),
            listOf(0,2,1,3), listOf(2,0,3,1), listOf(3,1,2,0), listOf(1,3,0,2))
        for (orientation in 1..8) {
            val file = File.createTempFile("photo-exif-", ".jpg", context.cacheDir)
            val original = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            Canvas(original).apply {
                colors.forEachIndexed { index, color ->
                    val x = (index % 2) * 60f; val y = (index / 2) * 40f
                    drawRect(x, y, x + 60, y + 40, Paint().apply { this.color = color })
                }
            }
            try {
                file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                original.recycle()
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val bitmap = decode(ImagePreparation.imageToDataUrl(context, Uri.fromFile(file)).getOrThrow())
                try {
                    assertEquals(if (orientation >= 5) 80 else 120, bitmap.width)
                    assertEquals(if (orientation >= 5) 120 else 80, bitmap.height)
                    expected[orientation - 1].forEachIndexed { index, colorIndex ->
                        val pixel = bitmap.getPixel(bitmap.width * (if (index % 2 == 0) 1 else 3) / 4,
                            bitmap.height * (if (index / 2 == 0) 1 else 3) / 4)
                        val expectedColor = colors[colorIndex]
                        assertTrue("orientation $orientation corner $index", kotlin.math.abs(Color.red(pixel) - Color.red(expectedColor)) < 35 &&
                            kotlin.math.abs(Color.green(pixel) - Color.green(expectedColor)) < 35 && kotlin.math.abs(Color.blue(pixel) - Color.blue(expectedColor)) < 35)
                    }
                } finally { bitmap.recycle() }
            } finally { if (!original.isRecycled) original.recycle(); file.delete() }
        }
    }
    @Test fun thinAndInvalidImagesDoNotCrashOrLeakTemporarySources() = runBlocking {
        val before = context.cacheDir.list()?.filter { it.startsWith("pulse-photo-") }?.toSet()
        val source = input(1, 5000)
        try {
            val bitmap = decode(ImagePreparation.imageToDataUrl(context, Uri.fromFile(source)).getOrThrow())
            assertEquals(1, bitmap.width); assertEquals(1280, bitmap.height); bitmap.recycle()
            source.writeText("not an image")
            assertTrue(ImagePreparation.imageToDataUrl(context, Uri.fromFile(source)).isFailure)
            assertEquals(before, context.cacheDir.list()?.filter { it.startsWith("pulse-photo-") }?.toSet())
        } finally { source.delete() }
    }
}
