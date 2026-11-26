package sh.haven.core.local.proot

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestRecipeRepositoryTest {

    @Test
    fun `imports persists updates and removes custom packs and services across reload`() {
        val dir = Files.createTempDirectory("haven-recipes-repo").toFile()
        try {
            val storeFile = File(dir, "proot/imported-recipes.json")
            val repo = GuestRecipeRepository(storeFile)
            assertEquals(GuestAppCatalog.PACKS.size, repo.allPacks().size)
            assertEquals(GuestServiceCatalog.PRESETS.size, repo.allServicePresets().size)

            val importedJson = """
                {
                  "schemaVersion": 1,
                  "sourceRepo": "https://github.com/GlassHaven/haven-recipes",
                  "packs": [
                    {
                      "id": "krita",
                      "label": "Krita",
                      "description": "Digital painting studio.",
                      "supportedArches": ["aarch64"],
                      "packages": {"APT": ["krita"]},
                      "verifyBinary": "usr/bin/krita",
                      "appLabel": "Krita",
                      "appCommand": "krita"
                    }
                  ],
                  "services": [
                    {
                      "id": "ollama",
                      "label": "Ollama",
                      "description": "Local LLM server on 127.0.0.1:11434.",
                      "port": 11434,
                      "packages": {"PACMAN": ["ollama"]},
                      "verifyBinaries": ["usr/bin/ollama"],
                      "serviceCommand": "OLLAMA_HOST=127.0.0.1:11434 exec ollama serve"
                    }
                  ]
                }
            """.trimIndent()

            val summary = repo.importJson(importedJson)
            assertEquals(listOf("krita"), summary.packIds)
            assertEquals(listOf("ollama"), summary.serviceIds)
            assertTrue(repo.isImportedPack("krita"))
            assertFalse(repo.isImportedPack("qmmp"))
            assertTrue(repo.isImportedService("ollama"))
            assertFalse(repo.isImportedService("mariadb"))

            // Reload from disk in a fresh instance.
            val reloaded = GuestRecipeRepository(storeFile)
            assertNotNull(reloaded.findPackById("krita"))
            assertNotNull(reloaded.findServicePresetById("ollama"))
            assertEquals(GuestAppCatalog.PACKS.size + 1, reloaded.allPacks().size)
            assertEquals(GuestServiceCatalog.PRESETS.size + 1, reloaded.allServicePresets().size)

            // Re-importing the same custom id updates in place instead of duplicating.
            val updatedJson = importedJson.replace("Digital painting studio.", "Updated description.")
            reloaded.importJson(updatedJson)
            assertEquals("Updated description.", reloaded.findPackById("krita")?.description)
            assertEquals(GuestAppCatalog.PACKS.size + 1, reloaded.allPacks().size)

            // Remove single imported entries.
            assertTrue(reloaded.removeImported("krita"))
            assertNull(reloaded.findPackById("krita"))
            assertNotNull(reloaded.findServicePresetById("ollama"))

            reloaded.clearImported()
            assertNull(reloaded.findServicePresetById("ollama"))
            assertFalse(storeFile.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejects imported recipe that shadows a built-in pack or service id`() {
        val dir = Files.createTempDirectory("haven-recipes-shadow").toFile()
        try {
            val repo = GuestRecipeRepository(File(dir, "imported-recipes.json"))
            val shadowPack = """
                {
                  "schemaVersion": 1,
                  "packs": [
                    {
                      "id": "qmmp",
                      "label": "Fake Qmmp",
                      "description": "Attempts to shadow built-in qmmp.",
                      "packages": {"APT": ["qmmp"]},
                      "verifyBinary": "usr/bin/qmmp",
                      "appLabel": "Qmmp",
                      "appCommand": "curl evil"
                    }
                  ],
                  "services": []
                }
            """.trimIndent()
            assertThrows(RecipeValidationException::class.java) {
                repo.importJson(shadowPack)
            }

            val shadowService = """
                {
                  "schemaVersion": 1,
                  "packs": [],
                  "services": [
                    {
                      "id": "mariadb",
                      "label": "Fake MariaDB",
                      "description": "Attempts to shadow built-in mariadb.",
                      "port": 3306,
                      "packages": {"APT": ["mariadb-server"]},
                      "verifyBinaries": ["usr/sbin/mariadbd"],
                      "serviceCommand": "exec mariadbd"
                    }
                  ]
                }
            """.trimIndent()
            assertThrows(RecipeValidationException::class.java) {
                repo.importJson(shadowService)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `verifyManifestBytes enforces size cap and optional sha256 pin`() {
        val body = """{"schemaVersion": 1, "packs": [], "services": []}""".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(body)
            .joinToString("") { "%02x".format(it) }

        val text = GuestRecipeRepository.decodeAndVerifyManifestBytes(body, expectedSha256 = digest.uppercase())
        assertEquals(1, GuestRecipeManifest.parse(text).schemaVersion)

        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeRepository.decodeAndVerifyManifestBytes(body, expectedSha256 = "0".repeat(64))
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeRepository.decodeAndVerifyManifestBytes(body, expectedSha256 = "not-a-sha")
        }
    }
}
