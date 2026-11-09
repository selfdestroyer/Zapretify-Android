package io.github.selfdestroyer.zapretify.engine

import android.content.Context
import org.json.JSONObject

/** Стратегия движка: набор параметров. Формат файла — strategies/byedpi.json (план, раздел 3.3). */
data class Strategy(val id: String, val name: String, val description: String, val args: String, val engine: String = "byedpi")

object Strategies {
    /** Встроенный набор из assets (собирается из папки strategies/ репозитория). Позже — обновление по сети. */
    fun load(context: Context): List<Strategy> {
        val json = JSONObject(context.assets.open("byedpi.json").bufferedReader().use { it.readText() })
        val engine = json.optString("engine", "byedpi")
        val arr = json.getJSONArray("strategies")
        // Стратегии с запрещёнными опциями в список не попадают.
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Strategy(o.getString("id"), o.getString("name"), o.optString("description"), o.getString("args"), engine)
        }.filter { StrategyValidator.check(splitArgs(it.args)) == null }
    }

    /**
     * Разбивает строку параметров на аргументы как оболочка: пробелы делят, кавычки "…" и '…' держат вместе
     * (например -n "google.com").
     */
    fun splitArgs(args: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var inToken = false
        for (c in args) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> cur.append(c)
                c == '"' || c == '\'' -> { quote = c; inToken = true }
                c.isWhitespace() -> { if (inToken) { out += cur.toString(); cur.clear(); inToken = false } }
                else -> { cur.append(c); inToken = true }
            }
        }
        if (inToken) out += cur.toString()
        return out
    }
}
