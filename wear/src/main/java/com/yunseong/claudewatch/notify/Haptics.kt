package com.yunseong.claudewatch.notify

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 진동 세기. 진폭(1..255)에 곱한다 */
enum class Strength(val label: String, val scale: Float) {
    LIGHT("약하게", 0.4f),
    MEDIUM("보통", 0.7f),
    STRONG("강하게", 1.0f);

    fun next(): Strength = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(name: String?): Strength? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 진동 패턴 프리셋. timings는 [쉼, 울림, 쉼, 울림 …] 순서의 ms, amplitudes는 같은 길이의 0..255.
 * enum 이름이 설정에 저장되므로 바꾸지 않는다.
 */
enum class VibrationPattern(val label: String, val timings: LongArray, val amplitudes: IntArray) {
    DOUBLE("짧게 두 번", longArrayOf(0, 150, 100, 150), intArrayOf(0, 255, 0, 255)),
    LONG("길게 한 번", longArrayOf(0, 600), intArrayOf(0, 255)),
    LONG_DOUBLE("길게 두 번", longArrayOf(0, 400, 200, 400), intArrayOf(0, 255, 0, 255)),
    TRIPLE("톡톡톡", longArrayOf(0, 80, 90, 80, 90, 80), intArrayOf(0, 255, 0, 255, 0, 255)),
    HEARTBEAT("심장 박동", longArrayOf(0, 90, 80, 160, 450, 90, 80, 160), intArrayOf(0, 180, 0, 255, 0, 180, 0, 255)),
    RAMP("점점 강하게", longArrayOf(0, 150, 150, 150, 150), intArrayOf(0, 60, 120, 190, 255)),
    WAVE("파도", longArrayOf(0, 120, 120, 120, 120, 120), intArrayOf(0, 80, 170, 255, 170, 80));

    /** 총 길이 (미리 듣기 간격 계산용) */
    val durationMs: Long get() = timings.sum()

    companion object {
        fun from(name: String?): VibrationPattern? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 진동을 직접 울린다. (docs/DESIGN.md 8.3, 8.9)
 *
 * usage를 ALARM으로 지정하는 이유:
 * - 방해 금지·무음 모드에서도 울린다 (사용자가 방해 금지를 항상 켜 둔다)
 * - 백그라운드 앱의 진동이 허용되는 usage다 (FCM 수신 시 앱은 백그라운드)
 * 대가로 취침 모드에서도 울린다. 그래서 앱에 "조용한 시간"이 있다.
 */
object Haptics {

    fun play(context: Context, pattern: VibrationPattern, strength: Strength) {
        val vibrator = vibrator(context) ?: return
        val effect = if (vibrator.hasAmplitudeControl()) {
            val amps = IntArray(pattern.amplitudes.size) { i ->
                val a = pattern.amplitudes[i]
                if (a == 0) 0 else (a * strength.scale).toInt().coerceIn(1, 255)
            }
            VibrationEffect.createWaveform(pattern.timings, amps, -1)
        } else {
            // 진폭 제어가 없는 모터: 세기는 무시하고 켜고 끄기만 한다
            VibrationEffect.createWaveform(pattern.timings, -1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    fun hasAmplitudeControl(context: Context): Boolean = vibrator(context)?.hasAmplitudeControl() == true

    private fun vibrator(context: Context): Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
}
