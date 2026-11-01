package sh.haven.feature.terminal

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.keystore.BiometricGate
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.security.HardwareKeyInfo
import sh.haven.core.security.HardwareKeySource
import java.security.KeyPairGenerator
import java.security.Signature

class SudoPasswordUnlockerTest {

    private class FakeHardwareKeySource(
        private val keyPair: java.security.KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.genKeyPair(),
        var invalidatedOnFirstInit: Boolean = false,
    ) : HardwareKeySource {
        val aliases = mutableSetOf<String>()
        var generateCalls = 0
        var deleteCalls = 0

        override fun generate(alias: String, preferStrongBox: Boolean): HardwareKeyInfo {
            generateCalls++
            aliases.add(alias)
            return HardwareKeyInfo(
                alias = alias,
                publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA $alias",
                fingerprintSha256 = "SHA256:test",
                strongBoxBacked = preferStrongBox,
            )
        }

        override fun initSignature(alias: String): Signature {
            require(alias in aliases) { "Missing alias $alias" }
            if (invalidatedOnFirstInit) {
                invalidatedOnFirstInit = false
                throw IllegalStateException("Key invalidated")
            }
            return Signature.getInstance("SHA256withECDSA").apply {
                initSign(keyPair.private)
            }
        }

        override fun delete(alias: String) {
            deleteCalls++
            aliases.remove(alias)
        }

        override fun contains(alias: String): Boolean = alias in aliases
    }

    private fun testProfile(
        id: String = "p1",
        label: String = "prod-box",
        sshPassword: String? = "hunter2",
    ) = ConnectionProfile(
        id = id,
        label = label,
        host = "10.0.0.1",
        username = "ian",
        sshPassword = sshPassword,
    )

    @Test
    fun `returns NoPasswordStored when profile and identity have no password`() = runTest {
        val profile = testProfile(sshPassword = null)
        val connRepo = mockk<ConnectionRepository> {
            coEvery { getById("p1") } returns profile
        }
        val idRepo = mockk<SshIdentityRepository> {
            coEvery { applyTo(profile) } returns profile
        }
        val gate = BiometricGate().apply { setForegroundActive(true) }
        val hw = FakeHardwareKeySource()
        val unlocker = SudoPasswordUnlocker(
            connectionRepository = connRepo,
            sshIdentityRepository = idRepo,
            biometricGate = gate,
            hardwareKeySource = hw,
            strongBiometricsAvailable = { true },
        )

        val result = unlocker.unlock("p1")
        assertEquals(SudoUnlockResult.NoPasswordStored("prod-box"), result)
        assertTrue(gate.pending.value.isEmpty())
    }

    @Test
    fun `unlocks stored password via hardware key CryptoObject when strong biometrics available`() =
        runTest(UnconfinedTestDispatcher()) {
            val profile = testProfile(sshPassword = "sudo-secret")
            val connRepo = mockk<ConnectionRepository> {
                coEvery { getById("p1") } returns profile
            }
            val idRepo = mockk<SshIdentityRepository> {
                coEvery { applyTo(profile) } returns profile
            }
            val gate = BiometricGate().apply { setForegroundActive(true) }
            val hw = FakeHardwareKeySource()
            val unlocker = SudoPasswordUnlocker(
                connectionRepository = connRepo,
                sshIdentityRepository = idRepo,
                biometricGate = gate,
                hardwareKeySource = hw,
                strongBiometricsAvailable = { true },
            )

            val deferred = async { unlocker.unlock("p1") }
            val pending = gate.pending.value.single()
            assertEquals("Fill sudo password", pending.label)
            assertEquals("prod-box", pending.detail)
            assertTrue("Hardware signature must be attached to the BiometricGate request", pending.signature != null)
            assertEquals(1, hw.generateCalls)

            gate.respond(pending.id, BiometricGate.Decision.ALLOW)
            val result = deferred.await()
            assertEquals(
                SudoUnlockResult.Unlocked(password = "sudo-secret", hardwareBacked = true),
                result,
            )
        }

