package app.pulse.data.di

import android.content.Context
import androidx.room.Room
import app.pulse.data.local.ConversationDao
import app.pulse.data.local.MessageDao
import app.pulse.data.local.PulseDatabase
import app.pulse.data.remote.PulseApi
import app.pulse.data.repository.PulseRepositoryImpl
import app.pulse.domain.repository.PulseRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PulseDatabase =
        Room.databaseBuilder(context, PulseDatabase::class.java, PulseDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(db: PulseDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: PulseDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideApi(): PulseApi = PulseApi(
        baseUrl = "https://pulse.example", // overridden per-build in N2 (gateway base URL)
        authTokenProvider = { null },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindPulseRepository(impl: PulseRepositoryImpl): PulseRepository
}
