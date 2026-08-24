package sh.haven.app.reticulum

import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connecting to a Sideband or Columba shared instance left Haven with no
 * interfaces at all (#588).
 *
 * reticulum-kt builds the shared instance's client interface itself and hands
 * it back through a registrar callback. Haven never set one, so the interface
 * was created and started and then never registered with Transport: the
 * connection reported as up while nothing could be sent or received over it.
 * The library logs a warning and continues, so from outside it looked fine.
 *
 * The gateway path registers its own interface, which is why only the shared
 * instance path was affected.
 */
class SharedInstanceRegistrarTest {

    private val registered = mutableListOf<LocalClientInterface>()

    @After
    fun deregister() {
        registered.forEach { Transport.deregisterInterface(it.toRef()) }
        registered.clear()
    }

    @Test
    fun `the registrar puts a shared instance interface into Transport`() {
        val iface = LocalClientInterface("Sideband", tcpPort = 37428, tcpHost = "127.0.0.1")
        registered += iface

        val before = Transport.getInterfaces().any { it.name == iface.name }
        assertTrue("interface was already registered before the test ran", !before)

        sharedInstanceInterfaceRegistrar(iface)

        assertTrue(
            "shared instance interface never reached Transport",
            Transport.getInterfaces().any { it.name == iface.name },
        )
    }

    @Test
    fun `a non-interface is not registered`() {
        val countBefore = Transport.getInterfaces().size

        sharedInstanceInterfaceRegistrar("not an interface")

        assertTrue(
            "something that is not a Reticulum interface was registered",
            Transport.getInterfaces().size == countBefore,
        )
    }
}
