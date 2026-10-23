package sh.haven.core.security

import android.os.Build
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 硬件驻留 SSH 密钥的 AndroidKeyStore 交互唯一入口（design.md §模块设计-1，L2）。
 *
 * 职责边界: ECDSA P-256 密钥在 Keystore 里的 生成 / 签名初始化 / 存在性 / 删除。
 * 不做: 生物识别 UI 与排队（BiometricGate，T3/T4）、DB 落库（core/data，T3）、
 * SSH 协议栈装配（core/ssh，T5）。
 *
 * 关键设计:
 * - 私钥永不出硬件: KeyPairGenerator 以 provider "AndroidKeyStore" 生成，
 *   进程只拿到公钥与一个 opaque 私钥句柄，导出路径在更上层直接不存在。
 * - per-op 认证: 生成策略固定 `setUserAuthenticationValidityDurationSeconds(-1)`
 *   （见 [HardwareKeyGenParams.USER_AUTH_VALIDITY_PER_OPERATION]），因此
 *   [initSignature] 返回的 Signature 必须先经 BiometricPrompt + CryptoObject
 *   认证才能 sign —— 这是 T4/T5 对接时的硬契约。
 * - StrongBox 降级: 仅在系统 >= API 28 且 preferStrongBox 时尝试
 *   `setIsStrongBoxBacked(true)`（StrongBox 的两个 API 都是 28+ 才存在，版本
 *   检查就地展开以保 lint 可验证），设备无 StrongBox
 *   （[StrongBoxUnavailableException]）则原样降级 TEE 重试一次，
 *   [HardwareKeyInfo.strongBoxBacked] 如实回报实际后端。
 */
@Singleton
class HardwareSshKeyManager @Inject constructor() {

    /** 硬件密钥的对外元数据; publicKeyOpenSsh 可直接展示/导入 authorized_keys。 */
    data class HardwareKeyInfo(
        val alias: String,
        val publicKeyOpenSsh: String,
        val fingerprintSha256: String,
        val strongBoxBacked: Boolean,
    )

    /**
     * 在 AndroidKeyStore 中生成一把 ECDSA P-256 SSH 密钥。已存在的同名 alias
     * 会被覆盖（alias 的唯一性分配是 DB 层 T3 的职责）。
     *
     * @param preferStrongBox 尝试 StrongBox 安全芯片。不可用（[StrongBoxUnavailableException]）
     *   或系统 < API 28 时自动降级 TEE，只重试一次；StrongBox 的 API 与异常类都自
     *   P 才存在，版本检查就地展开是为了 lint 可验证，勿抽进辅助函数。
     */
    fun generate(alias: String, preferStrongBox: Boolean): HardwareKeyInfo {
        require(alias.isNotBlank()) { "hardware key alias must not be blank" }
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateInAndroidKeyStore(
                    HardwareKeyGenParams.forHardwareSshKey(alias, strongBoxBacked = true),
                )
            } catch (e: StrongBoxUnavailableException) {
                // 该机型的 Secure Element 不可用（缺失/忙）: 静默降级 TEE 重试一次。
            }
        }
        return generateInAndroidKeyStore(
            HardwareKeyGenParams.forHardwareSshKey(alias, strongBoxBacked = false),
        )
    }

    /**
     * 返回绑定该 alias 私钥、等待 per-op 生物识别的 SHA256withECDSA Signature。
     * 调用方（T5 KeystoreSshIdentity）把它包进 BiometricPrompt CryptoObject，
     * 认证通过后 update+sign；未认证就 sign 会抛 UserNotAuthenticatedException。
     *
     * @throws android.security.keystore.KeyPermanentlyInvalidatedException
     *   新录生物特征使密钥作废时由 Keystore 抛出，原样上抛（fail-closed，由上层
     *   提示用户删除重建）。
     * @throws java.security.UnrecoverableKeyException 同族故障，原样上抛。
     * @throws IllegalArgumentException alias 下没有硬件密钥。
     */
    fun initSignature(alias: String): Signature {
        val privateKey = androidKeyStore().getKey(alias, null) as? PrivateKey
            ?: throw IllegalArgumentException("No hardware SSH key in AndroidKeyStore for alias '$alias'")
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply { initSign(privateKey) }
    }

    /** 从 Keystore 删除该 alias（幂等：不存在的 alias 静默成功）。 */
    fun delete(alias: String) {
        androidKeyStore().deleteEntry(alias)
    }

    fun contains(alias: String): Boolean = androidKeyStore().containsAlias(alias)

    private fun generateInAndroidKeyStore(params: HardwareKeyGenParams): HardwareKeyInfo {
        val generator = KeyPairGenerator.getInstance(params.algorithm, ANDROID_KEYSTORE)
        generator.initialize(params.toKeyGenParameterSpec())
        val keyPair = generator.generateKeyPair()
        val curve = SshKeyGenerator.EcdsaCurve.NIST_P256
        val blob = SshKeyGenerator.encodeEcdsaPublicKey(keyPair.public as ECPublicKey, curve)
        return HardwareKeyInfo(
            alias = params.alias,
            publicKeyOpenSsh = SshKeyGenerator.openSshPublicKeyLine(curve.sshKeyType, blob, params.alias),
            fingerprintSha256 = SshKeyGenerator.fingerprintSha256(blob),
            strongBoxBacked = params.strongBoxBacked,
        )
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
