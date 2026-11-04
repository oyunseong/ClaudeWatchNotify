package com.yunseong.claudewatch

import com.yunseong.claudewatch.notify.ClaudeEvent
import com.yunseong.claudewatch.notify.NotificationPoster
import com.yunseong.claudewatch.notify.formatElapsed
import com.yunseong.claudewatch.notify.Strength
import com.yunseong.claudewatch.notify.VibrationPattern
import com.yunseong.claudewatch.settings.Settings
import com.yunseong.claudewatch.settings.VibrationPolicy
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
        assertEquals("1분", formatElapsed(60))
        assertEquals("4분", formatElapsed(240))
        assertEquals("1시간 1분", formatElapsed(3661))
    }

    private fun done(elapsed: Long?, project: String = "p") = ClaudeEvent(ClaudeEvent.Type.DONE, project, "s", elapsed, "", "", "")
    private val attention = ClaudeEvent(ClaudeEvent.Type.ATTENTION, "p", "s", 1, "", "", "")
    private val noon = 12 * 60

    @Test fun `진동 기준`() {
        assertTrue(VibrationPolicy.shouldVibrate(done(5), Settings(minSeconds = 0), noon))
        assertFalse(VibrationPolicy.shouldVibrate(done(5), Settings(minSeconds = 30), noon))
        assertTrue(VibrationPolicy.shouldVibrate(done(30), Settings(minSeconds = 30), noon))
        assertTrue("경과 시간을 모르면 울린다", VibrationPolicy.shouldVibrate(done(null), Settings(minSeconds = 300), noon))
        assertTrue("입력 대기는 기준과 무관", VibrationPolicy.shouldVibrate(attention, Settings(minSeconds = 300), noon))
    }

    @Test fun `이벤트별 끄기`() {
        assertFalse(VibrationPolicy.shouldVibrate(done(100), Settings(doneEnabled = false), noon))
        assertTrue(VibrationPolicy.shouldVibrate(attention, Settings(doneEnabled = false), noon))
        assertFalse(VibrationPolicy.shouldVibrate(attention, Settings(attentionEnabled = false), noon))
    }

    @Test fun `프로젝트 음소거`() {
        val s = Settings(mutedProjects = setOf("noisy"))
        assertFalse(VibrationPolicy.shouldVibrate(done(100, "noisy"), s, noon))
        assertTrue(VibrationPolicy.shouldVibrate(done(100, "quiet"), s, noon))
    }

    @Test fun `조용한 시간 - 자정을 넘기는 구간`() {
        val s = Settings(quietEnabled = true, quietStartMinute = 23 * 60, quietEndMinute = 7 * 60)
        assertTrue(VibrationPolicy.isQuiet(s, 23 * 60))
        assertTrue(VibrationPolicy.isQuiet(s, 2 * 60))
        assertTrue(VibrationPolicy.isQuiet(s, 6 * 60 + 59))
        assertFalse("끝 시각은 포함하지 않는다", VibrationPolicy.isQuiet(s, 7 * 60))
        assertFalse(VibrationPolicy.isQuiet(s, noon))
        assertFalse(VibrationPolicy.shouldVibrate(attention, s, 2 * 60))
        assertFalse("꺼져 있으면 적용 안 함", VibrationPolicy.isQuiet(s.copy(quietEnabled = false), 2 * 60))
    }

    @Test fun `조용한 시간 - 하루 안의 구간과 같은 시각`() {
        val s = Settings(quietEnabled = true, quietStartMinute = 13 * 60, quietEndMinute = 14 * 60)
        assertTrue(VibrationPolicy.isQuiet(s, 13 * 60 + 30))
        assertFalse(VibrationPolicy.isQuiet(s, noon))
        assertFalse("시작과 끝이 같으면 꺼진 것으로 본다", VibrationPolicy.isQuiet(s.copy(quietEndMinute = 13 * 60), 13 * 60))
    }

    @Test fun `기준 프리셋 순환`() {
        val seen = generateSequence(0L) { VibrationPolicy.nextThreshold(it) }.take(6).toList()
        assertEquals(listOf(0L, 5L, 10L, 30L, 60L, 0L), seen)
        assertEquals("직접 정한 5분에서 탭하면 처음으로", 0L, VibrationPolicy.nextThreshold(300))
        assertEquals("목록에 없는 값은 다음 큰 값으로", 30L, VibrationPolicy.nextThreshold(15))
    }

    @Test fun `반복 알림 간격과 세기 순환`() {
        assertEquals(listOf(1, 2, 5, 10, 1), generateSequence(1) { VibrationPolicy.nextRemindInterval(it) }.take(5).toList())
        assertEquals(Strength.LIGHT, Strength.STRONG.next())
        assertEquals(Strength.MEDIUM, Strength.LIGHT.next())
    }

    @Test fun `패턴 데이터가 올바르다`() {
        VibrationPattern.entries.forEach {
            assertEquals("${it.name} 길이", it.timings.size, it.amplitudes.size)
            assertTrue("${it.name} 진폭 범위", it.amplitudes.all { a -> a in 0..255 })
            assertTrue("${it.name} 너무 길면 안 됨", it.durationMs in 100..3000)
        }
        assertEquals(VibrationPattern.DOUBLE, VibrationPattern.from("DOUBLE"))
        assertNull("모르는 이름은 null (기본값으로 대체)", VibrationPattern.from("REMOVED"))
    }

    @Test fun `알림 본문`() {
        assertEquals("작업 완료 · 2분 4초", NotificationPoster.bodyText(ClaudeEvent(ClaudeEvent.Type.DONE, "p", "s", 124, "작업 완료", "", "")))
        assertEquals("작업 완료", NotificationPoster.bodyText(ClaudeEvent(ClaudeEvent.Type.DONE, "p", "s", null, "", "", "")))
        assertEquals("입력을 기다리는 중", NotificationPoster.bodyText(ClaudeEvent(ClaudeEvent.Type.ATTENTION, "p", "s", 10, "", "", "")))
    }
}
