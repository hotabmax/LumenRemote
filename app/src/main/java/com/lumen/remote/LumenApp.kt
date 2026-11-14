package com.lumen.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.lumen.remote.core.Prefs

class LumenApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.get(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            getString(R.string.channel_alert),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_alert_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(alert)
    }

    companion object {
        const val CHANNEL_SERVICE = "lumen_session"

        /** Канал запросов на подключение (тревожные уведомления). */
        const val CHANNEL_ALERT = "lumen_approval"

        lateinit var instance: LumenApp
            private set
    }
}
