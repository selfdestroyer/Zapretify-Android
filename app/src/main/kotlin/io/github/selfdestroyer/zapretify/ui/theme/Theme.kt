package io.github.selfdestroyer.zapretify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Цвета из прототипа и десктопной версии: индиго — действия, зелёный — работает,
// янтарный — внимание, красный — ошибка. Тёмная тема по умолчанию, как на десктопе.
object ZapretifyColors {
    val Background = Color(0xFF0B0C12)
    val Surface = Color(0xFF12131A)
    val Indigo = Color(0xFF6C5CF0)
    val Green = Color(0xFF4FD08F)
    val Amber = Color(0xFFE0A84A)
    val Red = Color(0xFFDE606E)
    val Text = Color(0xFFEEF0F6)
    val Muted = Color(0xFF9095A3)
}

private val scheme = darkColorScheme(
    primary = ZapretifyColors.Indigo,
    background = ZapretifyColors.Background,
    surface = ZapretifyColors.Surface,
    error = ZapretifyColors.Red,
    onBackground = ZapretifyColors.Text,
    onSurface = ZapretifyColors.Text,
    onSurfaceVariant = ZapretifyColors.Muted,
)

@Composable
fun ZapretifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
