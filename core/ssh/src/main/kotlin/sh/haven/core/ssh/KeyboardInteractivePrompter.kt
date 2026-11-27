package sh.haven.core.ssh

/**
 * A server-issued keyboard-interactive challenge. An SSH server performing
 * `keyboard-interactive` auth sends one of these per round, typically asking
 * for a password + a 2FA / TOTP code in separate rounds, or both at once.
 *
 * The host should present [instruction] and each [Prompt.text] to the user,
 * collecting one response per prompt. Responses are returned in the same
 * order as [prompts].
 */
data class KeyboardInteractiveChallenge(
    /** "user@host" or similar — context for the UI so the user knows which
     *  session is being authenticated. May be empty. */
    val destination: String,
    /** Server-provided challenge name (e.g. "Google Authenticator"). May be empty. */
    val name: String,
    /** Server-provided instructions shown above the prompts. May be empty. */
    val instruction: String,
    val prompts: List<Prompt>,
    /**
     * Optional per-prompt suggested answers, parallel to [prompts]. A
     * non-null entry pre-fills the corresponding field in the UI — used
     * by the TOTP auto-fill path (#178) when the profile asks to confirm
     * the generated code before sending rather than auto-submitting.
     * Empty (the default) means no pre-fill.
     */
    val prefilled: List<String?> = emptyList(),
) {
    data class Prompt(
        val text: String,
        /** When false (the usual case for passwords / codes) the response
         *  should be masked in the UI. */
        val echo: Boolean,
    )
}

/**
 * Answers a [KeyboardInteractiveChallenge] from the SSH server. Implementations
 * typically surface a dialog and suspend until the user submits or cancels.
 *
 * Return a list of responses with the same length as [KeyboardInteractiveChallenge.prompts],
 * or `null` to abort authentication. JSch treats a null here as "user declined" and
 * stops attempting keyboard-interactive auth on this session.
 */
fun interface KeyboardInteractivePrompter {
    suspend fun prompt(challenge: KeyboardInteractiveChallenge): List<String>?

    /**
     * Non-blocking notification when the SSH server sends an
     * `SSH_MSG_USERAUTH_BANNER` during authentication (JSch's
     * `UserInfo.showMessage`). Must not block the calling IO thread: servers
     * like Tailscale SSH in `check` mode send a banner containing an approval
     * URL and hold the userauth request open on the same socket until the user
     * completes the browser check (#781).
     */
    fun onBanner(destination: String, message: String) {}
}

private val OTP_KEYWORDS = listOf(
    "verification code", "one-time", "one time", "otp", "totp",
    "token", "authenticator", "2fa", "two-factor", "two factor",
    "code:", "code ", "passcode",
    "一次性", "動態密碼", "动态密码", "验证码", "驗證碼",
    "einmal", "vérification", "verificação", "verificación",
    "ワンタイム", "確認コード", "일회용", "인증 코드", "однораз",
    "jednoráz", "eenmalig", "tek kullanımlık",
)

private val PASSWORD_KEYWORDS = listOf(
    "password", "passphrase", "passwort", "mot de passe",
    "contraseña", "contrasena", "пароль",
    "密码", "密碼", "口令", "パスワード", "암호", "비밀번호",
    "senha", "wachtwoord", "hasło", "haslo", "parola", "şifre",
    "heslo", "lösenord", "adgangskode", "passord", "salasana",
    "jelszó", "parolă", "κωδικός", "סיסמה", "كلمة المرور",
)

/**
 * Heuristic for "this prompt wants a TOTP / one-time code". PAM OTP modules
 * (google-authenticator, oath-toolkit, Duo, etc.) phrase the prompt in varied
 * ways (including "One-time password:") on a masked field (`echo == false`).
 */
fun KeyboardInteractiveChallenge.Prompt.looksLikeOtpPrompt(): Boolean {
    if (echo) return false
    val t = text.lowercase()
    return OTP_KEYWORDS.any { it in t }
}

/**
 * True when [this] is a masked login-password prompt (not echoed and not an
 * OTP / one-time-password prompt) whose answer can be satisfied from or saved
 * into a profile's stored SSH password (#892), including localized Linux-PAM
 * (`pam_unix`) password prompts.
 */
fun KeyboardInteractiveChallenge.Prompt.isSavablePasswordPrompt(): Boolean {
    if (echo || looksLikeOtpPrompt()) return false
    val t = text.lowercase()
    return PASSWORD_KEYWORDS.any { it in t }
}

private val AUTH_BANNER_URL_REGEX = Regex("""https?://[^\s<>"]+""")

/**
 * Extract `http://` and `https://` URLs from an SSH auth banner, stripping
 * trailing sentence or wrapper punctuation so links like
 * `(https://login.tailscale.com/a/abc123).` open cleanly (#781).
 */
fun extractAuthBannerUrls(message: String): List<String> =
    AUTH_BANNER_URL_REGEX.findAll(message)
        .map { it.value.trimEnd('.', ',', ';', ')', ']', '\'', '"') }
        .filter { it.length > "https://".length }
        .distinct()
        .toList()
