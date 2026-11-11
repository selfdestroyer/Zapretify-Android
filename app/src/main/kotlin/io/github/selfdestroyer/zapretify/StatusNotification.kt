package io.github.selfdestroyer.zapretify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.engine.EngineState
import io.github.selfdestroyer.zapretify.proxy.ProxyController
import io.github.selfdestroyer.zapretify.proxy.TgProxyService

/**
 * Одно уведомление на всё приложение. Обход (VPN) и Telegram-прокси — два фоновых сервиса, и каждому Android
 * требует уведомление; оба показывают одно и то же, с одним номером, поэтому пользователь видит одно.
 * Оно остаётся, пока работает хотя бы один сервис, и обновляется, когда меняется состояние.
 */
object StatusNotification {
    const val ID = 1
    private const val CHANNEL_ID = "status"

    fun build(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Состояние Zapretify", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Уведомление, пока работает обход или Telegram-прокси"; setShowBadge(false) })
            // Каналы прежних версий (отдельные уведомления прокси и обхода) больше не нужны.
            listOf("tgproxy", "engine").forEach { runCatching { nm.deleteNotificationChannel(it) } }
        }
        val engineOn = EngineController.state.value.phase != EngineState.Phase.Off
        val proxyOn = ProxyController.settings.enabled
        val text = when {
            engineOn && proxyOn -> "YouTube, Discord и Telegram"
            engineOn -> "YouTube и Discord"
            else -> "Telegram-прокси"
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
        val stopAll = PendingIntent.getService(context, 2,
            Intent(context, TgProxyService::class.java).setAction(TgProxyService.ACTION_STOP_ALL), flags)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("Zapretify работает")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (proxyOn) {
            val openTelegram = PendingIntent.getActivity(context, 1,
                Intent(Intent.ACTION_VIEW, ProxyController.linkFor().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            builder.addAction(0, "Открыть в Telegram", openTelegram)
        }
        return builder.addAction(0, "Выключить", stopAll).build()
    }

    /**
     * Обновляет текст и кнопки или убирает уведомление. Смотрим на то, что пользователь хочет (переключатель),
     * а не на то, что ещё не успело остановиться: прокси останавливается в отдельном потоке, и проверка «ещё
     * работает» снова показывала уведомление уже после выключения — его можно было только смахнуть.
     */
    fun refresh(context: Context) {
        val engineOn = EngineController.state.value.phase != EngineState.Phase.Off
        val proxyWanted = ProxyController.settings.enabled
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching { if (engineOn || proxyWanted) nm.notify(ID, build(context)) else nm.cancel(ID) }
    }
}
