package dev.thenets.pocketd.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

data class NetworkAddress(
    val interfaceName: String,
    val interfaceType: String,
    val url: String
)

object NetworkUtils {

    fun getServerAddresses(port: Int): List<NetworkAddress> {
        val addresses = mutableListOf<NetworkAddress>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address) {
                        addresses += NetworkAddress(
                            interfaceName = iface.name,
                            interfaceType = guessInterfaceType(iface.name),
                            url = "http://${addr.hostAddress}:$port"
                        )
                    }
                }
            }
        } catch (_: SocketException) { }

        addresses += NetworkAddress(
            interfaceName = "lo",
            interfaceType = "Localhost",
            url = "http://127.0.0.1:$port"
        )
        return addresses
    }

    private fun guessInterfaceType(name: String): String = when {
        name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("eth")  -> "Ethernet"
        name.startsWith("rndis") -> "USB Tethering"
        name.startsWith("rmnet") -> "Mobile Data"
        name.startsWith("bt")   -> "Bluetooth"
        name.startsWith("p2p")  -> "Wi-Fi Direct"
        else                     -> "Network"
    }
}
