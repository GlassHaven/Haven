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
import sh.haven.core.data.db.KnownTlsCertDao
import sh.haven.core.data.db.PortForwardRuleDao
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.AgeIdentityEntity
import sh.haven.core.data.db.entities.AgentAuditEvent
import sh.haven.core.data.db.entities.ChatConversation
import sh.haven.core.data.db.entities.ChatMessage
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
import sh.haven.core.data.db.entities.SshIdentity
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.db.entities.StandingPolicy
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.data.db.entities.SyncProfile
import sh.haven.core.data.db.entities.TotpSecret
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile
import sh.haven.core.data.repository.AgeIdentityRepository
import sh.haven.core.data.repository.ChatRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.MailRuleRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.data.repository.StepCaConfigRepository
import sh.haven.core.data.repository.SyncProfileRepository
import sh.haven.core.data.repository.TotpSecretRepository
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.data.repository.WorkspaceRepository
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted backup / restore for connection profiles, keys, authenticator
 * (TOTP) secrets, known hosts, port-forwards, tunnel configs, and a small
 * block of settings.
 *
 * The wire format and a manual-decrypt Python recipe live in
 * `docs/backup-format.md`. Touch that file when changing [encrypt],
 * [decrypt], [BACKUP_VERSION] or any of the envelope constants below.
 */
