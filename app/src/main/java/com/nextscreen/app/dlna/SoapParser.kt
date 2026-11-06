package com.nextscreen.app.dlna

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

class SoapCall(val action: String, val args: Map<String, String>) {
    operator fun get(key: String): String = args[key] ?: ""
}

/**
 * Tolerant SOAP request parser. Real-world control points send all sorts of non-compliant XML
 * (unescaped '&' in URLs, raw DIDL inside CurrentURIMetaData...), so we fall back to regex extraction.
 */
object SoapParser {
    private const val TAG = "SoapParser"

    fun parse(body: ByteArray, soapActionHeader: String?): SoapCall? {
        val headerAction = soapActionHeader?.trim()?.trim('"')?.substringAfterLast('#')?.trim()?.takeIf { it.isNotEmpty() }
        val raw = String(body, Charsets.UTF_8).trimStart('\uFEFF')
        var action: String? = null
        val args = LinkedHashMap<String, String>()
        val broken = ArrayList<String>()
        var failed = false
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(StringReader(raw))
            var inBody = false
            var actionDepth = -1
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val name = p.name
                    when {
                        !inBody && name == "Body" -> inBody = true
                        inBody && actionDepth < 0 -> {
                            action = name
                            actionDepth = p.depth
                        }
                        actionDepth > 0 && p.depth == actionDepth + 1 -> {
                            val argDepth = p.depth
                            try {
                                args[name] = p.nextText()
                            } catch (e: XmlPullParserException) {
                                broken += name
                                while (!(p.eventType == XmlPullParser.END_TAG && p.depth == argDepth)) {
                                    if (p.next() == XmlPullParser.END_DOCUMENT) break
                                }
                            }
                        }
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            failed = true
            Log.w(TAG, "pull parse failed, using regex fallback: ${e.message}")
        }

        if (failed) {
            regexParse(raw)?.let { (a, map) ->
                if (action == null) action = a
                map.forEach { (k, v) -> if (!args.containsKey(k)) args[k] = v }
            }
        }
        for (b in broken) extractRaw(raw, b)?.let { args[b] = it }
        val a = action ?: headerAction ?: return null
        return SoapCall(a, args)
    }

    private fun regexParse(raw: String): Pair<String, Map<String, String>>? {
        val bodyMatch = Regex("<(?:[\\w.-]+:)?Body[^>]*>(.*)</(?:[\\w.-]+:)?Body>", RegexOption.DOT_MATCHES_ALL).find(raw)
            ?: return null
        val inner = bodyMatch.groupValues[1].trim()
        val actMatch = Regex("^<(?:[\\w.-]+:)?([\\w.-]+)[^>]*>(.*)</(?:[\\w.-]+:)?\\1>", RegexOption.DOT_MATCHES_ALL).find(inner)
            ?: return null
        val action = actMatch.groupValues[1]
        val content = actMatch.groupValues[2]
        val map = LinkedHashMap<String, String>()
        Regex("<([\\w.-]+)(?:\\s[^>]*)?>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(content).forEach {
            map[it.groupValues[1]] = Upnp.unescape(it.groupValues[2])
        }
        Regex("<([\\w.-]+)(?:\\s[^>]*)?/>").findAll(content).forEach {
            if (!map.containsKey(it.groupValues[1])) map[it.groupValues[1]] = ""
        }
        return action to map
    }

    private fun extractRaw(raw: String, name: String): String? {
        val m = Regex("<(?:[\\w.-]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[\\w.-]+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(raw)
            ?: return null
        val v = m.groupValues[1].trim()
        return if (v.startsWith("&lt;")) Upnp.unescape(v) else v
    }
}
