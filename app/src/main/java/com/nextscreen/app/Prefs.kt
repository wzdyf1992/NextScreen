package com.nextscreen.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.preference.PreferenceManager
import java.util.Locale
import java.util.UUID

/** Central access to user settings (backed by the default SharedPreferences used by the settings screen). */
object Prefs {
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_UUID = "uuid"
    const val KEY_SPEED = "speed"
    const val KEY_REMEMBER_SPEED = "remember_speed"
    const val KEY_LONG_PRESS_SPEED = "long_press_speed"
    const val KEY_QUALITY = "quality"
    const val KEY_RESIZE = "resize_mode"
    const val KEY_END_ACTION = "end_action"
    const val KEY_BOOT_START = "boot_start"

    const val KEY_AIRPLAY_ENABLED = "airplay_enabled"
    const val KEY_AIRPLAY_RESOLUTION = "airplay_resolution"
    const val KEY_AIRPLAY_FPS = "airplay_fps"
    const val KEY_AIRPLAY_H265 = "airplay_h265"
    const val KEY_AIRPLAY_PIN = "airplay_pin"

    private lateinit var sp: SharedPreferences
    val raw: SharedPreferences get() = sp

    fun init(ctx: Context) {
        sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val e = sp.edit()
        if (!sp.contains(KEY_DEVICE_NAME)) e.putString(KEY_DEVICE_NAME, defaultName())
        if (!sp.contains(KEY_UUID)) e.putString(KEY_UUID, UUID.randomUUID().toString())
        e.apply()
    }

    fun defaultName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isEmpty()) "NextScreen" else "NextScreen-$model"
    }

    val uuid: String
        get() = sp.getString(KEY_UUID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_UUID, it).apply()
        }

    val deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName()

    /** Playback speed applied to every newly cast video. */
    var speed: Float
        get() = sp.getString(KEY_SPEED, "1.0")?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f
        set(v) = sp.edit().putString(KEY_SPEED, speedValue(v)).apply()

    val rememberSpeed: Boolean get() = sp.getBoolean(KEY_REMEMBER_SPEED, true)

    val longPressSpeed: Float get() = sp.getString(KEY_LONG_PRESS_SPEED, "2.0")?.toFloatOrNull() ?: 2f

    /** auto | highest | 2160 | 1440 | 1080 | 720 | 480 | 360 */
    var quality: String
        get() = sp.getString(KEY_QUALITY, "highest") ?: "highest"
        set(v) = sp.edit().putString(KEY_QUALITY, v).apply()

    var resizeKey: String
        get() = sp.getString(KEY_RESIZE, "fit") ?: "fit"
        set(v) = sp.edit().putString(KEY_RESIZE, v).apply()

    val resizeMode: Int
        get() = when (resizeKey) {
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

    /** wait | close */
    val endAction: String get() = sp.getString(KEY_END_ACTION, "wait") ?: "wait"

    val bootStart: Boolean get() = sp.getBoolean(KEY_BOOT_START, false)

    val airPlayEnabled: Boolean get() = sp.getBoolean(KEY_AIRPLAY_ENABLED, true)

    /** auto | 2560x1440 | 1920x1080 | 1280x720 */
    val airPlayResolution: String get() = sp.getString(KEY_AIRPLAY_RESOLUTION, "auto") ?: "auto"

    val airPlayFps: Int get() = sp.getString(KEY_AIRPLAY_FPS, "60")?.toIntOrNull()?.coerceIn(24, 60) ?: 60

    /** auto | on | off */
    val airPlayH265: String get() = sp.getString(KEY_AIRPLAY_H265, "auto") ?: "auto"

    val airPlayPin: Boolean get() = sp.getBoolean(KEY_AIRPLAY_PIN, false)

    fun isAirPlayRestartKey(key: String?): Boolean =
        key == KEY_DEVICE_NAME ||
            key == KEY_AIRPLAY_ENABLED ||
            key == KEY_AIRPLAY_RESOLUTION ||
            key == KEY_AIRPLAY_FPS ||
            key == KEY_AIRPLAY_H265 ||
            key == KEY_AIRPLAY_PIN

    fun speedValue(v: Float): String {
        val tenths = v * 10f
        return if (kotlin.math.abs(tenths - Math.round(tenths)) < 0.001f) {
            String.format(Locale.US, "%.1f", v)
        } else {
            String.format(Locale.US, "%.2f", v)
        }
    }

    fun speedLabel(v: Float): String = speedValue(v) + "x"
}
