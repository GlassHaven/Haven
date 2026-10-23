package sh.haven.core.security

import java.security.Signature
import javax.inject.Inject
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 硬件驻留 SSH 密钥的对外元数据（[HardwareKeySource.generate] 的返回值）。
 * `publicKeyOpenSsh` 可直接展示/导入 authorized_keys。顶层而非
 * [HardwareSshKeyManager] 嵌套：接口契约的一部分，消费方不应为一个
 * 返回类型依赖具体实现类。
 */
data class HardwareKeyInfo(
    val alias: String,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val strongBoxBacked: Boolean,
)

/**
 * 硬件驻留 SSH 密钥的薄接口 ([HardwareSshKeyManager] 的可替换视图)。
 *
 * 职责边界: 与 [HardwareSshKeyManager] 完全一致 —— 它只是给同一组能力一个接口
 * 类型。存在的理由是可测性: [HardwareSshKeyManager] 直连 AndroidKeyStore, JVM
 * 单测无法触碰; 消费方 (SshKeyRepository / KeystoreSshKeySigner) 依赖本接口,
 * 测试注入假实现即可覆盖生成/删除路径 (mirror OpenKeychainProvider/Keystore
 * 的注入模式)。
 *
 * 契约 (细节见 [HardwareSshKeyManager] 的 KDoc, 不在此重复):
 * - [generate] 在安全芯片生成 ECDSA P-256 密钥, 私钥永不出硬件, per-op 生物识别。
 * - [delete] 幂等: 不存在的 alias 静默成功。
 */
interface HardwareKeySource {
    fun generate(alias: String, preferStrongBox: Boolean): HardwareKeyInfo
    fun initSignature(alias: String): Signature
    fun delete(alias: String)
    fun contains(alias: String): Boolean
}

/**
 * 把 [HardwareSshKeyManager] 绑定为应用级 [HardwareKeySource]。消费方依赖接口,
 * 测试用假实现替换 (mirror KeystoreModule 的绑定模式)。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HardwareKeySourceModule {
    @Binds
    abstract fun bindHardwareKeySource(impl: HardwareSshKeyManager): HardwareKeySource
}
