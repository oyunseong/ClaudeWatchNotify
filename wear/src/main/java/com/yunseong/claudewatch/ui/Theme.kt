package com.yunseong.claudewatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * 따뜻한 코랄(완료)과 앰버(입력 대기) 두 축의 다크 팔레트.
 * 워치는 OLED라 배경은 순수 검정, 표면은 살짝 따뜻한 회색으로 둔다.
 */
private val ClaudeColors = ColorScheme(
    primary = Color(0xFFF0A285),
    primaryDim = Color(0xFFD97757),
    primaryContainer = Color(0xFF5C2B1B),
    onPrimary = Color(0xFF3A1406),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB0),
    secondaryDim = Color(0xFFC9A194),
    secondaryContainer = Color(0xFF3B2C27),
    onSecondary = Color(0xFF442A22),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFF2C46B),
    tertiaryDim = Color(0xFFD9A94E),
    tertiaryContainer = Color(0xFF4A3510),
    onTertiary = Color(0xFF3F2E00),
    onTertiaryContainer = Color(0xFFFFDFA0),
    surfaceContainerLow = Color(0xFF1A1715),
    surfaceContainer = Color(0xFF23201E),
    surfaceContainerHigh = Color(0xFF2E2A27),
    onSurface = Color(0xFFF2EBE7),
    onSurfaceVariant = Color(0xFFC9BEB8),
    outline = Color(0xFF8F837D),
    outlineVariant = Color(0xFF4F4541),
    background = Color.Black,
    onBackground = Color(0xFFF2EBE7),
    error = Color(0xFFFFB4AB),
    errorDim = Color(0xFFE0928A),
    errorContainer = Color(0xFF5C1A14),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun ClaudeWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClaudeColors, content = content)
}
