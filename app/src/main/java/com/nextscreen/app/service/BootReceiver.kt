package com.nextscreen.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nextscreen.app.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if ((a == Intent.ACTION_BOOT_COMPLETED || a == "android.intent.action.QUICKBOOT_POWERON") && Prefs.bootStart) {
            RendererService.start(context)
        }
    }
}
