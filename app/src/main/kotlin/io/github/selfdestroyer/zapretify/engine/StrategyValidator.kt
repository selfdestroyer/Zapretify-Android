package io.github.selfdestroyer.zapretify.engine

/**
 * Проверка параметров стратегии byedpi перед запуском (план, раздел 3.1): стратегии будут приходить из сети,
 * поэтому пропускаются только опции обхода. Запрещено всё, что меняет адрес и порт SOCKS5 (их задаёт
 * приложение), запускает фон, пишет или читает файлы, выводит справку. Файловые аргументы (-H, -j, -l)
 * разрешены только во встроенной форме «:строка».
 * Список опций — по README и main.c byedpi v0.17.3 (hufrea/byedpi, MIT).
 */
object StrategyValidator {
    /** Опции обхода: короткая → длинная, и нужен ли аргумент. */
    private data class Opt(val long: String, val hasArg: Boolean, val inlineOnly: Boolean = false)

    private val allowed: Map<String, Opt> = listOf(
        "N" to Opt("no-domain", false), "X" to Opt("no-ipv6", false), "U" to Opt("no-udp", false),
        "c" to Opt("max-conn", true), "b" to Opt("buf-size", true), "x" to Opt("debug", true), "g" to Opt("def-ttl", true),
        "F" to Opt("tfo", false), "A" to Opt("auto", true), "L" to Opt("auto-mode", true), "u" to Opt("cache-ttl", true),
        "T" to Opt("timeout", true), "B" to Opt("copy", true), "K" to Opt("proto", true),
        "H" to Opt("hosts", true, inlineOnly = true), "j" to Opt("ipset", true, inlineOnly = true),
        "V" to Opt("pf", true), "R" to Opt("round", true), "s" to Opt("split", true), "d" to Opt("disorder", true),
        "o" to Opt("oob", true), "q" to Opt("disoob", true), "f" to Opt("fake", true), "S" to Opt("md5sig", false),
        "n" to Opt("fake-sni", true), "t" to Opt("ttl", true), "l" to Opt("fake-data", true, inlineOnly = true),
        "O" to Opt("fake-offset", true), "Q" to Opt("fake-tls-mod", true), "e" to Opt("oob-data", true),
        "M" to Opt("mod-http", true), "r" to Opt("tlsrec", true), "m" to Opt("tlsminor", true), "a" to Opt("udp-fake", true),
        "Z" to Opt("wait-send", false), "W" to Opt("await-int", true), "Y" to Opt("drop-sack", false),
    ).toMap()
    private val byLong = allowed.entries.associate { it.value.long to (it.key to it.value) }

    /** null — всё в порядке, иначе текст ошибки. */
    fun check(args: List<String>): String? {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val (opt, inlineValue) = when {
                a.startsWith("--") -> {
                    val name = a.removePrefix("--").substringBefore('=')
                    val o = byLong[name]?.second ?: return "опция $a не разрешена"
                    o to (if ('=' in a) a.substringAfter('=') else null)
                }
                a.startsWith("-") && a.length >= 2 -> {
                    val o = allowed[a.substring(1, 2)] ?: return "опция ${a.take(2)} не разрешена"
                    o to (if (a.length > 2) a.substring(2) else null)
                }
                else -> return "лишний аргумент «$a»"
            }
            var value = inlineValue
            if (opt.hasArg && value == null) {
                if (i + 1 >= args.size) return "у опции $a нет значения"
                value = args[++i]
            }
            if (!opt.hasArg && inlineValue != null && a.startsWith("--")) return "у опции $a не бывает значения"
            if (opt.inlineOnly && value != null && !value.startsWith(":")) return "у опции $a разрешена только строка «:…», не файл"
            i++
        }
        return null
    }
}
