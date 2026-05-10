package com.wifishare

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.wifishare.util.WifiMonitor

class WifiShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVER,
                    getString(R.string.channel_server),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TRANSFERS,
                    getString(R.string.channel_transfers),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        WifiMonitor.attach(this)
    }

    companion object {
        const val CHANNEL_SERVER = "server"
        const val CHANNEL_TRANSFERS = "transfers"
    }
}
