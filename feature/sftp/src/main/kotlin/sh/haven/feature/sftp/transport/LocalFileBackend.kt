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
    }

    override suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        val f = File(path)
        val ok = if (isDirectory) f.deleteRecursively() else f.delete()
        if (!ok) throw java.io.IOException("Could not delete $path")
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
    }

    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        File(path).readBytes()
    }

    override suspend fun writeBytes(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        File(path).writeBytes(data)
    }

    private fun listRoots(): List<SftpEntry> {
        val roots = mutableListOf<SftpEntry>()
        val storage = Environment.getExternalStorageDirectory()
        if (storage.canRead()) {
            roots.add(SftpEntry("Internal Storage", storage.absolutePath, true, 0, storage.lastModified() / 1000, ""))
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads.canRead()) {
            roots.add(SftpEntry("Downloads", downloads.absolutePath, true, 0, downloads.lastModified() / 1000, ""))
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
                    roots.add(
                        SftpEntry(
                            name = volLabel,
                            path = dir.absolutePath,
                            isDirectory = true,
                            size = 0,
                            modifiedTime = dir.lastModified() / 1000,
                            permissions = "",
                        ),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping storage volume: ${e.message}")
                }
            }
        }
        // PRoot Alpine rootfs — only surfaced when the rootfs has been
        // installed. Lands the user in /root (the shell's home dir)
        // rather than the rootfs top, since that's where ~/.profile,
        // ~/README.md, ~/.ssh/, and ~/.config/haven/ live.
        val prootHome = File(appContext.filesDir, "proot/rootfs/alpine/root")
        if (prootHome.exists() && prootHome.canRead()) {
            roots.add(
                SftpEntry(
                    "PRoot (~/)", prootHome.absolutePath, true, 0,
                    prootHome.lastModified() / 1000, "",
                ),
            )
        }
        roots.add(SftpEntry("App Cache", appContext.cacheDir.absolutePath, true, 0, appContext.cacheDir.lastModified() / 1000, ""))
        return roots
    }
}
