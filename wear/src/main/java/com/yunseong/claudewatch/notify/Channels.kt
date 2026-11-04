package com.yunseong.claudewatch.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.yunseong.claudewatch.R

/**
 * 알림 채널은 소리·진동 없이 표시만 한다. 진동은 [Haptics]가 직접 울린다. (docs/DESIGN.md 8.3)
 *
 * 갤럭시 워치는 알림 진동을 삼성 시스템 UI가 대신 울리면서 채널의 진동 패턴과 bypassDnd를 무시한다.
 * 방해 금지를 항상 켜 두는 사용 환경이라 채널 진동으로는 울릴 수 없었다.
 *
 * 채널 설정은 만든 뒤 앱이 바꿀 수 없다. 설정을 바꾸려면 ID를 새로 만들고 이전 ID를 [OBSOLETE]에 넣는다.
 */
object Channels {
    private const val DONE = "claude_done_silent"
    private const val ATTENTION = "claude_attention_silent"

    /** 예전 버전에서 만든 채널 (채널 진동 방식, 방해 금지 무시 채널) */
    private val OBSOLETE = listOf("claude_done", "claude_attention", "claude_done_dnd", "claude_attention_dnd")

    fun forType(type: ClaudeEvent.Type): String = when (type) {
        ClaudeEvent.Type.DONE -> DONE
        ClaudeEvent.Type.ATTENTION -> ATTENTION
    }

    fun createAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        OBSOLETE.forEach(nm::deleteNotificationChannel)
        nm.createNotificationChannels(
            listOf(
                channel(context, DONE, R.string.channel_done_name, R.string.channel_done_desc),
                channel(context, ATTENTION, R.string.channel_attention_name, R.string.channel_attention_desc),
            )
        )
    }

    private fun channel(context: Context, id: String, name: Int, desc: Int) =
        NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(desc)
            enableVibration(false)
            setSound(null, null)
        }
}
