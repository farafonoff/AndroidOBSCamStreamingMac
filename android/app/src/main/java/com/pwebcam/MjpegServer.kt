package com.pwebcam

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MjpegServer(val port: Int = 8080) {

    interface ClientListener {
        fun onFirstClientConnected()
        fun onLastClientDisconnected()
    }

    var clientListener: ClientListener? = null

    /** Called whenever a client connects, with query params parsed from the request URL. */
    var onSettings: ((Map<String, String>) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientWriter>()
    private val activeCount = AtomicInteger(0)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        acceptExecutor.execute { acceptLoop() }
    }

    fun stop() {
        serverSocket?.close()
        clients.forEach { it.close() }
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (!ss.isClosed) {
            try {
                val socket = ss.accept()
                // One BufferedReader for the entire request — passed to ClientWriter so it
                // continues draining headers from the same buffer (no double-read bug).
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val firstLine = reader.readLine() ?: ""
                val params = parseQueryParams(firstLine)
                if (params.isNotEmpty()) onSettings?.invoke(params)

                val writer = ClientWriter(socket, reader)
                clients.add(writer)
                if (activeCount.getAndIncrement() == 0) {
                    clientListener?.onFirstClientConnected()
                }
                clientExecutor.execute(writer)
            } catch (_: Exception) {
                break
            }
        }
    }

    fun pushFrame(jpeg: ByteArray) {
        if (clients.isEmpty()) return
        val dead = mutableListOf<ClientWriter>()
        for (client in clients) {
            if (!client.offer(jpeg)) dead.add(client)
        }
        for (d in dead) clients.remove(d)
    }

    private fun onClientGone() {
        if (activeCount.decrementAndGet() == 0) {
            clientListener?.onLastClientDisconnected()
        }
    }

    /** Parse `?key=value&key2=value2` from a raw HTTP request line. */
    private fun parseQueryParams(requestLine: String): Map<String, String> {
        val qStart = requestLine.indexOf('?')
        val qEnd   = requestLine.lastIndexOf(' ')
        if (qStart == -1 || qEnd <= qStart) return emptyMap()
        return requestLine.substring(qStart + 1, qEnd)
            .split('&')
            .mapNotNull { pair ->
                val (k, v) = pair.split('=', limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else return@mapNotNull null
                }
                k to v
            }.toMap()
    }

    inner class ClientWriter(
        private val socket: Socket,
        /** Shared reader from acceptLoop — already past the first request line. */
        private val reader: BufferedReader,
    ) : Runnable {

        private val queue = ArrayBlockingQueue<ByteArray>(2)
        @Volatile private var alive = true

        fun offer(jpeg: ByteArray): Boolean {
            if (!alive) return false
            queue.poll()
            queue.offer(jpeg)
            return true
        }

        override fun run() {
            try {
                // Drain remaining HTTP headers (first line already consumed in acceptLoop)
                while (reader.readLine()?.isNotEmpty() == true) {}

                val out = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                     "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                     "Cache-Control: no-cache, no-store\r\n" +
                     "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                out.flush()

                while (alive && !socket.isClosed) {
                    val jpeg = queue.poll(2, TimeUnit.SECONDS) ?: continue
                    val header =
                        "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(jpeg)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                alive = false
                clients.remove(this)
                try { socket.close() } catch (_: Exception) {}
                onClientGone()
            }
        }

        fun close() {
            alive = false
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
