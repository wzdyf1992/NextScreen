package com.nextscreen.app.player

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** Maps the DLNA 0..100 volume scale onto the device media stream. */
class VolumeHelper(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun get(): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (cur * 100f / max).roundToInt() else 0
    }

    fun set(v: Int) {
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0, 100) * max / 100f).roundToInt(), 0)
        } catch (e: SecurityException) {
            // Do-not-disturb may block volume changes
        }
    }
}
