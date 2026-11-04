package com.yunseong.claudewatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.yunseong.claudewatch.R
import com.yunseong.claudewatch.data.NotificationRecord
import com.yunseong.claudewatch.notify.ClaudeEvent
import com.yunseong.claudewatch.notify.formatElapsed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 기록 상세: 전체 메시지, 걸린 시간, 받은 시각, 보낸 맥, 세션. 프로젝트 진동 끄기 (docs/DESIGN.md 8.11) */
@Composable
fun RecordDetailScreen(record: NotificationRecord?, muted: Boolean, onMutedChange: (Boolean) -> Unit) {
    ListScreen { spec ->
        if (record == null) {
            item { Text("지워진 기록이에요", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@ListScreen
        }
        val attention = record.event == ClaudeEvent.Type.ATTENTION.wire

        item { ListHeader { Text(record.project, textAlign = TextAlign.Center) } }
        item {
            Text(
                if (attention) "입력 대기" else "작업 완료",
                style = MaterialTheme.typography.labelMedium,
                color = if (attention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
        }
        // 메시지가 상태 이름과 같으면("작업 완료") 한 번만 보여 준다
        val message = record.message.takeIf { it.isNotBlank() && it != "작업 완료" }
        if (message != null) item {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (record.elapsed != null && !attention) Detail("걸린 시간", formatElapsed(record.elapsed))
                Detail("받은 시각", TIME.format(Instant.ofEpochMilli(record.receivedAt)) + " · " + relativeTime(record.receivedAt))
                if (record.host.isNotBlank()) Detail("보낸 맥", record.host)
                Detail("세션", record.session)
            }
        }
        item {
            SwitchButton(
                checked = !muted,
                onCheckedChange = { on -> onMutedChange(!on) },
                modifier = itemModifier(spec),
                transformation = surface(spec),
                icon = { ButtonIcon(if (muted) R.drawable.ic_volume_off else R.drawable.ic_vibration) },
                secondaryLabel = { Text(if (muted) "이 프로젝트는 진동 없음" else "끄면 기록만 남아요") },
                label = { Text("이 프로젝트 진동") },
            )
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.systemDefault())