@Singleton
class BackupService @Inject constructor(
    private val connectionDao: ConnectionDao,
    private val connectionRepository: ConnectionRepository,
    private val connectionGroupDao: ConnectionGroupDao,
    private val sshKeyDao: SshKeyDao,
    private val sshKeyRepository: SshKeyRepository,
    private val knownHostDao: KnownHostDao,
    private val portForwardRuleDao: PortForwardRuleDao,
    private val tunnelConfigRepository: TunnelConfigRepository,
    private val sshIdentityRepository: SshIdentityRepository,
    private val totpSecretRepository: TotpSecretRepository,
    private val dataStore: DataStore<Preferences>,
    private val chatRepository: ChatRepository,
    private val ageIdentityRepository: AgeIdentityRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val syncProfileRepository: SyncProfileRepository,
    private val stepCaConfigRepository: StepCaConfigRepository,
    private val knownTlsCertDao: KnownTlsCertDao,
    private val mailRuleRepository: MailRuleRepository,
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
                put("vncSshProfileId", p.vncSshProfileId ?: JSONObject.NULL)
                put("vncColorDepth", p.vncColorDepth)
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
                put("rdpUseNla", p.rdpUseNla)
                put("rdpColorDepth", p.rdpColorDepth)
                put("smbPort", p.smbPort)
                put("smbShare", p.smbShare ?: JSONObject.NULL)
                put("smbDomain", p.smbDomain ?: JSONObject.NULL)
                put("smbPassword", p.smbPassword ?: JSONObject.NULL)
                put("smbSshForward", p.smbSshForward)
                put("smbSshProfileId", p.smbSshProfileId ?: JSONObject.NULL)
                put("proxyType", p.proxyType ?: JSONObject.NULL)
                put("proxyHost", p.proxyHost ?: JSONObject.NULL)
                put("proxyPort", p.proxyPort)
                put("proxyUser", p.proxyUser ?: JSONObject.NULL)
                put("proxyPassword", p.proxyPassword ?: JSONObject.NULL)
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
                put("localShellMode", p.localShellMode ?: JSONObject.NULL)
                put("forwardAgent", p.forwardAgent)
                put("reticulumNetworkName", p.reticulumNetworkName ?: JSONObject.NULL)
                put("reticulumPassphrase", p.reticulumPassphrase ?: JSONObject.NULL)
                put("postLoginCommand", p.postLoginCommand ?: JSONObject.NULL)
                put("postLoginBeforeSessionManager", p.postLoginBeforeSessionManager)
                put("remoteCommand", p.remoteCommand ?: JSONObject.NULL)
                put("requestPty", p.requestPty)
                put("fileTransport", p.fileTransport)
                put("tunnelConfigId", p.tunnelConfigId ?: JSONObject.NULL)
                put("terminalColorScheme", p.terminalColorScheme ?: JSONObject.NULL)
                put("terminalBackgroundOpacity", p.terminalBackgroundOpacity?.toDouble() ?: JSONObject.NULL)
                put("portKnockSequence", p.portKnockSequence ?: JSONObject.NULL)
                put("portKnockDelayMs", p.portKnockDelayMs)
                put("spaKey", p.spaKey ?: JSONObject.NULL)
                put("spaKeyBase64", p.spaKeyBase64)
                put("spaHmacKey", p.spaHmacKey ?: JSONObject.NULL)
                put("spaHmacKeyBase64", p.spaHmacKeyBase64)
                put("spaAccessSpec", p.spaAccessSpec ?: JSONObject.NULL)
                put("spaAllowMode", p.spaAllowMode)
                put("spaExplicitIp", p.spaExplicitIp ?: JSONObject.NULL)
                put("spaPort", p.spaPort)
                put("identityId", p.identityId ?: JSONObject.NULL)
                put("authMethods", p.authMethods)
                put("totpConfirmBeforeSend", p.totpConfirmBeforeSend)
                put("ignoreSavedKeys", p.ignoreSavedKeys)
                put("spicePort", p.spicePort ?: JSONObject.NULL)
                put("spicePassword", p.spicePassword ?: JSONObject.NULL)
                put("spiceSshForward", p.spiceSshForward)
                put("spiceSshProfileId", p.spiceSshProfileId ?: JSONObject.NULL)
                put("keyboardLayout", p.keyboardLayout ?: JSONObject.NULL)
                put("glkvmPort", p.glkvmPort ?: JSONObject.NULL)
                put("glkvmPassword", p.glkvmPassword ?: JSONObject.NULL)
                put("glkvmTargetLayout", p.glkvmTargetLayout ?: JSONObject.NULL)
                put("glkvmSshForward", p.glkvmSshForward)
                put("glkvmSshProfileId", p.glkvmSshProfileId ?: JSONObject.NULL)
                put("emailProvider", p.emailProvider ?: JSONObject.NULL)
                put("emailServer", p.emailServer ?: JSONObject.NULL)
                put("emailSmtpServer", p.emailSmtpServer ?: JSONObject.NULL)
                put("emailPort", p.emailPort)
                put("emailSmtpPort", p.emailSmtpPort)
                put("emailTls", p.emailTls)
                put("emailUsername", p.emailUsername ?: JSONObject.NULL)
                put("emailPassword", p.emailPassword ?: JSONObject.NULL)
                put("emailMailboxPassword", p.emailMailboxPassword ?: JSONObject.NULL)
                put("emailAuthMethods", p.emailAuthMethods)
                put("openaiPathPrefix", p.openaiPathPrefix ?: JSONObject.NULL)
                put("openaiApiKey", p.openaiApiKey ?: JSONObject.NULL)
                put("aiProtocol", p.aiProtocol ?: JSONObject.NULL)
                put("aiRouteType", p.aiRouteType ?: JSONObject.NULL)
                put("aiRouteProfileId", p.aiRouteProfileId ?: JSONObject.NULL)
                put("sessionCommandOverride", p.sessionCommandOverride ?: JSONObject.NULL)
                put("safTreeUri", p.safTreeUri ?: JSONObject.NULL)
                put("prootDistroId", p.prootDistroId ?: JSONObject.NULL)
                put("addressFamily", p.addressFamily)
                put("bindAddress", p.bindAddress ?: JSONObject.NULL)
                put("usbForwardVidPid", p.usbForwardVidPid ?: JSONObject.NULL)
                put("autoReconnect", p.autoReconnect)
                put("reconnectMaxAttempts", p.reconnectMaxAttempts)
                put("reconnectOnNetworkChange", p.reconnectOnNetworkChange)
                put("tunnelOnly", p.tunnelOnly)
                put("mcpEnabled", p.mcpEnabled)
                put("usbDriveSerial", p.usbDriveSerial ?: JSONObject.NULL)
            })
        }
        json.put("connections", connections)

        // Tunnel configs (WireGuard / Tailscale / Cloudflare / NetBird / AnyConnect / OpenVPN).
        // Decrypted here — the backup file has its own AES-GCM layer around the whole payload.
        val tunnels = JSONArray()
        tunnelConfigRepository.getAllDecrypted().forEach { t ->
            tunnels.put(JSONObject().apply {
                put("id", t.id)
                put("label", t.label)
                put("type", t.type)
                put("configText", Base64.encodeToString(t.configText, Base64.NO_WRAP))
                put("createdAt", t.createdAt)
                put("ownerProfileId", t.ownerProfileId ?: JSONObject.NULL)
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
                put("identityId", g.identityId ?: JSONObject.NULL)
            })
        }
        json.put("groups", groups)

        // SSH identities (#360). Decrypted — the backup file has its own
        // AES-GCM layer. Absent from pre-#360 backups; the importer skips.
        val identities = JSONArray()
        sshIdentityRepository.getAllDecrypted().forEach { ident ->
            identities.put(JSONObject().apply {
                put("id", ident.id)
                put("name", ident.name)
                put("username", ident.username)
                put("password", ident.password ?: JSONObject.NULL)
                put("keyId", ident.keyId ?: JSONObject.NULL)
                put("createdAt", ident.createdAt)
            })
        }
        json.put("identities", identities)

        // step-ca certificate authority configs (#133).
        val stepCaConfigs = JSONArray()
        stepCaConfigRepository.getAll().forEach { ca ->
            stepCaConfigs.put(JSONObject().apply {
                put("id", ca.id)
                put("name", ca.name)
                put("caUrl", ca.caUrl)
                put("oidcIssuer", ca.oidcIssuer)
                put("oidcAuthUrl", ca.oidcAuthUrl)
                put("oidcTokenUrl", ca.oidcTokenUrl)
                put("oidcClientId", ca.oidcClientId)
                put("oidcClientSecret", ca.oidcClientSecret ?: JSONObject.NULL)
                put("provisioner", ca.provisioner)
                put("defaultPrincipals", ca.defaultPrincipals)
                put("rootCertPem", ca.rootCertPem)
                put("sshHostCaPublicKey", ca.sshHostCaPublicKey ?: JSONObject.NULL)
                put("createdAt", ca.createdAt)
            })
        }
        json.put("stepCaConfigs", stepCaConfigs)

        // SSH keys (software keys only — hardware Keystore keys stay bound to the secure chip)
        val keys = JSONArray()
        sshKeyRepository.getAllDecrypted().forEach { k ->
            val storedPassphrase = if (k.passphraseEncrypted != null) {
                runCatching { sshKeyRepository.getStoredPassphrase(k.id) }.getOrNull()
            } else {
                null
            }
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
                put("biometricProtected", k.biometricProtected)
                put(
                    "certificateBytes",
                    k.certificateBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL,
                )
                put("caConfigId", k.caConfigId ?: JSONObject.NULL)
                put("certIssuedAt", k.certIssuedAt ?: JSONObject.NULL)
                put("enabledForAuth", k.enabledForAuth)
                put("storedPassphrase", storedPassphrase ?: JSONObject.NULL)
            })
        }
        json.put("keys", keys)

        // Authenticator (TOTP) secrets.
        val totp = JSONArray()
        totpSecretRepository.getAll().forEach { t ->
            totp.put(JSONObject().apply {
                put("id", t.id)
                put("label", t.label)
                put("secret", t.secret)
                put("issuer", t.issuer ?: JSONObject.NULL)
                put("accountName", t.accountName ?: JSONObject.NULL)
                put("algorithm", t.algorithm)
                put("digits", t.digits)
                put("periodSeconds", t.periodSeconds)
                put("createdAt", t.createdAt)
            })
        }
        json.put("totp", totp)

        // age X25519 file-encryption identities (decrypted secret — re-encrypted on import).
        val ageIdentities = JSONArray()
        ageIdentityRepository.getAllDecrypted().forEach { a ->
            ageIdentities.put(JSONObject().apply {
                put("id", a.id)
                put("label", a.label)
                put("recipient", a.recipient)
                put("secret", a.secret)
                put("createdAt", a.createdAt)
            })
        }
        json.put("ageIdentities", ageIdentities)

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

        // Known TLS leaf certificates (TOFU pins for VNC VeNCrypt / RDP / GLKVM)
        val knownTlsCerts = JSONArray()
        knownTlsCertDao.getAll().forEach { cert ->
            knownTlsCerts.put(JSONObject().apply {
                put("hostname", cert.hostname)
                put("port", cert.port)
                put("sha256", cert.sha256)
                put("firstSeen", cert.firstSeen)
            })
        }
        json.put("knownTlsCerts", knownTlsCerts)

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

        // Workspaces and their ordered launch items
        val workspaces = JSONArray()
        workspaceRepository.getAllWithItems().forEach { ws ->
            val itemsJson = JSONArray()
            ws.items.forEach { item ->
                itemsJson.put(JSONObject().apply {
                    put("id", item.id)
                    put("workspaceId", item.workspaceId)
                    put("kind", item.kind.name)
                    put("connectionProfileId", item.connectionProfileId ?: JSONObject.NULL)
                    put("path", item.path ?: JSONObject.NULL)
                    put("sessionName", item.sessionName ?: JSONObject.NULL)
                    put("sortOrder", item.sortOrder)
                })
            }
            workspaces.put(JSONObject().apply {
                put("id", ws.profile.id)
                put("name", ws.profile.name)
                put("sortOrder", ws.profile.sortOrder)
                put("createdAt", ws.profile.createdAt)
                put("updatedAt", ws.profile.updatedAt)
                put("items", itemsJson)
            })
        }
        json.put("workspaces", workspaces)

        // Saved rclone folder-sync profiles (#159)
        val syncProfiles = JSONArray()
        syncProfileRepository.getAll().forEach { s ->
            syncProfiles.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("srcFs", s.srcFs)
                put("dstFs", s.dstFs)
                put("mode", s.mode)
                put("includePatterns", s.includePatterns)
                put("excludePatterns", s.excludePatterns)
                put("minSize", s.minSize ?: JSONObject.NULL)
                put("maxSize", s.maxSize ?: JSONObject.NULL)
                put("bandwidthLimit", s.bandwidthLimit ?: JSONObject.NULL)
                put("createdAt", s.createdAt)
                put("lastRunAt", s.lastRunAt ?: JSONObject.NULL)
            })
        }
        json.put("syncProfiles", syncProfiles)

        // Inbound email automation rules
        val mailRules = JSONArray()
        mailRuleRepository.getAllRules().forEach { r ->
            mailRules.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("enabled", r.enabled)
                put("orderIndex", r.orderIndex)
                put("accountProfileId", r.accountProfileId ?: JSONObject.NULL)
                put("folderId", r.folderId)
                put("criteriaJson", r.criteriaJson)
                put("actionsJson", r.actionsJson)
                put("stopOnMatch", r.stopOnMatch)
                put("notifyOnFire", r.notifyOnFire)
                put("createdAt", r.createdAt)
                put("lastFiredAt", r.lastFiredAt ?: JSONObject.NULL)
            })
        }
        json.put("mailRules", mailRules)

        // Saved AI chat conversations and their messages (#807)
        val chatConversations = JSONArray()
        chatRepository.getAllConversations().forEach { conv ->
            val messagesJson = JSONArray()
            chatRepository.getMessages(conv.id).forEach { msg ->
                messagesJson.put(JSONObject().apply {
                    put("id", msg.id)
                    put("conversationId", msg.conversationId)
                    put("role", msg.role)
                    put("content", msg.content)
                    put("model", msg.model ?: JSONObject.NULL)
                    put("attachments", msg.attachments ?: JSONObject.NULL)
                    put("createdAt", msg.createdAt)
                })
            }
            chatConversations.put(JSONObject().apply {
                put("id", conv.id)
                put("profileId", conv.profileId)
                put("title", conv.title)
                put("createdAt", conv.createdAt)
                put("savedAt", conv.savedAt)
                put("updatedAt", conv.updatedAt)
                put("messages", messagesJson)
            })
        }
        json.put("chatConversations", chatConversations)

        // Settings (DataStore preferences)
        val settings = JSONObject()
        val prefs = dataStore.data.first()
        prefs.asMap().forEach { (key, value) ->
            if (key.name in DEVICE_BOUND_PREF_KEYS) return@forEach
            when (value) {
                is String -> settings.put(key.name, value)
                is Int -> settings.put(key.name, value)
                is Boolean -> settings.put(key.name, value)
                is Long -> settings.put(key.name, value)
                is Float -> settings.put(key.name, value.toDouble())
                is Double -> settings.put(key.name, value)
                is Set<*> -> {
                    val arr = JSONArray()
                    value.filterIsInstance<String>().forEach { arr.put(it) }
                    settings.put(key.name, arr)
                }
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

        // SSH identities (#360; import before groups/connections, which
        // reference identityId). Absent from pre-#360 backups — skipped.
        val identities = json.optJSONArray("identities")
        if (identities != null) {
            for (i in 0 until identities.length()) {
                try {
                    val ident = identities.getJSONObject(i)
                    sshIdentityRepository.save(
                        SshIdentity(
                            id = ident.getString("id"),
                            name = ident.getString("name"),
                            username = ident.getString("username"),
                            password = ident.optStringOrNull("password"),
                            keyId = ident.optStringOrNull("keyId"),
                            createdAt = ident.optLong("createdAt", System.currentTimeMillis()),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("Identity ${i}: ${e.message}")
                }
            }
        }

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
                            identityId = g.optStringOrNull("identityId"),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("Group ${i}: ${e.message}")
                }
            }
        }

        // step-ca configs (import before SSH keys, which reference caConfigId)
        val stepCaConfigs = json.optJSONArray("stepCaConfigs")
        if (stepCaConfigs != null) {
            for (i in 0 until stepCaConfigs.length()) {
                try {
                    val ca = stepCaConfigs.getJSONObject(i)
                    stepCaConfigRepository.save(
                        StepCaConfig(
                            id = ca.getString("id"),
                            name = ca.getString("name"),
                            caUrl = ca.getString("caUrl"),
                            oidcIssuer = ca.getString("oidcIssuer"),
                            oidcAuthUrl = ca.getString("oidcAuthUrl"),
                            oidcTokenUrl = ca.getString("oidcTokenUrl"),
                            oidcClientId = ca.getString("oidcClientId"),
                            oidcClientSecret = ca.optStringOrNull("oidcClientSecret"),
                            provisioner = ca.getString("provisioner"),
                            defaultPrincipals = ca.optString("defaultPrincipals", ""),
                            rootCertPem = ca.getString("rootCertPem"),
                            sshHostCaPublicKey = ca.optStringOrNull("sshHostCaPublicKey"),
                            createdAt = ca.optLong("createdAt", System.currentTimeMillis()),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("StepCaConfig ${i}: ${e.message}")
                }
            }
        }

        // SSH keys (import first since connections reference keyId)
        val keys = json.optJSONArray("keys")
        if (keys != null) {
            for (i in 0 until keys.length()) {
                try {
                    val k = keys.getJSONObject(i)
                    val keyId = k.getString("id")
                    sshKeyRepository.save(
                        SshKey(
                            id = keyId,
                            label = k.getString("label"),
                            keyType = k.getString("keyType"),
                            privateKeyBytes = Base64.decode(k.getString("privateKeyBytes"), Base64.NO_WRAP),
                            publicKeyOpenSsh = k.getString("publicKeyOpenSsh"),
                            fingerprintSha256 = k.getString("fingerprintSha256"),
                            createdAt = k.getLong("createdAt"),
                            isEncrypted = k.optBoolean("isEncrypted", false),
                            biometricProtected = k.optBoolean("biometricProtected", false),
                            certificateBytes = k.optStringOrNull("certificateBytes")
                                ?.let { Base64.decode(it, Base64.NO_WRAP) },
                            caConfigId = k.optStringOrNull("caConfigId"),
                            certIssuedAt = k.optLongOrNull("certIssuedAt"),
                            enabledForAuth = k.optBoolean("enabledForAuth", true),
                        ),
                    )
                    k.optStringOrNull("storedPassphrase")?.let { passphrase ->
                        sshKeyRepository.setStoredPassphrase(keyId, passphrase)
                    }
                    count++
                } catch (e: Exception) {
                    errors.add("Key ${i}: ${e.message}")
                }
            }
        }

        // Authenticator (TOTP) secrets. Absent from backups written before
        // v5.87.x, so optJSONArray and skip rather than fail — restoring an
        // older backup is the common case, not an error.
        val totp = json.optJSONArray("totp")
        if (totp != null) {
            for (i in 0 until totp.length()) {
                try {
                    val t = totp.getJSONObject(i)
                    totpSecretRepository.save(
                        TotpSecret(
                            id = t.getString("id"),
                            label = t.getString("label"),
                            secret = t.getString("secret"),
                            issuer = t.optString("issuer").takeIf { it.isNotEmpty() },
                            accountName = t.optString("accountName").takeIf { it.isNotEmpty() },
                            algorithm = t.optString("algorithm", "SHA1"),
                            digits = t.optInt("digits", 6),
                            periodSeconds = t.optInt("periodSeconds", 30),
                            createdAt = t.optLong("createdAt", System.currentTimeMillis()),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("TOTP ${i}: ${e.message}")
                }
            }
        }

        // age X25519 file-encryption identities
        val ageIdentities = json.optJSONArray("ageIdentities")
        if (ageIdentities != null) {
            for (i in 0 until ageIdentities.length()) {
                try {
                    val a = ageIdentities.getJSONObject(i)
                    ageIdentityRepository.savePlaintext(
                        AgeIdentityEntity(
                            id = a.getString("id"),
                            label = a.getString("label"),
                            recipient = a.getString("recipient"),
                            secret = a.getString("secret"),
                            createdAt = a.optLong("createdAt", System.currentTimeMillis()),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("AgeIdentity ${i}: ${e.message}")
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
                            ownerProfileId = t.optStringOrNull("ownerProfileId"),
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
                            safTreeUri = c.optStringOrNull("safTreeUri"),
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
                            vncSshProfileId = c.optStringOrNull("vncSshProfileId"),
                            vncColorDepth = c.optString("vncColorDepth", "BPP_24_TRUE"),
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
                            rdpUseNla = c.optBoolean("rdpUseNla", true),
                            rdpColorDepth = c.optInt("rdpColorDepth", 32),
                            smbPort = c.optInt("smbPort", 445),
                            smbShare = c.optStringOrNull("smbShare"),
                            smbDomain = c.optStringOrNull("smbDomain"),
                            smbPassword = c.optStringOrNull("smbPassword"),
                            smbSshForward = c.optBoolean("smbSshForward", false),
                            smbSshProfileId = c.optStringOrNull("smbSshProfileId"),
                            proxyType = c.optStringOrNull("proxyType"),
                            proxyHost = c.optStringOrNull("proxyHost"),
                            proxyPort = c.optInt("proxyPort", 1080),
                            proxyUser = c.optStringOrNull("proxyUser"),
                            proxyPassword = c.optStringOrNull("proxyPassword"),
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
                            localShellMode = c.optStringOrNull("localShellMode")?.takeIf { raw ->
                                ConnectionProfile.LocalShellMode.entries.any { it.name == raw }
                            },
                            prootDistroId = c.optStringOrNull("prootDistroId"),
                            forwardAgent = c.optBoolean("forwardAgent", false),
                            addressFamily = c.optString("addressFamily", "AUTO"),
                            bindAddress = c.optStringOrNull("bindAddress"),
                            reticulumNetworkName = c.optStringOrNull("reticulumNetworkName"),
                            reticulumPassphrase = c.optStringOrNull("reticulumPassphrase"),
                            postLoginCommand = c.optStringOrNull("postLoginCommand"),
                            postLoginBeforeSessionManager = c.optBoolean("postLoginBeforeSessionManager", true),
                            remoteCommand = c.optStringOrNull("remoteCommand"),
                            requestPty = c.optBoolean("requestPty", true),
                            usbForwardVidPid = c.optStringOrNull("usbForwardVidPid"),
                            fileTransport = c.optString("fileTransport", "AUTO"),
                            tunnelConfigId = c.optStringOrNull("tunnelConfigId"),
                            terminalColorScheme = c.optStringOrNull("terminalColorScheme"),
                            terminalBackgroundOpacity =
                                if (c.has("terminalBackgroundOpacity") && !c.isNull("terminalBackgroundOpacity")) {
                                    c.optDouble("terminalBackgroundOpacity").toFloat()
                                } else {
                                    null
                                },
                            autoReconnect = c.optBoolean("autoReconnect", true),
                            reconnectMaxAttempts = c.optInt("reconnectMaxAttempts", 5),
                            reconnectOnNetworkChange = c.optBoolean("reconnectOnNetworkChange", true),
                            tunnelOnly = c.optBoolean("tunnelOnly", false),
                            mcpEnabled = c.optBoolean("mcpEnabled", true),
                            portKnockSequence = c.optStringOrNull("portKnockSequence"),
                            portKnockDelayMs = c.optInt("portKnockDelayMs", 100),
                            spaKey = c.optStringOrNull("spaKey"),
                            spaKeyBase64 = c.optBoolean("spaKeyBase64", false),
                            spaHmacKey = c.optStringOrNull("spaHmacKey"),
                            spaHmacKeyBase64 = c.optBoolean("spaHmacKeyBase64", false),
                            spaAccessSpec = c.optStringOrNull("spaAccessSpec"),
                            spaAllowMode = c.optString("spaAllowMode", "SOURCE"),
                            spaExplicitIp = c.optStringOrNull("spaExplicitIp"),
                            spaPort = c.optInt("spaPort", 62201),
                            identityId = c.optStringOrNull("identityId"),
                            authMethods = c.optString("authMethods", ""),
                            totpConfirmBeforeSend = c.optBoolean("totpConfirmBeforeSend", false),
                            ignoreSavedKeys = c.optBoolean("ignoreSavedKeys", false),
                            spicePort = c.optIntOrNull("spicePort"),
                            spicePassword = c.optStringOrNull("spicePassword"),
                            spiceSshForward = c.optBoolean("spiceSshForward", false),
                            spiceSshProfileId = c.optStringOrNull("spiceSshProfileId"),
                            keyboardLayout = c.optStringOrNull("keyboardLayout"),
                            glkvmPort = c.optIntOrNull("glkvmPort"),
                            glkvmPassword = c.optStringOrNull("glkvmPassword"),
                            glkvmTargetLayout = c.optStringOrNull("glkvmTargetLayout"),
                            glkvmSshForward = c.optBoolean("glkvmSshForward", false),
                            glkvmSshProfileId = c.optStringOrNull("glkvmSshProfileId"),
                            emailProvider = c.optStringOrNull("emailProvider"),
                            emailServer = c.optStringOrNull("emailServer"),
                            emailSmtpServer = c.optStringOrNull("emailSmtpServer"),
                            emailPort = c.optInt("emailPort", 993),
                            emailSmtpPort = c.optInt("emailSmtpPort", 465),
                            emailTls = c.optBoolean("emailTls", true),
                            emailUsername = c.optStringOrNull("emailUsername"),
                            emailPassword = c.optStringOrNull("emailPassword"),
                            emailMailboxPassword = c.optStringOrNull("emailMailboxPassword"),
                            emailAuthMethods = c.optString("emailAuthMethods", ""),
                            openaiPathPrefix = c.optStringOrNull("openaiPathPrefix"),
                            openaiApiKey = c.optStringOrNull("openaiApiKey"),
                            aiProtocol = c.optStringOrNull("aiProtocol"),
                            aiRouteType = c.optStringOrNull("aiRouteType"),
                            aiRouteProfileId = c.optStringOrNull("aiRouteProfileId"),
                            sessionCommandOverride = c.optStringOrNull("sessionCommandOverride"),
                            usbDriveSerial = c.optStringOrNull("usbDriveSerial"),
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

        // Known TLS leaf certificates
        val knownTlsCerts = json.optJSONArray("knownTlsCerts")
        if (knownTlsCerts != null) {
            for (i in 0 until knownTlsCerts.length()) {
                try {
                    val cert = knownTlsCerts.getJSONObject(i)
                    val hostname = cert.getString("hostname")
                    val port = cert.getInt("port")
                    knownTlsCertDao.deleteByHostPort(hostname, port)
                    knownTlsCertDao.upsert(
                        KnownTlsCert(
                            hostname = hostname,
                            port = port,
                            sha256 = cert.getString("sha256"),
                            firstSeen = cert.optLong("firstSeen", System.currentTimeMillis()),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("KnownTlsCert ${i}: ${e.message}")
                }
            }
        }

        // Port forward rules (after connections because of FK to connection_profiles)
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

        // Workspaces and items (after connections because WorkspaceItem.connectionProfileId FKs connection_profiles)
        val workspaces = json.optJSONArray("workspaces")
        if (workspaces != null) {
            for (i in 0 until workspaces.length()) {
                try {
                    val ws = workspaces.getJSONObject(i)
                    val wsId = ws.getString("id")
                    val profile = WorkspaceProfile(
                        id = wsId,
                        name = ws.getString("name"),
                        sortOrder = ws.optInt("sortOrder", 0),
                        createdAt = ws.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = ws.optLong("updatedAt", System.currentTimeMillis()),
                    )
                    val itemsJson = ws.optJSONArray("items")
                    val items = buildList {
                        if (itemsJson != null) {
                            for (j in 0 until itemsJson.length()) {
                                val item = itemsJson.getJSONObject(j)
                                add(
                                    WorkspaceItem(
                                        id = item.getString("id"),
                                        workspaceId = item.optString("workspaceId", wsId),
                                        kind = WorkspaceItem.Kind.valueOf(item.getString("kind")),
                                        connectionProfileId = item.optStringOrNull("connectionProfileId"),
                                        path = item.optStringOrNull("path"),
                                        sessionName = item.optStringOrNull("sessionName"),
                                        sortOrder = item.optInt("sortOrder", j),
                                    ),
                                )
                            }
                        }
                    }
                    workspaceRepository.restoreFromBackup(profile, items)
                    count++
                } catch (e: Exception) {
                    errors.add("Workspace ${i}: ${e.message}")
                }
            }
        }

        // Saved rclone sync profiles (#159)
        val syncProfiles = json.optJSONArray("syncProfiles")
        if (syncProfiles != null) {
            for (i in 0 until syncProfiles.length()) {
                try {
                    val s = syncProfiles.getJSONObject(i)
                    syncProfileRepository.save(
                        SyncProfile(
                            id = s.getString("id"),
                            name = s.getString("name"),
                            srcFs = s.getString("srcFs"),
                            dstFs = s.getString("dstFs"),
                            mode = s.getString("mode"),
                            includePatterns = s.optString("includePatterns", ""),
                            excludePatterns = s.optString("excludePatterns", ""),
                            minSize = s.optStringOrNull("minSize"),
                            maxSize = s.optStringOrNull("maxSize"),
                            bandwidthLimit = s.optStringOrNull("bandwidthLimit"),
                            createdAt = s.optLong("createdAt", System.currentTimeMillis()),
                            lastRunAt = s.optLongOrNull("lastRunAt"),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("SyncProfile ${i}: ${e.message}")
                }
            }
        }

        // Inbound email automation rules
        val mailRules = json.optJSONArray("mailRules")
        if (mailRules != null) {
            for (i in 0 until mailRules.length()) {
                try {
                    val r = mailRules.getJSONObject(i)
                    mailRuleRepository.saveRule(
                        MailRule(
                            id = r.getString("id"),
                            name = r.getString("name"),
                            enabled = r.optBoolean("enabled", true),
                            orderIndex = r.optInt("orderIndex", 0),
                            accountProfileId = r.optStringOrNull("accountProfileId"),
                            folderId = r.optString("folderId", "INBOX"),
                            criteriaJson = r.getString("criteriaJson"),
                            actionsJson = r.getString("actionsJson"),
                            stopOnMatch = r.optBoolean("stopOnMatch", false),
                            notifyOnFire = r.optBoolean("notifyOnFire", false),
                            createdAt = r.optLong("createdAt", System.currentTimeMillis()),
                            lastFiredAt = r.optLongOrNull("lastFiredAt"),
                        ),
                    )
                    count++
                } catch (e: Exception) {
                    errors.add("MailRule ${i}: ${e.message}")
                }
            }
        }

        // Saved AI chat conversations and messages (#807)
        val chatConversations = json.optJSONArray("chatConversations")
        if (chatConversations != null) {
            for (i in 0 until chatConversations.length()) {
                try {
                    val conv = chatConversations.getJSONObject(i)
                    val convId = conv.getString("id")
                    chatRepository.saveConversation(
                        ChatConversation(
                            id = convId,
                            profileId = conv.getString("profileId"),
                            title = conv.getString("title"),
                            createdAt = conv.optLong("createdAt", System.currentTimeMillis()),
                            savedAt = conv.optLong("savedAt", System.currentTimeMillis()),
                            updatedAt = conv.optLong("updatedAt", System.currentTimeMillis()),
                        ),
                    )
                    count++
                    val messages = conv.optJSONArray("messages")
                    if (messages != null) {
                        for (j in 0 until messages.length()) {
                            val m = messages.getJSONObject(j)
                            chatRepository.saveMessage(
                                ChatMessage(
                                    id = m.getString("id"),
                                    conversationId = m.optString("conversationId", convId),
                                    role = m.getString("role"),
                                    content = m.getString("content"),
                                    model = m.optStringOrNull("model"),
                                    attachments = m.optStringOrNull("attachments"),
                                    createdAt = m.optLong("createdAt", System.currentTimeMillis()),
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    errors.add("ChatConversation ${i}: ${e.message}")
                }
            }
        }

        // Settings
        val settings = json.optJSONObject("settings")
        if (settings != null) {
            dataStore.updateData { prefs ->
                val mutable = prefs.toMutablePreferences()
                settings.keys().forEach { key ->
                    if (key in DEVICE_BOUND_PREF_KEYS) return@forEach
                    val value = settings.get(key)
                    when {
                        // Whole-valued floats (1.0f, 2.0f) serialize via Android's
                        // JSONObject as integer tokens (`1`, `2`) and parse back as
                        // java.lang.Integer (#323, #779). Route known float and long
                        // preference keys by name first so they are never stored as Int.
                        key in sh.haven.core.data.preferences.UserPreferencesRepository.FLOAT_PREF_KEYS &&
                            value is Number ->
                            mutable[androidx.datastore.preferences.core.floatPreferencesKey(key)] =
                                value.toFloat()
                        key in sh.haven.core.data.preferences.UserPreferencesRepository.LONG_PREF_KEYS &&
                            value is Number ->
                            mutable[androidx.datastore.preferences.core.longPreferencesKey(key)] =
                                value.toLong()
                        value is JSONArray -> {
                            val set = buildSet {
                                for (idx in 0 until value.length()) {
                                    if (!value.isNull(idx)) add(value.getString(idx))
                                }
                            }
                            mutable[androidx.datastore.preferences.core.stringSetPreferencesKey(key)] = set
                        }
                        value is String ->
                            mutable[androidx.datastore.preferences.core.stringPreferencesKey(key)] = value
                        value is Boolean ->
                            mutable[androidx.datastore.preferences.core.booleanPreferencesKey(key)] = value
                        value is Int ->
                            mutable[androidx.datastore.preferences.core.intPreferencesKey(key)] = value
                        value is Long ->
                            mutable[androidx.datastore.preferences.core.longPreferencesKey(key)] = value
                        // DataStore has no Double type: every JSON double here was
                        // exported from a Float pref (export does Float.toDouble()),
                        // so it MUST go back under a floatPreferencesKey.
                        value is Double ->
                            mutable[androidx.datastore.preferences.core.floatPreferencesKey(key)] =
                                value.toFloat()
                        value is Float ->
                            mutable[androidx.datastore.preferences.core.floatPreferencesKey(key)] = value
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
        val key = deriveKey(password, salt, PBKDF2_ITERATIONS)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        // Format: MAGIC + salt + iv + ciphertext. The magic doubles as the KDF
        // version: MAGIC_V2 files use 600k PBKDF2 iterations, legacy MAGIC files
        // use 100k. Both magics are the same length, so offsets are unchanged.
        return MAGIC_V2.toByteArray(Charsets.US_ASCII) + salt + iv + ciphertext
    }

    private fun decrypt(data: ByteArray, password: String): ByteArray {
        val magic = String(data, 0, MAGIC_V2.length, Charsets.US_ASCII)
        val iterations = when (magic) {
            MAGIC_V2 -> PBKDF2_ITERATIONS
            MAGIC_LEGACY -> PBKDF2_ITERATIONS_LEGACY
            else -> throw IllegalArgumentException("Not a Haven backup file")
        }
        var offset = magic.length
        val salt = data.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = data.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
        val ciphertext = data.copyOfRange(offset, data.size)
        val key = deriveKey(password, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
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
        // KDF envelope version, encoded in the magic header. MAGIC_LEGACY files
        // used 100k PBKDF2 iterations; MAGIC_V2 files use 600k (OWASP 2023) —
        // the backup holds decrypted passwords and SSH keys. Old files still
        // import (decrypt reads the magic to pick the iteration count).
        private const val MAGIC_LEGACY = "HAVEN_BACKUP_V1"
        private const val MAGIC_V2 = "HAVEN_BACKUP_V2"
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val PBKDF2_ITERATIONS = 600_000
        private const val PBKDF2_ITERATIONS_LEGACY = 100_000

        /**
         * Preferences wrapped with the local Android Keystore master key rather
         * than stored as portable values. Exporting raw `ENC:...` ciphertext
         * onto another device produces permanently unreadable preferences and
         * overwrites any working auto-sync passphrase already configured there.
         */
        internal val DEVICE_BOUND_PREF_KEYS: Set<String> = setOf(
            "backup_sync_passphrase",
            "tasker_plugin_secret",
        )

        /**
         * Authoritative partition of [sh.haven.core.data.db.HavenDatabase] entities
         * backed up by [export] / [import]. Locked against `@Database(entities = ...)`
         * by `BackupServiceTest` so a newly added Room table cannot be omitted silently.
         */
        internal val BACKED_UP_ENTITIES: Set<Class<*>> = setOf(
            ConnectionProfile::class.java,
            ConnectionGroup::class.java,
            KnownHost::class.java,
            KnownTlsCert::class.java,
            SshKey::class.java,
            PortForwardRule::class.java,
            TunnelConfig::class.java,
            WorkspaceProfile::class.java,
            WorkspaceItem::class.java,
            StepCaConfig::class.java,
            SyncProfile::class.java,
            TotpSecret::class.java,
            MailRule::class.java,
            AgeIdentityEntity::class.java,
            SshIdentity::class.java,
            ChatConversation::class.java,
            ChatMessage::class.java,
        )

        /**
         * Room tables intentionally excluded from portable backups:
         * - Runtime/audit logs and transfer queues ([ConnectionLog], [AgentAuditEvent],
         *   [PasteQueueEntry], [ProotInstallLog], [MailWatermark], [MailRuleFiring],
         *   [MailRulePendingAction]).
         * - [StandingPolicy]: expiring Tier-3 MCP consent grants require an interactive
         *   consent-sheet approval on the target device and must never be silently
         *   installed by importing a backup file.
         */
        internal val TRANSIENT_OR_DEVICE_SCOPED_ENTITIES: Set<Class<*>> = setOf(
            ConnectionLog::class.java,
            AgentAuditEvent::class.java,
            PasteQueueEntry::class.java,
            ProotInstallLog::class.java,
            MailWatermark::class.java,
            MailRuleFiring::class.java,
            MailRulePendingAction::class.java,
            StandingPolicy::class.java,
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    return if (isNull(key)) null else optString(key)
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    return if (isNull(key)) null else optLong(key)
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    return if (isNull(key)) null else optInt(key)
}
