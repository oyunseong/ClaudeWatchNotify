package com.yunseong.claudewatch.register

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.yunseong.claudewatch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** FCM 토큰을 Worker의 POST /register 로 알린다. (docs/DESIGN.md 4.1, 8.5) */
class TokenRegistrar private constructor(private val appContext: Context) {

    data class Status(
        val registeredToken: String? = null,
        val registeredAt: Long = 0,
        val lastError: String? = null,
        val inProgress: Boolean = false,
    ) {
        val isRegistered: Boolean get() = registeredToken != null && lastError == null
    }

    private val prefs = appContext.getSharedPreferences("registration", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(load())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 앱을 열 때 호출. 토큰이 바뀌었거나 지난번에 실패했으면 다시 등록한다 */
    suspend fun ensureRegistered(force: Boolean = false, scheduleRetry: Boolean = true) {
        val token = try {
            // firebase-messaging 26.0.1에서 deprecated지만 공개 API에 대체 메서드가 없다
            @Suppress("DEPRECATION")
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            // google-services.json 없이 빌드했거나 Play 서비스가 없는 경우
            update(_status.value.copy(lastError = "FCM 토큰 없음: ${e.message}", inProgress = false))
            if (scheduleRetry) RegisterRetryWorker.schedule(appContext)
            return
        }
        val current = _status.value
        if (force || current.registeredToken != token || current.lastError != null) register(token, scheduleRetry)
    }

    suspend fun register(token: String, scheduleRetry: Boolean = true) {
        update(_status.value.copy(inProgress = true))
        val error = withContext(Dispatchers.IO) { post(token) }
        update(
            if (error == null) Status(registeredToken = token, registeredAt = System.currentTimeMillis())
            else _status.value.copy(lastError = error, inProgress = false)
        )
        // 네트워크가 없을 때 실패하면 연결되는 대로 다시 시도한다 (docs/DESIGN.md 8.5)
        if (error != null && scheduleRetry) RegisterRetryWorker.schedule(appContext)
    }

    /** 성공하면 null, 실패하면 오류 문구 */
    private fun post(token: String): String? {
        if (BuildConfig.WORKER_URL.isBlank() || BuildConfig.REGISTER_SECRET.isBlank()) {
            return "local.properties에 claudewatch.workerUrl / registerSecret이 없음"
        }
        return try {
            val conn = URL("${BuildConfig.WORKER_URL.trimEnd('/')}/register").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.REGISTER_SECRET}")
            val body = JSONObject().put("token", token).put("device", Build.MODEL).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) null else "HTTP $code"
        } catch (e: Exception) {
            Log.w(TAG, "등록 실패", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun load() = Status(
        registeredToken = prefs.getString(KEY_TOKEN, null),
        registeredAt = prefs.getLong(KEY_AT, 0),
        lastError = prefs.getString(KEY_ERROR, null),
    )

    private fun update(status: Status) {
        _status.value = status
        prefs.edit()
            .putString(KEY_TOKEN, status.registeredToken)
            .putLong(KEY_AT, status.registeredAt)
            .putString(KEY_ERROR, status.lastError)
            .apply()
    }

    companion object {
        private const val TAG = "TokenRegistrar"
        private const val KEY_TOKEN = "registeredToken"
        private const val KEY_AT = "registeredAt"
        private const val KEY_ERROR = "lastError"

        @Volatile private var instance: TokenRegistrar? = null

        fun get(context: Context): TokenRegistrar =
            instance ?: synchronized(this) {
                instance ?: TokenRegistrar(context.applicationContext).also { instance = it }
            }
    }
}
