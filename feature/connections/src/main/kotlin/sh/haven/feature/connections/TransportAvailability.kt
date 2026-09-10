package sh.haven.feature.connections

/**
 * Which transports the new-connection picker offers, given what this build
 * actually shipped (#510).
 *
 * Extracted from the dialog so the rule can be asserted. A gate that lives
 * only inside a Composable is a gate nobody can prove fired.
 */
internal object TransportAvailability {

    /**
     * @param all every transport the app knows, as (value, label).
     * @param rdp whether `librdp_transport.so` shipped.
     * @param spice whether `libspice_transport.so` shipped.
     * @param rclone whether this build's `libgojni.so` carries rclone. Unlike
     *   the other two this is not a missing *file* — the terminal flavour
     *   ships a smaller library built without the rcbridge package, so the
     *   answer comes from probing it rather than looking for it.
     * @param uml whether the UML guest payload (libvmlinux.so + stub + passt)
     *   shipped. The terminal flavour drops all three and F-Droid builds skip
     *   the fetch, so like RDP/SPICE this is a missing-file gate.
     *
     * VNC is never filtered: its client is Kotlin and ships in every build.
     * Nor is anything else — only the transports with native code the
     * terminal flavour drops.
     */
    fun offered(
        all: List<Pair<String, String>>,
        rdp: Boolean,
        spice: Boolean,
        rclone: Boolean,
        uml: Boolean = true,
    ): List<Pair<String, String>> = all.filter { (value, _) ->
        when (value) {
            "RDP" -> rdp
            "SPICE" -> spice
            "RCLONE" -> rclone
            "GUEST" -> uml
            else -> true
        }
    }
}
