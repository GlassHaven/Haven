package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [vmBootstrapScript]/[attachAndMountScript]/[markerVersion] are pure
 * string-building/parsing — extracted top-level so they're testable without
 * an Android Context. These pin the shell fragments the shared-VM multi-drive
 * work depends on: the ro/rw mount branch, the LUKS skip-and-report branch,
 * the delta-based (not bare-existence) enumeration wait, the busid-scoped
 * (not blind port-0) retry-detach, and the appliance marker's
 * version-upgrade gate.
 */
class QemuManagerScriptTest {

    @Test
    fun `read-only attach mounts ro with the noload fallback`() {
        val script = attachAndMountScript(busid = "1-2", readOnly = true)
        assertTrue(script.contains("mount -o ro \"\$p\" \"\$d\""))
        assertTrue(script.contains("mount -o ro,noload \"\$p\" \"\$d\""))
        assertFalse("read-only must never mount rw", script.contains("mount -o rw"))
    }

    @Test
    fun `noload fallback is gated on ext4-xfs — vfat-exfat-ntfs reject it outright`() {
        val script = attachAndMountScript(busid = "1-2", readOnly = true)
        assertTrue(
            "noload must be conditioned on fstype, or vfat/exfat/ntfs/ntfs3 reject the whole mount",
            script.contains("if [ \"\$t\" = ext4 ] || [ \"\$t\" = xfs ]; then mount -o ro,noload"),
        )
    }

    @Test
    fun `writable attach mounts rw with sync, no noload fallback`() {
        val script = attachAndMountScript(busid = "1-2", readOnly = false)
        assertTrue(script.contains("mount -o rw,sync \"\$p\" \"\$d\""))
        assertFalse("writable must not silently downgrade via the ro,noload fallback", script.contains("mount -o ro"))
    }

    @Test
    fun `LUKS partitions are skipped in the mount loop and reported separately`() {
        val script = attachAndMountScript(busid = "1-2", readOnly = true)
        assertTrue("must classify by blkid TYPE before mounting", script.contains("blkid -o value -s TYPE"))
        assertTrue(
            "must skip mounting crypto_LUKS partitions",
            script.contains("if [ \"\$t\" = crypto_LUKS ]; then echo \"HVNLOCKED:\$l\"; continue; fi"),
        )
        assertTrue("must report unmapped LUKS partitions as HVNLOCKED", script.contains("HVNLOCKED"))
    }

    @Test
    fun `busid is embedded verbatim in the attach command`() {
        val script = attachAndMountScript(busid = "3-7", readOnly = true)
        assertTrue(script.contains("usbip attach -r 10.0.2.2 -b 3-7"))
    }

    @Test
    fun `mount and report loop is scoped to a captured new-device list, not a blind rescan`() {
        // A second drive's attach re-runs this against the SAME shared VM —
        // scanning /dev/sd* directly (rather than a captured list of just
        // this attach's own new nodes) would re-touch or re-report an
        // earlier drive's already-mounted/already-locked partitions as if
        // they belonged to THIS drive (reproduced live: two drives sharing
        // a VM both reported the same locked partition).
        val script = attachAndMountScript(busid = "1-2", readOnly = true)
        assertTrue("must capture the new-device delta to a file", script.contains("newDev="))
        assertTrue(
            "the mount/report loop must read from the captured delta, not glob /dev/sd* directly",
            script.contains("done < \"\$newDev\""),
        )
        assertFalse(
            "must not mount-loop over a blind /dev/sd* glob (that would touch every attached drive's partitions)",
            script.contains("for p in /dev/sd[a-z][0-9]*"),
        )
    }

