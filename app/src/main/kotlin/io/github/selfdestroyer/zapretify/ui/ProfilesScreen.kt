package io.github.selfdestroyer.zapretify.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.engine.Strategy
import io.github.selfdestroyer.zapretify.profiles.NetworkId
import io.github.selfdestroyer.zapretify.profiles.NetworkWatcher
import io.github.selfdestroyer.zapretify.profiles.Profile
import io.github.selfdestroyer.zapretify.profiles.ProfileController
import io.github.selfdestroyer.zapretify.ui.theme.ZapretifyColors

/** Профили: «+» создаёт профиль (название, стратегия, сеть); плитка — включить или изменить профиль. */
@Composable
fun ProfilesScreen() {
    val state by ProfileController.state.collectAsState()
    // null — окно закрыто; Editing(null) — новый профиль.
    var editing by remember { mutableStateOf<Editing?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = BarSpace),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenTitle("Профили", "Сейчас: ${state.network.title}") }
                FilledIconButton(
                    onClick = { editing = Editing(null) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ZapretifyColors.Indigo, contentColor = ZapretifyColors.Text),
                ) { androidx.compose.material3.Icon(NavIcons.Add, contentDescription = "Новый профиль") }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Переключать при смене сети", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    Switch(checked = state.auto, onCheckedChange = { ProfileController.setAuto(it) })
                }
            }
        }
        if (state.profiles.isEmpty()) {
            item {
                Text("Нажмите «+», чтобы создать профиль.",
                    color = ZapretifyColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        items(state.profiles, key = { it.id }) { p ->
            ProfileTile(p, active = p.id == state.activeId, here = p.networkKey != null && p.networkKey == state.network.key,
                onOpen = { editing = Editing(p) })
        }
    }

    editing?.let { e -> ProfileSheet(e.profile, onClose = { editing = null }) }
}

private class Editing(val profile: Profile?)

@Composable
private fun ProfileTile(p: Profile, active: Boolean, here: Boolean, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    SectionCard(Modifier.clip(shape).clickable(onClick = onOpen).then(
        if (active) Modifier.border(1.dp, ZapretifyColors.Indigo.copy(alpha = 0.6f), shape) else Modifier,
    )) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.networkTitle ?: "Без сети — включается вручную", color = ZapretifyColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(p.strategyName, color = ZapretifyColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            if (active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).then(Modifier.border(4.dp, ZapretifyColors.Green, CircleShape)))
                    Spacer(Modifier.width(6.dp))
                    Text("Включён", color = ZapretifyColors.Green, fontSize = 13.sp)
                }
            } else {
                Button(onClick = { ProfileController.activate(p) }) { Text("Включить") }
            }
        }
        if (here && !active) Text("Вы сейчас в этой сети", color = ZapretifyColors.Amber, fontSize = 12.sp)
    }
}

/** Окно профиля снизу экрана: название, стратегия, сеть с кнопкой «Определить текущую сеть». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(profile: Profile?, onClose: () -> Unit) {
    val engine by EngineController.state.collectAsState()
    val current by NetworkWatcher.current.collectAsState()
    val strategies = engine.strategies
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var strategy by remember {
        mutableStateOf(strategies.firstOrNull { it.args == profile?.strategyArgs }
            ?: profile?.let { Strategy("profile", it.strategyName, it.strategyArgs, it.strategyArgs) }
            ?: engine.strategy ?: strategies.firstOrNull())
    }
    var network by remember { mutableStateOf(profile?.networkKey?.let { NetworkId.fromKey(it, profile.networkTitle) }) }
    var menu by remember { mutableStateOf(false) }
    var askedWifi by remember { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        NetworkWatcher.refresh(); askedWifi = true
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZapretifyColors.Surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (profile == null) "Новый профиль" else "Профиль", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)

            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                label = { Text("Название") }, placeholder = { Text("Например, Дом") }, modifier = Modifier.fillMaxWidth(),
            )

            Column {
                Text("Стратегия", color = ZapretifyColors.Muted, fontSize = 12.sp)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(strategy?.name ?: "Выберите", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        strategies.forEach { st ->
                            DropdownMenuItem(text = { Text(st.name, maxLines = 2) }, onClick = { strategy = st; menu = false })
                        }
                    }
                }
                strategy?.let { Text(it.args, color = ZapretifyColors.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }

            Column {
                OutlinedTextField(
                    value = network?.let { "${it.title}  ·  ${it.key}" } ?: "",
                    onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("Сеть") },
                    placeholder = { Text("Не задана — включается вручную") },
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (current.kind == NetworkId.Kind.WIFI && current.name == null && !NetworkWatcher.canNameWifi() && !askedWifi) {
                            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else if (current.kind != NetworkId.Kind.NONE) {
                            network = current
                        }
                    }, contentPadding = PaddingValues(0.dp)) { Text("Определить текущую сеть", color = ZapretifyColors.Indigo) }
                    Spacer(Modifier.weight(1f))
                    if (network != null) TextButton(onClick = { network = null }) { Text("Убрать", color = ZapretifyColors.Muted) }
                }
                if (current.kind == NetworkId.Kind.WIFI && current.name == null) {
                    Text(
                        if (askedWifi && !NetworkWatcher.canNameWifi())
                            "Без доступа к геолокации профиль будет для любого Wi-Fi. Нажмите «Определить» ещё раз, чтобы сохранить так."
                        else "Чтобы различать сети Wi-Fi, Android попросит доступ к геолокации.",
                        color = ZapretifyColors.Muted, fontSize = 12.sp,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (profile != null) {
                    TextButton(onClick = { ProfileController.delete(profile); onClose() }) { Text("Удалить", color = ZapretifyColors.Red) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Отмена") }
                Button(enabled = name.isNotBlank() && strategy != null, onClick = {
                    ProfileController.save(profile?.id, name, strategy!!, network)
                    onClose()
                }) { Text("Сохранить") }
            }
        }
    }
}
