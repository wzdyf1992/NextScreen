package com.nextscreen.app.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nextscreen.app.App
import com.nextscreen.app.Prefs
import com.nextscreen.app.service.Notifications
import com.nextscreen.app.ui.PlayerActivity
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the single ExoPlayer instance and the DLNA transport state.
 * Commands may be invoked from any thread; player access is marshalled to the main thread and the
 * state fields are volatile snapshots readable from the HTTP threads.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CastPlayer(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<CastListener>()
    val exo: ExoPlayer

    @Volatile var media: CastMedia? = null
        private set
    @Volatile var nextMedia: CastMedia? = null
        private set
    @Volatile var state: TransportState = TransportState.NO_MEDIA_PRESENT
        private set
    @Volatile var positionMs: Long = 0
        private set
    @Volatile var durationMs: Long = 0
        private set
    @Volatile var muted: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var ended: Boolean = false
        private set

    @Volatile private var stoppedByCommand = false
    @Volatile private var firstReady = false
    @Volatile private var pendingLoad = false
    @Volatile private var retrying = false
    @Volatile private var imageState = TransportState.STOPPED
    private var qualityApplied = false
    private var retryCount = 0

    val transportStatus: String get() = if (lastError != null) "ERROR_OCCURRED" else "OK"

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            updateTimes()
            main.postDelayed(this, 500)
        }
    }

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSource = DefaultDataSource.Factory(ctx, http)
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 120_000, 1_500, 3_000)
            .build()
        exo = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(playerListener())
        exo.setPlaybackSpeed(Prefs.speed)
        main.post(ticker)
    }

    fun addListener(l: CastListener) { listeners.addIfAbsent(l) }
    fun removeListener(l: CastListener) { listeners.remove(l) }

    // ================================================================= commands (any thread)

    fun setUri(
        uri: String,
        metadata: String?,
        autoPlay: Boolean = true,
        fromAirPlay: Boolean = false,
        titleOverride: String? = null,
        startPositionMs: Long = 0L,
    ) {
        if (!fromAirPlay) {
            runCatching { App.instance.airPlayServer.onDlnaTakeover() }
        }
        val base = CastMedia.create(uri, metadata)
        val m = if (titleOverride != null) base.copy(title = titleOverride) else base
        Log.i(TAG, "setUri kind=${m.kind} mime=${m.mime} title=${m.title} uri=$uri fromAirPlay=$fromAirPlay")
        media = m
        nextMedia = null
        ended = false
        positionMs = startPositionMs.coerceAtLeast(0L)
        durationMs = m.durationHintMs
        pendingLoad = true
        state = if (autoPlay) {
            if (m.kind == MediaKind.IMAGE) TransportState.PLAYING else TransportState.TRANSITIONING
        } else {
            TransportState.STOPPED
        }
        main.post { load(m, autoPlay, startPositionMs.coerceAtLeast(0L), recordHistory = !isEphemeralProxy(uri)) }
    }

    fun setNextUri(uri: String?, metadata: String?) {
        nextMedia = if (uri.isNullOrBlank()) null else CastMedia.create(uri, metadata)
        main.post {
            if (ended && nextMedia != null) advance() else notifyState()
        }
    }

    fun play() {
        main.post {
            val m = media ?: return@post
            if (m.kind == MediaKind.IMAGE) {
                imageState = TransportState.PLAYING
                notifyState()
                launchUi()
                return@post
            }
            lastError = null
            if (exo.mediaItemCount == 0) {
                exo.setMediaItem(m.toMediaItem())
                stoppedByCommand = true
            }
            if (ended) {
                ended = false
                exo.seekTo(0)
            }
            if (stoppedByCommand || exo.playbackState == Player.STATE_IDLE) {
                stoppedByCommand = false
                firstReady = false
                exo.prepare()
            }
            exo.play()
            notifyState()
            launchUi()
        }
    }

    fun pause() {
        main.post {
            if (media?.kind == MediaKind.IMAGE) imageState = TransportState.PAUSED else exo.pause()
            notifyState()
        }
    }

    fun stop(fromController: Boolean) {
        main.post {
            main.removeCallbacks(retryRunnable)
            retrying = false
            if (media?.kind == MediaKind.IMAGE) {
                imageState = TransportState.STOPPED
            } else {
                exo.pause()
                exo.stop()
                exo.seekTo(0)
            }
            stoppedByCommand = true
            ended = false
            positionMs = 0
            notifyState()
            if (fromController) listeners.forEach { it.onStopRequested() }
        }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        positionMs = target
        main.post {
            if (media == null || media?.kind == MediaKind.IMAGE) return@post
            if (ended) ended = false
            exo.seekTo(target)
            notifyState()
        }
    }

    fun next() {
        main.post { if (nextMedia != null) advance() }
    }

    fun setMute(m: Boolean) {
        muted = m
        main.post {
            exo.volume = if (m) 0f else 1f
            listeners.forEach { it.onVolumeChanged() }
        }
    }

    fun transportActions(): String {
        val base = when (state) {
            TransportState.PLAYING, TransportState.TRANSITIONING -> "Pause,Stop,Seek"
            TransportState.PAUSED -> "Play,Stop,Seek"
            TransportState.STOPPED -> "Play,Seek"
            TransportState.NO_MEDIA_PRESENT -> ""
        }
        return if (nextMedia != null && base.isNotEmpty()) "$base,Next" else base
    }

    // ================================================================= UI helpers (main thread)

    val speed: Float get() = exo.playbackParameters.speed

    /** Persistent speed change (applied to following videos when "remember speed" is on). */
    fun setSpeed(s: Float) {
        exo.setPlaybackSpeed(s)
        if (Prefs.rememberSpeed) Prefs.speed = s
        listeners.forEach { it.onStateChanged() }
    }

    /** Temporary speed (long-press fast forward) – not persisted. */
    fun setTemporarySpeed(s: Float) {
        exo.setPlaybackSpeed(s)
    }

    fun videoQualities(): List<QualityOption> {
        val list = ArrayList<QualityOption>()
        for (g in exo.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val w = f.width
                val h = f.height
                val short = if (w > 0 && h > 0) minOf(w, h) else 0
                val fps = if (f.frameRate > 40f) "60" else ""
                val label = qualityLabel(short) + fps
                val detail = buildList {
                    if (w > 0 && h > 0) add("${w}×$h")
                    if (f.bitrate > 0) add(String.format(Locale.US, "%.1f Mbps", f.bitrate / 1_000_000f))
                }.joinToString(" · ").ifEmpty { null }
                list += QualityOption(label, detail, g.mediaTrackGroup, i, short, f.bitrate, g.isTrackSelected(i))
            }
        }
        return list.sortedWith(compareByDescending<QualityOption> { it.shortSide }.thenByDescending { it.bitrate })
    }

    fun isAutoQuality(): Boolean =
        exo.trackSelectionParameters.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }

    /** null = automatic (adaptive). The choice becomes the default for following videos. */
    fun selectQuality(opt: QualityOption?) {
        val params = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (opt == null) {
            Prefs.quality = "auto"
        } else {
            params.setOverrideForType(TrackSelectionOverride(opt.group, opt.index))
            val top = videoQualities().firstOrNull()
            Prefs.quality = if (top != null && top.shortSide == opt.shortSide && top.bitrate == opt.bitrate) "highest" else opt.shortSide.toString()
        }
        exo.trackSelectionParameters = params.build()
        listeners.forEach { it.onTracksInfoChanged() }
    }

    fun currentQualityLabel(): String {
        val opts = videoQualities()
        if (opts.size > 1 && isAutoQuality()) return "自动"
        val vs = exo.videoSize
        if (vs.width > 0 && vs.height > 0) return qualityLabel(minOf(vs.width, vs.height))
        return opts.firstOrNull { it.selected }?.label ?: "画质"
    }

    // ================================================================= internals

    private fun load(m: CastMedia, autoPlay: Boolean, startPositionMs: Long = 0L, recordHistory: Boolean = true) {
        if (media !== m) return
        pendingLoad = false
        stoppedByCommand = false
        firstReady = false
        retrying = false
        retryCount = 0
        lastError = null
        qualityApplied = false
        ended = false
        main.removeCallbacks(retryRunnable)
        exo.setPlaybackSpeed(Prefs.speed)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        if (m.kind == MediaKind.IMAGE) {
            exo.stop()
            exo.clearMediaItems()
            imageState = if (autoPlay) TransportState.PLAYING else TransportState.STOPPED
        } else {
            if (startPositionMs > 0L) {
                exo.setMediaItem(m.toMediaItem(), startPositionMs)
            } else {
                exo.setMediaItem(m.toMediaItem())
            }
            exo.prepare()
            exo.playWhenReady = autoPlay
        }
        if (recordHistory) {
            try { App.instance.history.add(m) } catch (e: Exception) { Log.w(TAG, "history", e) }
        }
        listeners.forEach { it.onMediaChanged(m) }
        notifyState()
        if (autoPlay) launchUi()
    }

    private fun isEphemeralProxy(uri: String): Boolean =
        uri.startsWith("http://127.0.0.1") || uri.startsWith("http://localhost") || uri.startsWith("http://[::1]")

    private fun advance() {
        val n = nextMedia ?: return
        Log.i(TAG, "advance to next: ${n.title}")
        nextMedia = null
        media = n
        ended = false
        positionMs = 0
        durationMs = n.durationHintMs
        pendingLoad = true
        load(n, true)
    }

    private fun computeState(): TransportState {
        val m = media ?: return TransportState.NO_MEDIA_PRESENT
        if (pendingLoad || retrying) return TransportState.TRANSITIONING
        if (m.kind == MediaKind.IMAGE) return imageState
        if (stoppedByCommand || ended || lastError != null) return TransportState.STOPPED
        if (exo.playbackState == Player.STATE_IDLE) return TransportState.STOPPED
        if (!exo.playWhenReady) return if (firstReady) TransportState.PAUSED else TransportState.STOPPED
        if (!firstReady) return TransportState.TRANSITIONING
        return TransportState.PLAYING
    }

    private fun notifyState() {
        updateTimes()
        state = computeState()
        listeners.forEach { it.onStateChanged() }
    }

    private fun updateTimes() {
        if (media?.kind == MediaKind.IMAGE) return
        val d = exo.duration
        if (d > 0) durationMs = d
        if (ended) {
            if (durationMs > 0) positionMs = durationMs
        } else if (!pendingLoad && !stoppedByCommand && exo.mediaItemCount > 0) {
            positionMs = exo.currentPosition.coerceAtLeast(0)
        }
    }

    private val retryRunnable = Runnable {
        if (exo.playerError != null && media != null) {
            Log.i(TAG, "retrying playback ($retryCount)")
            exo.prepare()
        }
    }

    private fun handleError(e: PlaybackException) {
        Log.w(TAG, "player error ${e.errorCodeName}", e)
        val code = e.errorCode
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        val retriable = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            code == PlaybackException.ERROR_CODE_TIMEOUT
        if (retriable && retryCount < 3) {
            retryCount++
            retrying = true
            main.postDelayed(retryRunnable, 1500L * retryCount)
            notifyState()
            return
        }
        retrying = false
        val msg = friendlyError(e)
        lastError = msg
        notifyState()
        listeners.forEach { it.onError(msg) }
    }

    private fun friendlyError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> "网络连接失败，请检查平板的网络连接"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "视频地址无法访问（可能已过期或需要授权）\n请在手机上重新投屏"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "视频文件不存在"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "不允许的明文网络请求"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "视频格式无法解析"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "解码失败：本设备可能不支持该视频编码/分辨率\n可尝试在手机端降低清晰度后重新投屏"
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> "该视频受版权保护（DRM），无法播放"
        else -> "播放出错（${e.errorCodeName}）"
    }

    private fun maybeApplyQuality(tracks: Tracks) {
        if (qualityApplied) return
        if (tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) return
        qualityApplied = true
        val opts = videoQualities()
        if (opts.size <= 1) return
        val pick: QualityOption? = when (val q = Prefs.quality) {
            "auto" -> null
            "highest" -> opts.first()
            else -> {
                val target = q.toIntOrNull() ?: Int.MAX_VALUE
                opts.firstOrNull { it.shortSide in 1..target } ?: opts.last()
            }
        }
        if (pick != null) {
            Log.i(TAG, "apply default quality ${Prefs.quality} -> ${pick.label}")
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setOverrideForType(TrackSelectionOverride(pick.group, pick.index))
                .build()
        }
    }

    private fun playerListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    firstReady = true
                    retrying = false
                    retryCount = 0
                }
                Player.STATE_ENDED -> if (media?.kind != MediaKind.IMAGE && !pendingLoad && !stoppedByCommand) {
                    if (nextMedia != null) {
                        advance()
                        return
                    }
                    ended = true
                }
            }
            notifyState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = notifyState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = notifyState()
        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onTracksChanged(tracks: Tracks) {
            maybeApplyQuality(tracks)
            listeners.forEach { it.onTracksInfoChanged() }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            listeners.forEach { it.onTracksInfoChanged() }
        }
    }

    private fun launchUi() {
        if (PlayerActivity.isVisible) return
        val m = media ?: return
        val app = App.instance
        val canStart = app.isForeground || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            Settings.canDrawOverlays(app)
        if (canStart) {
            try {
                app.startActivity(Intent(app, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "start player failed", e)
            }
        }
        if (!app.isForeground) Notifications.showIncomingCast(app, m.title)
    }

    companion object {
        private const val TAG = "CastPlayer"
        val USER_AGENT: String =
            "NextScreen/1.0 (Linux;Android ${Build.VERSION.RELEASE}) ExoPlayerLib/1.9.2"

        fun qualityLabel(shortSide: Int): String = when {
            shortSide >= 2160 -> "4K"
            shortSide >= 1440 -> "2K"
            shortSide >= 1080 -> "1080P"
            shortSide >= 720 -> "720P"
            shortSide >= 480 -> "480P"
            shortSide >= 360 -> "360P"
            shortSide > 0 -> "${shortSide}P"
            else -> "原画"
        }
    }
}
