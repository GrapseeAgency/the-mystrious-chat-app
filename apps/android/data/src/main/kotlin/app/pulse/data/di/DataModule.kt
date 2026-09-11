package app.pulse.data.di

import android.content.Context
import androidx.room.Room
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.ConversationDao
import app.pulse.data.local.DraftDao
import app.pulse.data.local.MessageDao
import app.pulse.data.local.OutboxDao
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.SavedDao
import app.pulse.data.local.TopicDao
import app.pulse.data.remote.PulseApi
import app.pulse.data.remote.PulseSocketClient
import app.pulse.data.repository.PulseRepositoryImpl
import app.pulse.domain.repository.PulseRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json as ktorJson

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient {
        install(ContentNegotiation) {
            ktorJson(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        // Fail fast instead of hanging for minutes on an unreachable gateway —
        // the onboarding @handle probe must never outlive a blink.
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 6_000
            socketTimeoutMillis = 6_000
        }
    }

    @Provides
    @Singleton
    fun provideApi(http: HttpClient): PulseApi = PulseApi(http)

    @Provides
    @Singleton
    fun provideSocket(): PulseSocketClient = PulseSocketClient(PulseEndpoints.socketUrl)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PulseDatabase =
        Room.databaseBuilder(context, PulseDatabase::class.java, PulseDatabase.NAME)
            // Destructive migration is GONE — v3→v4 (outbox + draft),
            // v4→v5 (message media + membersJson) and v5→v6 (Wave 2 depth
            // columns + topics + savedMessages) are additive ALTER/CREATEs;
            // every deployed row must survive.
            .addMigrations(
                PulseDatabase.MIGRATION_3_4,
                PulseDatabase.MIGRATION_4_5,
                PulseDatabase.MIGRATION_5_6,
            )
            .build()

    @Provides
    @Singleton
    fun providePrefsStore(impl: app.pulse.data.local.PulsePrefsStoreImpl): app.pulse.domain.repository.PulsePrefsStore = impl

    @Provides
    fun provideConversationDao(db: PulseDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: PulseDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideOutboxDao(db: PulseDatabase): OutboxDao = db.outboxDao()

    @Provides
    fun provideDraftDao(db: PulseDatabase): DraftDao = db.draftDao()

    @Provides
    fun provideTopicDao(db: PulseDatabase): TopicDao = db.topicDao()

    @Provides
    fun provideSavedDao(db: PulseDatabase): SavedDao = db.savedDao()

    /** Domain stays pure Kotlin (no javax.inject) — the graph provides use cases here. */
    @Provides
    fun provideSendMessageUseCase(repo: PulseRepository): app.pulse.domain.usecase.SendMessageUseCase =
        app.pulse.domain.usecase.SendMessageUseCase(repo)

    @Provides
    fun provideFlushOutboxUseCase(repo: PulseRepository): app.pulse.domain.usecase.FlushOutboxUseCase =
        app.pulse.domain.usecase.FlushOutboxUseCase(repo)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindPulseRepository(impl: PulseRepositoryImpl): PulseRepository
}
