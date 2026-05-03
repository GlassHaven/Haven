package sh.haven.core.data.keystore

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import sh.haven.core.security.Keystore

/**
 * Bind [UnifiedKeystore] as the application-wide [Keystore]
 * implementation. Concrete sections ([SshKeySection],
 * [ProfileCredentialSection]) are wired through their own
 * @Inject constructors and don't need explicit bindings here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class KeystoreModule {
    @Binds
    abstract fun bindKeystore(impl: UnifiedKeystore): Keystore
}
