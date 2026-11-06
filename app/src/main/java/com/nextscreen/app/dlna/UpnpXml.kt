package com.nextscreen.app.dlna

/** UPnP device description + service descriptions (SCPD) for a DLNA MediaRenderer. */
object UpnpXml {

    val SINK_PROTOCOL_INFO: String = listOf(
        "http-get:*:video/mp4:*",
        "http-get:*:video/x-mp4:*",
        "http-get:*:video/mpeg:*",
        "http-get:*:video/x-matroska:*",
        "http-get:*:video/x-mkv:*",
        "http-get:*:video/webm:*",
        "http-get:*:video/quicktime:*",
        "http-get:*:video/x-flv:*",
        "http-get:*:video/flv:*",
        "http-get:*:video/3gpp:*",
        "http-get:*:video/MP2T:*",
        "http-get:*:video/vnd.dlna.mpeg-tts:*",
        "http-get:*:video/x-msvideo:*",
        "http-get:*:video/avi:*",
        "http-get:*:application/x-mpegURL:*",
        "http-get:*:application/vnd.apple.mpegurl:*",
        "http-get:*:application/dash+xml:*",
        "http-get:*:audio/mpeg:*",
        "http-get:*:audio/mp3:*",
        "http-get:*:audio/mp4:*",
        "http-get:*:audio/x-m4a:*",
        "http-get:*:audio/aac:*",
        "http-get:*:audio/flac:*",
        "http-get:*:audio/x-flac:*",
        "http-get:*:audio/wav:*",
        "http-get:*:audio/x-wav:*",
        "http-get:*:audio/ogg:*",
        "http-get:*:audio/L16:*",
        "http-get:*:image/jpeg:*",
        "http-get:*:image/png:*",
        "http-get:*:image/gif:*",
        "http-get:*:image/webp:*",
        "http-get:*:image/bmp:*",
        "http-get:*:*:*",
    ).joinToString(",")

