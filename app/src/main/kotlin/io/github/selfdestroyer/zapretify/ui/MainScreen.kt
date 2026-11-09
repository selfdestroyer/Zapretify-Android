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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
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

    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

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
    val anyOn = state.phase != ProxyState.Phase.Off || engine.phase != EngineState.Phase.Off
    val starting = state.phase == ProxyState.Phase.Starting || engine.phase == EngineState.Phase.Starting

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Zapretify", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Android · v$version · предварительная версия", color = ZapretifyColors.Muted, fontSize = 13.sp)
        }

        MasterCard(on = anyOn, starting = starting, onToggle = { on -> if (on) turnOn() else turnOff() })

        EngineCard(engine)

        TelegramCard(state, context = context)

        LogCard(state.log)
    }
}

@Composable
private fun TelegramCard(state: ProxyState, context: Context) {
    val (status, dot) = when (state.phase) {
        ProxyState.Phase.On -> "Работает · 127.0.0.1:${ProxyController.settings.port}" to ZapretifyColors.Green
        ProxyState.Phase.Starting -> "Запуск…" to ZapretifyColors.Indigo
        ProxyState.Phase.Off -> "Выключен" to ZapretifyColors.Muted
    }
    ServiceCard(title = "Telegram", status = status, dot = dot)
    if (state.phase == ProxyState.Phase.On) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { openTelegram(context, state.link) }, modifier = Modifier.weight(1f)) { Text("Открыть в Telegram") }
            OutlinedButton(onClick = { copyLink(context, state.link) }) { Text("Ссылка") }
        }
        if (state.cfBlocked) {
            Text(
                "Ошибка: провайдер блокирует путь Telegram-прокси через Cloudflare. Попробуйте другую стратегию — " +
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

/** Карточка обхода YouTube и Discord: состояние, стратегия, приложения. Включается главным переключателем. */
@Composable
private fun EngineCard(engine: EngineState) {
    val (status, dot) = when (engine.phase) {
        EngineState.Phase.On -> "Работает" to ZapretifyColors.Green
        EngineState.Phase.Starting -> "Запуск…" to ZapretifyColors.Indigo
        EngineState.Phase.Off -> (if (engine.nativeAvailable) "Выключен" else "Движок не входит в эту сборку") to ZapretifyColors.Muted
    }
    ServiceCard(title = "YouTube и Discord", status = status, dot = dot)
    var menu by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }, enabled = engine.strategies.isNotEmpty()) {
            Text("Стратегия: ${engine.strategy?.name ?: "—"}", color = ZapretifyColors.Muted)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            engine.strategies.forEach { st ->
                DropdownMenuItem(
                    text = { Column { Text(st.name); Text(st.description, fontSize = 11.sp, color = ZapretifyColors.Muted, maxLines = 2) } },
                    onClick = { menu = false; EngineController.select(st) },
                )
            }
            DropdownMenuItem(
                text = { Text("Своя стратегия…", color = ZapretifyColors.Indigo) },
                onClick = { menu = false; customDialog = true },
            )
        }
    }
    if (customDialog) CustomStrategyDialog(
        current = engine.strategies.firstOrNull { it.id == EngineController.CUSTOM_ID }?.args.orEmpty(),
        onDismiss = { customDialog = false },
    )
    if (engine.phase == EngineState.Phase.On && engine.apps.isNotEmpty()) {
        Text("Через обход идут: ${engine.apps.joinToString(", ")}", color = ZapretifyColors.Muted, fontSize = 13.sp)
    }
    engine.error?.let { Text(it, color = ZapretifyColors.Red, fontSize = 13.sp) }
}

/** Главный переключатель: включает обход YouTube и Discord и Telegram-прокси вместе. */
@Composable
private fun MasterCard(on: Boolean, starting: Boolean, onToggle: (Boolean) -> Unit) {
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
                Switch(checked = on || starting, onCheckedChange = onToggle)
            }
            if (!on && !starting) {
                Text("При первом включении Android попросит разрешение на VPN. Другой VPN на телефоне при этом " +
                    "отключится — одновременно Android держит только один.", color = ZapretifyColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

/** Своя строка параметров byedpi — например, лучшая из подбора в другой программе для этой сети. */
@Composable
private fun CustomStrategyDialog(current: String, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Своя стратегия") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Строка параметров byedpi, например лучшая из подбора для вашей сети.", fontSize = 13.sp, color = ZapretifyColors.Muted)
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = ZapretifyColors.Red, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val err = EngineController.setCustom(text)
                if (err == null) onDismiss() else error = "Не подходит: $err"
            }) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ServiceCard(title: String, status: String, dot: Color, control: (@Composable () -> Unit)? = null) {
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
            control?.invoke()
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
