package com.yunseong.claudewatch.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.yunseong.claudewatch.R

/**
 * 진동은 채널로만 울린다. Vibrator를 직접 쓰면 방해 금지와 취침 모드를 무시한다.
 * 채널 진동 패턴은 처음 만들 때 고정된다. 패턴을 바꾸려면 ID를 새로 만들고 (예: claude_done_v2)
 * 이전 ID를 [OBSOLETE]에 넣는다. (docs/DESIGN.md 8.3)
 */
object Channels {
    const val DONE = "claude_done"
    const val ATTENTION = "claude_attention"

    private val DONE_PATTERN = longArrayOf(0, 150, 100, 150)
    private val ATTENTION_PATTERN = longArrayOf(0, 600)

    /** 패턴을 바꾸면서 버린 채널 ID */
    private val OBSOLETE = emptyList<String>()

    fun forType(type: ClaudeEvent.Type): String = when (type) {
        ClaudeEvent.Type.DONE -> DONE
        ClaudeEvent.Type.ATTENTION -> ATTENTION
    }

    fun createAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        OBSOLETE.forEach(nm::deleteNotificationChannel)
        nm.createNotificationChannels(
            listOf(
                channel(context, DONE, R.string.channel_done_name, R.string.channel_done_desc, DONE_PATTERN),
                channel(context, ATTENTION, R.string.channel_attention_name, R.string.channel_attention_desc, ATTENTION_PATTERN),
            )
        )
    }

    private fun channel(context: Context, id: String, name: Int, desc: Int, pattern: LongArray) =
        NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(desc)
            enableVibration(true)
            vibrationPattern = pattern
        }
}
