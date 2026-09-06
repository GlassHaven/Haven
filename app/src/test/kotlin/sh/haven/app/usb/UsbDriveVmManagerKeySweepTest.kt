package sh.haven.app.usb

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.SshKeyRepository

/**
 * Ephemeral USB drive VM keys must never sit in the try-every-key SSH offer
 * pool: each stale one is a wasted publickey attempt, and a handful of them
 * exhausts sshd's MaxAuthTries before the user's real key is offered.
 */
class UsbDriveVmManagerKeySweepTest {

    private val sshKeyRepository = mockk<SshKeyRepository>(relaxed = true)

    private fun manager() = UsbDriveVmManager(
        qemuManager = mockk(relaxed = true),
        usbIpServer = mockk(relaxed = true),
        usbBroker = mockk(relaxed = true) {
            // init collects this; a relaxed SharedFlow mock's collect is
            // declared Nothing → KotlinNothingValueException.
            io.mockk.every { detached } returns kotlinx.coroutines.flow.MutableSharedFlow()
        },
        connectionRepository = mockk(relaxed = true),
        sshKeyRepository = sshKeyRepository,
        agentUiCommandBus = mockk(relaxed = true),
        sshSessionManager = mockk(relaxed = true),
        usbMountCorrelator = mockk(relaxed = true),
    )

    private fun key(id: String, label: String, enabledForAuth: Boolean) = SshKey(
        id = id, label = label, keyType = "ssh-ed25519",
        privateKeyBytes = ByteArray(0), publicKeyOpenSsh = "", fingerprintSha256 = "fp-$id",
        enabledForAuth = enabledForAuth,
    )

    @Test
    fun `sweep disables offer only on still-enabled drive keys`() = runTest {
        coEvery { sshKeyRepository.getAll() } returns listOf(
            key("stale1", SshKey.USB_DRIVE_VM_LABEL, enabledForAuth = true),
            key("stale2", SshKey.USB_DRIVE_VM_LABEL, enabledForAuth = true),
            key("done", SshKey.USB_DRIVE_VM_LABEL, enabledForAuth = false),
            key("user", "my laptop key", enabledForAuth = true),
        )

        manager().disableStaleDriveKeyOffers()

        // The init block may also fire the sweep on its own IO scope, so the
        // positive checks are atLeast rather than exactly.
        coVerify(atLeast = 1) { sshKeyRepository.setEnabledForAuth("stale1", false) }
        coVerify(atLeast = 1) { sshKeyRepository.setEnabledForAuth("stale2", false) }
        coVerify(exactly = 0) { sshKeyRepository.setEnabledForAuth("done", any()) }
        coVerify(exactly = 0) { sshKeyRepository.setEnabledForAuth("user", any()) }
    }
}
