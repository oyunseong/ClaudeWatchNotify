package com.yunseong.claudewatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunseong.claudewatch.BuildConfig
import com.yunseong.claudewatch.data.NotificationRecord
import com.yunseong.claudewatch.data.RecordRepository
import com.yunseong.claudewatch.dev.WirelessDebugging
import com.yunseong.claudewatch.notify.Haptics
import com.yunseong.claudewatch.notify.Reminders
import com.yunseong.claudewatch.notify.Strength
import com.yunseong.claudewatch.notify.VibrationPattern
import com.yunseong.claudewatch.register.TokenRegistrar
import com.yunseong.claudewatch.settings.Settings
import com.yunseong.claudewatch.settings.VibrationPolicy
import com.yunseong.claudewatch.settings.VibrationSettings
import com.yunseong.claudewatch.tile.StatusTileService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 모든 화면이 함께 쓰는 ViewModel (Activity 범위) */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = RecordRepository(app)
    private val registrar = TokenRegistrar.get(app)
    private val vibrationSettings = VibrationSettings.get(app)
    private val wirelessDebugging = WirelessDebugging.get(app)

    val records: StateFlow<List<NotificationRecord>> =
        repository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val registration: StateFlow<TokenRegistrar.Status> = registrar.status
    val settings: StateFlow<Settings> = vibrationSettings.settings
    val hasAmplitudeControl: Boolean = Haptics.hasAmplitudeControl(app)

    /** debug 빌드 전용 (docs/DESIGN.md 8.8) */
    val wirelessDebuggingState: StateFlow<WirelessDebugging.State> = wirelessDebugging.state

    fun record(id: Long): Flow<NotificationRecord?> = repository.observe(id)

    /** 앱을 열 때: 보관 정책, 토큰 재등록, 반복 알림 정지(확인했다는 뜻), 타일 갱신 */
    fun onOpen() {
        viewModelScope.launch { repository.prune() }
        viewModelScope.launch { registrar.ensureRegistered() }
        Reminders.cancelAll(getApplication())
        StatusTileService.requestUpdate(getApplication())
    }

    fun reRegister() {
        viewModelScope.launch { registrar.ensureRegistered(force = true) }
    }

    // ---- 진동 설정 (docs/DESIGN.md 8.7, 8.9) ----

    fun cycleThreshold() = vibrationSettings.update { it.copy(minSeconds = VibrationPolicy.nextThreshold(it.minSeconds)) }

    fun setThresholdMinutes(minutes: Int) = vibrationSettings.update { it.copy(minSeconds = minutes * 60L) }

    fun cycleStrength() {
        vibrationSettings.update { it.copy(strength = it.strength.next()) }
        preview(settings.value.donePattern)
    }

    fun setPattern(done: Boolean, pattern: VibrationPattern) {
        vibrationSettings.update { if (done) it.copy(donePattern = pattern) else it.copy(attentionPattern = pattern) }
        preview(pattern)
    }

    fun preview(pattern: VibrationPattern, strength: Strength = settings.value.strength) =
        Haptics.play(getApplication(), pattern, strength)

    fun setDoneEnabled(on: Boolean) = vibrationSettings.update { it.copy(doneEnabled = on) }
    fun setAttentionEnabled(on: Boolean) = vibrationSettings.update { it.copy(attentionEnabled = on) }

    fun setQuietEnabled(on: Boolean) = vibrationSettings.update { it.copy(quietEnabled = on) }
    fun setQuietStart(minute: Int) = vibrationSettings.update { it.copy(quietStartMinute = minute) }
    fun setQuietEnd(minute: Int) = vibrationSettings.update { it.copy(quietEndMinute = minute) }

    fun setRemindEnabled(on: Boolean) {
        vibrationSettings.update { it.copy(remindEnabled = on) }
        if (!on) Reminders.cancelAll(getApplication())
    }

    fun cycleRemindInterval() =
        vibrationSettings.update { it.copy(remindIntervalMinutes = VibrationPolicy.nextRemindInterval(it.remindIntervalMinutes)) }

    fun setProjectMuted(project: String, muted: Boolean) = vibrationSettings.update {
        it.copy(mutedProjects = if (muted) it.mutedProjects + project else it.mutedProjects - project)
    }

    // ---- 기록 ----

    fun clearRecords() {
        viewModelScope.launch {
            repository.clear()
            StatusTileService.requestUpdate(getApplication())
        }
    }

    // ---- 개발 ----

    fun onVisible() {
        if (BuildConfig.DEBUG) wirelessDebugging.onVisible()
    }

    fun onHidden() {
        if (BuildConfig.DEBUG) wirelessDebugging.onHidden()
    }

    fun toggleWirelessDebugging() {
        if (BuildConfig.DEBUG) wirelessDebugging.toggle()
    }
}
