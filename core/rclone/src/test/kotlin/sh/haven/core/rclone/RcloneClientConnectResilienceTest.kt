package sh.haven.core.rclone

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.rclone.bridge.RcloneBridge

/**
 * #839: initialize() must raise rclone's connect timeout and retry counts above
 * the library defaults via the RC options/set "main" block, so the first storage
 * connect (OAuth token refresh + root listing in one call) recovers from a
 * transient blip internally. The Go side is proven in rcbridge_options_test.go;
 * this pins the Kotlin payload and the best-effort failure path.
 */
class RcloneClientConnectResilienceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = unmockkAll()

    private fun client(): RcloneClient {
        val context = mockk<Context>()
        every { context.filesDir } returns tmp.root
        return RcloneClient(context)
    }

    @Test
    fun initializeRaisesConnectTimeoutAndRetries() {
        mockkObject(RcloneBridge)
        every { RcloneBridge.initialize(any()) } returns Unit
        val payload = slot<String>()
        every { RcloneBridge.rpc("options/set", capture(payload)) } returns
            RcloneBridge.RpcResult(200, "{}")

        client().initialize()

        verify(exactly = 1) { RcloneBridge.rpc("options/set", any()) }
        val main = JSONObject(payload.captured).getJSONObject("main")
        assertEquals("90s", main.getString("ConnectTimeout"))
        assertEquals(20, main.getInt("LowLevelRetries"))
        assertEquals(5, main.getInt("Retries"))
    }

    @Test
    fun initializeSurvivesOptionsSetFailure() {
        mockkObject(RcloneBridge)
        every { RcloneBridge.initialize(any()) } returns Unit
        every { RcloneBridge.rpc("options/set", any()) } returns
            RcloneBridge.RpcResult(500, """{"error":"boom"}""")
        // A later RPC must still work: proves initialized stayed true even though
        // the best-effort tuning call failed.
        every { RcloneBridge.rpc("config/listremotes", any()) } returns
            RcloneBridge.RpcResult(200, """{"remotes":["gdrive"]}""")

        val c = client()
        c.initialize()

        assertTrue("initialize must complete despite options/set failure", c.listRemotes().contains("gdrive"))
    }
}