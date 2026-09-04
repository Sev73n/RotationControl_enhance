package io.github.benbaobaoshigemi.rotationcontrol.ui.miuix

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// HyperOS / MIUIX 官方配色常量
object MiuixColors {
    // 品牌主色（MIUI / HyperOS 经典蔚蓝）
    val PrimaryLight = Color(0xFF007AFF)
    val PrimaryDark = Color(0xFF0A84FF)

    // 背景色（浅灰 / 深灰级联）
    val BackgroundLight = Color(0xFFF2F2F7)
    val BackgroundDark = Color(0xFF121212)

    // 卡片表面色
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceDark = Color(0xFF1E1E20)

    // 开关未激活轨道颜色
    val TrackUncheckedLight = Color(0xFFE5E5EA)
    val TrackUncheckedDark = Color(0xFF39393D)

    // 开关手柄（Thumb）
    val Thumb = Color(0xFFFFFFFF)

    // 文字主色与副色
    val TextPrimaryLight = Color(0xFF1A1A1A)
    val TextPrimaryDark = Color(0xFFEEEEEE)
    val TextSecondaryLight = Color(0xFF8E8E93)
    val TextSecondaryDark = Color(0xFF8E8E93)

    // 分割线
    val DividerLight = Color(0xFFEFEFF4)
    val DividerDark = Color(0xFF2C2C2E)
}

@Composable
fun MiuixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = MiuixColors.PrimaryDark,
            background = MiuixColors.BackgroundDark,
            surface = MiuixColors.SurfaceDark,
            onBackground = MiuixColors.TextPrimaryDark,
            onSurface = MiuixColors.TextPrimaryDark,
        )
    } else {
        lightColorScheme(
            primary = MiuixColors.PrimaryLight,
            background = MiuixColors.BackgroundLight,
            surface = MiuixColors.SurfaceLight,
            onBackground = MiuixColors.TextPrimaryLight,
            onSurface = MiuixColors.TextPrimaryLight,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
