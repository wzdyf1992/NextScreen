package com.nextscreen.app.dlna

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress,
    val local: InetAddress,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

class HttpResponse(
    val status: Int,
    val reason: String,
    val body: ByteArray = EMPTY,
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        val EMPTY = ByteArray(0)
        fun xml(s: String) = HttpResponse(200, "OK", s.toByteArray(Charsets.UTF_8), "text/xml; charset=\"utf-8\"")
        fun notFound() = HttpResponse(404, "Not Found")
    }
}

/** Minimal HTTP/1.1 server supporting the custom UPnP verbs (SUBSCRIBE / UNSUBSCRIBE). */
class HttpServer(private val handler: (HttpRequest) -> HttpResponse) {

    @Volatile private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val port: Int get() = server?.localPort ?: 0

    fun start(preferredPort: Int) {
        stop()
        var ss: ServerSocket? = null
        for (p in intArrayOf(preferredPort, preferredPort + 1, preferredPort + 2, preferredPort + 3, 0)) {
            try {
                ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p), 64)
                }
                break
            } catch (e: IOException) {
                Log.w(TAG, "port $p unavailable: ${e.message}")
            }
        }
        val s = ss ?: throw IOException("无法监听 HTTP 端口")
        server = s
        val exec = Executors.newCachedThreadPool()
        pool = exec
        thread(name = "ns-http-accept", isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    exec.execute { serve(c) }
                } catch (e: IOException) {
                    if (s.isClosed) break
                } catch (e: RejectedExecutionException) {
                    break
                }
            }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 15000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ').filter { it.isNotEmpty() }
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.ROOT)
                var target = parts[1]
                if (target.startsWith("http://", true)) {
                    target = target.substringAfter("://").let { r -> r.indexOf('/').let { if (it >= 0) r.substring(it) else "/" } }
                }
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] = line.substring(i + 1).trim()
                }
                if (headers["expect"]?.contains("100-continue", true) == true) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
                val body = readBody(input, headers)
                val req = HttpRequest(method, target.substringBefore('?'), headers, body, s.inetAddress, s.localAddress)
                val resp = try {
                    handler(req)
                } catch (e: Exception) {
                    Log.w(TAG, "handler error for $method ${req.path}", e)
                    HttpResponse(500, "Internal Server Error")
                }
                write(out, resp, method == "HEAD")
            }
        } catch (e: IOException) {
            // client went away
        } catch (e: Exception) {
            Log.w(TAG, "serve error", e)
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val len = headers["content-length"]?.trim()?.toIntOrNull()
        if (len != null && len > 0) {
            val size = len.coerceAtMost(MAX_BODY)
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n < 0) break
                read += n
            }
            return if (read == size) buf else buf.copyOf(read)
        }
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            val bos = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: break
                val chunk = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (chunk == 0) {
                    while (true) { val t = readLine(input); if (t.isNullOrEmpty()) break }
                    break
                }
                val buf = ByteArray(chunk)
                var read = 0
                while (read < chunk) {
                    val n = input.read(buf, read, chunk - read)
                    if (n < 0) break
                    read += n
                }
                bos.write(buf, 0, read)
                readLine(input)
                if (bos.size() > MAX_BODY) break
            }
            return bos.toByteArray()
        }
        return HttpResponse.EMPTY
    }

    private fun write(out: OutputStream, r: HttpResponse, head: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason).append("\r\n")
        if (r.contentType != null) sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Date: ").append(httpDate()).append("\r\n")
        sb.append("Server: ").append(Upnp.SERVER).append("\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!head && r.body.isNotEmpty()) out.write(r.body)
        out.flush()
    }

    companion object {
        private const val TAG = "HttpServer"
        private const val MAX_BODY = 4 * 1024 * 1024

        fun httpDate(): String {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("GMT")
            return f.format(Date())
        }

        fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(128)
            while (true) {
                val b = input.read()
                if (b == -1) {
                    return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.ISO_8859_1)
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                if (buf.size() > 16 * 1024) throw IOException("line too long")
            }
            return String(buf.toByteArray(), Charsets.ISO_8859_1)
        }
    }
}
