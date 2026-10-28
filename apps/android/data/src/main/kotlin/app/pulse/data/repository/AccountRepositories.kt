package app.pulse.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.AccountStorageKey
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.PulsePrefsLocalStore
import app.pulse.data.local.SessionTokenStore
import app.pulse.data.remote.PulseApi
import app.pulse.data.remote.PulseSocketClient
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** One database and preferences store per verified account/gateway namespace. */
@Singleton
class AccountRepositories @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpClient,
    private val tokens: SessionTokenStore,
    private val reminders: app.pulse.domain.notify.ReminderEffects = app.pulse.domain.notify.ReminderEffects.None,
) {
    // Keep handles alive for captured continuations; never close a DB underneath
    // an in-flight transaction. Room/Datastore handles are reused on return.
    private val databases = mutableMapOf<String, PulseDatabase>()
    private val preferences = mutableMapOf<String, PulsePrefsLocalStore>()
    private data class Binding(val key: String?, val token: String?, val alive: AtomicBoolean, val repo: PulseRepository)
    private var binding = create(null, "")
    private val router = AccountRepositoryRouter(binding.repo, ::start, ::stop)
    val repository: PulseRepository get() = router.repository

    private fun database(key: String?): PulseDatabase {
        if (key == null) return Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).build()
        return databases.getOrPut(key) {
            // Fresh account files use the current schema. Legacy pulse.db is
            // left untouched because it has no trustworthy account ownership.
            Room.databaseBuilder(context, PulseDatabase::class.java, "pulse-account-$key.db")
                .addMigrations(PulseDatabase.MIGRATION_3_4, PulseDatabase.MIGRATION_4_5,
                    PulseDatabase.MIGRATION_5_6, PulseDatabase.MIGRATION_6_7,
                    PulseDatabase.MIGRATION_7_8, PulseDatabase.MIGRATION_8_9,
                    PulseDatabase.MIGRATION_9_10, PulseDatabase.MIGRATION_10_11, PulseDatabase.MIGRATION_11_12, PulseDatabase.MIGRATION_12_13, PulseDatabase.MIGRATION_13_14, PulseDatabase.MIGRATION_14_15, PulseDatabase.MIGRATION_15_16, PulseDatabase.MIGRATION_16_17, PulseDatabase.MIGRATION_17_18, PulseDatabase.MIGRATION_18_19)
                .build()
        }
    }

    private fun create(owner: String?, origin: String): Binding {
        val key = owner?.let { AccountStorageKey.namespace(origin, it) }
        val db = database(key)
        val prefs = preferences.getOrPut(key ?: "guest") {
            PulsePrefsLocalStore(PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile("pulse-account-${key ?: "guest"}")
            })
        }
        val alive = AtomicBoolean(true)
        val token = if (owner == null) null else tokens.cached
        val credential: () -> String? = {
            if (!alive.get()) throw CancellationException("Account session ended")
            if (owner == null) null else {
                if (tokens.cached != token) throw CancellationException("Account credential changed")
                check(!token.isNullOrBlank()) { "Sign in before synchronizing this account" }
                token
            }
        }
        val invalid: (String?) -> Unit = { reason ->
            if (owner != null && alive.get() && tokens.cached == token) tokens.markInvalidAsync(reason)
        }
        val api = PulseApi(http, bearerToken = credential, onAuthInvalid = invalid, gateway = {
            if (!alive.get()) throw CancellationException("Account session ended")
            // Only onboarding follows endpoint edits. An account never moves its
            // credential to a different origin by reading a mutable global URL.
            if (owner == null) PulseEndpoints.gatewayHttpUrl else origin
        })
        val socket = PulseSocketClient(
            if (owner == null || token.isNullOrBlank()) "" else PulseEndpoints.socketUrl,
            tokenProvider = { token }, onAuthInvalid = invalid, allowEndpointFallback = false,
        )
        val repo = PulseRepositoryImpl(api, db, db.conversationDao(), db.messageDao(), db.outboxDao(),
            db.draftDao(), db.topicDao(), db.savedDao(), db.callLogDao(), db.storyDao(), db.wave7Dao(),
            socket, prefs, tokens, context, reminders,
            owner?.let { app.pulse.domain.notify.ReminderOwner(it, origin) })
        return Binding(key, token, alive, repo)
    }

    private fun start(owner: String): PulseRepository {
        val origin = AccountStorageKey.origin(PulseEndpoints.gatewayHttpUrl)
        val key = AccountStorageKey.namespace(origin, owner)
        if (binding.key == key && binding.token == tokens.cached && binding.alive.get()) {
            binding.repo.start(owner)
            return binding.repo
        }
        retire()
        binding = create(owner, origin)
        binding.repo.start(owner)
        return binding.repo
    }

    private fun retire() {
        binding.alive.set(false)
        binding.repo.stop()
    }

    private fun stop(): PulseRepository {
        if (binding.key == null) return binding.repo
        retire()
        binding = create(null, "")
        return binding.repo
    }
}
