package io.github.selfdestroyer.zapretify

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что включает кнопка на главной: обход YouTube и Discord (VPN) и/или Telegram-прокси. */
data class Launch(val bypass: Boolean = true, val telegram: Boolean = true)

// Хранится контекст приложения (не экрана): утечки нет.
@SuppressLint("StaticFieldLeak")
object LaunchOptions {
    private lateinit var app: Context
    private val _state = MutableStateFlow(Launch())
    val state: StateFlow<Launch> = _state.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        val p = app.getSharedPreferences("launch", Context.MODE_PRIVATE)
        _state.value = Launch(p.getBoolean("bypass", true), p.getBoolean("telegram", true))
    }

    /** Хотя бы один пункт должен остаться включённым — иначе кнопке нечего включать. */
    fun set(value: Launch) {
        if (!value.bypass && !value.telegram) return
        _state.value = value
        app.getSharedPreferences("launch", Context.MODE_PRIVATE).edit { putBoolean("bypass", value.bypass); putBoolean("telegram", value.telegram) }
    }
}
