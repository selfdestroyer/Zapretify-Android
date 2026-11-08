package io.github.selfdestroyer.zapretify.proxy

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom

/** Настройки прокси: порт и секрет. Секрет создаётся один раз и хранится в приложении. */
class ProxySettings(context: Context) {
    private val prefs = context.getSharedPreferences("tgproxy", Context.MODE_PRIVATE)

    val port: Int get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    val secret: String
        get() = prefs.getString(KEY_SECRET, null) ?: newSecret().also { prefs.edit { putString(KEY_SECRET, it) } }

    /** Новый секрет: после смены ссылку в Telegram нужно добавить заново. */
    fun regenerateSecret(): String = newSecret().also { prefs.edit { putString(KEY_SECRET, it) } }

    /** Включён ли прокси по желанию пользователя (чтобы вернуть его после перезапуска приложения). */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit { putBoolean(KEY_ENABLED, v) } }

    private fun newSecret() = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_PORT = 1443
        private const val KEY_PORT = "port"
        private const val KEY_SECRET = "secret"
        private const val KEY_ENABLED = "enabled"
    }
}
