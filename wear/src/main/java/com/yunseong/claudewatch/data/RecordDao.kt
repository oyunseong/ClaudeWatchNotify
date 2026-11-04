package com.yunseong.claudewatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Insert
    suspend fun insert(record: NotificationRecord): Long

    @Query("SELECT * FROM records ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<NotificationRecord>>

    @Query("DELETE FROM records WHERE receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query(
        """
        DELETE FROM records WHERE id NOT IN (
            SELECT id FROM records ORDER BY receivedAt DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun keepLatest(keep: Int): Int
}
