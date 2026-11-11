package io.github.selfdestroyer.zapretify.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.engine.EngineState
import io.github.selfdestroyer.zapretify.profiles.ProfileController
import io.github.selfdestroyer.zapretify.proxy.ProxyController
import io.github.selfdestroyer.zapretify.proxy.ProxyState
import io.github.selfdestroyer.zapretify.selection.SelectionController
import io.github.selfdestroyer.zapretify.ui.theme.ZapretifyColors

/** Главная: один переключатель и состояние. Всё остальное — в других разделах. */
@Composable
fun HomeScreen(onOpenStrategies: () -> Unit) {
    val proxy by ProxyController.state.collectAsState()
    val engine by EngineController.state.collectAsState()
    val selection by SelectionController.state.collectAsState()
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    // Один переключатель включает всё: обход YouTube и Discord (VPN) и Telegram-прокси.
    // Порядок разрешений: уведомления (Android 13+) → VPN. Прокси запускается в любом случае.
    fun startEngine() = if (engine.nativeAvailable) EngineController.start() else Unit
    val askVpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startEngine()
        else EngineController.reportError("Без разрешения на VPN обход YouTube и Discord не включится")
    }
    fun startAll() {
        ProxyController.enable()
        val consent = if (engine.nativeAvailable) VpnService.prepare(context) else null
        if (consent != null) askVpn.launch(consent) else startEngine()
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startAll() }
    fun turnOn() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startAll()
    }
    fun turnOff() {
        EngineController.stop()
        ProxyController.disable()
    }
    val anyOn = proxy.phase != ProxyState.Phase.Off || engine.phase != EngineState.Phase.Off
    // Обход включали, а он остановился сам (сбой движка или Android выгрузил приложение и поднял только прокси):
    // Telegram работает, YouTube и Discord — нет.
    val engineDropped = proxy.phase == ProxyState.Phase.On && engine.phase == EngineState.Phase.Off &&
        engine.nativeAvailable && engine.wanted
    val starting = proxy.phase == ProxyState.Phase.Starting || engine.phase == EngineState.Phase.Starting

    val profiles by ProfileController.state.collectAsState()
    val profile = profiles.profiles.firstOrNull { it.id == profiles.activeId }

    Column(
        // Кнопка — посередине свободного места, плитки сервисов — внизу, над панелью разделов.
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth()) { ScreenTitle("Zapretify", "v$version") }

        Spacer(Modifier.weight(1f))
        PowerButton(
            state = when {
                starting -> Power.STARTING
                engineDropped -> Power.PARTIAL
                anyOn -> Power.ON
                else -> Power.OFF
            },
            enabled = !selection.running,
            onClick = { if (anyOn || starting) turnOff() else turnOn() },
        )
        Text(
            when {
                selection.running -> "Идёт подбор стратегии…"
                starting -> "Запуск…"
                engineDropped -> "Работает частично"
                anyOn -> "Включено"
                else -> "Выключено"
            },
            fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
        )
        // Профиль и стратегия — в плашке; нажатие открывает раздел «Стратегии».
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(ZapretifyColors.Surface)
                .clickable(enabled = engine.nativeAvailable, onClick = onOpenStrategies)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                listOfNotNull(profile?.name, engine.strategy?.name).joinToString(" · ").ifEmpty { "Выбрать стратегию" },
                color = ZapretifyColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val error = engine.error ?: proxy.error
        if (error != null) Text(error, color = ZapretifyColors.Red, fontSize = 13.sp)
        if (engineDropped && !selection.running) Button(onClick = { startAll() }) { Text("Включить обход снова") }

        Spacer(Modifier.weight(1f))
        val engineStatus = when {
            engine.phase == EngineState.Phase.On -> Status.WORKS
            engine.phase == EngineState.Phase.Starting -> Status.STARTING
            engineDropped -> Status.FAILED
            else -> Status.OFF
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ServiceTile("YouTube", engineStatus, Modifier.weight(1f))
            ServiceTile("Discord", engineStatus, Modifier.weight(1f))
        }
        val tgStatus = when (proxy.phase) {
            ProxyState.Phase.On -> if (proxy.cfBlocked) Status.PARTIAL else Status.WORKS
            ProxyState.Phase.Starting -> Status.STARTING
            ProxyState.Phase.Off -> Status.OFF
        }
        ServiceTile("Telegram", tgStatus, Modifier.fillMaxWidth()) {
            if (proxy.phase == ProxyState.Phase.On) {
                Button(onClick = { openTelegram(context, proxy.link) }) { Text("Перейти в Telegram") }
            }
        }
        if (proxy.cfBlocked && proxy.phase == ProxyState.Phase.On) {
            Text("Провайдер режет путь прокси через Cloudflare — попробуйте другую стратегию.", color = ZapretifyColors.Amber, fontSize = 12.sp)
        }
        Spacer(Modifier.height(BarSpace - 8.dp))
    }
}

private enum class Power { OFF, STARTING, ON, PARTIAL }

/** Большая круглая кнопка включения, как в приложениях-аналогах: цвет показывает состояние. */
@Composable
private fun PowerButton(state: Power, enabled: Boolean, onClick: () -> Unit) {
    val color = when (state) {
        Power.ON -> ZapretifyColors.Indigo
        Power.PARTIAL -> ZapretifyColors.Amber
        Power.STARTING -> ZapretifyColors.Indigo.copy(alpha = 0.55f)
        Power.OFF -> ZapretifyColors.Raised
    }
    val iconColor = if (state == Power.OFF) ZapretifyColors.Muted else ZapretifyColors.Text
    Box(
        modifier = Modifier
            .size(168.dp)
            .shadow(if (state == Power.OFF) 0.dp else 28.dp, CircleShape, ambientColor = color, spotColor = color)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, ZapretifyColors.Text.copy(alpha = if (state == Power.OFF) 0.08f else 0.18f), CircleShape)
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(NavIcons.Power, contentDescription = if (state == Power.OFF) "Включить" else "Выключить", tint = iconColor, modifier = Modifier.size(64.dp))
    }
}

private enum class Status(val text: String) {
    WORKS("Работает"), PARTIAL("Работает частично"), FAILED("Не работает"), STARTING("Запуск…"), OFF("Выключен");
    val color: Color get() = when (this) {
        WORKS -> ZapretifyColors.Green
        PARTIAL -> ZapretifyColors.Amber
        FAILED -> ZapretifyColors.Red
        STARTING -> ZapretifyColors.Indigo
        OFF -> ZapretifyColors.Muted
    }
}

/** Плитка сервиса: название и состояние; [action] — кнопка справа (у Telegram). */
@Composable
private fun ServiceTile(name: String, status: Status, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                StatusLine(status.text, status.color)
            }
            action?.invoke()
        }
    }
}

internal fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

private fun openTelegram(context: Context, link: String?) {
    if (link == null) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Telegram не установлен", Toast.LENGTH_LONG).show()
    }
}
