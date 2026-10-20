package app.pulse.android.notify

import android.content.Context
import app.pulse.core.PulseEndpoints
import app.pulse.core.time.PulseTime
import app.pulse.data.local.AccountStorageKey
import app.pulse.data.local.SecureSessionStore
import app.pulse.data.local.SessionVault
import app.pulse.domain.notify.ReminderDeliveryGate
import app.pulse.domain.notify.ReminderEffects
import app.pulse.domain.notify.ReminderOwner
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.protocol.PulseJson
import app.pulse.protocol.ReminderItemDto
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class ReminderDeliveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PulsePrefsStore,
    private val vault: SecureSessionStore,
) : ReminderEffects {
    private val ledger = context.getSharedPreferences("reminder-delivery-v1", Context.MODE_PRIVATE)
    // SharedPreferences mutates its memory cache even when commit() fails.
    // Keep only confirmed writes visible to the delivery policy.
    private val confirmed = ledger.all.mapNotNull { (key, value) ->
        (value as? String)?.let { runCatching { ReminderDeliveryGate.State.valueOf(it) }.getOrNull() }?.let { key to it }
    }.toMap().toMutableMap()
    private val gate = ReminderDeliveryGate(object : ReminderDeliveryGate.Store {
        override fun read(key: String) = confirmed[key]
        override fun write(key: String, state: ReminderDeliveryGate.State) {
            check(ledger.edit().putString(key, state.name)
                .putString("__receiptNonce", java.util.UUID.randomUUID().toString()).commit()) { "Reminder receipt could not be saved" }
            confirmed[key] = state
        }
    })

    fun key(owner: ReminderOwner, id: String) = "${AccountStorageKey.namespace(owner.origin, owner.userId)}:$id"

    private suspend fun isSelected(owner: ReminderOwner): Boolean {
        if (prefs.viewerId.first() != owner.userId) return false
        val configured = prefs.serverBase.first()?.takeIf { it.isNotBlank() }
            ?: vault.load()?.let { PulseJson.decodeFromString(SessionVault.serializer(), it).gatewayOverride }
            ?: PulseEndpoints.gatewayHttpUrl
        return prefs.viewerId.first() == owner.userId &&
            runCatching { AccountStorageKey.origin(configured.orEmpty()) == owner.origin }.getOrDefault(false)
    }

    override suspend fun schedule(owner: ReminderOwner, reminder: ReminderItemDto) = withContext(Dispatchers.IO) {
        if (reminder.firedAt != null) {
            // Never re-arm already-resolved future reminders from a stale cache.
            // Leave an already-posted alert visible; explicit manual Finish removes it.
            gate.settleRemote(key(owner, reminder.id)) { ReminderNotifier.cancelWork(context, key(owner, reminder.id)) }
            return@withContext
        }
        if (!isSelected(owner)) return@withContext
        val at = PulseTime.epochMs(reminder.remindAt)
        if (at <= 0L) return@withContext
        gate.schedule(key(owner, reminder.id)) {
            ReminderNotifier.schedule(context, owner, reminder, at)
        }
        Unit
    }

    suspend fun deliver(owner: ReminderOwner, reminder: ReminderItemDto): ReminderDeliveryGate.Verdict = withContext(Dispatchers.IO) {
        if (reminder.firedAt != null) return@withContext ReminderDeliveryGate.Verdict.BLOCKED
        val at = PulseTime.epochMs(reminder.remindAt)
        if (at <= 0L) return@withContext ReminderDeliveryGate.Verdict.BLOCKED
        if (at > System.currentTimeMillis()) return@withContext ReminderDeliveryGate.Verdict.RETRY
        val selected = isSelected(owner)
        gate.deliver(key(owner, reminder.id), currentOwner = { selected }, post = {
            ReminderNotifier.show(context, key(owner, reminder.id), reminder.id,
                reminder.note.ifBlank { "Reminder" }, reminder.snippet ?: reminder.conversation.name,
                reminder.conversationId.ifBlank { null }, reminder.messageId)
        })
    }

    override suspend fun finish(owner: ReminderOwner, id: String) = withContext(NonCancellable + Dispatchers.IO) {
        gate.finish(key(owner, id)) { ReminderNotifier.cancel(context, key(owner, id), id) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderDeliveryModule {
    @Binds abstract fun reminders(manager: ReminderDeliveryManager): ReminderEffects
}
