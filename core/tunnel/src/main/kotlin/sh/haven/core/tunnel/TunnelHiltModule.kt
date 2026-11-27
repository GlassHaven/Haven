package sh.haven.core.tunnel

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TunnelHiltModule {
    @Binds
    @Singleton
    abstract fun bindTunnelFactory(impl: DefaultTunnelFactory): TunnelFactory

    @Binds
    @Singleton
    abstract fun bindUnderlayNetworkResolver(impl: AndroidUnderlayNetworkResolver): UnderlayNetworkResolver

    companion object {
        /**
         * Shared OkHttp instance for the Cloudflare Access SSH backend.
         * Per-dial call timeouts are set via `newBuilder()` in
         * [CloudflareAccessTunnel.dial]; this base client deliberately
         * leaves read/write timeouts at OkHttp's defaults (which
         * effectively let a WebSocket stay open as long as the SSH
         * session does) and stays idle when no Cloudflare Access
         * tunnel is configured.
         */
        @Provides
        @Singleton
        @JvmStatic
        fun provideTunnelHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}
