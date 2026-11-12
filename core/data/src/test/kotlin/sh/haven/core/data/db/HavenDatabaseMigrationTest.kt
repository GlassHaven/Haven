package sh.haven.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room migration tests for [HavenDatabase]. Each test materializes the
 * *previous* schema straight from the committed JSON under
 * `core/data/schemas` (via [MigrationTestHelper]), seeds rows at the old
 * shape, then reopens the file through `Room.databaseBuilder` with the
 * real migration registered — so the migration itself runs and the DAO
 * reads prove the new column exists with the right type and default.
 *
 * Opening through the builder (rather than the helper's
 * `runMigrationsAndValidate`) deliberately avoids depending on the
 * freshly generated schema JSON being packaged into test assets before
 * first build; the compiled Room implementation is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
class HavenDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HavenDatabase::class.java,
    )

    @Test
    fun `migration 87 to 88 adds nullable hardwareAlias to ssh_keys`() {
        val dbName = "migration-test-87.db"
        helper.createDatabase(dbName, 87).apply {
            execSQL(
                """
                INSERT INTO ssh_keys (
                    id, label, keyType, privateKeyBytes,
                    publicKeyOpenSsh, fingerprintSha256, createdAt,
                    isEncrypted, biometricProtected, enabledForAuth
                ) VALUES (
                    'legacy-1', 'work laptop', 'ssh-ed25519', x'010203',
                    'ssh-ed25519 AAAA', 'SHA256:legacy', 42,
                    0, 0, 1
                )
                """.trimIndent()
            )
            close()
        }

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HavenDatabase::class.java,
            dbName,
        )
            .addMigrations(HavenDatabase.MIGRATION_87_88, HavenDatabase.MIGRATION_88_89, HavenDatabase.MIGRATION_89_90)
            .build()

        db.openHelper.writableDatabase // force open + migration
        try {
            runBlocking {
                val dao = db.sshKeyDao()

                // Pre-existing software rows migrate to hardwareAlias = NULL
                // (they are software keys by definition).
                assertNull(dao.getById("legacy-1")?.hardwareAlias)

                // A hardware-resident row round-trips its Android Keystore alias.
                dao.upsert(hardwareKey("hw-1", alias = "haven_hw_hw-1"))
                assertEquals("haven_hw_hw-1", dao.getById("hw-1")?.hardwareAlias)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 88 to 89 adds nullable keyboardLayout to connection_profiles`() {
        val dbName = "migration-test-88.db"
        helper.createDatabase(dbName, 88).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HavenDatabase::class.java,
            dbName,
        )
            .addMigrations(HavenDatabase.MIGRATION_88_89, HavenDatabase.MIGRATION_89_90)
            .build()

        db.openHelper.writableDatabase
        try {
            runBlocking {
                val dao = db.connectionDao()
                val spiceProfile = sh.haven.core.data.db.entities.ConnectionProfile(
                    id = "spice-es-1",
                    label = "Debian 13 SPICE",
                    host = "192.168.122.10",
                    port = 5900,
                    username = "",
                    connectionType = "SPICE",
                    spicePort = 5900,
                    keyboardLayout = "es-ES",
                )
                dao.upsert(spiceProfile)
                assertEquals("es-ES", dao.getById("spice-es-1")?.keyboardLayout)

                val defaultProfile = sh.haven.core.data.db.entities.ConnectionProfile(
                    id = "spice-auto-1",
                    label = "Auto Layout SPICE",
                    host = "192.168.122.11",
                    port = 5900,
                    username = "",
                    connectionType = "SPICE",
                    spicePort = 5900,
                )
                dao.upsert(defaultProfile)
                assertNull(dao.getById("spice-auto-1")?.keyboardLayout)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 89 to 90 adds nullable localShellMode to connection_profiles`() {
        val dbName = "migration-test-89.db"
        helper.createDatabase(dbName, 89).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HavenDatabase::class.java,
            dbName,
        )
            .addMigrations(HavenDatabase.MIGRATION_89_90)
            .build()

        db.openHelper.writableDatabase
        try {
            runBlocking {
                val dao = db.connectionDao()
                val shizukuProfile = sh.haven.core.data.db.entities.ConnectionProfile(
                    id = "local-shizuku-1",
                    label = "Shizuku Shell",
                    host = "",
                    port = 22,
                    username = "",
                    connectionType = "LOCAL",
                    localShellMode = "SHIZUKU",
                )
                dao.upsert(shizukuProfile)
                val loaded = dao.getById("local-shizuku-1")
                assertEquals("SHIZUKU", loaded?.localShellMode)
                assertEquals(
                    sh.haven.core.data.db.entities.ConnectionProfile.LocalShellMode.SHIZUKU,
                    loaded?.resolvedLocalShellMode,
                )

                val legacyAndroid = sh.haven.core.data.db.entities.ConnectionProfile(
                    id = "local-android-legacy",
                    label = "Android Shell",
                    host = "",
                    port = 22,
                    username = "",
                    connectionType = "LOCAL",
                    useAndroidShell = true,
                )
                dao.upsert(legacyAndroid)
                val loadedLegacy = dao.getById("local-android-legacy")
                assertNull(loadedLegacy?.localShellMode)
                assertEquals(
                    sh.haven.core.data.db.entities.ConnectionProfile.LocalShellMode.ANDROID_SHELL,
                    loadedLegacy?.resolvedLocalShellMode,
                )
            }
        } finally {
            db.close()
        }
    }

    /**
     * The full registered chain from the last shipped schema (85) to the
     * current one (90): GLKVM's five connection_profiles columns (86→87),
     * ssh_keys.hardwareAlias (87→88), connection_profiles.keyboardLayout
     * (88→89), and connection_profiles.localShellMode (89→90) must all land —
     * a renumbered migration that skipped or reordered a step would fail here,
     * not at a user's next app update.
     */
    @Test
    fun `migration chain 85 to 89 runs every step in order`() {
        val dbName = "migration-chain-85.db"
        helper.createDatabase(dbName, 85).apply {
            execSQL(
                """
                INSERT INTO ssh_keys (
                    id, label, keyType, privateKeyBytes,
                    publicKeyOpenSsh, fingerprintSha256, createdAt,
                    isEncrypted, biometricProtected, enabledForAuth
                ) VALUES (
                    'chain-1', 'work laptop', 'ssh-ed25519', x'010203',
                    'ssh-ed25519 AAAA', 'SHA256:chain', 42,
                    0, 0, 1
                )
                """.trimIndent()
            )
            close()
        }

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HavenDatabase::class.java,
            dbName,
        )
            .addMigrations(
                HavenDatabase.MIGRATION_85_86,
                HavenDatabase.MIGRATION_86_87,
                HavenDatabase.MIGRATION_87_88,
                HavenDatabase.MIGRATION_88_89,
                HavenDatabase.MIGRATION_89_90,
            )
            .build()

        db.openHelper.writableDatabase // force open + full chain
        try {
            runBlocking {
                // 87→88 landed: the seeded software row is NULL-aliased.
                assertNull(db.sshKeyDao().getById("chain-1")?.hardwareAlias)
            }
            // 86→87, 88→89, and 89→90 landed: GLKVM columns, keyboardLayout, and localShellMode exist.
            val profileColumns = db.openHelper.readableDatabase
                .query("PRAGMA table_info(connection_profiles)").use { c ->
                    val idx = c.getColumnIndex("name")
                    generateSequence { if (c.moveToNext()) c.getString(idx) else null }
                        .toSet()
                }
            assertTrue(
                profileColumns.containsAll(
                    setOf(
                        "glkvmPort", "glkvmPassword", "glkvmTargetLayout",
                        "glkvmSshForward", "glkvmSshProfileId", "keyboardLayout",
                        "localShellMode",
                    ),
                ),
            )
            assertTrue(db.openHelper.readableDatabase.isDatabaseIntegrityOk)
        } finally {
            db.close()
        }
    }

    private fun hardwareKey(id: String, alias: String) = sh.haven.core.data.db.entities.SshKey(
        id = id,
        label = id,
        keyType = "ecdsa-sha2-nistp256",
        // Storage convention: no local private material for hardware keys.
        privateKeyBytes = ByteArray(0),
        publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA",
        fingerprintSha256 = "SHA256:$id",
        hardwareAlias = alias,
    )
}
