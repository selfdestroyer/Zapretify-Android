package io.github.selfdestroyer.zapretify.selection

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.engine.EngineState
import io.github.selfdestroyer.zapretify.engine.NativeBridge
import io.github.selfdestroyer.zapretify.engine.Strategies
import io.github.selfdestroyer.zapretify.engine.StrategyValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Строка итогов подбора для экрана (и для сохранения прошлого результата). */
data class SelectionRow(
    val strategyId: String,
    val name: String,
    val args: String,
    val score: Int,
    val runs: Int,
    val engineFailed: Boolean,
    val youtubeSite: Boolean,
    val youtubeApp: Boolean,
    /** null — сервер видео этой сети узнать не удалось. */
    val video: Boolean?,
    val discordChat: Boolean,
    val discordFiles: Boolean,
    val telegram: Boolean,
    val breaksSites: Boolean,
)

data class SelectionState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val rows: List<SelectionRow> = emptyList(),
    /** Пояснения к итогам: что открыто без обхода, что закрыто по адресу. */
    val notes: List<String> = emptyList(),
    val error: String? = null,
    /** Когда закончен показанный подбор (мс), 0 — подбора ещё не было. */
    val finishedAt: Long = 0,
    val cancelled: Boolean = false,
)

/**
 * Подбор стратегии в приложении (план, этап 4а). Движок byedpi в приложении один, поэтому на время подбора
 * обход выключается, а после — включается снова. Проверки идут через byedpi на отдельном порту, без VPN:
 * соединения самого приложения в VPN не входят.
 */
// Хранится контекст приложения (не экрана): утечки нет.
@SuppressLint("StaticFieldLeak")
object SelectionController {
    private const val PORT = 10801
    private const val PREFS = "selection"
    private const val KEY_LAST = "last"

    private lateinit var app: Context
    private val _state = MutableStateFlow(SelectionState())
    val state: StateFlow<SelectionState> = _state.asStateFlow()
    private val cancel = AtomicBoolean(false)

    fun init(context: Context) {
        app = context.applicationContext
        runCatching { load() }
    }

    fun stop() = cancel.set(true)

