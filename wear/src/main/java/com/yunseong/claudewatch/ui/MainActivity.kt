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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunseong.claudewatch.BuildConfig

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        setContent {
            val records = viewModel.records.collectAsStateWithLifecycle()
            val registration = viewModel.registration.collectAsStateWithLifecycle()
            val minSeconds = viewModel.minSeconds.collectAsStateWithLifecycle()
            val wirelessDebugging = viewModel.wirelessDebuggingState.collectAsStateWithLifecycle()
            HomeScreen(
                records = records.value,
                registration = registration.value,
                minSeconds = minSeconds.value,
                onReRegister = viewModel::reRegister,
                onCycleMinSeconds = viewModel::cycleMinSeconds,
                wirelessDebugging = if (BuildConfig.DEBUG) wirelessDebugging.value else null,
                onToggleWirelessDebugging = viewModel::toggleWirelessDebugging,
                onOpenDeveloperOptions = ::openDeveloperOptions,
            )
        }
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
