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
            .addMigrations(
                HavenDatabase.MIGRATION_1_2,
                HavenDatabase.MIGRATION_2_3,
                HavenDatabase.MIGRATION_3_4,
                HavenDatabase.MIGRATION_4_5,
                HavenDatabase.MIGRATION_5_6,
                HavenDatabase.MIGRATION_6_7,
                HavenDatabase.MIGRATION_7_8,
                HavenDatabase.MIGRATION_8_9,
                HavenDatabase.MIGRATION_9_10,
                HavenDatabase.MIGRATION_10_11,
                HavenDatabase.MIGRATION_11_12,
                HavenDatabase.MIGRATION_12_13,
                HavenDatabase.MIGRATION_13_14,
                HavenDatabase.MIGRATION_14_15,
                HavenDatabase.MIGRATION_15_16,
                HavenDatabase.MIGRATION_16_17,
            )
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