    @Test
    fun `enumeration wait polls a cheap COUNT, not bare existence — full list diff computed only once after`() {
        // With another drive already attached, its partitions already exist
        // — a bare `ls ... && break` would exit immediately without ever
        // waiting for THIS busid's own new partition to appear. A count
        // catches that same case (this busid's own new node makes the count
        // exceed its pre-attach value) far more cheaply than a full
        // sort+diff every second — under TCG, forking `sort`/`grep` on every
        // ~1s poll iteration (rather than once) meaningfully slowed live
        // attaches (reproduced: 195s+ for what used to take under a minute).
        val script = attachAndMountScript(busid = "1-2", readOnly = true)
        assertTrue("must snapshot the partition list (for the one-time diff) before attaching", script.contains("beforeDev="))
        assertTrue("must snapshot the cheap count too", script.contains("beforeCount=\$(wc -l < \"\$beforeDev\")"))
        assertTrue(
            "the polling loop itself must only do the cheap count check, not a full diff",
            script.contains("curCount=\$(ls /dev/sd[a-z][0-9]* 2>/dev/null | wc -l); [ \"\$curCount\" -gt \"\$beforeCount\" ]"),
        )
        assertTrue(
            "the full list diff (needed for mount/report scoping) must still happen, but only once after the loop",
            script.contains("grep -vxFf \"\$beforeDev\" \"\$afterDev\" 2>/dev/null > \"\$newDev\""),
        )
    }

    @Test
    fun `retry-detach is scoped to this busid's own vhci port, never a blind port 0`() {
        // Safe with exactly one device attached; with multiple, port 0 could
        // belong to a completely different, already-working drive.
        val script = attachAndMountScript(busid = "1-2", readOnly = true)
        assertFalse("must not blindly detach port 0 — that could be a different drive", script.contains("detach -p 00"))
        assertFalse("must not blindly detach port 0 — that could be a different drive", script.contains("detach -p 0 "))
        assertTrue("must derive the port from the vhci status delta instead", script.contains("vhci_hcd.0/status"))
        assertTrue(script.contains("usbip detach -p \"\$myPort\""))
    }

    @Test
    fun `attach script reports its own vhci port`() {
        val script = attachAndMountScript(busid = "1-2", readOnly = true)
        assertTrue(script.contains("HVNPORT:"))
    }

    @Test
    fun `attach script has no sshd or key setup — that's the bootstrap script's job`() {
        val script = attachAndMountScript(busid = "1-2", readOnly = true)
        assertFalse(script.contains("authorized_keys"))
        assertFalse(script.contains("sshd"))
        assertFalse(script.contains("ssh-keygen"))
    }

    @Test
    fun `bootstrap script authorizes the key and starts sshd, with no usbip attach`() {
        val script = vmBootstrapScript(pubKey = "ssh-ed25519 AAAAtestkey user@host", busidComment = null)
        assertTrue(script.contains("ssh-ed25519 AAAAtestkey user@host"))
        assertTrue(script.contains("authorized_keys"))
        assertTrue(script.contains("ssh-keygen -A"))
        assertTrue(script.contains("modprobe vhci_hcd"))
        assertFalse("attaching a drive is attachAndMountScript's job, not bootstrap's", script.contains("usbip attach"))
    }

    @Test
    fun `bootstrap script tags the key with a busid comment when one is given`() {
        val script = vmBootstrapScript(pubKey = "ssh-ed25519 AAAAtestkey user@host", busidComment = "1-2")
        assertTrue(script.contains("ssh-ed25519 AAAAtestkey user@host haven-usb:1-2"))
    }

    // #506: a reporter's boot stopped at "sshd never answered on 127.0.0.1:<port>" —
    // the bootstrap script used to echo BOOT_OK unconditionally after a single
    // DHCP attempt, so the error named a port and nothing else. These pin the
    // gate: BOOT_OK only fires when the lease and the sshd listener are both
    // verified, and every failure path names itself.

