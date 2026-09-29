package sh.haven.feature.tunnel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.tunnel.NetbirdConfigBlob
import sh.haven.core.tunnel.TailscaleConfigBlob

/**
 * Unit tests for the edit path added by GH #666: validation, identity
 * preservation (id / type / createdAt / ownerProfileId all come from the
 * stored row, not the caller), and the "row vanished" case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TunnelConfigRepository
    private lateinit var viewModel: TunnelViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        val connectionLogRepository = mockk<ConnectionLogRepository>(relaxed = true)
        viewModel = TunnelViewModel(
            repository = repository,
            connectionLogRepository = connectionLogRepository,
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun existingRow(
        type: TunnelConfigType = TunnelConfigType.WIREGUARD,
        ownerProfileId: String? = null,
    ) = TunnelConfig(
        id = "t1",
        label = "Old Label",
        type = type.name,
        configText = "old-bytes".toByteArray(),
        createdAt = 12345L,
        ownerProfileId = ownerProfileId,
    )

    @Test
    fun updateWireguard_blankLabel_setsErrorAndSkipsSave() = runTest {
        viewModel.updateWireguardConfig("t1", "  ", "[Interface]")
        advanceUntilIdle()
        assertEquals("Label is required", viewModel.error.value)
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun updateWireguard_blankConfigText_setsErrorAndSkipsSave() = runTest {
        viewModel.updateWireguardConfig("t1", "New Label", "")
        advanceUntilIdle()
        assertEquals("Config text is required", viewModel.error.value)
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun updateWireguard_happyPath_preservesIdentity() = runTest {
        val existing = existingRow()
        coEvery { repository.getById("t1") } returns existing
        val saved = slot<TunnelConfig>()
        coEvery { repository.save(capture(saved)) } just runs

        viewModel.updateWireguardConfig("t1", "  New Label  ", "[Interface]\nAddress = 10.0.0.2/32")
        advanceUntilIdle()

        val row = saved.captured
        assertEquals("t1", row.id)
        assertEquals("WIREGUARD", row.type)
        assertEquals(12345L, row.createdAt)
        assertNull(row.ownerProfileId)
        assertEquals("New Label", row.label)
        assertEquals("[Interface]\nAddress = 10.0.0.2/32", String(row.configText, Charsets.UTF_8))
        assertEquals("Tunnel \"New Label\" saved", viewModel.message.value)
    }

    @Test
    fun updateWireguard_ownerPreserved() = runTest {
        val existing = existingRow(ownerProfileId = "profile-9")
        coEvery { repository.getById("t1") } returns existing
        val saved = slot<TunnelConfig>()
        coEvery { repository.save(capture(saved)) } just runs

        viewModel.updateWireguardConfig("t1", "New Label", "[Interface]")
        advanceUntilIdle()

        assertEquals("profile-9", saved.captured.ownerProfileId)
    }

    @Test
    fun update_whenRowGone_setsErrorAndSkipsSave() = runTest {
        coEvery { repository.getById("t1") } returns null

        viewModel.updateWireguardConfig("t1", "New Label", "[Interface]")
        advanceUntilIdle()

        assertEquals("Tunnel no longer exists", viewModel.error.value)
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun updateTailscale_badControlUrl_setsErrorAndSkipsSave() = runTest {
        coEvery { repository.getById("t1") } returns existingRow(TunnelConfigType.TAILSCALE)

        viewModel.updateTailscaleConfig("t1", "New Label", "tskey-auth-x", "headscale.example.com")
        advanceUntilIdle()

        assertEquals(
            "Control plane URL must start with https:// (or http:// for local testing)",
            viewModel.error.value,
        )
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun updateTailscale_happyPath_roundTripsBlob() = runTest {
        val existing = existingRow(TunnelConfigType.TAILSCALE)
        coEvery { repository.getById("t1") } returns existing
        val saved = slot<TunnelConfig>()
        coEvery { repository.save(capture(saved)) } just runs

        viewModel.updateTailscaleConfig("t1", "New Label", " tskey-auth-x ", " https://headscale.example.com ")
        advanceUntilIdle()

        val parsed = TailscaleConfigBlob.parse(saved.captured.configText)
        assertEquals("tskey-auth-x", parsed.authKey)
        assertEquals("https://headscale.example.com", parsed.controlURL)
        assertEquals("TAILSCALE", saved.captured.type)
    }

    @Test
    fun updateNetbird_badManagementUrl_setsErrorAndSkipsSave() = runTest {
        coEvery { repository.getById("t1") } returns existingRow(TunnelConfigType.NETBIRD)

        viewModel.updateNetbirdConfig("t1", "New Label", "NBKEY-x", "netbird.example.com")
        advanceUntilIdle()

        assertEquals(
            "Management URL must start with https:// (or http:// for local testing)",
            viewModel.error.value,
        )
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun updateNetbird_happyPath_roundTripsBlob() = runTest {
        val existing = existingRow(TunnelConfigType.NETBIRD)
        coEvery { repository.getById("t1") } returns existing
        val saved = slot<TunnelConfig>()
        coEvery { repository.save(capture(saved)) } just runs

        viewModel.updateNetbirdConfig("t1", "New Label", " NBKEY-x ", " https://netbird.example.com ")
        advanceUntilIdle()

        val parsed = NetbirdConfigBlob.parse(saved.captured.configText)
        assertEquals("NBKEY-x", parsed!!.setupKey)
        assertEquals("https://netbird.example.com", parsed.managementURL)
        assertEquals("NETBIRD", saved.captured.type)
    }
}