package sh.haven.feature.sftp.root

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 *Privileged UID-0 filesystem RPC worker (#799).
 *
 * Launched on explicit user action via:
 *   su -mm -c "CLASSPATH='<base.apk>' exec /system/bin/app_process /system/bin sh.haven.feature.sftp.root.RootFsDaemon"
 * (falling back to plain `su -c` when the root manager rejects `-mm`).
 *
 * Speaks a length-prefixed binary protocol over its raw stdin/stdout file
 * descriptors after emitting the 8-byte [MAGIC] handshake banner so any
 * shell/Magisk wrapper text printed prior to `exec` is skipped cleanly by
 * [RootFsClient]. Kept free of Android `Context`/`Looper` initialisation so
 * it runs identically inside bare Android `app_process` and standard JVM
 * unit-test child processes.
 */
object RootFsDaemon {

    internal val MAGIC: ByteArray = "HVNROOT1".toByteArray(Charsets.US_ASCII)

    internal const val OP_PING = 1
    internal const val OP_LIST = 2
    internal const val OP_STAT = 3
    internal const val OP_READ_RANGE = 4
    internal const val OP_WRITE_OPEN = 5
    internal const val OP_WRITE_CHUNK = 6
    internal const val OP_MKDIR = 7
    internal const val OP_DELETE = 8
    internal const val OP_RENAME = 9
    internal const val OP_CHMOD = 10
    internal const val OP_CHOWN = 11
    internal const val OP_QUIT = 12

    internal const val STATUS_OK: Byte = 0
    internal const val STATUS_NOT_FOUND: Byte = 1
    internal const val STATUS_IO_ERROR: Byte = 2

    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000
    private const val S_IFDIR = 0x4000
    private const val S_ISUID = 0x0800
    private const val S_ISGID = 0x0400
    private const val S_ISVTX = 0x0200

    /** Well-known Android / Linux user and group names for `chown name:group`. */
    private val ANDROID_AIDS = mapOf(
        "root" to 0,
        "system" to 1000,
        "radio" to 1001,
        "bluetooth" to 1002,
        "graphics" to 1003,
        "input" to 1004,
        "audio" to 1005,
        "camera" to 1006,
        "log" to 1007,
        "compass" to 1008,
        "mount" to 1009,
        "wifi" to 1010,
        "adb" to 1011,
        "install" to 1012,
        "media" to 1013,
        "dhcp" to 1014,
        "sdcard_rw" to 1015,
        "vpn" to 1016,
        "keystore" to 1017,
        "usb" to 1018,
        "drm" to 1019,
        "mdnsr" to 1020,
        "gps" to 1021,
        "media_rw" to 1023,
        "mtp" to 1024,
        "sdcard_r" to 1028,
        "shell" to 2000,
        "cache" to 2001,
        "diag" to 2002,
        "everybody" to 9997,
        "misc" to 9998,
        "nobody" to 9999,
    )

    internal data class RawEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedTimeSec: Long,
        val permissions: String,
        val owner: String,
        val group: String,
        val childCount: Int?,
        val visibleChildCount: Int?,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val rawOut = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out), 64 * 1024))
        val rawIn = DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`), 64 * 1024))
        // Redirect System.out to stderr so incidental library writes cannot corrupt the RPC stream.
        System.setOut(System.err)

        // Test hook: emit optional pre-handshake noise when requested by unit tests.
        if (args.isNotEmpty() && args[0].startsWith("--banner=")) {
            val banner = args[0].removePrefix("--banner=").toByteArray(Charsets.UTF_8)
            rawOut.write(banner)
        }

        rawOut.write(MAGIC)
        rawOut.writeInt(currentUid())
        rawOut.flush()

        while (true) {
            val op = try {
                rawIn.read()
            } catch (_: IOException) {
                -1
            }
            if (op < 0 || op == OP_QUIT) break
            try {
                handleOp(op, rawIn, rawOut)
            } catch (e: FileNotFoundException) {
                rawOut.writeByte(STATUS_NOT_FOUND.toInt())
                writeString(rawOut, e.message ?: "Not found")
                rawOut.flush()
            } catch (t: Throwable) {
                rawOut.writeByte(STATUS_IO_ERROR.toInt())
                writeString(rawOut, t.message ?: t.javaClass.simpleName)
                rawOut.flush()
            }
        }
    }

    private fun handleOp(op: Int, rawIn: DataInputStream, rawOut: DataOutputStream) {
        when (op) {
            OP_PING -> {
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.writeInt(currentUid())
                rawOut.flush()
            }
            OP_LIST -> {
                val rawPath = readString(rawIn)
                val normPath = rawPath.ifBlank { "/" }
                val dir = File(normPath)
                if (!dir.exists() && !Files.isSymbolicLink(dir.toPath())) {
                    throw FileNotFoundException(normPath)
                }
                val children = dir.listFiles()
                    ?: throw IOException("Cannot list directory: $normPath")
                val includeCounts = !isVirtualKernelDir(normPath)
                val entries = children.map { child -> buildRawEntry(child, includeCounts) }
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.writeInt(entries.size)
                for (e in entries) writeRawEntry(rawOut, e)
                rawOut.flush()
            }
            OP_STAT -> {
                val path = readString(rawIn)
                val f = File(path)
                if (!f.exists() && !Files.isSymbolicLink(f.toPath())) {
                    throw FileNotFoundException(path)
                }
                val entry = buildRawEntry(f, includeChildCounts = false)
                rawOut.writeByte(STATUS_OK.toInt())
                writeRawEntry(rawOut, entry)
                rawOut.flush()
            }
            OP_READ_RANGE -> {
                val path = readString(rawIn)
                val offset = rawIn.readLong()
                val maxBytes = rawIn.readInt().coerceIn(0, 1024 * 1024)
                val f = File(path)
                if (!f.exists()) throw FileNotFoundException(path)
                val buf = ByteArray(maxBytes)
                val n = RandomAccessFile(f, "r").use { raf ->
                    if (offset > 0) raf.seek(offset)
                    if (maxBytes == 0) 0 else raf.read(buf)
                }
                val actual = if (n < 0) 0 else n
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.writeInt(actual)
                if (actual > 0) rawOut.write(buf, 0, actual)
                rawOut.flush()
            }
            OP_WRITE_OPEN -> {
                val path = readString(rawIn)
                val append = rawIn.readBoolean()
                val f = File(path)
                if (!append) {
                    RandomAccessFile(f, "rw").use { raf -> raf.setLength(0L) }
                } else if (!f.exists()) {
                    RandomAccessFile(f, "rw").close()
                }
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.flush()
            }
            OP_WRITE_CHUNK -> {
                val path = readString(rawIn)
                val offset = rawIn.readLong()
                val len = rawIn.readInt()
                if (len < 0 || len > 4 * 1024 * 1024) {
                    throw IOException("Invalid chunk length: $len")
                }
                val buf = ByteArray(len)
                rawIn.readFully(buf)
                RandomAccessFile(File(path), "rw").use { raf ->
                    raf.seek(offset)
                    if (len > 0) raf.write(buf)
                }
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.flush()
            }
            OP_MKDIR -> {
                val path = readString(rawIn)
                val f = File(path)
                if (!f.mkdirs() && !f.isDirectory) {
                    throw IOException("Could not create directory: $path")
                }
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.flush()
            }
            OP_DELETE -> {
                val path = readString(rawIn)
                deleteSymlinkSafe(File(path))
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.flush()
            }
            OP_RENAME -> {
                val from = readString(rawIn)
                val to = readString(rawIn)
                renameAcrossMounts(File(from), File(to))
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.flush()
            }
            OP_CHMOD -> {
                val path = readString(rawIn)
                val mode = rawIn.readInt() and 0xFFF
                applyChmod(path, mode)
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.flush()
            }
            OP_CHOWN -> {
                val path = readString(rawIn)
                val ownerSpec = readString(rawIn)
                applyChown(path, ownerSpec)
                rawOut.writeByte(STATUS_OK.toInt())
                rawOut.flush()
            }
            else -> throw IOException("Unknown root RPC op: $op")
        }
    }

    /**
     * Delete [target] without ever recursing through a symbolic link into its
     * target directory — critical as UID 0 where `/sdcard` or `/etc` are symlinks.
     */
    private fun deleteSymlinkSafe(target: File) {
        val nioPath = target.toPath()
        if (Files.isSymbolicLink(nioPath)) {
            Files.delete(nioPath)
            return
        }
        if (!Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        if (Files.isDirectory(nioPath, LinkOption.NOFOLLOW_LINKS)) {
            val kids = target.listFiles()
            if (kids != null) {
                for (kid in kids) deleteSymlinkSafe(kid)
            }
        }
        Files.delete(nioPath)
    }

    private fun renameAcrossMounts(src: File, dst: File) {
        val srcPath = src.toPath()
        val dstPath = dst.toPath()
        try {
            Files.move(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            // Cross-mount move (EXDEV, e.g. /sdcard -> /data/local/tmp): copy + delete.
        }
        copyRecursivelyNoFollow(src, dst)
        deleteSymlinkSafe(src)
    }

    private fun copyRecursivelyNoFollow(src: File, dst: File) {
        val srcPath = src.toPath()
        val dstPath = dst.toPath()
        when {
            Files.isSymbolicLink(srcPath) -> {
                val linkTarget = Files.readSymbolicLink(srcPath)
                Files.deleteIfExists(dstPath)
                Files.createSymbolicLink(dstPath, linkTarget)
            }
            Files.isDirectory(srcPath, LinkOption.NOFOLLOW_LINKS) -> {
                if (!dst.exists() && !dst.mkdirs()) {
                    throw IOException("Could not create directory: ${dst.path}")
                }
                val kids = src.listFiles() ?: throw IOException("Cannot read directory: ${src.path}")
                for (kid in kids) {
                    copyRecursivelyNoFollow(kid, File(dst, kid.name))
                }
            }
            else -> {
                Files.copy(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    private fun buildRawEntry(f: File, includeChildCounts: Boolean): RawEntry {
        val nioPath = f.toPath()
        val isLink = Files.isSymbolicLink(nioPath)
        val isDir = f.isDirectory
        var size = if (isDir) 0L else f.length()
        var mtimeSec = f.lastModified() / 1000L
        var permStr = fallbackPermissions(f, isDir, isLink)
        var owner = ""
        var group = ""

        try {
            val attrs = Files.readAttributes(
                nioPath,
                "unix:mode,uid,gid,size,lastModifiedTime",
                LinkOption.NOFOLLOW_LINKS,
            )
            val mode = (attrs["mode"] as? Number)?.toInt()
            if (mode != null) {
                permStr = formatUnixMode(mode, isDir, isLink)
            }
            val uid = (attrs["uid"] as? Number)?.toInt()
            val gid = (attrs["gid"] as? Number)?.toInt()
            if (uid != null) owner = uidToName(uid)
            if (gid != null) group = gidToName(gid)
            if (!isDir && !isLink) {
                (attrs["size"] as? Number)?.toLong()?.let { size = it }
            }
            (attrs["lastModifiedTime"] as? java.nio.file.attribute.FileTime)?.let {
                mtimeSec = it.toMillis() / 1000L
            }
        } catch (_: Throwable) {
            // Non-Unix FS or restricted attribute view: fallback values stand.
        }

        val counts = if (includeChildCounts && isDir && !isVirtualKernelDir(f.path)) {
            f.list()?.let { names ->
                var visible = 0
                for (n in names) if (!n.startsWith(".")) visible++
                names.size to visible
            }
        } else {
            null
        }

        val normalizedPath = if (f.path == "/") "/" else f.path.trimEnd('/')
        return RawEntry(
            name = if (normalizedPath == "/") "/" else f.name,
            path = normalizedPath,
            isDirectory = isDir,
            size = size,
            modifiedTimeSec = mtimeSec,
            permissions = permStr,
            owner = owner,
            group = group,
            childCount = counts?.first,
            visibleChildCount = counts?.second,
        )
    }

    internal fun formatUnixMode(mode: Int, isDirFallback: Boolean, isLinkFallback: Boolean): String {
        val fmt = mode and S_IFMT
        val typeChar = when {
            fmt == S_IFLNK || isLinkFallback -> 'l'
            fmt == S_IFDIR || isDirFallback -> 'd'
            else -> '-'
        }
        val ur = if ((mode and 0x100) != 0) 'r' else '-'
        val uw = if ((mode and 0x080) != 0) 'w' else '-'
        val ux = when {
            (mode and S_ISUID) != 0 -> if ((mode and 0x040) != 0) 's' else 'S'
            (mode and 0x040) != 0 -> 'x'
            else -> '-'
        }
        val gr = if ((mode and 0x020) != 0) 'r' else '-'
        val gw = if ((mode and 0x010) != 0) 'w' else '-'
        val gx = when {
            (mode and S_ISGID) != 0 -> if ((mode and 0x008) != 0) 's' else 'S'
            (mode and 0x008) != 0 -> 'x'
            else -> '-'
        }
        val or = if ((mode and 0x004) != 0) 'r' else '-'
        val ow = if ((mode and 0x002) != 0) 'w' else '-'
        val ox = when {
            (mode and S_ISVTX) != 0 -> if ((mode and 0x001) != 0) 't' else 'T'
            (mode and 0x001) != 0 -> 'x'
            else -> '-'
        }
        return String(charArrayOf(typeChar, ur, uw, ux, gr, gw, gx, or, ow, ox))
    }

    private fun fallbackPermissions(f: File, isDir: Boolean, isLink: Boolean): String {
        val t = when {
            isLink -> 'l'
            isDir -> 'd'
            else -> '-'
        }
        val r = if (f.canRead()) 'r' else '-'
        val w = if (f.canWrite()) 'w' else '-'
        val x = if (f.canExecute()) 'x' else '-'
        return "$t$r$w$x------"
    }

    private val IS_ANDROID_RUNTIME: Boolean =
        System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true

    private fun applyChmod(path: String, mode: Int) {
        if (IS_ANDROID_RUNTIME) {
            try {
                val osClass = Class.forName("android.system.Os")
                val method = osClass.getMethod("chmod", String::class.java, Int::class.javaPrimitiveType)
                method.invoke(null, path, mode)
                return
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw IOException(e.targetException?.message ?: "chmod failed", e.targetException)
            }
        }
        val nioPath = File(path).toPath()
        try {
            Files.setAttribute(nioPath, "unix:mode", mode, LinkOption.NOFOLLOW_LINKS)
        } catch (t: Throwable) {
            throw IOException("chmod failed for $path: ${t.message}", t)
        }
    }

    private fun applyChown(path: String, ownerSpec: String) {
        val trimmed = ownerSpec.trim()
        val colon = trimmed.indexOf(':')
        val userPart = if (colon >= 0) trimmed.substring(0, colon) else trimmed
        val groupPart = if (colon >= 0) trimmed.substring(colon + 1) else ""

        val uid = if (userPart.isEmpty()) -1 else resolveNameOrId(userPart, "/etc/passwd")
        val gid = if (groupPart.isEmpty()) -1 else resolveNameOrId(groupPart, "/etc/group")

        if (IS_ANDROID_RUNTIME) {
            try {
                val osClass = Class.forName("android.system.Os")
                val method = osClass.getMethod(
                    "lchown",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                method.invoke(null, path, uid, gid)
                return
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw IOException(e.targetException?.message ?: "chown failed", e.targetException)
            }
        }

        val nioPath = File(path).toPath()
        try {
            if (uid >= 0) Files.setAttribute(nioPath, "unix:uid", uid, LinkOption.NOFOLLOW_LINKS)
            if (gid >= 0) Files.setAttribute(nioPath, "unix:gid", gid, LinkOption.NOFOLLOW_LINKS)
        } catch (t: Throwable) {
            throw IOException("chown failed for $path: ${t.message}", t)
        }
    }

    internal fun resolveNameOrId(token: String, dbPath: String): Int {
        token.toIntOrNull()?.let { return it }
        ANDROID_AIDS[token]?.let { return it }
        val db = File(dbPath)
        if (db.isFile && db.canRead()) {
            db.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val parts = line.split(':')
                    if (parts.size >= 3 && parts[0] == token) {
                        parts[2].toIntOrNull()?.let { return it }
                    }
                }
            }
        }
        throw IOException("Unknown user/group: $token")
    }

    private fun uidToName(uid: Int): String =
        ANDROID_AIDS.entries.firstOrNull { it.value == uid }?.key ?: uid.toString()

    private fun gidToName(gid: Int): String =
        ANDROID_AIDS.entries.firstOrNull { it.value == gid }?.key ?: gid.toString()

    private fun currentUid(): Int {
        return try {
            val osClass = Class.forName("android.system.Os")
            (osClass.getMethod("getuid").invoke(null) as? Int) ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun isVirtualKernelDir(path: String): Boolean =
        path == "/proc" || path.startsWith("/proc/") ||
            path == "/sys" || path.startsWith("/sys/") ||
            path == "/dev" || path.startsWith("/dev/")

    internal fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        if (bytes.isNotEmpty()) out.write(bytes)
    }

    internal fun readString(input: DataInputStream): String {
        val len = input.readInt()
        if (len < 0 || len > 1024 * 1024) throw IOException("Invalid string length: $len")
        if (len == 0) return ""
        val buf = ByteArray(len)
        input.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    internal fun writeRawEntry(out: DataOutputStream, e: RawEntry) {
        writeString(out, e.name)
        writeString(out, e.path)
        out.writeBoolean(e.isDirectory)
        out.writeLong(e.size)
        out.writeLong(e.modifiedTimeSec)
        writeString(out, e.permissions)
        writeString(out, e.owner)
        writeString(out, e.group)
        out.writeInt(e.childCount ?: -1)
        out.writeInt(e.visibleChildCount ?: -1)
    }

    internal fun readRawEntry(input: DataInputStream): RawEntry {
        val name = readString(input)
        val path = readString(input)
        val isDir = input.readBoolean()
        val size = input.readLong()
        val mtime = input.readLong()
        val perms = readString(input)
        val owner = readString(input)
        val group = readString(input)
        val childCount = input.readInt().takeIf { it >= 0 }
        val visibleCount = input.readInt().takeIf { it >= 0 }
        return RawEntry(
            name = name,
            path = path,
            isDirectory = isDir,
            size = size,
            modifiedTimeSec = mtime,
            permissions = perms,
            owner = owner,
            group = group,
            childCount = childCount,
            visibleChildCount = visibleCount,
        )
    }
}
