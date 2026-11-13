package sh.haven.core.knock

/**
 * A single packet to send during port knocking.
 *
 * `knockd`, `iptables -m length`, and RouterOS watch for TCP SYNs, UDP
 * datagrams on specific ports, or ICMP Echo Requests of specific lengths (#846).
 * For [Protocol.TCP] and [Protocol.UDP], [port] is the destination port
 * (`1..65535`). For [Protocol.ICMP], [port] (aliased as [size]) is the
 * configured packet length interpreted according to [icmpSizeMode].
 */
data class KnockStep(
    val port: Int,
    val protocol: Protocol,
    val icmpSizeMode: IcmpSizeMode = IcmpSizeMode.PAYLOAD_ONLY,
) {
    enum class Protocol { TCP, UDP, ICMP }

    /**
     * How an ICMP knock's configured [size] maps to Echo payload bytes (#846),
     * matching Knock on Ports' three ICMP packet-size modes:
     * - [PAYLOAD_ONLY] (`icmp`, default): Echo data bytes only (`ping -s <size>`,
     *   Knock on Ports "Without headers" / "Only data").
     * - [WITH_ICMP_HEADER] (`icmp+h`): 8-byte ICMP Echo header + payload.
     * - [WITH_IP_AND_ICMP_HEADERS] (`icmp+ip`): total IP packet length
     *   (20-byte IPv4 or 40-byte IPv6 header + 8-byte ICMP Echo header + payload).
     */
    enum class IcmpSizeMode {
        PAYLOAD_ONLY,
        WITH_ICMP_HEADER,
        WITH_IP_AND_ICMP_HEADERS,
    }

    /** Alias for [port] on [Protocol.ICMP] steps. */
    val size: Int get() = port

    init {
        when (protocol) {
            Protocol.TCP, Protocol.UDP -> {
                require(port in 1..65535) { "port out of range: $port" }
                require(icmpSizeMode == IcmpSizeMode.PAYLOAD_ONLY) {
                    "icmpSizeMode only applies to ICMP steps"
                }
            }
            Protocol.ICMP -> when (icmpSizeMode) {
                IcmpSizeMode.PAYLOAD_ONLY -> require(port in 0..MAX_IPV4_ICMP_PAYLOAD_BYTES) {
                    "ICMP payload size out of range (0..$MAX_IPV4_ICMP_PAYLOAD_BYTES): $port"
                }
                IcmpSizeMode.WITH_ICMP_HEADER -> require(
                    port in ICMP_ECHO_HEADER_BYTES..(MAX_IPV4_ICMP_PAYLOAD_BYTES + ICMP_ECHO_HEADER_BYTES)
                ) {
                    "ICMP packet size (with 8B header) out of range " +
                        "($ICMP_ECHO_HEADER_BYTES..${MAX_IPV4_ICMP_PAYLOAD_BYTES + ICMP_ECHO_HEADER_BYTES}): $port"
                }
                IcmpSizeMode.WITH_IP_AND_ICMP_HEADERS -> require(
                    port in (IPV4_HEADER_BYTES + ICMP_ECHO_HEADER_BYTES)..65535
                ) {
                    "ICMP total packet size (with IP+ICMP headers) out of range " +
                        "(${IPV4_HEADER_BYTES + ICMP_ECHO_HEADER_BYTES}..65535): $port"
                }
            }
        }
    }

    /** Resolve the Echo Request data payload byte count for IPv4 or IPv6. */
    fun icmpPayloadBytes(ipv6: Boolean): Int {
        require(protocol == Protocol.ICMP) { "not an ICMP step: $this" }
        val payload = when (icmpSizeMode) {
            IcmpSizeMode.PAYLOAD_ONLY -> port
            IcmpSizeMode.WITH_ICMP_HEADER -> port - ICMP_ECHO_HEADER_BYTES
            IcmpSizeMode.WITH_IP_AND_ICMP_HEADERS -> {
                val ipHeaderBytes = if (ipv6) IPV6_HEADER_BYTES else IPV4_HEADER_BYTES
                port - ipHeaderBytes - ICMP_ECHO_HEADER_BYTES
            }
        }
        val maxPayload = if (ipv6) MAX_IPV6_ICMP_PAYLOAD_BYTES else MAX_IPV4_ICMP_PAYLOAD_BYTES
        require(payload in 0..maxPayload) {
            "ICMP payload length $payload out of range (0..$maxPayload) for " +
                "${if (ipv6) "IPv6" else "IPv4"} ($this)"
        }
        return payload
    }

    override fun toString(): String = when (protocol) {
        Protocol.TCP -> "$port/tcp"
        Protocol.UDP -> "$port/udp"
        Protocol.ICMP -> when (icmpSizeMode) {
            IcmpSizeMode.PAYLOAD_ONLY -> "$port/icmp"
            IcmpSizeMode.WITH_ICMP_HEADER -> "$port/icmp+h"
            IcmpSizeMode.WITH_IP_AND_ICMP_HEADERS -> "$port/icmp+ip"
        }
    }

    companion object {
        const val ICMP_ECHO_HEADER_BYTES = 8
        const val IPV4_HEADER_BYTES = 20
        const val IPV6_HEADER_BYTES = 40
        const val MAX_IPV4_ICMP_PAYLOAD_BYTES = 65535 - IPV4_HEADER_BYTES - ICMP_ECHO_HEADER_BYTES
        const val MAX_IPV6_ICMP_PAYLOAD_BYTES = 65535 - ICMP_ECHO_HEADER_BYTES
    }
}

