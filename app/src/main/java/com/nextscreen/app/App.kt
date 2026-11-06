package com.nextscreen.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.nextscreen.app.airplay.AirPlayServer
import com.nextscreen.app.data.HistoryStore
import com.nextscreen.app.player.CastPlayer
import com.nextscreen.app.service.Notifications

class App : Application() {

    lateinit var castPlayer: CastPlayer
        private set
    lateinit var airPlayServer: AirPlayServer
        private set
    lateinit var history: HistoryStore
        private set

    private var startedActivities = 0
    private val liveActivities = LinkedHashSet<Activity>()

    /** True while any of our activities is visible (needed to decide whether we may start activities). */
    val isForeground: Boolean get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Notifications.createChannels(this)
        history = HistoryStore(this)
        castPlayer = CastPlayer(this)
        airPlayServer = AirPlayServer(this, castPlayer)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { liveActivities += a }
            override fun onActivityStarted(a: Activity) { startedActivities++ }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { liveActivities -= a }
        })
    }

    fun finishAllActivities() {
        liveActivities.toList().forEach { it.finish() }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
