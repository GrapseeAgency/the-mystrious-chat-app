package app.pulse.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareInGrantTest {
    @Test fun imageHandoffCarriesItsReadGrantAndRejectsUnrelatedOrPrivateFileUris() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val uri = Uri.parse("content://photo-provider/shared/image")
        val intent = ShareInActivity.forwardIntent(context, null, uri)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(uri, ShareInActivity.forwardedStream(intent))
        intent.flags = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
        assertNull(ShareInActivity.forwardedStream(intent))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("other", Uri.parse("content://photo-provider/other"))
        assertNull(ShareInActivity.forwardedStream(intent))
        assertNull(ShareInActivity.forwardedStream(ShareInActivity.forwardIntent(context, null, Uri.parse("file:///data/private/photo.jpg"))))
    }
}
