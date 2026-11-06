package com.nextscreen.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nextscreen.app.R
import com.nextscreen.app.ui.MirrorActivity
import com.nextscreen.app.ui.PlayerActivity

object Notifications {
    const val CH_SERVICE = "renderer"
    const val CH_CAST = "incoming_cast"
    const val ID_SERVICE = 1
    const val ID_INCOMING = 2

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "投屏接收服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持 NextScreen 在后台可被手机和电脑发现"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CAST, "收到投屏提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "无法自动弹出播放或镜像界面时提醒你"
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun showIncomingCast(ctx: Context, title: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_CAST)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("收到投屏")
            .setContentText("$title · 点击开始观看")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        try {
            nm.notify(ID_INCOMING, n)
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    fun showIncomingMirror(ctx: Context, label: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MirrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_CAST)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("AirPlay 屏幕镜像")
            .setContentText("$label · 点击打开画面")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        try {
            nm.notify(ID_INCOMING, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelIncoming(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_INCOMING)
    }
}
