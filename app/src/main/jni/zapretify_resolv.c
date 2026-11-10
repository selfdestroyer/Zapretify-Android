/*
 * Разрешение имён для byedpi. byedpi собирается с -Dgetaddrinfo=zapretify_getaddrinfo (Android.mk),
 * и все его запросы адресов идут сюда.
 *
 * Серверы видео YouTube (*.googlevideo.com) — только по IPv4. Замер в мобильной сети (10.11.2026): местный кеш
 * Google у провайдера по IPv6 не принимал TCP-соединения, а по IPv4 работал. Остальные сайты — как обычно
 * (IPv4 и IPv6): Google выбирает местный сервер видео по адресу запроса к youtubei, и по IPv6 он был местным,
 * а по IPv4 — зарубежным, заблокированным по адресу.
 */
#undef getaddrinfo
#include <netdb.h>
#include <string.h>
#include <strings.h>

static int ends_with(const char *s, const char *suffix) {
    size_t n = strlen(s), m = strlen(suffix);
    return n >= m && strcasecmp(s + n - m, suffix) == 0;
}

int zapretify_getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **res) {
    if (node && hints && hints->ai_family == AF_UNSPEC &&
        (ends_with(node, ".googlevideo.com") || strcasecmp(node, "googlevideo.com") == 0)) {
        struct addrinfo h = *hints;
        h.ai_family = AF_INET;
        return getaddrinfo(node, service, &h, res);
    }
    return getaddrinfo(node, service, hints, res);
}
