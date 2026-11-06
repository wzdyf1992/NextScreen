package com.nextscreen.app.airplay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.nextscreen.app.airplay.DecoderSelector.Companion.videoCaps
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency H.264 / H.265 hardware MediaCodec video decoder for AirPlay Screen Mirroring.
 * Uses a dedicated output drain thread so static desktop frames (macOS cursor / window stops) are
 * rendered immediately without waiting for the next incoming RTP packet.
 */
class MirrorVideoRenderer(ctx: Context) {

    private val lock = Object()
    private val pipeline = MirrorGlPipeline()
    val selector = DecoderSelector(ctx)

    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 60
    private var codec: MediaCodec? = null
    private var drainFlag: AtomicBoolean? = null
    private var drainThread: Thread? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false

    @Volatile var fps = 0
        private set
    @Volatile var bitrateBps = 0L
        private set
    @Volatile var frameCount = 0L
        private set
    @Volatile var codecName = ""
        private set
    @Volatile var droppedFrames = 0L
        private set

    private var framesThisSec = 0
    private var bytesThisSec = 0L
    private var lastStatReset = 0L

    fun setScaleMode(mode: String) {
        pipeline.setScaleMode(mode)
    }

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w <= 0 || h <= 0) return@synchronized
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
        stopCodecLocked()
    }

    fun setSurface(surface: Surface, width: Int = 0, height: Int = 0) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface, width, height)
    }

    fun updateSurfaceSize(surface: Surface, width: Int, height: Int) {
        pipeline.updateDisplaySize(surface, width, height)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265Pref: Boolean?): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265Pref != false) selector.hevc(avcDecoder, w, h, fps, force = h265Pref == true) else null
        maxFps = fps
        Log.i(TAG, "Decoders selected: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(
            avcDecoder?.let { it to DecoderSelector.AVC },
            hevcDecoder?.let { it to DecoderSelector.HEVC },
        ).map { (info, mime) ->
            runCatching {
                info.videoCaps(mime).let {
                    val maxSide = maxOf(it.supportedWidths.upper, it.supportedHeights.upper)
                    maxSide to maxSide
                }
            }.getOrDefault(1920 to 1920)
        }.reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1920)

    fun startSession() = synchronized(lock) {
        resetStats()
        pipeline.start()
    }

    fun stopSession() = synchronized(lock) {
        stopCodecLocked()
        pipeline.clearFrame()
        resetStats()
    }

    fun tickStats() = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (lastStatReset > 0L && now - lastStatReset >= 1200L) {
            fps = framesThisSec
            bitrateBps = bytesThisSec * 8
            framesThisSec = 0
            bytesThisSec = 0L
            lastStatReset = now
        }
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            recordStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                if (!isKeyframe(data, isH265)) {
                    if (codec != null) stopCodecLocked()
                    return
                }
                stopCodecLocked()
            }

            try {
                if (codec == null) startCodecLocked(isH265)
                feedToCodecLocked(data, ntpTimeNs)
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodecLocked()
            }
        }
    }

    private fun feedToCodecLocked(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000L, 0)
                firstFrameQueued = true
                return
            }
        }
        droppedFrames++
    }

    private fun isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodecLocked(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val surface = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("No decoder for $mime")

        firstFrameQueued = false
        try {
            startWithLadder(info, mime, surface, h265)
        } catch (e: Exception) {
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, falling back to software ${sw.name}", e)
            startWithLadder(sw, mime, surface, h265)
        }
        Log.i(TAG, "Mirror decoder started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun startWithLadder(info: MediaCodecInfo, mime: String, surface: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = buildFormat(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                startDecoderInstance(MediaCodec.createByCodecName(info.name), format, surface, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                tryNum++
            }
        }
    }

    private fun buildFormat(mime: String, info: MediaCodecInfo) =
        MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
            if (selector.adaptive(info, mime)) {
                setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
                setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1)
            }
        }

    private fun startDecoderInstance(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = if (h265) "HEVC" else "H.264"
        val flag = AtomicBoolean(true)
        drainFlag = flag
        drainThread = Thread({ drainLoop(c, flag) }, "MirrorVideoDrain").apply {
            isDaemon = true
            start()
        }
    }

    private fun drainLoop(c: MediaCodec, running: AtomicBoolean) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val idx = try {
                c.dequeueOutputBuffer(info, DRAIN_WAIT_US)
            } catch (_: IllegalStateException) {
                break
            } catch (_: Exception) {
                break
            }
            if (!running.get()) break
            if (idx >= 0) {
                try {
                    c.releaseOutputBuffer(idx, info.size > 0)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun stopCodecLocked() {
        drainFlag?.set(false)
        drainFlag = null
        val t = drainThread
        drainThread = null
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(350) } catch (_: InterruptedException) {}
        }
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
    }

    private fun recordStats(bytes: Int) {
        val now = System.currentTimeMillis()
        if (now - lastStatReset >= 1000L) {
            fps = framesThisSec
            bitrateBps = bytesThisSec * 8
            framesThisSec = 0
            bytesThisSec = 0L
            lastStatReset = now
        }
        framesThisSec++
        bytesThisSec += bytes
        frameCount++
    }

    private fun resetStats() {
        fps = 0
        bitrateBps = 0L
        frameCount = 0L
        codecName = ""
        droppedFrames = 0L
        framesThisSec = 0
        bytesThisSec = 0L
        lastStatReset = System.currentTimeMillis()
    }

    fun release() = synchronized(lock) {
        stopCodecLocked()
        pipeline.release()
        resetStats()
    }

    companion object {
        private const val TAG = "MirrorVideoRenderer"
        private const val FEED_WAIT_US = 15_000L
        private const val DRAIN_WAIT_US = 15_000L
        private const val FEED_RETRIES = 12
        private const val FIRST_FEED_RETRIES = 50
    }
}
