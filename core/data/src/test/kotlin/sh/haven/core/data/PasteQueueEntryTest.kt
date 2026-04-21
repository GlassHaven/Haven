package sh.haven.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.PasteQueueEntry

class PasteQueueEntryTest {

    @Test
    fun defaultsMatchFreshlyInsertedPendingRow() {
        val before = System.currentTimeMillis()
        val row = PasteQueueEntry(
            indexInBatch = 0,
            sourceBackendType = "LOCAL",
            sourceProfileId = "local",
            sourcePath = "/sdcard/clip.mkv",
            sourceName = "clip.mkv",
            sourceSize = 1_234_567L,
            destBackendType = "SFTP",
            destProfileId = "home-server",
            destPath = "/home/me/clip.mkv",
        )
        val after = System.currentTimeMillis()

        assertEquals(0L, row.bytesTransferred)
        assertEquals(PasteQueueEntry.STATUS_PENDING, row.status)
        assertEquals("OVERWRITE", row.conflictAction)
        assertNull(row.lastError)
        assertEquals(false, row.isCut)
        assertEquals(false, row.sourceIsDirectory)
        assertTrue(row.createdAt in before..after)
        assertTrue(row.updatedAt in before..after)
    }

    @Test
    fun statusConstantsAreStable() {
        // These strings are persisted to the DB and used in raw SQL WHERE
        // clauses — changing them would orphan existing rows.
        assertEquals("PENDING", PasteQueueEntry.STATUS_PENDING)
        assertEquals("DONE", PasteQueueEntry.STATUS_DONE)
    }

    @Test
    fun progressRowTracksBytesTransferredAndError() {
        val base = PasteQueueEntry(
            indexInBatch = 3,
            sourceBackendType = "LOCAL",
            sourceProfileId = "local",
            sourcePath = "/sdcard/clip.mkv",
            sourceName = "clip.mkv",
            sourceSize = 1_000_000_000L,
            destBackendType = "SFTP",
            destProfileId = "home-server",
            destPath = "/home/me/clip.mkv",
        )
        val midTransfer = base.copy(bytesTransferred = 500_000_000L, updatedAt = 999L)
        val afterFailure = midTransfer.copy(
            lastError = "Broken pipe",
            updatedAt = 1000L,
        )

        assertEquals(500_000_000L, midTransfer.bytesTransferred)
        assertEquals(PasteQueueEntry.STATUS_PENDING, afterFailure.status)
        assertEquals("Broken pipe", afterFailure.lastError)
        // Resume cursor survives the error — this is what the retry / banner
        // depends on to pick up mid-file instead of restarting at zero.
        assertEquals(500_000_000L, afterFailure.bytesTransferred)
    }
}
