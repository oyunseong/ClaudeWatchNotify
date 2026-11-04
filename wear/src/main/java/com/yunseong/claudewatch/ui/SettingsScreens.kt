package com.yunseong.claudewatch.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimePicker
import androidx.wear.compose.material3.TimePickerType
import androidx.compose.ui.res.painterResource
import com.yunseong.claudewatch.R
import com.yunseong.claudewatch.notify.VibrationPattern
import com.yunseong.claudewatch.notify.formatElapsed
import com.yunseong.claudewatch.settings.Settings
import com.yunseong.claudewatch.settings.VibrationPolicy
import java.time.LocalTime

/** 진동 설정 (docs/DESIGN.md 8.9 ~ 8.11) */
@Composable
fun SettingsScreen(
    settings: Settings,
    hasAmplitudeControl: Boolean,
    onOpenThreshold: () -> Unit,
    onCycleStrength: () -> Unit,
    onOpenPattern: (done: Boolean) -> Unit,
    onDoneEnabled: (Boolean) -> Unit,
    onAttentionEnabled: (Boolean) -> Unit,
    onQuietEnabled: (Boolean) -> Unit,
    onOpenQuietTime: (start: Boolean) -> Unit,
    onRemindEnabled: (Boolean) -> Unit,
    onCycleRemindInterval: () -> Unit,
    onUnmute: (String) -> Unit,
) {
    val tonal = ButtonDefaults.filledTonalButtonColors(iconColor = MaterialTheme.colorScheme.primary)
    ListScreen { spec ->
        item { ListHeader { Text("진동 설정") } }

        item {
            Button(
                onClick = onOpenThreshold,
                modifier = itemModifier(spec), transformation = surface(spec), colors = tonal,
                icon = { ButtonIcon(R.drawable.ic_vibration) },
                secondaryLabel = {
                    Text(if (settings.minSeconds == 0L) "모든 작업 · 탭해서 분 단위로" else "${formatElapsed(settings.minSeconds)} 이상 · 탭해서 변경", maxLines = 2)
                },
                label = { Text("진동 기준") },
            )
        }

        // 세기와 패턴
        if (hasAmplitudeControl) {
            item {
                Button(
                    onClick = onCycleStrength,
                    modifier = itemModifier(spec), transformation = surface(spec), colors = tonal,
                    icon = { ButtonIcon(R.drawable.ic_tune) },
                    secondaryLabel = { Text("${settings.strength.label} · 탭해서 바꾸고 느껴 보기", maxLines = 2) },
                    label = { Text("세기") },
                )
            }
        }
        item {
            Button(
                onClick = { onOpenPattern(true) },
                modifier = itemModifier(spec), transformation = surface(spec), colors = tonal,
                icon = { ButtonIcon(R.drawable.ic_check) },
                secondaryLabel = { Text(settings.donePattern.label) },
                label = { Text("완료 패턴") },
            )
        }
        item {
            Button(
                onClick = { onOpenPattern(false) },
                modifier = itemModifier(spec), transformation = surface(spec),
                colors = ButtonDefaults.filledTonalButtonColors(iconColor = MaterialTheme.colorScheme.tertiary),
                icon = { ButtonIcon(R.drawable.ic_hourglass) },
                secondaryLabel = { Text(settings.attentionPattern.label) },
                label = { Text("입력 대기 패턴") },
            )
        }
        item {
            SwitchButton(
                checked = settings.doneEnabled, onCheckedChange = onDoneEnabled,
                modifier = itemModifier(spec), transformation = surface(spec),
                label = { Text("완료 때 진동") },
            )
        }
        item {
            SwitchButton(
                checked = settings.attentionEnabled, onCheckedChange = onAttentionEnabled,
                modifier = itemModifier(spec), transformation = surface(spec),
                label = { Text("입력 대기 때 진동") },
            )
        }

        // 조용한 시간
        item { ListSubHeader(modifier = itemModifier(spec)) { Text("조용한 시간") } }
        item {
            SwitchButton(
                checked = settings.quietEnabled, onCheckedChange = onQuietEnabled,
                modifier = itemModifier(spec), transformation = surface(spec),
                icon = { ButtonIcon(R.drawable.ic_moon) },
                secondaryLabel = {
                    Text("${minuteOfDayLabel(settings.quietStartMinute)} ~ ${minuteOfDayLabel(settings.quietEndMinute)} 진동 없음")
                },
                label = { Text("조용한 시간") },
            )
        }
        if (settings.quietEnabled) {
            item {
                Button(
                    onClick = { onOpenQuietTime(true) },
                    modifier = itemModifier(spec), transformation = surface(spec),
                    colors = ButtonDefaults.childButtonColors(),
                    secondaryLabel = { Text(minuteOfDayLabel(settings.quietStartMinute)) },
                    label = { Text("시작") },
                )
            }
            item {
                Button(
                    onClick = { onOpenQuietTime(false) },
                    modifier = itemModifier(spec), transformation = surface(spec),
                    colors = ButtonDefaults.childButtonColors(),
                    secondaryLabel = { Text(minuteOfDayLabel(settings.quietEndMinute)) },
                    label = { Text("끝") },
                )
            }
        }

        // 반복 알림
        item { ListSubHeader(modifier = itemModifier(spec)) { Text("입력 대기 다시 알림") } }
        item {
            SwitchButton(
                checked = settings.remindEnabled, onCheckedChange = onRemindEnabled,
                modifier = itemModifier(spec), transformation = surface(spec),
                icon = { ButtonIcon(R.drawable.ic_repeat) },
                secondaryLabel = {
                    Text("응답이 없으면 ${settings.remindIntervalMinutes}분마다, 최대 ${VibrationPolicy.MAX_REMINDERS}번", maxLines = 2)
                },
                label = { Text("다시 알림") },
            )
        }
        if (settings.remindEnabled) {
            item {
                Button(
                    onClick = onCycleRemindInterval,
                    modifier = itemModifier(spec), transformation = surface(spec),
                    colors = ButtonDefaults.childButtonColors(),
                    secondaryLabel = { Text("${settings.remindIntervalMinutes}분 · 탭해서 변경") },
                    label = { Text("간격") },
                )
            }
        }

        // 진동 끈 프로젝트
        if (settings.mutedProjects.isNotEmpty()) {
            item { ListSubHeader(modifier = itemModifier(spec)) { Text("진동 끈 프로젝트") } }
            settings.mutedProjects.sorted().forEach { project ->
                item(key = "muted-$project") {
                    Button(
                        onClick = { onUnmute(project) },
                        modifier = itemModifier(spec), transformation = surface(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { ButtonIcon(R.drawable.ic_volume_off) },
                        secondaryLabel = { Text("탭해서 다시 켜기") },
                        label = { Text(project, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

/** 패턴 고르기. 고르는 즉시 그 패턴으로 한 번 울린다 */
@Composable
fun PatternPickerScreen(title: String, selected: VibrationPattern, onSelect: (VibrationPattern) -> Unit) {
    ListScreen { spec ->
        item { ListHeader { Text(title) } }
        VibrationPattern.entries.forEach { pattern ->
            item(key = pattern.name) {
                RadioButton(
                    selected = pattern == selected,
                    onSelect = { onSelect(pattern) },
                    modifier = itemModifier(spec),
                    transformation = surface(spec),
                    secondaryLabel = { Text("%.1f초".format(pattern.durationMs / 1000f)) },
                    label = { Text(pattern.label) },
                )
            }
        }
    }
}

/**
 * 진동 기준을 1분 단위로 정한다 (1~60분). 크라운을 돌리거나 −/+ 를 누른다.
 * 홈의 진동 기준 버튼을 길게 누르면 열린다 (docs/DESIGN.md 8.7)
 */
@Composable
fun MinutePickerScreen(initialMinutes: Int, onConfirm: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(initialMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES)) }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    fun change(delta: Int) {
        val next = (minutes + delta).coerceIn(MIN_MINUTES, MAX_MINUTES)
        if (next != minutes) {
            minutes = next
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    // 베젤·크라운 한 칸이 약 136px(SM-R930 실측). 칸마다 1분씩 움직이도록 한 칸에 한 번만 바꾼다
                    rotaryAccumulator += it.verticalScrollPixels
                    if (rotaryAccumulator >= ROTARY_STEP_PX) { change(+1); rotaryAccumulator = 0f }
                    if (rotaryAccumulator <= -ROTARY_STEP_PX) { change(-1); rotaryAccumulator = 0f }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("진동 기준", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    FilledTonalIconButton(onClick = { change(-1) }, enabled = minutes > MIN_MINUTES, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.ic_remove), contentDescription = "1분 줄이기")
                    }
                    Text(
                        "${minutes}분",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp).width(76.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    FilledTonalIconButton(onClick = { change(+1) }, enabled = minutes < MAX_MINUTES, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "1분 늘리기")
                    }
                }
                Text("크라운으로도 조절", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text("이상 걸린 작업만 진동", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onConfirm(minutes) },
                    modifier = Modifier.fillMaxWidth(0.6f),
                    label = { Text("저장", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                )
            }
        }
    }
}

/** 조용한 시간 시작·끝 */
@Composable
fun QuietTimeScreen(initialMinute: Int, onConfirm: (Int) -> Unit) {
    TimePicker(
        initialTime = LocalTime.of(initialMinute / 60, initialMinute % 60),
        onTimePicked = { onConfirm(it.hour * 60 + it.minute) },
        timePickerType = TimePickerType.HoursMinutes24H,
    )
}

private const val MIN_MINUTES = 1
private const val MAX_MINUTES = 60
/** 이만큼 돌면 1분. 한 칸(약 136px)보다 작게 둬서 한 칸에 정확히 한 번 바뀐다 */
private const val ROTARY_STEP_PX = 40f
