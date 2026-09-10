package sh.haven.app.transport

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.ssh.Transport
import sh.haven.core.ssh.TransportSessionManager

/**
 * Every [Transport] must have a registry binding.
 *
 * Before #510, `SessionManagerRegistry` named all twelve managers in its
 * constructor, so forgetting one was a compile error — that is what stopped
 * the disconnect and terminal-input paths silently skipping a transport
 * (#363, #366). Contributing them via `@IntoSet` buys the ability to drop RDP
 * from a build variant, but gives that guarantee up: a missing binding is a
 * transport that quietly stops appearing in session lists, keeping the
 * foreground service alive, and accepting input.
 *
 * This test is the replacement guarantee. Adding a [Transport] constant
 * without a binding fails here, and so does deleting a binding.
 */
class TransportBindingsTest {

    // Reified: mockk resolves the manager type from the @Provides parameter
    // each call site is filling in.
    private inline fun <reified T : Any> manager(): T = mockk(relaxed = true)

    private val nonDesktop: List<TransportSessionManager> = with(TransportSessionManagerModule) {
        listOf(
            ssh(manager()),
            reticulum(manager()),
            mosh(manager()),
            et(manager()),
            btSerial(manager()),
            bleSerial(manager()),
            usbSerial(manager()),
            local(manager()),
            guest(manager()),
            smb(manager()),
            mail(manager()),
            rclone(manager()),
        )
    }

    /**
     * Bound separately so a terminal-only variant can leave the file out. When
     * that variant exists this list moves to a flavour source set — the
     * assertion below then reads "every transport this build ships".
     */
    private val desktop: List<TransportSessionManager> =
        listOf(DesktopTransportModule.rdpTransport(manager()))

    @Test
    fun `every transport has a registry binding`() {
        val bound = (nonDesktop + desktop).map { it.transport }.toSet()

        assertEquals(Transport.entries.toSet(), bound)
    }

    @Test
    fun `no transport is bound twice`() {
        val all = (nonDesktop + desktop).map { it.transport }

        assertEquals(
            "a transport bound twice would disconnect and list its sessions twice",
            all.size,
            all.toSet().size,
        )
    }

    /**
     * The transports that carry a PTY are the ones terminal input is offered
     * to. RDP and SMB have no terminal, and rclone is a storage handle — if
     * one of them grew an `inputName` it would start appearing in the
     * "no transport owned this session" error the user reads.
     */
    @Test
    fun `only PTY-like transports accept terminal input`() {
        val writable = (nonDesktop + desktop).filter { it.inputName != null }.map { it.transport }.toSet()

        assertEquals(
            setOf(
                Transport.SSH, Transport.LOCAL, Transport.GUEST, Transport.MOSH, Transport.ET,
                Transport.RETICULUM, Transport.BTSERIAL, Transport.BLESERIAL, Transport.USBSERIAL,
            ),
            writable,
        )
    }
}
