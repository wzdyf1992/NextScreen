package com.nextscreen.app.data

import android.content.Context
import com.nextscreen.app.player.CastMedia
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps the most recent cast items so they can be replayed from the home screen. */
class HistoryStore(ctx: Context) {

    data class Item(
        val title: String,
        val uri: String,
        val metadata: String,
        val kind: String,
        val time: Long,
    )

    private val sp = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    @Synchronized
    fun list(): List<Item> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    o.optString("title"),
                    o.optString("uri"),
                    o.optString("meta"),
                    o.optString("kind", "VIDEO"),
                    o.optLong("time"),
                )
            }.filter { it.uri.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(m: CastMedia) {
        synchronized(this) {
            val items = list().filterNot { it.uri == m.uri }.toMutableList()
            items.add(0, Item(m.title, m.uri, m.metadata, m.kind.name, System.currentTimeMillis()))
            save(items.take(MAX))
        }
        listeners.forEach { it() }
    }

    fun clear() {
        synchronized(this) { sp.edit().remove(KEY).apply() }
        listeners.forEach { it() }
    }

    private fun save(items: List<Item>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("title", it.title)
                    .put("uri", it.uri)
                    .put("meta", it.metadata)
                    .put("kind", it.kind)
                    .put("time", it.time)
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX = 30
    }
}
