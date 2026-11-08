# Zapretify для Android

> В разработке. Это **Android-версия** Zapretify. Версия для **Windows и macOS** — в репозитории
> [selfdestroyer/Zapretify](https://github.com/selfdestroyer/Zapretify).

Zapretify объединяет обход блокировок для YouTube и Discord и Telegram-прокси в одном приложении.

- **Версия 1 (без root):** движок `byedpi` через VpnService, Telegram-прокси, подбор стратегий.
- **Версия 2 (с root):** `nfqws` и стратегии Flowseal, как на десктопе.

План работ: [`docs/android-port-plan.md`](docs/android-port-plan.md). Ручная проверка перед разработкой:
[`docs/stage0-checklist.md`](docs/stage0-checklist.md).

## Сборка

Нужна Android Studio (в ней есть JDK и Android SDK). Открыть папку проекта → дождаться синхронизации Gradle →
Run. Тесты Telegram-прокси идут без телефона: `./gradlew :core:tgproxy:test`.

## Структура

```
app/            Android-приложение (Kotlin, Jetpack Compose)
core/tgproxy/   Telegram-прокси — обычная Kotlin-библиотека, тестируется на компьютере
strategies/     набор стратегий byedpi (будет обновляться отсюда)
docs/           план и чек-листы
```

## Лицензия

MIT — см. [LICENSE](LICENSE). Используются наработки zapret (bol-van), Flowseal (zapret-discord-youtube,
tg-ws-proxy) и byedpi (будет добавлен с лицензией при подключении).
