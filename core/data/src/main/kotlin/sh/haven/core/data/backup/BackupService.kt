package sh.haven.core.data.backup

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.PortForwardRuleDao
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.SshKey
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
    private val sshKeyDao: SshKeyDao,
    private val sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    private val knownHostDao: KnownHostDao,
    private val portForwardRuleDao: PortForwardRuleDao,
    private val dataStore: DataStore<Preferences>,
) {
    data class BackupResult(val count: Int, val errors: List<String> = emptyList())

    suspend fun export(password: String): ByteArray {
        val json = JSONObject()
        json.put("version", BACKUP_VERSION)
        json.put("created", System.currentTimeMillis())

        // Connections
        val connections = JSONArray()
        connectionDao.getAll().forEach { p ->
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
                put("vncPort", p.vncPort ?: JSONObject.NULL)
                put("vncPassword", p.vncPassword ?: JSONObject.NULL)
                put("vncSshForward", p.vncSshForward)
                put("sessionManager", p.sessionManager ?: JSONObject.NULL)
            })
        }
        json.put("connections", connections)

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
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("Key ${i}: ${e.message}")
                }
            }
        }

        // Connections
        val connections = json.optJSONArray("connections")
        if (connections != null) {
            for (i in 0 until connections.length()) {
                try {
                    val c = connections.getJSONObject(i)
                    connectionDao.upsert(
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
                            vncPort = c.optIntOrNull("vncPort"),
                            vncPassword = c.optStringOrNull("vncPassword"),
                            vncSshForward = c.optBoolean("vncSshForward", true),
                            sessionManager = c.optStringOrNull("sessionManager"),
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
        private const val BACKUP_VERSION = 1
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
