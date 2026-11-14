package com.bigeyes.tv.utils

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    data class InterfaceCandidate(
        val interfaceName: String,
        val ipAddress: String
    )

    private val IGNORED_INTERFACE_PREFIXES = listOf(
        "tun", "tap", "ppp", "wg", "vpn", "p2p", "docker", "veth", "br-", "dummy", "rmnet", "ccmni", "lo"
    )

    private val PREFERRED_LAN_PREFIXES = listOf("wlan", "eth", "en", "ap", "usb")

    /**
     * Get the local IPv4 address of this Android device on the Wi-Fi / Ethernet interface.
     */
    fun getLocalIpAddress(): String {
        val candidates = mutableListOf<InterfaceCandidate>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val ifName = intf.name?.lowercase().orEmpty()
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        if (!hostAddress.startsWith("127.")) {
                            candidates.add(InterfaceCandidate(ifName, hostAddress))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return selectBestIpAddress(candidates)
    }

    /**
     * Pure IP selection helper so multi-NIC / VPN prioritization can be unit-tested without sockets.
     */
    internal fun selectBestIpAddress(candidates: List<InterfaceCandidate>): String {
        if (candidates.isEmpty()) return "127.0.0.1"

        val validCandidates = candidates.filter { candidate ->
            val name = candidate.interfaceName.lowercase()
            !IGNORED_INTERFACE_PREFIXES.any { name.startsWith(it) } &&
                !isCarrierGradeNat(candidate.ipAddress)
        }

        val pool = validCandidates.ifEmpty { candidates }
        return pool.minByOrNull { candidate ->
            val name = candidate.interfaceName.lowercase()
            val interfaceRank = when {
                PREFERRED_LAN_PREFIXES.any { name.startsWith(it) } -> 0
                else -> 1
            }
            val ipRank = when {
                isPrivateLanIp(candidate.ipAddress) -> 0
                !candidate.ipAddress.startsWith("169.254.") -> 1
                else -> 2
            }
            interfaceRank * 10 + ipRank
        }?.ipAddress ?: "127.0.0.1"
    }

    private fun isPrivateLanIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 192 && second == 168 ||
            first == 10 ||
            (first == 172 && second in 16..31)
    }

    private fun isCarrierGradeNat(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }
}