    @Test
    fun `regenerates hardware gate key if invalidated by biometric enrollment`() =
        runTest(UnconfinedTestDispatcher()) {
            val profile = testProfile(sshPassword = "sudo-secret")
            val connRepo = mockk<ConnectionRepository> {
                coEvery { getById("p1") } returns profile
            }
            val idRepo = mockk<SshIdentityRepository> {
                coEvery { applyTo(profile) } returns profile
            }
            val gate = BiometricGate().apply { setForegroundActive(true) }
            val hw = FakeHardwareKeySource(invalidatedOnFirstInit = true).apply {
                aliases.add(SudoPasswordUnlocker.SUDO_HARDWARE_KEY_ALIAS)
            }
            val unlocker = SudoPasswordUnlocker(
                connectionRepository = connRepo,
                sshIdentityRepository = idRepo,
                biometricGate = gate,
                hardwareKeySource = hw,
                strongBiometricsAvailable = { true },
            )

            val deferred = async { unlocker.unlock("p1") }
            val pending = gate.pending.value.single()
            assertEquals(1, hw.deleteCalls)
            assertEquals(1, hw.generateCalls)
            gate.respond(pending.id, BiometricGate.Decision.ALLOW)

            assertEquals(
                SudoUnlockResult.Unlocked(password = "sudo-secret", hardwareBacked = true),
                deferred.await(),
            )
        }

    @Test
    fun `falls back to fresh per-use biometric gate when strong biometrics unavailable`() =
        runTest(UnconfinedTestDispatcher()) {
            val profile = testProfile(sshPassword = "from-identity")
            val connRepo = mockk<ConnectionRepository> {
                coEvery { getById("p1") } returns profile.copy(sshPassword = null)
            }
            val idRepo = mockk<SshIdentityRepository> {
                coEvery { applyTo(any()) } returns profile
            }
            val gate = BiometricGate().apply { setForegroundActive(true) }
            // Open the 30s session window first to verify sudo unlock STILL prompts.
            val warmup = async { gate.request(label = "Warmup") }
            gate.respond(gate.pending.value.single().id, BiometricGate.Decision.ALLOW)
            warmup.await()

            val hw = FakeHardwareKeySource()
            val unlocker = SudoPasswordUnlocker(
                connectionRepository = connRepo,
                sshIdentityRepository = idRepo,
                biometricGate = gate,
                hardwareKeySource = hw,
                strongBiometricsAvailable = { false },
            )

            val deferred = async { unlocker.unlock("p1") }
            val pending = gate.pending.value.single()
            assertSame(null, pending.signature)
            gate.respond(pending.id, BiometricGate.Decision.ALLOW)

            assertEquals(
                SudoUnlockResult.Unlocked(password = "from-identity", hardwareBacked = false),
                deferred.await(),
            )
        }

    @Test
    fun `returns Declined when user cancels biometric prompt`() =
        runTest(UnconfinedTestDispatcher()) {
            val profile = testProfile(sshPassword = "sudo-secret")
            val connRepo = mockk<ConnectionRepository> {
                coEvery { getById("p1") } returns profile
            }
            val idRepo = mockk<SshIdentityRepository> {
                coEvery { applyTo(profile) } returns profile
            }
            val gate = BiometricGate().apply { setForegroundActive(true) }
            val hw = FakeHardwareKeySource()
            val unlocker = SudoPasswordUnlocker(
                connectionRepository = connRepo,
                sshIdentityRepository = idRepo,
                biometricGate = gate,
                hardwareKeySource = hw,
                strongBiometricsAvailable = { true },
            )

            val deferred = async { unlocker.unlock("p1") }
            gate.respond(gate.pending.value.single().id, BiometricGate.Decision.DENY)

            val result = deferred.await()
            assertTrue(result is SudoUnlockResult.Declined)
        }
}
