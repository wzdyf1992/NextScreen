package com.nextscreen.app.airplay

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.os.ConfigurationCompat
import com.nextscreen.app.App
import com.nextscreen.app.Prefs
import com.nextscreen.app.player.CastListener
import com.nextscreen.app.player.CastPlayer
import com.nextscreen.app.player.TransportState
import com.nextscreen.app.service.Notifications
import com.nextscreen.app.service.RendererStatus
import com.nextscreen.app.ui.MirrorActivity
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

/**
 * Manages the UxPlay RAOP / AirPlay server, mDNS advertisement (`_airplay._tcp` & `_raop._tcp`),
 * screen mirroring video/audio pipelines, PIN pairing state, and AirPlay Video handoff to [CastPlayer].
 */
class AirPlayServer(
    private val ctx: Context,
    private val castPlayer: CastPlayer,
) : AirPlayCallback {

    interface MirrorListener {
        fun onMirrorStateChanged(active: Boolean) {}
        fun onMirrorVideoSize(srcW: Int, srcH: Int, width: Int, height: Int) {}
        fun onPinChanged(pin: String?) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<MirrorListener>()
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nsd = NsdAdvertiser(ctx)
    val videoRenderer = MirrorVideoRenderer(ctx)

    @Volatile var running = false
        private set
    @Volatile var port = 0
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var mirroring = false
        private set
    @Volatile var connectionCount = 0
        private set
    @Volatile var sourceWidth = 0
        private set
    @Volatile var sourceHeight = 0
        private set
    @Volatile var mirrorWidth = 0
        private set
    @Volatile var mirrorHeight = 0
        private set
    @Volatile var audioCodecName = ""
        private set
    @Volatile var currentPin: String? = null
        private set
    @Volatile private var airPlayVideoActive = false

    private var nativeHandle = 0L

    private val playbackSyncRunnable = object : Runnable {
        override fun run() {
            if (!airPlayVideoActive || nativeHandle == 0L) return
            val st = castPlayer.state
            val posSec = castPlayer.positionMs / 1000f
            val durSec = if (castPlayer.durationMs > 0) castPlayer.durationMs / 1000f else 0f
            val ready = st == TransportState.PLAYING || st == TransportState.PAUSED
            val rate = if (st == TransportState.PLAYING) 1f else 0f
            AirPlayBridge.nativeUpdatePlaybackInfo(nativeHandle, posSec, durSec, rate, ready)
            if (castPlayer.ended) {
                airPlayVideoActive = false
                AirPlayBridge.nativeUpdatePlaybackInfo(nativeHandle, 0f, -1f, 0f, false)
                return
            }
            main.postDelayed(this, 250L)
        }
    }

    init {
        videoRenderer.setScaleMode(Prefs.resizeKey)
        castPlayer.addListener(object : CastListener {
            override fun onStateChanged() {
                if (airPlayVideoActive && nativeHandle != 0L) {
                    val st = castPlayer.state
                    val ready = st == TransportState.PLAYING || st == TransportState.PAUSED
                    if (ready) {
                        val posSec = castPlayer.positionMs / 1000f
                        val durSec = if (castPlayer.durationMs > 0) castPlayer.durationMs / 1000f else 0f
                        val rate = if (st == TransportState.PLAYING) 1f else 0f
                        AirPlayBridge.nativeUpdatePlaybackInfo(nativeHandle, posSec, durSec, rate, true)
                    }
                }
            }
        })
    }

    fun addListener(l: MirrorListener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: MirrorListener) {
        listeners.remove(l)
    }

    @Synchronized
    fun start() {
        if (!Prefs.airPlayEnabled) {
            stop()
            return
        }
        if (running && nativeHandle != 0L) return
        shutdownInternal()

        try {
            val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
            val burst = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
            AirPlayBridge.nativeSetDefaultStreamValues(sampleRate, burst)

            val hwAddr = resolveHwAddr()
            val name = Prefs.deviceName
            val keyFile = ctx.filesDir.resolve("airplay_ed25519.bin").absolutePath
            val requirePin = Prefs.airPlayPin

            val handle = AirPlayBridge.nativeInit(
                callback = this,
                hwAddr = hwAddr,
                name = name,
                keyFile = keyFile,
                nohold = true,
                requirePin = requirePin,
            )
            if (handle == 0L) {
                error = "AirPlay 初始化失败"
                RendererStatus.updateAirPlay(false, 0, error)
                Log.e(TAG, "nativeInit returned 0")
                return
            }
            nativeHandle = handle

            AirPlayBridge.nativeAudioConfigure(
                handle = handle,
                cushionMs = 0,
                percentilePct = 95,
                oboeBufferFrames = 0,
                realtimePriority = true,
                lowLatency = true,
            )

            val maxFps = Prefs.airPlayFps
            val (reqW, reqH) = desiredDisplaySize(clamp = false)
            val h265Pref: Boolean? = when (Prefs.airPlayH265) {
                "on" -> true
                "off" -> false
                else -> null
            }
            val h265 = videoRenderer.selectDecoders(reqW, reqH, maxFps, h265Pref)
            val alac = videoRenderer.selector.hasAudioAlacDecoder()
            val aac = true

            AirPlayBridge.nativeSetH265Enabled(handle, h265)
            AirPlayBridge.nativeSetCodecs(handle, alac, aac)
            AirPlayBridge.nativeSetHlsEnabled(handle, true)
            val lang = ConfigurationCompat.getLocales(ctx.resources.configuration)
                .toLanguageTags().replace(',', ':').ifBlank { "zh-Hans" }
            AirPlayBridge.nativeSetLang(handle, lang)
            AirPlayBridge.nativeSetFeature(handle, AirPlayBridge.FT_MIRROR, true)
            AirPlayBridge.nativeSetFeature(handle, AirPlayBridge.FT_AUDIO, true)
            AirPlayBridge.nativeSetPlist(handle, "maxFPS", maxFps)
            AirPlayBridge.nativeSetPlist(handle, "overscanned", 0)

            val (w, h) = desiredDisplaySize(clamp = true)
            videoRenderer.setResolution(w, h)
            videoRenderer.setScaleMode(Prefs.resizeKey)
            AirPlayBridge.nativeSetDisplaySize(handle, w, h, maxFps)

            val boundPort = AirPlayBridge.nativeStart(handle, 7000)
            if (boundPort <= 0) {
                val err = "AirPlay 端口启动失败"
                shutdownInternal()
                error = err
                RendererStatus.updateAirPlay(false, 0, err)
                return
            }

            val raopTxt = AirPlayBridge.nativeGetRaopTxtRecords(handle) ?: emptyMap()
            val airplayTxt = AirPlayBridge.nativeGetAirplayTxtRecords(handle) ?: emptyMap()
            val raopName = AirPlayBridge.nativeGetRaopServiceName(handle) ?: "AirPlay"
            val serverName = AirPlayBridge.nativeGetServerName(handle) ?: name

            nsd.register(
                listOf(
                    NsdAdvertiser.ServiceSpec(raopName, "_raop._tcp", boundPort, raopTxt),
                    NsdAdvertiser.ServiceSpec(serverName, "_airplay._tcp", boundPort, airplayTxt),
                )
            )

            port = boundPort
            error = null
            running = true
            RendererStatus.updateAirPlay(true, boundPort, null)
            Log.i(TAG, "AirPlay receiver ready as '$serverName' on port $boundPort (display=${w}x${h}@${maxFps}, h265=$h265, alac=$alac)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start AirPlayServer", t)
            val err = "AirPlay 启动异常: ${t.message}"
            shutdownInternal()
            error = err
            RendererStatus.updateAirPlay(false, 0, err)
        }
    }

    @Synchronized
    fun stop() {
        shutdownInternal()
        error = null
        RendererStatus.updateAirPlay(false, 0, null)
    }

    @Synchronized
    fun restart() {
        shutdownInternal()
        if (Prefs.airPlayEnabled) start()
    }

    @Synchronized
    fun disconnectClients() {
        val h = nativeHandle
        if (h != 0L) {
            AirPlayBridge.nativeDisconnectClients(h)
        }
        if (mirroring) {
            mirroring = false
            videoRenderer.stopSession()
            main.post { listeners.forEach { it.onMirrorStateChanged(false) } }
        }
        if (airPlayVideoActive) {
            airPlayVideoActive = false
            main.removeCallbacks(playbackSyncRunnable)
        }
        clearPin()
    }

    /** Called when a DLNA controller starts casting so an active AirPlay session yields cleanly. */
    fun onDlnaTakeover() {
        airPlayVideoActive = false
        main.removeCallbacks(playbackSyncRunnable)
        if (mirroring || connectionCount > 0) {
            disconnectClients()
        }
    }

    fun attachMirrorSurface(surface: Surface, width: Int, height: Int) {
        videoRenderer.setScaleMode(Prefs.resizeKey)
        videoRenderer.setSurface(surface, width, height)
    }

    fun updateMirrorSurfaceSize(surface: Surface, width: Int, height: Int) {
        videoRenderer.updateSurfaceSize(surface, width, height)
    }

    fun detachMirrorSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    fun updateScaleMode(mode: String) {
        videoRenderer.setScaleMode(mode)
    }

    private fun shutdownInternal() {
        nsd.unregister()
        val h = nativeHandle
        nativeHandle = 0L
        if (h != 0L) {
            try { AirPlayBridge.nativeAudioStop(h) } catch (_: Throwable) {}
            try { AirPlayBridge.nativeStop(h) } catch (_: Throwable) {}
            try { AirPlayBridge.nativeDestroy(h) } catch (_: Throwable) {}
        }
        videoRenderer.release()
        val wasMirroring = mirroring
        mirroring = false
        airPlayVideoActive = false
        connectionCount = 0
        sourceWidth = 0
        sourceHeight = 0
        mirrorWidth = 0
        mirrorHeight = 0
        audioCodecName = ""
        port = 0
        running = false
        main.removeCallbacks(playbackSyncRunnable)
        clearPin()
        if (wasMirroring) {
            main.post { listeners.forEach { it.onMirrorStateChanged(false) } }
        }
    }

    // ================================================================= AirPlayCallback (native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        val iw = w.toInt()
        val ih = h.toInt()
        val isrcW = srcW.toInt()
        val isrcH = srcH.toInt()
        if (iw > 0 && ih > 0) {
            sourceWidth = isrcW
            sourceHeight = isrcH
            mirrorWidth = iw
            mirrorHeight = ih
            videoRenderer.setResolution(iw, ih)
            val becameActive = !mirroring
            mirroring = true
            main.post {
                if (becameActive) {
                    stopConflictingCast()
                    launchMirrorUi()
                    listeners.forEach { it.onMirrorStateChanged(true) }
                }
                listeners.forEach { it.onMirrorVideoSize(isrcW, isrcH, iw, ih) }
            }
        }
        Log.i(TAG, "Mirror video size: src=${isrcW}x${isrcH} -> ${iw}x${ih}")
    }

    override fun onMirrorRunning(running: Boolean) {
        Log.i(TAG, "onMirrorRunning=$running")
        if (running) {
            clearPin()
            mirroring = true
            videoRenderer.startSession()
            main.post {
                stopConflictingCast()
                launchMirrorUi()
                listeners.forEach { it.onMirrorStateChanged(true) }
            }
        } else {
            val wasMirroring = mirroring
            mirroring = false
            videoRenderer.stopSession()
            if (wasMirroring) {
                main.post {
                    Notifications.cancelIncoming(ctx)
                    listeners.forEach { it.onMirrorStateChanged(false) }
                }
            }
        }
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        audioCodecName = when (ct) {
            1 -> "PCM"
            2 -> "ALAC"
            4 -> "AAC-LC"
            8 -> "AAC-ELD"
            else -> "Audio($ct)"
        }
        val h = nativeHandle
        if (h != 0L) {
            AirPlayBridge.nativeAudioStart(h)
            AirPlayBridge.nativeAudioFormat(h, ct, spf)
        }
        Log.i(TAG, "Audio format: $audioCodecName spf=$spf usingScreen=$usingScreen")
    }

    override fun onAudioTeardown() {
        Log.i(TAG, "onAudioTeardown")
    }

    override fun onClientVolume(db: Float) {
        val gain = if (db <= -144f) 0f else 10f.pow(db.coerceIn(-30f, 0f) / 20f)
        val h = nativeHandle
        if (h != 0L) {
            AirPlayBridge.nativeAudioSetClientGain(h, gain)
        }
    }

    override fun onClientVolumeQuery(): Float = 0f

    override fun onConnectionInit() {
        connectionCount++
        Log.i(TAG, "AirPlay client connected (count=$connectionCount)")
    }

    override fun onConnectionDestroy() {
        connectionCount = (connectionCount - 1).coerceAtLeast(0)
        Log.i(TAG, "AirPlay client disconnected (count=$connectionCount)")
        if (connectionCount == 0) {
            val h = nativeHandle
            if (h != 0L) {
                AirPlayBridge.nativeAudioStop(h)
                AirPlayBridge.nativeAudioSetClientGain(h, 1f)
            }
            audioCodecName = ""
            if (mirroring) {
                mirroring = false
                videoRenderer.stopSession()
                main.post {
                    Notifications.cancelIncoming(ctx)
                    listeners.forEach { it.onMirrorStateChanged(false) }
                }
            }
            if (airPlayVideoActive) {
                airPlayVideoActive = false
                main.post {
                    main.removeCallbacks(playbackSyncRunnable)
                    castPlayer.stop(fromController = true)
                }
            }
            clearPin()
        }
    }

    override fun onConnectionReset(reason: Int) {
        Log.i(TAG, "AirPlay connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        if (currentPin == pin) return
        currentPin = pin
        Log.i(TAG, "AirPlay PIN requested: $pin")
        main.post {
            launchMirrorUi(pinNotice = pin)
            listeners.forEach { it.onPinChanged(pin) }
        }
    }

    override fun onVideoPlay(location: String, startPosition: Float) {
        clearPin()
        if (mirroring) {
            mirroring = false
            videoRenderer.stopSession()
            main.post { listeners.forEach { it.onMirrorStateChanged(false) } }
        }
        airPlayVideoActive = true
        val startMs = (startPosition.coerceAtLeast(0f) * 1000f).toLong()
        castPlayer.setUri(
            uri = location,
            metadata = null,
            autoPlay = true,
            fromAirPlay = true,
            titleOverride = "AirPlay 视频投屏",
            startPositionMs = startMs,
        )
        main.post {
            main.removeCallbacks(playbackSyncRunnable)
            main.postDelayed(playbackSyncRunnable, 200L)
        }
    }

    override fun onVideoScrub(position: Float) {
        if (!airPlayVideoActive) return
        castPlayer.seekTo((position.coerceAtLeast(0f) * 1000f).toLong())
    }

    override fun onVideoRate(rate: Float) {
        if (!airPlayVideoActive) return
        if (rate > 0f) castPlayer.play() else castPlayer.pause()
    }

    override fun onVideoStop() {
        if (!airPlayVideoActive) return
        airPlayVideoActive = false
        main.post {
            main.removeCallbacks(playbackSyncRunnable)
            castPlayer.stop(fromController = true)
        }
    }

    override fun onVideoSessionPoll() {
        // Handled natively via android_callbacks_update_playback_info snapshot
    }

    override fun onLog(msg: String) {
        Log.d(TAG, msg)
    }

    // ================================================================= helpers

    private fun stopConflictingCast() {
        airPlayVideoActive = false
        main.removeCallbacks(playbackSyncRunnable)
        if (castPlayer.state != TransportState.NO_MEDIA_PRESENT && castPlayer.state != TransportState.STOPPED) {
            castPlayer.stop(fromController = true)
        }
    }

    private fun clearPin() {
        if (currentPin == null) return
        currentPin = null
        main.post { listeners.forEach { it.onPinChanged(null) } }
    }

    private fun launchMirrorUi(pinNotice: String? = null) {
        if (MirrorActivity.isVisible) return
        val app = App.instance
        val canStart = app.isForeground || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            Settings.canDrawOverlays(app)
        if (canStart) {
            try {
                app.startActivity(
                    Intent(app, MirrorActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch MirrorActivity", e)
            }
        }
        if (!app.isForeground) {
            val label = if (pinNotice != null) "AirPlay 配对码：$pinNotice" else "AirPlay 屏幕镜像"
            Notifications.showIncomingMirror(app, label)
        }
    }

    private fun desiredDisplaySize(clamp: Boolean): Pair<Int, Int> {
        val raw = realDisplayPixels()
        val landscapeRaw = maxOf(raw.first, raw.second) to minOf(raw.first, raw.second)
        val (baseW, baseH) = when (val pref = Prefs.airPlayResolution) {
            "2560x1440" -> 2560 to 1440
            "1920x1080" -> 1920 to 1080
            "1280x720" -> 1280 to 720
            else -> {
                // Auto: use physical tablet screen aspect, capped at 2560 long edge for smooth 60fps
                val longEdge = landscapeRaw.first
                if (longEdge > 2560) {
                    val scale = 2560f / longEdge.toFloat()
                    val h = ((landscapeRaw.second * scale).toInt() and 1.inv()).coerceAtLeast(720)
                    2560 to h
                } else {
                    (landscapeRaw.first and 1.inv()) to (landscapeRaw.second and 1.inv())
                }
            }
        }
        if (!clamp) return baseW to baseH
        val (maxW, maxH) = videoRenderer.maxResolution()
        return baseW.coerceAtMost(maxW) to baseH.coerceAtMost(maxH)
    }

    private fun realDisplayPixels(): Pair<Int, Int> = try {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    } catch (_: Exception) {
        1920 to 1080
    }

    private fun resolveHwAddr(): ByteArray {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (ni in ifaces) {
                    if (ni.name.startsWith("wlan") || ni.name.startsWith("eth")) {
                        val mac = ni.hardwareAddress
                        if (mac != null && mac.size == 6 && mac.any { it != 0.toByte() } &&
                            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })
                        ) {
                            return mac
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        // Deterministic locally-administered unicast MAC derived from persistent device UUID
        val uuid = runCatching { UUID.fromString(Prefs.uuid) }.getOrElse { UUID.randomUUID() }
        val bb = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        val mac = ByteArray(6)
        for (i in 0 until 6) {
            mac[i] = (bb[i].toInt() xor bb[i + 6].toInt()).toByte()
        }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        if (mac.drop(1).all { it == 0.toByte() }) mac[5] = 0x5A
        return mac
    }

    companion object {
        private const val TAG = "AirPlayServer"
    }
}
