package io.github.selfdestroyer.zapretify.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.selfdestroyer.zapretify.selection.SelectionController
import io.github.selfdestroyer.zapretify.ui.theme.ZapretifyColors

/** Разделы приложения — кнопки нижней панели. */
enum class Tab(val title: String, val icon: ImageVector) {
    Home("Главная", NavIcons.Home),
    Strategies("Стратегии", NavIcons.Strategies),
    Log("Журнал", NavIcons.Log),
    Settings("Настройки", NavIcons.Settings),
}

/**
 * Каркас приложения: раздел на весь экран, поверх него — парящая панель переключения разделов.
 * Главная — только включение и состояние; стратегии и подбор, журнал и настройки — в своих разделах.
 */
@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }

    // Пока идёт подбор, экран не гаснет (в каком бы разделе ни был пользователь): в фоне Android может приостановить проверку.
    val selecting = SelectionController.state.collectAsState().value.running
    val view = LocalView.current
    DisposableEffect(selecting) {
        view.keepScreenOn = selecting
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize().background(ZapretifyColors.Background)) {
        // Содержимое раздела прокручивается под панелью; место под неё разделы оставляют сами (BarSpace).
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (tab) {
                Tab.Home -> HomeScreen(onOpenStrategies = { tab = Tab.Strategies })
                Tab.Strategies -> StrategiesScreen()
                Tab.Log -> LogScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
        FloatingBar(
            selected = tab, onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

/** Парящая полупрозрачная панель разделов: не прижата к краям, содержимое видно сквозь неё. */
@Composable
private fun FloatingBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 18.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(ZapretifyColors.Raised.copy(alpha = 0.82f))
            .border(BorderStroke(1.dp, ZapretifyColors.Text.copy(alpha = 0.08f)), shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val active = t == selected
            val itemShape = RoundedCornerShape(20.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(itemShape)
                    .background(if (active) ZapretifyColors.Indigo.copy(alpha = 0.28f) else Color.Transparent)
                    .then(if (active) Modifier.border(BorderStroke(1.dp, ZapretifyColors.Indigo.copy(alpha = 0.55f)), itemShape) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(t) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val color = if (active) ZapretifyColors.Text else ZapretifyColors.Muted
                Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Text(t.title, color = color, fontSize = 10.5.sp, maxLines = 1, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
