package com.yunseong.claudewatch.data

import android.content.Context
import com.yunseong.claudewatch.notify.ClaudeEvent
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/** 기록 저장과 보관 정책 (docs/DESIGN.md 8.4): 최대 10건, 3일 보관 */
class RecordRepository(private val dao: RecordDao) {
    constructor(context: Context) : this(AppDatabase.get(context).recordDao())

    fun observe(): Flow<List<NotificationRecord>> = dao.observeLatest(MAX_RECORDS)

    fun observe(id: Long): Flow<NotificationRecord?> = dao.observeById(id)

    suspend fun latest(): NotificationRecord? = dao.latest()

    suspend fun clear() = dao.deleteAll()

    suspend fun save(event: ClaudeEvent, now: Long = System.currentTimeMillis()) {
        dao.insert(
            NotificationRecord(
                event = event.type.wire,
                project = event.project,
                session = event.session,
                elapsed = event.elapsedSeconds,
                message = event.message,
                host = event.host,
                sentAt = event.sentAt,
                receivedAt = now,
            )
        )
        prune(now)
    }

    suspend fun prune(now: Long = System.currentTimeMillis()) {
        dao.deleteOlderThan(now - RETENTION_MS)
        dao.keepLatest(MAX_RECORDS)
    }

    companion object {
        const val MAX_RECORDS = 10
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(3)
    }
}
