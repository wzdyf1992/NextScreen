package com.nextscreen.app.ui

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.nextscreen.app.App
import com.nextscreen.app.Prefs
import com.nextscreen.app.R
import com.nextscreen.app.player.CastListener
import com.nextscreen.app.player.CastMedia
import com.nextscreen.app.player.MediaKind
import com.nextscreen.app.player.TransportState
import com.nextscreen.app.service.Notifications
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), CastListener {

    companion object {
        @Volatile var isVisible = false
            private set

        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        private const val HIDE_DELAY = 4000L
        private const val DRAG_NONE = 0
        private const val DRAG_SEEK = 1
        private const val DRAG_VOLUME = 2
        private const val DRAG_BRIGHTNESS = 3
        private const val DRAG_IGNORED = 4
    }

    private val cast get() = App.instance.castPlayer
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var audioPanel: View
    private lateinit var audioCover: ImageView
    private lateinit var audioTitle: TextView
    private lateinit var gestureLayer: View
    private lateinit var loadingPanel: View
    private lateinit var loadingText: TextView
    private lateinit var gestureHint: TextView
    private lateinit var speedBadge: TextView
    private lateinit var endPanel: View
    private lateinit var endText: TextView
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var seekRow: View
    private lateinit var titleView: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timePos: TextView
    private lateinit var timeDur: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnRatio: TextView
    private lateinit var sidePanel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelOptions: LinearLayout
    private lateinit var panelFooter: TextView

    private lateinit var audio: AudioManager
    private lateinit var gestureDetector: GestureDetector
    private var touchSlop = 24

    private var controlsShown = false
    private var userSeeking = false
    private var lastPlaying: Boolean? = null
    private var lastBack = 0L
    private var longPressing = false
    private var speedBeforeLongPress = 1f
    private var dragMode = DRAG_NONE
    private var downX = 0f
    private var downY = 0f
    private var seekStart = 0L
    private var seekTarget = 0L
    private var volStart = 0
    private var brightStart = 0.5f

    private val hideControlsRunnable = Runnable { showControls(false) }
    private val hideHintRunnable = Runnable { gestureHint.visibility = View.GONE }
    private val stopCloseRunnable = Runnable { finish() }
    private val endCloseRunnable = Runnable { finish() }
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    // ================================================================= lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(R.layout.activity_player)
        bindViews()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        playerView.setKeepContentOnPlayerReset(true)
        playerView.setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_OFF)
        playerView.resizeMode = Prefs.resizeMode

        setupControls()
        setupGestures()
        applyInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        cast.addListener(this)
        if (cast.media == null) {
            finish()
            return
        }
        bindMedia(cast.media)
        hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        bindMedia(cast.media)
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        playerView.player = cast.exo
        Notifications.cancelIncoming(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
        refreshState()
    }

    override fun onStop() {
        isVisible = false
        handler.removeCallbacks(tick)
        playerView.player = null
        super.onStop()
    }

    override fun onDestroy() {
        cast.removeListener(this)
        if (longPressing) cast.setTemporarySpeed(speedBeforeLongPress)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        playerView = findViewById(R.id.player_view)
        imageView = findViewById(R.id.image_view)
        audioPanel = findViewById(R.id.audio_panel)
        audioCover = findViewById(R.id.audio_cover)
        audioTitle = findViewById(R.id.audio_title)
        gestureLayer = findViewById(R.id.gesture_layer)
        loadingPanel = findViewById(R.id.loading_panel)
        loadingText = findViewById(R.id.loading_text)
        gestureHint = findViewById(R.id.gesture_hint)
        speedBadge = findViewById(R.id.speed_badge)
        endPanel = findViewById(R.id.end_panel)
        endText = findViewById(R.id.end_text)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        seekRow = findViewById(R.id.seek_row)
        titleView = findViewById(R.id.title)
        btnPlay = findViewById(R.id.btn_play)
        btnNext = findViewById(R.id.btn_next)
        seekBar = findViewById(R.id.seek_bar)
        timePos = findViewById(R.id.time_pos)
        timeDur = findViewById(R.id.time_dur)
        btnSpeed = findViewById(R.id.btn_speed)
        btnQuality = findViewById(R.id.btn_quality)
        btnRatio = findViewById(R.id.btn_ratio)
        sidePanel = findViewById(R.id.side_panel)
        panelTitle = findViewById(R.id.panel_title)
        panelOptions = findViewById(R.id.panel_options)
        panelFooter = findViewById(R.id.panel_footer)
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyInsets() {
        val topPad = intArrayOf(topBar.paddingLeft, topBar.paddingTop, topBar.paddingRight, topBar.paddingBottom)
        val botPad = intArrayOf(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, bottomBar.paddingBottom)
        val sidePad = intArrayOf(sidePanel.paddingLeft, sidePanel.paddingTop, sidePanel.paddingRight, sidePanel.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(topPad[0] + c.left, topPad[1] + c.top, topPad[2] + c.right, topPad[3])
            bottomBar.setPadding(botPad[0] + c.left, botPad[1], botPad[2] + c.right, botPad[3] + c.bottom)
            sidePanel.setPadding(sidePad[0], sidePad[1] + c.top, sidePad[2] + c.right, sidePad[3] + c.bottom)
            insets
        }
    }

    // ================================================================= binding

    private fun bindMedia(m: CastMedia?) {
        handler.removeCallbacks(stopCloseRunnable)
        handler.removeCallbacks(endCloseRunnable)
        endPanel.visibility = View.GONE
        errorPanel.visibility = View.GONE
        if (m == null) return
        titleView.text = m.title
        when (m.kind) {
            MediaKind.VIDEO -> {
                playerView.visibility = View.VISIBLE
                imageView.visibility = View.GONE
                audioPanel.visibility = View.GONE
            }
            MediaKind.AUDIO -> {
                playerView.visibility = View.VISIBLE
                imageView.visibility = View.GONE
                audioPanel.visibility = View.VISIBLE
                audioTitle.text = if (m.artist.isNullOrBlank()) m.title else "${m.title}\n${m.artist}"
                ImageLoader.load(m.albumArt, audioCover, 800, R.drawable.ic_music)
            }
            MediaKind.IMAGE -> {
                playerView.visibility = View.GONE
                audioPanel.visibility = View.GONE
                imageView.visibility = View.VISIBLE
                loadingPanel.visibility = View.VISIBLE
                val maxSide = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                ImageLoader.load(m.uri, imageView, maxSide) { ok ->
                    loadingPanel.visibility = View.GONE
                    if (!ok) {
                        errorText.text = "图片加载失败"
                        errorPanel.visibility = View.VISIBLE
                    }
                }
            }
        }
        val isImage = m.kind == MediaKind.IMAGE
        seekRow.visibility = if (isImage) View.GONE else View.VISIBLE
        btnPlay.visibility = if (isImage) View.GONE else View.VISIBLE
        btnSpeed.visibility = if (isImage) View.GONE else View.VISIBLE
        btnQuality.visibility = if (m.kind == MediaKind.VIDEO) View.VISIBLE else View.GONE
        btnRatio.visibility = if (m.kind == MediaKind.VIDEO) View.VISIBLE else View.GONE
        seekBar.progress = 0
        seekBar.secondaryProgress = 0
        timePos.text = fmt(0)
        timeDur.text = "--:--"
        updateSpeedButton()
        updateQualityButton()
        updateRatioButton()
        hidePanel(animate = false)
        lastPlaying = null
        showControls(true)
        refreshState()
    }

    private fun isPlayingNow(): Boolean {
        val m = cast.media ?: return false
        if (m.kind == MediaKind.IMAGE) return false
        return cast.exo.playWhenReady && !cast.ended && cast.state != TransportState.STOPPED
    }

    private fun refreshState() {
        val m = cast.media ?: return
        val exo = cast.exo
        val playing = isPlayingNow()
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnNext.visibility = if (cast.nextMedia != null) View.VISIBLE else View.GONE

        if (m.kind != MediaKind.IMAGE) {
            val loading = cast.lastError == null && !cast.ended &&
                (cast.state == TransportState.TRANSITIONING ||
                    (exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady))
            loadingPanel.visibility = if (loading) View.VISIBLE else View.GONE
            loadingText.text = if (exo.playbackState == Player.STATE_BUFFERING && cast.state == TransportState.PLAYING) "缓冲中…" else getString(R.string.loading)

            val err = cast.lastError
            if (err != null) {
                errorText.text = err
                errorPanel.visibility = View.VISIBLE
            } else {
                errorPanel.visibility = View.GONE
            }

            if (cast.ended) {
                if (Prefs.endAction == "close") {
                    handler.removeCallbacks(endCloseRunnable)
                    handler.postDelayed(endCloseRunnable, 1500)
                    endText.text = "播放完毕"
                    endPanel.visibility = View.VISIBLE
                } else if (endPanel.visibility != View.VISIBLE) {
                    endText.text = "「${m.title}」播放完毕\n等待手机投送下一集…"
                    endPanel.visibility = View.VISIBLE
                    showControls(true)
                }
            } else {
                handler.removeCallbacks(endCloseRunnable)
                endPanel.visibility = View.GONE
            }
        }

        if (lastPlaying != playing) {
            lastPlaying = playing
            if (controlsShown) {
                handler.removeCallbacks(hideControlsRunnable)
                if (playing && !sidePanel.isVisible) handler.postDelayed(hideControlsRunnable, HIDE_DELAY)
            }
        }
        updateSpeedButton()
    }

    private fun updateProgress() {
        val m = cast.media ?: return
        if (m.kind == MediaKind.IMAGE) return
        val exo = cast.exo
        val dur = exo.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        val pos = if (cast.ended) dur else exo.currentPosition.coerceAtLeast(0)
        if (!userSeeking && dragMode != DRAG_SEEK) {
            seekBar.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
            seekBar.secondaryProgress = if (dur > 0) (exo.bufferedPosition * 1000 / dur).toInt() else 0
            timePos.text = fmt(pos)
        }
        timeDur.text = when {
            dur > 0 -> fmt(dur)
            exo.isCurrentMediaItemLive -> "直播"
            else -> "--:--"
        }
        seekBar.isEnabled = dur > 0
    }

    private fun updateSpeedButton() {
        if (longPressing) return
        val s = cast.speed
        btnSpeed.text = if (abs(s - 1f) < 0.01f) getString(R.string.speed) else Prefs.speedLabel(s)
        btnSpeed.setTextColor(ContextCompat.getColor(this, if (abs(s - 1f) < 0.01f) android.R.color.white else R.color.accent))
    }

    private fun updateQualityButton() {
        btnQuality.text = cast.currentQualityLabel()
    }

    private fun updateRatioButton() {
        btnRatio.text = when (Prefs.resizeKey) {
            "fill" -> "拉伸"
            "zoom" -> "裁剪"
            else -> getString(R.string.ratio)
        }
    }

    // ================================================================= controls

    private fun setupControls() {
        findViewById<View>(R.id.btn_back).setOnClickListener { exitPlayer() }
        btnPlay.setOnClickListener { togglePlay() }
        btnNext.setOnClickListener { cast.next() }
        btnSpeed.setOnClickListener { showSpeedPanel() }
        btnQuality.setOnClickListener { showQualityPanel() }
        btnRatio.setOnClickListener { showRatioPanel() }
        findViewById<View>(R.id.btn_replay).setOnClickListener {
            handler.removeCallbacks(endCloseRunnable)
            endPanel.visibility = View.GONE
            cast.play()
        }
        findViewById<View>(R.id.btn_end_exit).setOnClickListener { exitPlayer() }
        findViewById<View>(R.id.btn_retry).setOnClickListener {
            errorPanel.visibility = View.GONE
            val m = cast.media
            if (m?.kind == MediaKind.IMAGE) bindMedia(m) else cast.play()
        }
        findViewById<View>(R.id.btn_error_exit).setOnClickListener { exitPlayer() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = cast.exo.duration
                if (dur > 0) timePos.text = fmt(dur * progress / 1000)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                val dur = cast.exo.duration
                if (dur > 0) cast.seekTo(dur * sb.progress / 1000)
                scheduleHide()
            }
        })
    }

    private fun togglePlay() {
        val m = cast.media ?: return
        if (m.kind == MediaKind.IMAGE) return
        if (isPlayingNow()) {
            cast.pause()
            showHint("已暂停")
        } else {
            cast.play()
        }
        showControls(true)
    }

    private fun exitPlayer() {
        cast.stop(fromController = false)
        finish()
    }

    private fun onBack() {
        if (sidePanel.isVisible) {
            hidePanel()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) {
            exitPlayer()
        } else {
            lastBack = now
            Toast.makeText(this, "再按一次退出播放", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsShown && isPlayingNow() && !sidePanel.isVisible) handler.postDelayed(hideControlsRunnable, HIDE_DELAY)
    }

    private fun showControls(show: Boolean) {
        controlsShown = show
        handler.removeCallbacks(hideControlsRunnable)
        for (bar in listOf(topBar, bottomBar)) {
            bar.animate().cancel()
            if (show) {
                bar.visibility = View.VISIBLE
                bar.animate().alpha(1f).setDuration(180).start()
            } else {
                bar.animate().alpha(0f).setDuration(220).withEndAction { bar.visibility = View.GONE }.start()
            }
        }
        if (show) scheduleHide()
    }

    // ================================================================= side panel

    private class Opt(val label: String, val sub: String?, val selected: Boolean, val action: () -> Unit)

    private fun showPanel(title: String, opts: List<Opt>, footer: String?) {
        panelTitle.text = title
        panelOptions.removeAllViews()
        val accent = ContextCompat.getColor(this, R.color.accent)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        for (o in opts) {
            val text = SpannableStringBuilder(o.label)
            if (!o.sub.isNullOrBlank()) {
                val start = text.length
                text.append("\n").append(o.sub)
                text.setSpan(RelativeSizeSpan(0.78f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(secondary), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val tv = TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(if (o.selected) accent else ContextCompat.getColor(context, android.R.color.white))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundResource(if (o.selected) R.drawable.bg_option_selected else R.drawable.bg_option)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    o.action()
                    hidePanel()
                }
            }
            panelOptions.addView(
                tv,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) },
            )
        }
        panelFooter.text = footer
        panelFooter.visibility = if (footer.isNullOrBlank()) View.GONE else View.VISIBLE
        showControls(false)
        sidePanel.animate().cancel()
        sidePanel.visibility = View.VISIBLE
        sidePanel.translationX = dp(340).toFloat()
        sidePanel.animate().translationX(0f).setDuration(200).start()
        panelOptions.getChildAt(opts.indexOfFirst { it.selected }.coerceAtLeast(0))?.requestFocus()
    }

    private fun hidePanel(animate: Boolean = true) {
        if (!sidePanel.isVisible) return
        sidePanel.animate().cancel()
        if (!animate) {
            sidePanel.visibility = View.GONE
            return
        }
        sidePanel.animate().translationX(dp(340).toFloat()).setDuration(180)
            .withEndAction { sidePanel.visibility = View.GONE }.start()
    }

    private fun showSpeedPanel() {
        val cur = cast.speed
        val opts = SPEEDS.map { s ->
            Opt(Prefs.speedLabel(s), if (s == 1f) "正常速度" else null, abs(s - cur) < 0.01f) {
                cast.setSpeed(s)
                updateSpeedButton()
                showHint("${Prefs.speedLabel(s)} 倍速播放")
            }
        }
        val footer = if (Prefs.rememberSpeed) "倍速会自动保持到后续投屏的视频（连续播放不用重复设置）" else "仅对当前视频生效"
        showPanel("播放倍速", opts, footer)
    }

    private fun showQualityPanel() {
        val opts = cast.videoQualities()
        val vs = cast.exo.videoSize
        val curInfo = if (vs.width > 0) "当前分辨率：${vs.width}×${vs.height}" else "正在获取视频信息…"
        if (opts.size <= 1) {
            val label = opts.firstOrNull()?.label ?: cast.currentQualityLabel()
            showPanel(
                "画质",
                listOf(Opt(label, curInfo, true) {}),
                "该视频源只提供这一种清晰度。\n如需更高画质，请在手机投屏控制页选择清晰度（如 1080P / 4K），NextScreen 会自动切换到新画质继续播放。",
            )
            return
        }
        val auto = cast.isAutoQuality()
        val list = ArrayList<Opt>()
        list += Opt("自动", "根据网速自动切换", auto) {
            cast.selectQuality(null)
            showHint("画质：自动")
        }
        opts.forEach { q ->
            list += Opt(q.label, q.detail, !auto && q.selected) {
                cast.selectQuality(q)
                showHint("已切换到 ${q.label}")
            }
        }
        showPanel("画质", list, "$curInfo\n选择的画质会作为默认画质，连续播放时自动沿用")
    }

    private fun showRatioPanel() {
        val cur = Prefs.resizeKey
        val items = listOf(
            Triple("fit", "适应屏幕", "保持原始比例，完整显示画面"),
            Triple("fill", "拉伸铺满", "拉伸画面填满屏幕"),
            Triple("zoom", "裁剪铺满", "保持比例放大，裁掉多余部分"),
        )
        showPanel("画面比例", items.map { (key, label, sub) ->
            Opt(label, sub, key == cur) {
                Prefs.resizeKey = key
                playerView.resizeMode = Prefs.resizeMode
                updateRatioButton()
            }
        }, null)
    }

    // ================================================================= gestures

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (sidePanel.isVisible) hidePanel() else showControls(!controlsShown)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                startLongPress()
            }
        })
        gestureLayer.setOnTouchListener { _, ev ->
            handleTouch(ev)
            true
        }
    }

    private fun handleTouch(ev: MotionEvent) {
        gestureDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragMode = DRAG_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressing || sidePanel.isVisible) return
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (dragMode == DRAG_NONE) {
                    if (abs(dx) < touchSlop * 2 && abs(dy) < touchSlop * 2) return
                    val m = cast.media
                    dragMode = if (abs(dx) > abs(dy)) {
                        if (m != null && m.kind != MediaKind.IMAGE && cast.exo.duration > 0) DRAG_SEEK else DRAG_IGNORED
                    } else if (downX < root.width / 2f) {
                        DRAG_BRIGHTNESS
                    } else {
                        DRAG_VOLUME
                    }
                    seekStart = cast.exo.currentPosition
                    seekTarget = seekStart
                    volStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    brightStart = currentBrightness()
                    handler.removeCallbacks(hideHintRunnable)
                }
                when (dragMode) {
                    DRAG_SEEK -> onSeekDrag(dx)
                    DRAG_VOLUME -> onVolumeDrag(dy)
                    DRAG_BRIGHTNESS -> onBrightnessDrag(dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (dragMode) {
                    DRAG_SEEK -> {
                        cast.seekTo(seekTarget)
                        handler.postDelayed(hideHintRunnable, 500)
                    }
                    DRAG_VOLUME, DRAG_BRIGHTNESS -> handler.postDelayed(hideHintRunnable, 600)
                }
                dragMode = DRAG_NONE
                if (longPressing) stopLongPress()
            }
        }
    }

    private fun onSeekDrag(dx: Float) {
        val dur = cast.exo.duration
        if (dur <= 0) return
        val range = max(90_000L, min(dur, 600_000L)).toFloat()
        seekTarget = (seekStart + dx / root.width.coerceAtLeast(1) * range).toLong().coerceIn(0, dur)
        val delta = (seekTarget - seekStart) / 1000
        val sign = if (delta >= 0) "快进" else "快退"
        showHint("$sign ${abs(delta)} 秒\n${fmt(seekTarget)} / ${fmt(dur)}", autoHide = false)
        seekBar.progress = (seekTarget * 1000 / dur).toInt()
        timePos.text = fmt(seekTarget)
    }

    private fun onVolumeDrag(dy: Float) {
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (volStart + (-dy / root.height.coerceAtLeast(1)) * maxVol * 1.5f).roundToInt().coerceIn(0, maxVol)
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (_: SecurityException) {}
        showHint("音量 ${if (maxVol > 0) v * 100 / maxVol else 0}%", autoHide = false)
    }

    private fun onBrightnessDrag(dy: Float) {
        val b = (brightStart + (-dy / root.height.coerceAtLeast(1)) * 1.2f).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = b }
        showHint("亮度 ${(b * 100).roundToInt()}%", autoHide = false)
    }

    private fun currentBrightness(): Float {
        val a = window.attributes.screenBrightness
        if (a >= 0) return a
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS).coerceIn(0, 255) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun startLongPress() {
        val m = cast.media ?: return
        if (m.kind == MediaKind.IMAGE || !cast.exo.isPlaying || dragMode != DRAG_NONE || sidePanel.isVisible) return
        longPressing = true
        speedBeforeLongPress = cast.speed
        val s = Prefs.longPressSpeed
        cast.setTemporarySpeed(s)
        speedBadge.text = "▶▶  ${Prefs.speedLabel(s)} 倍速播放中"
        speedBadge.visibility = View.VISIBLE
        gestureLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun stopLongPress() {
        longPressing = false
        cast.setTemporarySpeed(speedBeforeLongPress)
        speedBadge.visibility = View.GONE
        updateSpeedButton()
    }

    private fun showHint(text: String, autoHide: Boolean = true) {
        gestureHint.text = text
        gestureHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHintRunnable)
        if (autoHide) handler.postDelayed(hideHintRunnable, 1200)
    }

    // ================================================================= keys (remote / keyboard)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!sidePanel.isVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { cast.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { cast.pause(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controlsShown) { togglePlay(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (!controlsShown) { seekBy(-10_000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!controlsShown) { seekBy(10_000); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU ->
                    if (!controlsShown) { showControls(true); btnPlay.requestFocus(); return true }
            }
        }
        if (controlsShown) scheduleHide()
        return super.onKeyDown(keyCode, event)
    }

    private fun seekBy(delta: Long) {
        val dur = cast.exo.duration
        if (dur <= 0) return
        val target = (cast.exo.currentPosition + delta).coerceIn(0, dur)
        cast.seekTo(target)
        showHint("${if (delta > 0) "快进" else "快退"} ${abs(delta) / 1000} 秒\n${fmt(target)} / ${fmt(dur)}")
    }

    // ================================================================= CastListener

    override fun onMediaChanged(media: CastMedia?) = bindMedia(media)

    override fun onStateChanged() = refreshState()

    override fun onStopRequested() {
        endPanel.visibility = View.GONE
        loadingPanel.visibility = View.GONE
        showHint("手机已结束投屏")
        handler.removeCallbacks(stopCloseRunnable)
        handler.postDelayed(stopCloseRunnable, 2500)
    }

    override fun onError(message: String) = refreshState()

    override fun onTracksInfoChanged() = updateQualityButton()

    // ================================================================= utils

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = (t / 60) % 60
        val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
