package io.github.selfdestroyer.zapretify.proxy

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.selfdestroyer.zapretify.StatusNotification
import io.github.selfdestroyer.zapretify.engine.EngineController

/**
 * Фоновый сервис: держит Telegram-прокси, пока он включён (иначе Android остановит процесс).
 * Уведомление — общее с обходом ([StatusNotification]).
 */
class TgProxyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                // «Выключить» в уведомлении: и обход, и прокси.
                EngineController.stop()
                ProxyController.disable()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                ProxyController.stopServer()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ServiceCompat.startForeground(this, StatusNotification.ID, StatusNotification.build(this),
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

    companion object {
        const val ACTION_START = "io.github.selfdestroyer.zapretify.START_PROXY"
        const val ACTION_STOP = "io.github.selfdestroyer.zapretify.STOP_PROXY"
        const val ACTION_STOP_ALL = "io.github.selfdestroyer.zapretify.STOP_ALL"
    }
}
