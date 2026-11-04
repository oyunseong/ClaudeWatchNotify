package com.yunseong.claudewatch.notify

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * 이벤트별 진동을 직접 울린다. (docs/DESIGN.md 8.3)
 *
 * usage를 ALARM으로 지정하는 이유:
 * - 방해 금지·무음 모드에서도 울린다 (사용자가 방해 금지를 항상 켜 둔다)
 * - 백그라운드 앱의 진동이 허용되는 usage다 (FCM 수신 시 앱은 백그라운드)
 * 대가로 취침 모드에서도 울린다.
 */
object Haptics {
    private val DONE_PATTERN = longArrayOf(0, 150, 100, 150)
    private val ATTENTION_PATTERN = longArrayOf(0, 600)

    fun play(context: Context, type: ClaudeEvent.Type) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return

        val pattern = when (type) {
            ClaudeEvent.Type.DONE -> DONE_PATTERN
            ClaudeEvent.Type.ATTENTION -> ATTENTION_PATTERN
        }
        val effect = VibrationEffect.createWaveform(pattern, -1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
