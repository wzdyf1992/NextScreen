package com.nextscreen.app.dlna

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.Locale
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/** SSDP discovery: answers M-SEARCH and periodically announces the renderer via NOTIFY. */
class SsdpServer(
    private val uuid: String,
    private val locationFor: (InetAddress?) -> String?,
) {
    private val group: InetAddress = InetAddress.getByName(ADDR)
    @Volatile private var socket: MulticastSocket? = null
    private var scheduler: ScheduledExecutorService? = null

    private val targets: List<String> =
        listOf("upnp:rootdevice", "uuid:$uuid", Upnp.DEVICE_TYPE) + Upnp.SERVICES

    fun start(iface: NetworkInterface?) {
        stop(sendByeBye = false)
        val ms = MulticastSocket(null as SocketAddress?)
        try {
            ms.reuseAddress = true
            ms.bind(InetSocketAddress(PORT))
            ms.timeToLive = 4
            if (iface != null) {
                try { ms.networkInterface = iface } catch (e: Exception) { Log.w(TAG, "setNetworkInterface", e) }
            }
            try {
                if (iface != null) ms.joinGroup(InetSocketAddress(group, PORT), iface)
                else @Suppress("DEPRECATION") ms.joinGroup(group)
            } catch (e: Exception) {
                Log.w(TAG, "joinGroup with iface failed, retry default", e)
                @Suppress("DEPRECATION") ms.joinGroup(group)
            }
        } catch (e: Exception) {
            ms.close()
            throw e
        }
        socket = ms
        val sch = ScheduledThreadPoolExecutor(1).apply {
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
        scheduler = sch
        thread(name = "ns-ssdp", isDaemon = true) { receiveLoop(ms) }
        sch.schedule({ sendAlive() }, 200, TimeUnit.MILLISECONDS)
        sch.schedule({ sendAlive() }, 1500, TimeUnit.MILLISECONDS)
        sch.scheduleWithFixedDelay({ sendAlive() }, 5, 45, TimeUnit.SECONDS)
    }

    fun stop(sendByeBye: Boolean = true) {
        val ms = socket ?: return
        if (sendByeBye) {
            try {
                repeat(2) { targets.forEach { send(ms, notify(it, "ssdp:byebye", null), group, PORT) } }
            } catch (e: Exception) {
                // ignore
            }
        }
        scheduler?.shutdownNow()
        scheduler = null
        socket = null
        ms.close()
    }

    private fun receiveLoop(ms: MulticastSocket) {
        val buf = ByteArray(4096)
        while (!ms.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                ms.receive(p)
                val text = String(p.data, 0, p.length, Charsets.UTF_8)
                if (text.startsWith("M-SEARCH", ignoreCase = true)) handleSearch(text, p.address, p.port)
            } catch (e: IOException) {
                if (ms.isClosed) break
            } catch (e: Exception) {
                Log.w(TAG, "ssdp loop", e)
            }
        }
    }

    private fun handleSearch(text: String, from: InetAddress, port: Int) {
        val headers = HashMap<String, String>()
        text.split("\r\n", "\n").drop(1).forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().uppercase(Locale.ROOT)] = line.substring(i + 1).trim()
        }
        val st = headers["ST"]?.trim() ?: return
        val reply: List<String> = when {
            st.equals("ssdp:all", true) -> targets
            st.equals("upnp:rootdevice", true) -> listOf("upnp:rootdevice")
            st.equals("uuid:$uuid", true) -> listOf("uuid:$uuid")
            st == Upnp.DEVICE_TYPE -> listOf(st)
            st in Upnp.SERVICES -> listOf(st)
            else -> return
        }
        val location = locationFor(from) ?: return
        val mx = headers["MX"]?.toIntOrNull()?.coerceIn(1, 5) ?: 1
        val delay = Random.nextLong(10, (mx * 250L).coerceAtMost(800L))
        val ms = socket ?: return
        try {
            scheduler?.schedule({
                reply.forEach { t -> send(ms, response(t, location), from, port) }
            }, delay, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // scheduler shut down
        }
    }

    private fun sendAlive() {
        val ms = socket ?: return
        val loc = locationFor(null) ?: return
        targets.forEach { send(ms, notify(it, "ssdp:alive", loc), group, PORT) }
    }

    private fun usn(target: String) = if (target.startsWith("uuid:")) target else "uuid:$uuid::$target"

    private fun response(st: String, location: String) = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=1800\r\n")
        append("DATE: ").append(HttpServer.httpDate()).append("\r\n")
        append("EXT:\r\n")
        append("LOCATION: ").append(location).append("\r\n")
        append("SERVER: ").append(Upnp.SERVER).append("\r\n")
        append("ST: ").append(st).append("\r\n")
        append("USN: ").append(usn(st)).append("\r\n")
        append("Content-Length: 0\r\n")
        append("\r\n")
    }

    private fun notify(nt: String, nts: String, location: String?) = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: ").append(ADDR).append(':').append(PORT).append("\r\n")
        if (location != null) {
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("LOCATION: ").append(location).append("\r\n")
            append("SERVER: ").append(Upnp.SERVER).append("\r\n")
        }
        append("NT: ").append(nt).append("\r\n")
        append("NTS: ").append(nts).append("\r\n")
        append("USN: ").append(usn(nt)).append("\r\n")
        append("\r\n")
    }

    private fun send(ms: MulticastSocket, msg: String, to: InetAddress, port: Int) {
        try {
            val b = msg.toByteArray(Charsets.UTF_8)
            ms.send(DatagramPacket(b, b.size, to, port))
        } catch (e: Exception) {
            Log.v(TAG, "send failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SsdpServer"
        const val ADDR = "239.255.255.250"
        const val PORT = 1900
    }
}
