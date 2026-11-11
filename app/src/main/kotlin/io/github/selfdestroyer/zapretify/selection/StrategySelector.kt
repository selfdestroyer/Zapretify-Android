package io.github.selfdestroyer.zapretify.selection

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors

enum class Service { YOUTUBE, DISCORD, TELEGRAM, CONTROL }

/** Адрес, по которому проверяется стратегия. [weight] — вклад в оценку. */
data class Target(
    val id: String,
    val title: String,
    val service: Service,
    val host: String,
    val ip: InetAddress? = null,
    val weight: Int = 1,
)

/** Стратегия-кандидат подбора. */
data class Candidate(val id: String, val name: String, val args: String)

/** Сколько раз адрес открылся из скольких проверок, и чем закончилась последняя. */
data class TargetStat(val passed: Int, val total: Int, val last: Outcome) {
    val ok: Boolean get() = total > 0 && passed == total
}

data class CandidateResult(
    val candidate: Candidate,
    /** Сколько прогонов сделано (лучшие стратегии проверяются несколько раз). */
    val runs: Int,
    val stats: Map<String, TargetStat>,
    /** Движок не запустился с этими параметрами. */
    val engineFailed: Boolean,
    /** Оценка 0–100: доля открывшихся адресов с учётом веса. */
    val score: Int,
) {
    fun ok(targetId: String): Boolean = stats[targetId]?.ok == true
}

data class SelectionReport(
    /** Стратегии по убыванию оценки. */
    val results: List<CandidateResult>,
    val targets: List<Target>,
    /** Что открывается без обхода. */
    val baseline: Map<String, TargetStat>,
    /** Адреса, с которыми не удалось соединиться ни разу: закрыты по IP или недоступны — обход тут не поможет. */
    val unreachable: Set<String>,
    /** Сервер видео, назначенный этой сети; null — узнать не удалось. */
    val videoHost: String?,
    /** Адреса, для которых DNS не ответил: они не проверялись. */
    val unresolved: List<Target>,
    val cancelled: Boolean,
)

data class SelectionProgress(val done: Int, val total: Int, val current: String, val report: SelectionReport)

/** Всё, что подбору нужно от устройства: запуск движка и сеть. В тестах подменяется. */
interface SelectionBackend {
    /** Запускает движок с параметрами стратегии (пустая строка — без обхода). false — не запустился. */
    fun startEngine(args: String): Boolean
    fun stopEngine()
    fun probe(target: Target, layout: ClientHello.Layout): Outcome
    /** Имя кластера видео через работающий движок ([redirector] — адрес redirector.googlevideo.com); null — не удалось. */
    fun discoverVideoCluster(redirector: InetAddress?): String?
    fun resolve(host: String): List<InetAddress>
}

/**
 * Подбор стратегии (план, этап 4а). Порядок:
 * 0. Адреса всех сайтов узнаются заранее, один раз: byedpi разрешает имена по одному, и один медленный ответ DNS
 *    останавливает все его соединения — проверка стратегий не должна от этого зависеть.
 * 1. Прогон без обхода — что открыто и так (эти адреса стратегия не должна ломать).
 * 2. Каждая стратегия: запуск движка, проверка всех адресов большим ClientHello в двух раскладках.
 *    Как только какая-то стратегия открыла redirector.googlevideo.com — узнаём настоящий сервер видео сети
 *    и добавляем его в проверку отдельно по IPv4 и по IPv6.
 * 3. Лучшие стратегии проверяются ещё [extraRuns] раза: мобильная сеть шумит, один прогон ненадёжен.
 * Адреса, с которыми ни разу не удалось соединиться, из оценки исключаются и показываются отдельно.
 */
