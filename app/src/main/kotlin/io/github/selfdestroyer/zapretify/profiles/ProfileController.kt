package io.github.selfdestroyer.zapretify.profiles

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.engine.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Профиль: название и стратегия, по желанию привязанная к сети (план, раздел 3.4, пункт 9).
 * @property networkKey ключ сети ([NetworkId.key]); null — профиль без привязки, включается только вручную.
 */
data class Profile(
    val id: String,
    val name: String,
    val networkKey: String?,
    val networkTitle: String?,
    val strategyName: String,
    val strategyArgs: String,
)

data class ProfilesState(
    val profiles: List<Profile> = emptyList(),
    /** Профиль, чья стратегия сейчас включена; null — стратегия выбрана без профиля. */
    val activeId: String? = null,
    /** Переключать стратегию при смене сети. */
    val auto: Boolean = true,
    val network: NetworkId = NetworkId.NONE,
) {
    val forNetwork: Profile? get() = profiles.firstOrNull { it.networkKey == network.key }
}

/**
 * Профили стратегий по сетям. Профиль создаёт пользователь (название, стратегия, сеть); при смене сети включается
 * стратегия профиля этой сети. Выбор другой стратегии, пока профиль включён, меняет стратегию этого профиля.
 * Мобильная сеть различается по оператору без разрешений; сети Wi-Fi по названию — только с разрешением на
 * геолокацию (без него все Wi-Fi — одна сеть «Wi-Fi»).
 */
// Хранится контекст приложения (не экрана): утечки нет.
@SuppressLint("StaticFieldLeak")
object ProfileController {
    private const val PREFS = "profiles"
    private lateinit var app: Context
    private val _state = MutableStateFlow(ProfilesState())
    val state: StateFlow<ProfilesState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        app = context.applicationContext
        runCatching { load() }
        EngineController.onUserChoice = ::onUserChoice
        scope.launch {
            NetworkWatcher.current.collect { net ->
                val changed = net.key != _state.value.network.key
                _state.update { it.copy(network = net) }
                if (changed) onNetworkChanged()
            }
        }
    }

    /** Пользователь выбрал стратегию, пока включён профиль, — это новая стратегия профиля. */
    private fun onUserChoice(strategy: Strategy) {
        val active = _state.value.profiles.firstOrNull { it.id == _state.value.activeId } ?: return
        put(active.copy(strategyName = strategy.name, strategyArgs = strategy.args))
    }

    /** Создать или сохранить профиль (из окна профиля). Возвращает профиль. */
    fun save(id: String?, name: String, strategy: Strategy, network: NetworkId?): Profile {
        val p = Profile(
            id = id ?: UUID.randomUUID().toString(),
            name = name.trim().take(40).ifEmpty { "Профиль" },
            networkKey = network?.key, networkTitle = network?.title,
            strategyName = strategy.name, strategyArgs = strategy.args,
        )
        // Одна сеть — один профиль: прежний профиль этой сети теряет привязку.
        _state.update { st ->
            st.copy(profiles = st.profiles.map {
                if (it.id != p.id && p.networkKey != null && it.networkKey == p.networkKey) it.copy(networkKey = null, networkTitle = null) else it
            })
        }
        put(p)
        // Новый профиль текущей сети или правка включённого — включаем сразу.
        if (p.id == _state.value.activeId || (p.networkKey != null && p.networkKey == _state.value.network.key)) activate(p)
        return p
    }

    private fun put(p: Profile) {
        _state.update { st ->
            val list = st.profiles.toMutableList()
            val i = list.indexOfFirst { it.id == p.id }
            if (i >= 0) list[i] = p else list += p
            st.copy(profiles = list)
        }
        persist()
    }

    private fun onNetworkChanged() {
        val s = _state.value
        if (!s.auto || s.network.kind == NetworkId.Kind.NONE) return
        val p = s.forNetwork ?: return
        if (p.id == s.activeId && EngineController.state.value.strategy?.args == p.strategyArgs) return
        activate(p)
    }

    /** Включить стратегию профиля. */
    fun activate(profile: Profile) {
        _state.update { it.copy(activeId = profile.id) }
        persist()
        EngineController.applyArgs(profile.strategyArgs)
    }

    /** Записать стратегию в профиль (из итогов подбора). Если профиль включён — стратегия включается сразу. */
    fun setStrategy(profile: Profile, name: String, args: String) {
        val p = profile.copy(strategyName = name, strategyArgs = args)
        put(p)
        if (p.id == _state.value.activeId) EngineController.applyArgs(args)
    }

    fun delete(profile: Profile) {
        _state.update { st -> st.copy(profiles = st.profiles.filter { it.id != profile.id }, activeId = st.activeId.takeIf { it != profile.id }) }
        persist()
    }

    fun setAuto(on: Boolean) {
        _state.update { it.copy(auto = on) }
        persist()
        if (on) onNetworkChanged()
    }

    private fun persist() {
        val s = _state.value
        val arr = JSONArray()
        s.profiles.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("net", p.networkKey ?: JSONObject.NULL)
                .put("netTitle", p.networkTitle ?: JSONObject.NULL).put("sName", p.strategyName).put("sArgs", p.strategyArgs))
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("list", arr.toString()); putString("active", s.activeId); putBoolean("auto", s.auto)
        }
    }

    private fun load() {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("list", "[]"))
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Profile(o.getString("id"), o.getString("name"), if (o.isNull("net")) null else o.getString("net"),
                if (o.isNull("netTitle")) null else o.getString("netTitle"), o.getString("sName"), o.getString("sArgs"))
        }
        _state.value = ProfilesState(list, prefs.getString("active", null), prefs.getBoolean("auto", true))
    }
}
