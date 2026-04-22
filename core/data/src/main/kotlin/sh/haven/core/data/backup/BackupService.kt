package sh.haven.core.data.backup

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.ConnectionGroupDao
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.PortForwardRuleDao
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.repository.TunnelConfigRepository
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupService @Inject constructor(
    private val connectionDao: ConnectionDao,
    private val connectionRepository: sh.haven.core.data.repository.ConnectionRepository,
    private val connectionGroupDao: ConnectionGroupDao,
    private val sshKeyDao: SshKeyDao,
    private val sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    private val knownHostDao: KnownHostDao,
    private val portForwardRuleDao: PortForwardRuleDao,
    private val tunnelConfigRepository: TunnelConfigRepository,
    private val dataStore: DataStore<Preferences>,
) {
    data class BackupResult(val count: Int, val errors: List<String> = emptyList())

    suspend fun export(password: String): ByteArray {
        val json = JSONObject()
        json.put("version", BACKUP_VERSION)
        json.put("created", System.currentTimeMillis())

        // Connections (decrypted — backup file has its own encryption layer)
        val connections = JSONArray()
        connectionRepository.getAll().forEach { p ->
            connections.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("host", p.host)
                put("port", p.port)
                put("username", p.username)
                put("sshPassword", p.sshPassword ?: JSONObject.NULL)
                put("authType", p.authType.name)
                put("keyId", p.keyId ?: JSONObject.NULL)
                put("colorTag", p.colorTag)
                put("lastConnected", p.lastConnected ?: JSONObject.NULL)
                put("sortOrder", p.sortOrder)
                put("connectionType", p.connectionType)
                put("destinationHash", p.destinationHash ?: JSONObject.NULL)
                put("reticulumHost", p.reticulumHost)
                put("reticulumPort", p.reticulumPort)
                put("jumpProfileId", p.jumpProfileId ?: JSONObject.NULL)
                put("sshOptions", p.sshOptions ?: JSONObject.NULL)
                put("moshServerCommand", p.moshServerCommand ?: JSONObject.NULL)
                put("vncPort", p.vncPort ?: JSONObject.NULL)
                put("vncUsername", p.vncUsername ?: JSONObject.NULL)
                put("vncPassword", p.vncPassword ?: JSONObject.NULL)
                put("vncSshForward", p.vncSshForward)
                put("sessionManager", p.sessionManager ?: JSONObject.NULL)
                put("useMosh", p.useMosh)
                put("useEternalTerminal", p.useEternalTerminal)
                put("etPort", p.etPort)
                put("rdpPort", p.rdpPort)
                put("rdpUsername", p.rdpUsername ?: JSONObject.NULL)
                put("rdpDomain", p.rdpDomain ?: JSONObject.NULL)
                put("rdpPassword", p.rdpPassword ?: JSONObject.NULL)
                put("rdpSshForward", p.rdpSshForward)
                put("rdpSshProfileId", p.rdpSshProfileId ?: JSONObject.NULL)
                put("smbPort", p.smbPort)
                put("smbShare", p.smbShare ?: JSONObject.NULL)
                put("smbDomain", p.smbDomain ?: JSONObject.NULL)
                put("smbPassword", p.smbPassword ?: JSONObject.NULL)
                put("smbSshForward", p.smbSshForward)
                put("smbSshProfileId", p.smbSshProfileId ?: JSONObject.NULL)
                put("proxyType", p.proxyType ?: JSONObject.NULL)
                put("proxyHost", p.proxyHost ?: JSONObject.NULL)
                put("proxyPort", p.proxyPort)
                put("groupId", p.groupId ?: JSONObject.NULL)
                // Version-2 additions — fields the v1 schema missed. Adding
                // them here unconditionally is safe because the v1 importer
                // uses opt*() helpers that fall back to the ConnectionProfile
                // default when a key is absent.
                put("lastSessionName", p.lastSessionName ?: JSONObject.NULL)
                put("disableAltScreen", p.disableAltScreen)
                put("rcloneRemoteName", p.rcloneRemoteName ?: JSONObject.NULL)
                put("rcloneProvider", p.rcloneProvider ?: JSONObject.NULL)
                put("useAndroidShell", p.useAndroidShell)
                put("forwardAgent", p.forwardAgent)
                put("reticulumNetworkName", p.reticulumNetworkName ?: JSONObject.NULL)
                put("reticulumPassphrase", p.reticulumPassphrase ?: JSONObject.NULL)
                put("postLoginCommand", p.postLoginCommand ?: JSONObject.NULL)
                put("postLoginBeforeSessionManager", p.postLoginBeforeSessionManager)
                put("fileTransport", p.fileTransport)
                put("tunnelConfigId", p.tunnelConfigId ?: JSONObject.NULL)
            })
        }
        json.put("connections", connections)

        // Tunnel configs (WireGuard / Tailscale). Decrypted here — the backup
        // file has its own AES-GCM layer around the whole payload.
        val tunnels = JSONArray()
        tunnelConfigRepository.getAllDecrypted().forEach { t ->
            tunnels.put(JSONObject().apply {
                put("id", t.id)
                put("label", t.label)
                put("type", t.type)
                put("configText", Base64.encodeToString(t.configText, Base64.NO_WRAP))
                put("createdAt", t.createdAt)
            })
        }
        json.put("tunnels", tunnels)

        // Connection groups
        val groups = JSONArray()
        connectionGroupDao.getAll().forEach { g ->
            groups.put(JSONObject().apply {
                put("id", g.id)
                put("label", g.label)
                put("colorTag", g.colorTag)
                put("sortOrder", g.sortOrder)
                put("collapsed", g.collapsed)
            })
        }
        json.put("groups", groups)

        // SSH keys
        val keys = JSONArray()
        sshKeyRepository.getAllDecrypted().forEach { k ->
            keys.put(JSONObject().apply {
                put("id", k.id)
                put("label", k.label)
                put("keyType", k.keyType)
                put("privateKeyBytes", Base64.encodeToString(k.privateKeyBytes, Base64.NO_WRAP))
                put("publicKeyOpenSsh", k.publicKeyOpenSsh)
                put("fingerprintSha256", k.fingerprintSha256)
                put("createdAt", k.createdAt)
                // Whether the key material itself is passphrase-protected —
                // distinct from on-disk encryption. Backups need to preserve
                // this so JSch knows to prompt for the passphrase on use.
                put("isEncrypted", k.isEncrypted)
            })
        }
        json.put("keys", keys)

        // Known hosts
        val hosts = JSONArray()
        knownHostDao.getAll().forEach { h ->
            hosts.put(JSONObject().apply {
                put("hostname", h.hostname)
                put("port", h.port)
                put("keyType", h.keyType)
                put("publicKeyBase64", h.publicKeyBase64)
                put("fingerprint", h.fingerprint)
                put("firstSeen", h.firstSeen)
            })
        }
        json.put("knownHosts", hosts)

        // Port forward rules
        val forwards = JSONArray()
        portForwardRuleDao.getAll().forEach { r ->
            forwards.put(JSONObject().apply {
                put("id", r.id)
                put("profileId", r.profileId)
                put("type", r.type.name)
                put("bindAddress", r.bindAddress)
                put("bindPort", r.bindPort)
                put("targetHost", r.targetHost)
                put("targetPort", r.targetPort)
                put("enabled", r.enabled)
            })
        }
        json.put("portForwards", forwards)

        // Settings (DataStore preferences)
        val settings = JSONObject()
        val prefs = dataStore.data.first()
        prefs.asMap().forEach { (key, value) ->
            when (value) {
                is String -> settings.put(key.name, value)
                is Int -> settings.put(key.name, value)
                is Boolean -> settings.put(key.name, value)
                is Long -> settings.put(key.name, value)
                is Float -> settings.put(key.name, value.toDouble())
                is Double -> settings.put(key.name, value)
            }
        }
        json.put("settings", settings)

        return encrypt(json.toString().toByteArray(Charsets.UTF_8), password)
    }

    suspend fun import(data: ByteArray, password: String): BackupResult {
        val plaintext = decrypt(data, password)
        val json = JSONObject(String(plaintext, Charsets.UTF_8))
        val version = json.optInt("version", 1)
        if (version > BACKUP_VERSION) {
            return BackupResult(0, listOf("Backup version $version is newer than supported ($BACKUP_VERSION)"))
        }

        var count = 0
        val errors = mutableListOf<String>()

        // Connection groups (import before connections since connections reference groupId)
        val groups = json.optJSONArray("groups")
        if (groups != null) {
            for (i in 0 until groups.length()) {
                try {
                    val g = groups.getJSONObject(i)
                    connectionGroupDao.upsert(
                        ConnectionGroup(
                            id = g.getString("id"),
                            label = g.getString("label"),
                            colorTag = g.optInt("colorTag", 0),
                            sortOrder = g.optInt("sortOrder", 0),
                            collapsed = g.optBoolean("collapsed", false),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("Group ${i}: ${e.message}")
                }
            }
        }

        // SSH keys (import first since connections reference keyId)
        val keys = json.optJSONArray("keys")
        if (keys != null) {
            for (i in 0 until keys.length()) {
                try {
                    val k = keys.getJSONObject(i)
                    sshKeyRepository.save(
                        SshKey(
                            id = k.getString("id"),
                            label = k.getString("label"),
                            keyType = k.getString("keyType"),
                            privateKeyBytes = Base64.decode(k.getString("privateKeyBytes"), Base64.NO_WRAP),
                            publicKeyOpenSsh = k.getString("publicKeyOpenSsh"),
                            fingerprintSha256 = k.getString("fingerprintSha256"),
                            createdAt = k.getLong("createdAt"),
                            isEncrypted = k.optBoolean("isEncrypted", false),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("Key ${i}: ${e.message}")
                }
            }
        }

        // Tunnel configs (v2+). Import before connections so each
        // ConnectionProfile.tunnelConfigId has a target row to reference.
        // v1 backups simply won't have this key — optJSONArray returns null
        // and we skip.
        val tunnels = json.optJSONArray("tunnels")
        if (tunnels != null) {
            for (i in 0 until tunnels.length()) {
                try {
                    val t = tunnels.getJSONObject(i)
                    tunnelConfigRepository.save(
                        TunnelConfig(
                            id = t.getString("id"),
                            label = t.getString("label"),
                            type = t.getString("type"),
                            configText = Base64.decode(t.getString("configText"), Base64.NO_WRAP),
                            createdAt = t.optLong("createdAt", System.currentTimeMillis()),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("Tunnel ${i}: ${e.message}")
                }
            }
        }

        // Connections
        val connections = json.optJSONArray("connections")
        if (connections != null) {
            for (i in 0 until connections.length()) {
                try {
                    val c = connections.getJSONObject(i)
                    connectionRepository.save(
                        ConnectionProfile(
                            id = c.getString("id"),
                            label = c.getString("label"),
                            host = c.getString("host"),
                            port = c.getInt("port"),
                            username = c.getString("username"),
                            sshPassword = c.optStringOrNull("sshPassword"),
                            authType = ConnectionProfile.AuthType.valueOf(
                                c.optString("authType", "PASSWORD"),
                            ),
                            keyId = c.optStringOrNull("keyId"),
                            colorTag = c.optInt("colorTag", 0),
                            lastConnected = c.optLongOrNull("lastConnected"),
                            sortOrder = c.optInt("sortOrder", 0),
                            connectionType = c.optString("connectionType", "SSH"),
                            destinationHash = c.optStringOrNull("destinationHash"),
                            reticulumHost = c.optString("reticulumHost", "127.0.0.1"),
                            reticulumPort = c.optInt("reticulumPort", 37428),
                            jumpProfileId = c.optStringOrNull("jumpProfileId"),
                            sshOptions = c.optStringOrNull("sshOptions"),
                            moshServerCommand = c.optStringOrNull("moshServerCommand"),
                            vncPort = c.optIntOrNull("vncPort"),
                            vncUsername = c.optStringOrNull("vncUsername"),
                            vncPassword = c.optStringOrNull("vncPassword"),
                            vncSshForward = c.optBoolean("vncSshForward", true),
                            sessionManager = c.optStringOrNull("sessionManager"),
                            useMosh = c.optBoolean("useMosh", false),
                            useEternalTerminal = c.optBoolean("useEternalTerminal", false),
                            etPort = c.optInt("etPort", 2022),
                            rdpPort = c.optInt("rdpPort", 3389),
                            rdpUsername = c.optStringOrNull("rdpUsername"),
                            rdpDomain = c.optStringOrNull("rdpDomain"),
                            rdpPassword = c.optStringOrNull("rdpPassword"),
                            rdpSshForward = c.optBoolean("rdpSshForward", false),
                            rdpSshProfileId = c.optStringOrNull("rdpSshProfileId"),
                            smbPort = c.optInt("smbPort", 445),
                            smbShare = c.optStringOrNull("smbShare"),
                            smbDomain = c.optStringOrNull("smbDomain"),
                            smbPassword = c.optStringOrNull("smbPassword"),
                            smbSshForward = c.optBoolean("smbSshForward", false),
                            smbSshProfileId = c.optStringOrNull("smbSshProfileId"),
                            proxyType = c.optStringOrNull("proxyType"),
                            proxyHost = c.optStringOrNull("proxyHost"),
                            proxyPort = c.optInt("proxyPort", 1080),
                            groupId = c.optStringOrNull("groupId"),
                            // v2 additions. v1 backups don't carry these keys,
                            // so opt* falls back to the ConnectionProfile defaults
                            // — safe because v1 was written when these fields
                            // either didn't exist or were always at default.
                            lastSessionName = c.optStringOrNull("lastSessionName"),
                            disableAltScreen = c.optBoolean("disableAltScreen", false),
                            rcloneRemoteName = c.optStringOrNull("rcloneRemoteName"),
                            rcloneProvider = c.optStringOrNull("rcloneProvider"),
                            useAndroidShell = c.optBoolean("useAndroidShell", false),
                            forwardAgent = c.optBoolean("forwardAgent", false),
                            reticulumNetworkName = c.optStringOrNull("reticulumNetworkName"),
                            reticulumPassphrase = c.optStringOrNull("reticulumPassphrase"),
                            postLoginCommand = c.optStringOrNull("postLoginCommand"),
                            postLoginBeforeSessionManager = c.optBoolean("postLoginBeforeSessionManager", true),
                            fileTransport = c.optString("fileTransport", "AUTO"),
                            tunnelConfigId = c.optStringOrNull("tunnelConfigId"),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("Connection ${i}: ${e.message}")
                }
            }
        }

        // Known hosts
        val hosts = json.optJSONArray("knownHosts")
        if (hosts != null) {
            for (i in 0 until hosts.length()) {
                try {
                    val h = hosts.getJSONObject(i)
                    knownHostDao.upsert(
                        KnownHost(
                            hostname = h.getString("hostname"),
                            port = h.getInt("port"),
                            keyType = h.getString("keyType"),
                            publicKeyBase64 = h.getString("publicKeyBase64"),
                            fingerprint = h.getString("fingerprint"),
                            firstSeen = h.getLong("firstSeen"),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("KnownHost ${i}: ${e.message}")
                }
            }
        }

        // Port forward rules
        val forwards = json.optJSONArray("portForwards")
        if (forwards != null) {
            for (i in 0 until forwards.length()) {
                try {
                    val r = forwards.getJSONObject(i)
                    portForwardRuleDao.upsert(
                        PortForwardRule(
                            id = r.getString("id"),
                            profileId = r.getString("profileId"),
                            type = PortForwardRule.Type.valueOf(r.getString("type")),
                            bindAddress = r.getString("bindAddress"),
                            bindPort = r.getInt("bindPort"),
                            targetHost = r.getString("targetHost"),
                            targetPort = r.getInt("targetPort"),
                            enabled = r.getBoolean("enabled"),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("PortForward ${i}: ${e.message}")
                }
            }
        }

        // Settings
        val settings = json.optJSONObject("settings")
        if (settings != null) {
            dataStore.updateData { prefs ->
                val mutable = prefs.toMutablePreferences()
                settings.keys().forEach { key ->
                    val value = settings.get(key)
                    when (value) {
                        is String -> mutable[androidx.datastore.preferences.core.stringPreferencesKey(key)] = value
                        is Int -> mutable[androidx.datastore.preferences.core.intPreferencesKey(key)] = value
                        is Boolean -> mutable[androidx.datastore.preferences.core.booleanPreferencesKey(key)] = value
                        is Long -> mutable[androidx.datastore.preferences.core.longPreferencesKey(key)] = value
                        is Double -> mutable[androidx.datastore.preferences.core.intPreferencesKey(key)] = value.toInt()
                    }
                    count++
                }
                mutable
            }
        }

        return BackupResult(count, errors)
    }

    private fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        // Format: MAGIC + salt + iv + ciphertext
        return MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + ciphertext
    }

    private fun decrypt(data: ByteArray, password: String): ByteArray {
        val magic = String(data, 0, MAGIC.length, Charsets.US_ASCII)
        if (magic != MAGIC) throw IllegalArgumentException("Not a Haven backup file")
        var offset = MAGIC.length
        val salt = data.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = data.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
        val ciphertext = data.copyOfRange(offset, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }

    companion object {
        // v2 (2026-04-21): add tunnels section (WireGuard / Tailscale) and the
        // 13 ConnectionProfile fields the v1 schema silently dropped. Files
        // keep the original HAVEN_BACKUP_V1 magic header because the envelope
        // format (AES-GCM wrapper) is unchanged — the version bump only
        // affects the inner JSON payload schema. v1 files still restore
        // cleanly (the new keys default to ConnectionProfile defaults).
        private const val BACKUP_VERSION = 2
        private const val MAGIC = "HAVEN_BACKUP_V1"
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val PBKDF2_ITERATIONS = 100_000
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    return if (isNull(key)) null else optString(key, null)
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    return if (isNull(key)) null else optLong(key)
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    return if (isNull(key)) null else optInt(key)
}
