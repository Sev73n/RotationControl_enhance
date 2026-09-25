package io.github.benbaobaoshigemi.rotationcontrol.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.benbaobaoshigemi.rotationcontrol.ConfigManager
import io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixColors
import io.github.benbaobaoshigemi.rotationcontrol.ui.miuix.MiuixDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

private data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isInstalled: Boolean = true
)

private const val SAVE_DEBOUNCE_MS = 400L

@Composable
fun AppFilterScreen(
    initialSelection: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) MiuixColors.BackgroundDark else MiuixColors.BackgroundLight
    val surface = if (isDark) MiuixColors.SurfaceDark else MiuixColors.SurfaceLight
    val textPrimary = if (isDark) MiuixColors.TextPrimaryDark else MiuixColors.TextPrimaryLight
    val textSecondary = if (isDark) MiuixColors.TextSecondaryDark else MiuixColors.TextSecondaryLight
    val primary = if (isDark) MiuixColors.PrimaryDark else MiuixColors.PrimaryLight

    var selected by remember { mutableStateOf(initialSelection) }
    var savedSelection by remember { mutableStateOf(initialSelection) }
    // 排序只依据进入页面时的勾选状态，避免勾选时条目跳动
    val pinnedAtOpen = remember { initialSelection }

    var installedApps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var onlySelected by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val iconCache = remember { HashMap<String, ImageBitmap?>() }

    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }

    LaunchedEffect(selected) {
        onSelectionChanged(selected)
        if (selected != savedSelection) {
            delay(SAVE_DEBOUNCE_MS)
            ConfigManager.setBlockedApps(context, selected)
            savedSelection = selected
        }
    }

    val latestSelected by rememberUpdatedState(selected)
    val latestSaved by rememberUpdatedState(savedSelection)
    DisposableEffect(Unit) {
        onDispose {
            if (latestSelected != latestSaved) {
                ConfigManager.setBlockedApps(context, latestSelected)
            }
        }
    }

    val collator = remember { Collator.getInstance(Locale.CHINA) }
    val allEntries = remember(installedApps, selected) {
        val installed = installedApps ?: emptyList()
        val installedNames = installed.mapTo(HashSet()) { it.packageName }
        val missing = selected.filter { it !in installedNames }.map {
            AppEntry(packageName = it, label = it, isSystem = false, isInstalled = false)
        }
        installed + missing
    }

    val trimmedQuery = query.trim()
    val visibleEntries = remember(allEntries, trimmedQuery, showSystem, onlySelected, selected) {
        allEntries
            .asSequence()
            .filter { showSystem || !it.isSystem || it.packageName in selected }
            .filter { !onlySelected || it.packageName in selected }
            .filter {
                trimmedQuery.isEmpty() ||
                        it.label.contains(trimmedQuery, ignoreCase = true) ||
                        it.packageName.contains(trimmedQuery, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.packageName in pinnedAtOpen }
                    .thenComparator { a, b -> collator.compare(a.label, b.label) }
            )
            .toList()
    }

    val canonicalRule = ConfigManager.canonicalizeFilterRule(trimmedQuery)
    val canAddManually = canonicalRule != null &&
            allEntries.none { it.packageName == canonicalRule }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "‹", fontSize = 30.sp, color = textPrimary)
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = "应用过滤名单",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                    Text(
                        text = "已选 ${selected.size} 个 · 命中包名、Activity 或进程时不触发",
                        fontSize = 12.sp,
                        color = textSecondary
                    )
                }
            }

            // 搜索框
            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(surface)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(text = "搜索应用名、包名、Activity 或进程", fontSize = 15.sp, color = textSecondary)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = textPrimary),
                    cursorBrush = SolidColor(primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 筛选与批量操作
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(text = "系统应用", checked = showSystem) { showSystem = it }
                FilterChip(text = "仅已选", checked = onlySelected) { onlySelected = it }
                Spacer(modifier = Modifier.weight(1f))
                ActionText(text = "全选", color = primary, enabled = visibleEntries.isNotEmpty()) {
                    selected = selected + visibleEntries.map { it.packageName }
                }
                ActionText(text = "清空", color = Color(0xFFFF3B30), enabled = selected.isNotEmpty()) {
                    showClearDialog = true
                }
            }

            val apps = installedApps
            if (apps == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = primary, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "正在载入应用列表…", fontSize = 13.sp, color = textSecondary)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)
                ) {
                    if (canAddManually && canonicalRule != null) {
                        item(key = "__manual_add__") {
                            ManualAddRow(packageName = canonicalRule, surface = surface, primary = primary) {
                                selected = selected + canonicalRule
                                query = ""
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                    if (visibleEntries.isEmpty() && !canAddManually) {
                        item(key = "__empty__") {
                            Text(
                                text = if (trimmedQuery.isEmpty()) {
                                    "没有可显示的应用"
                                } else {
                                    "没有匹配项。可输入包名、Activity 类名或进程名，例如 com.tencent.mm:appbrand0"
                                },
                                fontSize = 13.sp,
                                color = textSecondary,
                                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            )
                        }
                    }
                    items(visibleEntries, key = { it.packageName }) { entry ->
                        AppRow(
                            entry = entry,
                            checked = entry.packageName in selected,
                            iconCache = iconCache,
                            surface = surface,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            primary = primary
                        ) { checked ->
                            selected = if (checked) selected + entry.packageName else selected - entry.packageName
                        }
                    }
                }
            }
        }

        if (showClearDialog) {
            MiuixDialog(
                title = "清空过滤名单",
                message = "将移除全部 ${selected.size} 个已选应用，手势会在所有应用中恢复生效。",
                confirmText = "清空",
                onConfirm = { selected = emptySet() },
                onDismiss = { showClearDialog = false }
            )
        }
    }
}

