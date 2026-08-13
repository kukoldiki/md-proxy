package kuko

import java.net.InetAddress



fun isLocal(ip: String): Boolean {
    val addr = InetAddress.getByName(ip)

    return addr.isLoopbackAddress ||
                addr.isAnyLocalAddress ||
                addr.isSiteLocalAddress
}