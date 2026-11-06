package com.nextscreen.app.dlna

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** GENA eventing (SUBSCRIBE / UNSUBSCRIBE / NOTIFY) for the renderer services. */
class EventManager {

    private class Sub(val sid: String, val service: String, val callbacks: List<String>, @Volatile var expiry: Long) {
        val seq = AtomicLong(0)
    }

    private val subs = ConcurrentHashMap<String, Sub>()
    private val lastBody = ConcurrentHashMap<String, String>()
    private val exec = ScheduledThreadPoolExecutor(1).apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    fun subscribe(service: String, req: HttpRequest, initial: () -> String): HttpResponse {
        val timeout = parseTimeout(req.header("timeout"))
        val existing = req.header("sid")
        if (!existing.isNullOrBlank()) {
            val s = subs[existing.trim()] ?: return HttpResponse(412, "Precondition Failed")
            s.expiry = System.currentTimeMillis() + timeout * 1000L
            return accepted(s.sid, timeout)
        }
        val cb = req.header("callback") ?: return HttpResponse(412, "Precondition Failed")
        val urls = Regex("<([^>]+)>").findAll(cb).map { it.groupValues[1].trim() }
            .filter { it.startsWith("http", true) }.toList()
        if (urls.isEmpty()) return HttpResponse(412, "Precondition Failed")
        val sid = "uuid:" + UUID.randomUUID().toString()
        val sub = Sub(sid, service, urls, System.currentTimeMillis() + timeout * 1000L)
        subs[sid] = sub
        Log.i(TAG, "SUBSCRIBE $service from ${req.remote.hostAddress} -> $sid")
        try {
            exec.schedule({ deliver(sub, initial()) }, 300, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // shut down
        }
        return accepted(sid, timeout)
    }

    fun unsubscribe(req: HttpRequest): HttpResponse {
        val sid = req.header("sid")?.trim()
        if (sid == null || subs.remove(sid) == null) return HttpResponse(412, "Precondition Failed")
        return HttpResponse(200, "OK")
    }

    fun notify(service: String, body: String) {
        if (lastBody[service] == body) return
        lastBody[service] = body
        val now = System.currentTimeMillis()
        for (s in subs.values) {
            if (s.expiry < now) {
                subs.remove(s.sid)
                continue
            }
            if (s.service != service) continue
            try {
                exec.execute { deliver(s, body) }
            } catch (e: Exception) {
                return
            }
        }
    }

    fun shutdown() {
        subs.clear()
        exec.shutdownNow()
    }

    private fun accepted(sid: String, timeout: Int) = HttpResponse(
        200, "OK",
        headers = mapOf("SID" to sid, "TIMEOUT" to "Second-$timeout"),
    )

    private fun parseTimeout(h: String?): Int {
        if (h == null) return 1800
        if (h.contains("infinite", true)) return 3600
        return h.substringAfter('-', "").trim().toIntOrNull()?.coerceIn(60, 7200) ?: 1800
    }

    private fun deliver(s: Sub, body: String) {
        if (!subs.containsKey(s.sid)) return
        var seq = s.seq.getAndIncrement()
        if (seq > 4294967295L) { s.seq.set(1); seq = 1 }
        val bytes = body.toByteArray(Charsets.UTF_8)
        for (u in s.callbacks) {
            try {
                val url = URL(u)
                val port = if (url.port > 0) url.port else 80
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(url.host, port), 3000)
                    sock.soTimeout = 3000
                    val path = url.file.ifEmpty { "/" }
                    val head = "NOTIFY $path HTTP/1.1\r\n" +
                        "HOST: ${url.host}:$port\r\n" +
                        "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
                        "NT: upnp:event\r\n" +
                        "NTS: upnp:propchange\r\n" +
                        "SID: ${s.sid}\r\n" +
                        "SEQ: $seq\r\n" +
                        "CONTENT-LENGTH: ${bytes.size}\r\n" +
                        "CONNECTION: close\r\n\r\n"
                    val out = sock.getOutputStream()
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(bytes)
                    out.flush()
                    try { HttpServer.readLine(sock.getInputStream()) } catch (_: Exception) {}
                }
                return
            } catch (e: Exception) {
                Log.v(TAG, "notify $u failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "EventManager"
    }
}