    /** @param telegramHost узел пути Telegram-прокси через Cloudflare для проверки (null — не проверять) */
    fun start(telegramHost: String?) {
        if (_state.value.running) return
        if (!NativeBridge.available) {
            _state.update { it.copy(error = "Движок обхода не входит в эту сборку") }
            return
        }
        val candidates = EngineController.state.value.strategies.map { Candidate(it.id, it.name, it.args) }
        cancel.set(false)
        _state.update { SelectionState(running = true, total = candidates.size + 1, current = "Подготовка") }
        thread(name = "selection") {
            val wasOn = EngineController.state.value.phase != EngineState.Phase.Off
            val proxyBypass = io.github.selfdestroyer.zapretify.proxy.ProxyController.releaseBypassForSelection()
            try {
                if (wasOn) {
                    EngineController.stop()
                    val deadline = System.currentTimeMillis() + 8000
                    while (EngineController.state.value.phase != EngineState.Phase.Off && System.currentTimeMillis() < deadline) Thread.sleep(100)
                }
                val report = StrategySelector(
                    backend = Backend(),
                    candidates = candidates,
                    baseTargets = StrategySelector.defaultTargets(telegramHost),
                    isCancelled = { cancel.get() },
                    onProgress = { p -> _state.update { it.copy(done = p.done, total = p.total, current = p.current, rows = rows(p.report), notes = notes(p.report, finished = false)) } },
                ).run()
                val finished = SelectionState(rows = rows(report), notes = notes(report, finished = true), finishedAt = System.currentTimeMillis(), cancelled = report.cancelled)
                _state.value = finished
                runCatching { save(finished) }
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = "Подбор не удался: ${e.message}") }
            } finally {
                if (wasOn) EngineController.start()
                else if (proxyBypass) io.github.selfdestroyer.zapretify.proxy.ProxyController.resumeBypass()
            }
        }
    }

    /** byedpi на отдельном порту и проверки через него. */
    private class Backend : SelectionBackend {
        private val socks = InetSocketAddress("127.0.0.1", PORT)
        private val probe = TlsProbe(socks)
        private var engine: Thread? = null

        override fun startEngine(args: String): Boolean {
            val strategyArgs = Strategies.splitArgs(args)
            if (StrategyValidator.check(strategyArgs) != null) return false
            val argv = arrayOf("ciadpi", "-i", "127.0.0.1", "-p", PORT.toString()) + strategyArgs
            val t = thread(name = "byedpi-selection") { NativeBridge.runByedpi(argv) }
            engine = t
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                if (!t.isAlive) return false
                if (runCatching { Socket().use { it.connect(socks, 200) } }.isSuccess) return true
                Thread.sleep(50)
            }
            stopEngine()
            return false
        }

        override fun stopEngine() {
            val t = engine ?: return
            engine = null
            // Остановка действует, только когда byedpi уже слушает порт; повторяем, пока поток не завершится.
            repeat(30) {
                if (!t.isAlive) return
                runCatching { NativeBridge.stopByedpi() }
                t.join(100)
            }
        }

        private val bulk = BulkProbe(socks)
        override fun probe(target: Target, layout: ClientHello.Layout): Outcome =
            if (target.bulkPath != null) bulk.check(target.host, target.bulkPath, target.ip, StrategySelector.BULK_BYTES)
            else probe.check(target.host, layout, target.ip)
        override fun discoverVideoCluster(redirector: InetAddress?): String? =
            VideoCluster.discover(socks, redirector, onError = { android.util.Log.w("selection", "сервер видео не определён: $it") })
        override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
    }

    private fun rows(r: SelectionReport): List<SelectionRow> {
        val videoIds = listOf(StrategySelector.VIDEO4, StrategySelector.VIDEO6).filter { id -> r.targets.any { it.id == id } && id !in r.unreachable }
        return r.results.map { c ->
            SelectionRow(
                strategyId = c.candidate.id, name = c.candidate.name, args = c.candidate.args, score = c.score, runs = c.runs,
                engineFailed = c.engineFailed,
                youtubeSite = c.ok("yt_web") && c.ok("yt_img"),
                youtubeApp = c.ok("yt_api"),
                video = if (videoIds.isEmpty() || videoIds.none { it in c.stats }) null else videoIds.any { c.ok(it) },
                // Заморозка после первых килобайт ломает и Discord, и путь Telegram-прокси через Cloudflare.
                discordChat = c.ok("dc_web") && c.ok("dc_gw") && bulkOk(c),
                discordFiles = c.ok("dc_cdn") && c.ok("dc_media"),
                telegram = c.ok("tg_cf") && bulkOk(c),
                breaksSites = r.baseline[StrategySelector.CONTROL]?.ok == true && StrategySelector.CONTROL in c.stats && !c.ok(StrategySelector.CONTROL),
            )
        }
    }

    /** Загрузка с Cloudflare не заморожена (или не проверялась). */
    private fun bulkOk(c: CandidateResult) = c.stats[StrategySelector.CF_BULK]?.let { it.ok } ?: true

    private fun notes(r: SelectionReport, finished: Boolean): List<String> = buildList {
        val open = r.targets.filter { r.baseline[it.id]?.ok == true && it.service != Service.CONTROL }.map { it.title }
        if (open.isNotEmpty()) add("Без обхода в этой сети уже открыто: ${open.joinToString(", ")}.")
        if (r.baseline.isNotEmpty() && r.baseline[StrategySelector.CONTROL]?.ok != true) add("Обычные сайты не отвечают — проверьте интернет: результатам подбора доверять нельзя.")
        val dead = r.targets.filter { it.id in r.unreachable }
        val deadVideo = dead.filter { it.id == StrategySelector.VIDEO4 || it.id == StrategySelector.VIDEO6 }
        val videoTargets = r.targets.count { it.id == StrategySelector.VIDEO4 || it.id == StrategySelector.VIDEO6 }
        when {
            r.videoHost == null && finished && r.results.isNotEmpty() -> add("Сервер видео этой сети узнать не удалось — видео не проверялось.")
            videoTargets > 0 && deadVideo.size == videoTargets -> add("Сервер видео этой сети не отвечает ни по IPv4, ни по IPv6: похоже, он закрыт по адресу. Стратегии тут не помогут.")
            deadVideo.isNotEmpty() -> add("Сервер видео не отвечает по ${if (deadVideo.first().id == StrategySelector.VIDEO6) "IPv6" else "IPv4"} — видео проверялось по другому протоколу.")
        }
        if (r.unresolved.isNotEmpty()) add("DNS не ответил, не проверялись: ${r.unresolved.joinToString(", ") { it.title }}.")
        // Что с сервером видео у лучшей стратегии — по каждому протоколу отдельно.
        r.videoHost?.let { host ->
            add("Сервер видео этой сети: ${host.removePrefix("rr1---").removeSuffix(".googlevideo.com")}.")
            val best = r.results.firstOrNull { !it.engineFailed }
            for (t in r.targets.filter { (it.id == StrategySelector.VIDEO4 || it.id == StrategySelector.VIDEO6) && it.id !in r.unreachable }) {
                val s = best?.stats?.get(t.id) ?: continue
                val what = when {
                    s.ok -> "работает"
                    s.passed > 0 -> "работает не всегда (${s.passed} из ${s.total})"
                    s.last == Outcome.NO_TCP -> "нет соединения"
                    s.last == Outcome.TIMEOUT -> "соединение есть, сервер не отвечает"
                    s.last == Outcome.RESET -> "соединение сбрасывается"
                    else -> "приходит не ответ сервера"
                }
                add("${t.title} у лучшей стратегии: $what.")
            }
        }
        if (finished && r.baseline[StrategySelector.CF_BULK]?.ok == true) {
            val best = r.results.firstOrNull { !it.engineFailed }
            if (best != null && best.stats[StrategySelector.CF_BULK]?.ok == false && r.results.none { !it.engineFailed && it.ok(StrategySelector.CF_BULK) && it.ok("dc_gw") }) {
                add("Провайдер замораживает соединения с Cloudflare после первых ~16 КБ, когда не может прочитать имя сайта. " +
                    "Discord и путь Telegram-прокси через Cloudflare в этой сети без VPN не заработают; YouTube это не мешает.")
            }
        }
        val other = dead.filter { it !in deadVideo }.map { it.title }
        if (other.isNotEmpty()) add("Не удалось даже соединиться (закрыто по адресу или недоступно): ${other.joinToString(", ")}.")
    }

    private fun save(s: SelectionState) {
        val arr = JSONArray()
        s.rows.take(60).forEach { r ->
            arr.put(JSONObject().put("id", r.strategyId).put("name", r.name).put("args", r.args).put("score", r.score).put("runs", r.runs)
                .put("failed", r.engineFailed).put("ys", r.youtubeSite).put("ya", r.youtubeApp).put("v", r.video ?: JSONObject.NULL)
                .put("dc", r.discordChat).put("df", r.discordFiles).put("tg", r.telegram).put("bs", r.breaksSites))
        }
        val json = JSONObject().put("at", s.finishedAt).put("cancelled", s.cancelled).put("notes", JSONArray(s.notes)).put("rows", arr)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_LAST, json.toString()) }
    }

    private fun load() {
        val text = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null) ?: return
        val json = JSONObject(text)
        val arr = json.getJSONArray("rows")
        val rows = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SelectionRow(o.getString("id"), o.getString("name"), o.getString("args"), o.getInt("score"), o.getInt("runs"), o.getBoolean("failed"),
                o.getBoolean("ys"), o.getBoolean("ya"), if (o.isNull("v")) null else o.getBoolean("v"),
                o.getBoolean("dc"), o.getBoolean("df"), o.getBoolean("tg"), o.getBoolean("bs"))
        }
        val notes = json.getJSONArray("notes").let { n -> (0 until n.length()).map { n.getString(it) } }
        _state.value = SelectionState(rows = rows, notes = notes, finishedAt = json.getLong("at"), cancelled = json.optBoolean("cancelled"))
    }
}
