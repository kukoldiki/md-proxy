package kuko.net

import arc.Core
import arc.util.Log
import kuko.CVars
import kuko.CVars.rand
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channel
import java.nio.channels.DatagramChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мод клиентский, на одного игрока — поэтому активной может быть только
 * ОДНА прокси-сессия одновременно. listen() автоматически закрывает
 * предыдущую (если она почему-то ещё жива) перед стартом новой.
 */
object ProxyListener {

    private val sessions = ConcurrentHashMap<Int, ProxySession>()

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "proxy-listener-worker").apply { isDaemon = true }
    }

    /**
     * Стартует новую прокси-сессию до destIp:destPort.
     * Возвращает локальный порт, на который должен подключиться клиент игры
     * (localhost:port).
     *
     * Если подключение к прокси не удалось (proxy == null или ошибка в
     * proxy.block()), слушатели просто не запускаются, но порт всё равно
     * возвращается как ни в чём не бывало — "тупая" заглушка без реальной
     * пересылки трафика.
     */
    @Synchronized
    fun listen(destIp: String, destPort: Int): Int {
        // одна активная сессия за раз - если что-то осталось висеть с прошлого
        // коннекта (например движок игры не успел разорвать сокет), гасим это
        if (sessions.isNotEmpty()) {
            Log.warn("[proxy] listen() called with ${sessions.size} session(s) still active, closing them first")
            closeAll()
        }

        var listenPort: Int
        var server: ServerSocketChannel

        // ищем свободный порт в диапазоне, с защитой от коллизий/занятости
        while (true) {
            listenPort = rand.nextInt(6500, 6900)
            if (sessions.containsKey(listenPort)) continue

            server = ServerSocketChannel.open()
            try {
                server.bind(InetSocketAddress("0.0.0.0", listenPort))
                break
            } catch (e: Exception) {
                closeQuietly(server)
            }
        }

        val udp = DatagramChannel.open()
        try {
            udp.bind(InetSocketAddress("0.0.0.0", listenPort))
        } catch (e: Exception) {
            closeQuietly(server)
            closeQuietly(udp)
            throw e
        }

        val session = ProxySession(listenPort, destIp, destPort, server, udp, executor) {
            // колбэк самоочистки сессии из мапы, когда она сама себя закрыла
            sessions.remove(listenPort)
        }

        sessions[listenPort] = session

        val started = try {
            session.start()
        } catch (e: Exception) {
            // сюда попадаем только при совсем неожиданных ошибках - сама
            // ошибка подключения к прокси уже перехвачена внутри start().
            // Порт всё равно "тупо" отдаём клиенту - слушателей просто не
            // будет, сокеты останутся висеть открытыми.
            Log.err("[proxy] unexpected error starting session on $listenPort, leaving port hanging", e)
            false
        }

        if (!started) {
            Log.warn("[proxy:$listenPort] no listeners running (proxy unavailable), port is a dummy stub")
        }

        Log.info("[proxy] listening on $listenPort -> $destIp:$destPort")
        return listenPort
    }

    /** Разрывает конкретную сессию по порту. */
    @Synchronized
    fun close(listenPort: Int) {
        sessions.remove(listenPort)?.close()
    }

    /**
     * Останавливает текущую (единственную) активную сессию, если она есть.
     * Дергай это из обработчика дисконнекта/выхода игрока в моде -
     * дополнительно к авто-закрытию по разрыву TCP-сокета.
     */
    @Synchronized
    fun stop() {
        closeAll()
    }

    /** Разрывает все активные сессии. */
    @Synchronized
    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    fun isActive(listenPort: Int): Boolean = sessions.containsKey(listenPort)

    /** Есть ли вообще активная сессия (для однопользовательского мода). */
    fun hasActiveSession(): Boolean = sessions.isNotEmpty()

    private fun closeQuietly(c: Channel) {
        try {
            c.close()
        } catch (_: Exception) {
        }
    }
}

