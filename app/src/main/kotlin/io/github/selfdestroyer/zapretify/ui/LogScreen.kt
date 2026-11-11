package io.github.selfdestroyer.zapretify.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.selfdestroyer.zapretify.proxy.ProxyController
import io.github.selfdestroyer.zapretify.ui.theme.ZapretifyColors

/** Журнал работы: последние события сверху. Кнопка копирует его целиком — для сообщения об ошибке. */
@Composable
fun LogScreen() {
    val lines = ProxyController.state.collectAsState().value.log
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ScreenTitle("Журнал", "Что делало приложение. Новые записи сверху.") }
        if (lines.isEmpty()) {
            item { Text("Пока пусто. Записи появятся, когда вы включите Zapretify.", color = ZapretifyColors.Muted, fontSize = 13.sp) }
        } else {
            item {
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Zapretify log", lines.joinToString("\n")))
                    Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
                }) { Text("Скопировать журнал") }
            }
            items(lines.asReversed()) {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = ZapretifyColors.Muted)
            }
        }
    }
}
