package com.yunseong.claudewatch.notify

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.yunseong.claudewatch.data.DailyStats
import com.yunseong.claudewatch.data.RecordRepository
import com.yunseong.claudewatch.register.TokenRegistrar
import com.yunseong.claudewatch.settings.VibrationPolicy
import com.yunseong.claudewatch.settings.VibrationSettings
import com.yunseong.claudewatch.tile.StatusTileService
import kotlinx.coroutines.runBlocking
import java.time.LocalTime

/**
 * 두 콜백 모두 FCM이 만든 백그라운드 스레드에서 실행되므로 blocking 호출을 써도 된다.
 * data 메시지만 받는다. notification 필드가 오면 시스템이 대신 처리해 여기로 오지 않는다.
 */
class ClaudeMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val event = ClaudeEvent.from(message.data)
        if (event == null) {
            Log.w(TAG, "알 수 없는 메시지 무시: ${message.data.keys}")
            return
        }
        val ctx = applicationContext
        val settings = VibrationSettings.get(ctx)

        runBlocking { RecordRepository(ctx).save(event) }
        DailyStats(ctx).record(event)
        NotificationPoster.post(ctx, event)

        // 같은 세션이 다시 진행됐으면 입력 대기 반복 알림은 필요 없다 (docs/DESIGN.md 8.10)
        if (event.type == ClaudeEvent.Type.DONE) Reminders.cancel(ctx, event.session)

        // 채널은 조용하다. 진동은 여기서 직접 울린다 (docs/DESIGN.md 8.3, 8.9)
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        if (settings.shouldVibrate(event, now)) {
            Haptics.play(ctx, VibrationPolicy.patternFor(event.type, settings.current), settings.current.strength)
        }

        if (event.type == ClaudeEvent.Type.ATTENTION && settings.current.remindEnabled) {
            Reminders.schedule(ctx, event)
        }

        StatusTileService.requestUpdate(ctx)
    }

    // firebase-messaging 26.0.1에서 deprecated지만 공개 API에 대체 메서드가 없다
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        runBlocking { TokenRegistrar.get(applicationContext).register(token) }
    }

    private companion object {
        const val TAG = "ClaudeMessaging"
    }
}
