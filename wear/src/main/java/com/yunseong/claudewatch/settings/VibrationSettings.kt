package com.yunseong.claudewatch.settings

import android.content.Context
import com.yunseong.claudewatch.notify.ClaudeEvent
import com.yunseong.claudewatch.notify.Strength
import com.yunseong.claudewatch.notify.VibrationPattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 사용자 설정 전체. 화면과 진동 판단이 함께 쓴다 (docs/DESIGN.md 8.7, 8.9) */
data class Settings(
    /** 이보다 짧게 끝난 "작업 완료"는 진동하지 않는다 */
    val minSeconds: Long = 0,
    val strength: Strength = Strength.STRONG,
    val donePattern: VibrationPattern = VibrationPattern.DOUBLE,
    val attentionPattern: VibrationPattern = VibrationPattern.LONG,
    val doneEnabled: Boolean = true,
    val attentionEnabled: Boolean = true,
    /** 조용한 시간: 하루 중 분(0..1439). 시작 > 끝이면 자정을 넘긴다 */
    val quietEnabled: Boolean = false,
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 7 * 60,
    /** 입력 대기 반복 알림 */
    val remindEnabled: Boolean = false,
    val remindIntervalMinutes: Int = 2,
    /** 진동을 끈 프로젝트 이름 */
    val mutedProjects: Set<String> = emptySet(),
)

/** 진동할지 정하는 순수 함수 모음. 단위 테스트 대상 */
object VibrationPolicy {
    /** 탭으로 순환하는 진동 기준. 길게 누르면 1분 단위로 직접 정한다 */
    val THRESHOLD_PRESETS = listOf(0L, 5L, 10L, 30L, 60L)
    val REMIND_INTERVALS = listOf(1, 2, 5, 10)
    const val MAX_REMINDERS = 3

    fun shouldVibrate(event: ClaudeEvent, s: Settings, nowMinuteOfDay: Int): Boolean {
        if (event.project in s.mutedProjects) return false
        if (isQuiet(s, nowMinuteOfDay)) return false
        return when (event.type) {
            ClaudeEvent.Type.ATTENTION -> s.attentionEnabled
            ClaudeEvent.Type.DONE -> s.doneEnabled && (event.elapsedSeconds?.let { it >= s.minSeconds } ?: true)
        }
    }

    fun isQuiet(s: Settings, now: Int): Boolean {
        if (!s.quietEnabled || s.quietStartMinute == s.quietEndMinute) return false
        return if (s.quietStartMinute < s.quietEndMinute) now in s.quietStartMinute until s.quietEndMinute
        else now >= s.quietStartMinute || now < s.quietEndMinute
    }

    /** 프리셋 순환. 직접 정한 값(프리셋에 없는 값)에서는 다음 큰 프리셋, 없으면 처음으로 */
    fun nextThreshold(current: Long): Long = THRESHOLD_PRESETS.firstOrNull { it > current } ?: THRESHOLD_PRESETS.first()

    fun nextRemindInterval(current: Int): Int = REMIND_INTERVALS.firstOrNull { it > current } ?: REMIND_INTERVALS.first()

    fun patternFor(type: ClaudeEvent.Type, s: Settings): VibrationPattern =
        if (type == ClaudeEvent.Type.DONE) s.donePattern else s.attentionPattern
}

/** SharedPreferences "settings" 에 저장. 키 minSeconds는 예전 버전과 같다 */
class VibrationSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        save(next)
    }

    fun shouldVibrate(event: ClaudeEvent, nowMinuteOfDay: Int): Boolean =
        VibrationPolicy.shouldVibrate(event, current, nowMinuteOfDay)

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            minSeconds = prefs.getLong("minSeconds", d.minSeconds),
            strength = Strength.from(prefs.getString("strength", null)) ?: d.strength,
            donePattern = VibrationPattern.from(prefs.getString("donePattern", null)) ?: d.donePattern,
            attentionPattern = VibrationPattern.from(prefs.getString("attentionPattern", null)) ?: d.attentionPattern,
            doneEnabled = prefs.getBoolean("doneEnabled", d.doneEnabled),
            attentionEnabled = prefs.getBoolean("attentionEnabled", d.attentionEnabled),
            quietEnabled = prefs.getBoolean("quietEnabled", d.quietEnabled),
            quietStartMinute = prefs.getInt("quietStartMinute", d.quietStartMinute),
            quietEndMinute = prefs.getInt("quietEndMinute", d.quietEndMinute),
            remindEnabled = prefs.getBoolean("remindEnabled", d.remindEnabled),
            remindIntervalMinutes = prefs.getInt("remindIntervalMinutes", d.remindIntervalMinutes),
            mutedProjects = prefs.getStringSet("mutedProjects", null)?.toSet() ?: d.mutedProjects,
        )
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putLong("minSeconds", s.minSeconds)
            .putString("strength", s.strength.name)
            .putString("donePattern", s.donePattern.name)
            .putString("attentionPattern", s.attentionPattern.name)
            .putBoolean("doneEnabled", s.doneEnabled)
            .putBoolean("attentionEnabled", s.attentionEnabled)
            .putBoolean("quietEnabled", s.quietEnabled)
            .putInt("quietStartMinute", s.quietStartMinute)
            .putInt("quietEndMinute", s.quietEndMinute)
            .putBoolean("remindEnabled", s.remindEnabled)
            .putInt("remindIntervalMinutes", s.remindIntervalMinutes)
            .putStringSet("mutedProjects", s.mutedProjects)
            .apply()
    }

    companion object {
        @Volatile private var instance: VibrationSettings? = null

        fun get(context: Context): VibrationSettings =
            instance ?: synchronized(this) {
                instance ?: VibrationSettings(context.applicationContext).also { instance = it }
            }
    }
}
