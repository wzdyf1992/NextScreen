package com.nextscreen.app.airplay

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.GLES20
import android.os.Build
import android.util.Log

/**
 * Selects hardware H.264 / H.265 decoders and applies low-latency vendor tuning flags.
 */
class DecoderSelector(private val ctx: Context) {

    private val emulator =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "cheets" || Build.BRAND == "Android-x86"

    private val glRenderer by lazy {
        runCatching { EglCore().use { GLES20.glGetString(GLES20.GL_RENDERER) ?: "" } }
            .getOrDefault("").lowercase()
    }

    private val adreno by lazy {
        if ("adreno" !in glRenderer) -1
        else Regex("\\d{3}").findAll(glRenderer).lastOrNull()?.value?.toInt() ?: -1
    }

    private val fireOs by lazy {
        ctx.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    }

    private val blacklist by lazy {
        buildList {
            if (!emulator) {
                add("omx.google")
                add("AVCDecoder")
                if (Build.VERSION.SDK_INT < 29) add("OMX.ffmpeg")
            }
            add("OMX.qcom.video.decoder.hevcswvdec")
            add("OMX.SEC.hevc.sw.dec")
            if (adreno in 0..399) add("OMX.qcom.video.decoder.hevc")
        }
    }

    private val hevcWhitelist by lazy {
        buildList {
            if (Build.HARDWARE == "ranchu") add("omx.google")
            add("omx.exynos")
            if (Build.VERSION.SDK_INT >= 26 && Build.DEVICE.lowercase() !in listOf("shieldtablet", "mocha")) {
                add("omx.nvidia")
            }
            if (Build.VERSION.SDK_INT >= 26 && Build.DEVICE.startsWith("BRAVIA_")) add("omx.mtk")
            if (Build.VERSION.SDK_INT >= 28 && !Build.DEVICE.equals("sabrina", ignoreCase = true)) {
                add("omx.amlogic")
            }
            if (Build.VERSION.SDK_INT >= 28) add("omx.realtek")
            add("omx.hisi")
            add("c2.")
            if (adreno >= 400) addAll(qti)
            if (fireOs) {
                add("omx.mtk")
                add("omx.amlogic")
            }
            if ("powervr" in glRenderer) add("omx.mtk")
        }
    }

    fun avc(): MediaCodecInfo? = probableSafe(AVC, CodecProfileLevel.AVCProfileHigh) ?: first(AVC)

    fun hevc(avc: MediaCodecInfo?, w: Int, h: Int, fps: Int, force: Boolean): MediaCodecInfo? {
        val info = probableSafe(HEVC, -1) ?: return null
        if (force || hevcWhitelisted(info)) return info
        val avcCaps = avc?.videoCaps(AVC) ?: return null
        return info.takeIf { !canMeet(avcCaps, w, h, fps) && canMeet(info.videoCaps(HEVC), w, h, fps) }
    }

