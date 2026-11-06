package com.nextscreen.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nextscreen.app.App
import com.nextscreen.app.Prefs
import com.nextscreen.app.R
import com.nextscreen.app.dlna.DlnaRenderer
import com.nextscreen.app.dlna.NetUtils
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Foreground service that keeps both DLNA/UPnP and AirPlay Screen Mirroring discoverable on the LAN. */
class RendererService : Service() {

    private val worker = ScheduledThreadPoolExecutor(1).apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private var renderer: DlnaRenderer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var restartFuture: ScheduledFuture<*>? = null
    private var checkFuture: ScheduledFuture<*>? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == Prefs.KEY_DEVICE_NAME -> scheduleRestart(both = true, delayMs = 300)
            Prefs.isAirPlayRestartKey(key) -> scheduleRestart(both = false, delayMs = 300)
            key == Prefs.KEY_RESIZE -> App.instance.airPlayServer.updateScaleMode(Prefs.resizeKey)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("NextScreen-multicast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock", e)
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NextScreen:Receiver").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock", e)
        }
        val r = DlnaRenderer(applicationContext, App.instance.castPlayer)
        renderer = r
        worker.execute {
            r.start()
            if (NetUtils.localIpv4() != null && Prefs.airPlayEnabled) {
                App.instance.airPlayServer.start()
            }
            updateNotification()
        }
        Prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
        registerNetworkCallback()
        worker.scheduleWithFixedDelay({ checkNetwork() }, 20, 20, TimeUnit.SECONDS)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            App.instance.castPlayer.stop(fromController = false)
            App.instance.airPlayServer.disconnectClients()
            App.instance.finishAllActivities()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Prefs.raw.unregisterOnSharedPreferenceChangeListener(prefListener)
        netCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        restartFuture?.cancel(false)
        checkFuture?.cancel(false)
        val r = renderer
        worker.execute {
            r?.stop()
            App.instance.airPlayServer.stop()
        }
        worker.shutdown()
        try { multicastLock?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun scheduleRestart(both: Boolean, delayMs: Long) {
        restartFuture?.cancel(false)
        try {
            restartFuture = worker.schedule({
                if (both) renderer?.restart()
                if (NetUtils.localIpv4() != null && Prefs.airPlayEnabled) {
                    App.instance.airPlayServer.restart()
                } else {
                    App.instance.airPlayServer.stop()
                }
                updateNotification()
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    private fun checkNetwork() {
        val r = renderer ?: return
        val current = NetUtils.localIpv4()?.hostAddress
        val airPlay = App.instance.airPlayServer
        if (current != r.ipString) {
            Log.i(TAG, "network changed ${r.ipString} -> $current, restarting services")
            r.restart()
            if (current != null && Prefs.airPlayEnabled) {
                airPlay.restart()
            } else {
                airPlay.stop()
            }
            updateNotification()
        } else if (current != null && Prefs.airPlayEnabled && !airPlay.running) {
            airPlay.start()
            updateNotification()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleCheck()
            override fun onLost(network: Network) = scheduleCheck()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = scheduleCheck()
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback", e)
        }
    }

    private fun scheduleCheck() {
        checkFuture?.cancel(false)
        try {
            checkFuture = worker.schedule({ checkNetwork() }, 1500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, buildNotification(), type)
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        try {
            val nm = NotificationManagerCompat.from(this)
            if (nm.areNotificationsEnabled()) nm.notify(Notifications.ID_SERVICE, buildNotification())
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val info = RendererStatus.info
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, com.nextscreen.app.ui.MainActivity::class.java)
        val open = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, RendererService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val proto = if (info.airPlayRunning) "DLNA + AirPlay" else "DLNA"
        val text = if (info.ip != null) {
            "${Prefs.deviceName} · ${info.ip} · $proto"
        } else {
            "${Prefs.deviceName} · 等待网络连接"
        }
        return NotificationCompat.Builder(this, Notifications.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("NextScreen 正在等待投屏 / 屏幕镜像")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "退出", exit)
            .build()
    }

    companion object {
        private const val TAG = "RendererService"
        const val ACTION_EXIT = "com.nextscreen.app.action.EXIT"

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RendererService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start service failed", e)
            }
        }
    }
}
