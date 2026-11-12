package sh.haven.app.agent

import sh.haven.core.mcp.McpError
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.desktop.DesktopInputHandle
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.SnippetOps
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.ffmpeg.TranscodeCommand
import sh.haven.core.local.WaylandSocketHelper
import sh.haven.core.local.proot.GuestAppCatalog
import sh.haven.core.local.proot.buildPackInstallScript
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.security.posixShellQuote
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool implementations for the MCP agent transport.
 *
 * Every tool:
 * 1. Has a name, a human description, and a JSON Schema for its
 *    arguments, discoverable via `tools/list`.
 * 2. Runs its work on the same repositories and session managers
 *    the UI uses, so "what the agent sees" and "what the user sees"
 *    come from the same source of truth.
 * 3. Returns a JSON object that both serialises to text (for MCP's
 *    `content[].text` field) and is structured (for
 *    `structuredContent`).
 *
 * v1 is read-only. Mutating tools (convert, upload, disconnect,
 * delete, add-port-forward, ...) will ship in v2 behind a per-action
 * consent mechanism that lives in the UI.
 */
@Singleton
class McpTools private constructor(
    injectedProviders: Set<ToolProvider>,
    private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val sshSessionManager: SshSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val mailSessionManager: sh.haven.core.mail.MailSessionManager,
    private val sftpStreamServer: SftpStreamServer,
    private val hlsStreamServer: HlsStreamServer,
    private val ffmpegExecutor: FfmpegExecutor,
    private val preferencesRepository: UserPreferencesRepository,
    private val terminalFontInstaller: TerminalFontInstaller,
    private val localSessionManager: LocalSessionManager,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
    private val connectionLogRepository: sh.haven.core.data.repository.ConnectionLogRepository,
    private val servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
    private val mailRuleRepository: sh.haven.core.data.repository.MailRuleRepository,
    private val mailWatchManager: sh.haven.app.agent.mailrules.MailWatchManager,
    private val agentActivityHolder: sh.haven.core.data.agent.AgentActivityHolder,
    private val terminalInputQueue: TerminalInputQueue,
    private val prootInstallLogRepository: sh.haven.core.data.repository.ProotInstallLogRepository,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val usbBroker: sh.haven.core.usb.UsbBroker,
    private val usbIpServer: sh.haven.core.usb.UsbIpServer,
    private val usbDriveVmManager: sh.haven.app.usb.UsbDriveVmManager,
    private val usbProxyServer: sh.haven.core.usb.UsbProxyServer,
    private val usbRedirServer: sh.haven.core.usb.UsbRedirServer,
    private val presentationManager: sh.haven.core.data.agent.AgentPresentationManager,
    private val havenUiBridge: HavenUiBridge,
    private val standingPolicyRepository: sh.haven.core.data.repository.StandingPolicyRepository,
    private val mcpTunnelManager: McpTunnelManager,
    private val mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder,
    openAiSessionManager: sh.haven.core.openai.OpenAiSessionManager?,
    tunnelResolver: sh.haven.core.tunnel.TunnelResolver?,
    private val toolCatalog: sh.haven.core.data.agent.McpToolCatalog?,
    private val agentConsentManager: sh.haven.core.data.agent.AgentConsentManager,
    internal var mcpPortProvider: () -> Int,
    private val updateChecker: sh.haven.core.data.update.UpdateChecker,
    textRecognizer: sh.haven.core.scan.TextRecognizer?,
) {

    @Inject
    constructor(
        injectedProviders: Set<@JvmSuppressWildcards ToolProvider>,
        @ApplicationContext context: Context,
        connectionRepository: ConnectionRepository,
        sshSessionManager: SshSessionManager,
        sessionManagerRegistry: SessionManagerRegistry,
        mailSessionManager: sh.haven.core.mail.MailSessionManager,
        sftpStreamServer: SftpStreamServer,
        hlsStreamServer: HlsStreamServer,
        ffmpegExecutor: FfmpegExecutor,
        preferencesRepository: UserPreferencesRepository,
        terminalFontInstaller: TerminalFontInstaller,
        localSessionManager: LocalSessionManager,
        agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
        transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
        terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
        connectionLogRepository: sh.haven.core.data.repository.ConnectionLogRepository,
        servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
        mailRuleRepository: sh.haven.core.data.repository.MailRuleRepository,
        mailWatchManager: sh.haven.app.agent.mailrules.MailWatchManager,
        agentActivityHolder: sh.haven.core.data.agent.AgentActivityHolder,
        terminalInputQueue: TerminalInputQueue,
        prootInstallLogRepository: sh.haven.core.data.repository.ProotInstallLogRepository,
        desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
        usbBroker: sh.haven.core.usb.UsbBroker,
        usbIpServer: sh.haven.core.usb.UsbIpServer,
        usbDriveVmManager: sh.haven.app.usb.UsbDriveVmManager,
        usbProxyServer: sh.haven.core.usb.UsbProxyServer,
        usbRedirServer: sh.haven.core.usb.UsbRedirServer,
        presentationManager: sh.haven.core.data.agent.AgentPresentationManager,
        havenUiBridge: HavenUiBridge,
        standingPolicyRepository: sh.haven.core.data.repository.StandingPolicyRepository,
        mcpTunnelManager: McpTunnelManager,
        mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder,
        openAiSessionManager: sh.haven.core.openai.OpenAiSessionManager,
        tunnelResolver: sh.haven.core.tunnel.TunnelResolver,
        toolCatalog: sh.haven.core.data.agent.McpToolCatalog,
        agentConsentManager: sh.haven.core.data.agent.AgentConsentManager,
        updateChecker: sh.haven.core.data.update.UpdateChecker,
        textRecognizer: sh.haven.core.scan.TextRecognizer,
    ) : this(
        injectedProviders = injectedProviders,
        context = context,
        connectionRepository = connectionRepository,
        sshSessionManager = sshSessionManager,
        sessionManagerRegistry = sessionManagerRegistry,
        mailSessionManager = mailSessionManager,
        sftpStreamServer = sftpStreamServer,
        hlsStreamServer = hlsStreamServer,
        ffmpegExecutor = ffmpegExecutor,
        preferencesRepository = preferencesRepository,
        terminalFontInstaller = terminalFontInstaller,
        localSessionManager = localSessionManager,
        agentUiCommandBus = agentUiCommandBus,
        transportSelector = transportSelector,
        terminalSessionRegistry = terminalSessionRegistry,
        connectionLogRepository = connectionLogRepository,
        servedFileTracker = servedFileTracker,
        mailRuleRepository = mailRuleRepository,
        mailWatchManager = mailWatchManager,
        agentActivityHolder = agentActivityHolder,
        terminalInputQueue = terminalInputQueue,
        prootInstallLogRepository = prootInstallLogRepository,
        desktopSessionRegistry = desktopSessionRegistry,
        usbBroker = usbBroker,
        usbIpServer = usbIpServer,
        usbDriveVmManager = usbDriveVmManager,
        usbProxyServer = usbProxyServer,
        usbRedirServer = usbRedirServer,
        presentationManager = presentationManager,
        havenUiBridge = havenUiBridge,
        standingPolicyRepository = standingPolicyRepository,
        mcpTunnelManager = mcpTunnelManager,
        mcpStatusHolder = mcpStatusHolder,
        openAiSessionManager = openAiSessionManager,
        tunnelResolver = tunnelResolver,
        toolCatalog = toolCatalog,
        agentConsentManager = agentConsentManager,
        mcpPortProvider = { MCP_REVERSE_TUNNEL_PORT },
        updateChecker = updateChecker,
        textRecognizer = textRecognizer,
    )

    internal constructor(
        context: Context,
        connectionRepository: ConnectionRepository,
        portForwardRepository: PortForwardRepository,
        sshSessionManager: SshSessionManager,
        sessionManagerRegistry: SessionManagerRegistry,
        reticulumSessionManager: sh.haven.core.reticulum.ReticulumSessionManager,
        reticulumForwardServer: sh.haven.core.reticulum.ReticulumForwardServer,
        rcloneClient: RcloneClient,
        mailSessionManager: sh.haven.core.mail.MailSessionManager,
        sftpStreamServer: SftpStreamServer,
        hlsStreamServer: HlsStreamServer,
        ffmpegExecutor: FfmpegExecutor,
        preferencesRepository: UserPreferencesRepository,
        terminalFontInstaller: TerminalFontInstaller,
        localSessionManager: LocalSessionManager,
        agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
        transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
        workspaceRepository: sh.haven.core.data.repository.WorkspaceRepository,
        workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher,
        tunnelConfigRepository: sh.haven.core.data.repository.TunnelConfigRepository,
        tunnelManager: sh.haven.core.tunnel.TunnelManager,
        terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
        portKnocker: sh.haven.core.knock.PortKnocker,
        spaSender: sh.haven.core.spa.SpaSender,
        connectionLogRepository: sh.haven.core.data.repository.ConnectionLogRepository,
        servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
        syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository,
        mailRuleRepository: sh.haven.core.data.repository.MailRuleRepository,
        mailWatchManager: sh.haven.app.agent.mailrules.MailWatchManager,
        agentActivityHolder: sh.haven.core.data.agent.AgentActivityHolder,
        terminalInputQueue: TerminalInputQueue,
        prootInstallLogRepository: sh.haven.core.data.repository.ProotInstallLogRepository,
        sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
        knownHostDao: sh.haven.core.data.db.KnownHostDao,
        stepCaConfigRepository: sh.haven.core.data.repository.StepCaConfigRepository,
        totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
        ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository,
        desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
        aiRouteRegistry: sh.haven.core.openai.AiRouteRegistry,
        usbBroker: sh.haven.core.usb.UsbBroker,
        usbIpServer: sh.haven.core.usb.UsbIpServer,
        usbDriveVmManager: sh.haven.app.usb.UsbDriveVmManager,
        umlRecoveryManager: sh.haven.app.usb.UmlRecoveryManager,
        presentationManager: sh.haven.core.data.agent.AgentPresentationManager,
        havenUiBridge: HavenUiBridge,
        standingPolicyRepository: sh.haven.core.data.repository.StandingPolicyRepository,
        mcpTunnelManager: McpTunnelManager,
        mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder,
        btSerialSessionManager: sh.haven.core.btserial.BtSerialSessionManager? = null,
        bleSerialSessionManager: sh.haven.core.bleserial.BleSerialSessionManager? = null,
        usbSerialSessionManager: sh.haven.core.usbserial.UsbSerialSessionManager? = null,
        openAiSessionManager: sh.haven.core.openai.OpenAiSessionManager? = null,
        tunnelResolver: sh.haven.core.tunnel.TunnelResolver? = null,
        headlessSshExec: HeadlessSshExec? = null,
        hardwareKeySource: sh.haven.core.security.HardwareKeySource? = null,
        biometricAuthenticator: sh.haven.core.security.BiometricAuthenticator? = null,
        pendingAuthPromptHolder: sh.haven.core.data.agent.PendingAuthPromptHolder =
            sh.haven.core.data.agent.PendingAuthPromptHolder(),
        toolCatalog: sh.haven.core.data.agent.McpToolCatalog? = null,
        sessionSelectionHolder: sh.haven.core.data.agent.SessionSelectionHolder =
            sh.haven.core.data.agent.SessionSelectionHolder(),
        agentConsentManager: sh.haven.core.data.agent.AgentConsentManager =
            sh.haven.core.data.agent.AgentConsentManager(),
        mcpPortProvider: () -> Int = { MCP_REVERSE_TUNNEL_PORT },
        updateChecker: sh.haven.core.data.update.UpdateChecker =
            sh.haven.core.data.update.UpdateChecker(context, preferencesRepository),
        textRecognizer: sh.haven.core.scan.TextRecognizer? = null,
        usbRedirServer: sh.haven.core.usb.UsbRedirServer =
            sh.haven.core.usb.UsbRedirServer(usbBroker),
        usbProxyServer: sh.haven.core.usb.UsbProxyServer =
            sh.haven.core.usb.UsbProxyServer(usbBroker),
        extraProviders: Set<ToolProvider> = emptySet(),
    ) : this(
        injectedProviders = linkedSetOf<ToolProvider>(
            KeyStoreToolProvider(
                totpSecretRepository = totpSecretRepository,
                ageIdentityRepository = ageIdentityRepository,
                agentUiCommandBus = agentUiCommandBus,
            ),
            TunnelToolProvider(
                tunnelConfigRepository = tunnelConfigRepository,
                tunnelManager = tunnelManager,
            ),
            SshKeyToolProvider(sshKeyRepository = sshKeyRepository),
            HostKeyToolProvider(knownHostDao = knownHostDao),
            StepCaToolProvider(stepCaConfigRepository = stepCaConfigRepository),
            RcloneToolProvider(
                rcloneClient = rcloneClient,
                syncProfileRepository = syncProfileRepository,
                connectionRepository = connectionRepository,
                sessionManagerRegistry = sessionManagerRegistry,
            ),
            UsbToolProvider(
                usbBroker = usbBroker,
                usbIpServer = usbIpServer,
                usbDriveVmManager = usbDriveVmManager,
                umlRecoveryManager = umlRecoveryManager,
                usbProxyServer = usbProxyServer,
                preferencesRepository = preferencesRepository,
                localSessionManager = localSessionManager,
                usbRedirServer = usbRedirServer,
            ),
            SerialBridgeToolProvider(
                btSerial = btSerialSessionManager,
                bleSerial = bleSerialSessionManager,
                usbSerial = usbSerialSessionManager,
            ),
            CrossProtocolToolProvider(
                sessionManagerRegistry = sessionManagerRegistry,
                workspaceRepository = workspaceRepository,
                transportSelector = transportSelector,
            ),
            CredentialToolProvider(
                sshKeyRepository = sshKeyRepository,
                headlessSshExec = headlessSshExec,
                hardwareKeys = hardwareKeySource,
                biometricAuthenticator = biometricAuthenticator,
            ),
            WorkspaceAndSnippetToolProvider(
                workspaceRepository = workspaceRepository,
                workspaceLauncher = workspaceLauncher,
                preferencesRepository = preferencesRepository,
            ),
            ConnectionsToolProvider(
                context = context,
                connectionRepository = connectionRepository,
                portForwardRepository = portForwardRepository,
                sshSessionManager = sshSessionManager,
                sessionManagerRegistry = sessionManagerRegistry,
                reticulumSessionManager = reticulumSessionManager,
                reticulumForwardServer = reticulumForwardServer,
                agentUiCommandBus = agentUiCommandBus,
                tunnelConfigRepository = tunnelConfigRepository,
                tunnelManager = tunnelManager,
                terminalSessionRegistry = terminalSessionRegistry,
                portKnocker = portKnocker,
                spaSender = spaSender,
                connectionLogRepository = connectionLogRepository,
                sshKeyRepository = sshKeyRepository,
                desktopSessionRegistry = desktopSessionRegistry,
                aiRouteRegistry = aiRouteRegistry,
                headlessSshExec = headlessSshExec,
                pendingAuthPromptHolder = pendingAuthPromptHolder,
                sessionSelectionHolder = sessionSelectionHolder,
            ),
        ) + extraProviders,
        context = context,
        connectionRepository = connectionRepository,
        sshSessionManager = sshSessionManager,
        sessionManagerRegistry = sessionManagerRegistry,
        mailSessionManager = mailSessionManager,
        sftpStreamServer = sftpStreamServer,
        hlsStreamServer = hlsStreamServer,
        ffmpegExecutor = ffmpegExecutor,
        preferencesRepository = preferencesRepository,
        terminalFontInstaller = terminalFontInstaller,
        localSessionManager = localSessionManager,
        agentUiCommandBus = agentUiCommandBus,
        transportSelector = transportSelector,
        terminalSessionRegistry = terminalSessionRegistry,
        connectionLogRepository = connectionLogRepository,
        servedFileTracker = servedFileTracker,
        mailRuleRepository = mailRuleRepository,
        mailWatchManager = mailWatchManager,
        agentActivityHolder = agentActivityHolder,
        terminalInputQueue = terminalInputQueue,
        prootInstallLogRepository = prootInstallLogRepository,
        desktopSessionRegistry = desktopSessionRegistry,
        usbBroker = usbBroker,
        usbIpServer = usbIpServer,
        usbDriveVmManager = usbDriveVmManager,
        usbProxyServer = usbProxyServer,
        usbRedirServer = usbRedirServer,
        presentationManager = presentationManager,
        havenUiBridge = havenUiBridge,
        standingPolicyRepository = standingPolicyRepository,
        mcpTunnelManager = mcpTunnelManager,
        mcpStatusHolder = mcpStatusHolder,
        openAiSessionManager = openAiSessionManager,
        tunnelResolver = tunnelResolver,
        toolCatalog = toolCatalog,
        agentConsentManager = agentConsentManager,
        mcpPortProvider = mcpPortProvider,
        updateChecker = updateChecker,
        textRecognizer = textRecognizer,
    )

    private val prootManager get() = localSessionManager.prootManager

    private fun profileLabel(profileId: String): String =
        runBlocking {
            connectionRepository.getById(profileId)?.label
        } ?: profileId

    private fun clientHintSnapshot(): String? = currentClientHint
    private fun registeredToolNames(): Set<String> = tools.keys

    private val presentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextAgentNotificationId = AtomicInteger(40_000)

    @Volatile
    private var agentNotificationChannelEnsured = false

    @Volatile
    private var installNotificationChannelEnsured = false

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val toolContext = ToolContext(
        profileLabel = ::profileLabel,
        backgroundScope = backgroundScope,
        attachAgentShell = ::attachAgentShell,
        invalidateGuestTools = ::invalidateGuestTools,
    )
    private val desktopProvider = DesktopToolProvider(
        ctx = toolContext,
        desktopSessionRegistry = desktopSessionRegistry,
        localSessionManager = localSessionManager,
        textRecognizer = textRecognizer,
    )
    private val mailProvider = MailToolProvider(
        ctx = toolContext,
        mailSessionManager = mailSessionManager,
        mailRuleRepository = mailRuleRepository,
        mailWatchManager = mailWatchManager,
        transportSelector = transportSelector,
        preferencesRepository = preferencesRepository,
        connectionLogRepository = connectionLogRepository,
        connectionRepository = connectionRepository,
        agentUiCommandBus = agentUiCommandBus,
    )
    private val openAiProvider: OpenAiToolProvider? =
        if (openAiSessionManager != null && tunnelResolver != null) {
            OpenAiToolProvider(
                ctx = toolContext,
                openAiSessionManager = openAiSessionManager,
                connectionRepository = connectionRepository,
                openAiClient = sh.haven.core.openai.OpenAiClient(),
                tunnelResolver = tunnelResolver,
            )
        } else {
            null
        }
    private val sensesProvider = SensesToolProvider(
        context = context,
        shizukuGrant = { permission ->
            try {
                runShizukuOrThrow(permission, "pm grant")
                null
            } catch (e: McpError) {
                e.message
            }
        },
    )
    private val gpsProvider = GpsToolProvider(
        context = context,
        shizukuGrant = { permission ->
            try {
                runShizukuOrThrow(permission, "pm grant")
                null
            } catch (e: McpError) {
                e.message
            }
        },
        preferencesRepository = preferencesRepository,
        localSessionManager = localSessionManager,
    )
    private val notificationProvider = NotificationToolProvider(
        context = context,
        shizukuExec = { cmd ->
            try {
                runShizukuOrThrow(cmd, "cmd", requireSuccess = false)
            } catch (e: McpError) {
                null
            }
        },
    )
    private val reflexProvider = ReflexToolProvider(
        context = context,
        sshSessionManager = sshSessionManager,
        localSessionManager = localSessionManager,
        transportSelector = transportSelector,
        ctx = toolContext,
    )
    private val havenUiAndPolicyProvider = HavenUiAndPolicyToolProvider(
        context = context,
        havenUiBridge = havenUiBridge,
        standingPolicyRepository = standingPolicyRepository,
        agentConsentManager = agentConsentManager,
        currentClientHint = ::clientHintSnapshot,
        knownToolNames = ::registeredToolNames,
    )
    private val prootAndGuestProvider = ProotAndGuestToolProvider(
        ctx = toolContext,
        context = context,
        preferencesRepository = preferencesRepository,
        localSessionManager = localSessionManager,
        prootInstallLogRepository = prootInstallLogRepository,
        presentationManager = presentationManager,
    )

    suspend fun readUiScreenResource(): Pair<String, String> =
        havenUiAndPolicyProvider.readUiScreenResource()

    private val providers: List<ToolProvider> = injectedProviders.toList() + listOfNotNull(
        desktopProvider,
        mailProvider,
        openAiProvider,
        sensesProvider,
        gpsProvider,
        notificationProvider,
        reflexProvider,
        havenUiAndPolicyProvider,
        prootAndGuestProvider,
    )

    private val tools: Map<String, ToolHandler> = buildMap {
        fun register(name: String, handler: ToolHandler) {
            check(put(name, handler) == null) {
                "Duplicate MCP tool registration: $name"
            }
        }
        for ((name, handler) in toolsPart1()) register(name, handler)
        for ((name, handler) in toolsPart2()) register(name, handler)
        for (provider in providers) {
            val defaultCat = provider.defaultCategory
            for ((name, handler) in provider.tools()) {
                val effective = if (
                    handler.category == null && defaultCat != null &&
                    sh.haven.core.data.agent.McpToolCategory.of(name) == sh.haven.core.data.agent.McpToolCategory.OTHER
                ) {
                    handler.withCategory(defaultCat)
                } else {
                    handler
                }
                register(name, effective)
            }
        }
    }

    // #755: the disabled-tool set is a Flow, but definitions() (tools/list)
    // is not suspend, so keep a volatile snapshot the collector below keeps
    // current. It starts empty (nothing disabled) and converges on the
    // persisted set within a DataStore read of startup; callTyped() reads the
    // catalog directly (it is suspend) so a call is gated on the authoritative
    // set even before the snapshot lands.
    @Volatile
    private var disabledToolSnapshot: Set<String> = emptySet()

    init {
        val uncategorised = tools.mapNotNull { (name, h) ->
            val cat = h.category ?: sh.haven.core.data.agent.McpToolCategory.of(name)
            name.takeIf { cat == sh.haven.core.data.agent.McpToolCategory.OTHER }
        }
        check(uncategorised.isEmpty()) {
            "MCP tools registered without a Settings switch category (set ToolHandler.category, " +
                "ToolProvider.defaultCategory, or add to McpToolCategory): $uncategorised"
        }
        toolCatalog?.let { catalog ->
            catalog.publish(tools.map { (name, h) ->
                val cat = h.category ?: sh.haven.core.data.agent.McpToolCategory.of(name)
                sh.haven.core.data.agent.McpToolInfo(
                    name = name,
                    consentLevel = h.consentLevel,
                    category = cat,
                    summary = firstSentenceSummary(h.description),
                )
            })
            presentScope.launch {
                catalog.disabledTools.collect { disabledToolSnapshot = it }
            }
        }
    }

    private fun toolsPart1(): Map<String, ToolHandler> = toolMapOf(
        "get_app_info" to ToolHandler(
            description = "Return Haven version, which optional features are available in this build, and mcpCarriers — which MCP transports are actually open right now (a WireGuard-collision warning if the WG carrier is shadowed by a system VPN, and whether the near/SSH carrier is currently riding a connected interactive session — see McpNearCarrier).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getAppInfo() },

        "get_native_crashes" to ToolHandler(
            description = "Return native crashes (SIGSEGV, SIGABRT, …) from previous runs of Haven, newest first, each with the system's tombstone when one was kept — the backtrace that names the failing function and library. This is the diagnostic Haven could not previously produce: it records its own logcat from inside its own process, so a native signal kills the recorder too and the tombstone lands after Haven is gone. Recovered on the next launch from ActivityManager.getHistoricalProcessExitReasons. Requires Android 11 (API 30); on older releases `supported` is false and the list is empty rather than misleadingly so. Kotlin/Java exceptions are NOT here — those do not kill the process this way. Read-only.",
            inputSchema = objectSchema {
                boolean("includeTrace", "Include the full tombstone text for each crash. Default true; set false for a short index when the traces are long.")
            },
        ) { args -> getNativeCrashes(args) },

        "get_process_exits" to ToolHandler(
            description = "Return system-initiated kills of previous Haven processes, newest first — the in-app equivalent of `adb shell dumpsys activity exit-info` (#494). Each: timestampMs; kind (DEBUG_APP_FORCE_STOP = Developer options' 'Select debug app' points at Haven and Android force-stops it, FORCE_STOPPED, FROZEN, LOW_MEMORY, ANR, SIGNALED, EXCESSIVE_RESOURCE_USAGE, OTHER_KILL); importance at death (100 foreground / 125 foreground-service / 400 cached); tookActiveWork (importance ≤ 230 — the death took user-visible sessions with it); and the system's description. Use this FIRST when diagnosing 'sessions disconnect in the background': an entry at the disconnect time = the process was killed (who and why is right here); no entry = the process survived and something cut the network instead. Crashes are NOT here — native ones are in get_native_crashes. Requires Android 11 (API 30). Read-only.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getProcessExits() },

        "list_paired_clients" to ToolHandler(
            description = "List the MCP clients paired with Haven — the clientInfo.name values that passed the first-connect pairing prompt and may call tools. For each: `name`; `autoApprove` (true when the user has enabled 'Skip approval prompts' for it under Settings → Agent endpoint → Paired MCP clients, so its calls bypass per-call consent); and `isCaller` (true for the client making this request). Read-only.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listPairedClients() },

        "unpair_mcp_client" to ToolHandler(
            description = "Remove a paired MCP client from Haven's allowlist. It must be approved again via a fresh pairing prompt the next time it connects, and any persistent auto-approval for it is revoked. Use list_paired_clients for exact names. Note: this gates *new* connections — a client with an already-established session may keep working until Haven restarts. The pairing allowlist is the trust boundary, so there is intentionally no MCP tool to *add* a client (that only happens through the on-device pairing prompt) or to grant a client auto-approval (that's UI-only). Gated by consent.",
            inputSchema = objectSchema {
                string("name", "Exact clientInfo.name to un-pair, as shown by list_paired_clients.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Un-pair MCP client '${args.optString("name", "?")}' from Haven?" },
        ) { args -> unpairMcpClient(args) },

        "read_exited_session" to ToolHandler(
            description = "Return the final screen of a terminal session that has already ended — the one thing every other terminal read verb cannot give you, because they need a live tab. A session that exits immediately (a remoteCommand that returns, a shell the server refuses, a connection dropped at startup) deregisters its tab, so read_terminal_snapshot and read_terminal_scrollback both fail with \"no registered terminal tab\" at exactly the moment its output would explain why. Pass profileId for the most recent exit on that profile, or omit it for the most recent exit overall. Returns { sessionId, profileId, label, rows, cols, exitedAtMs, screen: [lines] } with trailing blank lines trimmed, or { found: false } when nothing has exited since Haven started. The last 8 exits are kept in memory only — they do not survive an app restart.",
            inputSchema = objectSchema {
                string("profileId", "Optional: only consider sessions that belonged to this profile (from list_connections). Omit for the most recent exit on any profile.")
            },
        ) { args -> readExitedSession(args) },

        "list_directory" to ToolHandler(
            description = "List entries at a path on any connected backend (local, SSH/SFTP, SMB, rclone). Resolves the right driver from profileId — pass the literal string 'local' for the device filesystem, otherwise a profile ID from list_connections. Returns name, path, isDir, size, modTime, permissions, and mimeType for each entry. Replaces list_sftp_directory and list_rclone_directory; those still work as deprecated aliases.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile ID, or 'local' for the device filesystem.", required = true)
                string("path", "Directory path to list. Default '/' (POSIX backends), '' (rclone root), '/' for local synthetic-roots view.")
            },
        ) { args -> listDirectory(args) },

        "queue_terminal_input" to ToolHandler(
            description = "Power-user: queue text to be typed into any connected SSH session at the next matching prompt. Haven polls the session's stdout, matches `promptPattern` against the tail (ANSI escapes stripped, regex MULTILINE), then types `text + submitKey` via the session's tty when the pattern appears. Use cases include responding to interactive prompts in install scripts (`Continue? [y/N]` → `y`, or `Path:` → `/usr/local`), driving REPLs (Python, psql, Claude Code, etc.), and chaining steps where the agent waits for one prompt then types into the next. Defaults are tuned for the common \"drive an interactive shell or REPL\" case; supply explicit `promptPattern` / `submitKey` / `sessionId` when targeting something specific. Returns immediately with a queueId; delivery happens out-of-turn whenever the prompt appears (or the queue times out). Caveat: within one SSH session, only the *foreground* tmux pane receives the typed text — for multi-pane setups, pass `sessionId` to the right session and ensure the target pane is foreground. Gated by Settings → Agent endpoint → \"Allow agents to queue terminal input\" *and* per-call consent (with an \"Allow for N min\" option for collaborative windows).",
            inputSchema = objectSchema {
                string("text", "Text to type. `submitKey` is appended automatically.", required = true)
                string("sessionId", "SSH session id from list_sessions. Optional — defaults to the SSH session carrying the MCP reverse tunnel on port 8730 (which, in the agent-drives-its-own-conversation case, is the session running the agent's REPL). For unrelated sessions, pass this explicitly.")
                string("promptPattern", "Regex matched against the tail of the SSH scrollback to trigger delivery. Default `[\\\$#%>❯]\\s*\$` matches the trailing prompt glyph of common interactive shells (bash `\$`, root `#`, csh `%`, traditional `>`, fish/Claude Code/starship `❯`) at end-of-line. For specific programs, supply a pattern that matches their input prompt — e.g. `\\[y/N\\]\\s*\$` or `Password:\\s*\$` or `(?:postgres|mydb)=#\\s*\$`. ANSI escapes are stripped before matching; regex is MULTILINE.")
                string("submitKey", "Key bytes sent after the text. Default `\\r` — what TTYs in cooked mode translate to NL, and what programs in raw mode (Claude Code, vim, less, fzf, readline-based shells) read as Enter. Use `\\n` for line-buffered programs reading stdin directly without a tty. Use `\"\"` (empty) to leave the text in the input buffer without submitting (e.g. pre-fill a prompt the user will edit).")
                integer("timeoutSeconds", "Give up if the prompt hasn't appeared in this many seconds. Default 60.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            capability = AgentCapability.TERMINAL_INPUT_QUEUE,
            summarise = { args ->
                val text = args.optString("text", "").take(80)
                "Queue \"$text\" to be typed into the SSH session at the next prompt?"
            },
        ) { args -> queueTerminalInput(args) },

        // Deprecated alias for queue_terminal_input (#161). Same handler,
        // same gate, same consent shape; routes through queueTerminalInput
        // so the implementation only lives in one place. Tool names are
        // external contract — paired clients bound against this name still
        // call it (VISION.md documents it), so it stays registered until a
        // deliberate deprecation cycle. Pinned by McpToolCompatibilityTest.
        "queue_self_message" to ToolHandler(
            description = "DEPRECATED: alias for `queue_terminal_input`. Use queue_terminal_input — same arguments, same behaviour, plus a `submitKey` parameter you didn't have here.",
            inputSchema = objectSchema {
                string("text", required = true)
                string("sessionId")
                string("promptPattern")
                integer("timeoutSeconds")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            capability = AgentCapability.TERMINAL_INPUT_QUEUE,
            summarise = { args ->
                val text = args.optString("text", "").take(80)
                "Queue \"$text\" to be typed into the SSH session at the next prompt?"
            },
        ) { args -> queueTerminalInput(args) },

        "list_sftp_directory" to ToolHandler(
            description = "DEPRECATED: prefer list_directory(profileId=..., path=...). List files at a path on a connected SFTP profile. Requires an already-connected SSH/SFTP session for the profile.",
            inputSchema = objectSchema {
                string("profileId", "ID of the connected SSH/SFTP profile.", required = true)
                string("path", "Absolute directory path to list. Defaults to '.'")
            },
        ) { args -> listSftpDirectory(args) },

        "stream_sftp_file" to ToolHandler(
            description = "Start an HLS stream for an SFTP file and return the playlist URL. Reads via a loopback HTTP bridge so no bulk download is needed. Requires a connected SSH/SFTP session. Stops any prior HLS stream.",
            inputSchema = objectSchema {
                string("profileId", "ID of the connected SSH/SFTP profile.", required = true)
                string("path", "Absolute path of the media file on the SFTP server.", required = true)
            },
        ) { args -> streamSftpFile(args) },

        "serve_file" to ToolHandler(
            description = "Publish a single file from any connected backend (local, SFTP, SMB, rclone) as a short-lived loopback HTTP URL the caller can curl to its own filesystem. Returns { url, size, mimeType }. Bytes are streamed over HTTP rather than returned inline through JSON-RPC. Gated by Settings → Agent endpoint → \"Allow agents to read file contents\" and confirmed per-call by a consent prompt.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile ID, or 'local' for the device filesystem.", required = true)
                string("path", "Absolute path of the file on the chosen backend.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            capability = AgentCapability.FILE_READ,
            summarise = { args ->
                val pid = args.optString("profileId")
                val p = args.optString("path")
                "Let the agent download '$p' from '${profileLabel(pid)}' to its workstation"
            },
        ) { args -> serveFile(args) },

        "stop_stream" to ToolHandler(
            description = "Stop any currently running HLS stream started by stream_sftp_file or the UI.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> stopStream() },

        "play_file" to ToolHandler(
            description = "Open a media URL in the system player (VLC, MX Player, Chrome, etc.) via Android's ACTION_VIEW intent. Typically the playerUrl/playlistUrl returned by stream_sftp_file, or any http/https/content URL the agent already knows. The user's preferred app picker (or default app) decides what handles it — Haven only kicks off the intent.",
            inputSchema = objectSchema {
                string("url", "URL or content URI to open. http://, https://, file:// (rare; prefer FileProvider URIs) and content:// are accepted.", required = true)
                string("mimeType", "Optional MIME hint, e.g. 'video/mp4', 'application/vnd.apple.mpegurl' for HLS. Auto-detected from URL extension when omitted.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> playFile(args) },

        "present_media" to ToolHandler(
            description = "Show the user an image — or play a short sound — inline in Haven. A bottom sheet floats over whatever screen the user is on, rendering the image (or an audio card with a play button) plus an optional caption. The \"here, look at / listen to this\" channel: use it when you have something visual or audible you want the user to perceive directly. Reference the media by a file Haven can reach — `profileId` (\"local\" for the device / proot-guest cache, or an SSH/SMB/rclone profile id) + `path` — or by a ready `url` (e.g. a serve_file loopback URL). Haven streams the file into a local handle; the bytes never pass through the agent context. `mimeType` is inferred from the file (extension, else content sniff) when omitted; set it for audio. Only image/* and audio/* are supported. Returns immediately ({ presented }) as soon as the request is accepted — Haven fetches/stages the file and shows the sheet in the background, so a slow transfer can't turn a delivered image into a timeout; a staging failure is logged (not returned). The user dismisses the sheet at their leisure.",
            inputSchema = objectSchema {
                string("profileId", "Backend holding the file: \"local\" for the device / proot-guest cache (default), or an SSH/SMB/rclone profile id. Used with `path`.")
                string("path", "Absolute path of the image/audio file on that backend.")
                string("url", "Alternative to profileId+path: an http(s) URL to fetch the media from (e.g. a serve_file loopback URL).")
                string("mimeType", "Optional MIME, e.g. 'image/png' or 'audio/mpeg'. Inferred from the file otherwise; set it for audio.")
                string("caption", "Optional one-line caption shown above the media.")
                boolean("autoPlay", "Audio only: start playback as soon as the sheet appears. Defaults to false.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> presentMedia(args) },

        "list_bridges" to ToolHandler(
            description = "Unified view of every phone capability Haven is currently **brokering** to a sink — the 'Bridges' registry (see docs/design/bridges.md). A bridge is one Android-held capability (a USB device, audio, etc.) re-exposed to a consumer that can't reach it directly: the AI agent, the local Linux guest, a local VM, a remote host, or the workstation. Generalises list_usb_exports across all bridge types. Each entry: source, sourceKind, sink, transport, state, plus type-specific detail (busid/port/profileId/mounts). Read-only.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listBridges() },

        "open_local_shell" to ToolHandler(
            description = "Open a fresh local shell session (PRoot Linux by default, or Android / Shizuku shell via `mode`) and return its sessionId. Equivalent to opening a LOCAL profile — creates the profile if missing, registers a session, and connects it. The returned sessionId is immediately usable with send_terminal_input and read_terminal_scrollback. Use this when you need a clean bash REPL (e.g. when an existing session has Claude Code, vim, or another stdin-capturing process in front of it). Pass `plain: true` to bypass the user's session-manager preference (tmux / zellij / screen / byobu) and exec a bare login shell — required when the agent needs Haven's own scrollback ring to capture output, which doesn't happen when a multiplexer's status bar uses DECSTBM to reserve the bottom row. Pass `mode: \"SHIZUKU\"` (#837) for a privileged Shizuku shell (ADB uid 2000 / root) or `\"ANDROID_SHELL\"` for unprivileged `/system/bin/sh`. NOTE: if a live shell of the requested mode already exists, this returns that sessionId regardless of `plain` (response `reused: true`); call disconnect_profile on the existing profile first if you need a fresh plain-shell respawn.",
            inputSchema = objectSchema {
                boolean("plain", "Skip the user's sessionManager preference and exec a bare login shell directly. Default false (UI-equivalent behaviour).")
                string("mode", "Local shell mode (#837): \"PROOT\" (default), \"ANDROID_SHELL\", or \"SHIZUKU\".")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openLocalShell(args) },

        "set_terminal_font_from_url" to ToolHandler(
            description = "Download a font from a URL, validate it, install it as Haven's terminal font (replacing any prior custom font), and return the saved path. The URL may point at a .ttf/.otf, or a .zip containing them (a Regular face is auto-extracted) — useful for repos like Maple/Nerd Fonts that ship only zips (#123, #177). WOFF/WOFF2 web fonts are rejected (Android can't render them). Requires the URL to be reachable from the device — use a tunneled URL (via add_port_forward LOCAL) to expose a workstation HTTP server back through the existing SSH session.",
            inputSchema = objectSchema {
                string("url", "http(s) URL resolving to a .ttf/.otf, or a .zip of them (no HTML wrapper). WOFF/WOFF2 are not supported.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Download terminal font from ${args.optString("url").take(80)}?" },
        ) { args -> setTerminalFontFromUrl(args) },

        "convert_file" to ToolHandler(
            description = "Run ffmpeg to transcode a source URL (typically the playerUrl/playlistUrl from stream_sftp_file, or any URL ffmpeg can read) into a new file in Haven's app cache. Returns the cache path on success — use upload_file_to_sftp to put the result on a remote.",
            inputSchema = objectSchema {
                string("sourceUrl", "URL ffmpeg should read from. http(s)://, file://, or any protocol ffmpeg supports.", required = true)
                string("container", "Output container, e.g. 'mp4', 'mkv', 'webm'. Determines the file extension.", required = true)
                string("videoEncoder", "Video codec, e.g. 'libx264', 'libx265', 'copy'. Default: copy.")
                string("audioEncoder", "Audio codec, e.g. 'aac', 'libopus', 'copy'. Default: copy.")
                string("outputName", "Optional output filename (without extension). Default: 'agent-convert-<timestamp>'.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val container = args.optString("container", "mp4")
                val ve = args.optString("videoEncoder", "copy")
                val ae = args.optString("audioEncoder", "copy")
                "Transcode source → $container (video: $ve, audio: $ae)?"
            },
        ) { args -> convertFile(args) },

    )

    private fun toolsPart2(): Map<String, ToolHandler> = toolMapOf(
        "present_web" to ToolHandler(
            description = "Show the user HTML, an SVG, or a PDF inline in an in-app WebView — the interactive rung between present_media (a static image) and present_app (a full live VNC app). Pass a `url` (e.g. a serve_file loopback URL or any web page), or reference a file with `profileId` (\"local\" for the device / proot-guest cache, or an SSH/SMB/rclone profile id) + `path`, which Haven serves over a loopback URL. A PDF is paged; HTML/SVG render live (pinch-zoom + pan). Floats in a bottom sheet over whatever screen the user is on; bytes never pass through the agent context. Returns immediately: a `url` acks with { presented, id, url }; a file reference acks with { presented } and is staged/shown in the background (a staging failure is logged, not returned). The user dismisses it at their leisure.",
            inputSchema = objectSchema {
                string("url", "An http(s) URL to load (e.g. a serve_file loopback URL or any web page). Alternative to profileId+path.")
                string("profileId", "Backend holding the file: \"local\" (device / proot-guest cache, default) or an SSH/SMB/rclone profile id. Used with `path`.")
                string("path", "Absolute path of the .html/.svg/.pdf file on that backend.")
                string("caption", "Optional one-line caption shown above the view.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> presentWeb(args) },

        "raise_notification" to ToolHandler(
            description = "Post a real Android system notification on Haven's behalf so the agent can drive notification-listener / wake / DND / silencer apps during F-Droid tester reviews without needing a second device. Always posts to the dedicated 'agent.test.notifications' channel (created on first use) so the user can mute agent notifications cleanly without affecting Haven's own connection / renewal notifications. Returns { posted, id, channel } — keep the id around if you want to dismiss or replace the notification later (a future tool). Notifications use Haven's app identity, so notification-listener apps see package=sh.haven.app. Requires the POST_NOTIFICATIONS runtime grant (declared in the manifest, granted by the user on first Haven launch); the call fails with a clear error if notifications have been disabled in system settings.",
            inputSchema = objectSchema {
                string("title", "Notification title (mandatory). Shown on the lockscreen / shade row.", required = true)
                string("body", "Notification body (mandatory). Expanded into a BigTextStyle so multi-line content stays readable.", required = true)
                string("priority", "One of 'min', 'low', 'default', 'high', 'max'. Maps to NotificationCompat.PRIORITY_*. Defaults to 'default'. Note: from Android 8 the channel's importance is what actually drives heads-up behaviour; priority only matters on pre-O devices and as a hint to ranking.")
                boolean("ongoing", "If true, post as ongoing (non-dismissable) so the agent can test foreground-service-like notifications. Defaults to false (dismissable, auto-cancels on tap).")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> raiseNotification(args) },

        "open_convert_dialog_with_args" to ToolHandler(
            description = "Stage a conversion in the SFTP screen's convert dialog with the given container / codec defaults. Switches to the SFTP tab and opens the dialog; the user reviews and taps Convert to actually run ffmpeg. Tap-equivalent — the agent suggests, the user confirms. Use convert_file (EVERY_CALL consent) to skip the dialog and run the conversion directly.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile ID. Use list_connections to find IDs.", required = true)
                string("sourcePath", "Absolute path of the source file.", required = true)
                string("container", "Pre-selected container, e.g. 'mp4', 'mkv', 'webm', 'mp3'. Optional — defaults to source extension.")
                string("videoEncoder", "Pre-selected video codec, e.g. 'libx264', 'libx265', 'copy'. Optional — defaults to 'copy'.")
                string("audioEncoder", "Pre-selected audio codec, e.g. 'aac', 'libopus', 'copy'. Optional — defaults to 'copy'.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openConvertDialogWithArgs(args) },

        "focus_terminal_session" to ToolHandler(
            description = "Switch to the Terminal tab and bring the session with this sessionId to the front. Tap-equivalent — same effect as the user tapping the Terminal tab and tapping the session header. Use list_sessions to discover live sessionIds; stale IDs drop silently without error.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID (from list_sessions). Must be a session attached to a Terminal tab.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> focusTerminalSession(args) },

        "navigate_sftp_browser" to ToolHandler(
            description = "Switch to the Files tab and open the file browser at the given path on the given profile. Tap-equivalent — same effect as the user tapping into the SFTP screen and entering the path. The path is interpreted by whichever backend the profile resolves to (POSIX absolute for SSH/Local, share-relative for SMB, remote-relative for rclone).",
            inputSchema = objectSchema {
                string("profileId", "Connection profile ID (or the literal string \"local\" for the device filesystem). Use list_connections to find IDs.", required = true)
                string("path", "Directory path to open. Default '/' for SSH/Local, '' for rclone (treated as remote root).")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> navigateSftpBrowser(args) },

        "open_file_in_editor" to ToolHandler(
            description = "Open a text file in Haven's built-in editor (TextMate-syntax-highlighted, with Save). Routes to the SFTP/Files tab and loads the file from the given profile's backend (SSH, SMB, rclone, or the literal \"local\" for the device filesystem). The file is read on demand by the active backend; binary files render as garbled UTF-8 — use this for source code, config, logs, etc.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile ID (or the literal string \"local\" for the device filesystem). Use list_connections to find IDs.", required = true)
                string("path", "Absolute path to the file to open. POSIX absolute for SSH/Local, share-relative for SMB, remote-relative for rclone.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openFileInEditor(args) },

        "read_terminal_scrollback" to ToolHandler(
            description = "Return the most recent bytes of raw SSH stdout for an active terminal session, exactly as the user sees them (ANSI escapes, OSC markers, control bytes preserved). Use list_sessions to discover sessionIds. The buffer is capped at 256 KiB per session and rolls older bytes off; the human terminal still keeps its own visual scrollback separately.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open.")
                integer("maxBytes", "Maximum bytes to return. Default 16384, hard-capped at 262144.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readTerminalScrollback(args) },

        "read_terminal_snapshot" to ToolHandler(
            description = "Return a structured snapshot of an active terminal session: dimensions, cursor row/col, terminal title, scrollback line count + current scrollbackPosition, the remote-driven terminal modes (mouseMode / activeMouseMode 1000|1002|1003 / bracketPasteMode), an oscEvents object with the last-seen OSC 52 clipboard-set / OSC 7 cwd / OSC 8 hyperlink / OSC 9|777 notification, and the visible-screen lines as plain text (with `softWrapped` flag per line, and optional OSC 133 semantic segments). Use list_sessions to discover sessionIds. Distinct from read_terminal_scrollback, which returns raw bytes; this is the parsed view useful for cursor-aware tooling, prompt detection, and asserting OSC/mouse-mode round-trips.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open. Must have an attached terminal tab.")
                boolean("includeSemanticSegments", "If true, include each line's OSC 133 prompt-marker segments (PROMPT / COMMAND_INPUT / COMMAND_OUTPUT / COMMAND_FINISHED / ANNOTATION). Default false.")
                integer("maxLines", "Maximum number of visible-screen lines to include from the top. Default returns all visible rows. Cursor and dimensions are always present regardless.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readTerminalSnapshot(args) },

        "get_selection" to ToolHandler(
            description = "Return the current text-selection state for an active terminal session: { active, mode (NONE/CHARACTER/WORD/LINE), range: { startRow, startCol, endRow, endCol } | null }. Reads termlib's SelectionController; valid only while the session has an attached terminal tab.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID with an attached terminal tab.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getSelection(args) },

        "set_compose_mode" to ToolHandler(
            description = "Toggle or set termlib's local compose mode for a terminal session — the on-screen buffer used for CJK / accented / voice-friendly text entry. While compose mode is on, typed text (including IME-composed CJK candidates) buffers in an overlay at the cursor and the terminal hands the IME a composition-friendly InputConnection; the buffer commits to the shell on Enter, after which compose mode clears. Pass enabled=true/false to set explicitly, or omit enabled to toggle. Requires an attached terminal tab (errors for headless agent shells). Returns { sessionId, composeModeActive, composedText }.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID with an attached terminal tab.", required = true)
                boolean("enabled", "true = start compose mode, false = stop. Omit to toggle the current state.")
            },
            // ONCE_PER_SESSION: only flips an in-memory IME input mode +
            // local compose buffer; nothing is sent to the remote until the
            // user/agent presses Enter (a separate input call).
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                if (args.has("enabled")) "Set terminal compose mode = ${args.optBoolean("enabled")}?"
                else "Toggle terminal compose mode?"
            },
        ) { args -> setComposeMode(args) },

        "read_clipboard" to ToolHandler(
            description = "Return the system clipboard's primary plain-text content. Returns { text } where text is null when the clipboard is empty or non-text (image, intent, etc.). On Android 10+ the system enforces foreground/IME restrictions on clipboard reads; this call may return null even when the clipboard has content if Haven isn't currently focused.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> readClipboard() },

        "get_preference" to ToolHandler(
            description = "Read a Haven user preference by key. Whitelisted keys: terminal_scrollback_rows, terminal_tap_to_position_cursor, terminal_bell_notification (#753 bool, default true — post a notification naming the tab when a terminal rings BEL while Haven is backgrounded), terminal_font_size, terminal_horizontal_padding (#745: horizontal margin around the terminal grid in dp, 0..24 — the column count recalculates), terminal_color_scheme, terminal_auto_switch_scheme, terminal_light_color_scheme, terminal_dark_color_scheme, terminal_locale, terminal_inline_images (#583 inline-image consent: OFF / ASK (default) / ALWAYS — whether SSH programs' Kitty graphics / iTerm2 images render), mouse_input_enabled, terminal_right_click, terminal_tab_titles_follow_session (bool — tabs attached to tmux/zellij/screen show the session name instead of titles set by running programs; default true), mcp_tunnel_endpoint_profile_id, mcp_wireguard_enabled, mcp_lan_bind_enabled, mcp_wireguard_tunnel_config_id, usb_guest_exposure_enabled, gps_guest_exposure_enabled, connection_logging_enabled, verbose_logging_enabled, remap_low_ports (#300 proot launch toggle), share_storage_with_guest (#301 proot launch toggle), bind_android_system (#304 proot launch toggle), proot_dns_mode (#446 - system|public|custom), proot_dns_servers (custom nameservers), toolbar_layout (string — the terminal keyboard toolbar layout as JSON; see set_preference for the shape), custom_desktop_command (string — the Custom (X11) desktop's session command), update_check_enabled (bool — #578 opt-in launch-time update check; off by default, and inert on a copy not signed with the GitHub-release key), update_check_last_run_ms (long — epoch ms of the last launch-time check; the once-a-day throttle is measured from it), update_check_last_notified_version (string — the version the user was last notified about; blank if never). Returns { key, value } where value's type follows the preference's type (int / boolean / string). Colour-scheme values are TerminalColorScheme enum names.",
            inputSchema = objectSchema {
                string("key", "Preference key (see whitelist in description).", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getPreference(args) },

        // Read-only in effect — it changes nothing — but it asks for consent
        // anyway, because it makes an outbound request to GitHub and the whole
        // premise of #578 is that this request is something the user opts into.
        // A verb that fired it silently would undo the feature's own stance.
        "check_for_update" to ToolHandler(
            description = "Ask GitHub whether a newer Haven release exists, right now, and report the answer (#578). Independent of the update_check_enabled preference — that switch arms the unattended launch-time check; this is the on-demand one. Returns { channel, installedVersion, status, latestVersion?, releaseUrl?, error? }. channel is GITHUB_RELEASE when the running copy's own signing certificate matches the key that signs the release APKs, OTHER for anything else (F-Droid, a self-built or re-signed copy) — and on OTHER the status is WRONG_CHANNEL and NO network request is made, because a GitHub release cannot replace a differently-signed package. status is one of UP_TO_DATE, UPDATE_AVAILABLE, WRONG_CHANNEL, FAILED; FAILED carries `error` (GitHub's unauthenticated API is rate-limited per IP, so a shared address can 403). This is the verb to use to verify the update path end to end without driving Settings by hand.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { "Ask GitHub whether a newer Haven release exists?" },
        ) { _ -> checkForUpdate() },

        // --- Write tools (require consent) ------------------------------

        "upload_file" to ToolHandler(
            description = "Write a local file to a path on any connected backend (local, SSH, SMB, rclone). Source must live under Haven's app cache (context.cacheDir) — the agent has no other writable surface, so this constraint blocks reads of arbitrary device files via the upload destination. Currently uses small-file semantics (loads the source into memory); streaming variants ship in a later #126 stage. Replaces upload_file_to_sftp; that still works as a deprecated alias for the SSH streaming path.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile ID, or 'local' for the device filesystem.", required = true)
                string("localPath", "Absolute path to a file under context.cacheDir on the device.", required = true)
                string("remotePath", "Destination path on the target backend.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val local = args.optString("localPath", "?")
                val size = try { File(local).length() } catch (_: Exception) { 0L }
                val sizeStr = if (size > 0) "${size / 1024} KiB" else "(unknown size)"
                "Upload $sizeStr → ${args.optString("remotePath")} on \"${profileLabel(pid)}\"?"
            },
        ) { args -> uploadFile(args) },

        "delete_file" to ToolHandler(
            description = "Delete a file (not a directory) on any connected backend (local, SSH, SMB, rclone). Replaces delete_sftp_file; that still works as a deprecated alias.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile ID, or 'local' for the device filesystem.", required = true)
                string("path", "Path of the file to delete.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete ${args.optString("path")} on \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteFile(args) },

        "upload_file_to_sftp" to ToolHandler(
            description = "DEPRECATED: prefer upload_file(profileId=..., localPath=..., remotePath=...). Upload a local file to a path on a connected SFTP profile. Source must be a path under Haven's app cache (context.cacheDir) — the agent has no other writable surface, so this constraint blocks reads of arbitrary files via the upload destination. Requires a connected SSH/SFTP session. Uses streaming SFTP put (no in-memory buffer); use this for files larger than ~50 MiB until upload_file gains streaming support.",
            inputSchema = objectSchema {
                string("profileId", "Connected SSH/SFTP profile ID.", required = true)
                string("localPath", "Absolute path to a file under context.cacheDir on the device.", required = true)
                string("remotePath", "Absolute destination path on the SFTP server.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val local = args.optString("localPath", "?")
                val size = try { File(local).length() } catch (_: Exception) { 0L }
                val sizeStr = if (size > 0) "${size / 1024} KiB" else "(unknown size)"
                "Upload $sizeStr → ${args.optString("remotePath")} on \"${profileLabel(pid)}\"?"
            },
        ) { args -> uploadFileToSftp(args) },

        "delete_sftp_file" to ToolHandler(
            description = "DEPRECATED: prefer delete_file(profileId=..., path=...). Delete a file (not directory) from a connected SFTP profile. Requires a connected SSH/SFTP session.",
            inputSchema = objectSchema {
                string("profileId", "Connected SSH/SFTP profile ID.", required = true)
                string("path", "Absolute path of the file to delete.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete ${args.optString("path")} on \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteSftpFile(args) },

        "send_terminal_input" to ToolHandler(
            description = "Send input to an active terminal session as if the user typed it. Provide `text` (UTF-8) and/or named `keys` — real control bytes (Enter/Esc/Ctrl-C/arrows) that `text` can't express (a \"\\r\" in text arrives as literal chars; a raw-mode REPL reads \"\\n\" as newline-insert, not submit). `text` is sent first, then `keys`, so a submit key lands after the body. Set `bracketedPaste` to wrap `text` in bracketed-paste markers so a raw-mode REPL (Claude Code, readline, vim) treats multi-line input as one paste instead of interleaved keystrokes that fight submit. Set `returnSnapshot` to get the resulting screen back without a follow-up read_terminal_snapshot. Hard cap 4096 bytes total. Returns { sessionId, delivered, transport, bytesSent }, where `transport` names which transport accepted the write — `delivered` means accepted, not echoed back.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open. Must have an attached terminal.")
                string("text", "UTF-8 text to send (before keys). To submit into a raw-mode REPL, prefer keys:[\"enter\"] over a trailing \\n.")
                stringArray("keys", "Named keys sent after text, e.g. [\"enter\"], [\"ctrl-c\"], [\"up\",\"enter\"]. Supported: enter, esc, tab, space, backspace, delete, up, down, left, right, home, end, pageup, pagedown, ctrl-a/c/d/e/l/u/w/z.")
                boolean("bracketedPaste", "Wrap text in bracketed-paste markers (ESC[200~ … ESC[201~). Default false. Use for multi-line input into a raw-mode REPL so it isn't folded into submit.")
                boolean("returnSnapshot", "Return the terminal snapshot after sending, so you see the result without a follow-up read. Default false.")
                integer("snapshotDelayMs", "Wait this long after sending before capturing the returnSnapshot, so the target has rendered the input. Default 0, max 5000. Only meaningful with returnSnapshot=true.")
            },
            // Once the user approves the agent typing into a session, per-call
            // re-approval is friction without added safety — the session is
            // already agent-controlled, and EVERY_CALL made interactive shell
            // driving unusable (each keystroke-batch blocked the request on a
            // device tap and timed out when unattended). Matches open_local_shell.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val text = args.optString("text", "")
                val preview = if (text.length > 80) text.substring(0, 80) + "…" else text
                val visible = preview.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                "Send to terminal: \"$visible\"?"
            },
        ) { args -> sendTerminalInput(args) },

        "send_to_agent" to ToolHandler(
            description = "Deliver one message to another agent's REPL (or any raw-mode prompt) as a single submitted turn: paste the text (bracketed-paste when the target has enabled it, plain otherwise), settle, then Enter — and return the resulting screen (last ~50 lines by default, captured after a short render delay). A convenience wrapper over send_terminal_input tuned for agent↔agent / REPL conversation, so you don't hand-assemble the body-then-Enter sequence. Use list_sessions (chosenSessionName + isAgentRepl) to pick the target; pair with await_turn + read_last_turn for the full send → wait → read loop. Returns { sessionId, delivered, transport, bytesSent, snapshot }.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session.")
                string("message", "The message to deliver as one submitted prompt.", required = true)
                integer("maxLines", "Cap the returned snapshot to the last N lines (default 50). Keeps the ack small so a delivered message doesn't read back as a timeout behind a large scrollback over a tunnel.")
                integer("snapshotDelayMs", "Wait this long after Enter before capturing the ack snapshot so it reflects the submitted turn. Default 500, max 5000.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val m = args.optString("message", "")
                "Send to agent REPL: \"${if (m.length > 80) m.take(80) + "…" else m}\"?"
            },
        ) { args -> sendToAgent(args) },

        "await_turn" to ToolHandler(
            description = "Block until a terminal session is idle-at-prompt — the natural \"wait for the other agent / command to finish\" primitive for turn-based conversation (#226). Detection: OSC 133 shell-integration segments when present (idle = cursor on the newest prompt row); otherwise screen heuristics tuned for Claude Code's TUI (no busy spinner / \"esc to interrupt\", a prompt-looking line near the bottom, and the screen stable across polls). Idle must hold for settleMs before returning. Returns { sessionId, idle, method: \"osc133\"|\"heuristic\", waitedMs, timedOut }. idle=false + timedOut=true means the timeout elapsed first — the session may still be mid-turn. Full-screen TUIs that are neither shells nor agent REPLs (vim, htop) can read as idle once their screen stops changing; this is a turn heuristic, not a process-state probe.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session. Must have an attached terminal tab.")
                integer("timeoutMs", "Give up after this long. Default 60000, clamped to 1000–300000. Keep under your MCP client's request timeout.")
                integer("settleMs", "How long idle must hold continuously before returning. Default 1500, clamped to 0–30000.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> awaitTurn(args) },

        "read_last_turn" to ToolHandler(
            description = "Return the target session's latest completed turn as text — the receive half of agent↔agent conversation (#226). When the session is idle at an OSC 133-integrated shell prompt, returns the last command's output (semantic COMMAND_OUTPUT between the newest COMMAND_INPUT and COMMAND_FINISHED, scrollback included; source=\"osc133\"). Otherwise (Claude Code and other REPLs that don't emit shell-style 133) falls back to scraping the last ●/⏺-bulleted block above the input box from the visible screen (source=\"scrape\" — inherently heuristic; long replies that scrolled off-screen are truncated to what's visible). Returns { sessionId, text|null, source|null, truncated }. Call await_turn first so you read a finished turn, not a partial one.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session. Must have an attached terminal tab.")
                integer("maxChars", "Cap the returned text, keeping the TAIL (the end of a reply is usually the conclusion). Default 20000, clamped to 256–100000. Sets truncated=true when applied.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readLastTurn(args) },

        "feed_terminal_output" to ToolHandler(
            description = "Inject raw bytes into a terminal session's OUTPUT stream — as if they had arrived from the remote — running the exact pipeline the live data callback uses (OSC scan → mouse-mode scan → emulator). Distinct from send_terminal_input, which sends to the PTY input as if typed. Use this to deterministically exercise output-side parsing without a cooperating remote: e.g. feed an OSC 52 sequence to test the clipboard round-trip, a DECSET 1000/1002/1003 to flip mouseMode, an OSC 8 hyperlink, or a partial escape split across two calls (the OSC scanner keeps state between calls). Provide exactly one of `text` (UTF-8) or `bytesBase64` (for control bytes / ESC). Hard cap 65536 bytes per call. On agent-opened local shells the bytes run the DECSET mode scan (mouse / bracketed paste) then go into the agent emulator — same as their live pipeline, which has no OSC scan. Errors when the session has no output pipeline. Returns { sessionId, bytesFed }.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID with an attached terminal tab.", required = true)
                string("text", "UTF-8 text to inject as output. Mutually exclusive with bytesBase64.")
                string("bytesBase64", "Base64-encoded raw bytes to inject — use this for escape sequences (ESC = \\u001b) and other control bytes. Mutually exclusive with text.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = if (args.has("text")) {
                    args.optString("text").toByteArray(Charsets.UTF_8).size
                } else {
                    runCatching { android.util.Base64.decode(args.optString("bytesBase64"), android.util.Base64.DEFAULT).size }
                        .getOrDefault(0)
                }
                "Inject $n bytes into the terminal OUTPUT stream of session ${args.optString("sessionId").take(8)}…? (renders as if sent by the remote — can set the clipboard via OSC 52)"
            },
        ) { args -> feedTerminalOutput(args) },

        "write_clipboard" to ToolHandler(
            description = "Set the system clipboard's primary plain-text content. Replaces whatever's currently on the clipboard. Useful for priming the clipboard before triggering a terminal paste.",
            inputSchema = objectSchema {
                string("text", "Text to place on the clipboard. Empty string is allowed and clears the clipboard's primary item.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val text = args.optString("text", "")
                val preview = if (text.length > 80) text.substring(0, 80) + "…" else text
                val visible = preview.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                "Replace clipboard with: \"$visible\"?"
            },
        ) { args -> writeClipboard(args) },

        "set_preference" to ToolHandler(
            description = "Write a Haven user preference. Whitelisted keys (and their types): terminal_scrollback_rows (int 100..25000), terminal_tap_to_position_cursor (bool), terminal_bell_notification (bool — #753, default true: post a notification naming the tab when a terminal rings BEL while Haven is backgrounded), terminal_font_size (int 8..32), terminal_horizontal_padding (int 0..24 dp — #745 horizontal margin around the terminal grid; the column count recalculates), mouse_input_enabled (bool), terminal_right_click (bool), terminal_tab_titles_follow_session (bool — tabs attached to tmux/zellij/screen show the session name instead of titles set by running programs), terminal_color_scheme (string — a TerminalColorScheme enum name, e.g. HAVEN, DRACULA, NORD, GRUVBOX; case-insensitive), terminal_auto_switch_scheme (bool — when true the active scheme follows system light/dark via the light/dark keys), terminal_light_color_scheme (string scheme name), terminal_dark_color_scheme (string scheme name), terminal_background_opacity (float 0.0..1.0 — below 1.0 the terminal renders over the device wallpaper), terminal_inline_images (string — TerminalInlineImages enum name #583: \"OFF\" renders nothing, \"ASK\" (default) shows a consent dialog per image, \"ALWAYS\" renders directly), terminal_locale (string, e.g. zh_CN.UTF-8 — exported to local terminal sessions as LANG/LC_ALL; glibc distros need the locale generated first), mcp_tunnel_endpoint_profile_id (string SSH profile id, empty to clear), mcp_wireguard_enabled (bool), mcp_lan_bind_enabled (bool — also bind the device Wi-Fi/LAN address for direct same-network reach), mcp_wireguard_tunnel_config_id (string tunnel config id the MCP server keeps up as its WG carrier, empty to clear), usb_guest_exposure_enabled (bool — master gate for usb_attach_to_guest), gps_guest_exposure_enabled (bool — master gate for attach_gps_to_guest), connection_logging_enabled (bool — audit-log connection lifecycle events to Settings → View connection log; off by default; enable before reproducing a connection issue, then read get_connection_log), verbose_logging_enabled (bool - per-session transport tracing, captured into each ConnectionLog entry's verboseLog; off by default. Needed AS WELL AS connection_logging_enabled: the RDP decode breakdown, the negotiated graphics capabilities and the discarded-bitmap detail exist nowhere else), gpu_use_venus (bool — experimental venus+zink GPU stack for accelerated desktops; off = virgl/virpipe), remap_low_ports (bool — #300 proot launch toggle: remap guest privileged ports +2000), share_storage_with_guest (bool — #301 proot launch toggle: mount /storage + /sdcard into the local guest; default on), bind_android_system (bool — #304 proot launch toggle: bind Android's read-only /system, /vendor, /apex, /product, /system_ext, /odm into the guest so it can run Android native binaries like getprop/toybox; default off, exposes device internals), proot_dns_mode (string - #446: which resolvers the local Linux guest gets in /etc/resolv.conf. \"system\" (default) uses the network's own resolvers, \"public\" uses Google 8.8.8.8 + Cloudflare 1.1.1.1 (the old hardcoded pair), \"custom\" uses proot_dns_servers. Networks that block outbound port 53 to anything but their own resolver make \"public\" fail silently - package installs just hang), proot_dns_servers (string - comma/space separated IP literals for \"custom\"; hostnames are rejected because resolv.conf has no way to resolve them), toolbar_layout (string — the terminal keyboard toolbar as JSON: a 2-element array of rows, each row an array whose elements are either a built-in key id string (\"esc\", \"paste\", \"text_input\", \"arrow_up\", \"ctrl\", \"home\", … — see ToolbarKey) or a custom-key object {\"label\":\"…\",\"send\":\"…\"}; set validates against ToolbarLayout and replaces the WHOLE layout, so get_preference it first, edit, and write it back — e.g. add \"text_input\" to a row to surface the floating-text-input key), custom_desktop_command (string — the Custom (X11) desktop session command run at its next start; the command IS the session: when it exits the desktop stops, and a command that dies at startup surfaces its output in the desktop row's error state), update_check_enabled (bool — #578: look for a newer GitHub release when Haven opens, at most once an hour. Off by default. Turning it ON is what arms the launch-time path; check_for_update runs a check right now regardless)), update_check_last_run_ms (long epoch ms — set to 0 to CLEAR the once-an-hour throttle), update_check_last_notified_version (string — set to \"\" to CLEAR the already-told-you dedup). Those two exist so the launch path can be exercised for real: clear whichever gate you are testing, restart Haven, and watch checkOnLaunch run. check_for_update deliberately cannot substitute — it runs the on-demand check, which posts no notification and touches neither gate. Takes effect on the next local session/command. Returns { key, value }.",
            inputSchema = objectSchema {
                string("key", "Preference key (see whitelist).", required = true)
                property("value", JSONObject().put("description", "New value. Type must match the key's type — int for the *_rows / *_size keys, bool for the rest."), required = true)
            },
            // ONCE_PER_SESSION: settings writes are reversible and the
            // whitelist is small + non-sensitive (terminal display
            // tweaks). One approval per session avoids prompt fatigue
            // when an agent is sweeping multiple settings during a test
            // run; the user retains the destructive-action safety on
            // tools that touch session state or the clipboard.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Set Haven preference ${args.optString("key")} = ${args.opt("value")}?" },
        ) { args -> setPreference(args) },

        "start_selection" to ToolHandler(
            description = "Anchor a new text selection at (row, col) in an active terminal session. Equivalent to a long-press at that cell. Modes: CHARACTER (default), WORD (snaps to word boundaries on creation), LINE (whole-row selection). Subsequent extend_selection / copy_selection / clear_selection calls on the same session operate on this anchor. Replaces any existing selection on this session.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID with an attached terminal tab.", required = true)
                integer("row", "Viewport-relative row, 0 = top.", required = true)
                integer("col", "Column, 0 = leftmost.", required = true)
                string("mode", "CHARACTER | WORD | LINE. Default CHARACTER.")
            },
            // ONCE_PER_SESSION: only mutates an in-memory UI selection,
            // doesn't write to the clipboard or send anything to the
            // remote. The destructive step (copy_selection) keeps
            // EVERY_CALL because it writes to the system clipboard.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Start ${args.optString("mode", "CHARACTER")} selection at row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> startSelection(args) },

        "extend_selection" to ToolHandler(
            description = "Move the selection's end anchor to (row, col) in an active terminal session. Equivalent to dragging the selection handle to that cell. Pairs with start_selection — call start_selection first to set the anchor, then extend_selection to move the far end. Does NOT scroll the viewport — use drag_selection_to to extend a selection past the top or bottom of the viewport into scrollback.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID.", required = true)
                integer("row", "Viewport-relative row, 0 = top.", required = true)
                integer("col", "Column, 0 = leftmost.", required = true)
            },
            // ONCE_PER_SESSION for the same reason as start_selection —
            // pure UI-state move, the clipboard-touching follow-up
            // (copy_selection) gates separately.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Extend selection to row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> extendSelection(args) },

        "drag_selection_to" to ToolHandler(
            description = "Drag the selection's end anchor toward (toRow, toCol), auto-scrolling the viewport into scrollback (or back toward live) when toRow lies outside [0, rows-1]. Mirrors the Compose drag gesture that runs when the finger crosses the top or bottom edge zone: each row of out-of-viewport target is one ScrollController.scrollBy(±1) paired with one shiftSelectionStartByRows(±1) in lockstep. toRow < 0 scrolls back |toRow| rows (end anchor lands at viewport row 0). toRow >= rows scrolls forward toRow-(rows-1) rows (end anchor lands at viewport row rows-1). toRow in [0, rows-1] is equivalent to extend_selection. Clamps at the live screen and at scrollback.size; the response's `clamped` field indicates whether the full requested distance was achieved. Returns the resolved selection range, the new scrollbackPosition, the number of scroll steps actually taken (signed), the clamp flag, and the selection text via SelectionController.getSelectedText (libvterm softWrapped-aware, scrollback-aware).",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID with an attached terminal tab.", required = true)
                integer("toRow", "Target row. May be negative (above viewport top — drag into scrollback) or >= rows (below viewport bottom — drag toward live).", required = true)
                integer("toCol", "Target column.", required = true)
            },
            // Same rationale as start_selection / extend_selection — pure
            // UI-state moves, the clipboard-touching follow-up gates
            // separately on copy_selection.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Drag selection toward row=${args.optInt("toRow")} col=${args.optInt("toCol")} (scrolls viewport into scrollback as needed)?" },
        ) { args -> dragSelectionTo(args) },

        "copy_selection" to ToolHandler(
            description = "Copy the current selection to the system clipboard and return the copied text. Goes through Haven's smart-copy interceptor (TUI border-strip + soft-wrap rejoin). Clears the selection after copying. Returns { text }.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID with a current selection.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Copy current terminal selection to clipboard?" },
        ) { args -> copySelection(args) },

        "tap_terminal" to ToolHandler(
            description = "Simulate a tap inside an active terminal session at (row, col). When the user has Settings → Terminal → Tap to move cursor on supported prompts enabled, and the tap lands on a row carrying an OSC 133 COMMAND_INPUT segment with no matching COMMAND_FINISHED, Haven dispatches arrow keys so the readline cursor lands at the tapped column. Returns { handled, deltaCols, dispatched } describing what happened. handled=false means no OSC 133 prompt at the tap row — falls through silently.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID.", required = true)
                integer("row", "Viewport-relative row.", required = true)
                integer("col", "Column.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Tap terminal at row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> tapTerminal(args) },

        "scroll_terminal" to ToolHandler(
            description = "Scroll an active terminal session's viewport by N lines. Positive lines = back into scrollback (older content); negative lines = toward the live screen. Clamps at 0 (live) and scrollback.size. Returns { scrollbackPosition } — the new position after clamping.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID with an attached terminal tab.", required = true)
                integer("lines", "Lines to scroll. Positive = into scrollback, negative = toward live.", required = true)
            },
            // ONCE_PER_SESSION: pure view-state change — no PTY input,
            // no clipboard write, fully reversible by the user.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optInt("lines")
                if (n >= 0) "Scroll terminal $n lines into scrollback?"
                else "Scroll terminal ${-n} lines toward live screen?"
            },
        ) { args -> scrollTerminal(args) },

        "drag_terminal" to ToolHandler(
            description = "Drive a synthetic touch-drag through the terminal's REAL pointer pipeline — the same awaitEachGesture handler a physical finger feeds. Unlike start_selection / extend_selection / drag_selection_to, which mutate the selection model directly, this exercises the actual gesture code: long-press classification, drag-extend, edge-zone detection, and the held-still auto-repeat edge-scroll ticker. The gesture is: touch-down at path[0]; hold still for pressMs (long enough for the long-press selection timeout, ~500ms, to fire); move through path[1..] one stepMs apart; hold still at path.last() for holdMs (the window the edge-scroll ticker runs in); lift. Rows are viewport-relative; out-of-viewport rows (e.g. -3, or rows beyond the bottom) still map to valid pixels so a path can target the top/bottom edge zone. Blocks until the gesture completes (~pressMs + path*stepMs + holdMs). Requires the session's terminal tab to be the foreground tab (the gesture injector mounts with the Composable). Returns { sessionId, cells, approxDurationMs }. Verify the effect with get_selection + read_terminal_snapshot afterwards.",
            inputSchema = objectSchema {
                string("sessionId", "Active session ID; its terminal tab must be the foreground tab.", required = true)
                objectArray("path", "Ordered cells to traverse. Each element is { row, col } (viewport-relative; row may be negative or >= rows to reach the edge zones). path[0] = touch-down, last = lift. Must be non-empty; a single cell is a long-press with no drag.", required = true) {
                    integer("row", required = true)
                    integer("col", required = true)
                }
                integer("pressMs", "Initial still-hold before the first move. Default 900. Must clear the long-press timeout (~500ms).")
                integer("stepMs", "Delay between successive move events. Default 30.")
                integer("holdMs", "Still-hold at the final cell before lifting — the edge-scroll ticker's window. Default 1000.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = args.optJSONArray("path")?.length() ?: 0
                "Inject a synthetic $n-cell touch-drag into the terminal of session ${args.optString("sessionId").take(8)}…? (drives the real gesture handler)"
            },
        ) { args -> dragTerminal(args) },

        "open_developer_settings" to ToolHandler(
            description = "Open Android's Developer Options screen via ACTION_APPLICATION_DEVELOPMENT_SETTINGS so the user can flip Wireless debugging or other developer toggles. Tap-equivalent — the screen opens but no setting is changed without the user touching it.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> openDeveloperSettings() },

        "install_apk_from_url" to ToolHandler(
            description = "Download an APK from a URL and install it on the device. Validates the URL synchronously, then downloads + installs in the BACKGROUND and returns {pending:true, staging:true} immediately — a large APK on a slow link would otherwise outlast the request timeout (#331). Poll get_app_info: `activeInstall` carries the live phase (connecting/downloading/installing) and bytes/totalBytes while in flight; `lastInstall` carries the terminal outcome. With Shizuku running and granted, install is silent via `pm install`; without it a 'tap to install' prompt/notification appears on-device. A truncated transfer (dropped connection) is rejected against the advertised Content-Length rather than staged — a partial APK still passes the zip-magic check and would fail on-device with 'problem parsing the package', so `lastInstall` reports the short read instead. Useful for agent-driven self-update or sideloading over VPN where wireless ADB isn't reachable. NOTE: Android's network-security policy blocks cleartext http:// to anything but localhost, so an http:// URL on the LAN (e.g. a workstation IP) is rejected — use https://, or install_apk_from_backend (SFTP/rclone/Reticulum) which carries no cleartext.",
            inputSchema = objectSchema {
                string("url", "http(s) URL pointing at a signed APK file. Should resolve to APK bytes (no HTML wrapper).", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "INSTALL APK from ${args.optString("url").take(60)} — runs as code on this device. Only allow trusted sources."
            },
        ) { args -> installApkFromUrl(args) },

        "install_apk_from_backend" to ToolHandler(
            description = "Install an APK from a path on any connected backend (local, SSH/SFTP, SMB, rclone, Reticulum). Streams APK bytes via the existing FileBackend abstraction. Same Shizuku/system-installer fallback as install_apk_from_url. Because backend transfers can be slow (a big APK over SFTP/rclone/Reticulum), this validates synchronously (missing file, directory, size cap → immediate error) then streams + installs in the background, returning {pending:true, staging:true} right away rather than blocking past the request timeout. Poll get_app_info: `activeInstall` has live phase/bytes while in flight (#331), `lastInstall` the terminal outcome (and /mcp reconnect if Haven is updating itself). Gated by Settings → Agent endpoint → \"Allow agents to read file contents\" and confirmed per-call.",
            inputSchema = objectSchema {
                string("profileId", "Connection profile ID, or 'local' for the device filesystem.", required = true)
                string("path", "Absolute path to the APK file on the chosen backend.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "INSTALL APK from ${args.optString("path").take(60)} on profile ${args.optString("profileId")} — runs as code on this device. Only allow trusted sources."
            },
        ) { args -> installApkFromBackend(args) },

        "enable_wireless_adb" to ToolHandler(
            description = "Turn on Android's Wireless debugging (`adb connect` over WiFi) by setting `adb_wifi_enabled=1` via Shizuku. Requires Shizuku to be running and Haven to have its permission granted. NOTE: on Android 11+, a host that has never paired with this device must still complete the pairing-code flow manually — this tool cannot bypass that. For an already-paired host (the common case after a phone reboot) flipping the flag is enough.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Enable Wireless debugging on this device?" },
        ) { _ -> enableWirelessAdb() },

        "start_adb_pairing" to ToolHandler(
            description = "Pair a NEW workstation with this device's adb, without the user hunting for a port (#575). Turns wireless debugging on, opens Android's system pairing dialog, and discovers the pairing listener over mDNS (`_adb-tls-pairing._tcp`) — that service exists ONLY while the dialog is open and gets a fresh ephemeral port every time, which is why a port noted once is always stale and why `adb connect`'s port is the wrong one to pair against. Returns { host, port, pairCommand, wirelessDebuggingScreenOpened, codePromptShown }. The 6-digit code is NOT returned and cannot be: the system generates and draws it, and no API exposes it to an app — read it off the dialog. Haven posts a direct-reply notification so the code can be typed on-device without leaving the dialog; collect it with await_adb_pairing_code, or ignore it and pair from the workstation with the returned command. A notification rather than a floating window because Settings sets HIDE_NON_SYSTEM_OVERLAY_WINDOWS, which hides any app overlay drawn over it — the reply field is system-drawn, so it is unaffected and needs no permission beyond notifications. Shizuku only helps with the wireless-debugging flag and is optional.",
            inputSchema = objectSchema {
                integer("timeoutMs", "How long to wait for the pairing service to appear, 1000-120000. Default 30000. The clock starts before the dialog opens, since the service can appear immediately.")
                boolean("showPrompt", "Post the direct-reply notification so the code can be typed on-device. Default true; pass false to pair from the workstation instead.")
            },
            // Opens a system dialog and turns on a debugging transport — the
            // user must see this coming, and it is a once-per-workstation
            // action so a prompt is not friction the way terminal input is.
            consentLevel = ConsentLevel.EVERY_CALL,
            // Name the tap before the screen appears. What opens is Android's
            // Wireless debugging SCREEN; the pairing port does not exist until
            // "Pair device with pairing code" is tapped on it, so a prompt that
            // only says "opens the pairing dialog" leaves the user on a screen
            // with no idea which control matters.
            summarise = { _ ->
                "Open Wireless debugging so a computer can pair with adb? On the screen that " +
                    "appears, tap \"Pair device with pairing code\" — Haven watches for the port " +
                    "that only exists while that dialog is open."
            },
        ) { args -> startAdbPairing(args) },

        "await_adb_pairing_code" to ToolHandler(
            description = "Block until the user sends the 6-digit code from Haven's direct-reply pairing notification, then return it with a ready-to-run `adb pair <host>:<port> <code>` (#575). Pair this with start_adb_pairing: that verb opens the dialog, finds the ephemeral pairing port and posts the prompt; this one collects the reply, and the agent runs the resulting command from the workstation that wants adb — Haven cannot run it itself (that needs SPAKE2-EE over TLS-PSK). Returns { code, host, port, pairCommand }. Errors if no pairing is in progress or the wait elapsed; the port dies with the dialog, so restart the flow rather than retrying a stale one.",
            inputSchema = objectSchema {
                integer("timeoutMs", "How long to wait for the code, 1000-300000. Default 120000 — this is a human typing.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> awaitAdbPairingCode(args) },

        "read_logcat" to ToolHandler(
            description = "Read recent Android system log lines via Shizuku, so the agent can observe foreign-app behaviour (network calls, crashes, lifecycle) during F-Droid tester reviews. Requires Shizuku running + granted (no separate READ_LOGS grant on Haven — logcat is read as Shizuku's shell uid, which already has the permission). Optional `packageName` resolves to a `--uid` filter via `pm list packages -U`; combine with `filter` for tag-level narrowing. Returns the raw logcat block; the agent parses it. `lines` is capped at 5000 and the response payload is capped at 256 KiB (truncated:true when either limit hits). Use this whenever an MR review needs log-level observation of a non-Haven app — the Haven local shell's Alpine proot can't reach /system/bin/logcat.",
            inputSchema = objectSchema {
                integer("lines", "Number of recent lines to return (default 200, capped at 5000). Maps to logcat -t.")
                string("filter", "Standard logcat filter expression, e.g. 'NOVA:V *:S' to keep only NOVA-tagged lines, or '*:E' to keep only errors. Appended verbatim to the logcat command.")
                string("packageName", "Filter to this app only. Resolved to '--uid <uid>' via 'pm list packages -U'. Errors out if the package is not installed.")
                integer("pid", "Filter to this process pid only. Maps to logcat --pid <pid>. Use when you already have the pid (e.g. from a previous capture or pgrep).")
                string("since", "Only return lines after this point. Pass a logcat timestamp ('MM-DD HH:MM:SS.SSS') or an epoch-ms integer. Maps to logcat -T.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pkg = args.optString("packageName", "").ifEmpty { null }
                val pid = if (args.has("pid")) args.optInt("pid") else null
                val scope = pkg ?: pid?.let { "pid $it" } ?: "system-wide"
                "Allow agent to read Android logs ($scope) via Shizuku for this session?"
            },
        ) { args -> readLogcat(args) },

        "expose_adb" to ToolHandler(
            description = "Make the device's adb reachable from the workstation over 4G even with a system VPN active. Enables classic adb-over-TCP on a loopback port (default 5555) via Shizuku — no per-host pairing — then reverse-forwards 127.0.0.1:<port> over the existing MCP tunnel. Because the only phone-side hop is loopback (which Android never routes through a VpnService), adb stays reachable through any VPN. On the workstation: `adb connect localhost:<port>`. Requires Shizuku running + granted. Use install_apk_from_url/_from_backend for installs that don't need a full adb connection. Tear down with unexpose_adb.",
            inputSchema = objectSchema {
                integer("port", "Loopback adb-over-TCP port to expose (default 5555).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Expose full adb device control to the workstation over the MCP tunnel?" },
        ) { args -> exposeAdb(args) },

        "unexpose_adb" to ToolHandler(
            description = "Tear down expose_adb: remove the adb reverse forward from the MCP tunnel and disable adb-over-TCP on the device (returns adbd to USB-only). Safe to call even if adb wasn't exposed.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Disable adb exposure on this device?" },
        ) { _ -> unexposeAdb() },
    )

    /** Return the list of tool definitions for MCP `tools/list`. */
    // ---- Guest MCP aggregation (WS3) ----
    // Tools from running guest-resident MCP servers (isMcp guest services)
    // are surfaced through Haven's own MCP, namespaced `guest_<svc>_<tool>`,
    // so the agent connects only to Haven. Discovery is lazy + TTL-cached;
    // McpServer is request/response-only (no SSE push), so guest tools appear
    // on the next tools/list rather than via a listChanged notification.
    private val guestClients = java.util.concurrent.ConcurrentHashMap<String, GuestMcpClient>()
    private data class GuestToolRef(val serviceId: String, val remoteName: String, val def: JSONObject)

    @Volatile private var guestToolCache: Map<String, GuestToolRef> = emptyMap()
    @Volatile private var guestToolCacheAt = 0L
    private val guestToolCacheTtlMs = 8_000L

    /** Force the next [guestTools] call to re-discover (after register/start/stop). */
    fun invalidateGuestTools() { guestToolCacheAt = 0L }

    private fun sanitizeNs(s: String): String = s.replace(Regex("[^A-Za-z0-9]"), "_")

    /** Namespaced tools aggregated from running guest MCP servers (TTL-cached). */
    private fun guestTools(): Map<String, GuestToolRef> {
        val now = System.currentTimeMillis()
        if (now - guestToolCacheAt < guestToolCacheTtlMs) return guestToolCache
        val gsm = localSessionManager.guestServiceManager
        val running = try { gsm.runningMcpServices() } catch (e: Exception) { emptyList() }
        val liveIds = running.map { it.id }.toSet()
        guestClients.keys.retainAll { it in liveIds }
        val out = LinkedHashMap<String, GuestToolRef>()
        for (spec in running) {
            val client = guestClients.getOrPut(spec.id) {
                GuestMcpClient("http://127.0.0.1:${spec.port}${spec.mcpPath}")
            }
            val defs = try {
                client.listTools()
            } catch (e: Exception) {
                android.util.Log.w("McpTools", "guest MCP ${spec.id} listTools failed: ${e.message}")
                continue
            }
            val ns = sanitizeNs(spec.id)
            for (d in defs) {
                val remote = d.optString("name").ifBlank { continue }
                val nsName = "guest_${ns}_$remote"
                out[nsName] = GuestToolRef(
                    spec.id, remote,
                    JSONObject().apply {
                        put("name", nsName)
                        put("description", "[${spec.label}] " + d.optString("description"))
                        put("inputSchema", d.optJSONObject("inputSchema") ?: JSONObject().apply { put("type", "object") })
                    },
                )
            }
        }
        guestToolCache = out
        guestToolCacheAt = now
        return out
    }

    fun definitions(): List<JSONObject> {
        // #755: tools the user switched off leave the advertised surface
        // entirely — the context saving is the point, so a disabled tool must
        // not appear in tools/list at all, not merely fail when called.
        val disabled = disabledToolSnapshot
        val local = tools.filterNot { (name, _) -> name in disabled }.map { (name, handler) ->
            JSONObject().apply {
                put("name", name)
                put("description", handler.description)
                put("inputSchema", handler.inputSchema)
            }
        }
        val guest = guestTools().values.map { it.def }
        return local + guest
    }

    private fun firstSentenceSummary(description: String): String {
        val trimmed = description.trim()
        val endIdx = trimmed.indexOf(". ").let { if (it >= 0) it + 1 else trimmed.length }
        val sentence = trimmed.substring(0, endIdx).trim()
        return if (sentence.length <= 160) sentence else sentence.take(157).trimEnd() + "…"
    }

    private fun categoryTitle(cat: sh.haven.core.data.agent.McpToolCategory): String = when (cat) {
        sh.haven.core.data.agent.McpToolCategory.TERMINAL -> "Terminal"
        sh.haven.core.data.agent.McpToolCategory.CONNECTIONS -> "Connections"
        sh.haven.core.data.agent.McpToolCategory.FILES -> "Files"
        sh.haven.core.data.agent.McpToolCategory.DESKTOP -> "Desktop"
        sh.haven.core.data.agent.McpToolCategory.GUEST -> "Linux guest"
        sh.haven.core.data.agent.McpToolCategory.USB -> "USB and serial"
        sh.haven.core.data.agent.McpToolCategory.KEYS -> "Keys and credentials"
        sh.haven.core.data.agent.McpToolCategory.MAIL -> "Mail"
        sh.haven.core.data.agent.McpToolCategory.RCLONE -> "Cloud storage"
        sh.haven.core.data.agent.McpToolCategory.SENSES -> "Sensors and location"
        sh.haven.core.data.agent.McpToolCategory.AGENT -> "Agent and device control"
        sh.haven.core.data.agent.McpToolCategory.OTHER -> "Other"
    }

    private fun categoryOverview(cat: sh.haven.core.data.agent.McpToolCategory): String = when (cat) {
        sh.haven.core.data.agent.McpToolCategory.TERMINAL ->
            "SSH, Mosh, ET, serial & PRoot shells, scrollback, selection, and clipboard."
        sh.haven.core.data.agent.McpToolCategory.CONNECTIONS ->
            "Saved connection profiles, SSH -L/-R/-D forwards, WireGuard/Tailscale/AnyConnect tunnels, port knock/SPA, and headless run_command."
        sh.haven.core.data.agent.McpToolCategory.FILES ->
            "SFTP/SCP, SMB, local files, FFmpeg media transcode, HLS streaming, and background directory watches."
        sh.haven.core.data.agent.McpToolCategory.DESKTOP ->
            "Remote VNC/RDP/SPICE/GLKVM & local Wayland/PRoot desktops, RapidOCR + 2D k-d tree spatial lookup (inspect_desktop_tab, tap_desktop_text), mouse/drag/keyboard input, and recentUserInteractions."
        sh.haven.core.data.agent.McpToolCategory.GUEST ->
            "On-device PRoot Linux distros, app packs, audio bridge, guest services, and guest file I/O."
        sh.haven.core.data.agent.McpToolCategory.USB ->
            "Raw USB devices, USB/IP network export, ext4/LUKS USB drives via UML VM, Bluetooth/BLE/USB serial bridges."
        sh.haven.core.data.agent.McpToolCategory.KEYS ->
            "SSH keys (software & StrongBox/TEE hardware-backed), known_hosts, Step-CA certificates, TOTP codes, and age encryption."
        sh.haven.core.data.agent.McpToolCategory.MAIL ->
            "ProtonMail & IMAP/SMTP mailboxes, message search/compose, and inbound Mail Rules automation."
        sh.haven.core.data.agent.McpToolCategory.RCLONE ->
            "60+ cloud storage providers via rclone remotes, directory listing, and sync profiles."
        sh.haven.core.data.agent.McpToolCategory.SENSES ->
            "Phone battery/thermal/network state, motion/environment sensors, single-frame camera capture, and precise GPS/NTP."
        sh.haven.core.data.agent.McpToolCategory.AGENT ->
            "App info, native crash tombstones, process exit reasons, logcat, standing policies, and Haven UI self-drive (capture_haven_ui, dump_haven_ui, tap_haven_ui)."
        sh.haven.core.data.agent.McpToolCategory.OTHER ->
            "Uncategorised tools."
    }

    private fun categoryEntryTools(cat: sh.haven.core.data.agent.McpToolCategory): List<String> = when (cat) {
        sh.haven.core.data.agent.McpToolCategory.TERMINAL ->
            listOf("list_sessions", "read_terminal_snapshot", "send_terminal_input", "await_turn")
        sh.haven.core.data.agent.McpToolCategory.CONNECTIONS ->
            listOf("list_connections", "connect_profile", "run_command", "list_tunnels")
        sh.haven.core.data.agent.McpToolCategory.FILES ->
            listOf("list_directory", "upload_file", "convert_file", "serve_file")
        sh.haven.core.data.agent.McpToolCategory.DESKTOP ->
            listOf("list_desktop_sessions", "inspect_desktop_tab", "tap_desktop_text", "tap_desktop_tab", "type_desktop_text", "capture_desktop_tab")
        sh.haven.core.data.agent.McpToolCategory.GUEST ->
            listOf("list_distros", "run_in_proot", "read_guest_file", "write_guest_file")
        sh.haven.core.data.agent.McpToolCategory.USB ->
            listOf("list_usb_devices", "start_usbip_export", "open_usb_drive", "bridge_serial_to_tcp")
        sh.haven.core.data.agent.McpToolCategory.KEYS ->
            listOf("list_ssh_keys", "generate_ssh_key", "deploy_key", "generate_totp_code")
        sh.haven.core.data.agent.McpToolCategory.MAIL ->
            listOf("list_mail_messages", "read_mail_message", "send_mail", "list_mail_rules")
        sh.haven.core.data.agent.McpToolCategory.RCLONE ->
            listOf("list_rclone_remotes", "list_rclone_directory", "start_rclone_sync")
        sh.haven.core.data.agent.McpToolCategory.SENSES ->
            listOf("get_device_state", "read_sensors", "get_location_precise", "capture_camera_frame")
        sh.haven.core.data.agent.McpToolCategory.AGENT ->
            listOf("get_app_info", "capture_haven_ui", "dump_haven_ui", "tap_haven_ui")
        sh.haven.core.data.agent.McpToolCategory.OTHER -> emptyList()
    }

    private fun consentShortTag(level: ConsentLevel): String = when (level) {
        ConsentLevel.NEVER -> "no prompt"
        ConsentLevel.ONCE_PER_SESSION -> "once/session"
        ConsentLevel.EVERY_CALL -> "every call"
    }

    /**
     * Build a top-level capability summary + collapsed `<details>` tree of enabled
     * tools grouped by [sh.haven.core.data.agent.McpToolCategory] (#755). Included in
     * `initialize.instructions` (and served at `mcp://haven/tools`) so an agent sees
     * a structured subsystem overview first and can expand `mcp://haven/tools/<category>`
     * or read individual lazy tool schemas only when a category is relevant.
     */
    fun buildInstructionsTree(includeCollapsedToolLists: Boolean = true): String {
        val disabled = disabledToolSnapshot
        val enabledByCat = tools.entries
            .filterNot { (name, _) -> name in disabled }
            .groupBy { (name, h) -> h.category ?: sh.haven.core.data.agent.McpToolCategory.of(name) }
        val order = sh.haven.core.data.agent.McpToolCategory.entries
            .sortedBy { if (it == sh.haven.core.data.agent.McpToolCategory.OTHER) 1 else 0 }

        return buildString {
            appendLine(
                "Haven is a mobile thin-client OS for distributed compute, storage, and presence. " +
                    "Use these tools to inspect and drive the user's saved connections, active sessions, " +
                    "remote/local desktops, Linux guests, USB/serial devices, email, and cloud storage " +
                    "without disturbing the UI they're looking at.",
            )
            appendLine()
            appendLine("## Enabled MCP Capability Categories")
            appendLine(
                "Start from this top-level map. To expand full parameter schemas and consent levels for " +
                    "a category on demand, read its MCP resource (`mcp://haven/tools/<category>`) or open " +
                    "only the specific tool schema you need:",
            )
            appendLine()
            for (cat in order) {
                val entries = enabledByCat[cat].orEmpty().sortedBy { it.key }
                if (entries.isEmpty()) continue
                val slug = cat.name.lowercase()
                val enabledNames = entries.map { it.key }.toSet()
                val entryPoints = categoryEntryTools(cat).filter { it in enabledNames }
                val entrySuffix = if (entryPoints.isNotEmpty()) {
                    " Key tools: " + entryPoints.joinToString(", ") { "`$it`" } + "."
                } else ""
                appendLine(
                    "- **${categoryTitle(cat)}** (${entries.size} tools, `mcp://haven/tools/$slug`) — " +
                        "${categoryOverview(cat)}$entrySuffix",
                )
            }
            if (includeCollapsedToolLists) {
                appendLine()
                appendLine("## Collapsed Tool Tree by Category")
                for (cat in order) {
                    val entries = enabledByCat[cat].orEmpty().sortedBy { it.key }
                    if (entries.isEmpty()) continue
                    val slug = cat.name.lowercase()
                    appendLine("<details>")
                    appendLine("<summary><b>${categoryTitle(cat)}</b> (${entries.size} tools · <code>mcp://haven/tools/$slug</code>)</summary>")
                    appendLine()
                    for ((name, handler) in entries) {
                        appendLine(
                            "- `$name` [${consentShortTag(handler.consentLevel)}] — " +
                                firstSentenceSummary(handler.description),
                        )
                    }
                    appendLine("</details>")
                }
            }
        }.trimEnd()
    }

    /**
     * MCP resources for the collapsed tool tree (`mcp://haven/tools`) and per-category
     * expanded reference pages (`mcp://haven/tools/<category>`). Only categories with at
     * least one enabled tool are listed.
     */
    fun listToolCategoryResources(): List<JSONObject> {
        val disabled = disabledToolSnapshot
        val enabledByCat = tools.entries
            .filterNot { (name, _) -> name in disabled }
            .groupBy { (name, h) -> h.category ?: sh.haven.core.data.agent.McpToolCategory.of(name) }
        val order = sh.haven.core.data.agent.McpToolCategory.entries
            .sortedBy { if (it == sh.haven.core.data.agent.McpToolCategory.OTHER) 1 else 0 }

        return buildList {
            add(
                JSONObject().apply {
                    put("uri", "mcp://haven/tools")
                    put("name", "Haven MCP tool tree (collapsed overview)")
                    put(
                        "description",
                        "Top-level summary and collapsed category tree of all currently enabled Haven MCP tools. " +
                            "Read this first to see what Haven can do without loading all tool schemas into context.",
                    )
                    put("mimeType", "text/markdown")
                },
            )
            for (cat in order) {
                val entries = enabledByCat[cat].orEmpty()
                if (entries.isEmpty()) continue
                val slug = cat.name.lowercase()
                add(
                    JSONObject().apply {
                        put("uri", "mcp://haven/tools/$slug")
                        put("name", "Haven MCP tools: ${categoryTitle(cat)} (${entries.size})")
                        put(
                            "description",
                            "Expanded documentation, consent levels, and argument schemas for the ${entries.size} " +
                                "enabled tools in the ${categoryTitle(cat)} category: ${categoryOverview(cat)}",
                        )
                        put("mimeType", "text/markdown")
                    },
                )
            }
        }
    }

    /**
     * Render `mcp://haven/tools` or `mcp://haven/tools/<category>` as Markdown, or return
     * null when [uri] does not match a known tool-tree resource.
     */
    fun readToolCategoryResource(uri: String): String? {
        if (uri == "mcp://haven/tools") {
            return buildInstructionsTree(includeCollapsedToolLists = true)
        }
        if (!uri.startsWith("mcp://haven/tools/")) return null
        val slug = uri.removePrefix("mcp://haven/tools/").trim().lowercase()
        val cat = sh.haven.core.data.agent.McpToolCategory.entries
            .firstOrNull { it.name.lowercase() == slug } ?: return null
        val disabled = disabledToolSnapshot
        val entries = tools.entries
            .filterNot { (name, _) -> name in disabled }
            .filter { (name, h) -> (h.category ?: sh.haven.core.data.agent.McpToolCategory.of(name)) == cat }
            .sortedBy { it.key }

        return buildString {
            appendLine("# ${categoryTitle(cat)} (${entries.size} enabled tools)")
            appendLine()
            appendLine(categoryOverview(cat))
            appendLine()
            if (entries.isEmpty()) {
                appendLine("_All tools in this category are currently disabled in Settings → Agent endpoint → MCP tools._")
            } else {
                for ((name, handler) in entries) {
                    appendLine("## `$name` · ${consentShortTag(handler.consentLevel)}")
                    appendLine()
                    appendLine(handler.description.trim())
                    val props = handler.inputSchema.optJSONObject("properties")
                    val reqArr = handler.inputSchema.optJSONArray("required")
                    val requiredSet = buildSet {
                        if (reqArr != null) {
                            for (i in 0 until reqArr.length()) add(reqArr.getString(i))
                        }
                    }
                    if (props != null && props.length() > 0) {
                        appendLine()
                        appendLine("Arguments:")
                        val keys = props.keys().asSequence().toList().sorted()
                        for (k in keys) {
                            val p = props.optJSONObject(k) ?: continue
                            val type = p.optString("type", "any")
                            val reqTag = if (k in requiredSet) ", required" else ""
                            val desc = p.optString("description", "")
                            appendLine("- `$k` ($type$reqTag) — $desc")
                        }
                    }
                    appendLine()
                }
            }
        }.trimEnd()
    }

    /**
     * If tool [name] sits behind a Settings capability toggle that is
     * currently OFF, the error message to return; otherwise null
     * (#mcp-backbone Stage 5). Evaluated before consent so a disabled tool
     * fails cleanly without prompting. Driven by [ToolHandler.capability],
     * so the transport no longer matches tool names — a new gated tool sets
     * the field and, only if it needs a brand-new capability, adds one arm
     * here. Guest-proxied tools are never capability-gated.
     */
    suspend fun capabilityDenial(name: String): String? {
        val cap = tools[name]?.capability ?: return null
        val enabled = when (cap) {
            AgentCapability.FILE_READ -> preferencesRepository.agentAllowFileRead.first()
            AgentCapability.TERMINAL_INPUT_QUEUE -> preferencesRepository.agentAllowTerminalInputQueue.first()
        }
        if (enabled) return null
        return when (cap) {
            AgentCapability.FILE_READ ->
                "agent file read is disabled — enable in Settings → Agent endpoint"
            AgentCapability.TERMINAL_INPUT_QUEUE ->
                "queue_terminal_input is disabled — enable in Settings → Agent endpoint → " +
                    "Allow agents to queue terminal input"
        }
    }

    /**
     * Look up the consent gating metadata for a tool. Returns null for an
     * unknown tool (the dispatcher will surface that as an MCP error
     * regardless). Exposed to [McpServer] so consent prompting happens
     * before the handler runs. Proxied guest-MCP tools get a single
     * once-per-session gate keyed to the guest service.
     */
    fun consentFor(name: String): ToolConsent? {
        tools[name]?.let { return ToolConsent(level = it.consentLevel, summary = it.summarise) }
        val ref = guestToolCache[name] ?: guestTools()[name] ?: return null
        val label = localSessionManager.guestServiceManager.registered()
            .firstOrNull { it.id == ref.serviceId }?.label ?: ref.serviceId
        return ToolConsent(level = ConsentLevel.ONCE_PER_SESSION) {
            "Call guest MCP tool '${ref.remoteName}' on '$label'"
        }
    }

    /**
     * Per-call context that handlers can read. McpServer sets
     * [currentClientHint] right before invoking [call]; handlers like
     * `queue_terminal_input` consult it to thread the calling MCP
     * client's name through to the AgentConsentManager pre-delivery
     * prompt. Volatile because, while the JSON-RPC dispatcher is
     * sequential per connection, multiple HTTP connections can race —
     * the worst case is a stale hint, which is benign (the prompt
     * just shows the wrong client name).
     */
    @Volatile
    private var currentClientHint: String? = null

    /**
     * Which listener accepted the socket this call arrived on, set alongside
     * [currentClientHint]. Reported by get_app_info as `mcpCarriers.servedVia`:
     * the carrier list describes what is *configured*, and a reader who sees
     * `near.active = false` next to a call that plainly succeeded has no way
     * to tell which transport actually carried it. Same volatility caveat as
     * [currentClientHint] — a stale value only mislabels a report.
     */
    @Volatile
    private var currentOrigin: String? = null

    /**
     * Terminal outcome of the most recent backgrounded backend install
     * (install_apk_from_backend returns `pending` and finishes off-call, so the
     * result is otherwise only in logcat). Surfaced in get_app_info so an agent
     * can confirm the install landed without parsing logs. (#245 follow-up)
     */
    @Volatile
    private var lastInstallResult: JSONObject? = null

    /**
     * Live phase/progress of an in-flight backgrounded APK install (#331):
     * `{ phase: connecting|downloading|installing, bytes, totalBytes?, source }`.
     * Surfaced in get_app_info as `activeInstall` so an agent on a slow link can
     * poll the download instead of going blind after the immediate `pending`
     * ack. Null when nothing is in flight; the terminal outcome moves to
     * [lastInstallResult].
     */
    @Volatile
    private var activeInstallProgress: JSONObject? = null

    /** Call a tool by name. Throws [McpError] for bad input. */
    /**
     * Call a tool and return its result as a typed [ToolResult]
     * (#mcp-backbone Stage 5, Layer E) — the transport-facing entry point.
     * Local handlers return their [ToolResult] directly (image / structured);
     * a proxied guest-MCP tool becomes a [ToolResult.Passthrough]. No reserved
     * `__`-prefixed keys anywhere on the path.
     */
    suspend fun callTyped(
        name: String,
        arguments: JSONObject,
        clientHint: String? = null,
        origin: String? = null,
    ): ToolResult {
        currentClientHint = clientHint
        currentOrigin = origin
        // Per-connection MCP gate + activity light: if the tool targets a connection
        // (a profileId arg, or a sessionId that maps to one), refuse when the user has
        // disabled MCP for it, otherwise mark it agent-active so its row indicator lights.
        resolveTargetProfile(name, arguments)?.let { profileId ->
            if (connectionRepository.isMcpEnabled(profileId) == false) {
                throw McpError(-32603, "MCP is disabled for this connection — re-enable it from its MCP icon on the Connections screen.")
            }
            agentActivityHolder.touch(profileId)
        }
        // #755 per-tool switch: a tool the user disabled is off the surface —
        // a direct call fails with a Settings-pointing error, like the
        // capability gates, and holds regardless of loopback auto-trust
        // (it is a feature toggle, not consent). Guest-proxied tools are not
        // individually switchable (they come and go with the guest service).
        if (toolCatalog?.isDisabled(name) == true) {
            throw McpError(-32011, "'$name' is disabled — re-enable it in Settings → Agent endpoint → MCP tools")
        }
        tools[name]?.let { return it.handle(arguments) }

        // Proxied guest-MCP tool: forward to the guest server and pass its own
        // content array through verbatim as a Passthrough.
        val ref = guestToolCache[name] ?: guestTools()[name]
            ?: throw McpError(-32602, "Unknown tool: $name")
        val client = guestClients[ref.serviceId]
            ?: throw McpError(-32603, "Guest MCP '${ref.serviceId}' is not connected")
        val result = withContext(Dispatchers.IO) {
            try {
                client.callTool(ref.remoteName, arguments)
            } catch (e: Exception) {
                throw McpError(-32603, "Guest MCP call '${ref.remoteName}' failed: ${e.message}")
            }
        }
        val guestContent = result.optJSONArray("content")
        return if (guestContent != null) {
            val structured = JSONObject().apply {
                if (result.optBoolean("isError", false)) put("isError", true)
            }
            ToolResult.Passthrough(guestContent, structured)
        } else {
            ToolResult.Structured(result)
        }
    }

    /**
     * Call a tool and return only its structured [JSONObject] payload. The
     * consent-free entry point for the Mail-Rules engine (and tests) — image
     * and guest-passthrough content is dropped, which those callers never use.
     * The transport uses [callTyped].
     */
    suspend fun call(name: String, arguments: JSONObject, clientHint: String? = null): JSONObject =
        callTyped(name, arguments, clientHint).structured

    /**
     * The connection a tool call targets: an explicit profileId arg, the profile behind a
     * sessionId, or the single open desktop tab when a desktop viewer tool omits profileId.
     */
    private fun resolveTargetProfile(name: String, args: JSONObject): String? {
        args.optString("profileId").ifBlank { null }?.let { return it }
        args.optString("sessionId").ifBlank { null }?.let { sid ->
            return sessionManagerRegistry.allSessions.firstOrNull { it.sessionId == sid }?.profileId
        }
        if (name in desktopViewerTools) {
            desktopSessionRegistry.frameHandles().keys.singleOrNull()?.let { return it }
            desktopSessionRegistry.inputHandles().keys.singleOrNull()?.let { return it }
        }
        return null
    }

    private val desktopViewerTools = setOf(
        "capture_desktop_tab",
        "inspect_desktop_tab",
        "tap_desktop_tab",
        "tap_desktop_text",
        "move_desktop_mouse",
        "drag_desktop_tab",
        "scroll_desktop_tab",
        "type_desktop_text",
        "send_desktop_clipboard",
    )

    // --- Tool implementations ---

    /**
     * Native crashes from previous runs, with their tombstones.
     *
     * #509 and #517 both stalled on the same missing evidence: the reporter's
     * log ends at `Fatal signal …` and the backtrace is absent, because Haven
     * captures logcat from inside the process that just died. Recovering it on
     * the next launch turns "please reproduce this under adb" into a question
     * the app can answer about itself.
     */
    private fun getNativeCrashes(args: JSONObject): JSONObject {
        val includeTrace = if (args.has("includeTrace")) args.optBoolean("includeTrace", true) else true
        val log = sh.haven.core.data.NativeCrashLog(context)
        return JSONObject().apply {
            put("supported", log.supported)
            if (!log.supported) {
                put("note", "Requires Android 11 (API 30); this device reports API ${android.os.Build.VERSION.SDK_INT}.")
            }
            val records = log.records().sortedByDescending { it.timestampMs }
            put("count", records.size)
            put("crashes", JSONArray().apply {
                for (r in records) {
                    put(JSONObject().apply {
                        put("timestampMs", r.timestampMs)
                        put("description", r.description)
                        put("signal", r.signal)
                        put("hasTrace", r.trace != null)
                        if (includeTrace && r.trace != null) put("trace", r.trace)
                    })
                }
            })
        }
    }

    private fun getProcessExits(): JSONObject {
        val log = sh.haven.core.data.ProcessExitLog(context)
        return JSONObject().apply {
            put("supported", log.supported)
            if (!log.supported) {
                put("note", "Requires Android 11 (API 30); this device reports API ${android.os.Build.VERSION.SDK_INT}.")
            }
            // Fold in anything new before reporting — an MCP-only client may
            // ask before the Connections screen (or even the app UI) has run.
            if (log.supported) log.refresh()
            val records = log.records().sortedByDescending { it.timestampMs }
            put("count", records.size)
            put("exits", JSONArray().apply {
                for (r in records) {
                    put(JSONObject().apply {
                        put("timestampMs", r.timestampMs)
                        put("kind", r.kind.name)
                        put("importance", r.importance)
                        put("tookActiveWork", r.tookActiveWork)
                        put("description", r.description ?: JSONObject.NULL)
                    })
                }
            })
        }
    }

    private fun getAppInfo(): JSONObject = JSONObject().apply {
        put("app", "haven")
        put("version", sh.haven.app.BuildConfig.VERSION_NAME)
        put("versionCode", sh.haven.app.BuildConfig.VERSION_CODE)
        put("buildType", sh.haven.app.BuildConfig.BUILD_TYPE)
        // Loosely-advertised feature flags — what's reachable from which tool
        put("capabilities", JSONArray().apply {
            put("ssh")
            put("sftp")
            put("smb")
            put("reticulum")
            put("mosh")
            put("eternal_terminal")
            put("proot")
            // The desktop and media capabilities are reported from what the
            // build actually shipped, not asserted. The terminal flavour
            // (#510) drops these native libraries to save ~20 MB, and an
            // agent that believed a hardcoded list would keep offering
            // conversions and RDP sessions that can only fail at connect.
            // Wayland has been truthful since #469, for the same reason.
            val native = sh.haven.core.data.NativeFeatures(context)
            // rclone is probed rather than looked for: libgojni.so ships in
            // every build, but the terminal flavour's is built without the
            // rclone package (#510), so its presence proves nothing.
            if (sh.haven.rclone.bridge.RcloneBridge.available) put("rclone")
            if (native.vnc) put("vnc")
            if (native.rdp) put("rdp")
            if (native.spice) put("spice")
            if (sh.haven.core.wayland.WaylandBridge.available) put("wayland")
            if (native.ffmpeg) put("ffmpeg")
            if (native.uml) put("uml")
        })
        // #469: when the labwc lib failed to load, surface WHY over MCP —
        // the logcat line rotates out long before anyone asks, and reading
        // logcat remotely needs Shizuku. Absent when wayland is available.
        sh.haven.core.wayland.WaylandBridge.loadError?.let { put("waylandLoadError", it) }
        // Live phase/bytes of an in-flight backgrounded install (#331) — lets
        // an agent on a slow link poll the download instead of going blind.
        activeInstallProgress?.let { put("activeInstall", it) }
        // Terminal result of the last backgrounded backend install, if any —
        // lets a caller confirm a pending install landed. (#245 follow-up)
        lastInstallResult?.let { put("lastInstall", it) }
        // Which MCP carriers are actually open right now — this call itself
        // proves SOME carrier works, but an agent reasoning about
        // reliability (e.g. "why did my connection drop") benefits from
        // seeing the near (SSH) carrier's live state without a separate
        // lookup. See McpNearCarrier for what "near" means today.
        put("mcpCarriers", JSONObject().apply {
            // Which carrier actually delivered THIS call. The fields below
            // describe what is configured, which is a different question: a
            // reader seeing `near.active = false` beside a call that plainly
            // worked would otherwise have to guess how it arrived.
            put("servedVia", currentOrigin ?: JSONObject.NULL)
            val collision = mcpStatusHolder.wireguardCollision.value
            put("wireguardCollision", collision?.let { "${it.vpnInterface} @ ${it.address}" } ?: JSONObject.NULL)
            val near = mcpStatusHolder.nearCarrier.value
            put("near", JSONObject().apply {
                put("active", near.active)
                put("profileId", near.profileId ?: JSONObject.NULL)
                put("profileLabel", near.profileLabel ?: JSONObject.NULL)
            })
        })
    }

    private suspend fun listPairedClients(): JSONObject {
        val allowed = preferencesRepository.mcpAllowedClients.first().sorted()
        val bypass = preferencesRepository.mcpBypassConsentClients.first()
        val arr = JSONArray()
        for (name in allowed) {
            arr.put(JSONObject().apply {
                put("name", name)
                put("autoApprove", name in bypass)
                put("isCaller", name == currentClientHint)
            })
        }
        return JSONObject().apply {
            put("count", allowed.size)
            put("clients", arr)
        }
    }

    private suspend fun unpairMcpClient(args: JSONObject): JSONObject {
        val name = args.optString("name", "").trim()
            .ifEmpty { throw McpError(-32602, "Missing 'name' — the client to un-pair (see list_paired_clients)") }
        val allowed = preferencesRepository.mcpAllowedClients.first()
        if (name !in allowed) {
            throw McpError(-32602, "No paired client named '$name'. Call list_paired_clients for exact names.")
        }
        // removeMcpAllowedClient also drops the name from the persistent
        // auto-approve set, so this fully revokes the client's standing trust.
        preferencesRepository.removeMcpAllowedClient(name)
        val remaining = preferencesRepository.mcpAllowedClients.first().sorted()
        return JSONObject().apply {
            put("unpaired", name)
            put("remainingCount", remaining.size)
            put("remaining", JSONArray(remaining))
        }
    }

    private suspend fun queueTerminalInput(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val text = args.optString("text").also {
            if (it.isEmpty()) throw McpError(-32602, "Missing required argument: text")
        }
        val explicitSessionId = args.optString("sessionId").ifEmpty { null }
        val mcpPort = mcpPortProvider()
        val sessionId = explicitSessionId
            ?: sshSessionManager.findRemoteForwardSession(mcpPort)
            ?: run {
                // No SSH session carries the MCP tunnel. Fall back to the sole
                // open terminal so a direct-HTTP agent (no SSH -R) can still
                // drive a single terminal; with none open, give an actionable
                // "connect a profile first" error rather than the opaque
                // tunnel message that left agents looping (#213).
                val ids = terminalSessionRegistry.sessions.value.keys
                when (ids.size) {
                    0 -> throw McpError(
                        -32603,
                        "No terminal session is connected. Connect a profile first " +
                            "(list_connections then connect_profile, or open a Local Shell), " +
                            "then retry — or pass an explicit sessionId from list_sessions.",
                    )
                    1 -> ids.first()
                    else -> throw McpError(
                        -32603,
                        "No SSH session carries the MCP reverse tunnel on port $mcpPort, and " +
                            "several terminal sessions are open (${ids.joinToString(", ")}). " +
                            "Pass sessionId explicitly (list_sessions). For the auto-detection " +
                            "default to work, an SSH profile must have the MCP reverse-tunnel " +
                            "toggle on and be connected.",
                    )
                }
            }
        val promptPattern = args.optString("promptPattern").ifEmpty { DEFAULT_PROMPT_PATTERN }
        val timeoutSeconds = args.optInt("timeoutSeconds", 60).coerceIn(1, 600)
        // submitKey: see [DEFAULT_SUBMIT_KEY] for the rationale on `\r`
        // vs `\n` vs `""`. Callers override to fit the target program.
        val submitKey = if (args.has("submitKey") && !args.isNull("submitKey")) {
            args.optString("submitKey")
        } else DEFAULT_SUBMIT_KEY
        val queueId = terminalInputQueue.enqueue(
            sessionId = sessionId,
            text = text,
            promptPattern = promptPattern,
            timeoutSeconds = timeoutSeconds,
            submitKey = submitKey,
            clientHint = currentClientHint,
        )
        JSONObject().apply {
            put("queueId", queueId)
            put("sessionId", sessionId)
            put("promptPattern", promptPattern)
            put("timeoutSeconds", timeoutSeconds)
            put("submitKey", submitKey)
            put("textLength", text.length)
        }
    }

    /**
     * Backend-agnostic listing — collapses [listSftpDirectory] and
     * [listRcloneDirectory] (which stay registered as deprecated
     * aliases). Resolves the [FileBackend] from `profileId` via
     * [transportSelector], so adding a new backend lights this up
     * automatically.
     */
    private suspend fun listDirectory(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path", "/").ifEmpty { "/" }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val arr = JSONArray()
        try {
            for (entry in resolution.backend.list(path)) {
                arr.put(JSONObject().apply {
                    put("name", entry.name)
                    put("path", entry.path)
                    put("isDir", entry.isDirectory)
                    put("size", entry.size)
                    put("mtime", entry.modifiedTime)
                    put("permissions", entry.permissions)
                    entry.mimeType?.let { put("mimeType", it) }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $path: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("path", path)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    /**
     * Backend-agnostic upload via [FileBackend.writeBytes]. Loads the
     * source into memory; agents wanting streaming for large files
     * still use the SSH-only [uploadFileToSftp] until #126 stage 4+
     * promotes streaming to [FileBackend].
     */
    private suspend fun uploadFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val localPath = args.optString("localPath").ifEmpty {
            throw McpError(-32602, "Missing required argument: localPath")
        }
        val remotePath = args.optString("remotePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: remotePath")
        }
        val cacheRoot = context.cacheDir.canonicalFile
        val source = try {
            File(localPath).canonicalFile
        } catch (e: Exception) {
            throw McpError(-32602, "Cannot resolve localPath: ${e.message}")
        }
        if (!source.path.startsWith(cacheRoot.path + File.separator) && source.path != cacheRoot.path) {
            throw McpError(-32602, "localPath must be inside Haven's app cache (${cacheRoot.path})")
        }
        if (!source.exists() || !source.isFile) {
            throw McpError(-32602, "localPath does not point to a regular file")
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val data = source.readBytes()
        try {
            resolution.backend.writeBytes(remotePath, data)
        } catch (e: Exception) {
            throw McpError(-32603, "Upload failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("remotePath", remotePath)
            put("bytesUploaded", source.length())
        }
    }

    /**
     * Backend-agnostic file delete via [FileBackend.delete]. Refuses
     * directories — `delete_file` always passes `isDirectory = false`
     * so an agent that wants directory deletion has to use a separate
     * verb when one ships.
     */
    private suspend fun deleteFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        try {
            resolution.backend.delete(path, isDirectory = false)
        } catch (e: Exception) {
            throw McpError(-32603, "Delete failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("path", path)
            put("deleted", true)
        }
    }

    private suspend fun listSftpDirectory(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path", ".").ifEmpty { "." }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        val arr = JSONArray()
        try {
            session.list(path) { attrs ->
                arr.put(JSONObject().apply {
                    put("name", attrs.filename)
                    put("isDir", attrs.isDirectory)
                    put("size", attrs.size)
                    put("mtime", attrs.modifiedTimeSeconds.toLong())
                    put("permissions", attrs.permissions)
                })
                sh.haven.core.ssh.sftp.ListResult.CONTINUE
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $path: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    private suspend fun streamSftpFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        val size = try {
            session.stat(path).size
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to stat $path: ${e.message}")
        }

        val streamPort = sftpStreamServer.start()
        val urlPath = sftpStreamServer.publish(
            path = path,
            size = size,
            contentType = guessContentType(path),
            opener = { offset ->
                val s = sshSessionManager.openSftpSession(profileId)
                    ?: throw java.io.IOException("SFTP not connected for profile $profileId")
                // SftpStreamServer.Opener.open is a non-suspend fun interface,
                // called from the HTTP worker thread — runBlocking is fine here.
                runBlocking {
                    s.openInputStream(path, if (offset > 0) offset else 0L)
                }
            },
        )
        val sourceUrl = "http://127.0.0.1:$streamPort$urlPath"
        // Audit-log the transcode outcome like the Files→Stream flow does;
        // previously an MCP-triggered stream that failed left nothing to read.
        hlsStreamServer.onExit = { exitCode, stderr ->
            val status = if (exitCode == 0)
                sh.haven.core.data.db.entities.ConnectionLog.Status.CONNECTED
            else
                sh.haven.core.data.db.entities.ConnectionLog.Status.FAILED
            runCatching {
                backgroundScope.launch {
                    connectionLogRepository.logEvent(
                        profileId,
                        status,
                        details = "▶ stream: ${path.substringAfterLast('/')} — ffmpeg exit $exitCode",
                        verboseLog = stderr.takeLast(2000),
                    )
                }
            }
        }
        val hlsPort = hlsStreamServer.startFile(sourceUrl)
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("size", size)
            put("sourceUrl", sourceUrl)
            put("hlsPort", hlsPort)
            put("playlistUrl", "http://127.0.0.1:$hlsPort/stream.m3u8")
            put("playerUrl", "http://127.0.0.1:$hlsPort/")
        }
    }

    private fun stopStream(): JSONObject {
        hlsStreamServer.stop()
        return JSONObject().apply { put("stopped", true) }
    }

    /**
     * Backend-agnostic file download bridge. Resolves the profile via
     * [transportSelector], stats the path for size+mime, publishes a
     * loopback HTTP entry on [sftpStreamServer], and returns the URL.
     *
     * The capability gate ([UserPreferencesRepository.agentAllowFileRead])
     * is also enforced in [McpServer] *before* the consent prompt so a
     * disabled call fails immediately. The check here is belt-and-braces
     * for any in-process caller that might bypass the server dispatcher.
     */
    private suspend fun serveFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }

        if (!preferencesRepository.agentAllowFileRead.first()) {
            throw McpError(
                -32011,
                "agent file read is disabled — enable in Settings → Agent endpoint",
            )
        }

        val ref = publishFileLoopback(profileId, path)
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", ref.backendLabel)
            put("path", path)
            put("size", ref.size)
            put("mimeType", ref.contentType)
            put("url", ref.url)
        }
    }

    private data class LoopbackRef(
        val url: String,
        val size: Long,
        val contentType: String,
        val backendLabel: String,
    )

    /**
     * Resolve [profileId]'s backend, stat [path], publish it on
     * [sftpStreamServer] as a token-protected loopback HTTP entry, and return
     * the URL + metadata. Shared by serve_file (agent downloads) and
     * present_web (in-app WebView). Does NOT enforce the file-read capability
     * gate — callers that expose bytes to the *agent* (serve_file) check it
     * first; present_web only renders to the user.
     */
    private suspend fun publishFileLoopback(profileId: String, path: String): LoopbackRef {
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val backend = resolution.backend
        val entry = try {
            backend.stat(path)
        } catch (t: Throwable) {
            throw McpError(-32603, "Failed to stat $path: ${t.message}")
        }
        if (entry.isDirectory) {
            throw McpError(-32602, "$path is a directory; not a single file")
        }
        val contentType = guessContentType(path)
        val port = sftpStreamServer.start()
        val urlPath = sftpStreamServer.publish(
            path = path,
            size = entry.size,
            contentType = contentType,
            opener = { offset ->
                // Re-resolve on each Range request so a reconnected
                // session picks up the fresh client/channel rather than
                // capturing a stale one from publish-time.
                val live = kotlinx.coroutines.runBlocking {
                    transportSelector.resolveFileBackend(profileId)?.backend
                } ?: throw java.io.IOException("Backend disconnected for $profileId")
                kotlinx.coroutines.runBlocking { live.openInputStream(path, offset) }
            },
        )
        servedFileTracker.register(profileId, path)
        return LoopbackRef(
            url = "http://127.0.0.1:$port$urlPath",
            size = entry.size,
            contentType = contentType,
            backendLabel = backend.label,
        )
    }

    private suspend fun openConvertDialogWithArgs(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val sourcePath = args.optString("sourcePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: sourcePath")
        }
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.OpenConvertDialog(
            profileId = profileId,
            sourcePath = sourcePath,
            container = args.optString("container").ifEmpty { null },
            videoEncoder = args.optString("videoEncoder").ifEmpty { null },
            audioEncoder = args.optString("audioEncoder").ifEmpty { null },
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("sourcePath", sourcePath)
            put("container", command.container ?: JSONObject.NULL)
            put("videoEncoder", command.videoEncoder ?: JSONObject.NULL)
            put("audioEncoder", command.audioEncoder ?: JSONObject.NULL)
        }
    }

    private fun focusTerminalSession(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val command = sh.haven.core.data.agent.AgentUiCommand.FocusTerminalSession(sessionId)
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("sessionId", sessionId)
        }
    }

    private suspend fun openFileInEditor(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.OpenInEditor(
            profileId = profileId,
            path = path,
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("path", path)
        }
    }

    private suspend fun navigateSftpBrowser(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty { "/" }
        // Best-effort existence check so a typo'd profileId returns a
        // useful error instead of silently posting a command nobody
        // collects. "local" is a synthetic profile that has no DB row.
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath(
            profileId = profileId,
            path = path,
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("path", path)
        }
    }

    private fun playFile(args: JSONObject): JSONObject {
        val url = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        val explicitMime = args.optString("mimeType", "").ifEmpty { null }
        val resolvedMime = explicitMime ?: run {
            // Best-effort extension-based MIME guess so the system app
            // picker has something to filter on. Falls back to video/*
            // for typical SFTP/rclone playback.
            val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), resolvedMime)
            // Required when launching from a non-Activity context (we
            // hold the application Context here, not the current
            // Activity). Without this Android refuses to start the
            // target activity.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // For content:// URIs the granting flag lets the chosen
            // player read through Haven's FileProvider; harmless on
            // http(s) URLs.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            JSONObject().apply {
                put("dispatched", true)
                put("url", url)
                put("mimeType", resolvedMime)
            }
        } catch (e: android.content.ActivityNotFoundException) {
            throw McpError(-32603, "No installed app can play '$resolvedMime' from this URL")
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to dispatch player intent: ${e.message}")
        }
    }

    /**
     * Push an image or sound for the user to see/hear inline. References a
     * file Haven can reach — a backend `profileId` + `path` (incl. "local"
     * for the device / proot-guest cache) or a ready `url` — streams it into
     * a cache handle (so the overlay's image decoder / [MediaPlayer] read a
     * real file), and hands it to [presentationManager], which the top-of-tree
     * `PresentationHost` renders as a dismissible bottom sheet. The media
     * bytes never pass through the agent context (no base64). Fire and forget:
     * returns as soon as the item is queued, never waits on the user. See
     * [AgentPresentationManager].
     */
    private fun presentMedia(args: JSONObject): JSONObject {
        if (args.optString("dataBase64").isNotEmpty()) {
            throw McpError(
                -32602,
                "present_media no longer takes dataBase64 — reference the file instead: " +
                    "profileId (\"local\" for the device / proot-guest cache, or an SSH/SMB/rclone " +
                    "profile id) + path, or a url (e.g. from serve_file).",
            )
        }
        val caption = args.optString("caption", "").ifEmpty { null }
        val explicitMime = args.optString("mimeType", "").ifEmpty { null }
        val autoPlay = args.optBoolean("autoPlay", false)
        val url = args.optString("url", "").ifEmpty { null }
        val path = args.optString("path", "").ifEmpty { null }
        val profileId = args.optString("profileId", "local").ifEmpty { "local" }
        if (url == null && path == null) {
            throw McpError(
                -32602,
                "present_media needs a file reference: profileId + path, or url.",
            )
        }

        // Stage + enqueue OFF the MCP call's critical path so the response acks
        // immediately. Staging reads up to MAX_PRESENT_BYTES off a (possibly
        // laggy) backend; doing it inline meant a delivered image still read
        // back to the agent as a *timeout* when the read outlasted its call
        // window (serve_file, which only binds a server, never had this). The
        // overlay is the real user-facing artifact, so a staging failure is
        // logged, not surfaced — the agent already has its optimistic ack.
        presentScope.launch {
            try {
                val srcName: String
                val file: File = if (url != null) {
                    srcName = url.substringBefore('?').substringAfterLast('/')
                    downloadToPresentCache(url)
                } else {
                    srcName = path!!.substringAfterLast('/')
                    streamBackendToPresentCache(profileId, path)
                }
                // Determine MIME: explicit wins; else from the file name; else
                // sniff the head bytes for a known image signature (audio can't
                // be sniffed reliably, so it needs an extension or explicit mime).
                val head = ByteArray(32)
                val n = file.inputStream().use { it.read(head) }
                val mime = explicitMime
                    ?: guessContentType(srcName).takeIf { it.startsWith("image/") || it.startsWith("audio/") }
                    ?: sniffImageMime(if (n > 0) head.copyOf(n) else head)
                if (mime == null) {
                    file.delete()
                    logPresentFailure(profileId, url ?: path, "could not determine media type — pass mimeType")
                    return@launch
                }
                val kind = when {
                    mime.startsWith("image/") -> sh.haven.core.data.agent.PresentedMediaKind.IMAGE
                    mime.startsWith("audio/") -> sh.haven.core.data.agent.PresentedMediaKind.AUDIO
                    else -> {
                        file.delete()
                        logPresentFailure(profileId, url ?: path, "unsupported media type '$mime' (image/* and audio/* only)")
                        return@launch
                    }
                }
                presentationManager.present(
                    kind = kind,
                    filePath = file.absolutePath,
                    mimeType = mime,
                    caption = caption,
                    autoPlay = autoPlay,
                )
            } catch (t: Throwable) {
                logPresentFailure(profileId, url ?: path, t.message ?: t.toString())
            }
        }
        return JSONObject().apply { put("presented", true) }
    }

    /**
     * Log a present_media / present_web staging failure. The agent already
     * received its optimistic {presented:true} ack (the overlay is the real
     * artifact), so a failed stage is a user-visible no-op — surface it to
     * logcat, and to the connection log when it's a profile-backed read
     * (logEvent no-ops on an orphan/"local" id via its FK guard).
     */
    private suspend fun logPresentFailure(profileId: String, ref: String?, reason: String) {
        android.util.Log.w("McpTools", "present staging failed for ${ref ?: "?"}: $reason")
        runCatching {
            connectionLogRepository.logEvent(
                profileId,
                sh.haven.core.data.db.entities.ConnectionLog.Status.FAILED,
                details = "present: ${ref ?: ""} — $reason",
            )
        }
    }

    /**
     * Copy [input] into a fresh cache file (named with [ext]), capping at
     * [MAX_PRESENT_BYTES] so a referenced file can't fill the cache. The
     * cache file is owned by [AgentPresentationManager] from return — it
     * deletes it on dismissal / eviction.
     */
    private fun copyToPresentCache(input: java.io.InputStream, ext: String): File {
        val file = File(context.cacheDir, "haven-present-${System.currentTimeMillis()}.$ext")
        try {
            var total = 0L
            val buf = ByteArray(64 * 1024)
            input.use { ins ->
                file.outputStream().use { out ->
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        total += r
                        if (total > MAX_PRESENT_BYTES) {
                            throw McpError(
                                -32602,
                                "media exceeds $MAX_PRESENT_BYTES bytes — use present_app or play_file for large media",
                            )
                        }
                        out.write(buf, 0, r)
                    }
                }
            }
            return file
        } catch (t: Throwable) {
            runCatching { file.delete() }
            throw t
        }
    }

    /** Stream a file off a connected backend into a present-cache handle. */
    private suspend fun streamBackendToPresentCache(profileId: String, path: String): File {
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val backend = resolution.backend
        val entry = try {
            backend.stat(path)
        } catch (t: Throwable) {
            throw McpError(-32603, "Failed to stat $path: ${t.message}")
        }
        if (entry.isDirectory) throw McpError(-32602, "$path is a directory")
        if (entry.size > MAX_PRESENT_BYTES) {
            throw McpError(
                -32602,
                "$path is ${entry.size} bytes; caps at $MAX_PRESENT_BYTES — use present_app or play_file for large media",
            )
        }
        val ext = path.substringAfterLast('.', "").ifEmpty { "bin" }
        return copyToPresentCache(backend.openInputStream(path, 0), ext)
    }

    /** Fetch a URL (e.g. a serve_file loopback URL) into a present-cache handle. */
    private fun downloadToPresentCache(urlStr: String): File {
        val url = java.net.URL(urlStr)
        val conn = url.openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val ext = urlStr.substringBefore('?').substringAfterLast('.', "").ifEmpty { "bin" }
        return copyToPresentCache(conn.getInputStream(), ext)
    }

    /**
     * Show HTML / SVG / PDF inline. HTML/SVG are served over a loopback URL
     * and loaded in an in-app WebView; a PDF is streamed to a cache file and
     * paged by PdfRenderer (it needs a local fd). Reference by a ready `url`
     * or a backend `profileId` + `path`. Bytes never pass through the agent
     * context. Fire and forget; returns as soon as the item is queued.
     */
    private fun presentWeb(args: JSONObject): JSONObject {
        val caption = args.optString("caption", "").ifEmpty { null }
        val explicitUrl = args.optString("url", "").ifEmpty { null }
        val path = args.optString("path", "").ifEmpty { null }

        // The url case needs no staging (it already IS a URL) — present it
        // inline and return the full ack, including the url for reference.
        if (explicitUrl != null) {
            val parsedUri = try {
                java.net.URI(explicitUrl)
            } catch (_: Exception) {
                throw McpError(-32602, "present_web url is not a valid URI.")
            }
            val scheme = parsedUri.scheme?.lowercase()
            if ((scheme != "http" && scheme != "https") || parsedUri.host.isNullOrBlank()) {
                throw McpError(-32602, "present_web url must use http:// or https:// with a non-empty host.")
            }
            val mime = guessContentType(explicitUrl.substringBefore('?'))
            val id = presentationManager.presentWeb(
                url = explicitUrl,
                filePath = null,
                mimeType = mime,
                caption = caption,
            )
            return JSONObject().apply {
                put("presented", true)
                put("id", id)
                put("url", explicitUrl)
                put("mimeType", mime)
            }
        }
        if (path == null) {
            throw McpError(-32602, "present_web needs a url, or profileId + path.")
        }
        val profileId = args.optString("profileId", "local").ifEmpty { "local" }
        val mime = guessContentType(path)

        // The path case reads/serves the file off a backend; stage it OFF the
        // call's critical path (see present_media) so a slow read can't turn a
        // delivered view into a timeout. Failures are logged, not surfaced.
        presentScope.launch {
            try {
                if (mime == "application/pdf") {
                    // PdfRenderer needs a seekable local file, not a URL.
                    val pdfFile = streamBackendToPresentCache(profileId, path)
                    presentationManager.presentWeb(
                        url = null,
                        filePath = pdfFile.absolutePath,
                        mimeType = mime,
                        caption = caption,
                    )
                } else {
                    val loopbackUrl = publishFileLoopback(profileId, path).url
                    presentationManager.presentWeb(
                        url = loopbackUrl,
                        filePath = null,
                        mimeType = mime,
                        caption = caption,
                    )
                }
            } catch (t: Throwable) {
                logPresentFailure(profileId, path, t.message ?: t.toString())
            }
        }
        return JSONObject().apply { put("presented", true) }
    }

    /**
     * Post a real system notification through Haven's NotificationManager.
     *
     * Always uses the dedicated [AGENT_NOTIFICATION_CHANNEL_ID] channel so
     * the user can mute "agent test notifications" without silencing SSH /
     * desktop / renewal notifications. The channel is created lazily on
     * first call and pinned for the lifetime of the process.
     *
     * Fails early with a friendly McpError if the system-wide notification
     * toggle is off — POST_NOTIFICATIONS is declared in the manifest and
     * granted by the user during the connections flow, but on rare devices
     * (or fresh installs that skipped the prompt) it can still be denied.
     */
    private fun raiseNotification(args: JSONObject): JSONObject {
        val title = args.optString("title").ifEmpty {
            throw McpError(-32602, "title is required")
        }
        val body = args.optString("body").ifEmpty {
            throw McpError(-32602, "body is required")
        }
        val priorityStr = args.optString("priority", "default").lowercase()
        val priority = when (priorityStr) {
            "min" -> NotificationCompat.PRIORITY_MIN
            "low" -> NotificationCompat.PRIORITY_LOW
            "default", "" -> NotificationCompat.PRIORITY_DEFAULT
            "high" -> NotificationCompat.PRIORITY_HIGH
            "max" -> NotificationCompat.PRIORITY_MAX
            else -> throw McpError(
                -32602,
                "priority must be one of: min, low, default, high, max (got '$priorityStr')",
            )
        }
        val ongoing = args.optBoolean("ongoing", false)

        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) {
            throw McpError(
                -32603,
                "Notifications are disabled for Haven — grant the POST_NOTIFICATIONS permission in system settings before calling raise_notification.",
            )
        }
        ensureAgentNotificationChannel()

        val id = nextAgentNotificationId.getAndIncrement()
        // Default tap action: open Haven. Without a content intent Android
        // delivers nothing on tap (and AUTO_CANCEL never fires), leaving a
        // notification that visibly ignores the user.
        val tapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            android.app.PendingIntent.getActivity(
                context, id, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, AGENT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .apply { tapIntent?.let { setContentIntent(it) } }
            .build()

        try {
            managerCompat.notify(id, notification)
        } catch (e: SecurityException) {
            throw McpError(
                -32603,
                "Could not post notification: ${e.message ?: "permission denied"}",
            )
        }
        return JSONObject().apply {
            put("posted", true)
            put("id", id)
            put("channel", AGENT_NOTIFICATION_CHANNEL_ID)
        }
    }

    private fun ensureAgentNotificationChannel() {
        if (agentNotificationChannelEnsured) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            AGENT_NOTIFICATION_CHANNEL_ID,
            "Agent test notifications",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications posted by the MCP raise_notification tool — used by the agent to drive notification-listener / wake / DND apps during tester reviews. Mute this channel to silence agent notifications without affecting Haven's connection or renewal notifications."
        }
        nm.createNotificationChannel(channel)
        agentNotificationChannelEnsured = true
    }

    private fun ensureInstallNotificationChannel() {
        if (installNotificationChannelEnsured) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            INSTALL_NOTIFICATION_CHANNEL_ID,
            "App update install",
            // HIGH so the tap-to-install prompt can surface as a heads-up when
            // Haven is backgrounded — Android blocks a background app from
            // launching the installer directly, so the user's tap is the way in.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Tap-to-install prompts for an APK staged by install_apk_from_backend / _from_url when Shizuku isn't available for a silent install."
        }
        nm.createNotificationChannel(channel)
        installNotificationChannelEnsured = true
    }

    /**
     * Best-effort image-format detection from leading magic bytes, so
     * [presentMedia] can accept a raw image payload without an explicit
     * mimeType. Returns null for anything it doesn't recognise (including
     * all audio).
     */
    private fun sniffImageMime(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 6 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() &&
            b[2] == 0x46.toByte() -> "image/gif" // "GIF"
        b.size >= 12 && b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
            b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&
            b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
            b[10] == 0x42.toByte() && b[11] == 0x50.toByte() -> "image/webp" // "RIFF"…"WEBP"
        else -> null
    }

    private fun readTerminalScrollback(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val requested = args.optInt("maxBytes", 16384)
        val capped = requested.coerceIn(1, 256 * 1024)
        // sessionId can come from either the SSH or the local-shell
        // manager; try both rather than forcing the agent to know
        // which transport it's looking at.
        val sshBytes = sshSessionManager.readAgentScrollback(sessionId, capped)
        val bytes = sshBytes
            ?: localSessionManager.readAgentScrollback(sessionId, capped)
            ?: if (sshSessionManager.getSession(sessionId) == null &&
                localSessionManager.getActiveSession(sessionId) == null
            ) {
                // A stale id reads as "no scrollback" otherwise, which sends an
                // agent opening a tab on a session that no longer exists.
                throw McpError(
                    -32602,
                    "No live session '$sessionId' — it likely disconnected or its id " +
                        "churned on reconnect; get current session ids from list_sessions",
                )
            } else {
                throw McpError(
                    -32603,
                    "No scrollback available for session $sessionId — open a terminal tab on this session first",
                )
            }
        // Whether the underlying session has ended. Without this an agent
        // can't tell a quiet shell from a dead one — the ring keeps returning
        // the last output (e.g. frozen at the login banner) with no signal.
        val ended = if (sshBytes != null) {
            sshSessionManager.getSession(sessionId) == null
        } else {
            val ls = localSessionManager.getActiveSession(sessionId)
            ls == null || !ls.isAlive()
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("byteCount", bytes.size)
            // UTF-8 with malformed-replacement so any partial codepoint
            // at the buffer head doesn't poison the whole response.
            put("text", String(bytes, Charsets.UTF_8))
            put("ended", ended)
        }
    }

    // --- Write-tool implementations -------------------------------------

    private suspend fun uploadFileToSftp(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val localPath = args.optString("localPath").ifEmpty {
            throw McpError(-32602, "Missing required argument: localPath")
        }
        val remotePath = args.optString("remotePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: remotePath")
        }
        // Confine the source to context.cacheDir so a malicious agent
        // can't exfiltrate arbitrary device files via the upload.
        val cacheRoot = context.cacheDir.canonicalFile
        val source = try {
            File(localPath).canonicalFile
        } catch (e: Exception) {
            throw McpError(-32602, "Cannot resolve localPath: ${e.message}")
        }
        if (!source.path.startsWith(cacheRoot.path + File.separator) && source.path != cacheRoot.path) {
            throw McpError(-32602, "localPath must be inside Haven's app cache (${cacheRoot.path})")
        }
        if (!source.exists() || !source.isFile) {
            throw McpError(-32602, "localPath does not point to a regular file")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        try {
            source.inputStream().use { input ->
                session.upload(input, source.length(), remotePath) { _, _ -> }
            }
        } catch (e: Exception) {
            throw McpError(-32603, "SFTP upload failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("remotePath", remotePath)
            put("bytesUploaded", source.length())
        }
    }

    private suspend fun deleteSftpFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        try {
            // Refuse directories — `rm` and `rmdir` are different ops
            // in SFTP and the agent should pick the right verb.
            val attrs = session.stat(path)
            if (attrs.isDirectory) {
                throw McpError(-32602, "Refusing to delete directory '$path' — use a separate rmdir tool when one exists")
            }
            session.rm(path)
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "SFTP delete failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("deleted", true)
        }
    }

    // --- Terminal snapshot / selection / clipboard / preference helpers ---

    /**
     * Resolve which terminal session a terminal-driving tool should act on.
     *
     * An explicit `sessionId` is used verbatim (requireRegistryEntry then
     * tells a live session with no tab from a stale one). When the
     * caller omits it, default to the sole open terminal session. With none
     * open, throw an **actionable** error telling the agent to connect a
     * profile first — rather than the opaque "missing sessionId" result that
     * left an external agent looping with no feedback (#213). With several
     * open, ask the caller to pick one.
     */
    private fun resolveTerminalSessionId(explicit: String): String {
        if (explicit.isNotEmpty()) return explicit
        val ids = terminalSessionRegistry.sessions.value.keys
        return when (ids.size) {
            0 -> throw McpError(
                -32603,
                "No terminal session is connected. Connect a profile first " +
                    "(list_connections then connect_profile, or open a Local Shell), " +
                    "then retry — or pass an explicit sessionId from list_sessions.",
            )
            1 -> ids.first()
            else -> throw McpError(
                -32602,
                "Several terminal sessions are open (${ids.joinToString(", ")}); " +
                    "pass sessionId to pick one (see list_sessions).",
            )
        }
    }

    private fun requireRegistryEntry(sessionId: String): sh.haven.feature.terminal.agent.TerminalSessionRegistry.Entry {
        if (sessionId.isEmpty()) throw McpError(-32602, "Missing required argument: sessionId")
        return terminalSessionRegistry.get(sessionId)
            ?: if (sshSessionManager.getSession(sessionId) == null &&
                localSessionManager.getActiveSession(sessionId) == null
            ) {
                // Mirrors readTerminalScrollback: a stale id would otherwise
                // read as "open a tab", sending an agent in circles after the
                // id churned under it.
                throw McpError(
                    -32602,
                    "No live session '$sessionId' — it likely disconnected or its id " +
                        "churned on reconnect; get current session ids from list_sessions",
                )
            } else {
                throw McpError(-32603, "No registered terminal tab for session $sessionId — open a terminal tab on this session first")
            }
    }

    private fun readExitedSession(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank { null }
        val record = terminalSessionRegistry.lastExited(profileId)
            ?: return JSONObject().apply {
                put("found", false)
                put(
                    "note",
                    if (profileId == null) {
                        "No terminal session has exited since Haven started."
                    } else {
                        "No terminal session for profile $profileId has exited since Haven started."
                    },
                )
            }
        return JSONObject().apply {
            put("found", true)
            put("sessionId", record.sessionId)
            put("profileId", record.profileId ?: JSONObject.NULL)
            put("label", record.label ?: JSONObject.NULL)
            put("rows", record.rows)
            put("cols", record.cols)
            put("exitedAtMs", record.exitedAtMs)
            put("screen", org.json.JSONArray(record.screen))
        }
    }

    private fun readTerminalSnapshot(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val includeSegs = args.optBoolean("includeSemanticSegments", false)
        val maxLines = if (args.has("maxLines")) args.optInt("maxLines", Int.MAX_VALUE) else Int.MAX_VALUE
        val snap = entry.emulator.buildAgentSnapshot(
            includeSemanticSegments = includeSegs,
            maxLines = maxLines,
        )
        val scrollPos = entry.scrollController?.scrollbackPosition ?: 0
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("rows", snap.rows)
            put("cols", snap.cols)
            put("cursorRow", snap.cursorRow)
            put("cursorCol", snap.cursorCol)
            put("cursorVisible", snap.cursorVisible)
            put("terminalTitle", snap.terminalTitle)
            put("scrollbackSize", snap.scrollbackSize)
            put("scrollbackPosition", scrollPos)
            put("scrollbackEvictedCount", snap.scrollbackEvictedCount)
            put("imageAssetCount", snap.imageAssetCount)
            put("imagePlacementCount", snap.imagePlacementCount)
            if (scrollPos > 0) {
                val visible = entry.selectionController?.getVisibleLineTexts().orEmpty()
                if (visible.isNotEmpty()) {
                    put("visibleLines", JSONArray(visible))
                }
            }
            // Remote-driven terminal modes, scanned from the output stream
            // by MouseModeTracker. Null flows (headless agent shells) report
            // the inactive defaults.
            put("mouseMode", entry.mouseMode?.value ?: false)
            put("activeMouseMode", entry.activeMouseMode?.value ?: JSONObject.NULL)
            put("bracketPasteMode", entry.bracketPasteMode?.value ?: false)
            // Last-seen OSC events from this tab's OscHandler — useful for
            // asserting OSC 52 / 7 / 8 / 9 round-trips. Null when no event
            // of that type has been seen (or no OSC handler is wired).
            val osc = entry.oscHandler
            put("oscEvents", JSONObject().apply {
                put("lastClipboardSet", osc?.lastClipboardSet ?: JSONObject.NULL)
                put("lastCwd", osc?.lastCwd ?: JSONObject.NULL)
                put("lastHyperlink", osc?.lastHyperlink ?: JSONObject.NULL)
                put(
                    "lastNotification",
                    osc?.lastNotification?.let { (title, body) ->
                        JSONObject().apply {
                            put("title", title)
                            put("body", body)
                        }
                    } ?: JSONObject.NULL,
                )
            })
            put("lines", JSONArray().apply {
                snap.lines.forEach { line ->
                    put(JSONObject().apply {
                        put("text", line.text)
                        put("softWrapped", line.softWrapped)
                        if (line.images.isNotEmpty()) {
                            put("images", JSONArray().apply {
                                line.images.forEach { img ->
                                    put(JSONObject().apply {
                                        put("assetId", img.assetId)
                                        put("left", img.left)
                                        put("right", img.right)
                                        put("sourceRow", img.sourceRow)
                                        put("rows", img.rows)
                                        put("sourceCol", img.sourceCol)
                                        put("columns", img.columns)
                                        put("z", img.z)
                                    })
                                }
                            })
                        }
                        if (includeSegs) {
                            put("semanticSegments", JSONArray().apply {
                                line.semanticSegments.forEach { seg ->
                                    put(JSONObject().apply {
                                        put("startCol", seg.startCol)
                                        put("endCol", seg.endCol)
                                        put("type", seg.type)
                                        put("promptId", seg.promptId)
                                        if (seg.metadata != null) put("metadata", seg.metadata)
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
    }

    private fun getSelection(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController — the tab may not be the foreground tab")
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("active", controller.isSelectionActive)
            put("range", if (range == null) JSONObject.NULL else JSONObject().apply {
                put("startRow", range.startRow)
                put("startCol", range.startCol)
                put("endRow", range.endRow)
                put("endCol", range.endCol)
            })
            put("text", controller.getSelectedText())
        }
    }

    private fun readClipboard(): JSONObject {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString()
        } else null
        return JSONObject().apply {
            put("text", text ?: JSONObject.NULL)
        }
    }

    private fun writeClipboard(args: JSONObject): JSONObject {
        val text = args.optString("text", "")
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            ?: throw McpError(-32603, "ClipboardManager unavailable on this device")
        cm.setPrimaryClip(android.content.ClipData.newPlainText("haven-mcp", text))
        return JSONObject().apply {
            put("bytesWritten", text.toByteArray(Charsets.UTF_8).size)
        }
    }

    private val preferenceWhitelist = setOf(
        "terminal_scrollback_rows",
        "terminal_tap_to_position_cursor",
        "terminal_bell_notification",
        "terminal_font_size",
        "terminal_horizontal_padding",
        "mouse_input_enabled",
        "terminal_right_click",
        // Resize the terminal (reflow/SIGWINCH) to fit above the soft keyboard
        // instead of render-shifting (#206/#242). MCP-drivable so the reflow
        // path is testable without driving the Settings UI.
        "reflow_terminal_on_keyboard",
        // Tab titles follow the session-manager name (tmux/zellij/screen) on
        // tabs attached through one, ignoring titles set by running programs.
        // MCP-drivable so the #625 follow-up is testable without driving the
        // Settings UI.
        "terminal_tab_titles_follow_session",
        // Terminal colour scheme group (#139/#165). Values are
        // TerminalColorScheme enum names (e.g. DRACULA, NORD). When
        // auto-switch is on the active scheme follows the system light/dark
        // mode via the light/dark keys; otherwise the manual key is used.
        // MCP-drivable so the scheme-persistence path (#209) is testable
        // without driving the Settings UI.
        "terminal_color_scheme",
        "terminal_auto_switch_scheme",
        "terminal_light_color_scheme",
        "terminal_dark_color_scheme",
        // Terminal background opacity (float 0.0..1.0). Below 1.0 the terminal
        // renders over the device wallpaper. MCP-drivable so the see-through
        // path is testable without dragging the Settings slider.
        "terminal_background_opacity",
        // Locale exported to local terminal sessions as LANG/LC_ALL (#374).
        // MCP-drivable so the locale-propagation path is testable without
        // driving the Settings UI.
        "terminal_locale",
        // Inline-image consent policy (#583): OFF / ASK (default) / ALWAYS.
        // MCP-drivable so the consent path is testable without the Settings UI.
        "terminal_inline_images",
        // SSH profile id the MCP server tunnels its loopback listener back
        // to (dedicated headless `-R`). Empty string clears it. See
        // McpTunnelManager.
        "mcp_tunnel_endpoint_profile_id",
        // Whether the MCP server also binds on the active WireGuard tunnel.
        "mcp_wireguard_enabled",
        // Whether the MCP server also binds the device's Wi-Fi/LAN address
        // (direct same-network reach, gated by pairing). MCP-drivable so the
        // transport-robustness path is testable without the Settings UI.
        "mcp_lan_bind_enabled",
        // Tunnel config id of the WireGuard tunnel the MCP server actively
        // keeps up as its carrier (empty string clears it → attach-to-first
        // -live behaviour).
        "mcp_wireguard_tunnel_config_id",
        // Master opt-in for exposing USB devices to the proot guest (gates
        // usb_attach_to_guest). MCP-drivable so integration tests can flip it.
        "usb_guest_exposure_enabled",
        // Master opt-in for exposing the phone's GPS to the proot guest
        // (gates attach_gps_to_guest). MCP-drivable so integration tests can
        // flip it, same reasoning as the USB key.
        "gps_guest_exposure_enabled",
        // Master switch for inbound-email automation (Mail Rules). MCP-drivable so
        // the engine can be armed without the Settings UI.
        "mail_automation_enabled",
        // Experimental GPU stack for accelerated desktops: off = virgl/virpipe
        // (GL 2.1), on = venus + zink (modern GL, ~3.2 core) with wl_shm CPU-copy
        // present. MCP-drivable so the GPU-present path is testable without the
        // Settings UI. See DesktopManager.gpuPassthroughEnv.
        "gpu_use_venus",
        // Keyboard toolbar layout (JSON: array of rows; strings = built-in key
        // ids, {label,send} objects = custom snippet keys). MCP-drivable so the
        // toolbar can be reconfigured without the Settings GUI — get returns the
        // current JSON, set validates and replaces it. See ToolbarLayout.
        "toolbar_layout",
        // Audit logging of connection lifecycle events (connects, disconnects,
        // failures) to Settings → View connection log. Off by default. MCP-
        // drivable so an agent diagnosing a connection issue can enable logging,
        // reproduce, then read get_connection_log — e.g. an immediately exiting
        // local shell (#294).
        "connection_logging_enabled",
        // Per-session transport tracing (SSH/RDP/SPICE/ET), captured into the
        // ConnectionLog's verboseLog field. Off by default. MCP-drivable
        // because the diagnostic loop it feeds was not: the RDP decode
        // breakdown, the negotiated graphics capabilities and the discarded-
        // bitmap detail (#466, #477, #422) exist ONLY in this log, and an
        // agent could read get_connection_log but had no way to turn on the
        // thing that fills it.
        "verbose_logging_enabled",
        // Local proot launch toggles (#300 / #301). These live in
        // ProotManager's own SharedPreferences (not the DataStore repo), so
        // get/set route through prootManager below. MCP-drivable so the launch
        // behaviour is testable/restorable without driving the Desktop → Manage
        // toggles — the toggle-state gap that previously forced UI tapping.
        "remap_low_ports",
        "share_storage_with_guest",
        "bind_android_system",
        // #446: which resolvers the local guest's /etc/resolv.conf gets. Drivable
        // so a DNS-blocked network can be diagnosed and fixed without the GUI.
        "proot_dns_mode",
        "proot_dns_servers",
        // #550/#551: the Custom (X11) desktop's session command — drivable so
        // an agent can set a command and exercise the desktop lifecycle
        // (including crash-surfacing) without the GUI.
        "custom_desktop_command",
        // #578 opt-in update check. MCP-drivable because it was not: verifying
        // the launch-time path meant driving Settings by hand and reading
        // logcat, when the toggle is the one input the whole flow hangs off.
        "update_check_enabled",
        // #578 launch-check bookkeeping. Exposed because the launch path is
        // otherwise untestable from outside: both gates live in app-private
        // DataStore, a release build has no run-as, and pm clear would wipe
        // the user's whole Haven state. Set the timestamp to 0 to clear the
        // once-a-day throttle and the version to "" to clear the
        // already-told-you dedup, then restart Haven to exercise the real
        // checkOnLaunch — not a simulation of it.
        "update_check_last_run_ms",
        "update_check_last_notified_version",
    )

    /**
     * #578. Mirrors what Settings → About → "Check now" does, so the flow is
     * observable without capture/tap and a logcat grep. The channel is reported
     * even when it is OTHER — an agent should be able to see WHY nothing was
     * offered, not just that nothing was.
     */
    private suspend fun checkForUpdate(): JSONObject {
        val channel = updateChecker.channel()
        val result = updateChecker.check()
        return JSONObject().apply {
            put("channel", channel.name)
            put("installedVersion", updateChecker.installedVersion())
            when (result) {
                is sh.haven.core.data.update.UpdateChecker.Result.UpToDate -> {
                    put("status", "UP_TO_DATE")
                    put("latestVersion", result.installedVersion)
                }
                is sh.haven.core.data.update.UpdateChecker.Result.Available -> {
                    put("status", "UPDATE_AVAILABLE")
                    put("latestVersion", result.version)
                    put("releaseUrl", result.releaseUrl)
                }
                is sh.haven.core.data.update.UpdateChecker.Result.Failed -> {
                    put("status", "FAILED")
                    put("error", result.message)
                }
                sh.haven.core.data.update.UpdateChecker.Result.WrongChannel ->
                    put("status", "WRONG_CHANNEL")
            }
        }
    }

    private suspend fun getPreference(args: JSONObject): JSONObject {
        val key = args.optString("key").ifEmpty {
            throw McpError(-32602, "Missing required argument: key")
        }
        if (key !in preferenceWhitelist) {
            throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        val value: Any = when (key) {
            "terminal_scrollback_rows" -> preferencesRepository.terminalScrollbackRows.first()
            "terminal_tap_to_position_cursor" -> preferencesRepository.terminalTapToPositionCursor.first()
            "terminal_bell_notification" -> preferencesRepository.terminalBellNotification.first()
            "terminal_font_size" -> preferencesRepository.terminalFontSize.first()
            "terminal_horizontal_padding" -> preferencesRepository.terminalHorizontalPadding.first()
            "mouse_input_enabled" -> preferencesRepository.mouseInputEnabled.first()
            "terminal_right_click" -> preferencesRepository.terminalRightClick.first()
            "reflow_terminal_on_keyboard" -> preferencesRepository.reflowTerminalOnKeyboard.first()
            "terminal_tab_titles_follow_session" -> preferencesRepository.terminalTabTitlesFollowSession.first()
            "terminal_color_scheme" -> preferencesRepository.terminalColorScheme.first().name
            "terminal_auto_switch_scheme" -> preferencesRepository.terminalAutoSwitchScheme.first()
            "terminal_light_color_scheme" -> preferencesRepository.terminalLightColorScheme.first().name
            "terminal_dark_color_scheme" -> preferencesRepository.terminalDarkColorScheme.first().name
            "terminal_inline_images" -> preferencesRepository.terminalInlineImages.first().name
            "terminal_locale" -> preferencesRepository.terminalLocale.first()
            "mcp_tunnel_endpoint_profile_id" -> preferencesRepository.mcpTunnelEndpointProfileId.first() ?: ""
            "mcp_wireguard_enabled" -> preferencesRepository.mcpWireguardEnabled.first()
            "mcp_lan_bind_enabled" -> preferencesRepository.mcpLanBindEnabled.first()
            "mcp_wireguard_tunnel_config_id" -> preferencesRepository.mcpWireguardTunnelConfigId.first() ?: ""
            "usb_guest_exposure_enabled" -> preferencesRepository.usbGuestExposureEnabled.first()
            "gps_guest_exposure_enabled" -> preferencesRepository.gpsGuestExposureEnabled.first()
            "mail_automation_enabled" -> preferencesRepository.mailAutomationEnabled.first()
            "connection_logging_enabled" -> preferencesRepository.connectionLoggingEnabled.first()
            "verbose_logging_enabled" -> preferencesRepository.verboseLoggingEnabled.first()
            "gpu_use_venus" -> preferencesRepository.gpuUseVenus.first()
            "toolbar_layout" -> preferencesRepository.toolbarLayoutJson.first()
            // Proot launch toggles live in ProotManager (#300 / #301).
            "remap_low_ports" -> prootManager.remapLowPorts
            "share_storage_with_guest" -> prootManager.shareStorageWithGuest
            "bind_android_system" -> prootManager.bindAndroidSystem
            "proot_dns_mode" -> prootManager.dnsMode.id
            "proot_dns_servers" -> prootManager.dnsServers
            "custom_desktop_command" -> preferencesRepository.customDesktopCommand.first()
            "update_check_enabled" -> preferencesRepository.updateCheckEnabled.first()
            "update_check_last_run_ms" -> preferencesRepository.updateCheckLastRunMs.first()
            "update_check_last_notified_version" ->
                preferencesRepository.updateCheckLastNotifiedVersion.first()
            else -> throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        return JSONObject().apply {
            put("key", key)
            put("value", value)
        }
    }

    private suspend fun setPreference(args: JSONObject): JSONObject {
        val key = args.optString("key").ifEmpty {
            throw McpError(-32602, "Missing required argument: key")
        }
        if (key !in preferenceWhitelist) {
            throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        if (!args.has("value")) throw McpError(-32602, "Missing required argument: value")
        val rawValue = args.opt("value")
        // Coerce permissively — different MCP clients serialise JSON
        // numerics inconsistently. JSONObject.opt() may hand back
        // Integer, Long, Double, or even a String that parses as a
        // number; the caller's intent is unambiguous either way.
        fun coerceInt(): Int = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.toIntOrNull()
                ?: throw McpError(-32602, "value must be an integer for $key, got \"$rawValue\"")
            else -> throw McpError(-32602, "value must be an integer for $key, got ${rawValue?.javaClass?.simpleName}")
        }
        fun coerceBool(): Boolean = when (rawValue) {
            is Boolean -> rawValue
            is String -> rawValue.toBooleanStrictOrNull()
                ?: throw McpError(-32602, "value must be true/false for $key, got \"$rawValue\"")
            else -> throw McpError(-32602, "value must be a boolean for $key, got ${rawValue?.javaClass?.simpleName}")
        }
        fun coerceFloat(): Float = when (rawValue) {
            is Number -> rawValue.toFloat()
            is String -> rawValue.toFloatOrNull()
                ?: throw McpError(-32602, "value must be a number for $key, got \"$rawValue\"")
            else -> throw McpError(-32602, "value must be a number for $key, got ${rawValue?.javaClass?.simpleName}")
        }
        // Parse a TerminalColorScheme enum name (case-insensitive). Reject
        // unknown names rather than silently defaulting to HAVEN — a caller
        // that fat-fingers the scheme should hear about it, not have its
        // setting silently dropped.
        fun coerceScheme(): UserPreferencesRepository.TerminalColorScheme {
            val name = (rawValue as? String)
                ?: throw McpError(-32602, "value must be a scheme name string for $key, got ${rawValue?.javaClass?.simpleName}")
            return UserPreferencesRepository.TerminalColorScheme.entries
                .find { it.name.equals(name, ignoreCase = true) }
                ?: throw McpError(
                    -32602,
                    "Unknown colour scheme \"$name\" for $key. Valid: " +
                        UserPreferencesRepository.TerminalColorScheme.entries.joinToString(", ") { it.name },
                )
        }
        // Same policy as coerceScheme: an unknown consent mode should error,
        // not silently fall back to the default.
        fun coerceInlineImages(): UserPreferencesRepository.TerminalInlineImages {
            val name = (rawValue as? String)
                ?: throw McpError(-32602, "value must be a policy name string for $key, got ${rawValue?.javaClass?.simpleName}")
            return UserPreferencesRepository.TerminalInlineImages.entries
                .find { it.name.equals(name, ignoreCase = true) }
                ?: throw McpError(
                    -32602,
                    "Unknown inline-image policy \"$name\" for $key. Valid: " +
                        UserPreferencesRepository.TerminalInlineImages.entries.joinToString(", ") { it.name },
                )
        }
        when (key) {
            "terminal_inline_images" -> preferencesRepository.setTerminalInlineImages(coerceInlineImages())
            "terminal_scrollback_rows" -> preferencesRepository.setTerminalScrollbackRows(coerceInt())
            "terminal_font_size" -> preferencesRepository.setTerminalFontSize(coerceInt())
            "terminal_horizontal_padding" -> preferencesRepository.setTerminalHorizontalPadding(coerceInt())
            "terminal_tap_to_position_cursor" -> preferencesRepository.setTerminalTapToPositionCursor(coerceBool())
            "terminal_bell_notification" -> preferencesRepository.setTerminalBellNotification(coerceBool())
            "mouse_input_enabled" -> preferencesRepository.setMouseInputEnabled(coerceBool())
            "terminal_right_click" -> preferencesRepository.setTerminalRightClick(coerceBool())
            "reflow_terminal_on_keyboard" -> preferencesRepository.setReflowTerminalOnKeyboard(coerceBool())
            "terminal_tab_titles_follow_session" -> preferencesRepository.setTerminalTabTitlesFollowSession(coerceBool())
            "terminal_color_scheme" -> preferencesRepository.setTerminalColorScheme(coerceScheme())
            "terminal_auto_switch_scheme" -> preferencesRepository.setTerminalAutoSwitchScheme(coerceBool())
            "terminal_light_color_scheme" -> preferencesRepository.setTerminalLightColorScheme(coerceScheme())
            "terminal_dark_color_scheme" -> preferencesRepository.setTerminalDarkColorScheme(coerceScheme())
            "terminal_background_opacity" -> preferencesRepository.setTerminalBackgroundOpacity(coerceFloat())
            "terminal_locale" -> {
                val locale = (rawValue as? String)?.trim()
                    ?: throw McpError(-32602, "value must be a locale string for $key (e.g. zh_CN.UTF-8)")
                // Single env-safe token — it lands in the session env as
                // LANG=<value>/LC_ALL=<value> (#374).
                if (locale.isEmpty() || !locale.matches(Regex("^[A-Za-z0-9._@-]+$"))) {
                    throw McpError(-32602, "Invalid locale \"$locale\" for $key")
                }
                preferencesRepository.setTerminalLocale(locale)
            }
            "mcp_tunnel_endpoint_profile_id" ->
                preferencesRepository.setMcpTunnelEndpointProfileId((rawValue as? String)?.ifBlank { null })
            "mcp_wireguard_enabled" -> preferencesRepository.setMcpWireguardEnabled(coerceBool())
            "mcp_lan_bind_enabled" -> preferencesRepository.setMcpLanBindEnabled(coerceBool())
            "mcp_wireguard_tunnel_config_id" ->
                preferencesRepository.setMcpWireguardTunnelConfigId((rawValue as? String)?.ifBlank { null })
            "usb_guest_exposure_enabled" -> preferencesRepository.setUsbGuestExposureEnabled(coerceBool())
            "gps_guest_exposure_enabled" -> preferencesRepository.setGpsGuestExposureEnabled(coerceBool())
            "mail_automation_enabled" -> preferencesRepository.setMailAutomationEnabled(coerceBool())
            "connection_logging_enabled" -> preferencesRepository.setConnectionLoggingEnabled(coerceBool())
            "verbose_logging_enabled" -> preferencesRepository.setVerboseLoggingEnabled(coerceBool())
            "gpu_use_venus" -> preferencesRepository.setGpuUseVenus(coerceBool())
            "update_check_enabled" -> preferencesRepository.setUpdateCheckEnabled(coerceBool())
            "update_check_last_run_ms" -> {
                // Accept Int as well as Long: JSON-RPC numbers arrive as Int
                // when small, and 0 — the value that clears the throttle —
                // is exactly the small case.
                val ms = when (rawValue) {
                    is Number -> rawValue.toLong()
                    is String -> rawValue.toLongOrNull()
                    else -> null
                } ?: throw McpError(-32602, "value must be epoch milliseconds for $key (0 clears the throttle)")
                if (ms < 0) throw McpError(-32602, "value must not be negative for $key")
                preferencesRepository.setUpdateCheckLastRunMs(ms)
            }
            "update_check_last_notified_version" ->
                preferencesRepository.setUpdateCheckLastNotifiedVersion(
                    (rawValue as? String)
                        ?: throw McpError(-32602, "value must be a version string for $key (\"\" clears the dedup)"),
                )
            "toolbar_layout" -> {
                val json = (rawValue as? String)
                    ?: throw McpError(-32602, "value must be a toolbar-layout JSON string for $key")
                ToolbarLayout.validate(json)?.let { throw McpError(-32602, "Invalid toolbar layout: $it") }
                preferencesRepository.setToolbarLayoutJson(json)
            }
            // Proot launch toggles live in ProotManager (#300 / #301). Routing
            // through its setters updates the StateFlow, so the Desktop → Manage
            // UI reflects the change too.
            "remap_low_ports" -> prootManager.setRemapLowPorts(coerceBool())
            "share_storage_with_guest" -> prootManager.setShareStorageWithGuest(coerceBool())
            "bind_android_system" -> prootManager.setBindAndroidSystem(coerceBool())
            "proot_dns_mode" -> {
                val raw = (rawValue as? String)?.trim()
                    ?: throw McpError(-32602, "value must be one of system|public|custom for $key")
                val mode = sh.haven.core.local.ProotDnsMode.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
                    ?: throw McpError(-32602, "Invalid DNS mode \"$raw\" for $key (expected system, public or custom)")
                prootManager.setDnsMode(mode)
            }
            "proot_dns_servers" -> {
                val raw = (rawValue as? String)
                    ?: throw McpError(-32602, "value must be a string of IP or IP:port literals for $key")
                // Reject early rather than writing a resolv.conf that silently never
                // resolves - a hostname here cannot be resolved (#446, #838).
                if (
                    raw.isNotBlank() &&
                    (sh.haven.core.local.ProotDns.invalidTokens(raw).isNotEmpty() ||
                        sh.haven.core.local.ProotDns.parseServers(raw).isEmpty())
                ) {
                    throw McpError(-32602, "Invalid DNS server entry in \"$raw\" for $key (expected IP or IP:port literals)")
                }
                prootManager.setDnsServers(raw)
            }
            "custom_desktop_command" -> {
                val raw = (rawValue as? String)
                    ?: throw McpError(-32602, "value must be a string for $key")
                preferencesRepository.setCustomDesktopCommand(raw)
            }
        }
        return JSONObject().apply {
            put("key", key)
            put("value", rawValue)
        }
    }

    private fun parseSelectionMode(s: String?): org.connectbot.terminal.SelectionMode = when (s?.uppercase()) {
        null, "", "CHARACTER" -> org.connectbot.terminal.SelectionMode.CHARACTER
        "WORD" -> org.connectbot.terminal.SelectionMode.WORD
        "LINE" -> org.connectbot.terminal.SelectionMode.LINE
        "NONE" -> org.connectbot.terminal.SelectionMode.NONE
        else -> throw McpError(-32602, "Unknown selection mode: $s")
    }

    private fun startSelection(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        val mode = parseSelectionMode(args.optString("mode", "CHARACTER"))
        // The Compose-side SelectionController.startSelection short-circuits
        // when `selectionManager.mode != NONE` (so the gesture handler can't
        // re-anchor while a selection is already up). Mirror "replaces any
        // existing selection on this session" by clearing first; then
        // startSelection(mode) sets both mode and an initial range, and
        // updateSelectionStart/End move it to the caller's (row, col).
        controller.clearSelection()
        controller.startSelection(mode)
        controller.updateSelectionStart(row, col)
        controller.updateSelectionEnd(row, col)
        when (mode) {
            org.connectbot.terminal.SelectionMode.WORD ->
                sh.haven.feature.terminal.expandSelectionToWord(controller, entry.emulator)
            org.connectbot.terminal.SelectionMode.LINE ->
                controller.setSelectionMode(mode)
            else -> Unit
        }
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("active", controller.isSelectionActive)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
            put("text", controller.getSelectedText())
        }
    }

    private fun setComposeMode(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.composeController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active ComposeController — open a terminal tab on this session first")
        if (args.has("enabled")) {
            if (args.optBoolean("enabled")) controller.startComposeMode() else controller.stopComposeMode()
        } else {
            controller.toggleComposeMode()
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("composeModeActive", controller.isComposeModeActive)
            put("composedText", controller.getComposedText())
        }
    }

    private fun extendSelection(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        controller.updateSelectionEnd(row, col)
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
            put("text", controller.getSelectedText())
        }
    }

    private fun dragSelectionTo(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val selection = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val scroll = entry.scrollController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active ScrollController")
        if (!args.has("toRow")) throw McpError(-32602, "Missing required argument: toRow")
        if (!args.has("toCol")) throw McpError(-32602, "Missing required argument: toCol")
        val toRow = args.optInt("toRow")
        val toCol = args.optInt("toCol")
        if (toCol < 0) throw McpError(-32602, "toCol must be >= 0")
        val rows = entry.emulator.dimensions.rows
        // Translate the requested logical row into a viewport-anchored
        // endpoint plus a signed step count. The Compose gesture handler
        // does this implicitly because the pointer reports a Y position;
        // MCP works in logical rows so the agent can request "drag 30
        // rows into scrollback" without knowing the viewport height.
        //
        // Sign convention here matches scroll_terminal: positive steps =
        // scroll back into older content (so a finger at the top edge
        // dragging UP into scrollback is positive); negative = scroll
        // forward toward the live screen. Each step is one row of scroll
        // paired with one shiftSelectionStartByRows of the same sign.
        val (clampedRow, requestedSteps) = when {
            toRow < 0 -> 0 to (-toRow)                          // into scrollback
            toRow >= rows -> (rows - 1) to (rows - 1 - toRow)   // toward live
            else -> toRow to 0
        }
        var actualSteps = 0
        var clamped = false
        if (requestedSteps != 0) {
            val stepDirection = if (requestedSteps > 0) +1 else -1
            val totalSteps = kotlin.math.abs(requestedSteps)
            // Read the clamp threshold from the emulator's live agent
            // snapshot rather than the ScrollController's `maxScrollback`
            // property. The controller reads through the Compose-side
            // `screenState`, which only refreshes on recomposition — for
            // sessions whose tab isn't currently the foreground (the
            // typical agent-shell case), the cached scrollback size lags
            // the underlying emulator by an arbitrary amount.
            // `buildAgentSnapshot` reads the same `_snapshot.value`
            // that's updated on every processPendingUpdates(), so it's
            // authoritative even when the Compose subtree is paused.
            val maxScrollback = entry.emulator.buildAgentSnapshot(maxLines = 0).scrollbackSize
            repeat(totalSteps) {
                val canStep = if (stepDirection > 0) {
                    scroll.scrollbackPosition < maxScrollback
                } else {
                    scroll.scrollbackPosition > 0
                }
                if (!canStep) {
                    clamped = true
                    return@repeat
                }
                scroll.scrollBy(stepDirection)
                selection.shiftSelectionStartByRows(stepDirection)
                actualSteps += stepDirection
            }
        }
        selection.updateSelectionEnd(clampedRow, toCol)
        val range = selection.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
            put("scrollbackPosition", scroll.scrollbackPosition)
            put("scrollSteps", actualSteps)
            put("clamped", clamped)
            put("text", selection.getSelectedText())
        }
    }

    private fun copySelection(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val text = controller.copySelection()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("text", text)
        }
    }

    private fun tapTerminal(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        val beforeCol = entry.emulator.buildAgentSnapshot().cursorCol
        val handled = entry.emulator.tapToPositionCursorOnPrompt(row, col)
        val afterCol = entry.emulator.buildAgentSnapshot().cursorCol
        val deltaCols = afterCol - beforeCol
        val dispatched = when {
            !handled -> "NONE"
            deltaCols < 0 -> "LEFT"
            deltaCols > 0 -> "RIGHT"
            else -> "NONE"
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("handled", handled)
            put("deltaCols", deltaCols)
            put("dispatched", dispatched)
        }
    }

    private fun scrollTerminal(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val scrollController = entry.scrollController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active ScrollController")
        val lines = if (args.has("lines")) args.optInt("lines") else throw McpError(-32602, "Missing required argument: lines")
        scrollController.scrollBy(lines)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("scrollbackPosition", scrollController.scrollbackPosition)
        }
    }

    private fun sendTerminalInput(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val text = args.optString("text", "")
        val bracketed = args.optBoolean("bracketedPaste", false)
        val returnSnapshot = args.optBoolean("returnSnapshot", false)

        // Named keys → real control bytes the `text` param can't express
        // cleanly (a "\r" in text arrives as two literal chars; a raw-mode REPL
        // treats "\n" as newline-insert, not submit). #14
        val keyBytes = StringBuilder()
        args.optJSONArray("keys")?.let { keys ->
            for (i in 0 until keys.length()) {
                val name = keys.optString(i)
                keyBytes.append(
                    namedKeyToBytes(name) ?: throw McpError(
                        -32602,
                        "Unknown key '$name'. Supported: enter, esc, tab, space, backspace, " +
                            "delete, up, down, left, right, home, end, pageup, pagedown, " +
                            "ctrl-a/c/d/e/l/u/w/z.",
                    ),
                )
            }
        }
        if (text.isEmpty() && keyBytes.isEmpty()) {
            throw McpError(-32602, "Provide text and/or keys")
        }

        // Wrap text in bracketed-paste markers when asked, so a raw-mode REPL
        // (Claude Code, readline, vim) treats multi-line input as one paste
        // rather than interleaved keystrokes that fight its submit handling.
        val body = if (text.isNotEmpty() && bracketed) "\u001b[200~$text\u001b[201~" else text

        // Hard cap (≈two screens) keeps consent reviewable and avoids an agent
        // pasting megabytes through the terminal.
        val totalBytes = body.toByteArray(Charsets.UTF_8).size +
            keyBytes.toString().toByteArray(Charsets.UTF_8).size
        if (totalBytes > 4096) {
            throw McpError(-32602, "input too long: $totalBytes bytes (max 4096)")
        }

        // Send the body first, then the keys as a SEPARATE write after a brief
        // settle — so a submit key (Enter) arrives in its own read() and is seen
        // as a discrete keypress, not folded into the text burst by a raw-mode
        // REPL's input batching (verified on Claude Code: text+\r in one burst
        // stages without submitting; a separate \r submits). #14
        var transport: String? = null
        if (body.isNotEmpty()) transport = sendRawInput(sessionId, body)
        if (body.isNotEmpty() && keyBytes.isNotEmpty()) {
            try {
                Thread.sleep(SUBMIT_SETTLE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (keyBytes.isNotEmpty()) transport = sendRawInput(sessionId, keyBytes.toString())

        return JSONObject().apply {
            put("sessionId", sessionId)
            // `delivered` only ever means "a transport accepted the write
            // without throwing" — it is not an echo check, and #555 is exactly
            // the case where that is not enough. `transport` names who took the
            // bytes, so the next reproduction distinguishes "the local manager
            // accepted and lost them" from "another transport claimed the id".
            put("delivered", true)
            put("transport", transport)
            put("bytesSent", totalBytes)
            if (returnSnapshot) {
                // Give the target a beat to render the just-submitted input so
                // the ack snapshot reflects the result, not the pre-send screen
                // (#226 item 4). send_to_agent passes 500ms; default stays 0.
                val delay = args.optLong("snapshotDelayMs", 0L).coerceIn(0L, 5_000L)
                if (delay > 0) {
                    try {
                        Thread.sleep(delay)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                runCatching { readTerminalSnapshot(JSONObject().put("sessionId", sessionId)) }
                    .getOrNull()?.let { put("snapshot", it) }
            }
        }
    }

    /**
     * Write a raw string to a session's PTY on whichever transport owns it —
     * SSH, local, mosh, ET, or Reticulum (#366: only the first two were
     * tried, so agent input to a mosh session failed "No local session"
     * while snapshot reads resolved it fine).
     */
    /** @return the transport that accepted the write (see #555). */
    private fun sendRawInput(sessionId: String, s: String): String {
        try {
            return sessionManagerRegistry.sendTerminalInput(sessionId, s)
        } catch (e: IllegalStateException) {
            throw McpError(-32603, e.message ?: "No terminal session: $sessionId")
        }
    }

    /**
     * Deliver one message to a raw-mode REPL as a single submitted turn:
     * bracketed paste + settle + Enter + snapshot. Thin wrapper over
     * [sendTerminalInput] for agent↔agent conversation. #14/#19
     */
    private fun sendToAgent(args: JSONObject): JSONObject {
        val message = args.optString("message").ifEmpty {
            throw McpError(-32602, "Missing required argument: message")
        }
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        // Bracket-paste only when the target actually enabled it (raw-mode
        // REPLs do): a plain shell that never sent DECSET 2004 receives the
        // markers as literal text — busybox sh read ESC[200~echo… and ran
        // "[200~echo: not found" (device-reproduced, #226).
        val bracket = terminalSessionRegistry.get(sessionId)?.bracketPasteMode?.value == true
        return sendTerminalInput(
            JSONObject().apply {
                put("sessionId", sessionId)
                put("text", message)
                put("bracketedPaste", bracket)
                put("keys", JSONArray().put("enter"))
                put("returnSnapshot", true)
                // Let the REPL render the submitted turn before the ack
                // snapshot is captured (#226 item 4).
                put("snapshotDelayMs", if (args.has("snapshotDelayMs")) args.optLong("snapshotDelayMs") else 500L)
                // Bound the ack snapshot to a screenful so a delivered message
                // doesn't read back as a timeout behind a megabyte of scrollback
                // over the tunnel (deliver-then-return). An explicit maxLines wins.
                put("maxLines", if (args.has("maxLines")) args.optInt("maxLines") else SEND_TO_AGENT_SNAPSHOT_LINES)
            },
        )
    }

    /**
     * Block until the session is idle-at-prompt (#226). OSC 133 when the
     * shell emits it, screen heuristics (AgentTurnHeuristics.kt) otherwise.
     * Runs on the MCP request thread — Thread.sleep is the house pattern
     * here (see sendTerminalInput's settle).
     */
    private fun awaitTurn(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val timeoutMs = args.optLong("timeoutMs", 60_000L).coerceIn(1_000L, 300_000L)
        val settleMs = args.optLong("settleMs", 1_500L).coerceIn(0L, 30_000L)
        val pollMs = 500L
        val start = android.os.SystemClock.elapsedRealtime()
        var idleSince = -1L
        var lastHash = 0
        var haveHash = false
        var method = "heuristic"
        while (true) {
            val snap = entry.emulator.buildAgentSnapshot(includeSemanticSegments = true)
            val texts = snap.lines.map { it.text }
            val now = android.os.SystemClock.elapsedRealtime()
            val hash = (texts.joinToString("\n") + "|${snap.cursorRow},${snap.cursorCol}").hashCode()
            val stable = haveHash && hash == lastHash
            lastHash = hash
            haveHash = true
            val (idle, pollMethod) = idlePoll(snap, stable)
            method = pollMethod
            if (idle) {
                if (idleSince < 0) idleSince = now
                if (now - idleSince >= settleMs) {
                    return JSONObject().apply {
                        put("sessionId", sessionId)
                        put("idle", true)
                        put("method", method)
                        put("waitedMs", now - start)
                        put("timedOut", false)
                    }
                }
            } else {
                idleSince = -1L
            }
            if (now - start >= timeoutMs) {
                return JSONObject().apply {
                    put("sessionId", sessionId)
                    put("idle", false)
                    put("method", method)
                    put("waitedMs", now - start)
                    put("timedOut", true)
                }
            }
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw McpError(-32603, "await_turn interrupted")
            }
        }
    }

    /**
     * The session's latest completed turn as text (#226). OSC 133 command
     * output only when the shell is actually idle at its newest prompt —
     * Claude Code renders in the normal buffer, so an outer shell's stale
     * segments stay visible while the REPL runs; trusting them would return
     * the output of the command BEFORE the REPL launched.
     */
    private fun readLastTurn(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val maxChars = args.optInt("maxChars", 20_000).coerceIn(256, 100_000)
        val snap = entry.emulator.buildAgentSnapshot(includeSemanticSegments = true)
        val fromOsc = if (osc133Idle(snap) == true) entry.emulator.getLastCommandOutput() else null
        val (text, source) = when {
            fromOsc != null -> fromOsc to "osc133"
            else -> scrapeLastAgentBlock(snap.lines.map { it.text }) to "scrape"
        }
        val truncated = text != null && text.length > maxChars
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("text", if (text == null) JSONObject.NULL else if (truncated) text.takeLast(maxChars) else text)
            put("source", if (text == null) JSONObject.NULL else source)
            put("truncated", truncated)
        }
    }

    /** Map a named key to the control bytes a PTY expects. Null = unknown. */
    private fun namedKeyToBytes(name: String): String? = when (name.lowercase().trim()) {
        "enter", "return", "cr" -> "\r"
        "newline", "lf" -> "\n"
        "esc", "escape" -> "\u001b"
        "tab" -> "\t"
        "space" -> " "
        "backspace", "bs" -> "\u007f"
        "delete", "del" -> "\u001b[3~"
        "ctrl-a", "ctrl+a", "c-a" -> "\u0001"
        "ctrl-c", "ctrl+c", "c-c" -> "\u0003"
        "ctrl-d", "ctrl+d", "c-d" -> "\u0004"
        "ctrl-e", "ctrl+e", "c-e" -> "\u0005"
        "ctrl-l", "ctrl+l", "c-l" -> "\u000c"
        "ctrl-u", "ctrl+u", "c-u" -> "\u0015"
        "ctrl-w", "ctrl+w", "c-w" -> "\u0017"
        "ctrl-z", "ctrl+z", "c-z" -> "\u001a"
        "up" -> "\u001b[A"
        "down" -> "\u001b[B"
        "right" -> "\u001b[C"
        "left" -> "\u001b[D"
        "home" -> "\u001b[H"
        "end" -> "\u001b[F"
        "pageup", "pgup" -> "\u001b[5~"
        "pagedown", "pgdn" -> "\u001b[6~"
        else -> null
    }

    private fun feedTerminalOutput(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val feed = entry.feedOutput
            ?: throw McpError(
                -32603,
                "Session $sessionId has no output pipeline — feed_terminal_output is not supported for headless agent shells",
            )
        val hasText = args.has("text")
        val hasB64 = args.has("bytesBase64")
        if (hasText == hasB64) {
            throw McpError(-32602, "Provide exactly one of: text, bytesBase64")
        }
        val bytes: ByteArray = if (hasText) {
            args.optString("text").toByteArray(Charsets.UTF_8)
        } else {
            try {
                android.util.Base64.decode(args.optString("bytesBase64"), android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw McpError(-32602, "bytesBase64 is not valid base64: ${e.message}")
            }
        }
        if (bytes.size > 65536) {
            throw McpError(-32602, "payload too long: ${bytes.size} bytes (max 65536)")
        }
        // feedOutput is internally synchronized against the live data
        // callback, so this is safe to call straight from the MCP thread.
        feed(bytes, 0, bytes.size)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("bytesFed", bytes.size)
        }
    }

    private fun dragTerminal(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val injector = entry.gestureInjector
            ?: throw McpError(
                -32603,
                "Session $sessionId has no gesture injector — its terminal tab must be the foreground tab",
            )
        val pathArr = args.optJSONArray("path")
            ?: throw McpError(-32602, "Missing required argument: path")
        if (pathArr.length() == 0) {
            throw McpError(-32602, "path must have at least one cell")
        }
        if (pathArr.length() > 256) {
            throw McpError(-32602, "path too long: ${pathArr.length()} cells (max 256)")
        }
        val path = ArrayList<Pair<Int, Int>>(pathArr.length())
        for (i in 0 until pathArr.length()) {
            val cell = pathArr.optJSONObject(i)
                ?: throw McpError(-32602, "path[$i] must be an object { row, col }")
            if (!cell.has("row") || !cell.has("col")) {
                throw McpError(-32602, "path[$i] must have both row and col")
            }
            path.add(cell.getInt("row") to cell.getInt("col"))
        }
        val pressMs = if (args.has("pressMs")) args.optLong("pressMs", 900L) else 900L
        val stepMs = if (args.has("stepMs")) args.optLong("stepMs", 30L) else 30L
        val holdMs = if (args.has("holdMs")) args.optLong("holdMs", 1000L) else 1000L
        if (pressMs < 0 || stepMs < 0 || holdMs < 0) {
            throw McpError(-32602, "pressMs / stepMs / holdMs must be non-negative")
        }
        try {
            injector.injectDrag(path, pressMs, stepMs, holdMs)
        } catch (e: IllegalArgumentException) {
            throw McpError(-32602, e.message ?: "invalid drag_terminal arguments")
        } catch (e: IllegalStateException) {
            throw McpError(-32603, e.message ?: "gesture injection failed")
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("cells", path.size)
            put("approxDurationMs", pressMs + stepMs * (path.size - 1).coerceAtLeast(0) + holdMs)
        }
    }

    private fun openDeveloperSettings(): JSONObject {
        // ACTION_APPLICATION_DEVELOPMENT_SETTINGS lands directly on the
        // Developer Options screen on every Android version we support;
        // nothing in there changes without an explicit user tap, so
        // this is a tap-equivalent action — same consent shape as
        // play_file.
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            JSONObject().apply { put("dispatched", true) }
        } catch (e: android.content.ActivityNotFoundException) {
            throw McpError(
                -32603,
                "Could not open Developer Options — most likely Developer Mode is not yet enabled. Toggle Settings → About phone → Build number 7 times to unlock it.",
            )
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to open Developer Settings: ${e.message}")
        }
    }

    /** Shared upper bound on staged APK size — 200 MiB; release APKs are ~80–100 MiB. */
    private val agentInstallMaxBytes: Long = 200L * 1024 * 1024

    /** One copy of the Shizuku-missing hint, reused by [runShizukuOrThrow]. */
    private val shizukuInstallHint =
        "Install Shizuku from https://shizuku.rikka.app and grant Haven permission, then retry."

    /**
     * Shared Shizuku-exec wrapper for the privileged tools. Maps the two
     * failure modes every caller used to handle identically — Shizuku
     * unavailable (an [IllegalStateException], answered with
     * [shizukuInstallHint]) and a generic exec failure — into [McpError].
     * When [requireSuccess] is true (default) a non-zero exit is also
     * surfaced, tagged with [label]; pass false when the caller inspects the
     * output/exit itself (e.g. an exact readback or a tolerated empty result).
     *
     * Does NOT cover the install path ([installStagedApkBlocking]) — that one
     * deliberately catches the unavailable case to fall back to the system
     * installer rather than throw.
     */
    private fun runShizukuOrThrow(
        cmd: String,
        label: String,
        requireSuccess: Boolean = true,
    ): WaylandSocketHelper.ShizukuExecResult {
        val result = try {
            WaylandSocketHelper.execAsShizuku(cmd)
        } catch (e: IllegalStateException) {
            throw McpError(-32603, "${e.message}. $shizukuInstallHint")
        } catch (e: Exception) {
            throw McpError(-32603, "Shizuku exec failed: ${e.message}")
        }
        if (requireSuccess && result.exitCode != 0) {
            throw McpError(-32603, "$label exited ${result.exitCode}: ${result.output.take(500)}")
        }
        return result
    }

    /** Stage a fresh File handle under cacheDir/agent-install/. */
    private fun stageApkFile(): File {
        val dir = File(context.cacheDir, "agent-install").apply { mkdirs() }
        return File(dir, "haven-agent-install-${System.currentTimeMillis()}.apk")
    }

    /**
     * Verify that the staged file's first two bytes are the zip
     * magic ("PK") — cheap defence against an HTML 200, wrong file
     * type, or a truncated download.
     */
    private fun assertApkMagic(target: File, written: Long) {
        val firstFour = target.inputStream().use { stream ->
            ByteArray(4).also { stream.read(it) }
        }
        if (firstFour[0] != 0x50.toByte() || firstFour[1] != 0x4B.toByte()) {
            target.delete()
            throw McpError(
                -32603,
                "Got $written bytes but they don't start with the zip/APK magic — wrong path/URL?",
            )
        }
    }

    /**
     * Stage an APK by copying [openInput]'s stream into a fresh cache file,
     * enforcing [agentInstallMaxBytes]. Shared by [installApkFromUrl] and
     * [installApkFromBackend]. [openInput] runs inside the guarded block so a
     * failure to open the source maps through [onFailure] too. On any failure
     * the partial file is deleted; the size-cap breach throws its own McpError.
     * Returns (stagedFile, bytesWritten).
     */
    private suspend fun streamToStagedApk(
        openInput: suspend () -> java.io.InputStream,
        onProgress: (Long) -> Unit = {},
        expectedBytes: Long = -1,
        onFailure: (Exception) -> String,
    ): Pair<File, Long> {
        val target = stageApkFile()
        val written = try {
            openInput().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastReported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > agentInstallMaxBytes) {
                            target.delete()
                            throw McpError(-32603, "APK exceeds ${agentInstallMaxBytes / (1024 * 1024)} MiB cap")
                        }
                        output.write(buf, 0, n)
                        // #331: progress for the pollable activeInstall snapshot.
                        // Throttled to every 256 KiB so a fast stream doesn't
                        // spend its time building JSON.
                        if (total - lastReported >= 256 * 1024) {
                            lastReported = total
                            onProgress(total)
                        }
                    }
                    onProgress(total)
                    total
                }
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            target.delete()
            throw McpError(-32603, onFailure(e))
        }
        // Truncated-transfer guard: a dropped connection / short backend read
        // yields a partial APK that still starts with the PK zip magic (the
        // header is at the front), so assertApkMagic passes and the installer
        // fails on-device with "problem parsing the package". When the source
        // advertised a length, require we got all of it. (-1 = unknown, e.g.
        // chunked encoding — can't verify, so don't.)
        if (expectedBytes >= 0 && written != expectedBytes) {
            target.delete()
            throw McpError(
                -32603,
                "Truncated download: got $written of $expectedBytes bytes " +
                    "(connection dropped mid-transfer?) — retry the install",
            )
        }
        return target to written
    }

    /** Update [activeInstallProgress] (#331). Pass bytes=-1 for a phase-only update. */
    private fun reportInstallPhase(source: String, phase: String, bytes: Long = -1, totalBytes: Long = -1) {
        activeInstallProgress = JSONObject().apply {
            put("source", source)
            put("phase", phase)
            if (bytes >= 0) put("bytes", bytes)
            if (totalBytes > 0) put("totalBytes", totalBytes)
        }
    }

    private suspend fun installApkFromUrl(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val urlString = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        val url = try {
            java.net.URL(urlString)
        } catch (e: Exception) {
            throw McpError(-32602, "Invalid URL: ${e.message}")
        }
        if (url.protocol !in setOf("http", "https")) {
            throw McpError(-32602, "Only http(s) URLs are supported (got ${url.protocol})")
        }
        // #331: a large APK on a slow link routinely outlasts the MCP request
        // budget — the call read as a timeout while the download silently
        // continued (observed live: a 110 MB release over a travel network).
        // Mirror install_apk_from_backend: validate synchronously above, then
        // download + install in the background and return a pending ack. The
        // caller polls get_app_info: `activeInstall` carries live phase/bytes,
        // `lastInstall` the terminal outcome.
        reportInstallPhase(urlString, "connecting")
        backgroundScope.launch {
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Haven/1.0 (install_apk_from_url)")
            }
            runCatching {
                try {
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        throw McpError(-32603, "HTTP ${conn.responseCode} from ${url.host}")
                    }
                    val totalBytes = conn.contentLengthLong
                    reportInstallPhase(urlString, "downloading", 0, totalBytes)
                    val (target, written) = streamToStagedApk(
                        openInput = { conn.inputStream },
                        onProgress = { reportInstallPhase(urlString, "downloading", it, totalBytes) },
                        expectedBytes = totalBytes,
                    ) { "Download failed: ${it.message}" }
                    // Sanity check — APK is a zip, magic bytes 0x50 0x4B ("PK").
                    // Rejects HTML / plain-text bodies that 200'd anyway — the
                    // most common wrong-URL failure mode.
                    assertApkMagic(target, written)
                    reportInstallPhase(urlString, "installing", written)
                    installStagedApk(target, written)
                } finally {
                    conn.disconnect()
                }
            }.onFailure {
                android.util.Log.w("McpTools", "URL APK install failed ($urlString): ${it.message}")
                recordInstallOutcome(
                    "url", urlString, url.host,
                    ok = false, detail = it.message, tool = "install_apk_from_url",
                )
            }.onSuccess {
                android.util.Log.i("McpTools", "URL APK install completed ($urlString): $it")
                // installStagedApk returns {installed} (Shizuku), {restarting}
                // (self-update deferral), or {pending} (system-installer handoff)
                // — all mean the download reached the install step without error.
                val detail = when {
                    it.optBoolean("restarting", false) -> "self-update committing — expect the MCP link to drop"
                    it.optBoolean("pending", false) -> "awaiting on-device install confirmation"
                    else -> "installed"
                }
                recordInstallOutcome(
                    "url", urlString, url.host,
                    ok = true, detail = detail, tool = "install_apk_from_url",
                )
            }
            activeInstallProgress = null
        }
        val shizukuReady = sh.haven.core.local.WaylandSocketHelper.isShizukuAvailable() &&
            sh.haven.core.local.WaylandSocketHelper.hasShizukuPermission()
        JSONObject().apply {
            put("installed", false)
            put("pending", true)
            put("staging", true)
            put("url", urlString)
            put("shizukuReady", shizukuReady)
            put(
                "message",
                "Downloading and installing in the background — a large APK on a slow link " +
                    "outlasts the request timeout, so this returns immediately. Poll " +
                    "get_app_info: `activeInstall` has live phase/bytes while in flight, " +
                    "`lastInstall` the outcome. " +
                    (if (shizukuReady) {
                        "Shizuku is available: install is silent. "
                    } else {
                        "No Shizuku: a 'tap to install' prompt/notification appears on-device. "
                    }) +
                    "If Haven is updating itself the MCP link drops on restart — reconnect first.",
            )
        }
    }

    /**
     * Install a staged APK. For a foreign package this is synchronous and
     * returns the install result. For a **self-update** (the APK's package is
     * ours) the `pm install` commit replaces this very process, so a
     * synchronous response would never reach the MCP client — it reads as a
     * timeout (the friction repeatedly hit during agent-driven testing). So:
     * detect a self-install from the APK manifest (no Shizuku needed), return
     * an immediate `restarting` ack, and defer the install just long enough
     * for the ack to flush over the (reverse) MCP tunnel.
     */
    private fun installStagedApk(target: File, written: Long): JSONObject {
        val pkg = runCatching {
            context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)?.packageName
        }.getOrNull()
        if (pkg == context.packageName) {
            backgroundScope.launch {
                delay(800) // let the ack flush before pm install replaces us
                runCatching { installStagedApkBlocking(target, written) }
                    .onFailure {
                        android.util.Log.w("McpTools", "deferred self-install failed: ${it.message}")
                    }
            }
            return JSONObject().apply {
                put("installed", false)
                put("restarting", true)
                put("bytesStaged", written)
                put("package", pkg)
                put(
                    "note",
                    "Installing Haven over itself — the MCP connection drops when the new " +
                        "process starts. Reconnect (/mcp reconnect) and confirm the running " +
                        "build with get_app_info.",
                )
            }
        }
        return installStagedApkBlocking(target, written)
    }

    /**
     * Install an APK that's already been staged at [target]. Tries
     * the Shizuku-driven `pm install -S` path first; falls back to
     * firing the system installer dialog via `Intent.ACTION_VIEW`
     * when Shizuku is unavailable.
     *
     * Caller owns [target]'s creation; this function takes ownership
     * of cleanup. On the system-installer fallback path the file
     * survives long enough for the installer to read it and is
     * cleaned up by a delayed Handler.
     */
    private fun installStagedApkBlocking(target: File, written: Long): JSONObject {
        // Hand off to Shizuku — `pm install -S <size>` reads APK bytes
        // from stdin. Working directory of the shell process doesn't
        // need to see our cache dir; we pipe FileInputStream directly.
        //
        // NOTE: do NOT wrap the Shizuku call in a `finally { delete }`
        // here — the Shizuku-unavailable branch needs the staged APK
        // to survive long enough for the system installer to read it
        // via FileProvider. Each terminal path handles its own cleanup.
        val result = try {
            sh.haven.core.local.WaylandSocketHelper.execAsShizukuWithStdin(
                cmd = "pm install -S $written",
                stdin = target.inputStream(),
            )
        } catch (e: IllegalStateException) {
            // Shizuku unavailable — fall back to the system installer
            // dialog. The user gets one confirmation prompt on-device;
            // the MCP call returns immediately with pending=true. The
            // staged APK is intentionally not deleted here — the
            // system installer reads it via FileProvider after this
            // function returns. A delayed Handler cleans up after 5
            // min in case the user dismisses the dialog.
            return launchSystemInstaller(target, written, e.message)
        } catch (e: Exception) {
            target.delete()
            throw McpError(-32603, "Shizuku exec failed: ${e.message}")
        }
        // Shizuku path took ownership of the bytes; delete the staged
        // APK regardless of the install outcome.
        target.delete()
        if (result.exitCode != 0 || !result.output.contains("Success")) {
            throw McpError(
                -32603,
                "pm install exited ${result.exitCode}: ${result.output.take(500)}",
            )
        }
        return JSONObject().apply {
            put("installed", true)
            put("bytesDownloaded", written)
            put("output", result.output)
        }
    }

    private suspend fun installApkFromBackend(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        // Same gate as serve_file — installing an APK reads bytes
        // from a connected backend, so it falls under "allow agents
        // to read file contents". The system installer still asks
        // for confirmation on the Shizuku-less path; on the Shizuku
        // path the EVERY_CALL consent on this tool covers it.
        if (!preferencesRepository.agentAllowFileRead.first()) {
            throw McpError(
                -32011,
                "agent file read is disabled — enable in Settings → Agent endpoint",
            )
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val backend = resolution.backend

        val entry = try {
            backend.stat(path)
        } catch (t: Throwable) {
            throw McpError(-32603, "Failed to stat $path: ${t.message}")
        }
        if (entry.isDirectory) {
            throw McpError(-32602, "$path is a directory; need an APK file")
        }
        if (entry.size > agentInstallMaxBytes) {
            throw McpError(
                -32603,
                "$path is ${entry.size} bytes — exceeds ${agentInstallMaxBytes / (1024 * 1024)} MiB cap",
            )
        }

        // Streaming a large APK from a backend (SFTP over a flaky tunnel,
        // rclone over the network, Reticulum over the mesh) routinely outlasts
        // the MCP client's request budget — the call reads as a timeout even
        // though the install completes (observed installing Haven over itself
        // via the WG-tunnelled "near" SFTP). The existing self-install deferral
        // only covers the `pm install` commit, not this upstream download, so
        // the early-ack never reached the client. Fix: after the fast
        // validation above (gate / stat / dir / size cap — all return errors
        // synchronously), run the stream + install in the background and return
        // a pending ack the caller confirms with get_app_info.
        val sizeBytes = entry.size
        val backendLabel = backend.label
        reportInstallPhase(path, "downloading", 0, sizeBytes)
        backgroundScope.launch {
            runCatching {
                val (target, written) = streamToStagedApk(
                    openInput = { backend.openInputStream(path, 0) },
                    onProgress = { reportInstallPhase(path, "downloading", it, sizeBytes) },
                    expectedBytes = sizeBytes,
                ) {
                    "Read from $backendLabel failed: ${it.message}"
                }
                assertApkMagic(target, written)
                reportInstallPhase(path, "installing", written)
                installStagedApk(target, written)
            }.onFailure {
                android.util.Log.w("McpTools", "backend APK install failed ($path): ${it.message}")
                recordInstallOutcome(profileId, path, backendLabel, ok = false, detail = it.message)
            }.onSuccess {
                android.util.Log.i("McpTools", "backend APK install completed ($path): $it")
                // installStagedApk returns {installed} (Shizuku) or {pending}
                // (system-installer handoff) — both mean the background work
                // reached the install step without error.
                val staged = it.optBoolean("pending", false)
                recordInstallOutcome(
                    profileId, path, backendLabel,
                    ok = true,
                    detail = if (staged) "awaiting on-device install confirmation" else "installed",
                )
            }
            activeInstallProgress = null
        }
        val shizukuReady = sh.haven.core.local.WaylandSocketHelper.isShizukuAvailable() &&
            sh.haven.core.local.WaylandSocketHelper.hasShizukuPermission()
        JSONObject().apply {
            put("installed", false)
            put("pending", true)
            put("staging", true)
            put("bytes", sizeBytes)
            // Tells the caller whether the install will be silent (Shizuku) or
            // need a user tap on the staged-update notification (no Shizuku).
            put("shizukuReady", shizukuReady)
            put(
                "message",
                "Streaming $sizeBytes bytes from $backendLabel and installing in the " +
                    "background — backend transfers can outlast the request timeout, so this " +
                    "returns immediately rather than blocking. " +
                    (if (shizukuReady) {
                        "Shizuku is available: install is silent. "
                    } else {
                        "No Shizuku: a 'Haven update ready — tap to install' notification will " +
                            "appear (it survives backgrounding) — tap it to confirm. "
                    }) +
                    "Confirm the running build with get_app_info; if Haven is updating itself " +
                    "the MCP link drops on restart, so reconnect (/mcp reconnect) first.",
            )
        }
    }

    /**
     * Persist the terminal outcome of a backgrounded backend install so it's
     * discoverable after the `pending` ack: a queryable snapshot in
     * get_app_info's `lastInstall`, plus a connection-log entry (visible in
     * Settings → connection log; no-ops on an orphan/"local" profile id via the
     * repository's FK guard). (#245 follow-up)
     */
    private suspend fun recordInstallOutcome(
        profileId: String,
        path: String,
        backendLabel: String,
        ok: Boolean,
        detail: String?,
        tool: String = "install_apk_from_backend",
    ) {
        lastInstallResult = JSONObject().apply {
            put("ok", ok)
            put("path", path)
            put("backend", backendLabel)
            detail?.let { put("detail", it) }
        }
        runCatching {
            connectionLogRepository.logEvent(
                profileId,
                if (ok) {
                    sh.haven.core.data.db.entities.ConnectionLog.Status.CONNECTED
                } else {
                    sh.haven.core.data.db.entities.ConnectionLog.Status.FAILED
                },
                details = "$tool: $path — ${detail ?: if (ok) "ok" else "failed"}",
            )
        }
    }

    /**
     * Shizuku-less fallback for [installApkFromUrl] — fires the
     * system installer dialog via `Intent.ACTION_VIEW` with a
     * FileProvider URI for the staged APK. Returns a structured
     * response indicating the install is pending user confirmation
     * on-device.
     *
     * The APK stays in [target] until the system installer reads
     * it. A delayed cleanup job removes the file after a generous
     * window so it doesn't linger when the user dismisses the
     * dialog.
     */
    private fun launchSystemInstaller(target: java.io.File, written: Long, shizukuReason: String?): JSONObject {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
        } catch (e: Exception) {
            target.delete()
            throw McpError(
                -32603,
                "FileProvider URI build failed: ${e.message}. Cannot show system installer.",
            )
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }
        // Best-effort direct launch — works when Haven is foreground; Android
        // suppresses an activity start from the background (the install dialog
        // that "vanishes" when you switch away). The notification below is the
        // robust path: the user's tap is a foreground gesture the OS lets
        // through, so the installer appears even when Haven was backgrounded.
        val directLaunched = try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }

        var notified = false
        val versionName = runCatching {
            context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)?.versionName
        }.getOrNull()
        try {
            val managerCompat = NotificationManagerCompat.from(context)
            if (managerCompat.areNotificationsEnabled()) {
                ensureInstallNotificationChannel()
                val piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
                val pending = android.app.PendingIntent.getActivity(
                    context, target.absolutePath.hashCode(), intent, piFlags,
                )
                val notif = NotificationCompat.Builder(context, INSTALL_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Haven update ready")
                    .setContentText(
                        versionName?.let { "Tap to install Haven $it" }
                            ?: "Tap to install the staged update",
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setContentIntent(pending)
                    // Asks the OS to surface it immediately as a heads-up — the
                    // closest a backgrounded app gets to "comes to the foreground".
                    .setFullScreenIntent(pending, true)
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(context)
                    .notify(target.absolutePath.hashCode(), notif)
                notified = true
            }
        } catch (_: Exception) {
            // Notification is best-effort; a failed post must not abort the install.
        }

        // Keep the staged APK long enough for the user to tap the notification
        // (the direct dialog reads it in seconds; the notification path can be
        // minutes), then clean up so it doesn't linger.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { target.delete() },
            15 * 60 * 1000L,
        )

        if (!directLaunched && !notified) {
            target.delete()
            throw McpError(
                -32603,
                "Couldn't launch the installer or post a tap-to-install notification " +
                    "(Shizuku unavailable: ${shizukuReason ?: "no detail"}). " +
                    "Enable notifications for Haven, or start Shizuku for silent installs.",
            )
        }
        return JSONObject().apply {
            put("installed", false)
            put("pending", true)
            put("bytesDownloaded", written)
            put("shizukuReady", false)
            put("notified", notified)
            put(
                "message",
                buildString {
                    append("Shizuku unavailable (${shizukuReason ?: "not running"}) — no silent install. ")
                    if (notified) {
                        append("Posted a 'Haven update ready — tap to install' notification that " +
                            "survives Haven being backgrounded; tap it to confirm. ")
                    }
                    if (directLaunched) {
                        append("Also opened the installer directly (visible if Haven is in the foreground). ")
                    }
                    append("Start Shizuku for fully silent installs.")
                },
            )
        }
    }

    /**
     * Run `logcat -d` via Shizuku and return the captured block.
     *
     * Shizuku-as-shell-uid (2000) already holds READ_LOGS, so we don't have
     * to grant the permission to Haven itself — every call routes through
     * the same execAsShizuku path the other privileged tools use. That keeps
     * Haven's own permission set minimal and reuses the user's existing
     * Shizuku trust decision.
     *
     * `filter`, `packageName`, `pid` and `since` are all interpolated into
     * a `sh -c` command line, so each arg is validated to reject shell
     * metacharacters (`;`, `|`, `&`, `$`, backtick, newline) before reaching
     * the shell. `lines` and the response payload are capped so a sloppy
     * agent can't OOM the device or the MCP transport.
     */
    private suspend fun readLogcat(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val requestedLines = if (args.has("lines")) args.getInt("lines") else 200
        if (requestedLines < 1) {
            throw McpError(-32602, "lines must be >= 1")
        }
        val lines = requestedLines.coerceAtMost(MAX_LOGCAT_LINES)
        val truncatedByLines = requestedLines > MAX_LOGCAT_LINES

        val filter = args.optString("filter", "")
        if (filter.containsShellMetacharacter()) {
            throw McpError(-32602, "filter contains a shell metacharacter (; | & \$ ` newline)")
        }
        val packageName = args.optString("packageName", "").ifEmpty { null }
        val pid = if (args.has("pid")) args.getInt("pid").also {
            if (it < 1) throw McpError(-32602, "pid must be >= 1")
        } else null
        val since = args.optString("since", "").ifEmpty { null }
        if (since != null && since.containsShellMetacharacter()) {
            throw McpError(-32602, "since contains a shell metacharacter")
        }

        val uid: Int? = packageName?.let { resolvePackageUid(it) }

        val cmd = buildString {
            append("logcat -d -t $lines")
            if (uid != null) append(" --uid $uid")
            if (pid != null) append(" --pid $pid")
            if (since != null) append(" -T '$since'")
            if (filter.isNotEmpty()) {
                append(' ')
                append(filter)
            }
        }

        val result = runShizukuOrThrow(cmd, "logcat")

        var output = result.output
        var truncatedByBytes = false
        if (output.length > MAX_LOGCAT_BYTES) {
            output = output.substring(output.length - MAX_LOGCAT_BYTES)
            truncatedByBytes = true
            // Snap to the next line boundary so the first reported line
            // isn't a partial chunk from the middle of an earlier line.
            val nl = output.indexOf('\n')
            if (nl in 0 until 512) output = output.substring(nl + 1)
        }
        val lineCount = when {
            output.isEmpty() -> 0
            output.endsWith('\n') -> output.count { it == '\n' }
            else -> output.count { it == '\n' } + 1
        }
        JSONObject().apply {
            put("ok", true)
            put("lines", lineCount)
            put("truncated", truncatedByLines || truncatedByBytes)
            put("output", output)
            if (uid != null) put("uid", uid)
        }
    }

    /**
     * Resolve a package name to its installed UID via Shizuku-issued
     * `pm list packages -U <name>`. Returns the first exact-match line's
     * uid; throws an McpError with the same Shizuku-install hint as the
     * other privileged tools when Shizuku isn't reachable, and a
     * "package not installed" error when no exact match is found.
     */
    private fun resolvePackageUid(packageName: String): Int {
        if (packageName.isEmpty() ||
            packageName.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '_' }
        ) {
            throw McpError(-32602, "Invalid packageName: '$packageName'")
        }
        // `pm list packages -U <name>` returns exit 0 even when nothing
        // matches (empty output), so don't fail on a non-zero exit here —
        // the no-match case is handled by the parse below.
        val result = runShizukuOrThrow(
            "pm list packages -U $packageName", "pm list", requireSuccess = false,
        )
        // `pm list packages -U <name>` substring-matches the filter, so we
        // re-anchor to an exact "package:<name> " (or tab) prefix. Output
        // lines look like: "package:com.example uid:10123"
        val line = result.output.lineSequence()
            .firstOrNull {
                it.startsWith("package:$packageName ") ||
                    it.startsWith("package:$packageName\t")
            }
            ?: throw McpError(-32603, "Package not installed: $packageName")
        val uidMatch = Regex("uid:(\\d+)").find(line)
            ?: throw McpError(-32603, "Could not parse uid from pm output: $line")
        return uidMatch.groupValues[1].toInt()
    }

    private suspend fun enableWirelessAdb(): JSONObject = withContext(Dispatchers.IO) {
        // `cmd settings put global adb_wifi_enabled 1` is the
        // Android-11+ canonical flag. On older Android (<=10) the
        // setting key didn't exist and adb-over-tcp was driven by the
        // `service.adb.tcp.port` system property; we only target API
        // 26+ so the modern path is enough — older devices fall back
        // to the user toggling Developer Options by hand.
        runShizukuOrThrow("cmd settings put global adb_wifi_enabled 1", "settings put")
        // Read back the bound port so the agent can `adb connect <ip>:<port>` directly.
        val portReadback = try {
            WaylandSocketHelper.execAsShizuku("cmd settings get global adb_wifi_port")
        } catch (_: Exception) {
            null
        }
        val port = portReadback?.output?.trim()?.toIntOrNull()
        // Best-effort LAN IP discovery (excludes loopback/down/IPv6).
        val ip = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
        JSONObject().apply {
            put("enabled", true)
            put("ip", ip ?: JSONObject.NULL)
            put("port", port ?: JSONObject.NULL)
            put(
                "note",
                if (port != null) "If the host has paired before, run: adb connect ${ip ?: "<phone-ip>"}:$port"
                else "Open Settings → Developer Options → Wireless debugging on the device to read the pairing port if you need to pair a new host.",
            )
        }
    }

    /**
     * #575 v1: make pairing a Haven flow instead of a hunt through Settings.
     *
     * Ordering matters and is the whole trick. `_adb-tls-pairing._tcp` is
     * advertised only while the system dialog is open, so discovery is armed
     * BEFORE the dialog is launched — arming afterwards races a fast device and
     * misses the announcement. The connect port (`_adb-tls-connect._tcp`, what
     * `adb mdns services` shows in the steady state) is a different port and
     * pairing against it always fails.
     */
    private suspend fun startAdbPairing(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val timeoutMs = args.optLong("timeoutMs", AdbPairingDiscovery.DEFAULT_TIMEOUT_MS)
            .coerceIn(1_000L, 120_000L)
        val wantPrompt = args.optBoolean("showPrompt", args.optBoolean("showOverlay", true))

        // Best effort, NOT a precondition. Wireless debugging must be on for a
        // pairing listener to exist, but flipping it is the only privileged
        // step in this flow — mDNS discovery and the overlay need nothing. When
        // it is already on (the common case, and the case where the user most
        // wants this), demanding Shizuku would block the whole flow for no
        // reason. Found by running it: a reinstall drops Haven's Shizuku grant,
        // and the verb refused even though wireless debugging was already
        // advertising. If it really is off, discovery finds nothing and the
        // error below says so, carrying this failure as the likely cause.
        val flagResult = runCatching {
            runShizukuOrThrow("cmd settings put global adb_wifi_enabled 1", "settings put")
        }
        val wirelessDebuggingSet = flagResult.isSuccess

        val discovery = AdbPairingDiscovery(context)
        coroutineScope {
        val pending = async { discovery.awaitPairingEndpoint(timeoutMs) }

        // Best effort: the pairing dialog's component is not a public intent and
        // OEMs move it, so fall back to Developer Options and let the user take
        // the last step. Either way the discovery above is already listening.
        val screenOpened = openPairingDialog()
        // The screen is not the dialog. Say which control makes the port exist,
        // on the device, because the MCP reply below never reaches the person
        // holding it.
        if (screenOpened) postPairingSteps()

        val endpoint = pending.await()
            ?: throw McpError(
                -32603,
                buildString {
                    append("No pairing service appeared within ${timeoutMs}ms. ")
                    if (!wirelessDebuggingSet) {
                        append(
                            "Wireless debugging could not be turned on from here (" +
                                "${flagResult.exceptionOrNull()?.message ?: "Shizuku unavailable"}), " +
                                "so it may simply be off. ",
                        )
                    }
                    append(
                        "Open Settings → Developer options → Wireless debugging → " +
                            "\"Pair device with pairing code\" — the listener only exists while that " +
                            "dialog is on screen.",
                    )
                },
            )

        // Fresh latch per attempt: a code typed for a previous, expired dialog
        // must never satisfy this one.
        val codeLatch = kotlinx.coroutines.CompletableDeferred<String>()
        pendingPairingCode = codeLatch
        pendingPairingEndpoint = endpoint

        // A direct-reply notification, NOT an overlay. Settings windows set
        // HIDE_NON_SYSTEM_OVERLAY_WINDOWS (captured on-device), so the platform
        // hides any TYPE_APPLICATION_OVERLAY while the pairing dialog is up —
        // the overlay was drawn and never displayed. A notification's reply
        // field is rendered by system UI, so it is unaffected, needs no
        // SYSTEM_ALERT_WINDOW, and lands as a heads-up above the dialog.
        var promptShown = false
        if (wantPrompt) {
            val prompt = AdbPairingPrompt(context)
            pendingPairingPrompt = prompt
            withContext(Dispatchers.Main) {
                promptShown = prompt.show(endpoint) { code -> codeLatch.complete(code) }
            }
            // The "tap Pair device with pairing code" steps were for getting to
            // this point; leaving them beside the reply prompt puts two Haven
            // notifications on screen, one of them stale advice, and invites the
            // user to act on the wrong one. Clear it the moment the prompt that
            // supersedes it is up.
            if (promptShown) {
                runCatching {
                    NotificationManagerCompat.from(context).cancel(PAIRING_STEPS_NOTIFICATION_ID)
                }
            }
        }

        JSONObject().apply {
            put("host", endpoint.host)
            put("port", endpoint.port)
            put("pairCommand", "adb pair ${endpoint.host}:${endpoint.port} <code>")
            put("codeSource", "system-dialog")
            // Names what actually opened: the Wireless debugging screen. The
            // pairing dialog — and the port — appear only when the user taps
            // "Pair device with pairing code" on it.
            put("wirelessDebuggingScreenOpened", screenOpened)
            put("wirelessDebuggingSet", wirelessDebuggingSet)
            put("codePromptShown", promptShown)
            if (wantPrompt && !promptShown) {
                put(
                    "codePromptFix",
                    "The code prompt could not be posted — Haven has no notification permission. " +
                        "Grant notifications, or read the 6-digit code off the dialog and pair " +
                        "from the workstation with pairCommand.",
                )
            }
            put(
                "note",
                "The 6-digit code is on the device's pairing dialog; it is generated and drawn by the " +
                    "system and no API exposes it. This port is valid only while that dialog stays open.",
            )
        }
        }
    }

    /**
     * Set by [startAdbPairing], completed by the on-device overlay, consumed by
     * `await_adb_pairing_code`. This is the whole v1 relay: Haven cannot run
     * `adb pair` itself (that needs SPAKE2-EE over TLS-PSK, see #575 v2), so the
     * code the user types on the device is handed to the agent, which runs the
     * pair command from the workstation that actually wants access.
     */
    @Volatile
    private var pendingPairingCode: kotlinx.coroutines.CompletableDeferred<String>? = null

    @Volatile
    private var pendingPairingEndpoint: AdbPairingDiscovery.Endpoint? = null

    /** Held so a completed or abandoned pairing tears its notification down. */
    @Volatile
    private var pendingPairingPrompt: AdbPairingPrompt? = null

    /** Await the code typed into the on-device overlay (#575). */
    private suspend fun awaitAdbPairingCode(args: JSONObject): JSONObject {
        val latch = pendingPairingCode
            ?: throw McpError(-32603, "No pairing in progress — call start_adb_pairing first.")
        val timeoutMs = args.optLong("timeoutMs", 120_000L).coerceIn(1_000L, 300_000L)
        val code = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            runCatching { latch.await() }.getOrNull()
        } ?: run {
            // A reply box for a dead port is worse than none.
            pendingPairingPrompt?.let { it.unregister(); it.cancel() }
            pendingPairingPrompt = null
            throw McpError(
                -32603,
                "No code was entered within ${timeoutMs}ms. The pairing port expires with the " +
                    "dialog, so start_adb_pairing again for a fresh one.",
            )
        }
        val endpoint = pendingPairingEndpoint
        pendingPairingCode = null
        pendingPairingEndpoint = null
        pendingPairingPrompt?.let { it.unregister(); it.cancel() }
        pendingPairingPrompt = null
        return JSONObject().apply {
            put("code", code)
            if (endpoint != null) {
                put("host", endpoint.host)
                put("port", endpoint.port)
                put("pairCommand", "adb pair ${endpoint.host}:${endpoint.port} $code")
            }
        }
    }

    /**
     * Report and, when missing, offer the "Display over other apps" grant that
     * the pairing code box needs (#575).
     *
     * Kept separate from [startAdbPairing] on purpose: the grant screen is a
     * full-screen settings activity, so opening it mid-pairing would background
     * Android's pairing dialog and expire the ephemeral port along with it.
     */
    /** On-device instruction for the pairing screen (#575). */
    private fun postPairingSteps() {
        runCatching {
            ensureAgentNotificationChannel()
            val body = "Tap \"Pair device with pairing code\" on this screen.\n\n" +
                "Haven is watching for the pairing port, which only exists while that dialog " +
                "is open. Read the 6-digit code off it — the system generates it and Haven " +
                "cannot see it."
            val notification = NotificationCompat.Builder(context, AGENT_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pairing a computer with adb")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context)
                    .notify(PAIRING_STEPS_NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // POST_NOTIFICATIONS revoked between the channel setup and here.
                android.util.Log.d("McpTools", "pairing steps not posted: ${e.message}")
            }
        }.onFailure { android.util.Log.d("McpTools", "pairing steps not posted: ${it.message}") }
    }

    /**
     * Launch Android's pairing dialog. There is no public intent for it, so try
     * the AOSP component first and fall back to Developer Options.
     */
    private fun openPairingDialog(): Boolean {
        val candidates = listOf(
            android.content.ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$WirelessDebuggingActivity",
            ),
        )
        for (component in candidates) {
            val intent = Intent().apply {
                setComponent(component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /**
     * Expose the device's adb to the workstation over the MCP tunnel, VPN-proof.
     * Enables classic adb-over-TCP on [DEFAULT_ADB_PORT] (or the requested port)
     * via Shizuku, persists the port so the forward is re-armed across tunnel
     * rebuilds, then binds the reverse forward onto the live tunnel session. The
     * phone-side hop is loopback, so it bypasses any system VpnService.
     */
    private suspend fun exposeAdb(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val port = if (args.has("port")) args.getInt("port") else DEFAULT_ADB_PORT
        if (port < 1024 || port > 65535) {
            throw McpError(-32602, "port must be between 1024 and 65535")
        }
        enableAdbTcpipViaShizuku(port)
        preferencesRepository.setMcpAdbExposedPort(port)
        val bound = mcpTunnelManager.exposeAdbPort(port)
        JSONObject().apply {
            put("method", "tcpip")
            put("adbPort", port)
            put("activated", bound)
            put("connect", "adb connect localhost:$port")
            put(
                "note",
                if (bound) {
                    "adbd exposed on the MCP tunnel endpoint (127.0.0.1:$port). On the " +
                        "workstation run: adb connect localhost:$port — the loopback hop " +
                        "bypasses any system VPN. Verify with `adb devices`."
                } else {
                    "adb enabled and armed, but the MCP tunnel is not up right now — the " +
                        "forward binds automatically once the tunnel connects, then: " +
                        "adb connect localhost:$port."
                },
            )
        }
    }

    /**
     * Reverse of [exposeAdb]: drop the reverse forward, clear the persisted port,
     * and return adbd to USB-only. Best-effort on the Shizuku step so the more
     * important security action (removing the forward) always completes.
     */
    private suspend fun unexposeAdb(): JSONObject = withContext(Dispatchers.IO) {
        val port = preferencesRepository.mcpAdbExposedPort.first() ?: DEFAULT_ADB_PORT
        mcpTunnelManager.unexposeAdbPort(port)
        preferencesRepository.setMcpAdbExposedPort(null)
        disableAdbTcpipViaShizuku()
        JSONObject().apply {
            put("disabled", true)
            put("adbPort", port)
            put("note", "Removed the adb reverse forward and disabled adb-over-TCP on the device.")
        }
    }

    /**
     * Enable classic adb-over-TCP on `127.0.0.1:[port]` (and every interface)
     * via Shizuku: set `service.adb.tcp.port` then bounce adbd to pick it up.
     * Unlike Android-11 wireless debugging this needs no per-host pairing and
     * uses a fixed port. `service.adb.tcp.port` is volatile (cleared on reboot),
     * so the exposure is per-boot and re-armed by re-running the tool.
     *
     * Throws [McpError] if Shizuku is unavailable/unpermitted, or if the prop
     * didn't take / adbd couldn't be restarted (e.g. SELinux denies `ctl.restart`
     * to the shell uid and `stop/start adbd` is also denied — then Shizuku must
     * be in root mode).
     */
    private fun enableAdbTcpipViaShizuku(port: Int) {
        val cmd = "setprop service.adb.tcp.port $port && " +
            "(setprop ctl.restart adbd 2>/dev/null || (stop adbd; start adbd)) && " +
            "echo ADBTCP port=\$(getprop service.adb.tcp.port)"
        // requireSuccess=false: the precise success criterion is the port
        // readback below (adbd restart can print noise yet still succeed).
        val result = runShizukuOrThrow(cmd, "adb-over-TCP", requireSuccess = false)
        val readBack = Regex("port=(\\d+)").find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (result.exitCode != 0 || readBack != port) {
            throw McpError(
                -32603,
                "Could not enable adb-over-TCP on :$port (exit=${result.exitCode}, " +
                    "service.adb.tcp.port=$readBack). Output: ${result.output.trim()}. " +
                    "If adbd restart is blocked, ensure Shizuku is running in root mode.",
            )
        }
    }

    /** Disable adb-over-TCP (return adbd to USB-only). Best-effort. */
    private fun disableAdbTcpipViaShizuku() {
        val cmd = "setprop service.adb.tcp.port -1 && " +
            "(setprop ctl.restart adbd 2>/dev/null || (stop adbd; start adbd))"
        runCatching { WaylandSocketHelper.execAsShizuku(cmd) }
    }

    /**
     * The Bridges registry (Phase 0, read-only reflection): aggregate every
     * capability Haven is currently brokering to a sink. Inline for now — no
     * BridgeRegistry abstraction until ≥1 caller needs it beyond this + the UI
     * (docs/design/bridges.md). Generalises listUsbExports.
     */
    private suspend fun listBridges(): JSONObject = withContext(Dispatchers.IO) {
        val devices = usbBroker.listDevices()
        fun usbSource(name: String?): String {
            if (name == null) return "USB device"
            return devices.firstOrNull { it.deviceName == name }?.productName?.let { "USB: $it" } ?: "USB $name"
        }
        fun busidOf(name: String): String {
            val p = name.trimEnd('/').split('/')
            return "${p.getOrNull(p.size - 2)?.toIntOrNull() ?: 1}-${p.lastOrNull()?.toIntOrNull() ?: 1}"
        }
        val bridges = JSONArray()
        fun add(source: String, kind: String, sink: String, transport: String, state: String, detail: JSONObject.() -> Unit = {}) {
            bridges.put(JSONObject().apply {
                put("source", source); put("sourceKind", kind); put("sink", sink)
                put("transport", transport); put("state", state); detail()
            })
        }

        // USB device → remote host (USB/IP)
        if (usbIpServer.isRunning) {
            val n = usbIpServer.exportedDeviceName
            add(usbSource(n), "usb", "remote-host", "usbip", "active") {
                if (n != null) put("busid", busidOf(n))
                put("port", usbIpServer.boundPort ?: JSONObject.NULL)
                put("clients", usbIpServer.clientCount)
            }
        }
        // USB device → QEMU/SPICE guest (usbredir, #613)
        if (usbRedirServer.isRunning) {
            val n = usbRedirServer.exportedDeviceName
            add(usbSource(n), "usb", "qemu-guest", "usbredir", "active") {
                put("port", usbRedirServer.boundPort.takeIf { it > 0 } ?: JSONObject.NULL)
                put("clients", usbRedirServer.clientCount)
            }
        }
        // USB device → Linux guest (haven-usb shim)
        if (usbProxyServer.isRunning) {
            val n = usbProxyServer.proxyDeviceName
            add(usbSource(n), "usb", "linux-guest", "haven-usb-shim", "active") {
                put("socket", usbProxyServer.socketName)
            }
        }
        // USB drive(s) → local VM (#287) — one bridge entry per open drive.
        usbDriveVmManager.sessions.value.values.forEach { vm ->
            add(usbSource(vm.deviceName), "usb", "local-vm", "qemu+usbip", vm.phase.name.lowercase()) {
                if (vm.busid != null) put("busid", vm.busid)
                put("profileId", vm.profileId ?: JSONObject.NULL)
                put("mounts", JSONArray().apply { vm.mounts.forEach { put(it) } })
                if (vm.stage.isNotBlank()) put("stage", vm.stage)
            }
        }
        // Audio out → Linux guest (PulseAudio over loopback)
        val audio = localSessionManager.audioBridge.statusNow()
        if (audio.state == sh.haven.core.local.AudioBridge.State.RUNNING ||
            audio.state == sh.haven.core.local.AudioBridge.State.STARTING
        ) {
            add("Audio out", "audio", "linux-guest", "pulseaudio-tcp", audio.state.name.lowercase()) {
                put("port", audio.port)
            }
        }
        // adb → workstation (reverse tunnel)
        val adbPort = preferencesRepository.mcpAdbExposedPort.first()
        if (adbPort != null) {
            add("adb", "adb", "workstation", "reverse-tunnel", "active") { put("port", adbPort) }
        }

        // GPS broker (bridges.md GPS row): the continuous log feeds the
        // agent; the GPS-disciplined NTP service feeds the LAN.
        if (GpsBroker.isLogging || GpsBroker.isNtpRunning || GpsBroker.isGuestBridgeRunning) {
            val gps = GpsBroker.statusJson()
            if (GpsBroker.isLogging) {
                add("GPS", "gps", "agent", "gps-log-jsonl", "active") {
                    gps.optJSONObject("logging")?.let { l ->
                        put("id", l.optString("id"))
                        put("file", l.optString("file"))
                        put("fixes", l.optLong("fixes"))
                    }
                }
            }
            gps.optJSONObject("ntp")?.let { n ->
                add("GPS time", "gps", "lan", "sntp", "active") {
                    put("port", n.optInt("port"))
                    if (!n.isNull("lanBind")) put("lanBind", n.optString("lanBind"))
                    put("requests", n.optLong("requests"))
                }
            }
            gps.optJSONObject("guestBridge")?.let { g ->
                add("GPS", "gps", "linux-guest", "gpsd-nmea", "active") {
                    put("socketName", g.optString("socketName"))
                    put("readers", g.optInt("readers"))
                    put("sentences", g.optLong("sentences"))
                    put("dropped", g.optLong("dropped"))
                }
            }
        }

        JSONObject().apply {
            put("bridges", bridges)
            put("count", bridges.length())
            put(
                "note",
                "Every phone capability Haven is currently brokering to a sink (agent / linux-guest / " +
                    "local-vm / remote-host / workstation). Read-only; generalises list_usb_exports. " +
                    "Bridges/Devices manager — docs/design/bridges.md.",
            )
        }
    }

    private suspend fun openLocalShell(args: JSONObject = JSONObject()): JSONObject {
        val rawMode = args.optString("mode", "PROOT").trim().uppercase().ifBlank { "PROOT" }
        val shellMode = runCatching { ConnectionProfile.LocalShellMode.valueOf(rawMode) }
            .getOrElse { throw IllegalArgumentException("mode must be PROOT, ANDROID_SHELL, or SHIZUKU") }
        return attachAgentShell(plain = args.optBoolean("plain", false), desktopEnv = null, reuse = true, shellMode = shellMode)
    }

    /**
     * Open (or, when [reuse], adopt) the canonical local shell session for [shellMode],
     * spin up a headless PTY + agent emulator, and return its session JSON.
     * When [desktopEnv] is non-null the shell joins a running desktop (#285)
     * via DISPLAY / WAYLAND_DISPLAY / XDG_RUNTIME_DIR — pass [reuse]=false in
     * that case so a fresh session actually carries the env (a reused plain
     * shell would lack it).
     */
    private suspend fun attachAgentShell(
        plain: Boolean,
        desktopEnv: Map<String, String>?,
        reuse: Boolean,
        shellMode: ConnectionProfile.LocalShellMode = ConnectionProfile.LocalShellMode.PROOT,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (shellMode == ConnectionProfile.LocalShellMode.SHIZUKU) localSessionManager.ensureShizukuReady()
        val defaultLabel = when (shellMode) {
            ConnectionProfile.LocalShellMode.SHIZUKU -> "Shizuku Shell"
            ConnectionProfile.LocalShellMode.ANDROID_SHELL -> "Android Shell"
            ConnectionProfile.LocalShellMode.PROOT -> "Local Shell"
        }
        // Find or seed the canonical profile for the requested local shell mode.
        // We deliberately do NOT trigger a rootfs install from here — that's a
        // long-running install step that belongs behind explicit user consent in
        // the UI; if the rootfs is missing PRoot falls back to /system/bin/sh.
        val existing = connectionRepository.getAll().firstOrNull {
            it.connectionType == "LOCAL" && it.resolvedLocalShellMode == shellMode
        }
        val profile = existing ?: run {
            val seeded = ConnectionProfile(
                label = defaultLabel, host = "localhost", username = "", port = 0, connectionType = "LOCAL",
                useAndroidShell = shellMode == ConnectionProfile.LocalShellMode.ANDROID_SHELL, localShellMode = shellMode.name,
            )
            connectionRepository.save(seeded)
            connectionRepository.getAll()
                .firstOrNull { it.connectionType == "LOCAL" && it.label == defaultLabel && it.resolvedLocalShellMode == shellMode }
                ?: seeded
        }
        // Reuse a connected session for this profile if one is already
        // alive — there's no point spinning up a second PRoot when the
        // existing one is exactly what the agent wants. Multiple shells
        // on the same profile is a valid pattern, but the simple "give
        // me a sessionId I can type into" use case is best served by
        // the shortest path.
        val alive = if (reuse) {
            localSessionManager.getSessionsForProfile(profile.id)
                .firstOrNull { it.status == LocalSessionManager.SessionState.Status.CONNECTED }
        } else null
        val sessionId = alive?.sessionId ?: run {
            // Leave status as CONNECTING until terminalSessionRegistry has its
            // headless handles installed and startHeadlessShell transitions to
            // CONNECTED + attaches the LocalSession atomically under
            // sessionAttachLock (#555). Calling connectSession before the
            // preference reads below exposed isReadyForTerminal == true while
            // suspended, letting TerminalViewModel.syncSessions race ahead and
            // fork a second PTY.
            localSessionManager.registerSession(
                profile.id, profile.label, shellMode == ConnectionProfile.LocalShellMode.ANDROID_SHELL,
                profile.prootDistroId, desktopEnv = desktopEnv, localShellMode = shellMode,
            )
        }
        // Spin up the PTY without a UI tab on top so the agent can
        // immediately type into the session. Idempotent — a no-op if
        // the user already has a terminal tab open on this profile.
        // Also tees the data stream into a TerminalEmulator and
        // registers it with terminalSessionRegistry so the structured
        // MCP tools (read_terminal_snapshot, tap_terminal,
        // send_terminal_input) work against headless agent shells —
        // not just sessions the user has visited in the Terminal tab
        // (gap surfaced during v5.32.0-rc2 MCP testing). When the
        // Terminal tab adopts the session later, it shares this same
        // emulator, so agent reads and the on-screen tab agree.
        //
        // Check `agentHandles == null` rather than `get(sessionId) == null`:
        // when the user opened the UI tab first, `get(sessionId)` is already
        // non-null with `agentHandles == null`; installing backing AgentHandles
        // + tee behind the tab lets `restoreAgentHandles` preserve structured
        // agent reads if the Activity is later torn down (#555).
        var agentFeed: ((ByteArray, Int, Int) -> Unit)? = null
        var feedMainHandler: android.os.Handler? = null
        if (terminalSessionRegistry.get(sessionId)?.agentHandles == null) {
            val scrollbackRows = preferencesRepository.terminalScrollbackRows.first()
            val bellNotif = preferencesRepository.terminalBellNotification.first()
            val agentEmulator = org.connectbot.terminal.TerminalEmulatorFactory.create(
                autoDetectUrls = true,
                initialRows = 24,
                initialCols = 80,
                onKeyboardInput = { data ->
                    try {
                        localSessionManager.sendInput(sessionId, String(data, Charsets.UTF_8))
                    } catch (e: Exception) {
                        // Session went away mid-write; agents recover via list_sessions.
                    }
                },
                onResize = { dims ->
                    // When a UI tab adopts this headless session, HavenTerminal
                    // sizes the emulator from the Compose canvas — propagate
                    // that to the underlying PTY so `clear`/nano/etc. see the
                    // real viewport. Looking the LocalSession up each call
                    // tolerates the chicken-and-egg between emulator creation
                    // and startHeadlessShell.
                    localSessionManager.getActiveSession(sessionId)?.resize(dims.columns, dims.rows)
                },
                maxScrollbackLines = scrollbackRows,
                xtversion = sh.haven.feature.terminal.XtversionIdentity.of(context),
                // #753: BEL → background notification naming the session.
                onBell = {
                    if (bellNotif) sh.haven.feature.terminal.showBellNotification(context, profile.label)
                },
            )
            // writeInput must happen on the main looper — libvterm's
            // OSC dispatch is wired against the same thread that
            // initialised the native state, and cross-thread writes
            // succeed at the cell level but silently drop OSC callbacks
            // (notably 133 prompt markers and OSC 2 title). Mirroring
            // EmulatorWriteBuffer's main-thread post pattern.
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            // Track DECSET private modes (bracketed paste 2004, mouse) on the
            // agent feed — the one choke point both live PTY output and
            // feed_terminal_output pass through. Without it a headless shell's
            // bracketPasteMode read null forever and send_to_agent never
            // bracket-pasted to a REPL in a local shell (#336). Synchronized:
            // the PTY reader thread and an MCP feed call can interleave.
            val modeTracker = sh.haven.feature.terminal.MouseModeTracker()
            val feed: (ByteArray, Int, Int) -> Unit = { data, off, len ->
                synchronized(modeTracker) { modeTracker.process(data, off, len) }
                val copy = data.copyOfRange(off, off + len)
                mainHandler.post { agentEmulator.writeInput(copy, 0, copy.size) }
            }
            if (terminalSessionRegistry.attachOrRegisterHeadless(
                    sessionId,
                    agentEmulator,
                    feed,
                    mouseMode = modeTracker.mouseMode,
                    activeMouseMode = modeTracker.activeMouseMode,
                    bracketPasteMode = modeTracker.bracketPasteMode,
                    altScreen = modeTracker.altScreen,
                    cursorKeyAppMode = modeTracker.cursorKeyAppMode,
                )
            ) {
                agentFeed = feed
                feedMainHandler = mainHandler
            }
        }
        // Transitions CONNECTING -> CONNECTED and starts the PTY under
        // sessionAttachLock, or attaches the tee (replaying buffered scrollback)
        // when an existing LocalSession is already running (#555).
        try {
            localSessionManager.startHeadlessShell(
                sessionId,
                extraOnData = agentFeed,
                plain = plain,
            )
        } catch (e: Exception) {
            if (alive == null) {
                terminalSessionRegistry.unregister(sessionId)
                localSessionManager.updateStatus(sessionId, LocalSessionManager.SessionState.Status.ERROR)
                localSessionManager.removeSession(sessionId)
            }
            throw McpError(-32603, "Failed to open local shell: ${e.message}")
        }
        // The PTY reader thread starts asynchronously, so wait briefly for the
        // shell's first output before returning — otherwise an immediate
        // send_terminal_input / read_terminal_scrollback races an unattached
        // PTY (input ignored, scrollback empty). `ready` tells the agent
        // whether output is already flowing.
        val ready = localSessionManager.awaitFirstOutput(sessionId)
        // Drain any main-looper `agentEmulator.writeInput` runnables queued by
        // scrollback replay or initial prompt chunks so an immediate
        // read_terminal_snapshot sees the rendered prompt (#555).
        val drainHandler = feedMainHandler
            ?: if (ready) android.os.Looper.getMainLooper()?.let { android.os.Handler(it) } else null
        if (drainHandler != null) {
            withTimeoutOrNull(500L) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    val posted = drainHandler.post {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                    if (!posted && cont.isActive) {
                        cont.resumeWith(Result.success(Unit))
                    }
                }
            }
        }
        JSONObject().apply {
            put("sessionId", sessionId)
            put("profileId", profile.id)
            put("label", profile.label)
            put("reused", alive != null)
            put("plain", plain)
            put("ready", ready)
        }
    }

    /**
     * Open a local PRoot shell whose environment joins a RUNNING desktop (#285)
     * — DISPLAY / WAYLAND_DISPLAY / XDG_RUNTIME_DIR — so the agent can drive the
     * desktop's apps from a shell. Always a fresh session (a reused plain shell
     * would lack the display env). Returns the session JSON plus the resolved
     * display vars for verification.
     */

    private suspend fun setTerminalFontFromUrl(args: JSONObject): JSONObject {
        val urlString = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        // Delegate to the shared installer so the agent and the user
        // surfaces touch identical bytes — same download path, same
        // validation, same path layout, same preference write.
        return when (val r = terminalFontInstaller.installFromUrl(urlString)) {
            is TerminalFontInstaller.Result.Success -> JSONObject().apply {
                put("path", r.path)
                put("bytesDownloaded", r.bytesInstalled)
            }
            is TerminalFontInstaller.Result.Failure -> throw McpError(-32603, r.message)
        }
    }

    private suspend fun convertFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val sourceUrl = args.optString("sourceUrl").ifEmpty {
            throw McpError(-32602, "Missing required argument: sourceUrl")
        }
        val container = args.optString("container").ifEmpty {
            throw McpError(-32602, "Missing required argument: container")
        }
        val videoEncoder = args.optString("videoEncoder", "copy").ifEmpty { "copy" }
        val audioEncoder = args.optString("audioEncoder", "copy").ifEmpty { "copy" }
        if (!ffmpegExecutor.isAvailable()) {
            throw McpError(-32603, "ffmpeg is not available in this build")
        }
        val outputDir = File(context.cacheDir, "agent-convert").apply { mkdirs() }
        val outputName = args.optString("outputName", "").ifEmpty {
            "agent-convert-${System.currentTimeMillis()}"
        }
        val outputFile = File(outputDir, "$outputName.$container")
        val cmd = TranscodeCommand(input = sourceUrl, output = outputFile.absolutePath)
            .videoCodec(videoEncoder)
            .audioCodec(audioEncoder)
            .build()
        val started = System.currentTimeMillis()
        val result = ffmpegExecutor.execute(cmd)
        val elapsedMs = System.currentTimeMillis() - started
        if (result.exitCode != 0) {
            // Surface the last few stderr lines so the agent can react
            // to common failures (codec missing, source unreachable).
            val tail = result.stderr.lineSequence().toList().takeLast(8).joinToString("\n")
            throw McpError(-32603, "ffmpeg exit ${result.exitCode}: $tail")
        }
        JSONObject().apply {
            put("outputPath", outputFile.absolutePath)
            put("sizeBytes", outputFile.length())
            put("durationMs", elapsedMs)
            put("container", container)
            put("videoEncoder", videoEncoder)
            put("audioEncoder", audioEncoder)
        }
    }

    companion object {
        /**
         * Port the MCP HTTP server binds on the device and the matching
         * `-R` forward Haven installs on the SSH session. Kept in sync
         * with `feature/connections`'s MCP_REVERSE_TUNNEL_PORT (`#161`).
         */
        internal const val MCP_REVERSE_TUNNEL_PORT = 8730

        /**
         * Default loopback port for classic `adb tcpip` exposed via
         * [exposeAdb]. 5555 is the conventional adb-over-TCP port and needs no
         * per-host pairing (unlike Android-11 wireless debugging).
         */
        private const val DEFAULT_ADB_PORT = 5555

        /**
         * Upper bound on a [presentMedia] payload (8 MiB). present_media
         * holds the bytes in a cache file and decodes them on the UI side;
         * anything larger should go through serve_file/play_file instead
         * of inline base64 over JSON-RPC.
         */
        private const val MAX_PRESENT_BYTES = 8 * 1024 * 1024

        /**
         * Best-effort fallback regex matching the trailing
         * prompt glyph of common interactive shells and REPLs
         * at end-of-line. Covers `$` (bash/sh/zsh user), `#`
         * (root), `%` (csh), `>` (cmd.exe-style and traditional
         * minimal), and `❯` (Claude Code, fish, starship).
         * Multiline + ANSI-stripped before matching in
         * [TerminalInputQueue]; the watcher's tail-only scope
         * (last 512 chars of stripped scrollback) keeps the
         * character class from matching `$` redirects or `>`
         * in earlier output.
         *
         * Callers should supply their own `promptPattern` when
         * they know what's running on the other end — e.g.
         * `\[y/N\]\s*$` for an install script,
         * `Password:\s*$` for a password prompt. The default
         * exists for the convenience of the "drive an
         * interactive shell or REPL" case where the exact
         * prompt isn't pinned in advance.
         */
        internal const val DEFAULT_PROMPT_PATTERN = "[\\\$#%>❯]\\s*\$"

        /**
         * Default key bytes sent after the queued text. `\r` (CR) is
         * what TTYs in cooked mode translate to NL, and what programs
         * in raw mode (Claude Code, vim, less, fzf, readline-based
         * shells) read as the Enter key. Callers can override:
         * - `\n` (LF) for line-buffered programs reading stdin
         *   directly without a tty.
         * - `""` (empty) to leave the text in the input buffer
         *   without submitting — e.g. pre-filling a prompt the user
         *   will edit further before pressing Enter themselves.
         */
        internal const val DEFAULT_SUBMIT_KEY = "\r"

        /**
         * Pause between sending the text body and a trailing submit key in
         * send_terminal_input, so the submit lands in its own read() rather than
         * folding into the text burst (a raw-mode REPL's input batching otherwise
         * stages the text without submitting — verified on Claude Code). #14
         */
        internal const val SUBMIT_SETTLE_MS = 150L

        /**
         * Default cap on the post-send snapshot send_to_agent returns. An
         * unbounded full-scrollback snapshot is a large response that, over a
         * tunnel, can outlast the caller's MCP timeout — the message delivers
         * but the call reads back as a timeout (pilz hit this over WireGuard,
         * while a manual send_terminal_input with the same write path but a
         * smaller snapshot succeeded). A screenful is what a sender wants to
         * see anyway; an explicit maxLines still wins.
         */
        internal const val SEND_TO_AGENT_SNAPSHOT_LINES = 50
    }

}

/**
 * Consent gating metadata for a single tool, looked up by [McpServer]
 * before dispatching the call so destructive tools can prompt the user
 * with an action-specific summary.
 *
 * Read-only and tap-equivalent tools use [ConsentLevel.NEVER] and skip
 * the prompt entirely; the [summary] still has to be a function so the
 * registry shape stays uniform but is unused in that branch.
 */
data class ToolConsent(
    val level: ConsentLevel,
    val summary: (JSONObject) -> String,
)

class ToolHandler(
    val description: String,
    val inputSchema: JSONObject,
    /** Consent level the dispatcher applies before invoking [invoke]. */
    val consentLevel: ConsentLevel = ConsentLevel.NEVER,
    /**
     * An extra Settings capability toggle this tool sits behind, on top of
     * the global agent-endpoint switch and per-action consent, or null for
     * an ungated tool. Declared here so the dispatcher gates by a tool
     * PROPERTY, not by matching tool names in the transport layer
     * (#mcp-backbone Stage 5, Layer E). See [McpTools.capabilityDenial].
     */
    val capability: AgentCapability? = null,
    /**
     * Optional Settings switch category ([sh.haven.core.data.agent.McpToolCategory])
     * declared inline at the registration site. When null, falls back to
     * [sh.haven.core.data.agent.McpToolCategory.of] or [ToolProvider.defaultCategory].
     */
    val category: sh.haven.core.data.agent.McpToolCategory? = null,
    /**
     * Builds the human-readable one-liner shown in the consent prompt
     * for non-NEVER tools. Default is just the tool name; per-tool
     * registrations should override with something specific so the user
     * understands what they're approving.
     */
    val summarise: (JSONObject) -> String = { "tool call" },
    /**
     * The handler body. Returns a plain [JSONObject] for the common
     * structured tool (wrapped as [ToolResult.Structured] by [handle]), or a
     * [ToolResult] directly for the few tools that produce an image or a
     * guest-passthrough — so those need no reserved `__`-prefixed keys
     * (#mcp-backbone Stage 5, Layer E). The `Any` union avoids a
     * suspend-lambda overload clash (both function types erase to the same
     * JVM signature) and keeps every registration's `{ args -> … }` shape
     * unchanged.
     */
    private val invoke: suspend (JSONObject) -> Any,
) {
    fun withCategory(cat: sh.haven.core.data.agent.McpToolCategory): ToolHandler = ToolHandler(
        description = description,
        inputSchema = inputSchema,
        consentLevel = consentLevel,
        capability = capability,
        category = cat,
        summarise = summarise,
        invoke = invoke,
    )

    suspend fun handle(args: JSONObject): ToolResult = when (val r = invoke(args)) {
        is ToolResult -> r
        is JSONObject -> ToolResult.Structured(r)
        else -> error("Tool handler returned ${r::class.simpleName}, expected JSONObject or ToolResult")
    }
}

/**
 * An on/off Settings capability a tool can sit behind, evaluated before
 * consent (#mcp-backbone Stage 5). A tool declares one via
 * [ToolHandler.capability] instead of the dispatcher special-casing its name.
 */
enum class AgentCapability { FILE_READ, TERMINAL_INPUT_QUEUE }

/**
 * A tool invocation's result as a typed value (#mcp-backbone Stage 5, Layer E),
 * so the transport renders MCP content from a type instead of sniffing reserved
 * `__`-prefixed keys out of a JSONObject. [structured] is the tool's structured
 * payload, present on every variant (the audit summariser and the mail-rules
 * executor read it uniformly).
 */
sealed interface ToolResult {
    val structured: JSONObject

    /** The ordinary case: a structured JSON result. */
    data class Structured(override val structured: JSONObject) : ToolResult

    /** An inline image plus its structured echo (capture_* tools). */
    data class Image(
        val base64: String,
        val mimeType: String,
        override val structured: JSONObject,
    ) : ToolResult

    /** A proxied guest-MCP tool's own content array, forwarded verbatim. */
    data class Passthrough(
        val content: JSONArray,
        override val structured: JSONObject,
    ) : ToolResult
}

/** JSON Schema for tools that take no arguments. */
internal fun emptyObjectSchema(): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject())
}

/**
 * Dedicated notification channel for agent-posted notifications
 * (raise_notification). Kept separate from Haven's foreground / renewal
 * channels so the user can mute "agent test notifications" without
 * silencing real Haven notifications.
 */
private const val AGENT_NOTIFICATION_CHANNEL_ID = "agent.test.notifications"

/**
 * Fixed id so re-running the grant flow replaces the steps rather than stacking
 * a second identical notification (#575).
 */
/** Fixed id, so re-running the pairing flow replaces its steps. */
private const val PAIRING_STEPS_NOTIFICATION_ID = 0x0576
private const val INSTALL_NOTIFICATION_CHANNEL_ID = "agent.install"

/** Upper bound on lines requested from logcat in a single read_logcat call. */
/**
 * The destination hash a RETICULUM profile addresses, normalised to lower case.
 *
 * Checked at create time rather than at connect time because a malformed hash
 * is indistinguishable from an unreachable one once it is in flight: the
 * connect spends 20s waiting for a path that can never resolve and then reports
 * a timeout, which reads as a network fault rather than a typo.
 */
internal fun normaliseReticulumDestinationHash(raw: String): String {
    val hash = raw.trim().lowercase()
    require(hash.isNotBlank()) { "destinationHash required for RETICULUM" }
    require(hash.length == RETICULUM_DEST_HASH_CHARS && hash.all { it in "0123456789abcdef" }) {
        "destinationHash must be $RETICULUM_DEST_HASH_CHARS hex characters, got \"$raw\""
    }
    return hash
}

/** Reticulum truncates destination hashes to 16 bytes, so 32 hex characters. */
private const val RETICULUM_DEST_HASH_CHARS = 32

private const val MAX_LOGCAT_LINES = 5000

/** Upper bound on the response payload from a single read_logcat call (256 KiB). */
private const val MAX_LOGCAT_BYTES = 256 * 1024

/**
 * Cheap shell-metacharacter screen for read_logcat args that get
 * interpolated into a `sh -c` command line. Rejects the characters that
 * can start a new command, a process substitution, or a variable
 * expansion. Logcat filter expressions and timestamps don't legitimately
 * contain any of these.
 */
private fun String.containsShellMetacharacter(): Boolean =
    any { it == ';' || it == '|' || it == '&' || it == '$' || it == '`' || it == '\n' || it == '\r' }

/** Promoted from McpTools for reuse by tool providers (#mcp-backbone Stage 5). */
internal fun desktopToJson(
    de: sh.haven.core.local.ProotManager.DesktopEnvironment,
    activeFamily: sh.haven.core.local.proot.PackageFamily,
    installed: Boolean,
    running: Boolean,
): JSONObject = JSONObject().apply {
    put("id", de.spec.id)
    put("label", de.label)
    put("sizeEstimateMb", de.spec.sizeEstimateMb)
    put("installed", installed)
    put("running", running)
    put("isNative", de.isNative)
    put("isWayland", de.isWayland)
    put("verifyBinary", de.spec.verifyBinary)
    put("compatibility", de.spec.compatibilityOn(activeFamily).name)
    de.spec.compatibilityNoteOn(activeFamily)?.let { put("compatibilityNote", it) }
    val pkgs = de.spec.packagesPerFamily[activeFamily]
    if (pkgs != null) {
        put("packages", JSONArray().apply { pkgs.forEach { put(it) } })
    } else {
        put("packages", JSONObject.NULL)
    }
    put("packageFamilies", JSONArray().apply {
        de.spec.packagesPerFamily.keys.forEach { put(it.name) }
    })
}

/** Promoted from McpTools for reuse by tool providers (#mcp-backbone Stage 5). */
internal fun requireIntArg(args: JSONObject, name: String): Int {
    if (!args.has(name)) throw McpError(-32602, "$name is required")
    return args.optInt(name)
}

/** Promoted from McpTools for reuse by tool providers (#mcp-backbone Stage 5). */
internal fun encodeCapture(
    pngBytes: ByteArray,
    crop: IntArray?,
    maxWidth: Int,
    format: String,
): Triple<String, Int, Int> {
    var bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        ?: throw McpError(-32603, "Could not decode captured image")
    if (crop != null) {
        val x = crop[0].coerceIn(0, bmp.width - 1)
        val y = crop[1].coerceIn(0, bmp.height - 1)
        val cw = crop[2].coerceIn(1, bmp.width - x)
        val ch = crop[3].coerceIn(1, bmp.height - y)
        bmp = Bitmap.createBitmap(bmp, x, y, cw, ch)
    }
    if (maxWidth in 1 until bmp.width) {
        val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
        bmp = Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
    }
    val out = java.io.ByteArrayOutputStream()
    if (format == "jpeg") {
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
    } else {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return Triple(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), bmp.width, bmp.height)
}

/** Promoted from McpTools for reuse by tool providers (#mcp-backbone Stage 5). */
internal fun desktopByIdOrThrow(deId: String): sh.haven.core.local.ProotManager.DesktopEnvironment =
    sh.haven.core.local.ProotManager.DesktopEnvironment.entries
        .firstOrNull { it.spec.id == deId }
        ?: throw McpError(-32602, "Unknown deId: $deId")

/** Promoted from McpTools for reuse by tool providers (#mcp-backbone Stage 5). */
internal fun guessContentType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "html", "htm" -> "text/html"
        "txt", "text", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
