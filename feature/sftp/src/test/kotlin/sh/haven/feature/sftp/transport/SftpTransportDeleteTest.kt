package sh.haven.feature.sftp.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.ssh.sftp.ListResult
import sh.haven.core.ssh.sftp.SftpAttrs
import sh.haven.feature.sftp.SftpViewModel
import java.io.IOException

/**
 * Unit tests for recursive SFTP directory deletion (#815) and local media
 * scanner path resolution (#816).
 */
class SftpTransportDeleteTest {

    private class TreeSftpSession(
        initialDirs: Set<String>,
        initialFiles: Set<String>,
        initialSymlinks: Map<String, String> = emptyMap(),
    ) : FakeSftpSession() {
        val dirs = initialDirs.toMutableSet()
        val files = initialFiles.toMutableSet()
        val symlinks = initialSymlinks.toMutableMap()
        val ops = mutableListOf<String>()

        override suspend fun list(path: String, onEntry: (SftpAttrs) -> ListResult) {
            ops.add("list:$path")
            if (path !in dirs) throw IOException("SSH_FX_NO_SUCH_FILE: $path")
            val prefix = if (path == "/") "/" else "${path.trimEnd('/')}/"
            val children = mutableListOf<SftpAttrs>()

            for (d in dirs.sorted()) {
                if (d != path && d.startsWith(prefix)) {
                    val rem = d.removePrefix(prefix)
                    if ('/' !in rem && rem.isNotEmpty()) {
                        children.add(SftpAttrs(rem, isDirectory = true, isSymlink = false, 0, 0, "drwxr-xr-x", 0, 0))
                    }
                }
            }
            for (f in files.sorted()) {
                if (f.startsWith(prefix)) {
                    val rem = f.removePrefix(prefix)
                    if ('/' !in rem && rem.isNotEmpty()) {
                        children.add(SftpAttrs(rem, isDirectory = false, isSymlink = false, 10, 0, "-rw-r--r--", 0, 0))
                    }
                }
            }
            for ((linkPath, _) in symlinks.entries.sortedBy { it.key }) {
                if (linkPath.startsWith(prefix)) {
                    val rem = linkPath.removePrefix(prefix)
                    if ('/' !in rem && rem.isNotEmpty()) {
                        // On the SFTP wire, ls() uses lstat: symlinks have isSymlink=true, isDirectory=false
                        children.add(SftpAttrs(rem, isDirectory = false, isSymlink = true, 0, 0, "lrwxrwxrwx", 0, 0))
                    }
                }
            }

            for (attr in children) {
                if (onEntry(attr) == ListResult.BREAK) return
            }
        }

        override suspend fun rm(path: String) {
            ops.add("rm:$path")
            when {
                files.remove(path) -> Unit
                symlinks.remove(path) != null -> Unit
                path in dirs -> throw IOException("SSH_FX_FAILURE: cannot rm directory $path")
                else -> throw IOException("SSH_FX_NO_SUCH_FILE: $path")
            }
        }

        override suspend fun rmdir(path: String) {
            ops.add("rmdir:$path")
            if (path !in dirs) throw IOException("SSH_FX_NO_SUCH_FILE: $path")
            val prefix = if (path == "/") "/" else "${path.trimEnd('/')}/"
            val hasChildDir = dirs.any { it != path && it.startsWith(prefix) }
            val hasChildFile = files.any { it.startsWith(prefix) }
            val hasChildLink = symlinks.keys.any { it.startsWith(prefix) }
            if (hasChildDir || hasChildFile || hasChildLink) {
                throw IOException("SSH_FX_FAILURE: directory not empty: $path")
            }
            dirs.remove(path)
        }
    }

