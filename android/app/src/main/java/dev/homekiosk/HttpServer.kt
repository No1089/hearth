package dev.homekiosk

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Minimal parsed HTTP/1.x request. Pure Kotlin so it can be unit-tested. */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>, // lower-cased names
) {
    companion object {
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 64
        private const val MAX_BODY = 64 * 1024

        /** Parses the request line + headers and drains a small body. Returns null on garbage. */
        fun parse(input: InputStream): HttpRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = HashMap<String, String>()
            for (i in 0 until MAX_HEADERS + 1) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) break
                if (i == MAX_HEADERS) return null
                val c = line.indexOf(':')
                if (c <= 0) continue
                headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
            }
            // Drain (and ignore) a body if one was sent, so the client sees a clean response.
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len in 1..MAX_BODY) {
                var left = len
                val tmp = ByteArray(1024)
                while (left > 0) {
                    val n = input.read(tmp, 0, minOf(tmp.size, left))
                    if (n < 0) break
                    left -= n
                }
            }
            val q = target.indexOf('?')
            val path = if (q >= 0) target.substring(0, q) else target
            val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
            return HttpRequest(method, decode(path), query, headers)
        }

        fun parseQuery(s: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (pair in s.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) out[decode(pair)] = ""
                else out[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
            }
            return out
        }

        private fun decode(s: String): String =
            try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                if (buf.size() > MAX_LINE) return null
            }
            return buf.toString("UTF-8")
        }
    }

    /** Token from `Authorization: Bearer x` or `?token=x`. */
    fun presentedToken(): String? {
        val auth = headers["authorization"]
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) return auth.substring(7).trim()
        return query["token"]
    }

    /** Constant-time token check. Always false when no token is configured. */
    fun isAuthorized(configured: String): Boolean {
        if (configured.isEmpty()) return false
        val given = presentedToken() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), configured.toByteArray())
    }
}

data class HttpResponse(val status: Int, val body: String, val contentType: String = "application/json") {
    fun write(out: OutputStream) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}

/**
 * Tiny blocking ServerSocket server: one accept thread + a small worker pool.
 * Every connection is one request (Connection: close).
 */
class KioskHttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
) {
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null

    @Volatile var lastError: String? = null
        private set

    fun start() {
        if (socket != null) return
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 16)
            }
        } catch (e: Exception) {
            lastError = "bind :$port failed: ${e.message}"
            return
        }
        socket = ss
        val workers = Executors.newFixedThreadPool(2)
        pool = workers
        acceptThread = Thread({
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: SocketException) { break } catch (_: Exception) { continue }
                try {
                    workers.execute { serve(client) }
                } catch (_: Exception) {
                    runCatching { client.close() }
                }
            }
        }, "kiosk-http").apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
        acceptThread = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 5000
                val req = HttpRequest.parse(BufferedInputStream(s.getInputStream()))
                val resp = if (req == null) HttpResponse(400, """{"error":"bad request"}""")
                else try { handler(req) } catch (e: Exception) {
                    HttpResponse(500, """{"error":"internal"}""")
                }
                resp.write(s.getOutputStream())
            } catch (_: Exception) {
            }
        }
    }
}
