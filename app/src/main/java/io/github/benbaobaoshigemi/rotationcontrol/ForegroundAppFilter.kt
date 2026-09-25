package io.github.benbaobaoshigemi.rotationcontrol

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import de.robv.android.xposed.XposedBridge

/**
 * 前台过滤（运行于 system_server）：
 * 用 ActivityManager.getRunningTasks(1) 取最近获得焦点的栈顶 Activity，
 * 再读该 Activity 声明的进程名。命中用户规则，或命中微信小程序策略时，本次手势不执行。
 * 以 system uid 调用时该 API 返回完整任务信息；分屏/自由窗口下即最近被触摸的那个窗口所属任务。
 */
object ForegroundAppFilter {
    private const val TAG = "LSPosed-RotationFilter"

    @Volatile
    private var loggedProcessLookupFailure = false

    fun suppressionReason(context: Context?): String? {
        if (context == null) return null
        val rules = ConfigManager.blockedRules
        val guard = ConfigManager.isWeChatMiniProgramGuardEnabled
        if (rules.isEmpty() && !guard) return null
        val foreground = resolveForeground(context) ?: return null
        return ForegroundFilterPolicy.suppressionReason(foreground, rules, guard)
    }

    private fun resolveForeground(context: Context): ForegroundFilterPolicy.Foreground? {
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val top = am.getRunningTasks(1).firstOrNull()?.topActivity ?: return null
            val pkg = top.packageName.takeIf { it.isNotEmpty() } ?: return null
            val cls = top.className.takeIf { it.isNotEmpty() } ?: return null
            val process = resolveProcessName(context, top) ?: pkg
            ForegroundFilterPolicy.Foreground(pkg, cls, process)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to resolve foreground: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun resolveProcessName(context: Context, component: ComponentName): String? {
        return try {
            context.packageManager.getActivityInfo(component, 0).processName?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            if (!loggedProcessLookupFailure) {
                loggedProcessLookupFailure = true
                XposedBridge.log(
                    "[$TAG] Process name unavailable for ${component.flattenToShortString()}: " +
                            "${t.javaClass.simpleName}: ${t.message}"
                )
            }
            null
        }
    }
}
