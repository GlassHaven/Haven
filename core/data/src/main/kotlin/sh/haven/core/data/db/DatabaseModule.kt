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
            .addMigrations(HavenDatabase.MIGRATION_1_2, HavenDatabase.MIGRATION_2_3, HavenDatabase.MIGRATION_3_4, HavenDatabase.MIGRATION_4_5, HavenDatabase.MIGRATION_5_6, HavenDatabase.MIGRATION_6_7, HavenDatabase.MIGRATION_7_8, HavenDatabase.MIGRATION_8_9, HavenDatabase.MIGRATION_9_10, HavenDatabase.MIGRATION_10_11, HavenDatabase.MIGRATION_11_12, HavenDatabase.MIGRATION_12_13, HavenDatabase.MIGRATION_13_14, HavenDatabase.MIGRATION_14_15, HavenDatabase.MIGRATION_15_16, HavenDatabase.MIGRATION_16_17, HavenDatabase.MIGRATION_17_18, HavenDatabase.MIGRATION_18_19, HavenDatabase.MIGRATION_19_20, HavenDatabase.MIGRATION_20_21, HavenDatabase.MIGRATION_21_22, HavenDatabase.MIGRATION_22_23, HavenDatabase.MIGRATION_23_24, HavenDatabase.MIGRATION_24_25, HavenDatabase.MIGRATION_25_26, HavenDatabase.MIGRATION_26_27, HavenDatabase.MIGRATION_27_28, HavenDatabase.MIGRATION_28_29, HavenDatabase.MIGRATION_29_30, HavenDatabase.MIGRATION_30_31, HavenDatabase.MIGRATION_31_32, HavenDatabase.MIGRATION_32_33, HavenDatabase.MIGRATION_33_34, HavenDatabase.MIGRATION_34_35, HavenDatabase.MIGRATION_35_36, HavenDatabase.MIGRATION_36_37, HavenDatabase.MIGRATION_37_38, HavenDatabase.MIGRATION_38_39, HavenDatabase.MIGRATION_39_40, HavenDatabase.MIGRATION_40_41, HavenDatabase.MIGRATION_41_42, HavenDatabase.MIGRATION_42_43, HavenDatabase.MIGRATION_43_44, HavenDatabase.MIGRATION_44_45, HavenDatabase.MIGRATION_45_46, HavenDatabase.MIGRATION_46_47, HavenDatabase.MIGRATION_47_48, HavenDatabase.MIGRATION_48_49, HavenDatabase.MIGRATION_49_50, HavenDatabase.MIGRATION_50_51, HavenDatabase.MIGRATION_51_52, HavenDatabase.MIGRATION_52_53, HavenDatabase.MIGRATION_53_54, HavenDatabase.MIGRATION_54_55, HavenDatabase.MIGRATION_55_56, HavenDatabase.MIGRATION_56_57, HavenDatabase.MIGRATION_57_58, HavenDatabase.MIGRATION_58_59, HavenDatabase.MIGRATION_59_60, HavenDatabase.MIGRATION_60_61, HavenDatabase.MIGRATION_61_62, HavenDatabase.MIGRATION_62_63, HavenDatabase.MIGRATION_63_64, HavenDatabase.MIGRATION_64_65, HavenDatabase.MIGRATION_65_66, HavenDatabase.MIGRATION_66_67, HavenDatabase.MIGRATION_67_68, HavenDatabase.MIGRATION_68_69, HavenDatabase.MIGRATION_69_70, HavenDatabase.MIGRATION_70_71, HavenDatabase.MIGRATION_71_72, HavenDatabase.MIGRATION_72_73, HavenDatabase.MIGRATION_73_74, HavenDatabase.MIGRATION_74_75, HavenDatabase.MIGRATION_75_76, HavenDatabase.MIGRATION_76_77)
            .build()
    }

    @Provides
    fun provideConnectionDao(db: HavenDatabase): ConnectionDao = db.connectionDao()

    @Provides
    fun provideKnownHostDao(db: HavenDatabase): KnownHostDao = db.knownHostDao()

    @Provides
    fun provideKnownTlsCertDao(db: HavenDatabase): KnownTlsCertDao = db.knownTlsCertDao()

    @Provides
    fun provideConnectionLogDao(db: HavenDatabase): ConnectionLogDao = db.connectionLogDao()

    @Provides
    fun provideSshKeyDao(db: HavenDatabase): SshKeyDao = db.sshKeyDao()

    @Provides
    fun providePortForwardRuleDao(db: HavenDatabase): PortForwardRuleDao = db.portForwardRuleDao()

    @Provides
    fun provideConnectionGroupDao(db: HavenDatabase): ConnectionGroupDao = db.connectionGroupDao()

    @Provides
    fun provideAgentAuditEventDao(db: HavenDatabase): AgentAuditEventDao = db.agentAuditEventDao()

    @Provides
    fun provideTunnelConfigDao(db: HavenDatabase): TunnelConfigDao = db.tunnelConfigDao()

    @Provides
    fun providePasteQueueDao(db: HavenDatabase): PasteQueueDao = db.pasteQueueDao()

    @Provides
    fun provideWorkspaceDao(db: HavenDatabase): WorkspaceDao = db.workspaceDao()

    @Provides
    fun provideStepCaConfigDao(db: HavenDatabase): StepCaConfigDao = db.stepCaConfigDao()

    @Provides
    fun provideSyncProfileDao(db: HavenDatabase): SyncProfileDao = db.syncProfileDao()

    @Provides
    fun provideProotInstallLogDao(db: HavenDatabase): ProotInstallLogDao = db.prootInstallLogDao()

    @Provides
    fun provideTotpSecretDao(db: HavenDatabase): TotpSecretDao = db.totpSecretDao()

    @Provides
    fun provideMailRuleDao(db: HavenDatabase): MailRuleDao = db.mailRuleDao()

    @Provides
    fun provideMailWatermarkDao(db: HavenDatabase): MailWatermarkDao = db.mailWatermarkDao()

    @Provides
    fun provideMailRuleFiringDao(db: HavenDatabase): MailRuleFiringDao = db.mailRuleFiringDao()

    @Provides
    fun provideMailRulePendingActionDao(db: HavenDatabase): MailRulePendingActionDao =
        db.mailRulePendingActionDao()

    @Provides
    fun provideStandingPolicyDao(db: HavenDatabase): StandingPolicyDao = db.standingPolicyDao()

    @Provides
    fun provideAgeIdentityDao(db: HavenDatabase): AgeIdentityDao = db.ageIdentityDao()

    @Provides
    fun provideSshIdentityDao(db: HavenDatabase): SshIdentityDao = db.sshIdentityDao()
}
