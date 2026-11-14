package com.bigeyes.tv.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun testSelectBestIpAddressPrefersWlanRfc1918OverVpnAndCellular() {
        val candidates = listOf(
            NetworkUtils.InterfaceCandidate("tun0", "10.8.0.2"),
            NetworkUtils.InterfaceCandidate("rmnet_data0", "100.70.12.3"),
            NetworkUtils.InterfaceCandidate("p2p0", "192.168.49.1"),
            NetworkUtils.InterfaceCandidate("wlan0", "192.168.31.105")
        )
        assertEquals("192.168.31.105", NetworkUtils.selectBestIpAddress(candidates))
    }

    @Test
    fun testSelectBestIpAddressPrefersEthOverLinkLocalAndCgnat() {
        val candidates = listOf(
            NetworkUtils.InterfaceCandidate("eth1", "169.254.10.20"),
            NetworkUtils.InterfaceCandidate("eth0", "100.64.1.5"),
            NetworkUtils.InterfaceCandidate("en0", "172.16.8.44")
        )
        assertEquals("172.16.8.44", NetworkUtils.selectBestIpAddress(candidates))
    }

    @Test
    fun testSelectBestIpAddressFallsBackGracefullyWhenOnlyFilteredOrEmpty() {
        assertEquals("127.0.0.1", NetworkUtils.selectBestIpAddress(emptyList()))
        val onlyVpn = listOf(NetworkUtils.InterfaceCandidate("tun0", "10.9.0.1"))
        assertEquals("10.9.0.1", NetworkUtils.selectBestIpAddress(onlyVpn))
    }
}
