package com.nextscreen.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.nextscreen.app.player.CastPlayer
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny async image loader for cast photos and album art. */
object ImageLoader {
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String?, view: ImageView, maxSize: Int, placeholder: Int = 0, onDone: ((Boolean) -> Unit)? = null) {
        view.tag = url
        if (url.isNullOrBlank()) {
            if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
            onDone?.invoke(false)
            return
        }
        if (placeholder != 0) view.setImageResource(placeholder)
        exec.execute {
            val bmp = try { decode(url, maxSize) } catch (e: Throwable) { null }
            main.post {
                if (view.tag != url) return@post
                if (bmp != null) view.setImageBitmap(bmp)
                onDone?.invoke(bmp != null)
            }
        }
    }

    private fun decode(url: String, maxSize: Int): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        val bytes = try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", CastPlayer.USER_AGENT)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
