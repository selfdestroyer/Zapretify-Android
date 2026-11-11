package io.github.selfdestroyer.zapretify.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.selfdestroyer.zapretify.proxy.ProxyController
import io.github.selfdestroyer.zapretify.ui.theme.ZapretifyColors
import io.github.selfdestroyer.zapretify.vpn.ZapretVpnService

/** Настройки: Telegram-прокси, приложения, идущие через обход, сведения о программе. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Настройки")

        SectionCard {
            Text("Telegram-прокси", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Работает на этом телефоне: 127.0.0.1:${ProxyController.settings.port}", color = ZapretifyColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("Ключ защищает прокси от чужих программ на телефоне. После смены ключа прокси нужно добавить в Telegram заново.",
                color = ZapretifyColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                ProxyController.regenerateSecret()
                Toast.makeText(context, "Новый ключ. Добавьте прокси в Telegram заново: «Открыть в Telegram» на главной", Toast.LENGTH_LONG).show()
            }) { Text("Сменить ключ прокси") }
        }

        SectionCard {
            Text("Приложения через обход", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(ZapretVpnService.DEFAULT_APPS.map { it.second }.distinct().joinToString(", "), color = ZapretifyColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("Остальные приложения, в том числе банковские, идут напрямую. Telegram работает через прокси. " +
                "Выбор приложений появится в следующих версиях.", color = ZapretifyColors.Muted, fontSize = 12.sp)
        }

        SectionCard {
            Text("О программе", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Zapretify для Android · v$version · предварительная версия", color = ZapretifyColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/selfdestroyer/Zapretify-Android".toUri())) }
            }) { Text("Страница проекта на GitHub") }
        }
        Spacer(Modifier.height(BarSpace))
    }
}
