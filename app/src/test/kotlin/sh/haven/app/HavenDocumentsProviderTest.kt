package sh.haven.app

import android.provider.DocumentsContract.Document
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.proot.Distro
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.rclone.RcloneFileEntry
import sh.haven.core.rclone.RcloneSessionManager
import sh.haven.core.rclone.SyncConfig
import sh.haven.core.rclone.SyncJobStatus
import sh.haven.core.rclone.SyncMode
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.ssh.SshSessionManager
import java.io.File
import java.nio.file.Files

class HavenDocumentsProviderTest {

    private lateinit var tempDir: File
    private lateinit var filesDir: File
    private lateinit var rootfsDir: File
    private lateinit var cacheDir: File

    private lateinit var sshSessionManager: SshSessionManager
    private lateinit var moshSessionManager: MoshSessionManager
    private lateinit var etSessionManager: EtSessionManager
    private lateinit var smbSessionManager: SmbSessionManager
    private lateinit var rcloneSessionManager: RcloneSessionManager
    private lateinit var rcloneClient: RcloneClient
    private lateinit var localSessionManager: LocalSessionManager
    private lateinit var prootManager: ProotManager
    private lateinit var entryPoint: HavenDocumentsProvider.ProviderEntryPoint
    private lateinit var provider: HavenDocumentsProvider

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("haven_doc_provider_test").toFile()
        filesDir = File(tempDir, "files").apply { mkdirs() }
        rootfsDir = File(filesDir, "proot/rootfs/alpine-3.21").apply { mkdirs() }
        cacheDir = File(tempDir, "cache").apply { mkdirs() }

        sshSessionManager = mockk(relaxed = true)
        moshSessionManager = mockk(relaxed = true)
        etSessionManager = mockk(relaxed = true)
        smbSessionManager = mockk(relaxed = true)
        rcloneSessionManager = mockk(relaxed = true)
        rcloneClient = mockk(relaxed = true)
        localSessionManager = mockk(relaxed = true)
        prootManager = mockk(relaxed = true)

        every { sshSessionManager.sessions } returns MutableStateFlow(emptyMap())
        every { moshSessionManager.sessions } returns MutableStateFlow(emptyMap())
        every { etSessionManager.sessions } returns MutableStateFlow(emptyMap())
        every { smbSessionManager.sessions } returns MutableStateFlow(emptyMap())
        every { rcloneSessionManager.sessions } returns MutableStateFlow(emptyMap())
        every { localSessionManager.sessions } returns MutableStateFlow(emptyMap())
        every { localSessionManager.prootManager } returns prootManager
        every { prootManager.installedDistros } returns emptyList()
        every { prootManager.activeDistroId } returns "alpine-3.21"
        every { prootManager.isRootfsInstalledFor("alpine-3.21") } returns true
        every { prootManager.rootfsDirFor("alpine-3.21") } returns rootfsDir

