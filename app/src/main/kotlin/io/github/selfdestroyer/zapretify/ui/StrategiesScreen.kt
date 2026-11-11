package io.github.selfdestroyer.zapretify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.engine.Strategy
import io.github.selfdestroyer.zapretify.selection.SelectionController
import io.github.selfdestroyer.zapretify.selection.SelectionRow
import io.github.selfdestroyer.zapretify.selection.SelectionState
import io.github.selfdestroyer.zapretify.tgproxy.ProxyPaths
import io.github.selfdestroyer.zapretify.ui.theme.ZapretifyColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Стратегии: текущая, подбор для этой сети, итоги подбора (или весь набор) и своя строка параметров. */
@Composable
fun StrategiesScreen() {
    val engine by EngineController.state.collectAsState()
    val selection by SelectionController.state.collectAsState()
    var customDialog by remember { mutableStateOf(false) }
    val selectedId = engine.strategy?.id

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("Стратегии", "Способ обхода блокировок. В разных сетях работают разные.") }
        item { CurrentCard(engine.strategy) }
        item { SelectionCard(selection, available = engine.nativeAvailable) }
        item {
            OutlinedButton(onClick = { customDialog = true }, enabled = !selection.running, modifier = Modifier.fillMaxWidth()) {
                Text("Своя стратегия…")
            }
        }
        if (selection.rows.isNotEmpty()) {
            item { Text("Итоги подбора", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            itemsIndexed(selection.rows, key = { _, r -> "r-" + r.strategyId }) { index, row ->
                ResultCard(index + 1, row, selected = row.strategyId == selectedId, enabled = !selection.running)
            }
        } else {
            item { Text("Все стратегии", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            items(engine.strategies, key = { "s-" + it.id }) { st ->
                PlainCard(st, selected = st.id == selectedId, enabled = !selection.running)
            }
        }
    }

    if (customDialog) CustomStrategyDialog(
        current = engine.strategies.firstOrNull { it.id == EngineController.CUSTOM_ID }?.args.orEmpty(),
        onDismiss = { customDialog = false },
    )
}

@Composable
private fun CurrentCard(strategy: Strategy?) {
    SectionCard {
        Text("Сейчас выбрана", color = ZapretifyColors.Muted, fontSize = 12.sp)
        Text(strategy?.name ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        strategy?.let { Args(it.args) }
    }
}

/** Подбор: запуск, ход проверки, пояснения к итогам. */
@Composable
private fun SelectionCard(state: SelectionState, available: Boolean) {
    SectionCard {
        Text("Подбор для этой сети", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        if (state.running) {
            Text("Проверено ${state.done} из ${state.total}. ${state.current}", color = ZapretifyColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("Обход на время подбора выключен. Не сворачивайте приложение.", color = ZapretifyColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { SelectionController.stop() }) { Text("Остановить") }
        } else {
            Text(
                "Zapretify по очереди проверит все стратегии: YouTube, Discord и Telegram. Это займёт несколько минут, " +
                    "обход на это время выключится.",
                color = ZapretifyColors.Muted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { SelectionController.start(ProxyPaths.cloudflareHost()) }, enabled = available) {
                    Text(if (state.finishedAt == 0L) "Начать подбор" else "Подобрать заново")
                }
                if (state.finishedAt != 0L) {
                    Spacer(Modifier.width(12.dp))
                    val time = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(state.finishedAt))
                    Text(if (state.cancelled) "Остановлен $time" else "Проверено $time", color = ZapretifyColors.Muted, fontSize = 12.sp)
                }
            }
            if (!available) Text("Движок обхода не входит в эту сборку", color = ZapretifyColors.Red, fontSize = 13.sp)
        }
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = ZapretifyColors.Red, fontSize = 13.sp)
        }
        state.notes.forEach {
            Spacer(Modifier.height(6.dp))
            Text(it, color = ZapretifyColors.Amber, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ResultCard(place: Int, row: SelectionRow, selected: Boolean, enabled: Boolean) {
    SectionCard {
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
        Args(row.args)
        if (row.engineFailed) {
            Text("Движок не запустился с этими параметрами", color = ZapretifyColors.Red, fontSize = 12.sp)
        } else {
            Marks("YouTube", listOf("сайт" to row.youtubeSite, "приложение" to row.youtubeApp, "видео" to row.video))
            Marks("Discord", listOf("чат" to row.discordChat, "файлы" to row.discordFiles))
            Marks("Telegram", listOf("прокси" to row.telegram))
            if (row.breaksSites) Text("Ломает обычные сайты", color = ZapretifyColors.Red, fontSize = 12.sp)
            ChooseRow(
                note = if (row.runs > 1) "Проверена ${row.runs} раза" else "Проверена 1 раз",
                selected = selected, enabled = enabled, strategyId = row.strategyId,
            )
        }
    }
}

/** Стратегия из набора, пока подбора не было. */
@Composable
private fun PlainCard(strategy: Strategy, selected: Boolean, enabled: Boolean) {
    SectionCard {
        Text(strategy.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Args(strategy.args)
        ChooseRow(note = "", selected = selected, enabled = enabled, strategyId = strategy.id)
    }
}

@Composable
private fun ChooseRow(note: String, selected: Boolean, enabled: Boolean, strategyId: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(note, color = ZapretifyColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        if (selected) Text("Выбрана", color = ZapretifyColors.Green, fontSize = 13.sp)
        else TextButton(enabled = enabled, onClick = {
            EngineController.state.value.strategies.firstOrNull { it.id == strategyId }?.let { EngineController.select(it) }
        }) { Text("Выбрать") }
    }
}

@Composable
private fun Args(args: String) {
    Text(args, color = ZapretifyColors.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
