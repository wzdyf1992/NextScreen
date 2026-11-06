package com.nextscreen.app.dlna

import android.util.Log
import com.nextscreen.app.player.CastPlayer
import com.nextscreen.app.player.VolumeHelper

/** Implements the UPnP AV actions on top of [CastPlayer]. */
class SoapActions(private val player: CastPlayer, private val volume: VolumeHelper) {

    enum class Service(val type: String) { AVT(Upnp.AVT), RCS(Upnp.RCS), CMS(Upnp.CMS) }

    fun handle(service: Service, req: HttpRequest): HttpResponse {
        val call = SoapParser.parse(req.body, req.header("soapaction"))
            ?: return fault(401, "Invalid Action")
        if (!call.action.startsWith("Get")) {
            Log.i(TAG, "${service.name}.${call.action} from ${req.remote.hostAddress} ${call.args.filterKeys { !it.endsWith("MetaData") }}")
        }
        return try {
            when (service) {
                Service.AVT -> avt(call)
                Service.RCS -> rcs(call)
                Service.CMS -> cms(call)
            }
        } catch (e: Exception) {
            Log.w(TAG, "action ${call.action} failed", e)
            fault(501, "Action Failed")
        }
    }

    private fun avt(c: SoapCall): HttpResponse {
        val t = Upnp.AVT
        val a = c.action
        return when (a) {
            "SetAVTransportURI" -> {
                val uri = c["CurrentURI"].trim()
                if (uri.isEmpty()) return fault(714, "Illegal MIME-type")
                player.setUri(uri, c["CurrentURIMetaData"])
                ok(t, a)
            }
            "SetNextAVTransportURI" -> {
                player.setNextUri(c["NextURI"].trim(), c["NextURIMetaData"])
                ok(t, a)
            }
            "Play" -> { player.play(); ok(t, a) }
            "Pause" -> { player.pause(); ok(t, a) }
            "Stop" -> { player.stop(fromController = true); ok(t, a) }
            "Seek" -> {
                val unit = c["Unit"].trim().uppercase()
                val target = c["Target"].trim()
                when (unit) {
                    "REL_TIME", "ABS_TIME", "" -> {
                        val ms = Upnp.parseTime(target) ?: return fault(711, "Illegal seek target")
                        player.seekTo(ms)
                    }
                    "TRACK_NR" -> Unit
                    "REL_COUNT", "ABS_COUNT" -> target.toLongOrNull()?.let { player.seekTo(it) }
                    else -> return fault(710, "Seek mode not supported")
                }
                ok(t, a)
            }
            "Next" -> { player.next(); ok(t, a) }
            "Previous" -> ok(t, a)
            "SetPlayMode" -> ok(t, a)
            "GetTransportInfo" -> ok(
                t, a,
                "CurrentTransportState" to player.state.upnp,
                "CurrentTransportStatus" to player.transportStatus,
                "CurrentSpeed" to "1",
            )
            "GetPositionInfo" -> {
                val m = player.media
                val pos = Upnp.formatTime(player.positionMs)
                ok(
                    t, a,
                    "Track" to if (m != null) "1" else "0",
                    "TrackDuration" to Upnp.formatTime(player.durationMs),
                    "TrackMetaData" to (m?.metadata ?: ""),
                    "TrackURI" to (m?.uri ?: ""),
                    "RelTime" to pos,
                    "AbsTime" to pos,
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                )
            }
            "GetMediaInfo" -> {
                val m = player.media
                val n = player.nextMedia
                ok(
                    t, a,
                    "NrTracks" to if (m != null) "1" else "0",
                    "MediaDuration" to Upnp.formatTime(player.durationMs),
                    "CurrentURI" to (m?.uri ?: ""),
                    "CurrentURIMetaData" to (m?.metadata ?: ""),
                    "NextURI" to (n?.uri ?: ""),
                    "NextURIMetaData" to (n?.metadata ?: ""),
                    "PlayMedium" to if (m != null) "NETWORK" else "NONE",
                    "RecordMedium" to "NOT_IMPLEMENTED",
                    "WriteStatus" to "NOT_IMPLEMENTED",
                )
            }
            "GetDeviceCapabilities" -> ok(
                t, a,
                "PlayMedia" to "NETWORK",
                "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED",
            )
            "GetTransportSettings" -> ok(t, a, "PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> ok(t, a, "Actions" to player.transportActions())
            else -> fault(401, "Invalid Action")
        }
    }

    private fun rcs(c: SoapCall): HttpResponse {
        val t = Upnp.RCS
        val a = c.action
        return when (a) {
            "GetVolume" -> ok(t, a, "CurrentVolume" to volume.get().toString())
            "SetVolume" -> {
                c["DesiredVolume"].trim().toIntOrNull()?.let { volume.set(it) }
                ok(t, a)
            }
            "GetMute" -> ok(t, a, "CurrentMute" to if (player.muted) "1" else "0")
            "SetMute" -> {
                val v = c["DesiredMute"].trim().lowercase()
                player.setMute(v == "1" || v == "true" || v == "yes")
                ok(t, a)
            }
            "ListPresets" -> ok(t, a, "CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> ok(t, a)
            else -> fault(401, "Invalid Action")
        }
    }

    private fun cms(c: SoapCall): HttpResponse {
        val t = Upnp.CMS
        val a = c.action
        return when (a) {
            "GetProtocolInfo" -> ok(t, a, "Source" to "", "Sink" to UpnpXml.SINK_PROTOCOL_INFO)
            "GetCurrentConnectionIDs" -> ok(t, a, "ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> ok(
                t, a,
                "RcsID" to "0",
                "AVTransportID" to "0",
                "ProtocolInfo" to "",
                "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1",
                "Direction" to "Input",
                "Status" to "OK",
            )
            else -> fault(401, "Invalid Action")
        }
    }

    companion object {
        private const val TAG = "SoapActions"
        private const val ENV_START =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
        private const val ENV_END = "</s:Body></s:Envelope>"

        fun ok(serviceType: String, action: String, vararg out: Pair<String, String>): HttpResponse {
            val sb = StringBuilder(ENV_START)
            sb.append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">")
            for ((k, v) in out) sb.append('<').append(k).append('>').append(Upnp.esc(v)).append("</").append(k).append('>')
            sb.append("</u:").append(action).append("Response>").append(ENV_END)
            return HttpResponse(200, "OK", sb.toString().toByteArray(Charsets.UTF_8), "text/xml; charset=\"utf-8\"", mapOf("EXT" to ""))
        }

        fun fault(code: Int, desc: String): HttpResponse {
            val body = ENV_START +
                "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
                "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
                "<errorDescription>${Upnp.esc(desc)}</errorDescription></UPnPError></detail></s:Fault>" + ENV_END
            return HttpResponse(500, "Internal Server Error", body.toByteArray(Charsets.UTF_8), "text/xml; charset=\"utf-8\"", mapOf("EXT" to ""))
        }
    }
}