        entryPoint = object : HavenDocumentsProvider.ProviderEntryPoint {
            override fun sshSessionManager() = sshSessionManager
            override fun moshSessionManager() = moshSessionManager
            override fun etSessionManager() = etSessionManager
            override fun smbSessionManager() = smbSessionManager
            override fun rcloneSessionManager() = rcloneSessionManager
            override fun rcloneClient() = rcloneClient
            override fun localSessionManager() = localSessionManager
            override fun connectionRepository(): ConnectionRepository = mockk(relaxed = true)
        }
        provider = HavenDocumentsProvider()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `collectRootDescriptors includes connected rclone, local shell, installed distros, and home`() {
        every { rcloneSessionManager.sessions } returns MutableStateFlow(
            mapOf(
                "rc-1" to RcloneSessionManager.SessionState(
                    sessionId = "rc-1",
                    profileId = "prof-gdrive",
                    label = "Google Drive",
                    status = RcloneSessionManager.SessionState.Status.CONNECTED,
                    remoteName = "gdrive",
                    provider = "drive",
                ),
            ),
        )
        every { localSessionManager.sessions } returns MutableStateFlow(
            mapOf(
                "loc-1" to LocalSessionManager.SessionState(
                    sessionId = "loc-1",
                    profileId = "prof-local",
                    label = "Work Shell",
                    status = LocalSessionManager.SessionState.Status.CONNECTED,
                    useAndroidShell = false,
                    prootDistroId = "alpine-3.21",
                ),
            ),
        )
        val alpineDistro = mockk<Distro> {
            every { id } returns "alpine-3.21"
            every { label } returns "Alpine Linux 3.21"
        }
        val debianDir = File(filesDir, "proot/rootfs/debian-12").apply { mkdirs() }
        val debianDistro = mockk<Distro> {
            every { id } returns "debian-12"
            every { label } returns "Debian 12"
        }
        every { prootManager.installedDistros } returns listOf(alpineDistro, debianDistro)
        every { prootManager.isRootfsInstalledFor("debian-12") } returns true
        every { prootManager.rootfsDirFor("debian-12") } returns debianDir

        val roots = provider.collectRootDescriptors(entryPoint, filesDir)

        // 1. Connected Rclone session
        val rcloneRoot = roots.firstOrNull { it.rootId == "prof-gdrive" }
        assertNotNull(rcloneRoot)
        assertEquals("Google Drive", rcloneRoot!!.title)
        assertEquals("Rclone (drive)", rcloneRoot.summary)
        assertEquals("prof-gdrive:/", rcloneRoot.documentId)

        // 2. Connected Local shell session covering alpine-3.21
        val localSessionRoot = roots.firstOrNull { it.rootId == "prof-local" }
        assertNotNull(localSessionRoot)
        assertEquals("Work Shell", localSessionRoot!!.title)
        assertEquals("Local (alpine-3.21)", localSessionRoot.summary)

        // 3. alpine-3.21 is deduplicated (already covered by prof-local), while debian-12 is exposed
        assertNull(roots.firstOrNull { it.rootId == "local-distro-alpine-3.21" })
        val debianRoot = roots.firstOrNull { it.rootId == "local-distro-debian-12" }
        assertNotNull(debianRoot)
        assertEquals("Haven (Debian 12)", debianRoot!!.title)
        assertEquals("Local (debian-12)", debianRoot.summary)

        // 4. Haven Home (filesDir) is also exposed
        val homeRoot = roots.firstOrNull { it.rootId == "local-home" }
        assertNotNull(homeRoot)
        assertEquals("Haven Home", homeRoot!!.title)
        assertEquals("Local", homeRoot.summary)
    }

    @Test
    fun `local root supports query, create, rename, and delete`() {
        val rootDir = File(rootfsDir, "root").apply { mkdirs() }
        File(rootDir, "hello.txt").writeText("hello world")

        // List child documents under /root
        val children = provider.queryChildDescriptors(
            "local-distro-alpine-3.21:/root",
            entryPoint,
            filesDir,
        )
        assertEquals(1, children.size)
        assertEquals("hello.txt", children[0].displayName)
        assertEquals("local-distro-alpine-3.21:/root/hello.txt", children[0].documentId)
        assertEquals(11L, children[0].size)

        // Create a subdirectory and a file
        val subDirDocId = provider.createDoc(
            parentDocumentId = "local-distro-alpine-3.21:/root",
            mimeType = Document.MIME_TYPE_DIR,
            displayName = "notes",
            ep = entryPoint,
            filesDir = filesDir,
        )
        assertEquals("local-distro-alpine-3.21:/root/notes", subDirDocId)
        assertTrue(File(rootDir, "notes").isDirectory)

        val fileDocId = provider.createDoc(
            parentDocumentId = subDirDocId,
            mimeType = "text/plain",
            displayName = "todo.md",
            ep = entryPoint,
            filesDir = filesDir,
        )
        assertEquals("local-distro-alpine-3.21:/root/notes/todo.md", fileDocId)
        assertTrue(File(rootDir, "notes/todo.md").isFile)

        // Rename file
        val renamedDocId = provider.renameDoc(
            documentId = fileDocId,
            displayName = "done.md",
            ep = entryPoint,
            filesDir = filesDir,
        )
        assertEquals("local-distro-alpine-3.21:/root/notes/done.md", renamedDocId)
        assertFalse(File(rootDir, "notes/todo.md").exists())
        assertTrue(File(rootDir, "notes/done.md").isFile)

        // Query renamed document metadata
        val queried = provider.queryDocDescriptor(renamedDocId, entryPoint, filesDir)
        assertNotNull(queried)
        assertEquals("done.md", queried!!.displayName)

        // Delete directory recursively
        provider.deleteDoc(subDirDocId, entryPoint, filesDir)
        assertFalse(File(rootDir, "notes").exists())
    }

