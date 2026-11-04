package com.yunseong.claudewatch.settings

import android.content.Context
import com.yunseong.claudewatch.notify.ClaudeEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 진동 기준 시간. 이보다 짧게 끝난 "작업 완료"는 진동하지 않는다. (docs/DESIGN.md 8.7)
 * 맥은 항상 보내고, 진동 여부는 워치가 정한다.
 */
class VibrationSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _minSeconds = MutableStateFlow(prefs.getLong(KEY_MIN_SECONDS, DEFAULT_MIN_SECONDS))
    val minSeconds: StateFlow<Long> = _minSeconds.asStateFlow()

    /** 화면에서 탭할 때마다 다음 값으로 넘긴다 */
    fun cycle() {
        val next = PRESETS.firstOrNull { it > _minSeconds.value } ?: PRESETS.first()
        _minSeconds.value = next
        prefs.edit().putLong(KEY_MIN_SECONDS, next).apply()
    }

    /** 입력 대기는 항상, 작업 완료는 기준 이상이거나 경과 시간을 모를 때 진동한다 */
    fun shouldVibrate(event: ClaudeEvent): Boolean = when (event.type) {
        ClaudeEvent.Type.ATTENTION -> true
        ClaudeEvent.Type.DONE -> event.elapsedSeconds?.let { it >= _minSeconds.value } ?: true
    }

    companion object {
        val PRESETS = listOf(0L, 10L, 30L, 60L, 120L, 300L)
        private const val DEFAULT_MIN_SECONDS = 0L
        private const val KEY_MIN_SECONDS = "minSeconds"

        @Volatile private var instance: VibrationSettings? = null

        fun get(context: Context): VibrationSettings =
            instance ?: synchronized(this) {
                instance ?: VibrationSettings(context.applicationContext).also { instance = it }
            }
    }
}
