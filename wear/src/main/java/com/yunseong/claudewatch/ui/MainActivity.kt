package com.yunseong.claudewatch.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.yunseong.claudewatch.BuildConfig

/**
 * 화면 구성 (docs/DESIGN.md 8.6)
 * home → settings → pattern/{done|attention}, quiet/{start|end}
 *      → threshold (진동 기준 길게 누르기)
 *      → record/{id}
 * 오른쪽으로 밀면 뒤로 간다.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        setContent { App(viewModel, ::openDeveloperOptions) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onOpen()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onVisible()
    }

    override fun onStop() {
        viewModel.onHidden()
        super.onStop()
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // 개발자 옵션이 꺼져 있으면 화면이 없을 수 있다
        }
    }

    /** Android 13 이상은 알림 권한을 런타임에 받아야 한다 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun App(vm: HomeViewModel, openDeveloperOptions: () -> Unit) {
    val records by vm.records.collectAsStateWithLifecycle()
    val registration by vm.registration.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wirelessDebugging by vm.wirelessDebuggingState.collectAsStateWithLifecycle()
    val nav = rememberSwipeDismissableNavController()

    ClaudeWatchTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        records = records,
                        registration = registration,
                        settings = settings,
                        onReRegister = vm::reRegister,
                        onCycleThreshold = vm::cycleThreshold,
                        onOpenThresholdPicker = { nav.navigate("threshold") },
                        onOpenSettings = { nav.navigate("settings") },
                        onOpenRecord = { nav.navigate("record/$it") },
                        onClearRecords = vm::clearRecords,
                        wirelessDebugging = if (BuildConfig.DEBUG) wirelessDebugging else null,
                        onToggleWirelessDebugging = vm::toggleWirelessDebugging,
                        onOpenDeveloperOptions = openDeveloperOptions,
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        settings = settings,
                        hasAmplitudeControl = vm.hasAmplitudeControl,
                        onOpenThreshold = { nav.navigate("threshold") },
                        onCycleStrength = vm::cycleStrength,
                        onOpenPattern = { done -> nav.navigate(if (done) "pattern/done" else "pattern/attention") },
                        onDoneEnabled = vm::setDoneEnabled,
                        onAttentionEnabled = vm::setAttentionEnabled,
                        onQuietEnabled = vm::setQuietEnabled,
                        onOpenQuietTime = { start -> nav.navigate(if (start) "quiet/start" else "quiet/end") },
                        onRemindEnabled = vm::setRemindEnabled,
                        onCycleRemindInterval = vm::cycleRemindInterval,
                        onUnmute = { vm.setProjectMuted(it, false) },
                    )
                }
                composable("pattern/{which}") { entry ->
                    val done = entry.arguments?.getString("which") == "done"
                    PatternPickerScreen(
                        title = if (done) "완료 패턴" else "입력 대기 패턴",
                        selected = if (done) settings.donePattern else settings.attentionPattern,
                        onSelect = { vm.setPattern(done, it) },
                    )
                }
                composable("threshold") {
                    MinutePickerScreen(
                        initialMinutes = (settings.minSeconds / 60).toInt().coerceAtLeast(1),
                        onConfirm = {
                            vm.setThresholdMinutes(it)
                            nav.popBackStack()
                        },
                    )
                }
                composable("quiet/{which}") { entry ->
                    val start = entry.arguments?.getString("which") == "start"
                    QuietTimeScreen(
                        initialMinute = if (start) settings.quietStartMinute else settings.quietEndMinute,
                        onConfirm = {
                            if (start) vm.setQuietStart(it) else vm.setQuietEnd(it)
                            nav.popBackStack()
                        },
                    )
                }
                composable("record/{id}") { entry ->
                    val id = entry.arguments?.getString("id")?.toLongOrNull() ?: -1L
                    val record by vm.record(id).collectAsStateWithLifecycle(initialValue = null)
                    RecordDetailScreen(
                        record = record,
                        muted = record?.project in settings.mutedProjects,
                        onMutedChange = { muted -> record?.let { vm.setProjectMuted(it.project, muted) } },
                    )
                }
            }
        }
    }
}
