package sh.haven.rclone.bridge

import sh.haven.rclone.binding.rcbridge.Rcbridge

/**
 * Thin Kotlin wrapper around the gomobile-generated Java bindings for the
 * Go rcbridge module.  All rclone functionality is accessed via the [rpc]
 * method which calls rclone's RC (Remote Control) API.
 */
object RcloneBridge {

    data class RpcResult(
        val status: Int,
        val output: String,
    ) {
        val isOk: Boolean get() = status == 200
    }

    private var initialized = false

    /**
     * Initialise the rclone library.  Must be called once before any [rpc]
     * calls.  Safe to call multiple times (subsequent calls are no-ops).
     *
     * @param configPath absolute path to the rclone config file
     *                   (e.g. `/data/data/sh.haven.app/files/rclone/rclone.conf`).
     *                   Pass empty string to use rclone's default.
     */
    fun initialize(configPath: String) {
        if (initialized) return
        Rcbridge.rbInitialize(configPath)
        initialized = true
    }

    /** Shut down the rclone library. Call once at app shutdown. */
    fun shutdown() {
        if (!initialized) return
        Rcbridge.rbFinalize()
        initialized = false
    }

    /**
     * Call an rclone RC method.
     *
     * @param method RC method name, e.g. "operations/list", "config/create"
     * @param input  JSON string of method parameters, e.g. `{"fs":"remote:","remote":"/"}`
     * @return [RpcResult] with HTTP-style status code and JSON output
     */
    fun rpc(method: String, input: String = "{}"): RpcResult {
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbRPC(method, input)
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }
}
