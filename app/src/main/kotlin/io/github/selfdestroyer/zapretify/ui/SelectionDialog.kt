package io.github.selfdestroyer.zapretify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.selection.SelectionController
import io.github.selfdestroyer.zapretify.selection.SelectionRow
import io.github.selfdestroyer.zapretify.selection.SelectionState
import io.github.selfdestroyer.zapretify.tgproxy.ProxyPaths
import io.github.selfdestroyer.zapretify.ui.theme.ZapretifyColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Подбор стратегии: запуск, ход проверки и итоги с кнопкой «Выбрать» у каждой стратегии. */
@Composable
fun SelectionDialog(onDismiss: () -> Unit) {
    val state by SelectionController.state.collectAsState()
    val selectedId = EngineController.state.collectAsState().value.strategy?.id

    // Пока идёт подбор, экран не гаснет: в фоне Android может приостановить проверку.
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = ZapretifyColors.Background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Подбор стратегии", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                Header(state)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.rows, key = { _, r -> r.strategyId }) { index, row ->
                        ResultCard(index + 1, row, selected = row.strategyId == selectedId, enabled = !state.running)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: SelectionState) {
    if (state.running) {
        Text("Проверено ${state.done} из ${state.total}. ${state.current}", color = ZapretifyColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Обход на время подбора выключен. Не сворачивайте приложение.", color = ZapretifyColors.Muted, fontSize = 12.sp)
        OutlinedButton(onClick = { SelectionController.stop() }) { Text("Остановить") }
    } else {
        Text(
            "Zapretify по очереди проверит все стратегии в этой сети: YouTube, Discord и Telegram. Это займёт несколько минут. " +
                "Обход на это время выключится.",
            color = ZapretifyColors.Muted, fontSize = 13.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { SelectionController.start(ProxyPaths.cloudflareHost()) }) {
                Text(if (state.finishedAt == 0L) "Начать подбор" else "Подобрать заново")
            }
            if (state.finishedAt != 0L) {
                Spacer(Modifier.width(12.dp))
                val time = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(state.finishedAt))
                Text(if (state.cancelled) "Остановлен $time" else "Проверено $time", color = ZapretifyColors.Muted, fontSize = 12.sp)
            }
        }
    }
    state.error?.let { Text(it, color = ZapretifyColors.Red, fontSize = 13.sp) }
    state.notes.forEach { Text(it, color = ZapretifyColors.Amber, fontSize = 12.sp) }
}

@Composable
private fun ResultCard(place: Int, row: SelectionRow, selected: Boolean, enabled: Boolean) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ZapretifyColors.Surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$place. ${row.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    if (row.engineFailed) "ошибка" else "${row.score}%",
                    color = when {
                        row.engineFailed || row.score < 40 -> ZapretifyColors.Red
                        row.score < 85 -> ZapretifyColors.Amber
                        else -> ZapretifyColors.Green
                    },
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                )
            }
            Text(row.args, color = ZapretifyColors.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.engineFailed) {
                Text("Движок не запустился с этими параметрами", color = ZapretifyColors.Red, fontSize = 12.sp)
            } else {
                Marks("YouTube", listOf("сайт" to row.youtubeSite, "приложение" to row.youtubeApp, "видео" to row.video))
                Marks("Discord", listOf("чат" to row.discordChat, "файлы" to row.discordFiles))
                Marks("Telegram", listOf("прокси" to row.telegram))
                if (row.breaksSites) Text("Ломает обычные сайты", color = ZapretifyColors.Red, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (row.runs > 1) "Проверена ${row.runs} раза" else "Проверена 1 раз", color = ZapretifyColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    if (selected) Text("Выбрана", color = ZapretifyColors.Green, fontSize = 13.sp)
                    else TextButton(enabled = enabled, onClick = {
                        EngineController.state.value.strategies.firstOrNull { it.id == row.strategyId }?.let { EngineController.select(it) }
                    }) { Text("Выбрать") }
                }
            }
        }
    }
}

/** Строка вида «YouTube: сайт ✓ · приложение ✓ · видео ✗»; null — не проверялось. */
@Composable
private fun Marks(title: String, items: List<Pair<String, Boolean?>>) {
    Row {
        Text("$title: ", color = ZapretifyColors.Muted, fontSize = 12.sp)
        items.forEachIndexed { i, (label, ok) ->
            if (i > 0) Text(" · ", color = ZapretifyColors.Muted, fontSize = 12.sp)
            Text(
                "$label ${when (ok) { true -> "✓"; false -> "✗"; null -> "—" }}",
                color = when (ok) { true -> ZapretifyColors.Green; false -> ZapretifyColors.Red; null -> ZapretifyColors.Muted },
                fontSize = 12.sp,
            )
        }
    }
}
