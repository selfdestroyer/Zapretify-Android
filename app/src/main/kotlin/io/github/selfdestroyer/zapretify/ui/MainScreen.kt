package io.github.selfdestroyer.zapretify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
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
 * Каркас приложения: раздел сверху, панель переключения разделов снизу.
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

    Scaffold(
        containerColor = ZapretifyColors.Background,
        bottomBar = {
            NavigationBar(containerColor = ZapretifyColors.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ZapretifyColors.Text,
                            selectedTextColor = ZapretifyColors.Text,
                            indicatorColor = ZapretifyColors.Indigo,
                            unselectedIconColor = ZapretifyColors.Muted,
                            unselectedTextColor = ZapretifyColors.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Home -> HomeScreen(onOpenStrategies = { tab = Tab.Strategies })
                Tab.Strategies -> StrategiesScreen()
                Tab.Log -> LogScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}
