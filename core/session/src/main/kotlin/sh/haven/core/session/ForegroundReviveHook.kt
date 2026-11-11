package sh.haven.core.session

/**
 * A transport or background endpoint whose recovery is invoked on
 * return-to-foreground and network-available by the foreground connection
 * service.
 */
interface ForegroundReviveHook {
    /**
     * Proactively check liveness and reconnect now if needed. Must be cheap
     * and non-blocking — kick off any real work asynchronously.
     */
    fun reviveNow()
}
