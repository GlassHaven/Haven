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
    version = 5,
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
    }
}
