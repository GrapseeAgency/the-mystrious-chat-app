package app.pulse.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.pulse.android.push.PulseMessageNotifications
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.sync.MessageNotificationIdentity
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class MessageNotificationReconciliationTest {
    @Test fun exactProofCannotCancelAnUnseenGapOrANewerAlertAndOwnerChangesStopRemoval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")).use { it.readBytes() }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "pulse_read_reconciliation_test"
        manager.createNotificationChannel(NotificationChannel(channel, "Read reconciliation test", NotificationManager.IMPORTANCE_LOW))
        val owner = "notification-test-owner"
        fun identity(id: String) = MessageNotificationIdentity(owner, "test-room", id)
        fun post(id: String) = manager.notify(identity(id).tag, 0, NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(id).setOnlyAlertOnce(true).build())
        fun present(id: String) = manager.activeNotifications.any { it.tag == identity(id).tag }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue("Notification manager did not reach expected state", condition())
        }
        val fallback = Proxy.newProxyInstance(PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java)) { _, _, _ -> null } as PulseRepository
        var current = true
        var switchDuringProof = false
        val repository = object : PulseRepository by fallback {
            override val viewerId: String = owner
            override fun attachmentSessionKey(): String = "test-epoch"
            override suspend fun notificationMessageUnread(conversationId: String, messageId: String): Result<Boolean> {
                assertEquals("test-room", conversationId)
                if (messageId == "read") {
                    if (switchDuringProof) current = false else post("newer")
                }
                return Result.success(messageId != "read")
            }
        }
        try {
            post("read"); post("gap")
            await { present("read") && present("gap") }
            PulseMessageNotifications.reconcile(context, repository) { current }
            await { !present("read") && present("newer") }
            assertTrue(present("gap"))
            switchDuringProof = true
            post("read"); await { present("read") }
            PulseMessageNotifications.reconcile(context, repository) { current }
            assertTrue(present("read"))
            assertTrue(present("gap"))
        } finally {
            listOf("read", "gap", "newer").forEach { manager.cancel(identity(it).tag, 0) }
            manager.deleteNotificationChannel(channel)
        }
    }
}
