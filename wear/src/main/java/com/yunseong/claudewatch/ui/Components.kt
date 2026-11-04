package com.yunseong.claudewatch.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/** 스크롤 목록 화면의 공통 틀: ScreenScaffold + TransformingLazyColumn */
@Composable
fun ListScreen(content: TransformingLazyColumnScope.(TransformationSpec) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { contentPadding ->
        TransformingLazyColumn(
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content(spec) }
    }
}

/** 목록 항목이 화면 가장자리에서 자연스럽게 줄어들게 하는 modifier */
fun TransformingLazyColumnItemScope.itemModifier(spec: TransformationSpec): Modifier =
    Modifier.fillMaxWidth().transformedHeight(this, spec)

/** 카드·버튼 배경이 함께 변형되게 한다 */
fun TransformingLazyColumnItemScope.surface(spec: TransformationSpec) = SurfaceTransformation(spec)

@Composable
fun ButtonIcon(@DrawableRes id: Int) {
    Icon(
        painter = painterResource(id),
        contentDescription = null,
        modifier = Modifier.size(ButtonDefaults.IconSize),
    )
}

fun relativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    if (now - epochMs < DateUtils.MINUTE_IN_MILLIS) return "방금"
    return DateUtils.getRelativeTimeSpanString(epochMs, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** 하루 중 분 → "23:00" */
fun minuteOfDayLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
