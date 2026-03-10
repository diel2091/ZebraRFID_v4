package com.zebra.rfidscanner.di

import android.content.Context
import androidx.room.Room
import com.zebra.rfidscanner.data.RfidDatabase
import com.zebra.rfidscanner.data.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RfidDatabase {
        return RfidDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideTagDao(database: RfidDatabase): TagDao {
        return database.tagDao()
    }
}
