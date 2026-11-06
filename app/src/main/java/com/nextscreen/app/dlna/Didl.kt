package com.nextscreen.app.dlna

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Subset of DIDL-Lite metadata that matters for playback / display. */
data class DidlInfo(
    val title: String? = null,
    val upnpClass: String? = null,
    val mime: String? = null,
    val albumArt: String? = null,
    val artist: String? = null,
    val durationMs: Long = 0,
)

object Didl {
    fun parse(xml: String?): DidlInfo {
        if (xml.isNullOrBlank() || !xml.contains('<')) return DidlInfo()
        var title: String? = null
        var cls: String? = null
        var protocol: String? = null
        var art: String? = null
        var artist: String? = null
        var duration = 0L
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(xml))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name.substringAfterLast(':')) {
                        "title" -> if (title == null) title = safeText(p)
                        "class" -> if (cls == null) cls = safeText(p)
                        "albumArtURI" -> if (art == null) art = safeText(p)
                        "creator", "artist" -> if (artist == null) artist = safeText(p)
                        "res" -> if (protocol == null) {
                            protocol = p.getAttributeValue(null, "protocolInfo")
                            duration = Upnp.parseTime(p.getAttributeValue(null, "duration")) ?: 0L
                        }
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            if (title == null) {
                title = Regex("<dc:title>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)?.groupValues?.get(1)?.let { Upnp.unescape(it) }
            }
            if (cls == null) {
                cls = Regex("<upnp:class>(.*?)</upnp:class>").find(xml)?.groupValues?.get(1)
            }
        }
        val mime = protocol?.split(':')?.getOrNull(2)?.takeIf { it.isNotBlank() && it != "*" }
        return DidlInfo(title?.trim(), cls?.trim(), mime, art?.trim(), artist?.trim(), duration)
    }

    private fun safeText(p: XmlPullParser): String? = try {
        p.nextText()
    } catch (e: Exception) {
        null
    }
}