/**
 * Ordered sequence of knock steps plus the gap to wait between them.
 * Empty / null user input parses to `null`, which means "knocking
 * disabled for this profile".
 */
data class KnockSequence(
    val steps: List<KnockStep>,
    val interKnockDelayMs: Int,
) {
    init {
        require(steps.isNotEmpty()) { "empty knock sequence" }
        require(interKnockDelayMs in 0..60_000) {
            "delay out of range: $interKnockDelayMs"
        }
    }

    /** Render the sequence in the same syntax `parse` accepts. */
    fun format(): String = steps.joinToString(" ") { it.toString() }

    companion object {
        /** Default gap between packets — enough for `knockd`'s default `seq_timeout`. */
        const val DEFAULT_DELAY_MS = 100

        /** Cap per-token `<value>x<count>` expansion so malformed input cannot stall connects. */
        const val MAX_REPEAT_COUNT = 32

        /**
         * Parse a user-supplied knock string.
         *
         * Format: tokens separated by whitespace and/or commas. Each token is:
         * - `port` (defaults to TCP),
         * - `port[/|:]proto` where `proto` is `tcp` or `udp`, or
         * - `size[xN][/|:]icmp[+h|+ip]` for ICMP Echo Requests (#846), e.g.
         *   `495/icmp`, `495x1:ICMP`, `503/icmp+h`, or `523/icmp+ip`.
         *   Optional `xN` (`1..32`) repeats any step `N` times.
         *
         * Returns `Result.success(null)` for blank/null input ("knocking off").
         * Returns `Result.failure(IllegalArgumentException)` for any malformed
         * token, out-of-range port/size/repeat, or unknown protocol.
         */
        fun parse(raw: String?, delayMs: Int = DEFAULT_DELAY_MS): Result<KnockSequence?> {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return Result.success(null)
            return runCatching {
                val tokens = trimmed.split(',', ' ', '\t', '\n', '\r')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                require(tokens.isNotEmpty()) { "no knock tokens" }
                val steps = tokens.flatMap { parseToken(it) }
                KnockSequence(steps, delayMs)
            }
        }

        private fun parseToken(token: String): List<KnockStep> {
            val (valuePart, protoPart) = when {
                '/' in token -> token.substringBefore('/') to token.substringAfter('/')
                ':' in token -> token.substringBefore(':') to token.substringAfter(':')
                else -> token to "tcp"
            }
            val xIdx = valuePart.indexOfFirst { it == 'x' || it == 'X' }
            val (numStr, repeatCount) = if (xIdx >= 0) {
                val countStr = valuePart.substring(xIdx + 1)
                val count = countStr.toIntOrNull()
                    ?: throw IllegalArgumentException("invalid repeat count '$countStr' in '$token'")
                require(count in 1..MAX_REPEAT_COUNT) {
                    "repeat count out of range (1..$MAX_REPEAT_COUNT): $count in '$token'"
                }
                valuePart.substring(0, xIdx) to count
            } else {
                valuePart to 1
            }

            val value = numStr.toIntOrNull()
                ?: throw IllegalArgumentException("not a port or packet size: '$numStr' in '$token'")

            val (proto, sizeMode) = when (protoPart.trim().lowercase()) {
                "tcp" -> KnockStep.Protocol.TCP to KnockStep.IcmpSizeMode.PAYLOAD_ONLY
                "udp" -> KnockStep.Protocol.UDP to KnockStep.IcmpSizeMode.PAYLOAD_ONLY
                "icmp" -> KnockStep.Protocol.ICMP to KnockStep.IcmpSizeMode.PAYLOAD_ONLY
                "icmp+h", "icmphdr" -> KnockStep.Protocol.ICMP to KnockStep.IcmpSizeMode.WITH_ICMP_HEADER
                "icmp+ip", "icmpip" -> KnockStep.Protocol.ICMP to KnockStep.IcmpSizeMode.WITH_IP_AND_ICMP_HEADERS
                else -> throw IllegalArgumentException(
                    "unknown protocol '$protoPart' in '$token' (expected tcp, udp, icmp, icmp+h, or icmp+ip)"
                )
            }
            val step = try {
                KnockStep(value, proto, sizeMode)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("${e.message} (token '$token')", e)
            }
            return List(repeatCount) { step }
        }
    }
}
