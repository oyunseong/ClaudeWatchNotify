package com.yunseong.claudewatch.notify

/** FCM data 메시지 한 건. 스키마는 docs/DESIGN.md 5.1 */
data class ClaudeEvent(
    val type: Type,
    val project: String,
    val session: String,
    /** 경과 초. 맥 쪽에서 시작 시각을 모르면 null */
    val elapsedSeconds: Long?,
    val message: String,
    val host: String,
    /** 맥에서 보낸 시각 (ISO8601 문자열 그대로) */
    val sentAt: String,
) {
    enum class Type(val wire: String) {
        DONE("Stop"),
        ATTENTION("Notification");

        companion object {
            fun from(wire: String?): Type? = entries.firstOrNull { it.wire == wire }
        }
    }

    companion object {
        /** 알 수 없는 event면 null. 모르는 메시지는 무시한다 */
        fun from(data: Map<String, String>): ClaudeEvent? {
            val type = Type.from(data["event"]) ?: return null
            return ClaudeEvent(
                type = type,
                project = data["project"].orEmpty().ifBlank { "Claude" },
                session = data["session"].orEmpty().ifBlank { "unknown" },
                elapsedSeconds = data["elapsed"]?.toLongOrNull(),
                message = data["message"].orEmpty(),
                host = data["host"].orEmpty(),
                sentAt = data["ts"].orEmpty(),
            )
        }
    }
}

/** 124 → "2분 4초", 240 → "4분", 0 → "0초" */
fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (h > 0) append("${h}시간 ")
        if (m > 0) append("${m}분 ")
        // "4분 0초" 대신 "4분"
        if (h == 0L && (s > 0 || m == 0L)) append("${s}초")
    }.trim()
}
