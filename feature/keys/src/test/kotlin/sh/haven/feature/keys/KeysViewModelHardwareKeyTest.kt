package sh.haven.feature.keys

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.scan.BarcodeDecoder
import sh.haven.core.security.HardwareKeySource
import sh.haven.core.security.Keystore
import sh.haven.core.security.HardwareSshKeyManager
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.stepca.StepCaSignFlow
import sh.haven.core.data.repository.AgeIdentityRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.data.repository.StepCaConfigRepository
import sh.haven.core.data.repository.TotpSecretRepository

/**
 * Hardware-resident key generation on the Keys screen (design.md §6), JVM-only
 * via a recording fake in place of the AndroidKeyStore-bound manager: row shape
 * (the [SshKey.hardwareAlias] storage convention), alias format, and the
 * orphan-alias cleanup when persisting fails after the chip already minted the
 * key. Deletion's alias cleanup lives one layer down — its SSOT is
 * SshKeyRepository.delete, covered by SshKeyHardwareKeyTest in core/data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeysViewModelHardwareKeyTest {

    private val repository: SshKeyRepository = mockk(relaxed = true)
    private val fakeHardwareKeys = RecordingHardwareKeySource()
    private lateinit var viewModel: KeysViewModel

    @Before
    fun setUp() {
        // viewModelScope launches on Main; unconfined runs them eagerly so the
        // assertions below need no manual advancement.
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
            appContext = mockk<Context>(relaxed = true),
            totpSecretRepository = mockk<TotpSecretRepository>(relaxed = true),
            ageIdentityRepository = mockk<AgeIdentityRepository>(relaxed = true),
            sshIdentityRepository = mockk<SshIdentityRepository>(relaxed = true),
            barcodeDecoder = mockk<BarcodeDecoder>(relaxed = true),
            preferencesRepository = preferences,
            hardwareKeys = fakeHardwareKeys,
            agentUiCommandBus = AgentUiCommandBus(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Wait for the ViewModel's generate coroutine to run to completion.
     * The launch starts eagerly (unconfined Main) but suspends at its
     * `withContext(Dispatchers.Default)` and resumes on a real worker
     * thread — `generating` flipping back to false (the `finally`) is the
     * deterministic completion marker, so assertions never race the chip.
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

    @Test
    fun `generateHardwareKey saves the hardware-resident row convention`() {
        viewModel.generateHardwareKey("my chip key")
        awaitGenerationDone()

        val saved = slot<SshKey>()
        coVerify(exactly = 1) { repository.save(capture(saved)) }
        with(saved.captured) {
            assertEquals("my chip key", label)
            // The only curve the hardware backend supports (KeyDecision #1).
            assertEquals(SshKeyGenerator.EcdsaCurve.NIST_P256.sshKeyType, keyType)
            // Storage convention: no local private material, per-op biometric.
            assertEquals(0, privateKeyBytes.size)
            assertTrue(biometricProtected)
            assertNotNull(hardwareAlias)
            // Alias format contract: haven-ssh-<uuid8>.
            val alias = hardwareAlias!!
            assertTrue("alias=$alias", alias.startsWith("haven-ssh-") && alias.length == "haven-ssh-".length + 8)
            assertEquals(alias, fakeHardwareKeys.generatedAliases.single())
            // Public half mirrors what the (fake) chip reported.
            assertEquals("ecdsa-sha2-nistp256 AAAA $alias", publicKeyOpenSsh)
            assertEquals("SHA256:fake-$alias", fingerprintSha256)
        }
        assertNull(viewModel.error.value)
    }

    @Test
    fun `generateHardwareKey reports an error and saves nothing when the chip fails`() {
        fakeHardwareKeys.generateError = IllegalStateException("StrongBox busy")

        viewModel.generateHardwareKey("doomed")
        awaitGenerationDone()

        coVerify(exactly = 0) { repository.save(any()) }
        assertEquals("StrongBox busy", viewModel.error.value)
        // The alias was attempted exactly once and never persisted.
        assertEquals(listOf<String>(), fakeHardwareKeys.generatedAliases)
    }

    @Test
    fun `generateHardwareKey deletes the minted alias when saving the row fails`() {
        coEvery { repository.save(any()) } throws IllegalStateException("disk full")

        viewModel.generateHardwareKey("half-born")
        awaitGenerationDone()

        // One alias minted, then cleaned up — no orphan key in the scarce
        // secure-chip storage, and the error surfaces.
        assertEquals(1, fakeHardwareKeys.generatedAliases.size)
        assertEquals(fakeHardwareKeys.generatedAliases, fakeHardwareKeys.deletedAliases)
        assertEquals("disk full", viewModel.error.value)
    }

    @Test
    fun `deleteKey still routes through the repository SSOT`() {
        viewModel.deleteKey("hw-row")

        coVerify(exactly = 1) { repository.delete("hw-row") }
    }
}

/**
 * Recording [HardwareKeySource] double — the real [HardwareSshKeyManager]
 * needs AndroidKeyStore. Deterministic metadata derived from the alias, plus
 * generation/deletion ledgers so the tests can assert the orphan-alias
 * cleanup. (A per-module copy of core/data's test fake: test source sets
 * don't propagate across modules in this repo.)
 */
private class RecordingHardwareKeySource : HardwareKeySource {

    val generatedAliases = mutableListOf<String>()
    val deletedAliases = mutableListOf<String>()
    var generateError: Exception? = null

    override fun generate(alias: String, preferStrongBox: Boolean): HardwareSshKeyManager.HardwareKeyInfo {
        generateError?.let { throw it }
        generatedAliases += alias
        return HardwareSshKeyManager.HardwareKeyInfo(
            alias = alias,
            publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA $alias",
            fingerprintSha256 = "SHA256:fake-$alias",
            strongBoxBacked = preferStrongBox,
        )
    }

    override fun initSignature(alias: String) =
        throw UnsupportedOperationException("signature init is not part of ViewModel tests")

    override fun delete(alias: String) {
        deletedAliases += alias
    }

    override fun contains(alias: String): Boolean = alias in generatedAliases
}
