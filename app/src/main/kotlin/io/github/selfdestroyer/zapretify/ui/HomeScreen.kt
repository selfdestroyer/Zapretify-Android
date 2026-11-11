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
    val starting = proxy.phase == ProxyState.Phase.Starting || engine.phase == EngineState.Phase.Starting

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Zapretify", "Android · v$version · предварительная версия")

        MasterCard(on = anyOn, starting = starting, enabled = !selection.running, onToggle = { on -> if (on) turnOn() else turnOff() })
        if (selection.running) {
            Text("Идёт подбор стратегии — обход включится сам, когда он закончится.", color = ZapretifyColors.Amber, fontSize = 13.sp)
        }

        EngineCard(engine, neverSelected = selection.finishedAt == 0L && !selection.running, onOpenStrategies = onOpenStrategies)
        TelegramCard(proxy, context)
        Spacer(Modifier.height(BarSpace))
    }
}

@Composable
private fun MasterCard(on: Boolean, starting: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val (title, sub, dot) = when {
        starting -> Triple("Запуск…", "Включаем обход и Telegram-прокси", ZapretifyColors.Indigo)
        on -> Triple("Защита включена", "YouTube, Discord и Telegram работают через Zapretify", ZapretifyColors.Green)
        else -> Triple("Всё выключено", "Один переключатель — обход YouTube и Discord и Telegram-прокси", ZapretifyColors.Muted)
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ZapretifyColors.Surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    }
                    Text(sub, color = ZapretifyColors.Muted, fontSize = 13.sp)
                }
                Switch(checked = on || starting, onCheckedChange = onToggle, enabled = enabled)
            }
            if (!on && !starting) {
                Text("При первом включении Android попросит разрешение на VPN. Другой VPN на телефоне при этом " +
                    "отключится — одновременно Android держит только один.", color = ZapretifyColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

/** Обход YouTube и Discord: состояние и текущая стратегия. Выбор и подбор — в разделе «Стратегии». */
@Composable
private fun EngineCard(engine: EngineState, neverSelected: Boolean, onOpenStrategies: () -> Unit) {
    val (status, dot) = when (engine.phase) {
        EngineState.Phase.On -> "Работает" to ZapretifyColors.Green
        EngineState.Phase.Starting -> "Запуск…" to ZapretifyColors.Indigo
        EngineState.Phase.Off -> (if (engine.nativeAvailable) "Выключен" else "Движок не входит в эту сборку") to ZapretifyColors.Muted
    }
    SectionCard {
        Text("YouTube и Discord", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        StatusLine(status, dot)
        Spacer(Modifier.height(8.dp))
        Text("Стратегия", color = ZapretifyColors.Muted, fontSize = 12.sp)
        Text(engine.strategy?.name ?: "—", fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (engine.phase == EngineState.Phase.On && engine.apps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Через обход идут: ${engine.apps.joinToString(", ")}", color = ZapretifyColors.Muted, fontSize = 12.sp)
        }
        engine.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = ZapretifyColors.Red, fontSize = 13.sp)
        }
        if (engine.nativeAvailable) {
            TextButton(onClick = onOpenStrategies, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (neverSelected) "Подобрать стратегию для этой сети" else "Сменить стратегию", color = ZapretifyColors.Indigo)
            }
        }
    }
}

@Composable
private fun TelegramCard(state: ProxyState, context: Context) {
    val (status, dot) = when (state.phase) {
        ProxyState.Phase.On -> "Работает" to ZapretifyColors.Green
        ProxyState.Phase.Starting -> "Запуск…" to ZapretifyColors.Indigo
        ProxyState.Phase.Off -> "Выключен" to ZapretifyColors.Muted
    }
    SectionCard {
        Text("Telegram", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        StatusLine(status, dot)
        if (state.phase == ProxyState.Phase.On) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { openTelegram(context, state.link) }, modifier = Modifier.weight(1f)) { Text("Открыть в Telegram") }
                OutlinedButton(onClick = { copyLink(context, state.link) }) { Text("Ссылка") }
            }
            if (state.cfBlocked) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ошибка: провайдер блокирует путь Telegram-прокси через Cloudflare. Попробуйте другую стратегию — " +
                        "пока обход работает, прокси ходит через него.",
                    color = ZapretifyColors.Red, fontSize = 13.sp,
                )
            }
        }
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = ZapretifyColors.Red, fontSize = 13.sp)
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

private fun copyLink(context: Context, link: String?) {
    if (link == null) return
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Telegram proxy", link))
    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
}
