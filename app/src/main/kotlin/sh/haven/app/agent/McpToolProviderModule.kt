package sh.haven.app.agent

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import sh.haven.app.usb.UmlRecoveryManager
import sh.haven.app.usb.UsbDriveVmManager
import sh.haven.app.workspace.WorkspaceLauncher
import sh.haven.core.bleserial.BleSerialSessionManager
import sh.haven.core.btserial.BtSerialSessionManager
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.agent.PendingAuthPromptHolder
import sh.haven.core.data.agent.SessionSelectionHolder
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.desktop.DesktopSessionRegistry
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.AgeIdentityRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.data.repository.StepCaConfigRepository
import sh.haven.core.data.repository.SyncProfileRepository
import sh.haven.core.data.repository.TotpSecretRepository
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.knock.PortKnocker
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.openai.AiRouteRegistry
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.reticulum.ReticulumForwardServer
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.security.HardwareKeySource
import sh.haven.core.session.SessionManagerRegistry
import sh.haven.core.spa.SpaSender
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.tunnel.TunnelManager
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbIpServer
import sh.haven.core.usb.UsbProxyServer
import sh.haven.core.usb.UsbRedirServer
import sh.haven.core.usbserial.UsbSerialSessionManager
import sh.haven.feature.sftp.transport.TransportSelector
import sh.haven.feature.terminal.agent.TerminalSessionRegistry
import javax.inject.Singleton

/**
 * Contributes cohesive domain [ToolProvider] implementations into Hilt's
 * `Set<ToolProvider>` multibinding so [McpTools] and [McpServer] do not have to
 * accept or manually thread every domain dependency through one constructor.
 *
 * Providers that close over [McpTools]-owned runtime callbacks ([ToolContext],
 * Shizuku command bridge, live client-hint / tool-name set) are instantiated
 * inside [McpTools] and merged alongside this injected set, with cross-provider
 * duplicate tool-name detection enforced across the combined registry.
 */
@Module
@InstallIn(SingletonComponent::class)
object McpToolProviderModule {

    @Provides
    @IntoSet
    @Singleton
    fun provideKeyStoreToolProvider(
        totpSecretRepository: TotpSecretRepository,
        ageIdentityRepository: AgeIdentityRepository,
        agentUiCommandBus: AgentUiCommandBus,
    ): ToolProvider = KeyStoreToolProvider(
        totpSecretRepository = totpSecretRepository,
        ageIdentityRepository = ageIdentityRepository,
        agentUiCommandBus = agentUiCommandBus,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideTunnelToolProvider(
        tunnelConfigRepository: TunnelConfigRepository,
        tunnelManager: TunnelManager,
    ): ToolProvider = TunnelToolProvider(
        tunnelConfigRepository = tunnelConfigRepository,
        tunnelManager = tunnelManager,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideSshKeyToolProvider(
        sshKeyRepository: SshKeyRepository,
    ): ToolProvider = SshKeyToolProvider(
        sshKeyRepository = sshKeyRepository,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideHostKeyToolProvider(
        knownHostDao: KnownHostDao,
    ): ToolProvider = HostKeyToolProvider(
        knownHostDao = knownHostDao,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideStepCaToolProvider(
        stepCaConfigRepository: StepCaConfigRepository,
    ): ToolProvider = StepCaToolProvider(
        stepCaConfigRepository = stepCaConfigRepository,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideRcloneToolProvider(
        rcloneClient: RcloneClient,
        syncProfileRepository: SyncProfileRepository,
        connectionRepository: ConnectionRepository,
        sessionManagerRegistry: SessionManagerRegistry,
    ): ToolProvider = RcloneToolProvider(
        rcloneClient = rcloneClient,
        syncProfileRepository = syncProfileRepository,
        connectionRepository = connectionRepository,
        sessionManagerRegistry = sessionManagerRegistry,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideUsbToolProvider(
        usbBroker: UsbBroker,
        usbIpServer: UsbIpServer,
        usbDriveVmManager: UsbDriveVmManager,
        umlRecoveryManager: UmlRecoveryManager,
        usbProxyServer: UsbProxyServer,
        preferencesRepository: UserPreferencesRepository,
        localSessionManager: LocalSessionManager,
        usbRedirServer: UsbRedirServer,
    ): ToolProvider = UsbToolProvider(
        usbBroker = usbBroker,
        usbIpServer = usbIpServer,
        usbDriveVmManager = usbDriveVmManager,
        umlRecoveryManager = umlRecoveryManager,
        usbProxyServer = usbProxyServer,
        preferencesRepository = preferencesRepository,
        localSessionManager = localSessionManager,
        usbRedirServer = usbRedirServer,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideSerialBridgeToolProvider(
        btSerialSessionManager: BtSerialSessionManager,
        bleSerialSessionManager: BleSerialSessionManager,
        usbSerialSessionManager: UsbSerialSessionManager,
    ): ToolProvider = SerialBridgeToolProvider(
        btSerial = btSerialSessionManager,
        bleSerial = bleSerialSessionManager,
        usbSerial = usbSerialSessionManager,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideCrossProtocolToolProvider(
        sessionManagerRegistry: SessionManagerRegistry,
        workspaceRepository: WorkspaceRepository,
        transportSelector: TransportSelector,
    ): ToolProvider = CrossProtocolToolProvider(
        sessionManagerRegistry = sessionManagerRegistry,
        workspaceRepository = workspaceRepository,
        transportSelector = transportSelector,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideCredentialToolProvider(
        sshKeyRepository: SshKeyRepository,
        headlessSshExec: HeadlessSshExec,
        hardwareKeySource: HardwareKeySource,
        biometricAuthenticator: BiometricAuthenticator,
    ): ToolProvider = CredentialToolProvider(
        sshKeyRepository = sshKeyRepository,
        headlessSshExec = headlessSshExec,
        hardwareKeys = hardwareKeySource,
        biometricAuthenticator = biometricAuthenticator,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideWorkspaceAndSnippetToolProvider(
        workspaceRepository: WorkspaceRepository,
        workspaceLauncher: WorkspaceLauncher,
        preferencesRepository: UserPreferencesRepository,
    ): ToolProvider = WorkspaceAndSnippetToolProvider(
        workspaceRepository = workspaceRepository,
        workspaceLauncher = workspaceLauncher,
        preferencesRepository = preferencesRepository,
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideConnectionsToolProvider(
        @ApplicationContext context: Context,
        connectionRepository: ConnectionRepository,
        portForwardRepository: PortForwardRepository,
        sshSessionManager: SshSessionManager,
        sessionManagerRegistry: SessionManagerRegistry,
        reticulumSessionManager: ReticulumSessionManager,
        reticulumForwardServer: ReticulumForwardServer,
        agentUiCommandBus: AgentUiCommandBus,
        tunnelConfigRepository: TunnelConfigRepository,
        tunnelManager: TunnelManager,
        terminalSessionRegistry: TerminalSessionRegistry,
        portKnocker: PortKnocker,
        spaSender: SpaSender,
        connectionLogRepository: ConnectionLogRepository,
        sshKeyRepository: SshKeyRepository,
        desktopSessionRegistry: DesktopSessionRegistry,
        aiRouteRegistry: AiRouteRegistry,
        headlessSshExec: HeadlessSshExec,
        pendingAuthPromptHolder: PendingAuthPromptHolder,
        sessionSelectionHolder: SessionSelectionHolder,
    ): ToolProvider = ConnectionsToolProvider(
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
    )
}
