package com.justnels.agenticdroid.util

import java.net.NetworkInterface
import java.net.Inet4Address

object NetworkUtil {
    /**
     * Returns a list of all non-loopback IPv4 addresses assigned to this device.
     */
    fun getLocalIpv4Addresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { (it is Inet4Address) && !it.isLoopbackAddress }
                .map { it.hostAddress }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Returns the first IPv4 address in the Tailscale range (100.64.0.0/10), or null.
     */
    fun getTailscaleAddress(): String? {
        return getLocalIpv4Addresses().find { it.startsWith("100.") }
    }

    /**
     * Returns a "preferred" address: Tailscale if available, otherwise the first local one.
     */
    fun getPreferredAddress(): String? {
        return getTailscaleAddress() ?: getLocalIpv4Addresses().firstOrNull()
    }
}
