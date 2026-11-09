package io.github.selfdestroyer.zapretify.engine

/**
 * Мост к нативным движкам (app/src/main/jni): byedpi — локальный SOCKS5 с обходом DPI, hev-socks5-tunnel —
 * передаёт трафик из VPN (TUN) в этот SOCKS5. Обе функции run* блокируют поток до остановки.
 * Если библиотеки нет в сборке (исходники движков не подключены), [available] = false и обход недоступен.
 */
object NativeBridge {
    val available: Boolean = try {
        System.loadLibrary("zapretify_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Запускает byedpi с аргументами командной строки (первый — имя программы). Возвращает код выхода. */
    external fun runByedpi(args: Array<String>): Int
    external fun stopByedpi()

    /** Запускает туннель с конфигурацией YAML и дескриптором TUN из VpnService. Возвращает код выхода. */
    external fun runTunnel(config: String, tunFd: Int): Int
    external fun stopTunnel()
}
