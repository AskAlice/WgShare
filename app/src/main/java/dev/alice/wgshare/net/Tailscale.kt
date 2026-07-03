package dev.alice.wgshare.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * NAT traversal + transport encryption are delegated to Tailscale: the user runs the Tailscale
 * app and joins the same tailnet on every device. Tailscale assigns each node a stable IPv4 from
 * the CGNAT range 100.64.0.0/10 and gives full node-to-node connectivity (WireGuard + DERP relays),
 * so this app only needs to find its own Tailscale IP and address peers by theirs.
 */
object Tailscale {
    /** This device's Tailscale IPv4, or null if Tailscale isn't connected. */
    fun localIp(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .firstOrNull(::isTailscaleCgnat)
}

/** True for addresses in 100.64.0.0/10 (RFC 6598 CGNAT), which Tailscale draws node IPs from. */
private fun isTailscaleCgnat(ip: String): Boolean {
    val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
    return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
}
