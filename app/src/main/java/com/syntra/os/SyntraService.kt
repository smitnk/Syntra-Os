package com.syntra.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps Syntra's process (models, local API, Ubuntu shells, Telegram) alive while you use other apps. */
class SyntraService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("core", "Syntra core", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 1, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "core").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Syntra is running").setContentText("AI core, local API and Linux are active").setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        return START_STICKY
    }
}