    fun description(name: String, uuid: String, base: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<URLBase>${Upnp.esc(base)}</URLBase>
<device>
<deviceType>${Upnp.DEVICE_TYPE}</deviceType>
<friendlyName>${Upnp.esc(name)}</friendlyName>
<manufacturer>NextScreen</manufacturer>
<manufacturerURL>https://nextscreen.app</manufacturerURL>
<modelDescription>NextScreen DLNA Media Renderer</modelDescription>
<modelName>NextScreen</modelName>
<modelNumber>1.0</modelNumber>
<modelURL>https://nextscreen.app</modelURL>
<serialNumber>${uuid.take(8)}</serialNumber>
<UDN>uuid:$uuid</UDN>
<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
<iconList>
<icon><mimetype>image/png</mimetype><width>120</width><height>120</height><depth>32</depth><url>/icon.png</url></icon>
</iconList>
<serviceList>
<service>
<serviceType>${Upnp.AVT}</serviceType>
<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
<SCPDURL>/upnp/AVTransport.xml</SCPDURL>
<controlURL>/upnp/control/AVTransport</controlURL>
<eventSubURL>/upnp/event/AVTransport</eventSubURL>
</service>
<service>
<serviceType>${Upnp.RCS}</serviceType>
<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
<SCPDURL>/upnp/RenderingControl.xml</SCPDURL>
<controlURL>/upnp/control/RenderingControl</controlURL>
<eventSubURL>/upnp/event/RenderingControl</eventSubURL>
</service>
<service>
<serviceType>${Upnp.CMS}</serviceType>
<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
<SCPDURL>/upnp/ConnectionManager.xml</SCPDURL>
<controlURL>/upnp/control/ConnectionManager</controlURL>
<eventSubURL>/upnp/event/ConnectionManager</eventSubURL>
</service>
</serviceList>
</device>
</root>"""

    // ---------------------------------------------------------------- SCPD builder

    private class Arg(val name: String, val dir: String, val sv: String)
    private class Act(val name: String, val args: List<Arg>)
    private class Sv(
        val name: String,
        val type: String,
        val events: Boolean = false,
        val allowed: List<String>? = null,
        val range: Triple<String, String, String>? = null,
    )

    private fun i(n: String, sv: String) = Arg(n, "in", sv)
    private fun o(n: String, sv: String) = Arg(n, "out", sv)
    private fun act(n: String, vararg a: Arg) = Act(n, a.toList())

    private fun scpd(actions: List<Act>, vars: List<Sv>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n")
        append("<specVersion><major>1</major><minor>0</minor></specVersion>\n<actionList>\n")
        for (a in actions) {
            append("<action><name>").append(a.name).append("</name>")
            if (a.args.isNotEmpty()) {
                append("<argumentList>")
                for (g in a.args) {
                    append("<argument><name>").append(g.name).append("</name><direction>").append(g.dir)
                        .append("</direction><relatedStateVariable>").append(g.sv)
                        .append("</relatedStateVariable></argument>")
                }
                append("</argumentList>")
            }
            append("</action>\n")
        }
        append("</actionList>\n<serviceStateTable>\n")
        for (v in vars) {
            append("<stateVariable sendEvents=\"").append(if (v.events) "yes" else "no").append("\">")
            append("<name>").append(v.name).append("</name><dataType>").append(v.type).append("</dataType>")
            v.allowed?.let { list ->
                append("<allowedValueList>")
                list.forEach { append("<allowedValue>").append(it).append("</allowedValue>") }
                append("</allowedValueList>")
            }
            v.range?.let {
                append("<allowedValueRange><minimum>").append(it.first).append("</minimum><maximum>")
                    .append(it.second).append("</maximum><step>").append(it.third).append("</step></allowedValueRange>")
            }
            append("</stateVariable>\n")
        }
        append("</serviceStateTable>\n</scpd>")
    }

    private const val IID = "A_ARG_TYPE_InstanceID"

    val AVT_SCPD: String = scpd(
        listOf(
            act("SetAVTransportURI", i("InstanceID", IID), i("CurrentURI", "AVTransportURI"), i("CurrentURIMetaData", "AVTransportURIMetaData")),
            act("SetNextAVTransportURI", i("InstanceID", IID), i("NextURI", "NextAVTransportURI"), i("NextURIMetaData", "NextAVTransportURIMetaData")),
            act(
                "GetMediaInfo", i("InstanceID", IID),
                o("NrTracks", "NumberOfTracks"), o("MediaDuration", "CurrentMediaDuration"),
                o("CurrentURI", "AVTransportURI"), o("CurrentURIMetaData", "AVTransportURIMetaData"),
                o("NextURI", "NextAVTransportURI"), o("NextURIMetaData", "NextAVTransportURIMetaData"),
                o("PlayMedium", "PlaybackStorageMedium"), o("RecordMedium", "RecordStorageMedium"),
                o("WriteStatus", "RecordMediumWriteStatus"),
            ),
            act(
                "GetTransportInfo", i("InstanceID", IID),
                o("CurrentTransportState", "TransportState"), o("CurrentTransportStatus", "TransportStatus"),
                o("CurrentSpeed", "TransportPlaySpeed"),
            ),
            act(
                "GetPositionInfo", i("InstanceID", IID),
                o("Track", "CurrentTrack"), o("TrackDuration", "CurrentTrackDuration"),
                o("TrackMetaData", "CurrentTrackMetaData"), o("TrackURI", "CurrentTrackURI"),
                o("RelTime", "RelativeTimePosition"), o("AbsTime", "AbsoluteTimePosition"),
                o("RelCount", "RelativeCounterPosition"), o("AbsCount", "AbsoluteCounterPosition"),
            ),
            act(
                "GetDeviceCapabilities", i("InstanceID", IID),
                o("PlayMedia", "PossiblePlaybackStorageMedia"), o("RecMedia", "PossibleRecordStorageMedia"),
                o("RecQualityModes", "PossibleRecordQualityModes"),
            ),
            act("GetTransportSettings", i("InstanceID", IID), o("PlayMode", "CurrentPlayMode"), o("RecQualityMode", "CurrentRecordQualityMode")),
            act("Stop", i("InstanceID", IID)),
            act("Play", i("InstanceID", IID), i("Speed", "TransportPlaySpeed")),
            act("Pause", i("InstanceID", IID)),
            act("Seek", i("InstanceID", IID), i("Unit", "A_ARG_TYPE_SeekMode"), i("Target", "A_ARG_TYPE_SeekTarget")),
            act("Next", i("InstanceID", IID)),
            act("Previous", i("InstanceID", IID)),
            act("SetPlayMode", i("InstanceID", IID), i("NewPlayMode", "CurrentPlayMode")),
            act("GetCurrentTransportActions", i("InstanceID", IID), o("Actions", "CurrentTransportActions")),
        ),
        listOf(
            Sv("TransportState", "string", allowed = listOf("STOPPED", "PAUSED_PLAYBACK", "PLAYING", "TRANSITIONING", "NO_MEDIA_PRESENT")),
            Sv("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
            Sv("PlaybackStorageMedium", "string", allowed = listOf("NONE", "NETWORK")),
            Sv("RecordStorageMedium", "string", allowed = listOf("NOT_IMPLEMENTED")),
            Sv("PossiblePlaybackStorageMedia", "string"),
            Sv("PossibleRecordStorageMedia", "string"),
            Sv("CurrentPlayMode", "string", allowed = listOf("NORMAL", "REPEAT_ONE", "REPEAT_ALL", "SHUFFLE")),
            Sv("TransportPlaySpeed", "string", allowed = listOf("1")),
            Sv("RecordMediumWriteStatus", "string", allowed = listOf("NOT_IMPLEMENTED")),
            Sv("CurrentRecordQualityMode", "string", allowed = listOf("NOT_IMPLEMENTED")),
            Sv("PossibleRecordQualityModes", "string"),
            Sv("NumberOfTracks", "ui4", range = Triple("0", "65535", "1")),
            Sv("CurrentTrack", "ui4", range = Triple("0", "65535", "1")),
            Sv("CurrentTrackDuration", "string"),
            Sv("CurrentMediaDuration", "string"),
            Sv("CurrentTrackMetaData", "string"),
            Sv("CurrentTrackURI", "string"),
            Sv("AVTransportURI", "string"),
            Sv("AVTransportURIMetaData", "string"),
            Sv("NextAVTransportURI", "string"),
            Sv("NextAVTransportURIMetaData", "string"),
            Sv("RelativeTimePosition", "string"),
            Sv("AbsoluteTimePosition", "string"),
            Sv("RelativeCounterPosition", "i4"),
            Sv("AbsoluteCounterPosition", "i4"),
            Sv("CurrentTransportActions", "string"),
            Sv("LastChange", "string", events = true),
            Sv("A_ARG_TYPE_SeekMode", "string", allowed = listOf("ABS_TIME", "REL_TIME", "ABS_COUNT", "REL_COUNT", "TRACK_NR")),
            Sv("A_ARG_TYPE_SeekTarget", "string"),
            Sv(IID, "ui4"),
        ),
    )

    val RCS_SCPD: String = scpd(
        listOf(
            act("ListPresets", i("InstanceID", IID), o("CurrentPresetNameList", "PresetNameList")),
            act("SelectPreset", i("InstanceID", IID), i("PresetName", "A_ARG_TYPE_PresetName")),
            act("GetMute", i("InstanceID", IID), i("Channel", "A_ARG_TYPE_Channel"), o("CurrentMute", "Mute")),
            act("SetMute", i("InstanceID", IID), i("Channel", "A_ARG_TYPE_Channel"), i("DesiredMute", "Mute")),
            act("GetVolume", i("InstanceID", IID), i("Channel", "A_ARG_TYPE_Channel"), o("CurrentVolume", "Volume")),
            act("SetVolume", i("InstanceID", IID), i("Channel", "A_ARG_TYPE_Channel"), i("DesiredVolume", "Volume")),
        ),
        listOf(
            Sv("PresetNameList", "string"),
            Sv("LastChange", "string", events = true),
            Sv("Mute", "boolean"),
            Sv("Volume", "ui2", range = Triple("0", "100", "1")),
            Sv("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
            Sv(IID, "ui4"),
            Sv("A_ARG_TYPE_PresetName", "string", allowed = listOf("FactoryDefaults")),
        ),
    )

    val CMS_SCPD: String = scpd(
        listOf(
            act("GetProtocolInfo", o("Source", "SourceProtocolInfo"), o("Sink", "SinkProtocolInfo")),
            act("GetCurrentConnectionIDs", o("ConnectionIDs", "CurrentConnectionIDs")),
            act(
                "GetCurrentConnectionInfo", i("ConnectionID", "A_ARG_TYPE_ConnectionID"),
                o("RcsID", "A_ARG_TYPE_RcsID"), o("AVTransportID", "A_ARG_TYPE_AVTransportID"),
                o("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), o("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
                o("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), o("Direction", "A_ARG_TYPE_Direction"),
                o("Status", "A_ARG_TYPE_ConnectionStatus"),
            ),
        ),
        listOf(
            Sv("SourceProtocolInfo", "string", events = true),
            Sv("SinkProtocolInfo", "string", events = true),
            Sv("CurrentConnectionIDs", "string", events = true),
            Sv("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
            Sv("A_ARG_TYPE_ConnectionManager", "string"),
            Sv("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
            Sv("A_ARG_TYPE_ProtocolInfo", "string"),
            Sv("A_ARG_TYPE_ConnectionID", "i4"),
            Sv("A_ARG_TYPE_AVTransportID", "i4"),
            Sv("A_ARG_TYPE_RcsID", "i4"),
        ),
    )
}