@Composable
private fun AppRow(
    entry: AppEntry,
    checked: Boolean,
    iconCache: HashMap<String, ImageBitmap?>,
    surface: Color,
    textPrimary: Color,
    textSecondary: Color,
    primary: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val icon by produceState(initialValue = iconCache[entry.packageName], entry.packageName) {
        if (entry.isInstalled && !iconCache.containsKey(entry.packageName)) {
            val loaded = withContext(Dispatchers.IO) { loadIcon(context, entry.packageName) }
            iconCache[entry.packageName] = loaded
            value = loaded
        }
    }

    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surface)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(textSecondary.copy(alpha = 0.18f))
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = entry.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = ruleCaption(entry),
                fontSize = 12.sp,
                color = textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        CheckMark(checked = checked, primary = primary, idle = textSecondary)
    }
}

@Composable
private fun CheckMark(checked: Boolean, primary: Color, idle: Color) {
    val modifier = Modifier
        .size(24.dp)
        .clip(CircleShape)
    Box(
        modifier = if (checked) {
            modifier.background(primary)
        } else {
            modifier.border(1.5.dp, idle.copy(alpha = 0.6f), CircleShape)
        },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Text(text = "✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun ManualAddRow(packageName: String, surface: Color, primary: Color, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surface)
            .clickable { onAdd() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "+", fontSize = 22.sp, color = primary, modifier = Modifier.width(40.dp))
        Text(
            text = "添加过滤规则 $packageName",
            fontSize = 15.sp,
            color = primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FilterChip(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val isDark = isSystemInDarkTheme()
    val primary = if (isDark) MiuixColors.PrimaryDark else MiuixColors.PrimaryLight
    val idleBg = if (isDark) MiuixColors.TrackUncheckedDark else MiuixColors.TrackUncheckedLight
    val idleText = if (isDark) MiuixColors.TextPrimaryDark else MiuixColors.TextPrimaryLight
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (checked) primary else idleBg)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = text, fontSize = 13.sp, color = if (checked) Color.White else idleText)
    }
}

@Composable
private fun ActionText(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = if (enabled) color else color.copy(alpha = 0.35f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

private fun ruleCaption(entry: AppEntry): String {
    if (entry.isInstalled) return entry.packageName
    val kind = if (':' in entry.packageName) "进程" else "Activity / 自定义规则"
    return "$kind · ${entry.packageName}"
}

private fun loadInstalledApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launchable = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).mapTo(HashSet()) { it.activityInfo.packageName }

    @Suppress("DEPRECATION")
    return pm.getInstalledApplications(0)
        .filter { it.packageName != context.packageName }
        .map { info ->
            // 带桌面图标的预装应用（如图库、浏览器）按普通应用展示，仅纯后台系统组件归为“系统应用”
            val isSystemFlag = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            AppEntry(
                packageName = info.packageName,
                label = info.loadLabel(pm).toString(),
                isSystem = isSystemFlag && info.packageName !in launchable
            )
        }
}

private fun loadIcon(context: Context, packageName: String): ImageBitmap? {
    return try {
        val px = (40 * context.resources.displayMetrics.density).toInt()
        context.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Throwable) {
        null
    }
}
