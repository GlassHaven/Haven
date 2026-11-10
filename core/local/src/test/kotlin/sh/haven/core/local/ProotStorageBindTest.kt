package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for Android multi-user shared storage resolution and PRoot
 * `/sdcard` bind arguments (#825).
 */
class ProotStorageBindTest {

    @Test
    fun `resolveAndroidUserId extracts user id from data user path`() {
        assertEquals(0, resolveAndroidUserId(filesDirPath = "/data/user/0/sh.haven.app/files", uid = 10234))
        assertEquals(11, resolveAndroidUserId(filesDirPath = "/data/user/11/sh.haven.app/files", uid = 10234))
        assertEquals(10, resolveAndroidUserId(filesDirPath = "/data/user_de/10/sh.haven.app/files", uid = null))
    }

    @Test
    fun `resolveAndroidUserId falls back to uid division when filesDir is legacy or absent`() {
        assertEquals(0, resolveAndroidUserId(filesDirPath = "/data/data/sh.haven.app/files", uid = 10234))
        assertEquals(11, resolveAndroidUserId(filesDirPath = "/data/data/sh.haven.app/files", uid = 1110234))
        assertEquals(11, resolveAndroidUserId(filesDirPath = null, uid = 1110234))
        assertEquals(0, resolveAndroidUserId(filesDirPath = null, uid = null))
    }

    @Test
    fun `resolveUserEmulatedStoragePath resolves user 0 and secondary user 11`() {
        assertEquals(
            "/storage/emulated/0",
            resolveUserEmulatedStoragePath(
                envExternalStoragePath = "/storage/emulated/0",
                filesDirPath = "/data/user/0/sh.haven.app/files",
                uid = 10234,
            ),
        )
        assertEquals(
            "/storage/emulated/11",
            resolveUserEmulatedStoragePath(
                envExternalStoragePath = "/storage/emulated/11",
                filesDirPath = "/data/user/11/sh.haven.app/files",
                uid = 1110234,
            ),
        )
        // Even if Environment.getExternalStorageDirectory() is unavailable or
        // reports /storage/emulated/0 on a secondary profile, user 11's path wins.
        assertEquals(
            "/storage/emulated/11",
            resolveUserEmulatedStoragePath(
                envExternalStoragePath = "/storage/emulated/0",
                filesDirPath = "/data/user/11/sh.haven.app/files",
                uid = 1110234,
            ),
        )
        assertEquals(
            "/storage/emulated/11",
            resolveUserEmulatedStoragePath(
                envExternalStoragePath = null,
                filesDirPath = null,
                uid = 1110234,
            ),
        )
    }

    @Test
    fun `buildStorageBindArgs binds active user emulated storage to sdcard`() {
        assertEquals(
            listOf("-b", "/storage", "-b", "/storage/emulated/11:/sdcard"),
            buildStorageBindArgs(
                shareStorageWithGuest = true,
                longForm = false,
                emulatedStoragePath = "/storage/emulated/11/",
            ),
        )
        assertEquals(
            listOf("--bind=/storage", "--bind=/storage/emulated/11:/sdcard"),
            buildStorageBindArgs(
                shareStorageWithGuest = true,
                longForm = true,
                emulatedStoragePath = "/storage/emulated/11",
            ),
        )
        assertEquals(
            emptyList<String>(),
            buildStorageBindArgs(
                shareStorageWithGuest = false,
                longForm = false,
                emulatedStoragePath = "/storage/emulated/11",
            ),
        )
    }
}
