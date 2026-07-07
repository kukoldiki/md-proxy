package kuko.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/** A received UDP packet: application payload plus the address it (apparently) came from. */
data class UdpPacket(val data: ByteArray, val sender: SocketAddress)

/**
 * Wraps a [DatagramChannel] and transparently applies SOCKS5 UDP ASSOCIATE
 * framing (RFC 1928 section 7) when [proxied] is true, sending/receiving to/from
 * [relayAddress]. When [proxied] is false, packets are sent directly to/received
 * directly from [destAddress] - no proxy involved.
 *
 * The channel is non-blocking; [send] mirrors [DatagramChannel.send] semantics
 * (may send 0 bytes if the socket buffer is full) and [receive] returns null if
 * no packet is currently available.
 */
class SocksUdpChannel(
    val channel: DatagramChannel,
    val proxied: Boolean,
    val relayAddress: InetSocketAddress?,
    val destAddress: InetSocketAddress
) {

    /** Sends application payload. Returns number of payload bytes sent (0 if it would block). */
    fun send(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        return if (proxied) {
            requireNotNull(relayAddress) { "relayAddress must be set when proxied" }
            val header = buildSocks5UdpHeader(destAddress)
            val buf = ByteBuffer.allocate(header.size + length)
            buf.put(header)
            buf.put(data, offset, length)
            buf.flip()
            val sent = channel.send(buf, relayAddress)
            if (sent <= header.size) 0 else sent - header.size
        } else {
            val buf = ByteBuffer.wrap(data, offset, length)
            channel.send(buf, destAddress)
        }
    }

    /** Non-blocking receive. Returns null if no packet is currently available. */
    fun receive(bufferSize: Int = 65536): UdpPacket? {
        val buf = ByteBuffer.allocate(bufferSize)
        val from = channel.receive(buf) ?: return null
        buf.flip()
        return if (proxied) {
            unwrapSocks5Udp(buf)
        } else {
            val arr = ByteArray(buf.remaining())
            buf.get(arr)
            UdpPacket(arr, from)
        }
    }

    fun close() {
        runCatching { channel.close() }
    }

    private fun buildSocks5UdpHeader(dest: InetSocketAddress): ByteArray {
        val ipBytes = dest.address.address
        val atyp = if (ipBytes.size == 4) 0x01 else 0x04
        val buf = ByteBuffer.allocate(4 + ipBytes.size + 2)
        buf.put(0); buf.put(0) // RSV
        buf.put(0)             // FRAG (no fragmentation)
        buf.put(atyp.toByte())
        buf.put(ipBytes)
        buf.putShort(dest.port.toShort())
        return buf.array()
    }

    private fun unwrapSocks5Udp(buf: ByteBuffer): UdpPacket {
        buf.get(); buf.get() // RSV
        buf.get()            // FRAG (fragmentation not supported/ignored)
        val atyp = buf.get().toInt() and 0xFF
        val addrLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            else -> throw SocksException("Domain names in UDP replies are not supported")
        }
        val addrBytes = ByteArray(addrLen)
        buf.get(addrBytes)
        val port = buf.short.toInt() and 0xFFFF
        val srcAddr = InetAddress.getByAddress(addrBytes)
        val data = ByteArray(buf.remaining())
        buf.get(data)
        return UdpPacket(data, InetSocketAddress(srcAddr, port))
    }
}
