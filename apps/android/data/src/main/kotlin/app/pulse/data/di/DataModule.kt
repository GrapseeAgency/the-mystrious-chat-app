package app.pulse.data.di

import android.content.Context
import androidx.room.Room
import app.pulse.core.PulseEndpoints
import app.pulse.data.local.ConversationDao
import app.pulse.data.local.MessageDao
import app.pulse.data.local.PulseDatabase
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
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun providePrefsStore(impl: app.pulse.data.local.PulsePrefsStoreImpl): app.pulse.domain.repository.PulsePrefsStore = impl

    @Provides
    fun provideConversationDao(db: PulseDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: PulseDatabase): MessageDao = db.messageDao()

    /** Domain stays pure Kotlin (no javax.inject) — the graph provides use cases here. */
    @Provides
    fun provideSendMessageUseCase(repo: PulseRepository): app.pulse.domain.usecase.SendMessageUseCase =
        app.pulse.domain.usecase.SendMessageUseCase(repo)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindPulseRepository(impl: PulseRepositoryImpl): PulseRepository
}
