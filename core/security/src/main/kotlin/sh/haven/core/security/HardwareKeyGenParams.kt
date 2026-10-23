package sh.haven.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.spec.ECGenParameterSpec

/**
 * Hardware SSH key 的 Keystore 生成策略（纯数据）+ 到 framework
 * [KeyGenParameterSpec] 的机械映射。
 *
 * 职责边界: 只携带"我们要 AndroidKeyStore 做什么"的全部决策，不含任何
 * Keystore I/O。独立成纯数据类的原因: Robolectric/JVM 测试无法承载真实
 * AndroidKeyStore，只能对"策略"断言；[toKeyGenParameterSpec] 是逐字段
 * setter 的薄封装，靠 review 保证，不进单测。
 */
internal data class HardwareKeyGenParams(
    val alias: String,
    val strongBoxBacked: Boolean,
    val algorithm: String = KeyProperties.KEY_ALGORITHM_EC,
    val ecCurveName: String = CURVE_SECP256R1,
    val purposes: Int = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
    val digest: String = KeyProperties.DIGEST_SHA256,
    val userAuthenticationRequired: Boolean = true,
    val userAuthenticationValidityDurationSeconds: Int = USER_AUTH_VALIDITY_PER_OPERATION,
    val invalidatedByBiometricEnrollment: Boolean = true,
) {
    fun toKeyGenParameterSpec(): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec(ecCurveName))
            .setDigests(digest)
            .setUserAuthenticationRequired(userAuthenticationRequired)
            .setUserAuthenticationValidityDurationSeconds(userAuthenticationValidityDurationSeconds)
            .setInvalidatedByBiometricEnrollment(invalidatedByBiometricEnrollment)
            .apply {
                // StrongBox 自 API 28 才存在。门槛与 HardwareSshKeyManager 的
                // generate() 一致，此处就地展开（而非只靠调用方把关）是为了
                // lint 可验证 + 防未来调用方漏掉版本检查。
                if (strongBoxBacked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

    companion object {
        /** KeyMint/Keymaster 硬件后端唯一可靠曲线（StrongBox 不支持 curve25519，见设计决策 1）。 */
        const val CURVE_SECP256R1 = "secp256r1"

        /**
         * -1 = 每次使用密钥都要单独一次用户认证（CryptoObject per-op 路径）。
         * 不用 30s 窗口: 带 CryptoObject 的 BiometricPrompt 认证不受 validity
         * 窗口豁免（Android 强制每次 CryptoObject 过一次 BIOMETRIC_STRONG），
         * 正数窗口只会营造"窗口内免认证复用"的错觉；per-op 是已确认的最强语义。
         */
        const val USER_AUTH_VALIDITY_PER_OPERATION = -1

        /** Hardware SSH key 的默认策略: ECDSA P-256, sign+verify, per-op 认证。 */
        fun forHardwareSshKey(alias: String, strongBoxBacked: Boolean): HardwareKeyGenParams =
            HardwareKeyGenParams(alias = alias, strongBoxBacked = strongBoxBacked)
    }
}
