package sh.haven.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.SshKey

@Database(
    entities = [
        ConnectionProfile::class,
        KnownHost::class,
        ConnectionLog::class,
        SshKey::class,
        PortForwardRule::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class HavenDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun connectionLogDao(): ConnectionLogDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun portForwardRuleDao(): PortForwardRuleDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ssh_keys` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `keyType` TEXT NOT NULL,
                        `privateKeyBytes` BLOB NOT NULL,
                        `publicKeyOpenSsh` TEXT NOT NULL,
                        `fingerprintSha256` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN connectionType TEXT NOT NULL DEFAULT 'SSH'")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN destinationHash TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN reticulumHost TEXT NOT NULL DEFAULT '127.0.0.1'")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN reticulumPort INTEGER NOT NULL DEFAULT 37428")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `port_forward_rules` (
                        `id` TEXT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `bindAddress` TEXT NOT NULL,
                        `bindPort` INTEGER NOT NULL,
                        `targetHost` TEXT NOT NULL,
                        `targetPort` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`profileId`) REFERENCES `connection_profiles`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_port_forward_rules_profileId` ON `port_forward_rules` (`profileId`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN jumpProfileId TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN sshOptions TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN vncPort INTEGER")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN vncPassword TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN vncSshForward INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN sessionManager TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN useMosh INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN useEternalTerminal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN etPort INTEGER NOT NULL DEFAULT 2022")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpPort INTEGER NOT NULL DEFAULT 3389")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpUsername TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpDomain TEXT")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpSshForward INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpSshProfileId TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpPassword TEXT")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbPort INTEGER NOT NULL DEFAULT 445")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbShare TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbDomain TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbPassword TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbSshForward INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbSshProfileId TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN sshPassword TEXT")
            }
        }
    }
}
