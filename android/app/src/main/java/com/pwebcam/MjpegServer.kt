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
import java.util.concurrent.atomic.AtomicLong

class MjpegServer(val port: Int = 8080) {

    interface ClientListener {
        fun onFirstClientConnected()
        fun onLastClientDisconnected()
    }

    var clientListener: ClientListener? = null

    /** Called whenever a client connects, with query params parsed from the request URL. */
    var onSettings: ((Map<String, String>) -> Unit)? = null

    /** Reply for a non-stream HTTP route (web UI page, JSON API). */
    class HttpReply(
        val body: ByteArray,
        val contentType: String,
        val status: String = "200 OK",
    )

    /**
     * Handles `/ui` and `/api/...`; return null for 404. A browser navigating to `/`
     * (Accept: text/html) is mapped to `/ui`. Plain stream clients (the Mac receiver) are untouched.
     */
    var httpHandler: ((path: String, params: Map<String, String>) -> HttpReply?)? = null

    /** Connected stream clients. */
    val clientCount: Int get() = activeCount.get()

    /** Total frames pushed to this server; the UI derives fps from deltas. */
    val framesPushed = AtomicLong()

    /** Total JPEG bytes produced (once per frame, not per client); the UI derives bitrate from deltas. */
    val bytesPushed = AtomicLong()

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
                // Parse off the accept thread so a stalled client can't block new connections.
                clientExecutor.execute { handle(socket) }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        var streaming = false
        try {
            // Latency tuning for Wi-Fi: send frames immediately, keep the kernel send buffer to
            // about one frame so stale data can't pile up behind a stalled link (our own
            // drop-oldest queue only protects the layer above it), and mark traffic AF41 (video).
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 64 * 1024
                socket.trafficClass = 0x88
            } catch (_: Exception) {}
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            socket.soTimeout = 0

            val path = firstLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val params = parseQueryParams(firstLine)
            val wantsPage = path == "/" && headers["accept"]?.contains("text/html") == true

            if (wantsPage || path == "/ui" || path.startsWith("/api/")) {
                val reply = httpHandler?.invoke(if (wantsPage) "/ui" else path, params)
                    ?: HttpReply("Not found".toByteArray(), "text/plain", "404 Not Found")
                val out = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 ${reply.status}\r\n" +
                     "Content-Type: ${reply.contentType}\r\n" +
                     "Content-Length: ${reply.body.size}\r\n" +
                     "Cache-Control: no-store\r\n" +
                     "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                out.write(reply.body)
                out.flush()
                return
            }

            // MJPEG stream (`/` or `/stream`).
            if (params.isNotEmpty()) onSettings?.invoke(params)
            val writer = ClientWriter(socket)
            clients.add(writer)
            streaming = true
            if (activeCount.getAndIncrement() == 0) clientListener?.onFirstClientConnected()
            writer.run()
        } catch (_: Exception) {
        } finally {
            if (!streaming) try { socket.close() } catch (_: Exception) {}
        }
    }

    fun pushFrame(jpeg: ByteArray) {
        framesPushed.incrementAndGet()
        bytesPushed.addAndGet(jpeg.size.toLong())
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

    inner class ClientWriter(private val socket: Socket) : Runnable {

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
