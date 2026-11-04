package com.yunseong.claudewatch.dev

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * debug 빌드 전용: 무선 디버깅 토글. (docs/DESIGN.md 8.8)
 *
 * - 무선 디버깅 = Settings.Global "adb_wifi_enabled". 바꾸려면 WRITE_SECURE_SETTINGS가 필요하고,
 *   이 권한은 src/debug 매니페스트에만 있다. adb로 한 번 허용한다.
 * - 갤럭시 워치는 폰과 블루투스로 연결돼 있으면 Wi-Fi를 끈다. Wi-Fi 네트워크를 요청해 두면
 *   요청을 놓을 때까지 Wi-Fi를 유지한다. 화면이 보이는 동안만 요청한다.
 * - 맥의 adb는 한 번 페어링한 워치를 mDNS로 찾아 자동으로 다시 연결한다.
 */
class WirelessDebugging private constructor(private val context: Context) {

    data class State(
        val hasPermission: Boolean = false,
        val adbEnabled: Boolean = false,
        val wifiConnected: Boolean = false,
        val ipAddress: String? = null,
        val error: String? = null,
    )

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    fun refresh() {
        _state.value = _state.value.copy(
            hasPermission = hasPermission(),
            adbEnabled = Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1,
        )
    }

    /** 화면이 보일 때: 무선 디버깅이 켜져 있으면 Wi-Fi를 붙잡는다 */
    fun onVisible() {
        refresh()
        if (_state.value.adbEnabled) holdWifi()
    }

    /** 화면이 가려질 때: Wi-Fi 요청을 놓는다 (배터리) */
    fun onHidden() = releaseWifi()

    fun toggle() {
        refresh()
        if (!_state.value.hasPermission) return
        val enable = !_state.value.adbEnabled
        try {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, if (enable) 1 else 0)
            if (enable) holdWifi() else releaseWifi()
            _state.value = _state.value.copy(error = null)
        } catch (e: SecurityException) {
            Log.w(TAG, "무선 디버깅 변경 실패", e)
            _state.value = _state.value.copy(error = e.message)
        }
        refresh()
    }

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun holdWifi() {
        if (wifiCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.value = _state.value.copy(wifiConnected = true)
            }

            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                val ip = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
                _state.value = _state.value.copy(ipAddress = ip)
            }

            override fun onLost(network: Network) {
                _state.value = _state.value.copy(wifiConnected = false, ipAddress = null)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivity.requestNetwork(request, callback)
            wifiCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi 요청 실패", e)
            _state.value = _state.value.copy(error = "Wi-Fi 요청 실패: ${e.message}")
        }
    }

    private fun releaseWifi() {
        wifiCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        wifiCallback = null
        _state.value = _state.value.copy(wifiConnected = false, ipAddress = null)
    }

    companion object {
        private const val TAG = "WirelessDebugging"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val GRANT_COMMAND =
            "adb shell pm grant com.yunseong.claudewatch android.permission.WRITE_SECURE_SETTINGS"

        @Volatile private var instance: WirelessDebugging? = null

        fun get(context: Context): WirelessDebugging =
            instance ?: synchronized(this) {
                instance ?: WirelessDebugging(context.applicationContext).also { instance = it }
            }
    }
}
