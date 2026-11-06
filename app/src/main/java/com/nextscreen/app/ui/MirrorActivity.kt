package com.nextscreen.app.ui

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import com.nextscreen.app.App
import com.nextscreen.app.Prefs
import com.nextscreen.app.R
import com.nextscreen.app.airplay.AirPlayServer
import com.nextscreen.app.service.Notifications
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fullscreen low-latency AirPlay Screen Mirroring & Wireless Extended Display activity.
 */
class MirrorActivity : AppCompatActivity(), AirPlayServer.MirrorListener {

    companion object {
        @Volatile var isVisible = false
            private set

        private const val HIDE_DELAY = 3500L
        private const val DRAG_NONE = 0
        private const val DRAG_VOLUME = 1
        private const val DRAG_BRIGHTNESS = 2
        private const val DRAG_IGNORED = 3
    }

    private val airPlay get() = App.instance.airPlayServer
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var gestureLayer: View
    private lateinit var waitingPanel: View
    private lateinit var pinPanel: View
    private lateinit var pinCodeView: TextView
    private lateinit var gestureHint: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var statsPill: TextView
    private lateinit var liveDetail: TextView
    private lateinit var btnRotate: TextView
    private lateinit var btnRatio: TextView
    private lateinit var btnInfo: TextView
    private lateinit var sidePanel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelOptions: LinearLayout
    private lateinit var panelFooter: TextView

    private lateinit var audio: AudioManager
    private lateinit var gestureDetector: GestureDetector
    private var touchSlop = 24

    private var controlsShown = true
    private var infoPanelOpen = false
    private var rotationMode = "auto" // auto | landscape | portrait
    private var lastBackMs = 0L

    private var dragMode = DRAG_NONE
    private var downX = 0f
    private var downY = 0f
    private var volStart = 0
    private var brightStart = 0.5f

