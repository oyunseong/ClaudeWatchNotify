package com.yunseong.claudewatch.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yunseong.claudewatch.R
import com.yunseong.claudewatch.ui.MainActivity

/** 세션마다 알림 하나 (tag = session), 그룹 claude 로 묶는다. (docs/DESIGN.md 8.3) */
object NotificationPoster {
    private const val GROUP = "claude"
    private const val EVENT_ID = 1
    private const val SUMMARY_ID = 0

    fun post(context: Context, event: ClaudeEvent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = NotificationManagerCompat.from(context)
        val channel = Channels.forType(event.type)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.project)
            .setContentText(bodyText(event))
            .setCategory(
                if (event.type == ClaudeEvent.Type.ATTENTION) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_STATUS
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .build()

        // 요약 알림은 소리나 진동 없이 묶음만 만든다
        val summary = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()

        try {
            nm.notify(event.session, EVENT_ID, notification)
            nm.notify(SUMMARY_ID, summary)
        } catch (_: SecurityException) {
            // 권한을 확인한 뒤에 철회된 경우
        }
    }

    fun bodyText(event: ClaudeEvent): String {
        val base = event.message.ifBlank {
            if (event.type == ClaudeEvent.Type.DONE) "작업 완료" else "입력을 기다리는 중"
        }
        val elapsed = event.elapsedSeconds
        return if (event.type == ClaudeEvent.Type.DONE && elapsed != null) "$base · ${formatElapsed(elapsed)}" else base
    }
}