    @Test(expected = SecurityException::class)
    fun `resolveLocalFile rejects path traversal outside root`() {
        HavenDocumentsProvider.resolveLocalFile(rootfsDir, "/../../etc/passwd")
    }

    @Test
    fun `rclone root supports query, create, rename, and delete`() {
        every { rcloneSessionManager.getRemoteNameForProfile("prof-gdrive") } returns "gdrive"
        every { rcloneClient.listDirectory("gdrive", "docs") } returns listOf(
            RcloneFileEntry(
                name = "report.pdf",
                path = "docs/report.pdf",
                size = 4096L,
                mimeType = "application/pdf",
                modTime = "2026-11-02T20:00:00Z",
                isDir = false,
            ),
            RcloneFileEntry(
                name = "archive",
                path = "docs/archive",
                size = 0L,
                mimeType = "inode/directory",
                modTime = "2026-11-02T20:00:00Z",
                isDir = true,
            ),
        )

        val children = provider.queryChildDescriptors(
            "prof-gdrive:/docs",
            entryPoint,
            filesDir,
        )
        assertEquals(2, children.size)
        assertEquals("report.pdf", children[0].displayName)
        assertEquals("application/pdf", children[0].mimeType)
        assertEquals(4096L, children[0].size)
        assertEquals("prof-gdrive:/docs/report.pdf", children[0].documentId)
        assertEquals(Document.MIME_TYPE_DIR, children[1].mimeType)

        // Create directory on rclone remote
        val createdDir = provider.createDoc(
            parentDocumentId = "prof-gdrive:/docs",
            mimeType = Document.MIME_TYPE_DIR,
            displayName = "2026",
            ep = entryPoint,
            filesDir = filesDir,
            cacheDir = cacheDir,
        )
        assertEquals("prof-gdrive:/docs/2026", createdDir)
        verify { rcloneClient.mkdir("gdrive", "docs/2026") }

        // Create file on rclone remote
        val createdFile = provider.createDoc(
            parentDocumentId = "prof-gdrive:/docs",
            mimeType = "text/plain",
            displayName = "new.txt",
            ep = entryPoint,
            filesDir = filesDir,
            cacheDir = cacheDir,
        )
        assertEquals("prof-gdrive:/docs/new.txt", createdFile)
        verify { rcloneClient.copyFile(cacheDir.absolutePath, any(), "gdrive", "docs/new.txt") }

        // Rename file on rclone remote
        val renamed = provider.renameDoc(
            documentId = "prof-gdrive:/docs/report.pdf",
            displayName = "final.pdf",
            ep = entryPoint,
            filesDir = filesDir,
        )
        assertEquals("prof-gdrive:/docs/final.pdf", renamed)
        verify { rcloneClient.moveFile("gdrive", "docs/report.pdf", "gdrive", "docs/final.pdf") }

        // Rename directory on rclone remote (uses SyncMode.MOVE)
        every { rcloneClient.startSync(any()) } returns 42L
        every { rcloneClient.getJobStatus(42L) } returns SyncJobStatus(
            jobId = 42L,
            finished = true,
            success = true,
            error = null,
            duration = 0.1,
        )
        val renamedDir = provider.renameDoc(
            documentId = "prof-gdrive:/docs/archive",
            displayName = "archive-2026",
            ep = entryPoint,
            filesDir = filesDir,
        )
        assertEquals("prof-gdrive:/docs/archive-2026", renamedDir)
        verify {
            rcloneClient.startSync(
                SyncConfig(
                    srcFs = "gdrive:docs/archive",
                    dstFs = "gdrive:docs/archive-2026",
                    mode = SyncMode.MOVE,
                ),
            )
        }

        // Delete file vs directory on rclone remote
        provider.deleteDoc("prof-gdrive:/docs/report.pdf", entryPoint, filesDir)
        verify { rcloneClient.deleteFile("gdrive", "docs/report.pdf") }

        provider.deleteDoc("prof-gdrive:/docs/archive", entryPoint, filesDir)
        verify { rcloneClient.deleteDir("gdrive", "docs/archive") }
    }
}
