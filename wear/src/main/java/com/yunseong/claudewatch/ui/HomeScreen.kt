package com.yunseong.claudewatch.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.yunseong.claudewatch.data.NotificationRecord
import com.yunseong.claudewatch.notify.ClaudeEvent
import com.yunseong.claudewatch.notify.formatElapsed
import com.yunseong.claudewatch.register.TokenRegistrar

@Composable
fun HomeScreen(
    records: List<NotificationRecord>,
    registration: TokenRegistrar.Status,
    minSeconds: Long,
    onReRegister: () -> Unit,
    onCycleMinSeconds: () -> Unit,
) {
    MaterialTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                    item { ListHeader { Text("Claude") } }
                    item { RegistrationCard(registration, onReRegister) }
                    item { ThresholdCard(minSeconds, onCycleMinSeconds) }
                    if (records.isEmpty()) {
                        item {
                            Text(
                                "아직 받은 알림이 없습니다",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(records, key = { it.id }) { RecordCard(it) }
                }
            }
        }
    }
}

@Composable
private fun RegistrationCard(status: TokenRegistrar.Status, onClick: () -> Unit) {
    val (title, detail) = when {
        status.inProgress -> "등록 중…" to ""
        status.isRegistered -> "등록됨 ✓" to buildString {
            append("…").append(status.registeredToken.orEmpty().takeLast(6))
            if (status.registeredAt > 0) append(" · ").append(relative(status.registeredAt))
        }
        else -> "등록 안 됨" to (status.lastError ?: "탭해서 등록")
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (status.isRegistered) CardDefaults.cardColors() else CardDefaults.outlinedCardColors(),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ThresholdCard(minSeconds: Long, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (minSeconds == 0L) "진동: 모든 작업" else "진동: ${formatElapsed(minSeconds)} 이상",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "탭해서 기준 변경 · 입력 대기는 항상 진동",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordCard(record: NotificationRecord) {
    val attention = record.event == ClaudeEvent.Type.ATTENTION.wire
    val body = buildString {
        append(record.message.ifBlank { if (attention) "입력을 기다리는 중" else "작업 완료" })
        if (!attention && record.elapsed != null) append(" · ").append(formatElapsed(record.elapsed))
    }
    TitleCard(
        onClick = {},
        title = {
            Text(
                (if (attention) "⏳ " else "✓ ") + record.project,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        time = { Text(relative(record.receivedAt)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            body,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = if (attention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun relative(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
