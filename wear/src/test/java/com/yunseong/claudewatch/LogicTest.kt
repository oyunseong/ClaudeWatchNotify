package com.yunseong.claudewatch

import com.yunseong.claudewatch.notify.ClaudeEvent
import com.yunseong.claudewatch.notify.NotificationPoster
import com.yunseong.claudewatch.notify.formatElapsed
import com.yunseong.claudewatch.settings.VibrationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기기 없이 도는 순수 로직 테스트 (docs/OPERATIONS.md 5.1) */
class LogicTest {

    private fun data(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test fun `FCM data를 이벤트로 바꾼다`() {
        val e = ClaudeEvent.from(
            data("event" to "Stop", "project" to "keystore", "session" to "abcd1234", "elapsed" to "124", "message" to "작업 완료", "host" to "mac", "ts" to "t")
        )!!
        assertEquals(ClaudeEvent.Type.DONE, e.type)
        assertEquals("keystore", e.project)
        assertEquals(124L, e.elapsedSeconds)
    }

    @Test fun `모르는 event와 빈 값을 처리한다`() {
        assertNull(ClaudeEvent.from(data("event" to "Foo")))
        val e = ClaudeEvent.from(data("event" to "Notification", "elapsed" to ""))!!
        assertEquals(ClaudeEvent.Type.ATTENTION, e.type)
        assertEquals("Claude", e.project)
        assertEquals("unknown", e.session)
        assertNull(e.elapsedSeconds)
    }

    @Test fun `경과 시간 표기`() {
        assertEquals("0초", formatElapsed(0))
        assertEquals("9초", formatElapsed(9))
        assertEquals("2분 4초", formatElapsed(124))
        assertEquals("1분 0초", formatElapsed(60))
        assertEquals("1시간 1분", formatElapsed(3661))
    }

    @Test fun `진동 기준`() {
        fun done(elapsed: Long?) = ClaudeEvent(ClaudeEvent.Type.DONE, "p", "s", elapsed, "", "", "")
        val attention = ClaudeEvent(ClaudeEvent.Type.ATTENTION, "p", "s", 1, "", "", "")
        assertTrue(VibrationSettings.shouldVibrate(done(5), 0))
        assertFalse(VibrationSettings.shouldVibrate(done(5), 30))
        assertTrue(VibrationSettings.shouldVibrate(done(30), 30))
        assertTrue("경과 시간을 모르면 울린다", VibrationSettings.shouldVibrate(done(null), 300))
        assertTrue("입력 대기는 항상 울린다", VibrationSettings.shouldVibrate(attention, 300))
    }

    @Test fun `기준 프리셋 순환`() {
        val seen = generateSequence(0L) { VibrationSettings.nextPreset(it) }.take(7).toList()
        assertEquals(listOf(0L, 10L, 30L, 60L, 120L, 300L, 0L), seen)
        assertEquals("목록에 없는 값은 다음 큰 값으로", 30L, VibrationSettings.nextPreset(15))
    }

    @Test fun `알림 본문`() {
        assertEquals("작업 완료 · 2분 4초", NotificationPoster.bodyText(ClaudeEvent(ClaudeEvent.Type.DONE, "p", "s", 124, "작업 완료", "", "")))
        assertEquals("작업 완료", NotificationPoster.bodyText(ClaudeEvent(ClaudeEvent.Type.DONE, "p", "s", null, "", "", "")))
        assertEquals("입력을 기다리는 중", NotificationPoster.bodyText(ClaudeEvent(ClaudeEvent.Type.ATTENTION, "p", "s", 10, "", "", "")))
    }
}
