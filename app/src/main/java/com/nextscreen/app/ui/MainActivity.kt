package com.nextscreen.app.ui

import android.Manifest
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nextscreen.app.App
import com.nextscreen.app.Prefs
import com.nextscreen.app.R
import com.nextscreen.app.player.TransportState
import com.nextscreen.app.service.RendererService
import com.nextscreen.app.service.RendererStatus

class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var panelStatus: View
    private lateinit var panelSide: View
    private lateinit var deviceName: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var ipText: TextView
    private lateinit var overlayBanner: View
    private lateinit var historyList: RecyclerView
    private lateinit var historyEmpty: TextView

    private val animators = ArrayList<Animator>()
    private val adapter = HistoryAdapter { item ->
        App.instance.castPlayer.setUri(item.uri, item.metadata, autoPlay = true)
    }
    private val statusObserver: (RendererStatus.Info) -> Unit = { render(it) }
    private val historyListener: () -> Unit = { runOnUiThread { reloadHistory() } }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        panelStatus = findViewById(R.id.panel_status)
        panelSide = findViewById(R.id.panel_side)
        deviceName = findViewById(R.id.device_name)
        statusText = findViewById(R.id.status_text)
        statusDot = findViewById(R.id.status_dot)
        ipText = findViewById(R.id.ip_text)
        overlayBanner = findViewById(R.id.overlay_banner)
        historyList = findViewById(R.id.history_list)
        historyEmpty = findViewById(R.id.history_empty)

        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = adapter

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_overlay).setOnClickListener { openOverlaySettings() }
        findViewById<View>(R.id.btn_clear).setOnClickListener {
            if (App.instance.history.list().isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage("清空所有投屏记录？")
                .setPositiveButton("清空") { _, _ -> App.instance.history.clear() }
                .setNegativeButton("取消", null)
                .show()
        }
        panelStatus.setOnClickListener {
            when {
                App.instance.airPlayServer.mirroring || App.instance.airPlayServer.currentPin != null ->
                    startActivity(Intent(this, MirrorActivity::class.java))
                App.instance.castPlayer.state == TransportState.PLAYING ||
                    App.instance.castPlayer.state == TransportState.PAUSED ->
                    startActivity(Intent(this, PlayerActivity::class.java))
            }
        }

        RendererService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        applyOrientation(resources.configuration)
        startPulse()
    }

    override fun onStart() {
        super.onStart()
        RendererStatus.observe(statusObserver)
        App.instance.history.addListener(historyListener)
        reloadHistory()
        if (!RendererService.running) RendererService.start(this)
    }

    override fun onResume() {
        super.onResume()
        val needOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)
        overlayBanner.visibility = if (needOverlay) View.VISIBLE else View.GONE
        deviceName.text = Prefs.deviceName
        render(RendererStatus.info)
    }

    override fun onStop() {
        RendererStatus.remove(statusObserver)
        App.instance.history.removeListener(historyListener)
        super.onStop()
    }

    override fun onDestroy() {
        animators.forEach { it.cancel() }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun applyOrientation(cfg: Configuration) {
        val land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        content.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val gap = (24 * resources.displayMetrics.density).toInt()
        if (land) {
            panelStatus.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            panelSide.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = gap
            }
        } else {
            panelStatus.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            panelSide.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
    }

    private fun render(info: RendererStatus.Info) {
        deviceName.text = info.name ?: Prefs.deviceName
        val (color, text) = when {
            !info.running -> R.color.warning to "正在启动投屏服务…"
            info.error != null -> R.color.danger to info.error
            info.airPlayError != null && Prefs.airPlayEnabled -> R.color.warning to "DLNA 已就绪 · ${info.airPlayError}"
            App.instance.airPlayServer.mirroring -> R.color.success to "AirPlay 屏幕镜像进行中（点击返回画面）"
            info.airPlayRunning -> R.color.success to "已就绪，等待视频投屏或 AirPlay 屏幕镜像"
            else -> R.color.success to "已就绪，等待手机投屏"
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        statusText.text = text
        ipText.text = if (info.ip != null) {
            buildString {
                append("IP ${info.ip}  ·  DLNA :${info.port}")
                if (info.airPlayRunning && info.airPlayPort > 0) {
                    append("  ·  AirPlay :${info.airPlayPort}")
                }
            }
        } else {
            "未连接局域网"
        }
    }

    private fun reloadHistory() {
        val list = App.instance.history.list()
        adapter.submit(list)
        historyEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startPulse() {
        listOf(R.id.ring1, R.id.ring2).forEachIndexed { i, id ->
            val ring = findViewById<View>(id)
            val anim = ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.75f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.75f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f),
            ).apply {
                duration = 2600
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                startDelay = i * 1300L
            }
            anim.start()
            animators += anim
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "请在系统设置中为 NextScreen 开启悬浮窗权限", Toast.LENGTH_LONG).show()
        }
    }
}
