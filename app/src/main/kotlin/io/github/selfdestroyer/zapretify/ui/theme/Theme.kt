package io.github.selfdestroyer.zapretify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра — точно как в версии для Windows и macOS (переменные CSS в её интерфейсе): индиго — действия,
// зелёный — работает, янтарный — внимание, красный — ошибка. Тёмная тема, как на десктопе.
// Другие темы (например, Material You) — отдельным переключателем в настройках, позже.
object ZapretifyColors {
    val Background = Color(0xFF0B0D13) // --bg
    val Surface = Color(0xFF12141C) // --card
    val Raised = Color(0xFF1E212F) // --raised: всплывающие панели
    val Indigo = Color(0xFF625BF6) // --indigo
    val Green = Color(0xFF54B279) // --green
    val Amber = Color(0xFFE2A84F) // --amber
    val Red = Color(0xFFDE606E) // --red
    val Text = Color(0xFFEEF0F5) // --text
    val Muted = Color(0xFFA8ADBE) // --text-2: второстепенный текст
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