    @Test
    fun `bootstrap gates BOOT_OK on a verified lease and a listening sshd`() {
        val script = vmBootstrapScript(pubKey = "ssh-ed25519 AAAAtestkey user@host", busidComment = null)
        assertTrue("must check the guest actually holds an IPv4 address", script.contains("ip -4 addr show dev eth0"))
        assertTrue("must check sshd bound port 22", script.contains("netstat -tln"))
        // BOOT_OK must be inside the success branch, not an unconditional tail echo.
        assertTrue(
            "BOOT_OK must only echo when both checks pass",
            Regex("if .*then echo HAVEN_BOOTSTRAP_OK").containsMatchIn(script),
        )
        assertFalse(
            "the last echo must be the if/else chain, not a bare trailing BOOT_OK",
            script.trimEnd().endsWith("echo HAVEN_BOOTSTRAP_OK"),
        )
    }

    @Test
    fun `bootstrap failure paths name themselves`() {
        val script = vmBootstrapScript(pubKey = "ssh-ed25519 AAAAtestkey user@host", busidComment = null)
        assertTrue(script.contains("echo HAVEN_BOOTSTRAP_FAIL:no-network"))
        assertTrue(script.contains("echo HAVEN_BOOTSTRAP_FAIL:sshd-not-listening"))
    }

    @Test
    fun `bootstrap marker regex matches every marker the script can emit`() {
        val script = vmBootstrapScript(pubKey = "ssh-ed25519 AAAAtestkey user@host", busidComment = null)
        val re = Regex(BOOTSTRAP_MARKER_REGEX)
        for (marker in listOf("HAVEN_BOOTSTRAP_OK", "HAVEN_BOOTSTRAP_FAIL:no-network", "HAVEN_BOOTSTRAP_FAIL:sshd-not-listening")) {
            assertTrue("script emits $marker", script.contains(marker))
            assertTrue("wait regex must match $marker", re.containsMatchIn("echo noise\n$marker\n"))
        }
        // And the alternation must pick the FAIL branch out of a stream that
        // contains a stale echo of the script text (the echo problem bootSharedVm
        // stty -echo's around — the regex still has to resolve correctly).
        assertEquals("HAVEN_BOOTSTRAP_FAIL:no-network", re.find("HAVEN_BOOTSTRAP_FAIL:no-network")?.value)
    }

    @Test
    fun `network up retries udhcpc until the lease exists`() {
        val script = vmBootstrapScript(pubKey = "ssh-ed25519 AAAAtestkey user@host", busidComment = null)
        assertTrue(
            "a single udhcpc -q -n miss used to go unnoticed until the banner probe timed out; " +
                "must retry and only break once an address exists",
            Regex("for i in [0-9 ]+; do udhcpc -i eth0 -q -n[^;]*; ip -4 addr show dev eth0[^;]*grep -q 'inet '[^;]*&& break").containsMatchIn(script),
        )
    }

    @Test
    fun `bootstrap failure messages name the stage in user terms`() {
        assertTrue(bootstrapFailureMessage("no-network").contains("address"))
        assertTrue(bootstrapFailureMessage("sshd-not-listening").contains("SSH server"))
    }

    @Test
    fun `markerVersion parses a numeric marker`() {
        val dir = Files.createTempDirectory("qemu-marker-test").toFile()
        val marker = File(dir, "usb_vm_appliance.img.ok").apply { writeText("3\n") }
        assertEquals(3, markerVersion(marker))
        dir.deleteRecursively()
    }

    @Test
    fun `markerVersion treats a pre-versioning marker as version 0`() {
        val dir = Files.createTempDirectory("qemu-marker-test").toFile()
        val marker = File(dir, "usb_vm_appliance.img.ok").apply { writeText("ok\n") }
        assertEquals(0, markerVersion(marker))
        dir.deleteRecursively()
    }

    @Test
    fun `markerVersion is 0 for a missing marker`() {
        val dir = Files.createTempDirectory("qemu-marker-test").toFile()
        val marker = File(dir, "usb_vm_appliance.img.ok")
        assertEquals(0, markerVersion(marker))
        dir.deleteRecursively()
    }
}