class StrategySelector(
    private val backend: SelectionBackend,
    candidates: List<Candidate>,
    baseTargets: List<Target> = defaultTargets(),
    private val topCount: Int = 5,
    private val extraRuns: Int = 2,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (SelectionProgress) -> Unit = {},
) {
    /** Одинаковые параметры (например, своя стратегия совпала со встроенной) проверяются один раз. */
    private val candidates = candidates.distinctBy { it.args.trim() }.toMutableList()
    private val wanted = baseTargets
    private val targets = mutableListOf<Target>()
    private val unresolved = mutableListOf<Target>()
    private val pool = Executors.newFixedThreadPool(32)
    private val runs = LinkedHashMap<String, Int>()
    private val failed = HashSet<String>()
    private val data = LinkedHashMap<String, MutableMap<String, TargetStat>>()
    private var baseline: Map<String, TargetStat> = emptyMap()
    private var videoHost: String? = null
    private var videoAttempts = 0
    private var redirectorAddrs: List<InetAddress> = emptyList()
    private var done = 0

    fun run(): SelectionReport {
        try {
            progress("Определение адресов сайтов")
            resolveTargets()
            if (targets.isEmpty()) throw IllegalStateException("DNS не отвечает — проверьте интернет")
            if (!backend.startEngine("")) throw IllegalStateException("движок не запустился")
            try {
                progress("Проверка без обхода")
                baseline = measure(targets.toList())
                maybeDiscoverVideo(baseline)?.let { added -> baseline = baseline + measure(added) }
            } finally {
                backend.stopEngine()
            }
            done++

            for (c in candidates) {
                if (isCancelled()) break
                progress(c.name)
                runOnce(c)
                done++
            }

            val top = report(false).results.filter { !it.engineFailed && it.score > 0 }.take(topCount).map { it.candidate }
            repeat(extraRuns) {
                for (c in top) {
                    if (isCancelled()) return@repeat
                    progress("Повторная проверка: ${c.name}")
                    runOnce(c)
                    done++
                }
            }
            combineForVideo()
            return report(isCancelled()).also { onProgress(SelectionProgress(done, done, "", it)) }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Узнаёт адреса сайтов параллельно, с общим ограничением по времени. Берётся первый адрес из ответа системы —
     * тот же, что выбрал бы движок; для серверов видео — IPv4, как в движке (zapretify_resolv.c).
     */
    private fun resolveTargets() {
        val jobs = wanted.map { t -> t to pool.submit(Callable { if (t.ip != null) listOf(t.ip) else backend.resolve(t.host) }) }
        val deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS
        for ((t, job) in jobs) {
            val addrs = runCatching {
                job.get((deadline - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrNull().orEmpty()
            job.cancel(true)
            val ip = (if (t.host.endsWith(".googlevideo.com")) addrs.firstOrNull { it is Inet4Address } else null) ?: addrs.firstOrNull()
            if (ip == null) unresolved += t else targets += t.copy(ip = ip)
            if (t.id == REDIRECTOR) redirectorAddrs = listOfNotNull(addrs.firstOrNull(), ip).distinct()
        }
    }

    /**
     * Если лучшая стратегия не открывает сервер видео, а какая-то другая открывает, собирается объединённая:
     * для серверов видео — приём второй, для всего остального — первая (группы byedpi с фильтром по домену).
     * Так было в мобильной сети 11.11.2026: видео открывала только перестановка, остальное — разбиение TLS-записи.
     */
    private fun combineForVideo() {
        if (isCancelled()) return
        val now = report(false)
        val video = listOf(VIDEO4, VIDEO6).filter { id -> targets.any { it.id == id } && id !in now.unreachable }
        val best = now.results.firstOrNull { !it.engineFailed && it.score > 0 } ?: return
        if (video.isEmpty() || video.any { best.ok(it) }) return
        val donor = now.results.firstOrNull { r -> !r.engineFailed && video.any { r.ok(it) } && !hasGroups(r.candidate.args) } ?: return
        val combined = Candidate(COMBINED, "Объединённая: для видео свой приём", "-H:googlevideo.com ${donor.candidate.args} -An ${best.candidate.args}")
        if (candidates.any { it.args == combined.args }) return
        candidates += combined
        repeat(1 + extraRuns) {
            if (isCancelled()) return
            progress(combined.name)
            runOnce(combined)
            done++
        }
    }

    /** В параметрах уже есть группы или фильтр доменов — такую стратегию нельзя вставить в группу для видео. */
    private fun hasGroups(args: String): Boolean =
        args.split(' ').any { it.startsWith("-A") || it.startsWith("--auto") || it.startsWith("-H") || it.startsWith("--hosts") }

    private fun runOnce(c: Candidate) {
        if (!backend.startEngine(c.args)) {
            failed += c.id
            runs.putIfAbsent(c.id, 0)
            return
        }
        try {
            val stats = data.getOrPut(c.id) { LinkedHashMap() }
            val first = measure(targets.toList())
            merge(stats, first)
            maybeDiscoverVideo(first)?.let { added -> merge(stats, measure(added)) }
            runs[c.id] = (runs[c.id] ?: 0) + 1
        } finally {
            backend.stopEngine()
        }
    }

    /** Один прогон: адрес засчитан, если ответ пришёл при обеих раскладках ClientHello. */
    private fun measure(list: List<Target>): Map<String, TargetStat> {
        val jobs = list.map { t ->
            t to ClientHello.Layout.entries.map { layout -> pool.submit(Callable { backend.probe(t, layout) }) }
        }
        return jobs.associate { (t, futures) ->
            val outcomes = futures.map { runCatching { it.get() }.getOrDefault(Outcome.BROKEN) }
            val worst = outcomes.firstOrNull { it != Outcome.OK } ?: Outcome.OK
            t.id to TargetStat(if (worst == Outcome.OK) 1 else 0, 1, worst)
        }
    }

    private fun merge(into: MutableMap<String, TargetStat>, add: Map<String, TargetStat>) {
        for ((id, s) in add) {
            val old = into[id]
            into[id] = if (old == null) s else TargetStat(old.passed + s.passed, old.total + s.total, s.last)
        }
    }

    /** Узнаёт сервер видео, когда текущая стратегия открыла redirector; возвращает добавленные адреса. */
    private fun maybeDiscoverVideo(stats: Map<String, TargetStat>): List<Target>? {
        // Не больше трёх попыток: каждая неудачная стоит нескольких секунд.
        if (videoHost != null || videoAttempts >= MAX_VIDEO_ATTEMPTS || stats[REDIRECTOR]?.ok != true) return null
        videoAttempts++
        // Google назначает кластер видео по адресу, с которого пришёл запрос. Приложение YouTube спрашивает его
        // по адресу, который система выбирает сама (обычно IPv6, если он есть), — спрашиваем так же, иначе получим
        // кластер, которым YouTube в этой сети не пользуется. Запасной вариант — адрес из проверки (IPv4).
        val cluster = redirectorAddrs.firstNotNullOfOrNull { backend.discoverVideoCluster(it) } ?: return null
        videoAttempts = MAX_VIDEO_ATTEMPTS
        val host = VideoCluster.hostFor(cluster)
        val addrs = runCatching {
            pool.submit(Callable { backend.resolve(host) }).get(RESOLVE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(emptyList())
        val added = buildList {
            addrs.firstOrNull { it is Inet4Address }?.let { add(Target(VIDEO4, "Видео (IPv4)", Service.YOUTUBE, host, it, weight = 2)) }
            addrs.firstOrNull { it is Inet6Address }?.let { add(Target(VIDEO6, "Видео (IPv6)", Service.YOUTUBE, host, it, weight = 2)) }
        }
        if (added.isEmpty()) return null
        videoHost = host
        targets += added
        return added
    }

    private fun progress(current: String) {
        val total = (1 + candidates.size + topCount.coerceAtMost(candidates.size) * extraRuns).coerceAtLeast(done + 1)
        onProgress(SelectionProgress(done, total, current, report(false)))
    }

    private fun report(cancelled: Boolean): SelectionReport {
        // Недоступные адреса: ни одного соединения ни без обхода, ни с одной из стратегий.
        val seen = (data.values + listOf(baseline)).flatMap { it.entries }.groupBy({ it.key }, { it.value })
        val unreachable = seen.filter { (_, stats) -> stats.isNotEmpty() && stats.all { it.last == Outcome.NO_TCP && it.passed == 0 } }.keys
        val scored = targets.filter { it.id !in unreachable }
        val results = candidates.filter { it.id in runs }.map { c ->
            val stats = data[c.id].orEmpty()
            CandidateResult(c, runs[c.id] ?: 0, stats, c.id in failed, score(stats, scored))
        }.sortedWith(compareByDescending<CandidateResult> { it.score }.thenByDescending { it.runs }.thenBy { it.candidate.args.length })
        return SelectionReport(results, targets.toList(), baseline, unreachable, videoHost, unresolved.toList(), cancelled)
    }

    /** Доля открывшихся адресов с учётом веса; адреса, которые стратегия ещё не проверяла, не считаются. */
    private fun score(stats: Map<String, TargetStat>, scored: List<Target>): Int {
        var got = 0.0
        var max = 0
        for (t in scored) {
            val s = stats[t.id] ?: continue
            got += t.weight * s.passed.toDouble() / s.total
            max += t.weight
        }
        return if (max == 0) 0 else Math.round(100 * got / max).toInt()
    }

    companion object {
        const val REDIRECTOR = "yt_redirector"
        const val VIDEO4 = "yt_video4"
        const val VIDEO6 = "yt_video6"
        const val CONTROL = "control"
        const val COMBINED = "combined"
        private const val MAX_VIDEO_ATTEMPTS = 3
        private const val RESOLVE_TIMEOUT_MS = 6000L

        /**
         * Адреса проверки. [telegramHost] — узел пути Telegram-прокси через Cloudflare.
         * Контрольный сайт открыт и без обхода: стратегия, которая его ломает, сломает и обычные сайты в браузере.
         */
        fun defaultTargets(telegramHost: String? = null): List<Target> = listOfNotNull(
            Target("yt_web", "Сайт YouTube", Service.YOUTUBE, "www.youtube.com"),
            Target("yt_api", "Приложение YouTube", Service.YOUTUBE, "youtubei.googleapis.com"),
            Target("yt_img", "Обложки видео", Service.YOUTUBE, "i.ytimg.com"),
            Target(REDIRECTOR, "Раздача видео", Service.YOUTUBE, "redirector.googlevideo.com"),
            Target("dc_web", "Discord", Service.DISCORD, "discord.com"),
            Target("dc_gw", "Чат Discord", Service.DISCORD, "gateway.discord.gg"),
            Target("dc_cdn", "Файлы Discord", Service.DISCORD, "cdn.discordapp.com"),
            Target("dc_media", "Картинки Discord", Service.DISCORD, "media.discordapp.net"),
            telegramHost?.let { Target("tg_cf", "Telegram-прокси", Service.TELEGRAM, it) },
            Target(CONTROL, "Обычные сайты", Service.CONTROL, "ya.ru", weight = 3),
        )
    }
}
