package io.github.benbaobaoshigemi.rotationcontrol.ui.miuix

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MIUIX 风格原生质感胶囊开关 (MiuixSwitch)：
 * - 49dp x 28dp 胶囊轨道
 * - 22dp 纯白悬浮滑块
 * - 阻尼弹簧动画与按压微缩回弹
 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val view = LocalView.current
    val isDark = isSystemInDarkTheme()

    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            if (isDark) MiuixColors.PrimaryDark else MiuixColors.PrimaryLight
        } else {
            if (isDark) MiuixColors.TrackUncheckedDark else MiuixColors.TrackUncheckedLight
        },
        animationSpec = spring(dampingRatio = 0.95f, stiffness = 400f),
        label = "switchTrackColor"
    )

    // 滑块位移动画 (0dp -> 21dp)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 500f),
        label = "switchThumbOffset"
    )

    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .clickable(enabled = enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(24.dp)
                .clip(CircleShape)
                .background(MiuixColors.Thumb)
        )
    }
}

/**
 * MIUIX 卡片容器 (MiuixCard)：
 * - 超椭圆（Squircle）大圆角 18dp
 * - 原生分组留白与背景沉浸
 */
@Composable
fun MiuixCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) MiuixColors.SurfaceDark else MiuixColors.SurfaceLight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardBackground)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column {
            content()
        }
    }
}

/**
 * MIUIX 设置选项行 (MiuixPreferenceItem)
 */
@Composable
fun MiuixPreferenceItem(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true
) {
    val isDark = isSystemInDarkTheme()
    val textPrimary = if (isDark) MiuixColors.TextPrimaryDark else MiuixColors.TextPrimaryLight
    val textSecondary = if (isDark) MiuixColors.TextSecondaryDark else MiuixColors.TextSecondaryLight
    val dividerColor = if (isDark) MiuixColors.DividerDark else MiuixColors.DividerLight

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = textPrimary
                )
                if (!summary.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = summary,
                        fontSize = 12.5.sp,
                        color = textSecondary,
                        lineHeight = 17.sp
                    )
                }
            }

            MiuixSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = dividerColor,
                thickness = 0.6.dp
            )
        }
    }
}

/**
 * MIUIX 设置导航行 (MiuixNavItem)：右侧箭头，点击进入二级页面
 */
@Composable
fun MiuixNavItem(
    title: String,
    summary: String? = null,
    value: String? = null,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    val isDark = isSystemInDarkTheme()
    val textPrimary = if (isDark) MiuixColors.TextPrimaryDark else MiuixColors.TextPrimaryLight
    val textSecondary = if (isDark) MiuixColors.TextSecondaryDark else MiuixColors.TextSecondaryLight
    val dividerColor = if (isDark) MiuixColors.DividerDark else MiuixColors.DividerLight

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = textPrimary
                )
                if (!summary.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = summary,
                        fontSize = 12.5.sp,
                        color = textSecondary,
                        lineHeight = 17.sp
                    )
                }
            }
            if (!value.isNullOrEmpty()) {
                Text(
                    text = value,
                    fontSize = 14.sp,
                    color = textSecondary,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
            Text(
                text = "›",
                fontSize = 22.sp,
                color = textSecondary
            )
        }

        if (showDivider) {
            HorizontalDivider(
                color = dividerColor,
                thickness = 0.6.dp
            )
        }
    }
}

/**
 * MIUIX 原生风格操作按钮 (MiuixButton)：
 * - 22dp 圆角药丸形态
 * - 按压弹性微缩 (0.97x) 与触觉震动反馈
 */
@Composable
fun MiuixButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false
) {
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1.0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "btnScale"
    )

    val isDark = isSystemInDarkTheme()
    val btnColor = if (isDestructive) {
        Color(0xFFFF3B30)
    } else {
        if (isDark) MiuixColors.PrimaryDark else MiuixColors.PrimaryLight
    }

    Box(
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(btnColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

/**
 * MIUIX 风格确认对话框 (MiuixDialog)
 */
@Composable
fun MiuixDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) MiuixColors.SurfaceDark else MiuixColors.SurfaceLight
    val textPrimary = if (isDark) MiuixColors.TextPrimaryDark else MiuixColors.TextPrimaryLight
    val textSecondary = if (isDark) MiuixColors.TextSecondaryDark else MiuixColors.TextSecondaryLight

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(bgColor)
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = textSecondary,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dismissText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = textPrimary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (isDark) MiuixColors.PrimaryDark else MiuixColors.PrimaryLight)
                            .clickable {
                                onDismiss()
                                onConfirm()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = confirmText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

