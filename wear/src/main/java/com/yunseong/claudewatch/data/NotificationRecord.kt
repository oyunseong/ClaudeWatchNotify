package com.yunseong.claudewatch.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "records", indices = [Index("receivedAt")])
data class NotificationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ClaudeEvent.Type.wire 값 (Stop / Notification) */
    val event: String,
    val project: String,
    val session: String,
    val elapsed: Long?,
    val message: String,
    val host: String,
    val sentAt: String,
    /** 워치에서 받은 시각 (epoch ms). 보관 정책의 기준 */
    val receivedAt: Long,
)
