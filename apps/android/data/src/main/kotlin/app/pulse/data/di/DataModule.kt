package app.pulse.data.di

import android.content.Context
import androidx.room.Room
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.CallLogDao
import app.pulse.data.local.ConversationDao
import app.pulse.data.local.DraftDao
import app.pulse.data.local.MessageDao
import app.pulse.data.local.OutboxDao
import app.pulse.data.local.PulseDatabase
import app.pulse.data.local.SavedDao
import app.pulse.data.local.SessionTokenCipher
import app.pulse.data.local.SessionTokenPersistence
import app.pulse.data.local.SessionTokenStore
import app.pulse.data.local.StoryDao
import app.pulse.data.local.Wave7Dao
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
        // Fail fast instead of hanging for minutes on an unreachable gateway -
        // the onboarding @handle probe must never outlive a blink.
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 6_000
            socketTimeoutMillis = 6_000
        }
    }

    @Provides
    @Singleton
    fun provideApi(http: HttpClient, tokenStore: SessionTokenStore): PulseApi = PulseApi(
        http,
        bearerToken = { tokenStore.cached },
        onAuthInvalid = { reason -> tokenStore.markInvalidAsync(reason) },
    )

    @Provides
    @Singleton
    fun provideSocket(tokenStore: SessionTokenStore): PulseSocketClient = PulseSocketClient(
        PulseEndpoints.socketUrl,
        tokenProvider = { tokenStore.cached },
        onAuthInvalid = { reason -> tokenStore.markInvalidAsync(reason) },
    )
    @Provides
    @Singleton
    fun provideRepository(accounts: app.pulse.data.repository.AccountRepositories): PulseRepository = accounts.repository

    @Provides
    @Singleton
    fun providePrefsStore(impl: app.pulse.data.local.PulsePrefsStoreImpl): app.pulse.domain.repository.PulsePrefsStore = impl

    /** Domain stays pure Kotlin (no javax.inject) - the graph provides use cases here. */
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
    /** Wave 8 - session-token plumbing: DataStore persistence + Keystore cipher. */
    @Binds
    abstract fun bindSessionTokenPersistence(
        impl: app.pulse.data.local.DataStoreSessionTokenPersistence,
    ): SessionTokenPersistence

    @Binds
    abstract fun bindSessionTokenCipher(
        impl: app.pulse.data.local.KeystoreSessionTokenCipher,
    ): SessionTokenCipher
}
