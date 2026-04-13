package sh.haven.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * H2 regression: `app/src/main/res/xml/file_paths.xml` must not declare
 * `<root-path>`. The previous `<root-path name="root" path="."/>` entry
 * let any URI Haven constructed via `FileProvider.getUriForFile` target
 * any file the app process could read — including the Room database
 * with encrypted credentials at `/data/data/sh.haven.app/databases/`.
 *
 * The test reads the source XML directly (no Robolectric needed). It
 * fails if anyone re-introduces `<root-path>` or widens `<files-path>`
 * to the entire `filesDir`, both of which would re-open the credential
 * extraction vector.
 */
class FileProviderPathsTest {

    private val filePathsXml: String by lazy {
        // Resolve relative to the gradle module rootDir so the test runs
        // both from the IDE and from `./gradlew`.
        val candidates = listOf(
            File("src/main/res/xml/file_paths.xml"),
            File("app/src/main/res/xml/file_paths.xml"),
        )
        val found = candidates.firstOrNull { it.exists() }
            ?: error("file_paths.xml not found, looked in: ${candidates.joinToString { it.absolutePath }}")
        // Strip XML comments before scanning so the rationale comment in
        // the file (which legitimately mentions <root-path>) doesn't trip
        // these regression checks.
        found.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    @Test
    fun `file_paths xml exists and is non-empty`() {
        assertTrue(filePathsXml.isNotBlank())
        assertTrue(filePathsXml.contains("<paths"))
    }

    @Test
    fun `file_paths xml does NOT declare root-path`() {
        // <root-path> grants the FileProvider access to the entire
        // filesystem visible to the app process, which includes the
        // Room database directory and the Tink credential keystore.
        // It must never be reintroduced.
        assertFalse(
            "H2 regression: <root-path> reintroduces the credential DB exposure",
            filePathsXml.contains("<root-path", ignoreCase = true),
        )
    }

    @Test
    fun `files-path declarations are narrower than the entire filesDir`() {
        // A bare `<files-path name=".." path="."/>` would expose
        // /data/data/sh.haven.app/files/, which contains the Tink master
        // key reference, DataStore preferences, and the rclone config.
        // Allow only narrowed sub-path declarations like path="proot/".
        val filesPathRegex = Regex("""<files-path[^>]*\bpath\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        for (match in filesPathRegex.findAll(filePathsXml)) {
            val path = match.groupValues[1]
            assertFalse(
                "files-path path=\"$path\" is too broad — must be a sub-directory like proot/",
                path == "." || path == "" || path == "/",
            )
        }
    }

    @Test
    fun `external-path is permitted (covers user storage and SAF roots)`() {
        // Sanity check: our local-file-browser still works against external storage.
        assertTrue(
            "external-path is intentionally retained for the local file browser",
            filePathsXml.contains("<external-path", ignoreCase = true),
        )
    }

    @Test
    fun `cache-path is retained for staging files (APK, playlists)`() {
        assertTrue(
            "cache-path is required for FileProvider-backed APK install and HLS playlist sharing",
            filePathsXml.contains("<cache-path", ignoreCase = true),
        )
    }
}
