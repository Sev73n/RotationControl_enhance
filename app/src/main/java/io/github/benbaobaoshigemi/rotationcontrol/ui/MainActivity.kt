package io.github.benbaobaoshigemi.rotationcontrol.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.benbaobaoshigemi.rotationcontrol.ConfigManager
import io.github.benbaobaoshigemi.rotationcontrol.GestureFilterScope
import io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixButton
import io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixCard
import io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixColors
import io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixNavItem
import io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixPreferenceItem
import io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 确保本地配置与 Settings.System 及 Root 权限初始化同步
        ConfigManager.syncAllSettingsToSystem(this)

        setContent {
            MiuixTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var openFilterName by rememberSaveable { mutableStateOf<String?>(null) }
    var blockedByScope by remember {
        mutableStateOf(
            GestureFilterScope.entries.associateWith { ConfigManager.getBlockedRules(context, it) }
        )
    }
    val openScope = openFilterName?.let { name ->
        GestureFilterScope.entries.firstOrNull { it.name == name }
    }

    if (openScope != null) {
        BackHandler { openFilterName = null }
        key(openScope) {
            AppFilterScreen(
                scope = openScope,
                initialSelection = blockedByScope[openScope].orEmpty(),
                onSelectionChanged = { blockedByScope = blockedByScope + (openScope to it) },
                onBack = { openFilterName = null }
            )
        }
    } else {
        MainScreen(
            blockedCounts = blockedByScope.mapValues { it.value.size },
            onOpenFilter = { openFilterName = it.name }
        )
    }
}

@Composable
fun MainScreen(
    blockedCounts: Map<GestureFilterScope, Int>,
    onOpenFilter: (GestureFilterScope) -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) MiuixColors.BackgroundDark else MiuixColors.BackgroundLight
    val textPrimary = if (isDark) MiuixColors.TextPrimaryDark else MiuixColors.TextPrimaryLight
    val textSecondary = if (isDark) MiuixColors.TextSecondaryDark else MiuixColors.TextSecondaryLight

    // 读取初始配置状态（本地持久化 SharedPreferences 优先）
    var twoFingerEnabled by remember {
        mutableStateOf(ConfigManager.getSetting(context, ConfigManager.KEY_TWO_FINGER, true))
    }
    var bottomSwipeEnabled by remember {
        mutableStateOf(ConfigManager.getSetting(context, ConfigManager.KEY_BOTTOM_SWIPE, true))
    }
    var tripleTapEnabled by remember {
        mutableStateOf(ConfigManager.getSetting(context, ConfigManager.KEY_TRIPLE_TAP, true))
    }
    var imeGuardEnabled by remember {
        mutableStateOf(ConfigManager.getSetting(context, ConfigManager.KEY_IME_GUARD, true))
    }
    var weChatMiniProgramGuard by remember {
        mutableStateOf(ConfigManager.getSetting(context, ConfigManager.KEY_WECHAT_MINIPROGRAM_GUARD, true))
    }

    var showRestartDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // 大标题
            Text(
                text = "多指旋转控制",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 卡片容器：3 个核心手势开关
            MiuixCard {
                MiuixPreferenceItem(
                    title = "双指双击定向旋转",
                    summary = "手指连线平行于目标显示顶部",
                    checked = twoFingerEnabled,
                    onCheckedChange = { newVal ->
                        twoFingerEnabled = newVal
                        ConfigManager.setSetting(context, ConfigManager.KEY_TWO_FINGER, newVal)
                    },
                    showDivider = true
                )

                MiuixPreferenceItem(
                    title = "输入法弹出时禁用双指旋转",
                    summary = "避免打字时误触发双指双击旋转",
                    checked = imeGuardEnabled,
                    onCheckedChange = { newVal ->
                        imeGuardEnabled = newVal
                        ConfigManager.setSetting(context, ConfigManager.KEY_IME_GUARD, newVal)
                    },
                    showDivider = true
                )

                MiuixPreferenceItem(
                    title = "底边三指横扫 90° 旋转",
                    summary = "底边左右横扫顺/逆时针旋转 90°",
                    checked = bottomSwipeEnabled,
                    onCheckedChange = { newVal ->
                        bottomSwipeEnabled = newVal
                        ConfigManager.setSetting(context, ConfigManager.KEY_BOTTOM_SWIPE, newVal)
                    },
                    showDivider = true
                )

                MiuixPreferenceItem(
                    title = "三指三击切换旋转锁定",
                    summary = "任意位置轻敲 3 次切换系统锁定状态",
                    checked = tripleTapEnabled,
                    onCheckedChange = { newVal ->
                        tripleTapEnabled = newVal
                        ConfigManager.setSetting(context, ConfigManager.KEY_TRIPLE_TAP, newVal)
                    },
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            MiuixCard {
                MiuixPreferenceItem(
                    title = "微信小程序内禁用手势",
                    summary = "小程序在前台时三种手势都不触发。若只想禁某一种，用下面的单独名单",
                    checked = weChatMiniProgramGuard,
                    onCheckedChange = { newVal ->
                        weChatMiniProgramGuard = newVal
                        ConfigManager.setSetting(context, ConfigManager.KEY_WECHAT_MINIPROGRAM_GUARD, newVal)
                    },
                    showDivider = true
                )
                for (scope in GestureFilterScope.entries) {
                    val count = blockedCounts[scope] ?: 0
                    MiuixNavItem(
                        title = scope.listTitle(),
                        summary = scope.listEffect(),
                        value = if (count > 0) "已选 $count 个" else "未设置",
                        onClick = { onOpenFilter(scope) },
                        showDivider = scope != GestureFilterScope.TRIPLE_TAP
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 作用域重启按钮
            MiuixButton(
                text = "重启作用域进程",
                onClick = {
                    showRestartDialog = true
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 说明小字
            Text(
                text = "开关即时生效，通常无需重启作用域",
                fontSize = 12.sp,
                color = textSecondary,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        if (showRestartDialog) {
            io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixDialog(
                title = "重启作用域进程",
                message = "当前手势开关已实时热生效，通常无需重启。\n\n若更新了模块或需重新挂钩系统核心，点击确认将通过 Android init 优雅重启系统框架（Zygote）以重新加载模块。",
                confirmText = "确认重启",
                dismissText = "取消",
                onConfirm = {
                    val success = ConfigManager.restartScopeProcess()
                    if (!success) {
                        Toast.makeText(context, "请授予 Root 权限", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = {
                    showRestartDialog = false
                }
            )
        }
    }
}
