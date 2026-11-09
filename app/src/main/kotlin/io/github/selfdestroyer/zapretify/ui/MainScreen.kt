package io.github.selfdestroyer.zapretify.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.app.Activity
import android.net.VpnService
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.engine.EngineState
import io.github.selfdestroyer.zapretify.proxy.ProxyController
import io.github.selfdestroyer.zapretify.proxy.ProxyState
import io.github.selfdestroyer.zapretify.ui.theme.ZapretifyColors

/**
 * Главный экран (этап 2): Telegram-прокси работает по-настоящему, обход блокировок — заглушка до этапа 3.
 * Дизайн — по прототипу десктопной версии: карточки служб, цвет состояния, тёмная тема.
 */
@Composable
fun MainScreen() {
    val state by ProxyController.state.collectAsState()
    val engine by EngineController.state.collectAsState()
    val context = LocalContext.current

    // Android 13+: уведомление сервиса показывается только с разрешения. Прокси работает и без него.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ProxyController.enable()
    }
    fun turnOn() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else ProxyController.enable()
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Zapretify", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Android · предварительная версия", color = ZapretifyColors.Muted, fontSize = 13.sp)
        }

        TelegramCard(state, onToggle = { on -> if (on) turnOn() else ProxyController.disable() }, context = context)

        EngineCard(engine, context)

        LogCard(state.log)
    }
}

@Composable
private fun TelegramCard(state: ProxyState, onToggle: (Boolean) -> Unit, context: Context) {
    val (status, dot) = when (state.phase) {
        ProxyState.Phase.On -> "Работает · 127.0.0.1:${ProxyController.settings.port}" to ZapretifyColors.Green
        ProxyState.Phase.Starting -> "Запуск…" to ZapretifyColors.Indigo
        ProxyState.Phase.Off -> "Выключен" to ZapretifyColors.Muted
    }
    ServiceCard(title = "Telegram", status = status, dot = dot) {
        Switch(checked = state.phase != ProxyState.Phase.Off, onCheckedChange = onToggle)
    }
    if (state.phase == ProxyState.Phase.On) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { openTelegram(context, state.link) }, modifier = Modifier.weight(1f)) { Text("Открыть в Telegram") }
            OutlinedButton(onClick = { copyLink(context, state.link) }) { Text("Ссылка") }
        }
        if (state.cfBlocked) {
            Text(
                "Ошибка: провайдер блокирует путь Telegram-прокси через Cloudflare. Включите «YouTube и Discord» — " +
                    "пока обход работает, прокси ходит через него.",
                color = ZapretifyColors.Red, fontSize = 13.sp,
            )
        }
        TextButton(onClick = {
            ProxyController.regenerateSecret()
            Toast.makeText(context, "Новый ключ. Добавьте прокси в Telegram заново: «Открыть в Telegram»", Toast.LENGTH_LONG).show()
        }) { Text("Сменить ключ прокси", color = ZapretifyColors.Muted) }
    }
    state.error?.let { Text(it, color = ZapretifyColors.Red, fontSize = 13.sp) }
}

/** Карточка обхода YouTube и Discord: включение (с разрешением на VPN), стратегия, приложения. */
@Composable
private fun EngineCard(engine: EngineState, context: Context) {
    // Разрешение на VPN Android спрашивает один раз своим окном.
    val askVpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) EngineController.start()
    }
    fun turnOn() {
        val consent = VpnService.prepare(context)
        if (consent != null) askVpn.launch(consent) else EngineController.start()
    }
    val (status, dot) = when (engine.phase) {
        EngineState.Phase.On -> "Работает · ${engine.strategy?.name ?: ""}" to ZapretifyColors.Green
        EngineState.Phase.Starting -> "Запуск…" to ZapretifyColors.Indigo
        EngineState.Phase.Off -> (if (engine.nativeAvailable) "Выключен" else "Движок не входит в эту сборку") to ZapretifyColors.Muted
    }
    ServiceCard(title = "YouTube и Discord", status = status, dot = dot) {
        Switch(
            checked = engine.phase != EngineState.Phase.Off,
            onCheckedChange = { on -> if (on) turnOn() else EngineController.stop() },
            enabled = engine.nativeAvailable,
        )
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }, enabled = engine.strategies.isNotEmpty()) {
            Text("Стратегия: ${engine.strategy?.name ?: "—"}", color = ZapretifyColors.Muted)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            engine.strategies.forEach { st ->
                DropdownMenuItem(
                    text = { Column { Text(st.name); Text(st.description, fontSize = 11.sp, color = ZapretifyColors.Muted) } },
                    onClick = { menu = false; EngineController.select(st) },
                )
            }
        }
    }
    if (engine.phase == EngineState.Phase.On && engine.apps.isNotEmpty()) {
        Text("Через обход идут: ${engine.apps.joinToString(", ")}", color = ZapretifyColors.Muted, fontSize = 13.sp)
    }
    if (engine.phase == EngineState.Phase.Off && engine.nativeAvailable) {
        Text("При включении Android попросит разрешение на VPN. Если на телефоне работает другой VPN, он отключится — " +
            "одновременно Android держит только один.", color = ZapretifyColors.Muted, fontSize = 12.sp)
    }
    engine.error?.let { Text(it, color = ZapretifyColors.Red, fontSize = 13.sp) }
}

@Composable
private fun ServiceCard(title: String, status: String, dot: Color, control: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ZapretifyColors.Surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(status, color = ZapretifyColors.Muted, fontSize = 13.sp)
                }
            }
            control()
        }
    }
}

@Composable
private fun LogCard(lines: List<String>) {
    if (lines.isEmpty()) return
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ZapretifyColors.Surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Журнал", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            lines.takeLast(40).asReversed().forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = ZapretifyColors.Muted)
            }
        }
    }
}

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
