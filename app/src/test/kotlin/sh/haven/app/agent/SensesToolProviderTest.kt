package sh.haven.app.agent

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.mcp.McpError

/**
 * Unit-test what is JVM-testable in [SensesToolProvider]: the pure vendor-
 * constant helpers and the consent/schema metadata. The four tools' real
 * behaviour (BatteryManager broadcasts, SensorManager windows, LocationManager
 * fixes, Camera2 capture) runs only on a device — the on-device paths are NOT
 * covered here and are marked unverified in the commit message.
 */
class SensesToolProviderTest {

    private fun newProvider(grant: (String) -> String? = { "shizuku unavailable" }) =
        SensesToolProvider(
            context = mockk<Context>(relaxed = true) {
                every { packageName } returns "sh.haven.app"
            },
            shizukuGrant = grant,
        )

    // --- vendor-constant helpers ---

    @Test
    fun `battery percent divides level by scale and clamps`() {
        assertEquals(50, batteryPercent(50, 100))
        assertEquals(0, batteryPercent(0, 100))
        assertEquals(100, batteryPercent(120, 100))
        assertNull(batteryPercent(-1, 100))
        assertNull(batteryPercent(50, 0))
        assertNull(batteryPercent(50, -1))
    }

    @Test
    fun `battery status and plugged names`() {
        assertEquals("charging", batteryStatusName(android.os.BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("full", batteryStatusName(android.os.BatteryManager.BATTERY_STATUS_FULL))
        assertEquals("discharging", batteryStatusName(android.os.BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals("unknown", batteryStatusName(-42))

        assertEquals("ac", batteryPluggedName(android.os.BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals("usb", batteryPluggedName(android.os.BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("wireless", batteryPluggedName(android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals("unplugged_or_unknown", batteryPluggedName(0))
        assertEquals("unplugged_or_unknown", batteryPluggedName(-42))
    }

    @Test
    fun `thermal status names and the throttling threshold`() {
        assertEquals("none", thermalStatusName(android.os.PowerManager.THERMAL_STATUS_NONE))
        assertEquals("light", thermalStatusName(android.os.PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals("moderate", thermalStatusName(android.os.PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("shutdown", thermalStatusName(android.os.PowerManager.THERMAL_STATUS_SHUTDOWN))
        assertEquals("unknown", thermalStatusName(999))
        // The provider reports throttling at MODERATE and above.
        assertTrue(android.os.PowerManager.THERMAL_STATUS_MODERATE >=
            android.os.PowerManager.THERMAL_STATUS_MODERATE)
        assertFalse(android.os.PowerManager.THERMAL_STATUS_LIGHT >=
            android.os.PowerManager.THERMAL_STATUS_MODERATE)
    }

    @Test
    fun `network transport names map each bit`() {
        fun has(vararg transports: Int): (Int) -> Boolean = { t -> t in transports }
        assertEquals(
            listOf("wifi", "cellular", "vpn"),
            networkTransportNames(has(
                android.net.NetworkCapabilities.TRANSPORT_VPN,
                android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                android.net.NetworkCapabilities.TRANSPORT_WIFI,
            )),
        )
        assertEquals(listOf("unknown"), networkTransportNames(has()))
    }

    @Test
    fun `round produces stable decimals`() {
        assertEquals(12.3457, round(12.345678, 4), 1e-9)
        assertEquals(51.5, round(51.45001, 1), 1e-9)
        assertEquals(-0.123, round(-0.1234, 3), 1e-9)
        assertEquals(0.0, round(0.0, 6), 0.0)
    }

    // --- registry metadata ---

    @Test
    fun `consent levels are pinned`() {
        val tools = newProvider().tools()
        assertEquals(ConsentLevel.NEVER, tools["get_device_state"]!!.consentLevel)
        assertEquals(ConsentLevel.ONCE_PER_SESSION, tools["read_sensors"]!!.consentLevel)
        assertEquals(ConsentLevel.EVERY_CALL, tools["get_location"]!!.consentLevel)
        assertEquals(ConsentLevel.EVERY_CALL, tools["capture_camera_frame"]!!.consentLevel)
    }

    @Test
    fun `consent summaries name what they would do`() {
        val tools = newProvider().tools()
        // get_device_state has no consent sheet (NEVER → default summary).
        assertEquals("tool call", tools["get_device_state"]!!.summarise(JSONObject()))

        val sensors = tools["read_sensors"]!!.summarise(JSONObject())
        assertTrue("got: $sensors", sensors.contains("sensors"))
        val filtered = tools["read_sensors"]!!
            .summarise(JSONObject().put("sensors", org.json.JSONArray().put("pressure")))
        assertTrue("got: $filtered", filtered.contains("pressure"))

        assertEquals(
            "Take one location fix from this phone?",
            tools["get_location"]!!.summarise(JSONObject()),
        )

        val front = tools["capture_camera_frame"]!!
            .summarise(JSONObject().put("lensFacing", "front"))
        assertTrue("got: $front", front.contains("front"))
        val defaultFacing = tools["capture_camera_frame"]!!.summarise(JSONObject())
        assertTrue("got: $defaultFacing", defaultFacing.contains("back"))
    }

    @Test
    fun `capture rejects an unknown lensFacing before touching the camera`() {
        val provider = newProvider()
        val e = kotlinx.coroutines.runBlocking {
            runCatching {
                provider.tools()["capture_camera_frame"]!!
                    .handle(JSONObject().put("lensFacing", "sideways"))
            }.exceptionOrNull()
        }
        assertTrue("expected McpError, got $e", e is McpError)
        assertTrue((e as McpError).message!!.contains("back, front, or external"))
    }

    @Test
    fun `read_sensors errors clearly when a filter matches nothing`() {
        // No sensors at all: the provider must fail with a McpError naming
        // availability, not return an empty reading set as success.
        val ctx = mockk<Context>(relaxed = true) {
            every { packageName } returns "sh.haven.app"
            every { getSystemService(Context.SENSOR_SERVICE) } returns mockk<android.hardware.SensorManager> {
                every { getDefaultSensor(any()) } returns null
            }
        }
        val provider = SensesToolProvider(ctx, shizukuGrant = { "shizuku unavailable" })
        val e = kotlinx.coroutines.runBlocking {
            runCatching {
                provider.tools()["read_sensors"]!!
                    .handle(JSONObject().put("sensors", org.json.JSONArray().put("accelerometer")))
            }.exceptionOrNull()
        }
        assertTrue("expected McpError, got $e", e is McpError)
        assertTrue((e as McpError).message!!.contains("No matching sensors"))
    }
}