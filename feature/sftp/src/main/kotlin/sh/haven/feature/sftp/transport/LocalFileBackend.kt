package sh.haven.feature.sftp.transport

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.feature.sftp.SftpEntry
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

private const val TAG = "LocalFileBackend"

/**
 * [FileBackend] over the Android device filesystem. The synthetic root
 * `"/"` returns a curated list of storage volumes (Internal, Downloads,
 * removable storage, optional PRoot rootfs, app cache); other paths use
 * standard `java.io.File` listing. An unreadable path silently falls back
 * to the synthetic root rather than surfacing an error — same behaviour
 * the legacy `SftpViewModel.listLocalDirectory` had.
 */
class LocalFileBackend(
    private val appContext: Context,
) : FileBackend {

    override val label: String = "Local"

    override suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        if (path == "/") return@withContext listRoots()
        val dir = File(path)
        val files = dir.listFiles() ?: return@withContext listRoots()
        files.map { f ->
            val counts = countDirectoryChildren(f)
            SftpEntry(
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                size = if (f.isDirectory) 0 else f.length(),
                modifiedTime = f.lastModified() / 1000,
                permissions = buildString {
                    if (f.canRead()) append('r') else append('-')
                    if (f.canWrite()) append('w') else append('-')
                    if (f.canExecute()) append('x') else append('-')
                },
                childCount = counts?.first,
                visibleChildCount = counts?.second,
            )
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        val f = File(path)
        val ok = if (isDirectory) f.deleteRecursively() else f.delete()
        if (!ok) throw java.io.IOException("Could not delete $path")
        scanPaths(path)
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.mkdirs() && !f.isDirectory) {
            throw java.io.IOException("Could not create $path")
        }
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        val ok = File(from).renameTo(File(to))
        if (!ok) throw java.io.IOException("Could not rename $from to $to")
        scanPaths(from, to)
    }

    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        File(path).readBytes()
    }

    override suspend fun writeBytes(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        File(path).writeBytes(data)
        scanPaths(path)
    }

    private fun scanPaths(vararg paths: String) {
        runCatching {
            android.media.MediaScannerConnection.scanFile(appContext, arrayOf(*paths), null, null)
        }
    }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            FileInputStream(File(path)).apply {
                if (offset > 0) channel.position(offset)
            }
        }

    override suspend fun stat(path: String): SftpEntry = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.exists()) throw java.io.FileNotFoundException(path)
        SftpEntry(
            name = f.name,
            path = f.absolutePath,
            isDirectory = f.isDirectory,
            size = if (f.isDirectory) 0 else f.length(),
            modifiedTime = f.lastModified() / 1000,
            permissions = buildString {
                if (f.canRead()) append('r') else append('-')
                if (f.canWrite()) append('w') else append('-')
                if (f.canExecute()) append('x') else append('-')
            },
        )
    }

    private fun countDirectoryChildren(dir: File): Pair<Int, Int>? {
        if (!dir.isDirectory) return null
        val names = dir.list() ?: return null
        var visible = 0
        for (name in names) {
            if (!name.startsWith(".")) visible++
        }
        return names.size to visible
    }

    private fun rootDirEntry(name: String, dir: File): SftpEntry {
        val counts = countDirectoryChildren(dir)
        return SftpEntry(
            name = name,
            path = dir.absolutePath,
            isDirectory = true,
            size = 0,
            modifiedTime = dir.lastModified() / 1000,
            permissions = "",
            childCount = counts?.first,
            visibleChildCount = counts?.second,
        )
    }

    private fun listRoots(): List<SftpEntry> {
        val roots = mutableListOf<SftpEntry>()
        val storage = Environment.getExternalStorageDirectory()
        if (storage.canRead()) {
            roots.add(rootDirEntry("Internal Storage", storage))
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads.canRead()) {
            roots.add(rootDirEntry("Downloads", downloads))
        }
        // Removable storage — USB SD card readers, USB flash drives, and
        // (on some phones) physical microSD slots. StorageManager enumerates
        // all mounted volumes; each one with a readable `.directory`
        // (API 30+) is surfaced as its own root. The primary emulated volume
        // is deliberately skipped because `Internal Storage` above already
        // covers it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = appContext.getSystemService(StorageManager::class.java)
            val primaryPath = storage.absolutePath
            sm?.storageVolumes?.forEach { volume ->
                try {
                    if (volume.isPrimary) return@forEach
                    val state = volume.state
                    if (state != Environment.MEDIA_MOUNTED &&
                        state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                        return@forEach
                    }
                    val dir = volume.directory ?: return@forEach
                    if (dir.absolutePath == primaryPath) return@forEach
                    if (!dir.canRead()) return@forEach
                    val volLabel = volume.getDescription(appContext)
                        ?: dir.name
                        ?: "Removable Storage"
                    roots.add(rootDirEntry(volLabel, dir))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping storage volume: ${e.message}")
                }
            }
        }
        // PRoot rootfs — only surfaced when the rootfs has been
        // installed. Lands the user in /root (the shell's home dir)
        // rather than the rootfs top, since that's where ~/.profile,
        // ~/README.md, ~/.ssh/, and ~/.config/haven/ live. The active
        // distro id is normally `alpine-3.21/`; we also probe the
        // legacy `alpine/` path so installs predating issue #162 that
        // skipped the rename migration still show up here.
        val rootfsRoot = File(appContext.filesDir, "proot/rootfs")
        val prootHome = sequenceOf("alpine-3.21", "alpine")
            .map { File(rootfsRoot, "$it/root") }
            .firstOrNull { it.exists() && it.canRead() }
        if (prootHome != null) {
            roots.add(rootDirEntry("PRoot (~/)", prootHome))
        }
        roots.add(rootDirEntry("App Cache", appContext.cacheDir))
        return roots
    }
}
