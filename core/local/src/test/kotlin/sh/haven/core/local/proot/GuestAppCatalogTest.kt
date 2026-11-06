package sh.haven.core.local.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #470 — invariants of the curated pack data and the shape of the generated
 * guest install script. The data tests exist so a future edit can't silently
 * drop the parts a pack depends on for correctness (qmmp's pulse config
 * write, sha256 pins, verified-family honesty).
 */
class GuestAppCatalogTest {

    @Test
    fun `catalog invariants hold for every pack`() {
        assertTrue(GuestAppCatalog.PACKS.isNotEmpty())
        for (p in GuestAppCatalog.PACKS) {
            assertTrue("${p.id}: needs at least one family", p.packages.isNotEmpty())
            assertTrue(
                "${p.id}: verifiedFamilies must be a subset of packages.keys",
                p.packages.keys.containsAll(p.verifiedFamilies),
            )
            assertFalse(
                "${p.id}: verifyBinary is rootfs-relative (no leading /)",
                p.verifyBinary.startsWith("/"),
            )
            assertTrue("${p.id}: appCommand set", p.appCommand.isNotBlank())
            for (a in p.assets) {
                assertTrue(
                    "${p.id}: asset sha256 must be 64 hex chars (${a.url})",
                    a.sha256.matches(Regex("[0-9a-f]{64}")),
                )
                assertTrue("${p.id}: asset destDir absolute", a.destDir.startsWith("/"))
            }
            for (w in p.configWrites) {
                assertTrue("${p.id}: config path absolute", w.path.startsWith("/"))
                assertTrue("${p.id}: stanza non-blank", w.stanza.isNotBlank())
            }
        }
    }

    @Test
    fun `pack ids are unique and resolvable`() {
        val ids = GuestAppCatalog.PACKS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) assertEquals(id, GuestAppCatalog.byId(id)?.id)
    }

    /** The device-diagnosed silent failure (#470): qmmp defaults to ALSA,
     *  which has no /dev/snd in proot. Dropping this config write would
     *  reintroduce a player that looks fine and plays nothing. */
    @Test
    fun `qmmp pack keeps the pulse output config write`() {
        val w = GuestAppCatalog.QMMP.configWrites.first { it.stanza.startsWith("[Output]") }
        assertEquals("/root/.qmmp/qmmprc", w.path)
        assertTrue(w.stanza.contains("current_plugin=pulse"))
        // And the deck opens whole on first run (PL/EQ default closed).
        assertTrue(
            GuestAppCatalog.QMMP.configWrites.any {
                it.stanza.startsWith("[Skinned]") && it.stanza.contains("pl_visible=true")
            },
        )
        assertTrue(GuestAppCatalog.QMMP.needsAudioBridge)
        // Skinned UI needs xcb (native Wayland kills the compositor) and the
        // three-window deck needs floating, not kiosk fullscreen.
        assertTrue(GuestAppCatalog.QMMP.appCommand.contains("QT_QPA_PLATFORM=xcb"))
        assertTrue(GuestAppCatalog.QMMP.multiWindow)
        // Placement rules for all three deck windows — sway centers floating
        // windows, so without these the deck maps as a stack.
        assertEquals(3, GuestAppCatalog.QMMP.swayRules.size)
        for (title in listOf("Qmmp", "Playlist", "Equalizer")) {
            assertTrue(GuestAppCatalog.QMMP.swayRules.any { it.contains(title) && it.contains("move position") })
        }
    }

    @Test
    fun `install script has package phase, guarded config append, and pinned asset fetch`() {
        val script = buildPackInstallScript(
            GuestAppCatalog.QMMP,
            GuestAppCatalog.QMMP.packages.getValue(PackageFamily.APT),
            PackageFamily.APT,
            includeAssets = true,
        )
        assertTrue(script.startsWith("set -e\n"))
        assertTrue("install phase", script.contains("apt-get install") && script.contains("qmmp"))
        // Idempotence guard: exact-match grep on the stanza's first line.
        assertTrue(script.contains("grep -qxF '[Output]' '/root/.qmmp/qmmprc'"))
        assertTrue("append after guard", script.contains(">> '/root/.qmmp/qmmprc'"))
        // Pinned asset: curl then a sha256sum check of the same dest path.
        val sha = GuestAppCatalog.QMMP.assets.single().sha256
        assertTrue(script.contains("curl -fsSL --retry 2 -o '/root/.qmmp/skins/Pika_Amp.wsz'"))
        assertTrue(script.contains(sha) && script.contains("sha256sum -c -"))
    }

    @Test
    fun `includeAssets=false drops the asset phase but keeps config writes`() {
        val script = buildPackInstallScript(
            GuestAppCatalog.QMMP,
            listOf("qmmp"),
            PackageFamily.APT,
            includeAssets = false,
        )
        assertFalse(script.contains("curl "))
        assertTrue(script.contains("current_plugin=pulse"))
    }

    /** Single quotes in a stanza must survive shell quoting. */
    @Test
    fun `config stanzas with single quotes are escaped for sh`() {
        val pack = GuestAppCatalog.QMMP.copy(
            configWrites = listOf(PackConfigWrite("/root/.x/rc", "it's fine")),
            assets = emptyList(),
        )
        val script = buildPackInstallScript(pack, listOf("qmmp"), PackageFamily.APT, includeAssets = true)
        assertTrue(script.contains("'it'\\''s fine'"))
    }

    @Test
    fun `emulator packs configure pulse audio and software rendering`() {
        val dosbox = GuestAppCatalog.DOSBOX
        assertTrue(dosbox.needsAudioBridge)
        assertTrue(dosbox.appCommand.contains("SDL_AUDIODRIVER=pulse"))
        assertTrue(dosbox.configWrites.any { it.path == "/root/.dosbox/haven.conf" && it.stanza.contains("usescancodes=false") })

        val fsUae = GuestAppCatalog.FS_UAE
        assertTrue(fsUae.needsAudioBridge)
        assertTrue(fsUae.appCommand.contains("LIBGL_ALWAYS_SOFTWARE=1"))
        assertTrue(fsUae.appCommand.contains("ALSOFT_DRIVERS=pulse"))
        assertTrue(fsUae.configWrites.any { it.path == "/root/.config/fs-uae/fs-uae.conf" && it.stanza.contains("amiga_model = A500") })

        val vice = GuestAppCatalog.VICE
        assertTrue(vice.needsAudioBridge)
        assertTrue(vice.appCommand.contains("GDK_BACKEND=x11"))
        assertTrue(vice.configWrites.any { it.path == "/root/.config/vice/vicerc" && it.stanza.contains("SoundDeviceName=\"pulse\"") })
    }
}
