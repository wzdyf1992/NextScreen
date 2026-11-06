package com.nextscreen.app.dlna

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetUtils {

    /** Best LAN IPv4 address of this device (Wi-Fi preferred, then hotspot, then ethernet). */
    fun localIpv4(): Inet4Address? = candidates().firstOrNull()?.second

    fun interfaceOf(addr: InetAddress?): NetworkInterface? = try {
        if (addr == null) null else NetworkInterface.getByInetAddress(addr)
    } catch (e: Exception) {
        null
    }

    fun candidates(): List<Pair<NetworkInterface, Inet4Address>> {
        val res = ArrayList<Pair<NetworkInterface, Inet4Address>>()
        try {
            val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) res += ni to a
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return res.sortedBy { score(it.first.name, it.second) }
    }

    private fun score(name: String, a: Inet4Address): Int {
        val n = name.lowercase()
        val base = when {
            n.startsWith("wlan") -> 0
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> 1
            n.startsWith("eth") -> 2
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") ||
                n.startsWith("ppp") || n.startsWith("dummy") -> 9
            else -> 5
        }
        return base * 2 + if (a.isSiteLocalAddress) 0 else 1
    }

    /** Local address that is on the same subnet as [remote], so replies carry a reachable LOCATION. */
    fun addressFor(remote: InetAddress?): Inet4Address? {
        if (remote !is Inet4Address) return null
        val r = toInt(remote.address)
        try {
            for ((ni, _) in candidates()) {
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val prefix = ia.networkPrefixLength.toInt()
                    val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32)))
                    if ((toInt(a.address) and mask) == (r and mask)) return a
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
}
