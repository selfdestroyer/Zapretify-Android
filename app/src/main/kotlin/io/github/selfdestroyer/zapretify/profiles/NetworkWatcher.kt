package io.github.selfdestroyer.zapretify.profiles

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сеть, через которую сейчас идёт интернет.
 * @property name оператор мобильной сети (без разрешений) или название Wi-Fi (только с разрешением на геолокацию:
 *   так устроен Android); null — узнать нельзя.
 */
data class NetworkId(val kind: Kind, val name: String?) {
    enum class Kind { MOBILE, WIFI, OTHER, NONE }

    /** Ключ для привязки профиля. Wi-Fi без названия — один общий ключ «любой Wi-Fi». */
    val key: String get() = "${kind.name}:${name.orEmpty()}"

    val title: String get() = when (kind) {
        Kind.MOBILE -> name?.let { "Мобильная сеть · $it" } ?: "Мобильная сеть"
        Kind.WIFI -> name?.let { "Wi-Fi «$it»" } ?: "Wi-Fi"
        Kind.OTHER -> "Другая сеть"
        Kind.NONE -> "Нет сети"
    }

    /** Короткое имя для нового профиля. */
    val profileName: String get() = when (kind) {
        Kind.MOBILE -> name ?: "Мобильная сеть"
        Kind.WIFI -> name ?: "Wi-Fi"
        Kind.OTHER -> "Другая сеть"
        Kind.NONE -> "Без сети"
    }

    companion object {
        val NONE = NetworkId(Kind.NONE, null)

        /** Восстановить сеть из сохранённого ключа профиля. */
        fun fromKey(key: String, title: String?): NetworkId? {
            val kind = runCatching { Kind.valueOf(key.substringBefore(':')) }.getOrNull() ?: return null
            return NetworkId(kind, key.substringAfter(':').ifEmpty { null })
        }
    }
}

/** Следит за сетью по умолчанию. Свой VPN в расчёт не берётся: смотрим на сеть под ним. */
// Хранится контекст приложения (не экрана): утечки нет.
@SuppressLint("StaticFieldLeak")
object NetworkWatcher {
    private lateinit var app: Context
    private val _current = MutableStateFlow(NetworkId.NONE)
    val current: StateFlow<NetworkId> = _current.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
                override fun onLost(network: Network) = refresh()
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
                override fun onLost(network: Network) = refresh()
            }
        }
        // Сеть по умолчанию, но под VPN: у самого VPN нет ни оператора, ни названия Wi-Fi.
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        refresh()
    }

    /** Есть ли разрешение, без которого Android не называет сеть Wi-Fi. */
    fun canNameWifi(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Перечитать сеть (например, после выдачи разрешения). */
    fun refresh() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val best = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .maxByOrNull { if (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 1 }
        if (best == null) _current.value = NetworkId.NONE else update(best)
    }

    private fun update(caps: NetworkCapabilities) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        _current.value = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkId(NetworkId.Kind.WIFI, wifiName(caps))
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkId(NetworkId.Kind.MOBILE, operatorName())
            else -> NetworkId(NetworkId.Kind.OTHER, null)
        }
    }

    private fun operatorName(): String? =
        runCatching { app.getSystemService(TelephonyManager::class.java).networkOperatorName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    @Suppress("DEPRECATION")
    private fun wifiName(caps: NetworkCapabilities): String? {
        if (!canNameWifi()) return null
        val info = (if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null)
            ?: runCatching { app.getSystemService(WifiManager::class.java).connectionInfo }.getOrNull()
        val ssid = info?.ssid?.trim('"')?.trim() ?: return null
        return ssid.takeIf { it.isNotEmpty() && it != "unknown ssid" && !it.startsWith("<") }
    }
}
