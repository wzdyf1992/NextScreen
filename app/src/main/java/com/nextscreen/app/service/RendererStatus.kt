package com.nextscreen.app.service

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Observable DLNA + AirPlay status for the UI (callbacks on the main thread). */
object RendererStatus {
    data class Info(
        val running: Boolean = false,
        val ip: String? = null,
        val port: Int = 0,
        val name: String? = null,
        val error: String? = null,
        val airPlayRunning: Boolean = false,
        val airPlayPort: Int = 0,
        val airPlayError: String? = null,
    )

    @Volatile var info = Info()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Info) -> Unit>()

    @Synchronized
    fun updateDlna(running: Boolean, ip: String?, port: Int, name: String?, error: String?) {
        val next = info.copy(
            running = running,
            ip = ip,
            port = port,
            name = name,
            error = error,
        )
        info = next
        main.post { listeners.forEach { it(next) } }
    }

    @Synchronized
    fun updateAirPlay(running: Boolean, port: Int, error: String?) {
        val next = info.copy(
            airPlayRunning = running,
            airPlayPort = port,
            airPlayError = error,
        )
        info = next
        main.post { listeners.forEach { it(next) } }
    }

    fun observe(l: (Info) -> Unit) {
        listeners.addIfAbsent(l)
        l(info)
    }

    fun remove(l: (Info) -> Unit) {
        listeners.remove(l)
    }
}
