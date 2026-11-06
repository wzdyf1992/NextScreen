package com.nextscreen.app.dlna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.nextscreen.app.Prefs
import com.nextscreen.app.R
import com.nextscreen.app.player.CastListener
import com.nextscreen.app.player.CastMedia
import com.nextscreen.app.player.CastPlayer
import com.nextscreen.app.player.VolumeHelper
import com.nextscreen.app.service.RendererStatus
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress

/** Wires the HTTP server, SSDP discovery, SOAP control and GENA eventing into one DLNA renderer. */
class DlnaRenderer(private val ctx: Context, private val player: CastPlayer) : CastListener {

    val uuid: String = Prefs.uuid
    private val volume = VolumeHelper(ctx)
    private val soap = SoapActions(player, volume)
    private var http: HttpServer? = null
    private var ssdp: SsdpServer? = null
    private var events: EventManager? = null
    private var volumeReceiver: BroadcastReceiver? = null

    @Volatile var ip: Inet4Address? = null
        private set
    val ipString: String? get() = ip?.hostAddress
    @Volatile private var port = 0
    @Volatile var running = false
        private set

    private val iconBytes: ByteArray by lazy {
        try {
            val d = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)!!
            val bmp = d.toBitmap(120, 120, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        val addr = NetUtils.localIpv4()
        ip = addr
        events = EventManager()
        var error: String? = null
        try {
            val h = HttpServer(::handle)
            h.start(PREFERRED_PORT)
            http = h
            port = h.port
        } catch (e: Exception) {
            Log.e(TAG, "http start failed", e)
            error = "HTTP 服务启动失败：${e.message}"
        }
        if (addr == null) error = error ?: "未检测到局域网，请连接 Wi-Fi（手机与平板需在同一网络）"
        if (http != null) {
            try {
                val s = SsdpServer(uuid, ::locationFor)
                s.start(NetUtils.interfaceOf(addr))
                ssdp = s
            } catch (e: Exception) {
                Log.e(TAG, "ssdp start failed", e)
                error = error ?: "设备发现服务启动失败：${e.message}"
            }
        }
        player.addListener(this)
        registerVolumeReceiver()
        running = true
        RendererStatus.updateDlna(true, addr?.hostAddress, port, Prefs.deviceName, error)
        Log.i(TAG, "renderer started at ${addr?.hostAddress}:$port name=${Prefs.deviceName}")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        player.removeListener(this)
        unregisterVolumeReceiver()
        try { ssdp?.stop(sendByeBye = true) } catch (_: Exception) {}
        ssdp = null
        http?.stop()
        http = null
        events?.shutdown()
        events = null
        RendererStatus.updateDlna(false, ip?.hostAddress, port, Prefs.deviceName, null)
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    private fun locationFor(remote: InetAddress?): String? {
        val a = NetUtils.addressFor(remote) ?: ip ?: NetUtils.localIpv4() ?: return null
        return "http://${a.hostAddress}:$port/description.xml"
    }

    private fun baseUrl(req: HttpRequest): String {
        val local = (req.local as? Inet4Address)?.hostAddress ?: ipString ?: "127.0.0.1"
        return "http://$local:$port/"
    }

    private fun handle(req: HttpRequest): HttpResponse {
        val path = req.path
        return when (req.method) {
            "GET", "HEAD" -> when (path) {
                "/description.xml", "/dmr.xml", "/rootDesc.xml" ->
                    HttpResponse.xml(UpnpXml.description(Prefs.deviceName, uuid, baseUrl(req)))
                "/upnp/AVTransport.xml" -> HttpResponse.xml(UpnpXml.AVT_SCPD)
                "/upnp/RenderingControl.xml" -> HttpResponse.xml(UpnpXml.RCS_SCPD)
                "/upnp/ConnectionManager.xml" -> HttpResponse.xml(UpnpXml.CMS_SCPD)
                "/icon.png" -> HttpResponse(200, "OK", iconBytes, "image/png")
                "/" -> HttpResponse(200, "OK", statusHtml().toByteArray(Charsets.UTF_8), "text/html; charset=utf-8")
                else -> HttpResponse.notFound()
            }
            "POST" -> when (path) {
                "/upnp/control/AVTransport" -> soap.handle(SoapActions.Service.AVT, req)
                "/upnp/control/RenderingControl" -> soap.handle(SoapActions.Service.RCS, req)
                "/upnp/control/ConnectionManager" -> soap.handle(SoapActions.Service.CMS, req)
                else -> HttpResponse.notFound()
            }
            "SUBSCRIBE" -> {
                val ev = events ?: return HttpResponse(503, "Service Unavailable")
                when (path) {
                    "/upnp/event/AVTransport" -> ev.subscribe(KEY_AVT, req) { avtEvent() }
                    "/upnp/event/RenderingControl" -> ev.subscribe(KEY_RCS, req) { rcsEvent() }
                    "/upnp/event/ConnectionManager" -> ev.subscribe(KEY_CMS, req) { cmsEvent() }
                    else -> HttpResponse.notFound()
                }
            }
            "UNSUBSCRIBE" -> events?.unsubscribe(req) ?: HttpResponse(412, "Precondition Failed")
            else -> HttpResponse(405, "Method Not Allowed")
        }
    }

    // ------------------------------------------------------------------ events

    private fun avtEvent(): String {
        val m = player.media
        val n = player.nextMedia
        val dur = Upnp.formatTime(player.durationMs)
        val sb = StringBuilder("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">")
        fun v(name: String, value: String) {
            sb.append('<').append(name).append(" val=\"").append(Upnp.esc(value)).append("\"/>")
        }
        v("TransportState", player.state.upnp)
        v("TransportStatus", player.transportStatus)
        v("CurrentTransportActions", player.transportActions())
        v("PlaybackStorageMedium", if (m != null) "NETWORK" else "NONE")
        v("PossiblePlaybackStorageMedia", "NETWORK")
        v("CurrentPlayMode", "NORMAL")
        v("TransportPlaySpeed", "1")
        v("NumberOfTracks", if (m != null) "1" else "0")
        v("CurrentTrack", if (m != null) "1" else "0")
        v("AVTransportURI", m?.uri ?: "")
        v("AVTransportURIMetaData", m?.metadata ?: "")
        v("CurrentTrackURI", m?.uri ?: "")
        v("CurrentTrackMetaData", m?.metadata ?: "")
        v("CurrentTrackDuration", dur)
        v("CurrentMediaDuration", dur)
        v("NextAVTransportURI", n?.uri ?: "")
        v("NextAVTransportURIMetaData", n?.metadata ?: "")
        sb.append("</InstanceID></Event>")
        return Upnp.propertySet("LastChange" to sb.toString())
    }

    private fun rcsEvent(): String {
        val lc = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
            "<Volume channel=\"Master\" val=\"${volume.get()}\"/>" +
            "<Mute channel=\"Master\" val=\"${if (player.muted) 1 else 0}\"/>" +
            "<PresetNameList val=\"FactoryDefaults\"/>" +
            "</InstanceID></Event>"
        return Upnp.propertySet("LastChange" to lc)
    }

    private fun cmsEvent(): String = Upnp.propertySet(
        "SourceProtocolInfo" to "",
        "SinkProtocolInfo" to UpnpXml.SINK_PROTOCOL_INFO,
        "CurrentConnectionIDs" to "0",
    )

    override fun onMediaChanged(media: CastMedia?) { events?.notify(KEY_AVT, avtEvent()) }
    override fun onStateChanged() { events?.notify(KEY_AVT, avtEvent()) }
    override fun onVolumeChanged() { events?.notify(KEY_RCS, rcsEvent()) }

    private fun registerVolumeReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                events?.notify(KEY_RCS, rcsEvent())
            }
        }
        try {
            ContextCompat.registerReceiver(
                ctx, r, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED,
            )
            volumeReceiver = r
        } catch (e: Exception) {
            Log.w(TAG, "volume receiver", e)
        }
    }

    private fun unregisterVolumeReceiver() {
        volumeReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        volumeReceiver = null
    }

    private fun statusHtml(): String {
        val m = player.media
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<title>NextScreen</title></head><body style=\"font-family:sans-serif;background:#0B1020;color:#fff;padding:24px\">" +
            "<h2>NextScreen</h2><p>设备名称：${Upnp.esc(Prefs.deviceName)}</p>" +
            "<p>状态：${player.state.upnp}</p><p>正在播放：${Upnp.esc(m?.title ?: "无")}</p></body></html>"
    }

    companion object {
        private const val TAG = "DlnaRenderer"
        const val PREFERRED_PORT = 49152
        private const val KEY_AVT = "AVT"
        private const val KEY_RCS = "RCS"
        private const val KEY_CMS = "CMS"
    }
}
