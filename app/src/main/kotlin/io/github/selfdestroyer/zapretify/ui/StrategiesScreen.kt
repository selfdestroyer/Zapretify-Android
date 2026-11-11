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
import io.github.selfdestroyer.zapretify.profiles.ProfileController
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
    // Стратегия (название и параметры), которую привязывают к профилю; null — окно закрыто.
    var toProfile by remember { mutableStateOf<Pair<String, String>?>(null) }
    // В итогах — 10 лучших; остальные по кнопке «Показать все».
    var showAll by remember(selection.finishedAt) { mutableStateOf(false) }
    val selectedId = engine.strategy?.id
    val selectedArgs = engine.strategy?.args

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = BarSpace),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("Стратегии") }
        item { CurrentCard(engine.strategy) }
        item { SelectionCard(selection, available = engine.nativeAvailable) }
        item {
            OutlinedButton(onClick = { customDialog = true }, enabled = !selection.running, modifier = Modifier.fillMaxWidth()) {
                Text("Своя стратегия…")
            }
        }
        if (selection.rows.isNotEmpty()) {
            item { Text("Итоги подбора", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            val shown = if (showAll || selection.running) selection.rows else selection.rows.take(TOP_RESULTS)
            itemsIndexed(shown, key = { _, r -> "r-" + r.strategyId }) { index, row ->
                ResultCard(index + 1, row, selected = row.strategyId == selectedId || row.args == selectedArgs, enabled = !selection.running,
                    fixArgs = videoFix(row, selection.rows), onToProfile = { toProfile = it })
            }
            if (!showAll && !selection.running && selection.rows.size > TOP_RESULTS) {
                item {
                    TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Показать все (${selection.rows.size})", color = ZapretifyColors.Indigo)
                    }
                }
            }
        } else {
            item { Text("Все стратегии", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            items(engine.strategies, key = { "s-" + it.id }) { st ->
                PlainCard(st, selected = st.id == selectedId, enabled = !selection.running)
            }
        }
    }

    toProfile?.let { (name, args) -> ProfilePickDialog(name, args, onDismiss = { toProfile = null }) }
    if (customDialog) CustomStrategyDialog(
        current = engine.strategies.firstOrNull { it.id == EngineController.CUSTOM_ID }?.args.orEmpty(),
        onDismiss = { customDialog = false },
    )
}

private const val TOP_RESULTS = 10

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
            Text("Не сворачивайте приложение.", color = ZapretifyColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { SelectionController.stop() }) { Text("Остановить") }
        } else {
            Text("Проверит все стратегии в этой сети. Около 5 минут.", color = ZapretifyColors.Muted, fontSize = 13.sp)
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

/**
 * Исправление для видео YouTube: стратегия открывает YouTube, но не сервер видео, а другая стратегия из того же
 * подбора сервер видео открывает. Тогда для серверов видео (googlevideo.com) берётся приём второй, для всего
 * остального — эта (группы byedpi с фильтром по домену). Так видео заработало в мобильной сети 11.11.2026.
 * null — исправление не нужно или не из чего его собрать.
 */
private fun videoFix(row: SelectionRow, rows: List<SelectionRow>): String? {
    if (row.engineFailed || row.video != false || !(row.youtubeSite || row.youtubeApp)) return null
    if (hasGroups(row.args)) return null
    val donor = rows.firstOrNull { it.video == true && !it.engineFailed && !hasGroups(it.args) } ?: return null
    return "-H:$VIDEO_HOSTS ${donor.args} -An ${row.args}"
}

private const val VIDEO_HOSTS = "googlevideo.com"

private fun hasGroups(args: String) =
    args.split(' ').any { it.startsWith("-A") || it.startsWith("--auto") || it.startsWith("-H") || it.startsWith("--hosts") }

@Composable
private fun ResultCard(place: Int, row: SelectionRow, selected: Boolean, enabled: Boolean, fixArgs: String?, onToProfile: (Pair<String, String>) -> Unit) {
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
            if (fixArgs != null) {
                Text("Видео не идёт — есть исправление для сервера видео ($VIDEO_HOSTS).",
                    color = ZapretifyColors.Amber, fontSize = 12.sp)
            }
            Text(if (row.runs > 1) "Проверена ${row.runs} раза" else "Проверена 1 раз", color = ZapretifyColors.Muted, fontSize = 11.sp)
            // Применяется исправленная стратегия, если исправление есть.
            val name = if (fixArgs != null) "${row.name} + видео" else row.name
            val args = fixArgs ?: row.args
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = enabled, onClick = { onToProfile(name to args) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.weight(1f),
                ) { Text("В профиль…", fontSize = 13.sp, maxLines = 1) }
                if (selected && fixArgs == null) {
                    OutlinedButton(onClick = {}, enabled = false, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.weight(1f)) {
                        Text("Выбрана", color = ZapretifyColors.Green, fontSize = 13.sp, maxLines = 1)
                    }
                } else {
                    Button(
                        enabled = enabled, onClick = { apply(row.strategyId, args) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.weight(1f),
                    ) { Text(if (fixArgs != null) "Применить с исправлением" else "Применить", fontSize = 13.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
        }
    }
}

/** Стратегии из набора выбираются по имени; исправленная или собранная подбором сохраняется как «Своя». */
private fun apply(strategyId: String, args: String) {
    val known = EngineController.state.value.strategies.firstOrNull { it.id == strategyId && it.args == args }
    if (known != null) EngineController.select(known) else EngineController.setCustom(args)
}

/** Окно выбора профиля, к которому привязать стратегию. */
@Composable
private fun ProfilePickDialog(name: String, args: String, onDismiss: () -> Unit) {
    val profiles = ProfileController.state.collectAsState().value.profiles
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("В какой профиль?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (name.endsWith(" + видео")) Text("С исправлением для видео", color = ZapretifyColors.Amber, fontSize = 12.sp)
                StrategyArgs(args)
                Spacer(Modifier.height(4.dp))
                if (profiles.isEmpty()) Text("Профилей пока нет — создайте его в разделе «Профили» кнопкой «+».", fontSize = 13.sp, color = ZapretifyColors.Muted)
                profiles.forEach { p ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = {
                        val same = p.strategyArgs == args
                        ProfileController.setStrategy(p, name, args)
                        android.widget.Toast.makeText(context,
                            when {
                                same -> "В профиле «${p.name}» уже эта стратегия"
                                name.endsWith(" + видео") -> "Записано в профиль «${p.name}» с исправлением для видео"
                                else -> "Записано в профиль «${p.name}»"
                            },
                            android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(p.name, color = ZapretifyColors.Text, fontWeight = FontWeight.SemiBold)
                            Text(p.networkTitle ?: "Без сети", color = ZapretifyColors.Muted, fontSize = 12.sp)
                            Text(p.strategyName, color = ZapretifyColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Стратегия из набора, пока подбора не было. */
@Composable
private fun PlainCard(strategy: Strategy, selected: Boolean, enabled: Boolean) {
    SectionCard {
        Text(strategy.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Args(strategy.args)
        ChooseRow(note = "", selected = selected, enabled = enabled, strategyId = strategy.id, args = strategy.args)
    }
}

@Composable
private fun ChooseRow(note: String, selected: Boolean, enabled: Boolean, strategyId: String, args: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(note, color = ZapretifyColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        if (selected) Text("Выбрана", color = ZapretifyColors.Green, fontSize = 13.sp)
        else Button(enabled = enabled, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), onClick = {
            // Стратегии из набора выбираются по имени; собранная подбором (объединённая) сохраняется как «Своя».
            val known = EngineController.state.value.strategies.firstOrNull { it.id == strategyId }
            if (known != null) EngineController.select(known) else EngineController.setCustom(args)
        }) { Text("Выбрать", fontSize = 13.sp) }
    }
}

@Composable
private fun Args(args: String) = StrategyArgs(args)

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
                Text("Параметры byedpi.", fontSize = 13.sp, color = ZapretifyColors.Muted)
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
