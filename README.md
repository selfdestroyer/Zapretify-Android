# Zapretify для Android

> В разработке. Это **Android-версия** Zapretify. Версия для **Windows и macOS** — в репозитории
> [selfdestroyer/Zapretify](https://github.com/selfdestroyer/Zapretify).

Zapretify объединяет обход блокировок для YouTube и Discord и Telegram-прокси в одном приложении.

- **Версия 1 (без root):** движок `byedpi` через VpnService, Telegram-прокси, подбор стратегий.
- **Версия 2 (с root):** `nfqws` и стратегии Flowseal, как на десктопе.

## Сборка

Нужна Android Studio (в ней есть JDK и Android SDK). Открыть папку проекта → дождаться синхронизации Gradle →
Run. Тесты Telegram-прокси идут без телефона: `./gradlew :core:tgproxy:test`.

## Структура

```
app/            Android-приложение (Kotlin, Jetpack Compose)
core/tgproxy/   Telegram-прокси — обычная Kotlin-библиотека, тестируется на компьютере
strategies/     набор стратегий byedpi (будет обновляться отсюда)
```

## Авторы и благодарности

Zapretify для Android построен на работе этих проектов:

- **[byedpi](https://github.com/hufrea/byedpi)** — hufrea, движок обхода без root;
- **[tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)** и **[zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube)** — Flowseal, Telegram-прокси, стратегии и списки;
- **[zapret](https://github.com/bol-van/zapret)** — bol-van, движок `nfqws`;
- **[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)** — heiher, передача трафика из VPN в SOCKS5.

Подробно, с лицензиями: [THIRD_PARTY.md](THIRD_PARTY.md).

## Лицензия

MIT — см. [LICENSE](LICENSE).
