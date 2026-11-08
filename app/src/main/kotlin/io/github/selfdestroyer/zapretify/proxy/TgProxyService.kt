package io.github.selfdestroyer.zapretify.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import io.github.selfdestroyer.zapretify.MainActivity
import io.github.selfdestroyer.zapretify.R

/**
 * Фоновый сервис с уведомлением: держит Telegram-прокси, пока он включён (иначе Android остановит процесс).
 * В уведомлении — «Открыть в Telegram» и «Выключить».
 */
class TgProxyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ProxyController.stopServer()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                if (!ProxyController.isRunning) ProxyController.startServer()
            }
        }
        // Если система выгрузила сервис, она перезапустит его, и прокси поднимется снова.
        return START_STICKY
    }

    override fun onDestroy() {
        if (ProxyController.isRunning) ProxyController.stopServer()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel_proxy), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_proxy_desc); setShowBadge(false) })
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val openTelegram = PendingIntent.getActivity(this, 1,
            Intent(Intent.ACTION_VIEW, ProxyController.linkFor().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, TgProxyService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("Telegram-прокси работает")
            .setContentText("127.0.0.1:${ProxyController.settings.port}")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Открыть в Telegram", openTelegram)
            .addAction(0, "Выключить", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.selfdestroyer.zapretify.START_PROXY"
        const val ACTION_STOP = "io.github.selfdestroyer.zapretify.STOP_PROXY"
        private const val CHANNEL_ID = "tgproxy"
        private const val NOTIFICATION_ID = 1
    }
}
