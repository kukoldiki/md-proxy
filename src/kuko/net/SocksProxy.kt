package kuko.net

import arc.util.Log
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch

/** Thrown for any SOCKS protocol / connection error. */
class SocksException(message: String) : IOException(message)

enum class SocksState { IDLE, CONNECTING, CONNECTED, FAILED }

/**
 * SOCKS4 / SOCKS5 client (no authentication) with TCP CONNECT tunnel support
 * and SOCKS5 UDP ASSOCIATE support (with automatic fallback to plain/direct
 * UDP if the proxy does not support UDP ASSOCIATE, or if it fails).
 *
 * Usage:
 * ```
 * val proxy = SocksProxy("proxyip", proxyport, "destip", destport, 5)
 * proxy.block()          // wait until connected (throws on failure)
 * val tcp = proxy.getTcp()
 * val udp = proxy.getUdp()
 * ```
 *
 * or non-blocking:
 * ```
 * val proxy = SocksProxy("proxyip", proxyport, "destip", destport, 5)
 * proxy.connect()        // returns immediately
 * // ... later, poll:
 * if (proxy.isConnected()) { ... } else if (proxy.isFailed()) { ... }
 * ```
 */
class SocksProxy(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val destHost: String,
    private val destPort: Int,
    private val socksVersion: Int,
    private val connectTimeoutMs: Long = 10_000,
    private val debug: Boolean = false
) {

    init {
        require(socksVersion == 4 || socksVersion == 5) { "socksVersion must be 4 or 5" }
    }

    private fun log(msg: String) {
        if (debug) Log.info("[SocksProxy] $msg")
    }

    private fun hex(buf: ByteBuffer): String {
        val dup = buf.duplicate()
        dup.flip()
        val sb = StringBuilder()
        while (dup.hasRemaining()) sb.append(String.format("%02x ", dup.get()))
        return sb.toString().trim()
    }

    @Volatile private var state: SocksState = SocksState.IDLE
    @Volatile private var lastError: Throwable? = null
    private val latch = CountDownLatch(1)
    private var worker: Thread? = null

    @Volatile private var tcpChannel: SocketChannel? = null
    @Volatile private var udpControlChannel: SocketChannel? = null
    @Volatile private var udpChannelWrapper: SocksUdpChannel? = null
    @Volatile private var udpProxied: Boolean = false

    /** Starts connecting in a background thread. Does not block the caller. */
    fun connect() {
        synchronized(this) {
            if (worker != null) return
            state = SocksState.CONNECTING
            worker = Thread({ runConnect() }, "SocksProxy-connect-$proxyHost:$proxyPort").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Starts connecting (if not already) and blocks the calling thread until it finishes.
     *  Throws [SocksException] / [IOException] on failure. */
    fun block() {
        connect()
        latch.await()
        if (state == SocksState.FAILED) {
            throw lastError ?: SocksException("Unknown connection error")
        }
    }

    fun isConnected(): Boolean = state == SocksState.CONNECTED
    fun isConnecting(): Boolean = state == SocksState.CONNECTING
    fun isFailed(): Boolean = state == SocksState.FAILED
    fun getError(): Throwable? = lastError

    /** True if the proxy accepted UDP ASSOCIATE and UDP traffic is being relayed through it.
     *  False means UDP traffic (from [getUdp]) is sent directly, bypassing the proxy. */
    fun isUdpProxied(): Boolean = udpProxied

    /** Returns the connected, non-blocking TCP tunnel channel, or null if not connected yet / failed. */
    fun getTcp(): SocketChannel? = if (state == SocksState.CONNECTED) tcpChannel else null

    /** Returns the UDP channel wrapper (proxied via UDP ASSOCIATE, or direct fallback), or null if not ready. */
    fun getUdp(): SocksUdpChannel? = if (state == SocksState.CONNECTED) udpChannelWrapper else null

    fun close() {
        runCatching { tcpChannel?.close() }
        runCatching { udpControlChannel?.close() }
        runCatching { udpChannelWrapper?.close() }
    }

    // ------------------------------------------------------------------
    // Connection orchestration
    // ------------------------------------------------------------------

    private fun runConnect() {
        try {
            log("=== opening TCP tunnel ===")
            tcpChannel = openTcpTunnel()
            log("=== opening UDP channel ===")
            udpChannelWrapper = openUdpChannel()
            state = SocksState.CONNECTED
            log("=== connected: udpProxied=$udpProxied ===")
        } catch (e: Throwable) {
            lastError = e
            state = SocksState.FAILED
            log("=== connect FAILED: ${e::class.simpleName}: ${e.message} ===")
            close()
        } finally {
            latch.countDown()
        }
    }

    /** Opens a dedicated TCP connection to the proxy and issues a CONNECT command to destHost:destPort. */
    private fun openTcpTunnel(): SocketChannel {
        val channel = SocketChannel.open()
        channel.configureBlocking(false)
        Selector.open().use { selector ->
            try {
                connectBlocking(channel, InetSocketAddress(proxyHost, proxyPort), selector)
                if (socksVersion == 5) {
                    socks5Handshake(channel, selector)
                    socks5Request(channel, selector, cmd = 0x01, destHost, destPort)
                } else {
                    socks4Request(channel, selector, destHost, destPort)
                }
                return channel
            } catch (e: Throwable) {
                runCatching { channel.close() }
                throw e
            }
        }
    }

    /** Tries SOCKS5 UDP ASSOCIATE on a dedicated control connection; falls back to direct UDP
     *  (no proxy) if unsupported (SOCKS4) or if the attempt fails. */
    private fun openUdpChannel(): SocksUdpChannel {
        if (socksVersion == 5) {
            try {
                return openUdpAssociate()
            } catch (_: Exception) {
                udpProxied = false
                runCatching { udpControlChannel?.close() }
                udpControlChannel = null
            }
        }
        udpProxied = false
        return openDirectUdp()
    }

    private fun openUdpAssociate(): SocksUdpChannel {
        val channel = SocketChannel.open()
        channel.configureBlocking(false)
        val (relayHost, relayPort) = Selector.open().use { selector ->
            connectBlocking(channel, InetSocketAddress(proxyHost, proxyPort), selector)
            socks5Handshake(channel, selector)
            // Client's own source address is generally unknown/irrelevant here; 0.0.0.0:0 is standard.
            socks5Request(channel, selector, cmd = 0x03, "0.0.0.0", 0)
        }
        // Keep this TCP connection open for the whole lifetime of the UDP association.
        udpControlChannel = channel

        val fixedRelayHost = if (relayHost == "0.0.0.0" || relayHost == "0:0:0:0:0:0:0:0") proxyHost else relayHost

        val dc = DatagramChannel.open()
        dc.configureBlocking(false)
        udpProxied = true
        return SocksUdpChannel(
            channel = dc,
            proxied = true,
            relayAddress = InetSocketAddress(fixedRelayHost, relayPort),
            destAddress = InetSocketAddress(destHost, destPort)
        )
    }

    private fun openDirectUdp(): SocksUdpChannel {
        val dc = DatagramChannel.open()
        dc.configureBlocking(false)
        return SocksUdpChannel(
            channel = dc,
            proxied = false,
            relayAddress = null,
            destAddress = InetSocketAddress(destHost, destPort)
        )
    }

    // ------------------------------------------------------------------
    // Non-blocking selector-driven I/O helpers
    // ------------------------------------------------------------------

    private fun connectBlocking(channel: SocketChannel, addr: InetSocketAddress, selector: Selector) {
        log("TCP connect -> $addr")
        channel.connect(addr)
        val key = channel.register(selector, SelectionKey.OP_CONNECT)
        val deadline = System.currentTimeMillis() + connectTimeoutMs
        try {
            while (!channel.finishConnect()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw SocksException("Timeout connecting to proxy $proxyHost:$proxyPort")
                selector.selectedKeys().clear()
                if (selector.select(remaining) == 0) throw SocksException("Timeout connecting to proxy $proxyHost:$proxyPort")
            }
        } finally {
            key.interestOps(0)
            selector.selectedKeys().clear()
        }
        log("TCP connect established")
    }

    private fun writeFully(channel: SocketChannel, buf: ByteBuffer, selector: Selector) {
        log("write ${buf.remaining()} bytes: ${hex(buf)}")
        val key = channel.register(selector, SelectionKey.OP_WRITE)
        val deadline = System.currentTimeMillis() + connectTimeoutMs
        try {
            while (buf.hasRemaining()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw SocksException("Timeout writing to proxy (${buf.remaining()} bytes left)")
                selector.selectedKeys().clear()
                key.interestOps(SelectionKey.OP_WRITE)
                val ready = selector.select(remaining)
                if (ready == 0) throw SocksException("Timeout writing to proxy (${buf.remaining()} bytes left)")
                if (key.isValid && key.isWritable) {
                    val n = channel.write(buf)
                    if (n < 0) throw SocksException("Proxy closed the connection while writing")
                }
            }
        } finally {
            key.interestOps(0)
            selector.selectedKeys().clear()
        }
        log("write complete")
    }

    private fun readFully(channel: SocketChannel, buf: ByteBuffer, selector: Selector) {
        log("waiting to read ${buf.remaining()} bytes")
        val key = channel.register(selector, SelectionKey.OP_READ)
        val deadline = System.currentTimeMillis() + connectTimeoutMs
        try {
            while (buf.hasRemaining()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw SocksException("Timeout reading from proxy (${buf.remaining()} bytes left)")
                selector.selectedKeys().clear()
                key.interestOps(SelectionKey.OP_READ)
                val ready = selector.select(remaining)
                if (ready == 0) throw SocksException("Timeout reading from proxy (${buf.remaining()} bytes left)")
                if (key.isValid && key.isReadable) {
                    val n = channel.read(buf)
                    if (n == -1) throw SocksException("Proxy closed the connection while reading")
                }
            }
        } finally {
            key.interestOps(0)
            selector.selectedKeys().clear()
        }
        log("read complete: ${hex(buf)}")
    }

    // ------------------------------------------------------------------
    // SOCKS5
    // ------------------------------------------------------------------

    private fun socks5Handshake(channel: SocketChannel, selector: Selector) {
        log("SOCKS5 handshake: sending greeting")
        // version 5, 1 method offered: 0x00 = no authentication
        writeFully(channel, ByteBuffer.wrap(byteArrayOf(0x05, 0x01, 0x00)), selector)
        val resp = ByteBuffer.allocate(2)
        readFully(channel, resp, selector)
        resp.flip()
        val ver = resp.get().toInt() and 0xFF
        val method = resp.get().toInt() and 0xFF
        if (ver != 0x05) throw SocksException("Unexpected SOCKS version in handshake reply: $ver")
        if (method != 0x00) throw SocksException("Proxy requires authentication (method=$method), which is not supported")
        log("SOCKS5 handshake OK, method=$method")
    }

    /** Sends a SOCKS5 request (CONNECT/UDP ASSOCIATE) and returns the bound (host, port) from the reply. */
    private fun socks5Request(channel: SocketChannel, selector: Selector, cmd: Int, host: String, port: Int): Pair<String, Int> {
        log("SOCKS5 request: cmd=$cmd host=$host port=$port")
        val req = buildSocks5Request(cmd, host, port)
        writeFully(channel, req, selector)

        val head = ByteBuffer.allocate(4)
        readFully(channel, head, selector)
        head.flip()
        val ver = head.get().toInt() and 0xFF
        val rep = head.get().toInt() and 0xFF
        head.get() // RSV
        val atyp = head.get().toInt() and 0xFF
        if (ver != 0x05) throw SocksException("Unexpected SOCKS version in reply: $ver")
        if (rep != 0x00) throw SocksException("SOCKS5 request failed, reply code=$rep (${socks5ReplyName(rep)})")

        val bound = readSocks5Address(channel, selector, atyp)
        log("SOCKS5 request OK: bound=${bound.first}:${bound.second}")
        return bound
    }

    private fun buildSocks5Request(cmd: Int, host: String, port: Int): ByteBuffer {
        val ipv4 = parseIPv4(host)
        val ipv6 = if (ipv4 == null) parseIPv6(host) else null
        val atyp: Int
        val addrBytes: ByteArray
        when {
            ipv4 != null -> { atyp = 0x01; addrBytes = ipv4 }
            ipv6 != null -> { atyp = 0x04; addrBytes = ipv6 }
            else -> {
                val hostBytes = host.toByteArray(Charsets.US_ASCII)
                atyp = 0x03
                addrBytes = byteArrayOf(hostBytes.size.toByte()) + hostBytes
            }
        }
        val buf = ByteBuffer.allocate(3 + 1 + addrBytes.size + 2)
        buf.put(0x05); buf.put(cmd.toByte()); buf.put(0x00)
        buf.put(atyp.toByte())
        buf.put(addrBytes)
        buf.putShort(port.toShort())
        buf.flip()
        return buf
    }

    private fun readSocks5Address(channel: SocketChannel, selector: Selector, atyp: Int): Pair<String, Int> {
        return when (atyp) {
            0x01 -> {
                val buf = ByteBuffer.allocate(4 + 2)
                readFully(channel, buf, selector)
                buf.flip()
                val bytes = ByteArray(4); buf.get(bytes)
                Pair(InetAddress.getByAddress(bytes).hostAddress, buf.short.toInt() and 0xFFFF)
            }
            0x04 -> {
                val buf = ByteBuffer.allocate(16 + 2)
                readFully(channel, buf, selector)
                buf.flip()
                val bytes = ByteArray(16); buf.get(bytes)
                Pair(InetAddress.getByAddress(bytes).hostAddress, buf.short.toInt() and 0xFFFF)
            }
            0x03 -> {
                val lenBuf = ByteBuffer.allocate(1)
                readFully(channel, lenBuf, selector)
                lenBuf.flip()
                val len = lenBuf.get().toInt() and 0xFF
                val buf = ByteBuffer.allocate(len + 2)
                readFully(channel, buf, selector)
                buf.flip()
                val hostBytes = ByteArray(len); buf.get(hostBytes)
                Pair(String(hostBytes, Charsets.US_ASCII), buf.short.toInt() and 0xFFFF)
            }
            else -> throw SocksException("Unsupported address type in reply: $atyp")
        }
    }

    private fun socks5ReplyName(code: Int): String = when (code) {
        0x01 -> "general SOCKS server failure"
        0x02 -> "connection not allowed by ruleset"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "unknown"
    }

    // ------------------------------------------------------------------
    // SOCKS4
    // ------------------------------------------------------------------

    private fun socks4Request(channel: SocketChannel, selector: Selector, host: String, port: Int) {
        val ip = parseIPv4(host) ?: InetAddress.getByName(host).address
        if (ip.size != 4) throw SocksException("SOCKS4 only supports IPv4 addresses")
        val buf = ByteBuffer.allocate(9)
        buf.put(0x04)
        buf.put(0x01) // CONNECT
        buf.putShort(port.toShort())
        buf.put(ip)
        buf.put(0x00) // empty user-id, null terminated
        buf.flip()
        writeFully(channel, buf, selector)

        val resp = ByteBuffer.allocate(8)
        readFully(channel, resp, selector)
        resp.flip()
        resp.get() // VN, should be 0x00
        val rep = resp.get().toInt() and 0xFF
        if (rep != 0x5A) throw SocksException("SOCKS4 request rejected, code=$rep (${socks4ReplyName(rep)})")
    }

    private fun socks4ReplyName(code: Int): String = when (code) {
        0x5B -> "request rejected or failed"
        0x5C -> "request rejected (no identd)"
        0x5D -> "request rejected (identd mismatch)"
        else -> "unknown"
    }

    // ------------------------------------------------------------------
    // Address parsing helpers
    // ------------------------------------------------------------------

    private fun parseIPv4(host: String): ByteArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        return try {
            ByteArray(4) { i ->
                val v = parts[i].toInt()
                if (v !in 0..255) throw NumberFormatException()
                v.toByte()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIPv6(host: String): ByteArray? {
        if (!host.contains(":")) return null
        return try {
            val addr = InetAddress.getByName(host)
            if (addr is Inet6Address) addr.address else null
        } catch (e: Exception) {
            null
        }
    }
}
