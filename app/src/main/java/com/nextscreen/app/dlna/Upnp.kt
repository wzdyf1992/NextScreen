package com.nextscreen.app.dlna

import android.os.Build
import java.util.Locale

object Upnp {
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"
    val SERVICES = listOf(AVT, RCS, CMS)

    val SERVER: String = "Linux/${Build.VERSION.RELEASE} UPnP/1.0 NextScreen/1.0"

    fun esc(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Lenient XML entity decoding (leaves stray '&' untouched). */
    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val t = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    }

    /** Parses H+:MM:SS[.F+] (and a few lenient variants) into milliseconds. */
    fun parseTime(s: String?): Long? {
        val v = s?.trim() ?: return null
        if (v.isEmpty() || v.equals("NOT_IMPLEMENTED", true)) return null
        val parts = v.split(':')
        return try {
            when (parts.size) {
                3 -> ((parts[0].toLong() * 3600 + parts[1].toLong() * 60) * 1000 + (parts[2].toDouble() * 1000).toLong())
                2 -> (parts[0].toLong() * 60 * 1000 + (parts[1].toDouble() * 1000).toLong())
                1 -> (parts[0].toDouble() * 1000).toLong()
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun propertySet(vararg props: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
        for ((k, v) in props) {
            append("<e:property><").append(k).append('>').append(esc(v)).append("</").append(k).append("></e:property>")
        }
        append("</e:propertyset>")
    }
}
