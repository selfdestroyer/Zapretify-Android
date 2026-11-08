# Авторы и сторонние компоненты

Zapretify для Android опирается на работу других людей. Ниже — чей код или идеи используются, где и под какой
лицензией. Лицензии проверены по репозиториям на GitHub 08.11.2026.

## Код, который входит или будет входить в приложение

| Компонент | Автор | Лицензия | Где используется |
|---|---|---|---|
| [byedpi](https://github.com/hufrea/byedpi) | hufrea | MIT | Движок версии 1 (без root): нативная библиотека, через JNI |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | heiher | MIT | Предполагается для передачи трафика из VpnService (TUN) в SOCKS5 `byedpi` (решается на этапе 3) |
| [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) | Flowseal | MIT | Telegram-прокси (`core/tgproxy`) переписывается на Kotlin по его Go-реализации в Zapretify |
| [zapret](https://github.com/bol-van/zapret) | bol-van | MIT | Движок `nfqws` в версии 2 (root) |
| [zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube) | Flowseal | MIT | Стратегии и списки доменов в версии 2 (root) |
| [Zapretify](https://github.com/selfdestroyer/Zapretify) | Zapretify Contributors | MIT | Десктопная версия: эталон поведения прокси и апдейтера, дизайн интерфейса |

## Приложения, на которые мы смотрим как на образец

Их код и наборы стратегий **не копируются** в Zapretify: они под GPL, и копирование потребовало бы выпускать
всё приложение под GPL. Используется только общее знание о том, как устроен обход на Android.

| Приложение | Автор | Лицензия | Что берём как ориентир |
|---|---|---|---|
| [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) | dovecoteescapee | GPL-3.0 | Как `byedpi` запускается через VpnService |
| [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) | romanvht | GPL-3.0 | Проверка этапа 0 на телефоне; работа с параметрами `byedpi` |
| [zaprett](https://github.com/egor-white/zaprett) | egor-white | GPL-2.0 | Root-режим с `nfqws` (ориентир для версии 2) |
| [zaprett-app](https://github.com/CherretGit/zaprett-app) | CherretGit | GPL-3.0 | Приложение к zaprett (ориентир для версии 2) |
| [ZDT-D](https://github.com/GAME-OVER-op/ZDT-D) | GAME-OVER-op | GPL-3.0 | Root-режим (ориентир для версии 2) |

При подключении каждого компонента его лицензия и копирайт добавляются в `LICENSE` или в папку компонента,
а сам компонент — в эту таблицу.
