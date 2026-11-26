package sh.haven.core.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level regression contract ensuring credential, secret, PIN, token, and private-key
 * Compose text inputs never bypass [PasswordField] / [PrivateKeyboard], and that remote-desktop
 * and terminal compose overlays retain [PrivateKeyboard].
 */
class PrivateKeyboardContractTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun productionKotlinFiles(): List<File> {
        val root = repoRoot()
        return listOf("app", "core", "feature")
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { tree ->
                tree.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filterNot { it.path.contains("/build/") }
                    .filterNot { it.path.contains("/test/") || it.path.contains("/androidTest/") }
                    .toList()
            }
    }

    /**
     * Extracts balanced `( ... )` call argument text starting at the opening `(` index.
     */
    private fun extractCallArgs(src: String, openParenIndex: Int): String {
        var depth = 0
        var i = openParenIndex
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(openParenIndex, i + 1)
                }
            }
            i++
        }
        return src.substring(openParenIndex)
    }

    private fun isInsidePrivateKeyboardBlock(src: String, callOffset: Int): Boolean {
        // Walk backwards ~450 chars before the text-field call for `PrivateKeyboard {`.
        val windowStart = (callOffset - 450).coerceAtLeast(0)
        val prefix = src.substring(windowStart, callOffset)
        return Regex("""\bPrivateKeyboard\s*\{""").containsMatchIn(prefix)
    }

    @Test
    fun `raw OutlinedTextField and TextField never bind secrets or PasswordVisualTransformation without PrivateKeyboard`() {
        val rawFieldCall = Regex("""\b(OutlinedTextField|BasicTextField|TextField)\s*\(""")
        val secretValueBinding = Regex(
            """\bvalue\s*=\s*(?:form\.)?(?:""" +
                """[a-zA-Z0-9_]*(?:password|Password|passphrase|Passphrase|secret|Secret|privateKey|PrivateKey)""" +
                """|pin|token|authKey|setupKey|spaKey|spaHmacKey""" +
                """)\b"""
        )

        val offenders = mutableListOf<String>()

        for (file in productionKotlinFiles()) {
            if (file.name == "PasswordField.kt") continue
            val src = file.readText()
            for (match in rawFieldCall.findAll(src)) {
                val openParen = match.range.last
                val callBody = extractCallArgs(src, openParen)
                val lineNo = src.substring(0, match.range.first).count { it == '\n' } + 1

                if (secretValueBinding.containsMatchIn(callBody) && !isInsidePrivateKeyboardBlock(src, match.range.first)) {
                    val valueMatch = secretValueBinding.find(callBody)?.value?.trim().orEmpty()
                    offenders += "${file.relativeTo(repoRoot()).path}:$lineNo: ${match.groupValues[1]} binds `$valueMatch` without PasswordField or PrivateKeyboard"
                }
                if (callBody.contains("PasswordVisualTransformation") && !isInsidePrivateKeyboardBlock(src, match.range.first)) {
                    offenders += "${file.relativeTo(repoRoot()).path}:$lineNo: ${match.groupValues[1]} uses PasswordVisualTransformation without PasswordField or PrivateKeyboard"
                }
            }
        }

        assertTrue(
            "Found secret/masked Compose text inputs missing PasswordField / PrivateKeyboard:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `remote desktop and terminal compose overlays wrap their text input in PrivateKeyboard`() {
        val root = repoRoot()
        val requiredOverlays = listOf(
            "core/ui/src/main/kotlin/sh/haven/core/ui/RemoteDesktopViewport.kt",
            "feature/vnc/src/main/kotlin/sh/haven/feature/vnc/VncComposeBar.kt",
            "feature/terminal/src/main/kotlin/sh/haven/feature/terminal/FloatingTextInputDialog.kt",
        )
        for (relPath in requiredOverlays) {
            val file = File(root, relPath)
            assertTrue("Expected overlay file to exist: $relPath", file.isFile)
            val src = file.readText()
            assertTrue(
                "Expected $relPath to wrap its text input in PrivateKeyboard",
                Regex("""\bPrivateKeyboard\s*\{""").containsMatchIn(src),
            )
        }
    }
}
