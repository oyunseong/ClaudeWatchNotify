package com.yunseong.claudewatch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yunseong.claudewatch.data.AppDatabase
import com.yunseong.claudewatch.data.RecordRepository
import com.yunseong.claudewatch.notify.ClaudeEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** 워치에서 도는 보관 정책 테스트. 메모리 DB라 실제 기록은 건드리지 않는다 (docs/OPERATIONS.md 5.1) */
@RunWith(AndroidJUnit4::class)
class RecordRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RecordRepository

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = RecordRepository(db.recordDao())
    }

    @After fun tearDown() = db.close()

    private fun event(n: Int) = ClaudeEvent(ClaudeEvent.Type.DONE, "p$n", "s$n", n.toLong(), "m$n", "mac", "")

    @Test fun keepsLatestTen() = runBlocking {
        val base = System.currentTimeMillis()
        repeat(12) { repo.save(event(it), now = base + it) }
        val records = repo.observe().first()
        assertEquals(10, records.size)
        assertEquals("최신순", "p11", records.first().project)
        assertEquals("가장 오래된 2건이 지워짐", "p2", records.last().project)
    }

    @Test fun deletesOlderThanThreeDays() = runBlocking {
        val now = System.currentTimeMillis()
        repo.save(event(1), now = now - TimeUnit.DAYS.toMillis(4))
        repo.save(event(2), now = now - TimeUnit.DAYS.toMillis(2))
        repo.prune(now)
        assertEquals(listOf("p2"), repo.observe().first().map { it.project })
    }
}
