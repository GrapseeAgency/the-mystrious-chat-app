package app.pulse.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pulse.core.time.PulseTime
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * R1-W2H D45 — reboot resilience for Tier-1 reminders (F-RO-06).
 *
 * ReminderNotifier's offline one-shots are WorkManager jobs: they survive
 * process death, but after a REBOOT the persisted work is re-run by the
 * scheduler at an unspecified time — reminders that came due while the device
 * was off would stay silent until the app is next opened. This receiver
 * closes that window using the SAME house infra as the shell due-loop
 * (MainActivity.onStart) — no new scheduling mechanism:
 *
 *   · still-future remindAt → [ReminderNotifier.schedule] re-arms the
 *     WorkManager one-shot (REPLACE policy dedupes any half-armed copy);
 *   · already due and never fired (firedAt == null) → [ReminderNotifier.show]
 *     delivers the notification immediately, then the server PATCH converges
 *     the firedAt flag exactly like the foreground due-loop does.
 *
 * Reminder truth is server-side: the network is tried first, and the last
 * good snapshot cache ([PulseRepository.cachedReminders]) is the offline
 * fallback — boot-time connectivity is never guaranteed.
 *
 * DI: receivers cannot be constructor-injected; the house pattern is
 * @AndroidEntryPoint field injection (same as MainActivity), so Hilt supplies
 * the singleton repository before onReceive runs.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: PulseRepository

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON -> {}
            else -> return
        }
        // goAsync: the repository round-trip may outlive onReceive by seconds;
        // the async grant keeps the process alive until finish().
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                reschedule(appContext)
            } catch (_: Throwable) {
                // A boot receiver must never crash-loop the system re-broadcast;
                // the app-open due-loop remains the second line of defense.
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun reschedule(context: Context) {
        ReminderNotifier.ensureChannel(context)
        val page = repository.reminders(dueOnly = false).getOrNull()
            ?: repository.cachedReminders()
            ?: return
        val now = System.currentTimeMillis()
        for (item in page.items) {
            val remindAtMs = PulseTime.epochMs(item.remindAt)
            if (remindAtMs <= 0L) continue
            if (remindAtMs > now) {
                // Still future — re-arm the offline one-shot (idempotent REPLACE).
                // R2-C item 6 — the conversation rides along for the deep link.
                ReminderNotifier.schedule(
                    context,
                    item.id,
                    item.note.ifBlank { "Reminder" },
                    remindAtMs,
                    item.conversationId.ifBlank { null },
                )
            } else if (item.firedAt == null) {
                // Came due while the device was off — nudge now, converge after.
                ReminderNotifier.show(
                    context,
                    item.id,
                    item.note.ifBlank { "Reminder" },
                    item.snippet ?: item.conversation.name,
                    item.conversationId.ifBlank { null },
                )
                runCatching { repository.resolveReminder(item.id) }
            }
        }
    }

    private companion object {
        /** OEM quick-boot (HTC lineage / some emulators) — same semantics as BOOT_COMPLETED. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
