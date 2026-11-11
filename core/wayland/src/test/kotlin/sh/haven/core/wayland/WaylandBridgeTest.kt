package sh.haven.core.wayland

import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WaylandBridgeTest {

    @Test
    fun `WaylandBridge exposes reactive compositorRunning StateFlow`() {
        val getter = WaylandBridge::class.java.methods.firstOrNull { it.name == "getCompositorRunning" }
        assertNotNull("WaylandBridge must expose compositorRunning: StateFlow<Boolean>", getter)
        @Suppress("UNCHECKED_CAST")
        val flow = getter!!.invoke(WaylandBridge) as StateFlow<Boolean>
        assertEquals(false, flow.value)

        val refresh = WaylandBridge::class.java.methods.firstOrNull { it.name == "refreshCompositorState" }
        assertNotNull("WaylandBridge must expose refreshCompositorState() for start/stop transitions", refresh)
        refresh!!.invoke(WaylandBridge)
        assertEquals(false, flow.value)
    }
}
