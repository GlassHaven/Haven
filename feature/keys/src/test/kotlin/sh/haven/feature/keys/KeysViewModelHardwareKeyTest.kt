package sh.haven.feature.keys

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.scan.BarcodeDecoder
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.security.Keystore
import sh.haven.core.stepca.StepCaSignFlow
import sh.haven.core.data.repository.AgeIdentityRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.data.repository.StepCaConfigRepository
import sh.haven.core.data.repository.TotpSecretRepository

/**
 * Hardware-resident key generation on the Keys screen. Since the minting
 * flow moved into [SshKeyRepository.generateHardwareKey] (SSOT — row
 * convention, alias minting, orphan cleanup; covered by
 * SshKeyHardwareKeyTest in core/data), this file pins the ViewModel's
 * remaining responsibilities: the strong-biometry entry gate (with the
 * localized, actionable error), delegation to the repository, error
 * surfacing, and delete still routing through the repository's SSOT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeysViewModelHardwareKeyTest {

    private val repository: SshKeyRepository = mockk(relaxed = true)
    private val biometrics = mockk<BiometricAuthenticator>()
    private lateinit var appContext: Context
    private lateinit var viewModel: KeysViewModel

    @Before
    fun setUp() {
        // viewModelScope launches on Main; unconfined runs them eagerly so the
        // assertions below need no manual advancement.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        appContext = mockk(relaxed = true)
        val preferences: UserPreferencesRepository = mockk(relaxed = true) {
            every { sshKeyOrder } returns flowOf(emptyList())
            every { keysSortMode } returns flowOf(null)
            every { keysCollapsedSections } returns flowOf(null)
        }
        viewModel = KeysViewModel(
            repository = repository,
            connectionRepository = mockk(relaxed = true),
            keystore = mockk(relaxed = true),
            stepCaConfigRepository = mockk(relaxed = true),
            stepCaSignFlow = mockk<StepCaSignFlow>(relaxed = true),
            fidoAuthenticator = mockk<FidoAuthenticator>(relaxed = true),
            appContext = appContext,
            totpSecretRepository = mockk<TotpSecretRepository>(relaxed = true),
            ageIdentityRepository = mockk<AgeIdentityRepository>(relaxed = true),
            sshIdentityRepository = mockk<SshIdentityRepository>(relaxed = true),
            barcodeDecoder = mockk<BarcodeDecoder>(relaxed = true),
            preferencesRepository = preferences,
            biometricAuthenticator = biometrics,
            agentUiCommandBus = AgentUiCommandBus(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Wait for the ViewModel's generate coroutine to run to completion.
     * The launch starts eagerly (unconfined Main) but resumes on a real
     * worker thread inside the repository call — `generating` flipping
     * back to false (the `finally`) is the deterministic completion marker,
     * so assertions never race the chip.
     */
    private fun awaitGenerationDone() {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.generating.value) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("generate coroutine did not finish within 5s")
            }
            Thread.sleep(10)
        }
    }

    private fun chipRow() = SshKey(
        label = "my chip key", keyType = "ecdsa-sha2-nistp256",
        privateKeyBytes = ByteArray(0),
        publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA haven-ssh-1",
        fingerprintSha256 = "SHA256:fake",
        biometricProtected = true,
        hardwareAlias = "haven-ssh-1",
    )

    @Test
    fun `generateHardwareKey delegates to the repository SSOT`() {
        every { biometrics.canAuthorizeWithStrongBiometrics() } returns true
        coEvery { repository.generateHardwareKey("my chip key") } returns chipRow()

        viewModel.generateHardwareKey("my chip key")
        awaitGenerationDone()

        coVerify(exactly = 1) { repository.generateHardwareKey("my chip key") }
        assertNull(viewModel.error.value)
    }

    @Test
    fun `generateHardwareKey reports an error when the repository fails`() {
        every { biometrics.canAuthorizeWithStrongBiometrics() } returns true
        coEvery { repository.generateHardwareKey(any()) } throws IllegalStateException("StrongBox busy")

        viewModel.generateHardwareKey("doomed")
        awaitGenerationDone()

        assertEquals("StrongBox busy", viewModel.error.value)
    }

    @Test
    fun `generateHardwareKey refuses without a strong biometric and never mints`() {
        every { biometrics.canAuthorizeWithStrongBiometrics() } returns false
        every { appContext.getString(any()) } returns "no strong biometric enrolled"

        viewModel.generateHardwareKey("never born")

        // Refused at the door: the localized, distinguishable error is set
        // synchronously and the repository is never reached.
        assertEquals("no strong biometric enrolled", viewModel.error.value)
        coVerify(exactly = 0) { repository.generateHardwareKey(any()) }
    }

    @Test
    fun `deleteKey still routes through the repository SSOT`() {
        viewModel.deleteKey("hw-row")

        coVerify(exactly = 1) { repository.delete("hw-row") }
    }
}
