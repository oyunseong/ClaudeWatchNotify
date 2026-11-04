package com.yunseong.claudewatch.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yunseong.claudewatch.settings.VibrationPolicy
import com.yunseong.claudewatch.settings.VibrationSettings
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 입력 대기 반복 알림. (docs/DESIGN.md 8.10)
 *
 * 입력 대기 알림이 오면 설정한 간격 뒤에 다시 진동한다(최대 3번).
 * 이런 때 취소한다:
 * - 같은 세션의 "작업 완료"가 도착 (맥에서 응답해서 Claude가 다시 진행했다는 뜻)
 * - 알림을 밀어서 지움 ([DismissReceiver])
 * - 앱을 엶 (확인했다는 뜻)
 *
 * WorkManager 지연 작업이라 Doze 상태에서는 몇 분 늦을 수 있다.
 */
object Reminders {
    private const val TAG = "remind"
    private fun name(session: String) = "remind-$session"

    fun schedule(context: Context, event: ClaudeEvent, attempt: Int = 1) {
        val minutes = VibrationSettings.get(context).current.remindIntervalMinutes.toLong()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .addTag(TAG)
            .setInputData(
                workDataOf(
                    "session" to event.session,
                    "project" to event.project,
                    "message" to event.message,
                    "host" to event.host,
                    "attempt" to attempt,
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(event.session), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, session: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(session))
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val settings = VibrationSettings.get(applicationContext)
            if (!settings.current.remindEnabled) return Result.success()

            val attempt = inputData.getInt("attempt", 1)
            val event = ClaudeEvent(
                type = ClaudeEvent.Type.ATTENTION,
                project = inputData.getString("project").orEmpty(),
                session = inputData.getString("session").orEmpty(),
                elapsedSeconds = null,
                message = inputData.getString("message").orEmpty(),
                host = inputData.getString("host").orEmpty(),
                sentAt = "",
            )
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            if (settings.shouldVibrate(event, now)) {
                Haptics.play(applicationContext, settings.current.attentionPattern, settings.current.strength)
            }
            NotificationPoster.post(applicationContext, event, reminder = attempt to VibrationPolicy.MAX_REMINDERS)
            if (attempt < VibrationPolicy.MAX_REMINDERS) schedule(applicationContext, event, attempt + 1)
            return Result.success()
        }
    }

    /** 알림을 밀어서 지우면 그 세션의 반복 알림을 멈춘다 */
    class DismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(EXTRA_SESSION)?.let { cancel(context, it) }
        }
    }

    const val EXTRA_SESSION = "session"
}
