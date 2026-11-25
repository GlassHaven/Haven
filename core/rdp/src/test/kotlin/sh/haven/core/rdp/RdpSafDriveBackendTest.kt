package sh.haven.core.rdp

import android.provider.DocumentsContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.rdp.RdpDriveStatus

class RdpSafDriveBackendTest {

    private class FakeSafTreeOps(
        override val rootDisplayName: String = "SharedDocs",
    ) : SafTreeOps {
        override val rootDocId: String = "doc:root"

        private var nextId = 1
        private val nodes = LinkedHashMap<String, SafDocNode>()
        private val children = LinkedHashMap<String, MutableList<String>>()
        private val contents = LinkedHashMap<String, ByteArray>()
        val closedDocIds = mutableListOf<String>()

        init {
            nodes[rootDocId] = SafDocNode(
                docId = rootDocId,
                name = rootDisplayName,
                isDir = true,
                size = 0L,
                modifiedMs = 1_700_000_000_000L,
                flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
            )
            children[rootDocId] = mutableListOf()
        }

        override fun queryDoc(docId: String): SafDocNode? {
            val node = nodes[docId] ?: return null
            val bytes = contents[docId]
            return if (!node.isDir && bytes != null) {
                node.copy(size = bytes.size.toLong())
            } else {
                node
            }
        }

        override fun queryChildren(parentDocId: String): List<SafDocNode>? {
            val parent = nodes[parentDocId] ?: return null
            if (!parent.isDir) return null
            return children[parentDocId].orEmpty().mapNotNull { queryDoc(it) }
        }

        override fun createDoc(parentDocId: String, mimeType: String, displayName: String): String? {
            val list = children[parentDocId] ?: return null
            val id = "doc:${nextId++}"
            val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            nodes[id] = SafDocNode(
                docId = id,
                name = displayName,
                isDir = isDir,
                size = 0L,
                modifiedMs = 1_700_000_005_000L,
                flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
            )
            if (isDir) {
                children[id] = mutableListOf()
            } else {
                contents[id] = ByteArray(0)
            }
            list.add(id)
            return id
        }

        override fun deleteDoc(docId: String): Boolean {
            if (docId == rootDocId || !nodes.containsKey(docId)) return false
            nodes.remove(docId)
            contents.remove(docId)
            children.remove(docId)
            children.values.forEach { it.remove(docId) }
            return true
        }

        override fun renameDoc(docId: String, newDisplayName: String): String? {
            val existing = nodes[docId] ?: return null
            nodes[docId] = existing.copy(name = newDisplayName)
            return docId
        }

        override fun moveDoc(
            docId: String,
            sourceParentDocId: String,
            targetParentDocId: String,
        ): String? {
            val src = children[sourceParentDocId] ?: return null
            val dst = children[targetParentDocId] ?: return null
            if (!src.remove(docId)) return null
            dst.add(docId)
            return docId
        }

        override fun readAt(docId: String, offset: Long, length: Int): ByteArray {
            val bytes = contents[docId] ?: throw java.io.FileNotFoundException(docId)
            val start = offset.toInt().coerceIn(0, bytes.size)
            val end = (start + length).coerceIn(start, bytes.size)
            return bytes.copyOfRange(start, end)
        }

        override fun writeAt(docId: String, offset: Long, data: ByteArray): Int {
            val current = contents[docId] ?: throw java.io.FileNotFoundException(docId)
            val end = offset.toInt() + data.size
            val grown = if (current.size < end) current.copyOf(end) else current
            System.arraycopy(data, 0, grown, offset.toInt(), data.size)
            contents[docId] = grown
            return data.size
        }

        override fun truncate(docId: String, size: Long) {
            val current = contents[docId] ?: throw java.io.FileNotFoundException(docId)
            contents[docId] = current.copyOf(size.toInt())
        }

        override fun closeCached(docId: String) {
            closedDocIds.add(docId)
        }

        override fun close() {
            closedDocIds.add("ALL")
        }
    }

    @Test
    fun `create write read truncate and delete file roundtrip`() {
        val fake = FakeSafTreeOps("Downloads")
        val backend = RdpSafDriveBackend(fake)
        assertEquals("Downloads", backend.shareName)

        assertEquals(RdpDriveStatus.OK, backend.mkdir("/reports"))
        assertEquals(RdpDriveStatus.OK, backend.createFile("/reports/q4.txt", overwrite = true))

        val payload = "Hello MS-RDPEFS over SAF!".encodeToByteArray()
        val writeRes = backend.writeAt("/reports/q4.txt", 0UL, payload)
        assertEquals(RdpDriveStatus.OK, writeRes.status)
        assertEquals(payload.size.toUInt(), writeRes.written)

        val stat = backend.stat("/reports/q4.txt")
        assertNotNull(stat)
        assertEquals("q4.txt", stat!!.name)
        assertEquals(payload.size.toULong(), stat.size)

        val readRes = backend.readAt("/reports/q4.txt", 6UL, 9u)
        assertEquals(RdpDriveStatus.OK, readRes.status)
        assertArrayEquals("MS-RDPEFS".encodeToByteArray(), readRes.data)

        assertEquals(RdpDriveStatus.OK, backend.truncate("/reports/q4.txt", 5UL))
        assertEquals(5UL, backend.stat("/reports/q4.txt")!!.size)

        // Non-empty directory refuses deletePath(isDir=true) with DIRECTORY_NOT_EMPTY.
        assertEquals(
            RdpDriveStatus.DIRECTORY_NOT_EMPTY,
            backend.deletePath("/reports", isDir = true),
        )

        backend.closePath("/reports/q4.txt")
        assertTrue(fake.closedDocIds.isNotEmpty())

        assertEquals(RdpDriveStatus.OK, backend.deletePath("/reports/q4.txt", isDir = false))
        assertNull(backend.stat("/reports/q4.txt"))
        assertEquals(RdpDriveStatus.OK, backend.deletePath("/reports", isDir = true))
    }

    @Test
    fun `cross folder rename moves and updates cached paths`() {
        val backend = RdpSafDriveBackend(FakeSafTreeOps())
        assertEquals(RdpDriveStatus.OK, backend.mkdir("/inbox"))
        assertEquals(RdpDriveStatus.OK, backend.mkdir("/archive"))
        assertEquals(RdpDriveStatus.OK, backend.createFile("/inbox/draft.md", overwrite = true))
        backend.writeAt("/inbox/draft.md", 0UL, "v1".encodeToByteArray())

        assertEquals(
            RdpDriveStatus.OK,
            backend.renamePath("/inbox/draft.md", "/archive/final.md", replaceIfExists = false),
        )
        assertNull(backend.stat("/inbox/draft.md"))
        val moved = backend.stat("/archive/final.md")
        assertNotNull(moved)
        assertEquals(2UL, moved!!.size)

        // Collision check when replaceIfExists == false vs true.
        assertEquals(RdpDriveStatus.OK, backend.createFile("/inbox/next.md", overwrite = true))
        backend.writeAt("/inbox/next.md", 0UL, "v2-updated".encodeToByteArray())
        assertEquals(
            RdpDriveStatus.ALREADY_EXISTS,
            backend.renamePath("/inbox/next.md", "/archive/final.md", replaceIfExists = false),
        )
        assertEquals(
            RdpDriveStatus.OK,
            backend.renamePath("/inbox/next.md", "/archive/final.md", replaceIfExists = true),
        )
        assertEquals(10UL, backend.stat("/archive/final.md")!!.size)
    }
}