private class ProxySession(
    private val listenPort: Int,
    destIp: String,
    destPort: Int,
    private val server: ServerSocketChannel,
    private val udp: DatagramChannel,
    private val executor: ExecutorService,
    private val onClosed: () -> Unit
) : Closeable {

    private val proxy = runCatching {
        SocksProxy(Core.settings.getString("proxyip"), CVars.proxyPort, destIp, destPort, CVars.socksVersion)
    }.onFailure {
        Log.err("Failed to connect to proxy!")
    }.getOrNull()

    private val alive = AtomicBoolean(true)

    // getUdp() кэшируем вручную (не lateinit/lazy, т.к. может вернуть null,
    // если прокси ещё не готов на момент первого обращения).
    @Volatile
    private var proxyUdp: SocksUdpChannel? = null

    private fun getProxyUdp(): SocksUdpChannel {
        proxyUdp?.let { return it }
        synchronized(this) {
            proxyUdp?.let { return it }
            val created = requireNotNull(proxy?.getUdp()) { "proxy.getUdp() returned null" }
            proxyUdp = created
            return created
        }
    }

    @Volatile
    private var clientTcp: SocketChannel? = null

    @Volatile
    private var clientUdpAddr: SocketAddress? = null

    /**
     * Возвращает true, если слушатели реально запустились (и трафик будет
     * пересылаться), false — если проверка/подключение к прокси не удались
     * и порт остаётся "тупой" заглушкой без запущенных циклов.
     */
    fun start(): Boolean {
        if (proxy == null) {
            Log.err("[proxy:$listenPort] proxy unavailable, leaving port hanging")
            return false
        }

        try {
            proxy.block()
        } catch (e: Exception) {
            Log.err("[proxy:$listenPort] proxy connect failed, leaving port hanging", e)
            return false
        }

        executor.execute(::acceptLoop)
        executor.execute(::udpClientToProxyLoop)
        executor.execute(::udpProxyToClientLoop)

        return true
    }

    private fun acceptLoop() {
        try {
            // одна игровая сессия = одно TCP-соединение клиента, поэтому
            // после первого accept просто ждём завершения (не крутим цикл вхолостую)
            val client = server.accept()
            if (client == null || !alive.get()) {
                closeQuietly(client)
                return
            }

            clientTcp = client
            Log.info("[proxy:$listenPort] client connected: ${client.remoteAddress}")
            handleTcp(client)
        } catch (e: Exception) {
            if (alive.get()) Log.err("[proxy:$listenPort] accept error", e)
        }
    }

    private fun handleTcp(client: SocketChannel) {
        proxy ?: return
        val target: SocketChannel = try {
            requireNotNull(proxy.getTcp()) { "proxy.getTcp() returned null" }
        } catch (e: Exception) {
            Log.err("[proxy:$listenPort] tcp proxy connect failed", e)
            close()
            return
        }

        val done = AtomicBoolean(false)
        val finish = {
            if (done.compareAndSet(false, true)) close()
        }

        executor.execute {
            try {
                pipeTcp(client, target)
            } finally {
                finish()
            }
        }

        executor.execute {
            try {
                pipeTcp(target, client)
            } finally {
                finish()
            }
        }
    }

    private fun pipeTcp(input: SocketChannel, output: SocketChannel) {
        val buffer = ByteBuffer.allocate(8192)
        try {
            while (alive.get()) {
                buffer.clear()
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) continue

                buffer.flip()
                while (buffer.hasRemaining()) output.write(buffer)
            }
        } catch (e: Exception) {
            if (alive.get()) Log.debug("[proxy:$listenPort] tcp pipe closed: ${e.message}")
        }
    }

    /** Пакеты от игрового клиента -> в SOCKS-прокси. */
    private fun udpClientToProxyLoop() {
        val buffer = ByteBuffer.allocate(65535)
        try {
            while (alive.get()) {
                buffer.clear()
                val sender = udp.receive(buffer) ?: continue
                clientUdpAddr = sender

                buffer.flip()
                val data = ByteArray(buffer.remaining())
                buffer.get(data)

                try {
                    getProxyUdp().send(data)
                } catch (e: Exception) {
                    Log.err("[proxy:$listenPort] udp send to proxy failed", e)
                }
            }
        } catch (e: Exception) {
            if (alive.get()) Log.debug("[proxy:$listenPort] udp client->proxy loop closed: ${e.message}")
        }
    }

    /**
     * Пакеты от SOCKS-прокси (ответы реального сервера) -> обратно игровому клиенту.
     * SocksUdpChannel.receive() неблокирующий, поэтому крутим лёгкий poll-цикл.
     */
    private fun udpProxyToClientLoop() {
        try {
            while (alive.get()) {
                val packet = getProxyUdp().receive()
                if (packet == null) {
                    // нет данных прямо сейчас - не жрём CPU busy-loop'ом
                    Thread.sleep(1)
                    continue
                }

                val addr = clientUdpAddr
                if (addr == null) {
                    // клиент ещё не прислал ни одного пакета - адресовать некуда
                    continue
                }

                try {
                    udp.send(ByteBuffer.wrap(packet.data), addr)
                } catch (e: Exception) {
                    Log.err("[proxy:$listenPort] udp send to client failed", e)
                }
            }
        } catch (e: InterruptedException) {
            // нормальное завершение при остановке
        } catch (e: Exception) {
            if (alive.get()) Log.debug("[proxy:$listenPort] udp proxy->client loop closed: ${e.message}")
        }
    }

    override fun close() {
        if (!alive.compareAndSet(true, false)) return

        Log.info("[proxy:$listenPort] session closed")

        closeQuietly(server)
        closeQuietly(udp)
        closeQuietly(clientTcp)

        // proxyUdp мог не успеть инициализироваться, если UDP-пакетов не было
        proxyUdp?.let { runCatching { it.close() } }

        proxy?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }

        onClosed()
    }

    private fun closeQuietly(c: Channel?) {
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }
}