package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyImporterTest {

    // Throwaway RSA 2048-bit key in OpenSSH format for use as a test fixture only.
    // Generated with: ssh-keygen -t rsa -b 2048 -N "" -f /tmp/haven_test_key
    // This key must never be used for real authentication.
    private val validRsaPem = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
        NhAAAAAwEAAQAAAQEAwCsJkuZffj9UnEKr33fxniFh+3PQ/Ef0sUS+dlT9RCmgRoMwnbLW
        bR7sKuz4k6xYcXm5CgxkrlRNsjfleR1TbAreMm3sbLU6yhftCrNMPJCPpj1bx2Usqdi6Ia
        qYIJXj0LZFCdEdRDQhFa/X1JhCSKXh38aNR7o/jgbzjIPFZXG7hDTJAFS4xzhE9YCmAxkQ
        0+xNShYk4VIFtVWtkNK/pUDFTXf6gvgDXL+cMBAXlPshyMn7ZNxRAwP1KCp0JOtfNNZRzw
        HZuD1BFneNvLZPNbErEv4zi3XD5TZX35TtCvUNjnUs+ySosZI044tu2mAhgfYBP8qK+WGp
        fDGI9kFnWQAAA8jBPPCIwTzwiAAAAAdzc2gtcnNhAAABAQDAKwmS5l9+P1ScQqvfd/GeIW
        H7c9D8R/SxRL52VP1EKaBGgzCdstZtHuwq7PiTrFhxebkKDGSuVE2yN+V5HVNsCt4ybexs
        tTrKF+0Ks0w8kI+mPVvHZSyp2LohqpgglePQtkUJ0R1ENCEVr9fUmEJIpeHfxo1Huj+OBv
        OMg8VlcbuENMkAVLjHOET1gKYDGRDT7E1KFiThUgW1Va2Q0r+lQMVNd/qC+ANcv5wwEBeU
        +yHIyftk3FEDA/UoKnQk61801lHPAdm4PUEWd428tk81sSsS/jOLdcPlNlfflO0K9Q2OdS
        z7JKixkjTji27aYCGB9gE/yor5Yal8MYj2QWdZAAAAAwEAAQAAAQBR16J+sWW3J3K6ED0R
        8gvx3GbWCFfXsi+Y9d2mGQE6b/4GOeZRK3LeS36qs30Uq6CJR52SlX+lrVrfzaWKJP6784
        75bE52Z+LvYiw+0+jinHDJjLVTYRgaCCcRoo2ixyOc5pvVl/1+aDM1AMyLiwMj3J4rx2yx
        QTXDH9vHGvHNh1sH9NUXNETpEg11m9wQeY2f/vOhH54PucZLYXrHMAYu3kRqO42FeRHeWC
        P3R7EOA9RWMlYykpkAGIEEK+BjIZo89SFB8cZqDzDV9UKYTwPi4AQYVSgA7lTIJuQSbuhV
        bB/t/PFYoXlHLWZ0MfFkLZ40GmppwXyWa8C8tRoWv6THAAAAgQCkZWwtsw3lJ+J4+ptDMa
        pnEoJ92lKZXjMxaFAcERbMgQfJuEYE/YheHuG4LOARRGdR3nTj9bWPSezu4S/waV9BGns5
        iBIenFxUDtrKtVx0g9dABp/nKPbFYLszogEnkH8eik6YpcVEDoMAVJxhRycsoOEwYufSeT
        Q6C92X8ujMewAAAIEA595thhVDg4DwHhDB7sz1ILLljmjN0RMpebj3KlElfIOgckCQyvrP
        Pv6OnSGjMMbotu+HnBFiHxdJy+RWavNOgQc3xaitnQnav0Od3VchtZ3JjtT1hs7rDk9pJa
        0C/ZTt9fLapE/bjvp04GN9cpZLI2McFgGjBoy3Xes1Ok2s4wcAAACBANQq4acoMbwpae57
        CVmYkSVxyv1puHz5fgYOnTNoAdnMWyb/fn7yMbKC2eb2b1tH68Xt8LBZ27RvNc0eTqYyiN
        2DP7e/Voy1Aq9qX3pxWf12SBkyRMixBNghjlSHhRek+70j6YclWJxinNqu9wlimequ725r
        6w//dktAErFpxOqfAAAADGlhbkBtc2ktejc5MAECAwQFBg==
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent().toByteArray()

    // ---- import() with invalid input ----

    @Test(expected = IllegalArgumentException::class)
    fun `import with empty bytes throws IllegalArgumentException`() {
        SshKeyImporter.import(byteArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import with garbage bytes throws IllegalArgumentException`() {
        val garbage = ByteArray(256) { it.toByte() }
        SshKeyImporter.import(garbage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import with short random bytes throws IllegalArgumentException`() {
        SshKeyImporter.import(byteArrayOf(0x00, 0x01, 0x02))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import with arbitrary text bytes throws IllegalArgumentException`() {
        SshKeyImporter.import("not a key".toByteArray())
    }

    // ---- import() happy path ----

    @Test
    fun `import valid RSA PEM key returns ImportedKey`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertNotNull(result)
    }

    @Test
    fun `import valid RSA PEM key returns ssh-rsa key type`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertEquals("ssh-rsa", result.keyType)
    }

    @Test
    fun `import valid RSA PEM key preserves original file bytes`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertTrue(validRsaPem.contentEquals(result.privateKeyBytes))
    }

    @Test
    fun `import valid RSA PEM key produces SHA256 fingerprint`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertTrue(
            "Expected fingerprint to start with SHA256:, got: ${result.fingerprintSha256}",
            result.fingerprintSha256.startsWith("SHA256:")
        )
    }

    @Test
    fun `import valid RSA PEM key produces openssh public key line`() {
        val result = SshKeyImporter.import(validRsaPem)
        assertTrue(
            "Expected public key to start with key type, got: ${result.publicKeyOpenSsh}",
            result.publicKeyOpenSsh.startsWith("ssh-rsa ")
        )
    }

    @Test
    fun `import is deterministic for same input`() {
        val r1 = SshKeyImporter.import(validRsaPem)
        val r2 = SshKeyImporter.import(validRsaPem)
        assertEquals(r1.keyType, r2.keyType)
        assertEquals(r1.fingerprintSha256, r2.fingerprintSha256)
        assertEquals(r1.publicKeyOpenSsh, r2.publicKeyOpenSsh)
    }

    // ---- import() with passphrase ----

    // Throwaway RSA 2048-bit key encrypted with passphrase "test-passphrase" for use as a test fixture only.
    // Generated with: ssh-keygen -t rsa -b 2048 -N "test-passphrase" -f /tmp/haven_enc_rsa_test_key
    // RSA is used (not Ed25519) because JSch supports writePrivateKey() for RSA but not Ed25519.
    // This key must never be used for real authentication.
    private val encryptedRsaPem = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABCdMXrGfM
        Q5sCH5WDSNCuRvAAAAGAAAAAEAAAEXAAAAB3NzaC1yc2EAAAADAQABAAABAQDgWRIAPwGL
        HMU9snxLbALQbY6MxmSpGiAbsCR9Kzq95kAqbK7ozS9a10LpNjhL80S8USQJQKEV6QFVuN
        gv9NzxJfhhhHOeZJVrkzw6TRasrDQuNKIhMvXBsygicJKlRkOlYbOlWZVUSSLmMt58Ohq4
        1vLStbREryirNByCSLMCYIqH0itClYgbuI/QhMk5mIXkbRvxyW1zL8ybVqecCcVWpy6AnI
        qHeIcKNlacRvLck/8YrI9TlazOIgRILHHVApK0XdaT1Eq7L0+a+eEzM2RTNNuPQM9m2aBU
        v7VWCsLhDHoWDyt8v5t1jmBpBBm8pyuypCWCC4ivvGWP+MGpX3bpAAAD0JOxj2Ze7njGZF
        i1j6YEcOZRjf0Tliz5eOrdYnaYtJNF+I3yTKb3VpMuLylvdreOCHL3dn7tnNFjZDsE/bc+
        Mf6Y/Sr8ji+WjrJVETMiiQ+Q9J7Q1fTP7j6SmyUpdXjbFxQ/5DP98BNgzXTC684H6qTVK+
        B4UC1fjCaU54fErqglw7bN0NkDXil6wJJM3nliONiiwcp0Q8bJBbemhaUfeisLRIbHYkBU
        z/S/Q5yOEm0bogij6nyDd/sJ3htDYdSGN5bh6fOzwcN6d3WQXSpJNnMjUh4wbxh+UDBcas
        MStA2btGKx08r/XWV5UnK8Og4ok5zBuTQXr8sr+M9pWOBwRHnQRAmaAkg8QWK+yVioWDp0
        Dr1Gf66EAykjMKo7LPvFzpNixnSI4/TjexU4cH+5/V13g7asuz2Ws5Htd41OPky5zP2ttG
        InYifXVLwb+qySpBiAbHutmeQdqoLLJ0W2ulrPkhpKnNSxNe76vEhBBlG4kmk7UfgbW/NC
        AbYNwnygrMHXmOYi2anG2wS+Mh8WLlNi4aCT14PSV3s6ZBE+ygdipJXqL8IrRpJQ7lFBhu
        FDWgR9OX+KtOkUkH0QX9loiMG5kXTla+sMLkXfI/IxB/qu1NRLKlhBNTX1toYgFPNyGY2A
        8bziMBgVZuuWRaoFBLAxgT+YwjoQ7VhO0zalLuEyFbs+5pGLxRLMJw2VqoIPVQRTQDkyDs
        rWt3Kk90ACOB3g8XbQJ+AGHKqMa0Cvd5aXzAZqirdQ/3de3N5J6nyKGyWifhvDncosuqFl
        3bqnc4lvb28W35aJl/QxezD3Kc9h0ch9Rs6MjhxeX/SpwAyowbHQz66BjOxdoBxLgsh1HU
        zq0wtwgtl6jE9PvvqrcsoEQOdEiOlWfJEH/jsifteOi9qZakJ5K2DkUsYIuekwGpQr6Qq8
        JxjFvd035xv/JQOVVg8tBGQGkxHKan1oDSMWYJZEkEEQB6lIpbjo5as/1KJuZjm2kozuXb
        d//PkCJGMMbHMDOOq7+FijjXd1CWJq6aIaMygy5S9s4QycD/ngn6+Ppx5eNql+pxuCAMUD
        49pbgruARf32Jgb+YiwL5+85aWbcwqu8DQZuMeCW8cm2/Vsqeig8RUyHCBbGQvjrN6HOmu
        BJzgc3peO2gZbludmndsdKB+ojY4B5j+xE61lWaMWG2/8jYSbkDHUfTZIwbrCCPCM1yNuH
        vhD5DZyS9uW93ZTjX5GH7IAQt7QcJusQyvfqU/8T1DU2i/j9/r+FmxkG918zwDZxNqNef2
        PsVIuNOjBodkljf1EuEN7VoLnYpK0=
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent().toByteArray()

    @Test(expected = SshKeyImporter.EncryptedKeyException::class)
    fun `import encrypted key without passphrase throws EncryptedKeyException`() {
        SshKeyImporter.import(encryptedRsaPem)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import encrypted key with wrong passphrase throws IllegalArgumentException`() {
        SshKeyImporter.import(encryptedRsaPem, "wrong-passphrase")
    }

    @Test
    fun `import encrypted key with correct passphrase stores original encrypted bytes`() {
        val result = SshKeyImporter.import(encryptedRsaPem, "test-passphrase")
        assertTrue(
            "Stored key bytes should equal the original encrypted input",
            encryptedRsaPem.contentEquals(result.privateKeyBytes)
        )
        assertTrue("isEncrypted should be true", result.isEncrypted)
    }

    @Test
    fun `import encrypted key with correct passphrase produces valid fingerprint`() {
        val result = SshKeyImporter.import(encryptedRsaPem, "test-passphrase")
        assertTrue(
            "Expected fingerprint to start with SHA256:, got: ${result.fingerprintSha256}",
            result.fingerprintSha256.startsWith("SHA256:")
        )
    }

    @Test
    fun `import encrypted key with correct passphrase returns ssh-rsa type`() {
        val result = SshKeyImporter.import(encryptedRsaPem, "test-passphrase")
        assertEquals("ssh-rsa", result.keyType)
    }

    @Test
    fun `import encrypted key with correct passphrase produces openssh public key line`() {
        val result = SshKeyImporter.import(encryptedRsaPem, "test-passphrase")
        assertTrue(
            "Expected public key to start with ssh-rsa, got: ${result.publicKeyOpenSsh}",
            result.publicKeyOpenSsh.startsWith("ssh-rsa ")
        )
    }

    @Test
    fun `import encrypted key result is consistent across calls`() {
        val r1 = SshKeyImporter.import(encryptedRsaPem, "test-passphrase")
        val r2 = SshKeyImporter.import(encryptedRsaPem, "test-passphrase")
        assertEquals(r1.fingerprintSha256, r2.fingerprintSha256)
        assertEquals(r1.publicKeyOpenSsh, r2.publicKeyOpenSsh)
        assertEquals(r1.keyType, r2.keyType)
    }

    // ---- import() with encrypted Ed25519 key ----

    // Throwaway Ed25519 key encrypted with passphrase "test-ed25519-pass" for use as a test fixture only.
    // Generated with: ssh-keygen -t ed25519 -N "test-ed25519-pass" -f /tmp/haven_test_ed25519_enc
    // This key must never be used for real authentication.
    private val encryptedEd25519Pem = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABCS3fhvqX
        dOf0JZQYdTkkENAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIOnBvgn2SbqahNXp
        f4MYj7/fV1X5c3ZkeuRALlPF5DbbAAAAkFKTXlDYAaLvgux4vT8ZQA363ibu21QxUKVZEU
        O6p/yhMpBUTSE/bZhDBhzjKW1KacHT3j4uS4CFgS52HtJKHAo3gnFBHDMWmUPNN0QagmT1
        2Ohjr/FduMCU9VhS77D1jk3cxW14ryUDgKbtzM5QJ04D46zYGvgbxSgP2IV9JwAAVFshzM
        A2XAvQgB2Qe2XfKg==
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent().toByteArray()

    @Test(expected = SshKeyImporter.EncryptedKeyException::class)
    fun `import encrypted Ed25519 key without passphrase throws EncryptedKeyException`() {
        SshKeyImporter.import(encryptedEd25519Pem)
    }

    @Test
    fun `import encrypted Ed25519 key with correct passphrase returns ssh-ed25519 type`() {
        val result = SshKeyImporter.import(encryptedEd25519Pem, "test-ed25519-pass")
        assertEquals("ssh-ed25519", result.keyType)
    }

    @Test
    fun `import encrypted Ed25519 key with correct passphrase stores original encrypted bytes`() {
        val result = SshKeyImporter.import(encryptedEd25519Pem, "test-ed25519-pass")
        assertTrue(
            "Stored key bytes should equal the original encrypted input",
            encryptedEd25519Pem.contentEquals(result.privateKeyBytes)
        )
        assertTrue("isEncrypted should be true", result.isEncrypted)
    }

    @Test
    fun `import encrypted Ed25519 key produces valid fingerprint`() {
        val result = SshKeyImporter.import(encryptedEd25519Pem, "test-ed25519-pass")
        assertTrue(
            "Expected fingerprint to start with SHA256:, got: ${result.fingerprintSha256}",
            result.fingerprintSha256.startsWith("SHA256:")
        )
    }

    @Test
    fun `import encrypted Ed25519 key produces openssh public key line`() {
        val result = SshKeyImporter.import(encryptedEd25519Pem, "test-ed25519-pass")
        assertTrue(
            "Expected public key to start with ssh-ed25519, got: ${result.publicKeyOpenSsh}",
            result.publicKeyOpenSsh.startsWith("ssh-ed25519 ")
        )
    }

    // ---- import() with unencrypted Ed25519 key ----

    // Throwaway unencrypted Ed25519 key for use as a test fixture only.
    // Generated with: ssh-keygen -t ed25519 -N "" -f /tmp/haven_test_ed25519_plain
    // This key must never be used for real authentication.
    private val unencryptedEd25519Pem = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
        QyNTUxOQAAACAGDEAEY/2eTWPYbI21/d13lEIrFjHWa11tKBnYa6M6xgAAAJihYZxQoWGc
        UAAAAAtzc2gtZWQyNTUxOQAAACAGDEAEY/2eTWPYbI21/d13lEIrFjHWa11tKBnYa6M6xg
        AAAEASnxhlh0i/Gz1H26nWiojhTd888E1YQC1hgnYMnaZuuAYMQARj/Z5NY9hsjbX93XeU
        QisWMdZrXW0oGdhrozrGAAAAEGhhdmVuLXRlc3QtcGxhaW4BAgMEBQ==
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent().toByteArray()

    @Test
    fun `import unencrypted Ed25519 key preserves original file bytes`() {
        val result = SshKeyImporter.import(unencryptedEd25519Pem)
        assertTrue(
            "Unencrypted key should be stored as original file bytes",
            unencryptedEd25519Pem.contentEquals(result.privateKeyBytes)
        )
    }

    @Test
    fun `import unencrypted Ed25519 key returns ssh-ed25519 type`() {
        val result = SshKeyImporter.import(unencryptedEd25519Pem)
        assertEquals("ssh-ed25519", result.keyType)
    }
}
