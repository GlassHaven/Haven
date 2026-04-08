package sh.haven.core.ssh

/**
 * Thrown by [SshClient.connect] when the SSH key exchange completes successfully
 * but user authentication fails. Carries the host key captured during KEX so the
 * caller can still run TOFU verification (showing a fingerprint dialog on first
 * contact) before surfacing the auth error to the user.
 *
 * Without this, a fresh-install user whose first auth attempt fails (e.g.
 * passphrase-protected keys, wrong remembered password, MaxAuthTries) would
 * never see the host key prompt at all — the JSchException would be raised
 * from inside `sess.connect()` and no key would ever reach the TOFU layer.
 * See follow-up on GlassOnTin/Haven#75.
 */
class HostKeyAuthFailure(
    val hostKey: KnownHostEntry,
    cause: Throwable,
) : Exception("SSH authentication failed for ${hostKey.hostname}:${hostKey.port}", cause)