    @Test
    fun deleteNonEmptyNestedDirectoryRecursivelyRemovesChildrenBeforeRmdir() = runBlocking {
        val sim = TreeSftpSession(
            initialDirs = setOf("/", "/work", "/work/sub", "/work/sub/deep", "/keep"),
            initialFiles = setOf(
                "/work/a.txt",
                "/work/.hidden",
                "/work/sub/b.txt",
                "/work/sub/deep/c.txt",
                "/keep/untouched.txt",
            ),
        )
        val transport = SftpTransport({ sim })

        transport.delete("/work", isDirectory = true)

        assertFalse("/work" in sim.dirs)
        assertFalse("/work/sub" in sim.dirs)
        assertFalse("/work/sub/deep" in sim.dirs)
        assertTrue(sim.files.none { it.startsWith("/work/") })
        assertTrue("/keep" in sim.dirs)
        assertTrue("/keep/untouched.txt" in sim.files)
    }

    @Test
    fun deleteDirectoryUnlinksSymlinksWithoutTraversingTargets() = runBlocking {
        val sim = TreeSftpSession(
            initialDirs = setOf("/", "/work", "/target_dir"),
            initialFiles = setOf("/work/file.txt", "/target_dir/precious.txt"),
            initialSymlinks = mapOf("/work/link_to_target" to "/target_dir"),
        )
        val transport = SftpTransport({ sim })

        transport.delete("/work", isDirectory = true)

        assertFalse("/work" in sim.dirs)
        assertFalse("/work/link_to_target" in sim.symlinks)
        assertTrue("/target_dir" in sim.dirs)
        assertTrue("/target_dir/precious.txt" in sim.files)
        assertTrue("rm:/work/link_to_target" in sim.ops)
        assertFalse("rmdir:/work/link_to_target" in sim.ops)
    }

    @Test
    fun deleteRegularFileCallsRmDirectly() = runBlocking {
        val sim = TreeSftpSession(
            initialDirs = setOf("/", "/work"),
            initialFiles = setOf("/work/a.txt", "/work/b.txt"),
        )
        val transport = SftpTransport({ sim })

        transport.delete("/work/a.txt", isDirectory = false)

        assertFalse("/work/a.txt" in sim.files)
        assertTrue("/work/b.txt" in sim.files)
        assertEquals(listOf("rm:/work/a.txt"), sim.ops)
    }

    @Test
    fun resolveLocalPathFromDocumentIdHandlesPrimarySecondaryAndRaw() {
        assertEquals(
            "/storage/emulated/0/Pictures/cat.jpg",
            SftpViewModel.resolveLocalPathFromDocumentId(
                authority = "com.android.externalstorage.documents",
                docId = "primary:Pictures/cat.jpg",
                externalStorageDir = "/storage/emulated/0",
            ),
        )
        assertEquals(
            "/storage/1234-5678/DCIM/Camera/clip.mp4",
            SftpViewModel.resolveLocalPathFromDocumentId(
                authority = "com.android.externalstorage.documents",
                docId = "1234-5678:DCIM/Camera/clip.mp4",
                externalStorageDir = "/storage/emulated/0",
            ),
        )
        assertEquals(
            "/storage/emulated/0/Download/song.flac",
            SftpViewModel.resolveLocalPathFromDocumentId(
                authority = "com.android.providers.downloads.documents",
                docId = "raw:/storage/emulated/0/Download/song.flac",
                externalStorageDir = "/storage/emulated/0",
            ),
        )
        assertEquals(
            null,
            SftpViewModel.resolveLocalPathFromDocumentId(
                authority = "com.android.providers.downloads.documents",
                docId = "msf:1000012345",
                externalStorageDir = "/storage/emulated/0",
            ),
        )
        assertEquals(
            "/storage/emulated/11/Download/report.pdf",
            SftpViewModel.resolveLocalPathFromDocumentId(
                authority = "com.android.externalstorage.documents",
                docId = "primary:Download/report.pdf",
                externalStorageDir = SftpViewModel.resolvePrimaryExternalStoragePath(
                    envExternalStoragePath = "/storage/emulated/0",
                    filesDirPath = "/data/user/11/sh.haven.app/files",
                    uid = 1110234,
                ),
            ),
        )
    }
}