    private val hideControlsRunnable = Runnable { showControls(false) }
    private val hideHintRunnable = Runnable { gestureHint.visibility = View.GONE }
    private val statsTicker = object : Runnable {
        override fun run() {
            airPlay.videoRenderer.tickStats()
            refreshHud()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(R.layout.activity_mirror)

        bindViews()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        setupSurface()
        setupControls()
        setupGestures()
        applyInsets()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        airPlay.addListener(this)
        updatePinUi(airPlay.currentPin)
        refreshHud()
        showControls(true)
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        Notifications.cancelIncoming(this)
        handler.post(statsTicker)
        if (!airPlay.mirroring && airPlay.currentPin == null && airPlay.connectionCount == 0) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        isVisible = false
        handler.removeCallbacks(statsTicker)
        super.onStop()
    }

    override fun onDestroy() {
        airPlay.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ================================================================= MirrorListener

    override fun onMirrorStateChanged(active: Boolean) {
        if (!active && airPlay.currentPin == null) {
            Toast.makeText(this, "AirPlay 屏幕镜像已结束", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            refreshHud()
        }
    }

    override fun onMirrorVideoSize(srcW: Int, srcH: Int, width: Int, height: Int) {
        if (rotationMode == "auto") {
            applyRotationMode()
        }
        refreshHud()
    }

    override fun onPinChanged(pin: String?) {
        updatePinUi(pin)
        if (pin == null && !airPlay.mirroring && airPlay.connectionCount == 0) {
            finish()
        }
    }

    // ================================================================= setup & controls

    private fun bindViews() {
        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.mirror_surface)
        gestureLayer = findViewById(R.id.gesture_layer)
        waitingPanel = findViewById(R.id.waiting_panel)
        pinPanel = findViewById(R.id.pin_panel)
        pinCodeView = findViewById(R.id.pin_code)
        gestureHint = findViewById(R.id.gesture_hint)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        statsPill = findViewById(R.id.stats_pill)
        liveDetail = findViewById(R.id.live_detail)
        btnRotate = findViewById(R.id.btn_rotate)
        btnRatio = findViewById(R.id.btn_ratio)
        btnInfo = findViewById(R.id.btn_info)
        sidePanel = findViewById(R.id.side_panel)
        panelTitle = findViewById(R.id.panel_title)
        panelOptions = findViewById(R.id.panel_options)
        panelFooter = findViewById(R.id.panel_footer)
    }

    private fun setupSurface() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                airPlay.attachMirrorSurface(holder.surface, surfaceView.width, surfaceView.height)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                airPlay.updateMirrorSurfaceSize(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                airPlay.detachMirrorSurface(holder.surface)
            }
        })
    }

    private fun setupControls() {
        findViewById<View>(R.id.btn_back).setOnClickListener { handleBack() }
        findViewById<View>(R.id.btn_disconnect).setOnClickListener {
            airPlay.disconnectClients()
            Toast.makeText(this, "已断开屏幕镜像", Toast.LENGTH_SHORT).show()
            finish()
        }
        btnRotate.setOnClickListener { openRotationPanel() }
        btnRatio.setOnClickListener { openRatioPanel() }
        btnInfo.setOnClickListener { openInfoPanel() }
        updateChipLabels()
    }

    private fun updateChipLabels() {
        btnRotate.text = when (rotationMode) {
            "landscape" -> "横屏"
            "portrait" -> "竖屏"
            else -> "自动旋转"
        }
        btnRatio.text = when (Prefs.resizeKey) {
            "fill" -> "拉伸"
            "zoom" -> "裁剪"
            else -> "适应"
        }
    }

    private fun applyRotationMode() {
        requestedOrientation = when (rotationMode) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    private fun openRotationPanel() {
        infoPanelOpen = false
        panelTitle.text = "屏幕方向"
        panelOptions.removeAllViews()
        val items = listOf(
            "auto" to "跟随平板方向（自由旋转）",
            "landscape" to "固定横屏（适合 Mac 镜像/扩展屏）",
            "portrait" to "固定竖屏（适合手机竖屏镜像）",
        )
        for ((key, label) in items) {
            addOptionRow(label, key == rotationMode) {
                rotationMode = key
                applyRotationMode()
                updateChipLabels()
                closeSidePanel()
            }
        }
        panelFooter.visibility = View.GONE
        sidePanel.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun openRatioPanel() {
        infoPanelOpen = false
        panelTitle.text = "画面比例"
        panelOptions.removeAllViews()
        val items = listOf(
            "fit" to "适应屏幕（保持原比例）",
            "fill" to "拉伸铺满全屏",
            "zoom" to "裁剪铺满全屏",
        )
        for ((key, label) in items) {
            addOptionRow(label, key == Prefs.resizeKey) {
                Prefs.resizeKey = key
                airPlay.updateScaleMode(key)
                updateChipLabels()
                closeSidePanel()
            }
        }
        panelFooter.visibility = View.GONE
        sidePanel.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun openInfoPanel() {
        infoPanelOpen = true
        renderInfoPanel()
        sidePanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun renderInfoPanel() {
        if (!infoPanelOpen) return
        panelTitle.text = "实时镜像状态"
        panelOptions.removeAllViews()
        val vr = airPlay.videoRenderer
        val mbps = String.format(Locale.US, "%.2f Mbps", vr.bitrateBps / 1_000_000.0)
        val rows = listOf(
            "源设备画面" to if (airPlay.sourceWidth > 0) "${airPlay.sourceWidth} × ${airPlay.sourceHeight}" else "--",
            "传输分辨率" to if (airPlay.mirrorWidth > 0) "${airPlay.mirrorWidth} × ${airPlay.mirrorHeight}" else "--",
            "视频编码" to vr.codecName.ifEmpty { "等待关键帧" },
            "实时帧率" to "${vr.fps} fps（静止桌面按需降帧）",
            "实时码率" to mbps,
            "已解码帧 / 丢帧" to "${vr.frameCount} / ${vr.droppedFrames}",
            "音频流" to airPlay.audioCodecName.ifEmpty { "无" },
        )
        for ((k, v) in rows) {
            val tv = TextView(this).apply {
                text = "$k：$v"
                setTextColor(ContextCompat.getColor(this@MirrorActivity, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            panelOptions.addView(tv)
        }
        panelFooter.text = "提示：Mac 在「控制中心 → 屏幕镜像」中可选择「镜像内置显示器」或「用作单独的显示器（无线副屏）」。"
        panelFooter.visibility = View.VISIBLE
    }

    private fun addOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = if (selected) "✓  $label" else "    $label"
            setTextColor(
                ContextCompat.getColor(
                    this@MirrorActivity,
                    if (selected) R.color.accent else R.color.text_primary
                )
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundResource(R.drawable.bg_pill)
            background.alpha = if (selected) 255 else 0
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
        panelOptions.addView(tv, lp)
    }

    private fun closeSidePanel() {
        infoPanelOpen = false
        sidePanel.visibility = View.GONE
    }

    private fun updatePinUi(pin: String?) {
        if (pin != null) {
            pinCodeView.text = pin
            pinPanel.visibility = View.VISIBLE
            waitingPanel.visibility = View.GONE
        } else {
            pinPanel.visibility = View.GONE
        }
    }

    private fun refreshHud() {
        val vr = airPlay.videoRenderer
        val hasFrames = vr.frameCount > 0L
        waitingPanel.visibility = if (!hasFrames && airPlay.currentPin == null) View.VISIBLE else View.GONE

        val res = if (airPlay.mirrorWidth > 0) "${airPlay.mirrorWidth}×${airPlay.mirrorHeight}" else "连接中"
        val codec = vr.codecName.ifEmpty { "AirPlay" }
        statsPill.text = if (vr.fps > 0) "$res · ${vr.fps}fps · $codec" else "$res · $codec"

        val mbps = String.format(Locale.US, "%.1f Mbps", vr.bitrateBps / 1_000_000.0)
        val audioTag = airPlay.audioCodecName.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        liveDetail.text = if (hasFrames) "实时镜像中 · $mbps$audioTag" else "等待发送端视频帧…"

        if (infoPanelOpen && sidePanel.isVisible) {
            renderInfoPanel()
        }
    }

    // ================================================================= gestures & system bars

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (sidePanel.isVisible) {
                    closeSidePanel()
                } else {
                    showControls(!controlsShown)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val next = if (Prefs.resizeKey == "fit") "zoom" else "fit"
                Prefs.resizeKey = next
                airPlay.updateScaleMode(next)
                updateChipLabels()
                showHint(if (next == "zoom") "画面：裁剪铺满" else "画面：适应屏幕")
                return true
            }
        })

        gestureLayer.setOnTouchListener { v, ev ->
            gestureDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragMode = DRAG_NONE
                    downX = ev.x
                    downY = ev.y
                    val edge = dp(28)
                    if (ev.y < edge || ev.y > v.height - edge) {
                        dragMode = DRAG_IGNORED
                    }
                    volStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    brightStart = currentBrightness()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (dragMode == DRAG_NONE && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragMode = if (abs(dy) > abs(dx)) {
                            if (downX < v.width / 2f) DRAG_BRIGHTNESS else DRAG_VOLUME
                        } else {
                            DRAG_IGNORED
                        }
                    }
                    val h = v.height.coerceAtLeast(1)
                    when (dragMode) {
                        DRAG_BRIGHTNESS -> {
                            val next = (brightStart - dy / (h * 0.75f)).coerceIn(0.03f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = next }
                            showHint("亮度 ${(next * 100).roundToInt()}%")
                        }
                        DRAG_VOLUME -> {
                            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val target = (volStart - (dy / (h * 0.75f)) * maxVol).roundToInt().coerceIn(0, maxVol)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            val pct = (target * 100f / maxVol).roundToInt()
                            showHint(if (target == 0) "静音" else "音量 $pct%")
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragMode == DRAG_BRIGHTNESS || dragMode == DRAG_VOLUME) {
                        handler.removeCallbacks(hideHintRunnable)
                        handler.postDelayed(hideHintRunnable, 600L)
                    }
                    dragMode = DRAG_NONE
                }
            }
            true
        }
    }

    private fun currentBrightness(): Float {
        val attr = window.attributes.screenBrightness
        if (attr in 0.01f..1f) return attr
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.05f, 1f)
    }

    private fun showHint(text: String) {
        gestureHint.text = text
        gestureHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHintRunnable)
        handler.postDelayed(hideHintRunnable, 1100L)
    }

    private fun showControls(show: Boolean) {
        controlsShown = show
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) closeSidePanel()
        handler.removeCallbacks(hideControlsRunnable)
        if (show) scheduleHideControls()
        hideSystemBars()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, HIDE_DELAY)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(12) + cut.left, dp(12) + cut.top, dp(24) + cut.right, dp(36))
            bottomBar.setPadding(dp(20) + cut.left, dp(44), dp(20) + cut.right, dp(16) + cut.bottom)
            insets
        }
    }

    private fun handleBack() {
        if (sidePanel.isVisible) {
            closeSidePanel()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000L) {
            airPlay.disconnectClients()
            finish()
        } else {
            lastBackMs = now
            Toast.makeText(this, "再按一次返回退出并断开屏幕镜像", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