    fun software(mime: String, w: Int, h: Int): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.supportsMime(mime) &&
                (if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
                else info.name.lowercase().let {
                    it.startsWith("omx.google.") || it.startsWith("c2.android.") ||
                        (!it.startsWith("omx.") && !it.startsWith("c2."))
                })
        }?.takeIf { portraitSafe(w, h, it.videoCaps(mime)::isSizeSupported) }

    fun hasAudioAlacDecoder(): Boolean =
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportsMime("audio/alac")
            }
        }.getOrDefault(false)

    fun adaptive(info: MediaCodecInfo, mime: String): Boolean =
        !inList(noAdaptive, info.name) &&
            runCatching {
                info.getCapabilitiesForType(mime).isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)
            }.getOrDefault(false)

    fun lowLatencyOptions(format: MediaFormat, info: MediaCodecInfo, mime: String, tryNum: Int): Boolean {
        var set = false
        if (tryNum < 1) {
            format.setInteger("low-latency", 1)
            if (supportsLowLatencyFeature(info, mime)) return true
            set = true
        }
        if (tryNum < 2) {
            format.setInteger("vdec-lowlatency", 1)
            set = true
        }
        if (tryNum < 3) {
            val max = inList(qti, info.name) && adreno != 620
            if (max) format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            else format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            set = true
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (inList(qti, info.name)) {
                if (tryNum < 4) { format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1); set = true }
                if (tryNum < 5) { format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1); set = true }
            } else if (tryNum < 4) {
                vendorLowLatency.firstOrNull { (prefixes, _) -> inList(prefixes, info.name) }?.let { (_, keys) ->
                    keys.forEach { (k, v) -> format.setInteger(k, v) }
                    set = true
                }
            }
        }
        return set
    }

    private fun decoders(mime: String) =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && (Build.VERSION.SDK_INT < 29 || !info.isAlias) &&
                info.supportsMime(mime) && !blacklisted(info)
        }

    private fun blacklisted(info: MediaCodecInfo) =
        (!emulator && Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly) || inList(blacklist, info.name)

    private fun probableSafe(mime: String, profile: Int) = try {
        val all = decoders(mime)
        (0..1).firstNotNullOfOrNull { round ->
            all.firstOrNull { info ->
                (round == 1 || supportsLowLatencyFeature(info, mime)) &&
                    (profile == -1 || info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile })
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "caps query failed", e)
        first(mime)
    }

    private fun first(mime: String) = decoders(mime).firstOrNull()

    private fun supportsLowLatencyFeature(info: MediaCodecInfo, mime: String) =
        Build.VERSION.SDK_INT >= 30 &&
            runCatching { info.getCapabilitiesForType(mime).isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) }
                .getOrDefault(false)

    private fun hevcWhitelisted(info: MediaCodecInfo): Boolean = when {
        "sw" in info.name.lowercase() -> false
        Build.VERSION.SDK_INT >= 29 && (!info.isHardwareAccelerated || info.isSoftwareOnly) -> false
        Build.VERSION.SDK_INT >= 31 && Build.VERSION.MEDIA_PERFORMANCE_CLASS >= 31 -> true
        supportsLowLatencyFeature(info, HEVC) -> true
        else -> inList(hevcWhitelist, info.name)
    }

    private fun canMeet(caps: VideoCapabilities, w: Int, h: Int, fps: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val target = VideoCapabilities.PerformancePoint(w, h, fps)
            caps.supportedPerformancePoints?.let { pts -> return pts.any { it.covers(target) } }
        }
        return portraitSafe(w, h) { cw, ch -> rateSupported(caps, cw, ch, fps) }
    }

    private fun rateSupported(caps: VideoCapabilities, w: Int, h: Int, fps: Int) = try {
        caps.getAchievableFrameRatesFor(w, h)?.let { fps <= it.upper }
            ?: caps.areSizeAndRateSupported(w, h, fps.toDouble())
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun portraitSafe(w: Int, h: Int, check: (Int, Int) -> Boolean) =
        check(w, h) || (w < h && check(h, w))

    private fun inList(prefixes: List<String>, name: String) =
        prefixes.any { name.startsWith(it, ignoreCase = true) }

    private fun MediaCodecInfo.supportsMime(mime: String) =
        supportedTypes.any { it.equals(mime, ignoreCase = true) }

    companion object {
        private const val TAG = "DecoderSelector"
        const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        private val qti = listOf("omx.qcom", "c2.qti")
        private val noAdaptive = listOf("omx.intel", "omx.mtk")
        private val vendorLowLatency = listOf(
            listOf("omx.hisi", "c2.hisi") to mapOf(
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1,
            ),
            listOf("omx.exynos", "c2.exynos") to mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1),
            listOf("omx.amlogic", "c2.amlogic") to mapOf("vendor.low-latency.enable" to 1),
        )

        fun MediaCodecInfo.videoCaps(mime: String): VideoCapabilities =
            getCapabilitiesForType(mime).videoCapabilities!!
    }
}
