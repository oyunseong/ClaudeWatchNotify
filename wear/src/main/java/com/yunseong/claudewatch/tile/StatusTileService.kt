package com.yunseong.claudewatch.tile

import android.content.Context
import android.text.format.DateUtils
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.yunseong.claudewatch.data.DailyStats
import com.yunseong.claudewatch.data.NotificationRecord
import com.yunseong.claudewatch.data.RecordRepository
import com.yunseong.claudewatch.notify.ClaudeEvent
import com.yunseong.claudewatch.notify.formatElapsed
import com.yunseong.claudewatch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 워치 타일: 마지막 알림과 오늘 받은 수. 탭하면 앱을 연다. (docs/DESIGN.md 8.12)
 * FCM 메시지를 받을 때마다 [requestUpdate]로 갱신하고, 상대 시간 표기 때문에 5분마다 새로 그린다.
 */
class StatusTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                runCatching {
                    val latest = RecordRepository(applicationContext).latest()
                    val today = DailyStats(applicationContext).today()
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setFreshnessIntervalMillis(5 * 60 * 1000L)
                        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(latest, today)))
                        .build()
                }.onSuccess(completer::set).onFailure(completer::setException)
            }
            "StatusTile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
            "StatusTileResources"
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun layout(latest: NotificationRecord?, today: DailyStats.Today): LayoutElementBuilders.LayoutElement {
        val attention = latest?.event == ClaudeEvent.Type.ATTENTION.wire
        val accent = if (attention) AMBER else CORAL

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("✦ Claude", 14f, CORAL_DIM, bold = true))
            .addContent(spacer(10f))

        if (latest == null) {
            column.addContent(text("아직 알림이 없어요", 15f, MUTED))
        } else {
            column
                .addContent(text(if (attention) "입력 대기" else "작업 완료", 13f, accent, bold = true))
                .addContent(spacer(2f))
                .addContent(text(latest.project, 20f, ON_SURFACE, bold = true, maxLines = 1))
                .addContent(spacer(2f))
                .addContent(text(relative(latest.receivedAt), 13f, MUTED))
        }

        column.addContent(spacer(12f))
            .addContent(
                text(
                    buildString {
                        append("오늘 완료 ${today.done}")
                        if (today.attention > 0) append(" · 대기 ${today.attention}")
                    },
                    13f, ON_SURFACE,
                )
            )
        if (today.totalSeconds > 0) {
            column.addContent(text("작업 ${formatElapsed(today.totalSeconds)}", 12f, MUTED))
        }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(MainActivity::class.java.name)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(column.build())
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 2) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(maxLines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            )
            .build()

    private fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private fun relative(epochMs: Long): String {
        val now = System.currentTimeMillis()
        if (now - epochMs < DateUtils.MINUTE_IN_MILLIS) return "방금"
        return DateUtils.getRelativeTimeSpanString(epochMs, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        // ui/Theme.kt 와 같은 팔레트
        private const val CORAL = 0xFFF0A285.toInt()
        private const val CORAL_DIM = 0xFFD97757.toInt()
        private const val AMBER = 0xFFF2C46B.toInt()
        private const val ON_SURFACE = 0xFFF2EBE7.toInt()
        private const val MUTED = 0xFFC9BEB8.toInt()

        fun requestUpdate(context: Context) {
            runCatching { getUpdater(context).requestUpdate(StatusTileService::class.java) }
        }
    }
}
