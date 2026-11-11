package sh.haven.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildGraphHygieneTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("Could not locate Haven repo root")
        }
        return dir
    }

    @Test
    fun `SkKeyData lives in core-security so core-data is decoupled from core-fido`() {
        val root = repoRoot()
        val securitySkKey = File(root, "core/security/src/main/kotlin/sh/haven/core/security/SkKeyData.kt")
        assertTrue("SkKeyData.kt must live in :core:security", securitySkKey.isFile)

        val coreDataBuild = File(root, "core/data/build.gradle.kts").readText()
        assertFalse(":core:data must not depend on :core:fido", coreDataBuild.contains("project(\":core:fido\")"))
        val securityDepCount = Regex("""implementation\(project\(":core:security"\)\)""").findAll(coreDataBuild).count()
        assertEquals(":core:data must declare :core:security once", 1, securityDepCount)
    }

    @Test
    fun `feature-terminal is decoupled from feature-connections and FidoTouchPromptDialog lives in core-ui`() {
        val root = repoRoot()
        val terminalBuild = File(root, "feature/terminal/build.gradle.kts").readText()
        assertFalse(
            ":feature:terminal must not depend on :feature:connections",
            terminalBuild.contains("project(\":feature:connections\")"),
        )
        val uiDialog = File(root, "core/ui/src/main/kotlin/sh/haven/core/ui/components/FidoTouchPromptDialog.kt")
        assertTrue("Shared FidoTouchPromptDialog must live in :core:ui", uiDialog.isFile)
    }

    @Test
    fun `dead module dependencies and hardcoded org-json versions stay pruned`() {
        val root = repoRoot()
        val forbiddenEdges = mapOf(
            "core/openai/build.gradle.kts" to listOf(":core:ssh", ":core:tunnel"),
            "feature/settings/build.gradle.kts" to listOf(":core:ssh", ":feature:tunnel"),
            "feature/rdp/build.gradle.kts" to listOf(":core:knock", ":core:ssh", ":core:mosh", ":core:et"),
            "core/mail/build.gradle.kts" to listOf(":core:data"),
            "core/tunnel/build.gradle.kts" to listOf(":core:security"),
            "feature/imagetools/build.gradle.kts" to listOf(":core:ffmpeg"),
        )
        val presentDeadEdges = mutableListOf<String>()
        for ((relPath, targets) in forbiddenEdges) {
            val text = File(root, relPath).readText()
            for (target in targets) {
                if (text.contains("project(\"$target\")")) {
                    presentDeadEdges += "$relPath -> $target"
                }
            }
        }
        assertEquals("Dead project(...) edges must stay pruned", emptyList<String>(), presentDeadEdges)

        val rootBuild = File(root, "build.gradle.kts").readText()
        assertFalse(
            "Obsolete store/full missingDimensionStrategy must be removed from root build.gradle.kts",
            rootBuild.contains("missingDimensionStrategy(\"store\""),
        )

        val buildFiles = listOf(File(root, "app/build.gradle.kts")) +
            (File(root, "core").listFiles()?.mapNotNull { File(it, "build.gradle.kts").takeIf(File::isFile) }.orEmpty()) +
            (File(root, "feature").listFiles()?.mapNotNull { File(it, "build.gradle.kts").takeIf(File::isFile) }.orEmpty())
        val hardcodedOrgJson = buildFiles.filter { it.readText().contains("\"org.json:json:") }
            .map { it.relativeTo(root).path }
        assertEquals("All modules must reference libs.orgjson instead of hardcoded org.json version", emptyList<String>(), hardcodedOrgJson)
    }

    @Test
    fun `CI, release test gate, binary scan, and prns gating invariants hold`() {
        val root = repoRoot()
        val releaseYml = File(root, ".github/workflows/release.yml").readText()
        assertTrue(
            "release.yml test job must run :app:testArm64FullDebugUnitTest",
            releaseYml.contains(":app:testArm64FullDebugUnitTest"),
        )

        val ciYml = File(root, ".github/workflows/ci.yml").readText()
        assertTrue(
            "ci.yml must run Go unit tests in rclone-android/go",
            ciYml.contains("rclone-android/go") && ciYml.contains("go test"),
        )

        val binaryGate = File(root, "scripts/check-no-committed-binaries.sh").readText()
        assertTrue(
            "check-no-committed-binaries.sh must scan submodules via --recurse-submodules",
            binaryGate.contains("--recurse-submodules"),
        )

        val settingsGradle = File(root, "settings.gradle.kts").readText()
        assertTrue(
            "settings.gradle.kts must gate :core:prns behind -PenablePrns=true",
            Regex("""if\s*\(providers\.gradleProperty\("enablePrns"\)\.orNull\s*==\s*"true"\)\s*\{\s*include\(":core:prns"\)""")
                .containsMatchIn(settingsGradle),
        )
        val appBuild = File(root, "app/build.gradle.kts").readText()
        assertEquals(1, Regex("""project\(":core:prns"\)""").findAll(appBuild).count())
        assertTrue(
            ":app must gate unwired :core:prns behind -PenablePrns=true",
            Regex("""if\s*\(providers\.gradleProperty\("enablePrns"\)\.orNull\s*==\s*"true"\)\s*\{\s*implementation\(project\(":core:prns"\)\)""")
                .containsMatchIn(appBuild),
        )

        val gitignore = File(root, ".gitignore").readText()
        assertNotNull(gitignore)
        assertTrue(".gitignore must ignore scratch/", gitignore.lineSequence().any { it.trim() == "scratch/" })
        assertTrue(".gitignore must ignore .venv/", gitignore.lineSequence().any { it.trim() == ".venv/" })
        assertTrue(".gitignore must ignore .opencode/", gitignore.lineSequence().any { it.trim() == ".opencode/" })
    }
}
