package io.github.benbaobaoshigemi.rotationcontrol

import android.app.ActivityManager
import android.content.Context
import de.robv.android.xposed.XposedBridge

/**
 * 前台应用过滤（运行于 system_server）：
 * 通过公开 API ActivityManager.getRunningTasks(1) 取得最近获得焦点的任务栈顶应用，
 * 若其包名在用户勾选的过滤名单中，则本次手势不执行。
 * 以 system uid 调用时该 API 返回完整任务信息；分屏/自由窗口下即最近被触摸的那个窗口所属任务。
 */
object ForegroundAppFilter {
    private const val TAG = "LSPosed-RotationFilter"

    fun blockedForegroundPackage(context: Context?): String? {
        val blocked = ConfigManager.blockedPackages
        if (blocked.isEmpty() || context == null) return null
        val pkg = foregroundPackage(context) ?: return null
        return if (pkg in blocked) pkg else null
    }

    private fun foregroundPackage(context: Context): String? {
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to resolve foreground package: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }
}
