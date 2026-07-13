package sh.haven.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import sh.haven.core.data.db.entities.AgeIdentityEntity
import sh.haven.core.data.db.entities.SshIdentity
import sh.haven.core.data.db.entities.AgentAuditEvent
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.db.entities.KnownTlsCert
import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.db.entities.MailRuleFiring
import sh.haven.core.data.db.entities.MailRulePendingAction
import sh.haven.core.data.db.entities.MailWatermark
import sh.haven.core.data.db.entities.PasteQueueEntry
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.ProotInstallLog
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.db.entities.StandingPolicy
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.data.db.entities.SyncProfile
import sh.haven.core.data.db.entities.TotpSecret
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile

@Database(
    entities = [
        ConnectionProfile::class,
        ConnectionGroup::class,
        KnownHost::class,
        KnownTlsCert::class,
        ConnectionLog::class,
        SshKey::class,
        PortForwardRule::class,
        AgentAuditEvent::class,
        TunnelConfig::class,
        PasteQueueEntry::class,
        WorkspaceProfile::class,
        WorkspaceItem::class,
        StepCaConfig::class,
        SyncProfile::class,
        ProotInstallLog::class,
        TotpSecret::class,
        MailRule::class,
        MailWatermark::class,
        MailRuleFiring::class,
        MailRulePendingAction::class,
        StandingPolicy::class,
        AgeIdentityEntity::class,
        SshIdentity::class,
    ],
    version = 77,
    exportSchema = true,
)
abstract class HavenDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun connectionGroupDao(): ConnectionGroupDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun knownTlsCertDao(): KnownTlsCertDao
    abstract fun connectionLogDao(): ConnectionLogDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun portForwardRuleDao(): PortForwardRuleDao
    abstract fun agentAuditEventDao(): AgentAuditEventDao
    abstract fun tunnelConfigDao(): TunnelConfigDao
    abstract fun pasteQueueDao(): PasteQueueDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun stepCaConfigDao(): StepCaConfigDao
    abstract fun syncProfileDao(): SyncProfileDao
    abstract fun prootInstallLogDao(): ProotInstallLogDao
    abstract fun totpSecretDao(): TotpSecretDao
    abstract fun mailRuleDao(): MailRuleDao
    abstract fun mailWatermarkDao(): MailWatermarkDao
    abstract fun mailRuleFiringDao(): MailRuleFiringDao
    abstract fun mailRulePendingActionDao(): MailRulePendingActionDao
    abstract fun standingPolicyDao(): StandingPolicyDao
    abstract fun ageIdentityDao(): AgeIdentityDao
    abstract fun sshIdentityDao(): SshIdentityDao

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

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_logs ADD COLUMN details TEXT")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_logs ADD COLUMN verboseLog TEXT")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN proxyType TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN proxyHost TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN proxyPort INTEGER NOT NULL DEFAULT 1080")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `connection_groups` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `colorTag` INTEGER NOT NULL DEFAULT 0,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        `collapsed` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN groupId TEXT")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN lastSessionName TEXT")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN disableAltScreen INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ssh_keys ADD COLUMN isEncrypted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rcloneRemoteName TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rcloneProvider TEXT")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN useAndroidShell INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN moshServerCommand TEXT")
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN forwardAgent INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN reticulumNetworkName TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN reticulumPassphrase TEXT")
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN postLoginCommand TEXT")
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN postLoginBeforeSessionManager INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN vncUsername TEXT")
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_audit_events` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `timestamp` INTEGER NOT NULL,
                        `clientHint` TEXT,
                        `method` TEXT NOT NULL,
                        `toolName` TEXT,
                        `argsJson` TEXT,
                        `resultSummary` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `errorMessage` TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_audit_events_timestamp` ON `agent_audit_events` (`timestamp`)")
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN fileTransport TEXT NOT NULL DEFAULT 'AUTO'")
            }
        }

        /**
         * Per-app WireGuard (#102): add the `tunnel_configs` table for
         * named tunnel configurations the user manages in settings, and
         * an optional `tunnelConfigId` reference on each connection
         * profile. Existing profiles get NULL and behave as before.
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tunnel_configs` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `configText` BLOB NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN tunnelConfigId TEXT")
            }
        }

        /**
         * Persistent paste queue so a long-running copy/paste survives app
         * backgrounding, process death, reboots, and transient connection
         * drops. Each row is a leaf file; [status] flips from PENDING to
         * DONE as each transfer completes, and [bytesTransferred] lets
         * the resume path pick up mid-file via ChannelSftp.RESUME.
         */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `paste_queue_entries` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `indexInBatch` INTEGER NOT NULL,
                        `sourceBackendType` TEXT NOT NULL,
                        `sourceProfileId` TEXT NOT NULL,
                        `sourceRemoteName` TEXT,
                        `sourcePath` TEXT NOT NULL,
                        `sourceName` TEXT NOT NULL,
                        `sourceSize` INTEGER NOT NULL,
                        `sourceIsDirectory` INTEGER NOT NULL DEFAULT 0,
                        `destBackendType` TEXT NOT NULL,
                        `destProfileId` TEXT NOT NULL,
                        `destRemote` TEXT,
                        `destPath` TEXT NOT NULL,
                        `isCut` INTEGER NOT NULL DEFAULT 0,
                        `conflictAction` TEXT NOT NULL DEFAULT 'OVERWRITE',
                        `bytesTransferred` INTEGER NOT NULL DEFAULT 0,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_paste_queue_entries_status` " +
                        "ON `paste_queue_entries` (`status`)",
                )
            }
        }

        /**
         * Bring VNC saved-on-profile fields in line with RDP/SMB: a VNC
         * connection can now opt into SSH tunneling via a paired SSH
         * profile, same as RDP's rdpSshProfileId. Default null = no tunnel.
         */
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN vncSshProfileId TEXT DEFAULT NULL"
                )
            }
        }

        /**
         * Per-profile VNC pixel format (24-bit / 16-bit / 8-bit). Defaults
         * to existing behaviour. Added so users on slow mobile paths can
         * downshift to 256 colours and have a usable session, mirroring
         * RealVNC's behaviour (Nesos-ita on #107).
         */
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN vncColorDepth TEXT NOT NULL DEFAULT 'BPP_24_TRUE'"
                )
            }
        }

        /**
         * Per-profile NLA / CredSSP toggle for RDP, default on. Allows
         * users to fall back to SSL-only security on servers where
         * ironrdp's CredSSP implementation doesn't interoperate
         * (#109 — Windows Server 2025 Datacenter).
         */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN rdpUseNla INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * Per-profile RDP colour depth, default 16bpp. 16 was the
         * only value verified to work against every server type
         * pre-EGFX; 24 fails on Windows (server resets connection)
         * and 32 was thought to fail on xrdp (custom RLE). #109.
         *
         * The "default 16" assumption is invalidated by v5.24.69's
         * EGFX patch — see MIGRATION_40_41.
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN rdpColorDepth INTEGER NOT NULL DEFAULT 16"
                )
            }
        }

        /**
         * EGFX (v5.24.69) sets the `SUPPORT_DYN_VC_GFX_PROTOCOL`
         * early-capability flag, which makes Windows servers
         * stricter about the legacy `color_depth` field in the GCC
         * core data: `Bpp8` (= 16-bit) now causes the server to
         * TCP-FIN after MCS Connect, mid-handshake, with no useful
         * error. Bumping to 32 fixes Windows but is risky for xrdp
         * users (whose pre-EGFX matrix had 32 marked as broken).
         *
         * NLA-on strongly correlates with Windows-class servers, so
         * this migration only auto-bumps profiles with
         * `rdpUseNla = 1`. Profiles with NLA off (typically xrdp
         * targets) keep 16. Users who hit the new failure mode and
         * have NLA off can flip the depth manually in the dialog.
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE connection_profiles SET rdpColorDepth = 32 WHERE rdpColorDepth = 16 AND rdpUseNla = 1"
                )
            }
        }

        /**
         * Per-key biometric protection (#129 stage 5). Adds the
         * `biometricProtected` flag on each SSH key; when true, the
         * unified Keystore.fetch path requires a successful
         * BiometricPrompt before returning the key material. Default
         * 0 — every existing key behaves as before until the user
         * flips the toggle in Settings → Security audit.
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ssh_keys ADD COLUMN biometricProtected INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * SSH certificate auth (#133 phase 1). Adds an optional
         * `certificateBytes` BLOB column on each SSH key, holding the
         * contents of an `id_xxx-cert.pub` file signed by a CA the
         * remote server trusts. SshClient wraps private + cert via
         * OpenSshCertificateAwareIdentityFile when this is non-null.
         * Default null — existing keys behave as plain key auth.
         */
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ssh_keys ADD COLUMN certificateBytes BLOB DEFAULT NULL"
                )
            }
        }

        /**
         * Per-connection IPv4-only toggle (#137). On networks where
         * AAAA records resolve but the IPv6 path doesn't route, the
         * user can flip this to skip IPv6 entirely. Default 0 — every
         * existing profile keeps the dual-stack behaviour.
         */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN forceIpv4 INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Generalise the `forceIpv4` Boolean to a tri-state
         * `addressFamily` selector — `AUTO`, `IPV4_ONLY`, `IPV6_ONLY`
         * (#137 follow-up). Networks with broken IPv4 paths exist too,
         * so a Boolean undersells the failure mode (pannal).
         *
         * SQLite ≥ 3.35 supports `DROP COLUMN`, but Haven's minSdk 26
         * ships SQLite 3.18, which doesn't — so do the table-recreate
         * dance: copy through a `_new` table, dropping `forceIpv4` and
         * carrying its value into the new `addressFamily` column.
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE `connection_profiles_new` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `username` TEXT NOT NULL,
                        `sshPassword` TEXT,
                        `authType` TEXT NOT NULL,
                        `keyId` TEXT,
                        `colorTag` INTEGER NOT NULL,
                        `lastConnected` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        `connectionType` TEXT NOT NULL,
                        `destinationHash` TEXT,
                        `reticulumHost` TEXT NOT NULL,
                        `reticulumPort` INTEGER NOT NULL,
                        `jumpProfileId` TEXT,
                        `sshOptions` TEXT,
                        `vncPort` INTEGER,
                        `vncUsername` TEXT,
                        `vncPassword` TEXT,
                        `vncSshForward` INTEGER NOT NULL,
                        `vncSshProfileId` TEXT,
                        `vncColorDepth` TEXT NOT NULL,
                        `sessionManager` TEXT,
                        `useMosh` INTEGER NOT NULL,
                        `useEternalTerminal` INTEGER NOT NULL,
                        `etPort` INTEGER NOT NULL,
                        `rdpPort` INTEGER NOT NULL,
                        `rdpUsername` TEXT,
                        `rdpDomain` TEXT,
                        `rdpPassword` TEXT,
                        `rdpSshForward` INTEGER NOT NULL,
                        `rdpSshProfileId` TEXT,
                        `rdpUseNla` INTEGER NOT NULL,
                        `rdpColorDepth` INTEGER NOT NULL,
                        `smbPort` INTEGER NOT NULL,
                        `smbShare` TEXT,
                        `smbDomain` TEXT,
                        `smbPassword` TEXT,
                        `smbSshForward` INTEGER NOT NULL,
                        `smbSshProfileId` TEXT,
                        `proxyType` TEXT,
                        `proxyHost` TEXT,
                        `proxyPort` INTEGER NOT NULL,
                        `groupId` TEXT,
                        `lastSessionName` TEXT,
                        `disableAltScreen` INTEGER NOT NULL,
                        `rcloneRemoteName` TEXT,
                        `rcloneProvider` TEXT,
                        `useAndroidShell` INTEGER NOT NULL,
                        `moshServerCommand` TEXT,
                        `forwardAgent` INTEGER NOT NULL,
                        `addressFamily` TEXT NOT NULL DEFAULT 'AUTO',
                        `reticulumNetworkName` TEXT,
                        `reticulumPassphrase` TEXT,
                        `postLoginCommand` TEXT,
                        `postLoginBeforeSessionManager` INTEGER NOT NULL,
                        `fileTransport` TEXT NOT NULL,
                        `tunnelConfigId` TEXT,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO connection_profiles_new
                    SELECT
                        id, label, host, port, username, sshPassword, authType,
                        keyId, colorTag, lastConnected, sortOrder, connectionType,
                        destinationHash, reticulumHost, reticulumPort, jumpProfileId,
                        sshOptions, vncPort, vncUsername, vncPassword, vncSshForward,
                        vncSshProfileId, vncColorDepth, sessionManager, useMosh,
                        useEternalTerminal, etPort, rdpPort, rdpUsername, rdpDomain,
                        rdpPassword, rdpSshForward, rdpSshProfileId, rdpUseNla,
                        rdpColorDepth, smbPort, smbShare, smbDomain, smbPassword,
                        smbSshForward, smbSshProfileId, proxyType, proxyHost,
                        proxyPort, groupId, lastSessionName, disableAltScreen,
                        rcloneRemoteName, rcloneProvider, useAndroidShell,
                        moshServerCommand, forwardAgent,
                        CASE WHEN forceIpv4 = 1 THEN 'IPV4_ONLY' ELSE 'AUTO' END,
                        reticulumNetworkName, reticulumPassphrase, postLoginCommand,
                        postLoginBeforeSessionManager, fileTransport, tunnelConfigId
                    FROM connection_profiles
                """.trimIndent())
                db.execSQL("DROP TABLE connection_profiles")
                db.execSQL("ALTER TABLE connection_profiles_new RENAME TO connection_profiles")
            }
        }

        /**
         * Workspace profiles: a named, ordered composition of launchable
         * items (terminal sessions, file-browser tabs, remote desktops,
         * Wayland) the user can fire in one tap. `workspace_item.kind`
         * is a TEXT enum; `connectionProfileId` is `SET NULL` on profile
         * delete so the workspace stays intact and the launcher reports
         * the broken item rather than silently dropping it.
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_profile` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_item` (
                        `id` TEXT NOT NULL,
                        `workspaceId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `connectionProfileId` TEXT,
                        `path` TEXT,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`workspaceId`) REFERENCES `workspace_profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`connectionProfileId`) REFERENCES `connection_profiles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workspace_item_workspaceId` " +
                        "ON `workspace_item` (`workspaceId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workspace_item_connectionProfileId` " +
                        "ON `workspace_item` (`connectionProfileId`)",
                )
            }
        }

        /**
         * #144: per-profile terminal colour-scheme override. Null = inherit
         * the global pref. Stored as the enum's `name` (e.g. "DRACULA").
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN terminalColorScheme TEXT")
            }
        }

        /**
         * #133 phase 2: register step-ca certificate authorities the user
         * has configured. Each row stores the CA URL, OIDC endpoints,
         * provisioner name, default principals, and the pinned root cert
         * PEM. Plus two audit fields on `ssh_keys` — `caConfigId` (which
         * CA minted this cert) and `certIssuedAt` (when) — both nullable
         * so existing keys keep working. The auth path is unchanged; JSch
         * still reads `certificateBytes` via OpenSshCertificateAwareIdentityFile.
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `step_ca_configs` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `caUrl` TEXT NOT NULL,
                        `oidcIssuer` TEXT NOT NULL,
                        `oidcAuthUrl` TEXT NOT NULL,
                        `oidcTokenUrl` TEXT NOT NULL,
                        `oidcClientId` TEXT NOT NULL,
                        `provisioner` TEXT NOT NULL,
                        `defaultPrincipals` TEXT NOT NULL,
                        `rootCertPem` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE ssh_keys ADD COLUMN caConfigId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE ssh_keys ADD COLUMN certIssuedAt INTEGER DEFAULT NULL")
            }
        }

        /**
         * #133 phase 2b: optional SSH host CA public key on each step-ca
         * config row. When set, [HostKeyVerifier] short-circuits TOFU
         * for hosts presenting a CA-signed host cert. Default null —
         * existing CAs keep their TOFU-only behaviour.
         */
        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE step_ca_configs ADD COLUMN sshHostCaPublicKey TEXT DEFAULT NULL",
                )
            }
        }

        /**
         * #150 fix: the four new ConnectionProfile columns
         * (autoReconnect, reconnectMaxAttempts, reconnectOnNetworkChange,
         * tunnelOnly) were added to the entity in v5.31.0 without
         * bumping the database version, so v5.30.0 → v5.31.0 upgrades
         * crash with "Room cannot verify the data integrity" — the
         * stored schema-49 hash (`724bf4a8…` from v5.30.0) no longer
         * matches what the v5.31.0 entity-graph computes. Bumping to
         * version 50 here lets Room run a real migration; the ALTERs
         * are wrapped because devices that installed v5.31.0 fresh
         * already have those columns from a from-scratch CREATE TABLE.
         */
        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db, "connection_profiles", "autoReconnect",
                    "INTEGER NOT NULL DEFAULT 1",
                )
                addColumnIfMissing(
                    db, "connection_profiles", "reconnectMaxAttempts",
                    "INTEGER NOT NULL DEFAULT 5",
                )
                addColumnIfMissing(
                    db, "connection_profiles", "reconnectOnNetworkChange",
                    "INTEGER NOT NULL DEFAULT 1",
                )
                addColumnIfMissing(
                    db, "connection_profiles", "tunnelOnly",
                    "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Per-profile port-knock sequence (#TBD). Two new columns on
         * `connection_profiles`: an optional whitespace/comma-separated
         * list of `port[/proto]` tokens, plus the inter-knock delay in
         * ms (default 100). Empty/null sequence = knocking disabled,
         * matching the existing behaviour for all profiles created before
         * this migration.
         */
        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db, "connection_profiles", "portKnockSequence",
                    "TEXT DEFAULT NULL",
                )
                addColumnIfMissing(
                    db, "connection_profiles", "portKnockDelayMs",
                    "INTEGER NOT NULL DEFAULT 100",
                )
            }
        }

        /**
         * Cloudflare Tunnel as an SSH transport (#154). Adds
         * `ownerProfileId` to `tunnel_configs`: when non-null, the row is
         * owned by a single SSH profile and hidden from the standalone
         * Tunnels list. ConnectionRepository cascade-deletes by
         * ownerProfileId on profile delete.
         */
        val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db, "tunnel_configs", "ownerProfileId",
                    "TEXT DEFAULT NULL",
                )
            }
        }

        /**
         * Add `oidcClientSecret` to `step_ca_configs` (#133 phase 2c).
         * Confidential-client IdPs — Authentik default, Keycloak, Okta —
         * require the secret on the token-exchange POST or reject with
         * `invalid_client`. step-ca publishes the secret via its public
         * `/provisioners` endpoint so the new bootstrap flow can pre-fill
         * it; rows persisted before this migration keep the column NULL
         * and continue working as PKCE-only public clients.
         */
        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db, "step_ca_configs", "oidcClientSecret",
                    "TEXT DEFAULT NULL",
                )
            }
        }

        /**
         * Save rclone sync configurations for reuse (#159). Adds the
         * `sync_profiles` table so the SFTP folder-sync dialog can
         * persist a named src/dst/mode/filters bundle the user can
         * recall on subsequent runs.
         */
        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_profiles` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `srcFs` TEXT NOT NULL,
                        `dstFs` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `includePatterns` TEXT NOT NULL DEFAULT '',
                        `excludePatterns` TEXT NOT NULL DEFAULT '',
                        `minSize` TEXT,
                        `maxSize` TEXT,
                        `bandwidthLimit` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `lastRunAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Durable per-phase install log for the proot distro/DE pipeline
         * (issue #162 Phase 3b). One row per state transition + completion
         * + failure; logTail carries up to ~1500 chars of failing-command
         * output. Replaces the previous reliance on `adb logcat` for
         * post-mortem of install failures.
         */
        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `proot_install_log` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `distroId` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `phase` TEXT NOT NULL,
                        `deId` TEXT,
                        `exit` INTEGER,
                        `ok` INTEGER NOT NULL,
                        `message` TEXT,
                        `logTail` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_proot_install_log_distroId` " +
                        "ON `proot_install_log` (`distroId`)",
                )
            }
        }

        // #166: ordered multi-factor auth methods. Adds the authMethods
        // column and backfills each profile's single legacy method
        // (authType/keyId) as a one-element list, so existing profiles
        // connect exactly as before.
        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db, "connection_profiles", "authMethods", "TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    """
                    UPDATE `connection_profiles`
                    SET `authMethods` = CASE
                        WHEN `authType` = 'KEY' AND `keyId` IS NOT NULL AND `keyId` != ''
                            THEN 'KEY:' || `keyId`
                        WHEN `authType` = 'KEY' THEN 'KEY'
                        ELSE 'PASSWORD'
                    END
                    WHERE `authMethods` = ''
                    """.trimIndent(),
                )
            }
        }

        // #178: OATH-TOTP auto-fill. Adds the totp_secrets table and the
        // per-profile totpConfirmBeforeSend toggle (0 = auto-submit).
        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `totp_secrets` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `secret` TEXT NOT NULL,
                        `issuer` TEXT,
                        `accountName` TEXT,
                        `algorithm` TEXT NOT NULL DEFAULT 'SHA1',
                        `digits` INTEGER NOT NULL DEFAULT 6,
                        `periodSeconds` INTEGER NOT NULL DEFAULT 30,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                addColumnIfMissing(
                    db, "connection_profiles", "totpConfirmBeforeSend", "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        // #156: fwknop Single Packet Authorization. Per-profile SPA key/HMAC-key
        // (stored encrypted by ConnectionRepository), access spec, allow-IP mode,
        // explicit IP, and SPA UDP port. Empty key/access spec = SPA disabled.
        val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "spaKey", "TEXT DEFAULT NULL")
                addColumnIfMissing(db, "connection_profiles", "spaKeyBase64", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "connection_profiles", "spaHmacKey", "TEXT DEFAULT NULL")
                addColumnIfMissing(db, "connection_profiles", "spaHmacKeyBase64", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "connection_profiles", "spaAccessSpec", "TEXT DEFAULT NULL")
                addColumnIfMissing(db, "connection_profiles", "spaAllowMode", "TEXT NOT NULL DEFAULT 'SOURCE'")
                addColumnIfMissing(db, "connection_profiles", "spaExplicitIp", "TEXT DEFAULT NULL")
                addColumnIfMissing(db, "connection_profiles", "spaPort", "INTEGER NOT NULL DEFAULT 62201")
            }
        }

        // #121: per-profile "use password only" — when set, the connect path
        // never offers keystore keys, so a password-only server gets the
        // password prompt instead of failed key attempts.
        val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "ignoreSavedKeys", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        // #227: proxy username/password for SOCKS5/SOCKS4/HTTP proxies that
        // require authentication. Nullable TEXT, default null (no proxy auth).
        val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "proxyUser", "TEXT")
                addColumnIfMissing(db, "connection_profiles", "proxyPassword", "TEXT")
            }
        }

        // #EMAIL: embedded email connection type. Proton (v1) uses provider +
        // username + (mailbox)password + emailAuthMethods; the server/port/tls
        // columns serve the stage-2 JVM IMAP/SMTP path. Passwords are encrypted
        // at rest by ConnectionRepository, same as the other secret columns.
        val MIGRATION_60_61 = object : Migration(60, 61) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "emailProvider", "TEXT")
                addColumnIfMissing(db, "connection_profiles", "emailServer", "TEXT")
                addColumnIfMissing(db, "connection_profiles", "emailPort", "INTEGER NOT NULL DEFAULT 993")
                addColumnIfMissing(db, "connection_profiles", "emailSmtpPort", "INTEGER NOT NULL DEFAULT 465")
                addColumnIfMissing(db, "connection_profiles", "emailTls", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "connection_profiles", "emailUsername", "TEXT")
                addColumnIfMissing(db, "connection_profiles", "emailPassword", "TEXT")
                addColumnIfMissing(db, "connection_profiles", "emailMailboxPassword", "TEXT")
                addColumnIfMissing(db, "connection_profiles", "emailAuthMethods", "TEXT NOT NULL DEFAULT ''")
            }
        }

        // Per-distro LOCAL connections: a Local profile can open its proot
        // shell in a chosen distro instead of the global active one.
        val MIGRATION_61_62 = object : Migration(61, 62) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "prootDistroId", "TEXT")
            }
        }

        // Separate SMTP host for EMAIL/IMAP profiles. Real providers split the
        // IMAP and SMTP hosts (e.g. imap.gmail.com vs smtp.gmail.com), so a send
        // needs its own coordinate; null falls back to emailServer for the
        // self-hosted same-host case.
        val MIGRATION_62_63 = object : Migration(62, 63) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "emailSmtpServer", "TEXT")
            }
        }

        /**
         * Mail Rules — inbound-email automation. Four tables: the user-defined rules,
         * the per-(account,folder) poll high-water-mark, the firing/audit log, and the
         * queue of destructive actions awaiting foreground approval.
         */
        val MIGRATION_63_64 = object : Migration(63, 64) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mail_rules` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `orderIndex` INTEGER NOT NULL DEFAULT 0,
                        `accountProfileId` TEXT,
                        `folderId` TEXT NOT NULL DEFAULT 'INBOX',
                        `criteriaJson` TEXT NOT NULL,
                        `actionsJson` TEXT NOT NULL,
                        `stopOnMatch` INTEGER NOT NULL DEFAULT 0,
                        `notifyOnFire` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `lastFiredAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mail_watermarks` (
                        `profileId` TEXT NOT NULL,
                        `folderId` TEXT NOT NULL,
                        `uidValidity` INTEGER NOT NULL,
                        `lastSeenUid` INTEGER NOT NULL,
                        `uidNext` INTEGER,
                        `lastPolledAt` INTEGER NOT NULL,
                        PRIMARY KEY(`profileId`, `folderId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mail_rule_firings` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `ruleId` TEXT,
                        `profileId` TEXT NOT NULL,
                        `folderId` TEXT NOT NULL,
                        `uid` INTEGER NOT NULL,
                        `messageSubject` TEXT,
                        `firedAt` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL DEFAULT 'FIRED',
                        `actionsCompletedMask` INTEGER NOT NULL DEFAULT 0,
                        `outcomeSummary` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mail_rule_firings_profileId_folderId_uid` " +
                        "ON `mail_rule_firings` (`profileId`, `folderId`, `uid`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mail_rule_firings_ruleId` " +
                        "ON `mail_rule_firings` (`ruleId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mail_rule_pending_actions` (
                        `id` TEXT NOT NULL,
                        `ruleId` TEXT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `folderId` TEXT NOT NULL,
                        `uid` INTEGER NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `messageSubject` TEXT,
                        `actionJson` TEXT NOT NULL,
                        `queuedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Per-connection MCP/agent enable flag (default 1 = current behaviour). */
        val MIGRATION_64_65 = object : Migration(64, 65) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "mcpEnabled", "INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Tier-3 standing policies: scoped, rate-capped, expiring no-prompt grants. */
        val MIGRATION_65_66 = object : Migration(65, 66) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `standing_policies` (
                        `id` TEXT NOT NULL,
                        `clientHint` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `toolNamesJson` TEXT NOT NULL,
                        `argConstraintsJson` TEXT,
                        `maxCallsPerMinute` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Per-key opt-out of the "any saved key" auto-offer (Keys-tab toggle). */
        val MIGRATION_66_67 = object : Migration(66, 67) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "ssh_keys", "enabledForAuth", "INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_67_68 = object : Migration(67, 68) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "usbForwardVidPid", "TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_68_69 = object : Migration(68, 69) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `age_identities` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `recipient` TEXT NOT NULL,
                        `secret` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        /** Per-key opt-in stored passphrase for encrypted keys (#290). */
        val MIGRATION_69_70 = object : Migration(69, 70) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "ssh_keys", "passphraseEncrypted", "TEXT DEFAULT NULL")
            }
        }

        /** Per-profile terminal background opacity (null = inherit global). */
        val MIGRATION_70_71 = object : Migration(70, 71) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "terminalBackgroundOpacity", "REAL")
            }
        }

        /** SPICE connection fields (#286). */
        val MIGRATION_71_72 = object : Migration(71, 72) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "spicePort", "INTEGER")
                addColumnIfMissing(db, "connection_profiles", "spicePassword", "TEXT")
                addColumnIfMissing(db, "connection_profiles", "spiceSshForward", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "connection_profiles", "spiceSshProfileId", "TEXT")
            }
        }

        /** USB-drive VM bookmark serial (#287 re-open-on-click). */
        val MIGRATION_72_73 = object : Migration(72, 73) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "usbDriveSerial", "TEXT")
            }
        }

        /**
         * TOFU store for remote-desktop TLS server certificates (VNC
         * VeNCrypt X509 + RDP), the TLS analogue of `known_hosts`. Fixes
         * the "accepts any server certificate" criticals by pinning the
         * leaf cert fingerprint on first connect.
         */
        val MIGRATION_73_74 = object : Migration(73, 74) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `known_tls_certs` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `hostname` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `firstSeen` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Reusable SSH identities (#360): a named username+password+key
         * bundle, referenced by connections (own choice or NONE opt-out)
         * and by groups (inherited default).
         */
        val MIGRATION_74_75 = object : Migration(74, 75) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ssh_identities` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `password` TEXT,
                        `keyId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                addColumnIfMissing(db, "connection_profiles", "identityId", "TEXT")
                addColumnIfMissing(db, "connection_groups", "identityId", "TEXT")
            }
        }

        // Workspace terminal items remember their tmux/zellij/screen session so
        // restore reattaches by name instead of prompting (#360 follow-up).
        val MIGRATION_75_76 = object : Migration(75, 76) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "workspace_item", "sessionName", "TEXT")
            }
        }

        // Mosh session persistence (#371): the (ip, port, key) tuple from the
        // last successful mosh-server bootstrap, so a connect after an app
        // restart can re-attach to the still-running server without an SSH
        // round-trip, plus the per-profile opt-out toggle.
        val MIGRATION_76_77 = object : Migration(76, 77) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "connection_profiles", "moshReconnectToExisting", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "connection_profiles", "savedMoshKey", "TEXT")
                addColumnIfMissing(db, "connection_profiles", "savedMoshPort", "INTEGER")
                addColumnIfMissing(db, "connection_profiles", "savedMoshServerIp", "TEXT")
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            typeAndDefault: String,
        ) {
            val exists = db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                generateSequence { if (c.moveToNext()) c.getString(nameIdx) else null }
                    .any { it == column }
            }
            if (!exists) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $typeAndDefault")
            }
        }
    }
}
