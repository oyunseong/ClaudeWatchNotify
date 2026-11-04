package com.yunseong.claudewatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunseong.claudewatch.data.NotificationRecord
import com.yunseong.claudewatch.data.RecordRepository
import com.yunseong.claudewatch.register.TokenRegistrar
import com.yunseong.claudewatch.settings.VibrationSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = RecordRepository(app)
    private val registrar = TokenRegistrar.get(app)
    private val vibrationSettings = VibrationSettings.get(app)

    val records: StateFlow<List<NotificationRecord>> =
        repository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val registration: StateFlow<TokenRegistrar.Status> = registrar.status

    /** 진동 기준 시간(초) (docs/DESIGN.md 8.7) */
    val minSeconds: StateFlow<Long> = vibrationSettings.minSeconds

    /** 앱을 열 때: 보관 정책 적용 + 필요하면 토큰 재등록 (docs/DESIGN.md 8.4, 8.5) */
    fun onOpen() {
        viewModelScope.launch { repository.prune() }
        viewModelScope.launch { registrar.ensureRegistered() }
    }

    fun cycleMinSeconds() = vibrationSettings.cycle()

    fun reRegister() {
        viewModelScope.launch { registrar.ensureRegistered(force = true) }
    }
}
