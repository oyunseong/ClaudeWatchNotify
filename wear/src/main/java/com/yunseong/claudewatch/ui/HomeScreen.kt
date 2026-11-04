package com.yunseong.claudewatch.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.yunseong.claudewatch.R
import com.yunseong.claudewatch.data.NotificationRecord
import com.yunseong.claudewatch.dev.WirelessDebugging
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
    /** null이면 숨긴다 (release 빌드) */
    wirelessDebugging: WirelessDebugging.State? = null,
    onToggleWirelessDebugging: () -> Unit = {},
    onOpenDeveloperOptions: () -> Unit = {},
) {
    ClaudeWatchTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            val spec = rememberTransformationSpec()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item { AppHeader() }
                    item { ConnectionButton(registration, onReRegister, spec) }
                    item { ThresholdButton(minSeconds, onCycleMinSeconds, spec) }

                    item {
                        ListSubHeader(modifier = Modifier.transformedHeight(this, spec)) {
                            Text(if (records.isEmpty()) "최근 알림" else "최근 알림 ${records.size}")
                        }
                    }
                    if (records.isEmpty()) {
                        item { EmptyState() }
                    } else {
                        items(records, key = { it.id }) { RecordCard(it, spec) }
                    }

                    if (wirelessDebugging != null) {
                        item {
                            ListSubHeader(modifier = Modifier.transformedHeight(this, spec)) { Text("개발") }
                        }
                        item { WirelessDebuggingSwitch(wirelessDebugging, onToggleWirelessDebugging, spec) }
                        item {
                            Button(
                                onClick = onOpenDeveloperOptions,
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                icon = { ButtonIcon(R.drawable.ic_code) },
                                label = { Text("개발자 옵션") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    ListHeader {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primaryDim,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Claude", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.ConnectionButton(
    status: TokenRegistrar.Status,
    onClick: () -> Unit,
    spec: TransformationSpec,
) {
    val scheme = MaterialTheme.colorScheme
    val ok = status.isRegistered || status.inProgress
    val (label, detail) = when {
        status.inProgress -> "연결 중…" to "워커에 등록하는 중"
        status.isRegistered -> "알림 수신 중" to buildString {
            append("…").append(status.registeredToken.orEmpty().takeLast(4))
            if (status.registeredAt > 0) append(" · ").append(relative(status.registeredAt))
        }
        else -> "연결 안 됨" to (status.lastError ?: "탭해서 다시 연결")
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = if (ok) {
            ButtonDefaults.filledTonalButtonColors(iconColor = scheme.primary)
        } else {
            ButtonDefaults.buttonColors(
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
                secondaryContentColor = scheme.onErrorContainer,
                iconColor = scheme.error,
            )
        },
        icon = { ButtonIcon(if (ok) R.drawable.ic_cloud_done else R.drawable.ic_cloud_off) },
        secondaryLabel = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        label = { Text(label) },
    )
}

@Composable
private fun TransformingLazyColumnItemScope.ThresholdButton(
    minSeconds: Long,
    onClick: () -> Unit,
    spec: TransformationSpec,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = ButtonDefaults.filledTonalButtonColors(iconColor = MaterialTheme.colorScheme.primary),
        icon = { ButtonIcon(R.drawable.ic_vibration) },
        secondaryLabel = {
            Text(if (minSeconds == 0L) "모든 작업" else "${formatElapsed(minSeconds)} 이상")
        },
        label = { Text("진동 기준") },
    )
}

@Composable
private fun TransformingLazyColumnItemScope.RecordCard(record: NotificationRecord, spec: TransformationSpec) {
    val scheme = MaterialTheme.colorScheme
    val attention = record.event == ClaudeEvent.Type.ATTENTION.wire
    val accent = if (attention) scheme.tertiary else scheme.primary
    val message = record.message.ifBlank { if (attention) "입력을 기다리는 중" else "작업 완료" }

    TitleCard(
        onClick = {},
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = if (attention) {
            CardDefaults.cardColors(
                containerColor = scheme.tertiaryContainer,
                contentColor = scheme.onTertiaryContainer,
                titleColor = scheme.onTertiaryContainer,
                timeColor = scheme.tertiary,
            )
        } else {
            CardDefaults.cardColors(timeColor = scheme.onSurfaceVariant)
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(if (attention) R.drawable.ic_hourglass else R.drawable.ic_check),
                    contentDescription = if (attention) "입력 대기" else "완료",
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(record.project, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        time = { Text(relative(record.receivedAt)) },
    ) {
        Column {
            Text(message, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (!attention && record.elapsed != null) {
                Text(
                    "${formatElapsed(record.elapsed)} 걸림",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_bell),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "아직 받은 알림이 없어요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "Claude 작업이 끝나면 여기에 쌓여요",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.WirelessDebuggingSwitch(
    state: WirelessDebugging.State,
    onToggle: () -> Unit,
    spec: TransformationSpec,
) {
    val detail = when {
        !state.hasPermission -> "권한 필요 (adb)"
        state.error != null -> state.error
        !state.adbEnabled -> "꺼짐"
        state.wifiConnected -> state.ipAddress ?: "Wi-Fi 연결됨"
        else -> "Wi-Fi 연결 중…"
    }
    SwitchButton(
        checked = state.adbEnabled,
        onCheckedChange = { onToggle() },
        enabled = state.hasPermission,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        icon = { ButtonIcon(R.drawable.ic_wifi) },
        secondaryLabel = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        label = { Text("무선 디버깅") },
    )
}

@Composable
private fun ButtonIcon(@DrawableRes id: Int) {
    Icon(
        painter = painterResource(id),
        contentDescription = null,
        modifier = Modifier.size(ButtonDefaults.IconSize),
    )
}

private fun relative(epochMs: Long): String {
    val now = System.currentTimeMillis()
    if (now - epochMs < DateUtils.MINUTE_IN_MILLIS) return "방금"
    return DateUtils.getRelativeTimeSpanString(epochMs, now, DateUtils.MINUTE_IN_MILLIS).toString()
}
