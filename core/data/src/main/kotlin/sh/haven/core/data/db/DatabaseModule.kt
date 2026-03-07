package sh.haven.core.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HavenDatabase {
        return Room.databaseBuilder(
            context,
            HavenDatabase::class.java,
            "haven.db",
        )
            .addMigrations(HavenDatabase.MIGRATION_1_2, HavenDatabase.MIGRATION_2_3, HavenDatabase.MIGRATION_3_4, HavenDatabase.MIGRATION_4_5)
            .build()
    }

    @Provides
    fun provideConnectionDao(db: HavenDatabase): ConnectionDao = db.connectionDao()

    @Provides
    fun provideKnownHostDao(db: HavenDatabase): KnownHostDao = db.knownHostDao()

    @Provides
    fun provideConnectionLogDao(db: HavenDatabase): ConnectionLogDao = db.connectionLogDao()

    @Provides
    fun provideSshKeyDao(db: HavenDatabase): SshKeyDao = db.sshKeyDao()

    @Provides
    fun providePortForwardRuleDao(db: HavenDatabase): PortForwardRuleDao = db.portForwardRuleDao()
}
