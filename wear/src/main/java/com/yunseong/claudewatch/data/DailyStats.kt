package com.yunseong.claudewatch.data

import android.content.Context
import com.yunseong.claudewatch.notify.ClaudeEvent
import java.time.LocalDate

/**
 * 오늘 받은 알림 수. 기록(최대 10건, 3일)과 따로 센다. 타일에 보여 준다 (docs/DESIGN.md 8.12)
 * 날짜가 바뀌면 0부터 다시 센다.
 */
class DailyStats(context: Context) {
    data class Today(val done: Int, val attention: Int, val totalSeconds: Long)

    private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    fun record(event: ClaudeEvent, today: LocalDate = LocalDate.now()) {
        val t = today(today)
        prefs.edit()
            .putString(KEY_DATE, today.toString())
            .putInt(KEY_DONE, t.done + if (event.type == ClaudeEvent.Type.DONE) 1 else 0)
            .putInt(KEY_ATTENTION, t.attention + if (event.type == ClaudeEvent.Type.ATTENTION) 1 else 0)
            .putLong(KEY_SECONDS, t.totalSeconds + if (event.type == ClaudeEvent.Type.DONE) (event.elapsedSeconds ?: 0) else 0)
            .apply()
    }

    fun today(today: LocalDate = LocalDate.now()): Today =
        if (prefs.getString(KEY_DATE, null) != today.toString()) Today(0, 0, 0)
        else Today(prefs.getInt(KEY_DONE, 0), prefs.getInt(KEY_ATTENTION, 0), prefs.getLong(KEY_SECONDS, 0))

    private companion object {
        const val KEY_DATE = "date"
        const val KEY_DONE = "done"
        const val KEY_ATTENTION = "attention"
        const val KEY_SECONDS = "seconds"
    }
}
