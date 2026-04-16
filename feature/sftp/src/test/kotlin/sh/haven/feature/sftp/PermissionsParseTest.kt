package sh.haven.feature.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip coverage for [SftpViewModel.permissionsStringToMode]. Each
 * case uses the 10-char Unix permissions string JSch/ls emit and checks
 * the parser against the expected low-12-bit mode rendered in octal.
 */
class PermissionsParseTest {

    private fun parse(s: String) = SftpViewModel.permissionsStringToMode(s)

    @Test fun plainFile_0644() {
        assertEquals(0x1A4 /* 0644 */, parse("-rw-r--r--"))
    }

    @Test fun executableFile_0755() {
        assertEquals(0x1ED /* 0755 */, parse("-rwxr-xr-x"))
    }

    @Test fun directory_0700() {
        assertEquals(0x1C0 /* 0700 */, parse("drwx------"))
    }

    @Test fun setuid_lowercase_s_includes_exec_bit() {
        // -rwsr-xr-x → 04755
        assertEquals(0x9ED, parse("-rwsr-xr-x"))
    }

    @Test fun setuid_uppercase_S_excludes_exec_bit() {
        // -rwSr-xr-x → 04655 (setuid set, owner-exec clear)
        assertEquals(0x9AD, parse("-rwSr-xr-x"))
    }

    @Test fun sticky_lowercase_t_includes_exec_bit() {
        // drwxrwxrwt → 01777
        assertEquals(0x3FF, parse("drwxrwxrwt"))
    }

    @Test fun setgid_bit() {
        // -rwxr-sr-x → 02755
        assertEquals(0x5ED, parse("-rwxr-sr-x"))
    }

    @Test fun emptyReturnsNull() {
        assertNull(parse(""))
    }

    @Test fun tooShortReturnsNull() {
        assertNull(parse("-rw-r-"))
    }
}
