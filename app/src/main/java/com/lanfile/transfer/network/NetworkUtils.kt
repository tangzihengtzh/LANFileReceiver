package com.lanfile.transfer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lanfile.transfer.model.LanAddress
import com.lanfile.transfer.util.AppLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/**
 * 运行时自动探测本机可被局域网访问的 IPv4 地址。
 *
 * 不写死 192.168.43.1；排除回环、IPv6、链路本地、VPN 与蜂窝数据接口。
 */
object NetworkUtils {

    fun detectLanAddresses(context: Context): List<LanAddress> {
        val found = LinkedHashMap<String, LanAddress>()

        collectFromInterfaces(found)
        collectFromConnectivityManager(context, found)

        return found.values.sortedWith(
            compareByDescending<LanAddress> { it.score }.thenBy { it.ip }
        )
    }

    private fun collectFromInterfaces(found: MutableMap<String, LanAddress>) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val score = scoreOfInterface(nif.name)
                if (score <= 0) continue
                for (address in nif.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (!isUsableIpv4(address)) continue
                    val ip = address.hostAddress ?: continue
                    found.putIfAbsent(
                        ip,
                        LanAddress(ip, nif.name, describeInterface(nif.name), score)
                    )
                }
            }
        } catch (t: Throwable) {
            AppLog.w(AppLog.NETWORK, "枚举网络接口失败：${t.message}", t)
        }
    }

    private fun collectFromConnectivityManager(
        context: Context,
        found: MutableMap<String, LanAddress>
    ) {
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val score = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 95
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 85
                    else -> 0
                }
                if (score <= 0) continue
                val linkProperties = manager.getLinkProperties(network) ?: continue
                val name = linkProperties.interfaceName ?: "wifi"
                for (linkAddress in linkProperties.linkAddresses) {
                    val address = linkAddress.address
                    if (address !is Inet4Address || !isUsableIpv4(address)) continue
                    val ip = address.hostAddress ?: continue
                    found.putIfAbsent(ip, LanAddress(ip, name, describeInterface(name), score))
                }
            }
        } catch (t: Throwable) {
            AppLog.w(AppLog.NETWORK, "通过 ConnectivityManager 探测地址失败：${t.message}", t)
        }
    }

    private fun isUsableIpv4(address: InetAddress): Boolean {
        return !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isAnyLocalAddress &&
            !address.isMulticastAddress
    }

    /**
     * 接口优先级：热点 > Wi-Fi > 有线 > USB 网络。
     * 返回 0 表示该接口不可用于局域网访问（VPN、蜂窝、P2P 等）。
     */
    private fun scoreOfInterface(rawName: String): Int {
        val name = rawName.lowercase(Locale.ROOT)
        return when {
            name.startsWith("ap") || name.contains("softap") || name.contains("swlan") -> 100
            name.startsWith("wlan") -> 90
            name.startsWith("eth") -> 85
            name.startsWith("rndis") || name.startsWith("usb") -> 70
            name.startsWith("tun") ||
                name.startsWith("ppp") ||
                name.startsWith("p2p") ||
                name.startsWith("rmnet") ||
                name.startsWith("dummy") ||
                name.startsWith("sit") ||
                name.startsWith("ip6") ||
                name.startsWith("clat") ||
                name.startsWith("vpn") -> 0
            else -> 20
        }
    }

    private fun describeInterface(rawName: String): String {
        val name = rawName.lowercase(Locale.ROOT)
        return when {
            name.startsWith("ap") || name.contains("softap") || name.contains("swlan") -> "手机热点"
            name.startsWith("wlan") -> "Wi-Fi"
            name.startsWith("eth") -> "有线网络"
            name.startsWith("rndis") || name.startsWith("usb") -> "USB 网络"
            else -> "局域网接口"
        }
    }
}
