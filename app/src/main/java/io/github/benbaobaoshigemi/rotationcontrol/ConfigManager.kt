package io.github.benbaobaoshigemi.rotationcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 手势开关配置管理器：
 * 1. 负责 system_server 内部的内存状态缓存、BroadcastReceiver 零权限实时通信、ContentObserver 监听；
 * 2. 负责 App UI 端的配置存取（本地 SharedPreferences 兜底 + Broadcast 实时通知 + Root 备选）。
 */
object ConfigManager {
    private const val TAG = "LSPosed-RotationConfig"

    const val PREF_NAME = "rotation_config"
    const val KEY_TWO_FINGER = "lsp_rot_two_finger"
    const val KEY_BOTTOM_SWIPE = "lsp_rot_bottom_swipe"
    const val KEY_TRIPLE_TAP = "lsp_rot_triple_tap"
    const val KEY_IME_GUARD = "lsp_rot_ime_guard"
    const val KEY_WECHAT_MINIPROGRAM_GUARD = "lsp_rot_wechat_miniprog"
    const val KEY_BLOCKED_APPS = "lsp_rot_blocked_apps"

    const val ACTION_UPDATE_CONFIG = "io.github.benbaobaoshigemi.rotationcontrol.UPDATE_CONFIG"
    const val EXTRA_KEY = "extra_key"
    const val EXTRA_VALUE = "extra_value"
    const val EXTRA_STRING_VALUE = "extra_string_value"

    // system_server 内部内存实时状态缓存
    @Volatile
    var isTwoFingerEnabled: Boolean = true
        private set

    @Volatile
    var isBottomSwipeEnabled: Boolean = true
        private set

    @Volatile
    var isTripleTapEnabled: Boolean = true
        private set

    @Volatile
    var isImeGuardEnabled: Boolean = true
        private set

    @Volatile
    var isWeChatMiniProgramGuardEnabled: Boolean = true
        private set

    @Volatile
    var blockedRules: Set<String> = emptySet()
        private set

    private var isObserverRegistered = false

    /**
     * 在 system_server 启动时初始化并注册广播监听器与 ContentObserver
     */
    fun initInSystemServer(context: Context) {
        if (isObserverRegistered) return
        isObserverRegistered = true

        updateAllValues(context)

        // 1. 注册 BroadcastReceiver：零权限、跨进程无延迟投递配置
        val filter = IntentFilter(ACTION_UPDATE_CONFIG)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                if (key == KEY_BLOCKED_APPS) {
                    val raw = intent.getStringExtra(EXTRA_STRING_VALUE) ?: ""
                    blockedRules = decodeRules(raw)
                    try {
                        Settings.System.putString(ctx.contentResolver, key, raw)
                    } catch (_: Throwable) {}
                    log("Broadcast config applied: blockedRules=${blockedRules.size} $blockedRules")
                    return
                }
                val value = intent.getBooleanExtra(EXTRA_VALUE, true)
                when (key) {
                    KEY_TWO_FINGER -> isTwoFingerEnabled = value
                    KEY_BOTTOM_SWIPE -> isBottomSwipeEnabled = value
                    KEY_TRIPLE_TAP -> isTripleTapEnabled = value
                    KEY_IME_GUARD -> isImeGuardEnabled = value
                    KEY_WECHAT_MINIPROGRAM_GUARD -> isWeChatMiniProgramGuardEnabled = value
                }
                // 由 system_server (UID 1000) 自身写入系统设置持久化
                try {
                    Settings.System.putInt(ctx.contentResolver, key, if (value) 1 else 0)
                } catch (_: Throwable) {}
                log("Broadcast config applied: $key=$value -> twoFinger=$isTwoFingerEnabled, bottomSwipe=$isBottomSwipeEnabled, tripleTap=$isTripleTapEnabled, imeGuard=$isImeGuardEnabled, weChatMiniProgram=$isWeChatMiniProgramGuardEnabled")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            log("BroadcastReceiver registered for gesture config in system_server")
        } catch (t: Throwable) {
            log("Failed to register BroadcastReceiver: ${t.message}")
        }

        // 2. 注册 ContentObserver 兜底
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                updateAllValues(context)
            }
        }
        try {
            val cr = context.contentResolver
            cr.registerContentObserver(Settings.System.getUriFor(KEY_TWO_FINGER), false, observer)
            cr.registerContentObserver(Settings.System.getUriFor(KEY_BOTTOM_SWIPE), false, observer)
            cr.registerContentObserver(Settings.System.getUriFor(KEY_TRIPLE_TAP), false, observer)
            cr.registerContentObserver(Settings.System.getUriFor(KEY_IME_GUARD), false, observer)
            cr.registerContentObserver(Settings.System.getUriFor(KEY_WECHAT_MINIPROGRAM_GUARD), false, observer)
            cr.registerContentObserver(Settings.System.getUriFor(KEY_BLOCKED_APPS), false, observer)
            log("ContentObserver registered for gesture settings in system_server")
        } catch (t: Throwable) {
            log("Failed to register ContentObserver: ${t.message}")
        }
    }

    private fun updateAllValues(context: Context) {
        try {
            val cr = context.contentResolver
            isTwoFingerEnabled = Settings.System.getInt(cr, KEY_TWO_FINGER, 1) == 1
            isBottomSwipeEnabled = Settings.System.getInt(cr, KEY_BOTTOM_SWIPE, 1) == 1
            isTripleTapEnabled = Settings.System.getInt(cr, KEY_TRIPLE_TAP, 1) == 1
            isImeGuardEnabled = Settings.System.getInt(cr, KEY_IME_GUARD, 1) == 1
            isWeChatMiniProgramGuardEnabled = Settings.System.getInt(cr, KEY_WECHAT_MINIPROGRAM_GUARD, 1) == 1
            blockedRules = decodeRules(Settings.System.getString(cr, KEY_BLOCKED_APPS) ?: "")
            log("Config updated: twoFinger=$isTwoFingerEnabled, bottomSwipe=$isBottomSwipeEnabled, tripleTap=$isTripleTapEnabled, imeGuard=$isImeGuardEnabled, weChatMiniProgram=$isWeChatMiniProgramGuardEnabled, blockedRules=${blockedRules.size}")
        } catch (t: Throwable) {
            log("Error reading Settings.System: ${t.message}")
        }
    }

    /**
     * App 端读取开关状态：
     * 优先读取本地 SharedPreferences（保证 UI 状态永不被覆盖或回退），未存过则回退到 Settings.System
     */
    fun getSetting(context: Context, key: String, default: Boolean = true): Boolean {
        return try {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            if (sp.contains(key)) {
                sp.getBoolean(key, default)
            } else {
                val sysVal = Settings.System.getInt(context.contentResolver, key, if (default) 1 else 0) == 1
                sp.edit().putBoolean(key, sysVal).apply()
                sysVal
            }
        } catch (_: Throwable) {
            default
        }
    }

    /**
     * App 端保存开关状态：
     * 1. 同步提交至 SharedPreferences（UI 立即持久化，绝不丢状态）
     * 2. 发送 Broadcast 直达 system_server（零权限障碍、<5ms 立即热生效）
     * 3. 异步尝试 Settings.System 及 Root 兜底
     */
    fun setSetting(context: Context, key: String, value: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(key, value).apply()

        // 1. 发送动态广播
        val intent = Intent(ACTION_UPDATE_CONFIG).apply {
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_VALUE, value)
        }
        context.sendBroadcast(intent)

        // 2. 异步兜底写入 Settings.System
        Thread({
            val intVal = if (value) 1 else 0
            var directSuccess = false
            try {
                directSuccess = Settings.System.putInt(context.contentResolver, key, intVal)
            } catch (_: Throwable) {}
            if (!directSuccess) {
                runRootCmd("settings put system $key $intVal")
            }
        }, "ConfigSync-$key").start()
    }

    /**
     * App 端读取过滤名单：本地 SharedPreferences 优先，未存过则回退到 Settings.System
     */
    fun getBlockedApps(context: Context): Set<String> {
        return try {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = if (sp.contains(KEY_BLOCKED_APPS)) {
                sp.getString(KEY_BLOCKED_APPS, "") ?: ""
            } else {
                Settings.System.getString(context.contentResolver, KEY_BLOCKED_APPS) ?: ""
            }
            decodeRules(raw)
        } catch (_: Throwable) {
            emptySet()
        }
    }

    /**
     * App 端保存过滤名单：与布尔开关相同的 SharedPreferences + Broadcast + Settings/Root 兜底链路
     */
    fun setBlockedApps(context: Context, packages: Set<String>) {
        val raw = encodeRules(packages)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BLOCKED_APPS, raw).apply()

        context.sendBroadcast(Intent(ACTION_UPDATE_CONFIG).apply {
            putExtra(EXTRA_KEY, KEY_BLOCKED_APPS)
            putExtra(EXTRA_STRING_VALUE, raw)
        })

        Thread({
            var directSuccess = false
            try {
                directSuccess = Settings.System.putString(context.contentResolver, KEY_BLOCKED_APPS, raw)
            } catch (_: Throwable) {}
            if (!directSuccess) {
                runRootCmd("settings put system $KEY_BLOCKED_APPS '$raw'")
            }
        }, "ConfigSync-$KEY_BLOCKED_APPS").start()
    }

    fun canonicalizeFilterRule(raw: String): String? = ForegroundFilterPolicy.canonicalizeRule(raw)

    fun isValidFilterRule(name: String): Boolean = ForegroundFilterPolicy.isValidRule(name)

    private fun encodeRules(rules: Set<String>): String =
        rules.mapNotNull { canonicalizeFilterRule(it) }.toSortedSet().joinToString(",")

    private fun decodeRules(raw: String): Set<String> =
        raw.split(',').map { it.trim() }.mapNotNull { canonicalizeFilterRule(it) }.toSet()

    /**
     * 在 App 启动时，将本地所有配置同步广播给 system_server
     */
    fun syncAllSettingsToSystem(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val twoFinger = sp.getBoolean(KEY_TWO_FINGER, true)
        val bottomSwipe = sp.getBoolean(KEY_BOTTOM_SWIPE, true)
        val tripleTap = sp.getBoolean(KEY_TRIPLE_TAP, true)
        val imeGuard = sp.getBoolean(KEY_IME_GUARD, true)
        val weChatMiniProgram = sp.getBoolean(KEY_WECHAT_MINIPROGRAM_GUARD, true)

        listOf(
            KEY_TWO_FINGER to twoFinger,
            KEY_BOTTOM_SWIPE to bottomSwipe,
            KEY_TRIPLE_TAP to tripleTap,
            KEY_IME_GUARD to imeGuard,
            KEY_WECHAT_MINIPROGRAM_GUARD to weChatMiniProgram
        ).forEach { (k, v) ->
            val intent = Intent(ACTION_UPDATE_CONFIG).apply {
                putExtra(EXTRA_KEY, k)
                putExtra(EXTRA_VALUE, v)
            }
            context.sendBroadcast(intent)
        }

        if (sp.contains(KEY_BLOCKED_APPS)) {
            context.sendBroadcast(Intent(ACTION_UPDATE_CONFIG).apply {
                putExtra(EXTRA_KEY, KEY_BLOCKED_APPS)
                putExtra(EXTRA_STRING_VALUE, sp.getString(KEY_BLOCKED_APPS, "") ?: "")
            })
        }
    }

    /**
     * 重启作用域进程：
     * 使用 Android 原生 init 指令 setprop ctl.restart zygote 优雅平滑重启，
     * 严禁使用 kill -9 $(pidof system_server) 导致异常崩溃计数或 LSPosed 安全模式。
     */
    fun restartScopeProcess(): Boolean {
        return runRootCmd("setprop ctl.restart zygote")
    }

    /**
     * 执行 Root 命令行（自动适配不同 Root 环境下的 su 路径）
     */
    fun runRootCmd(cmd: String): Boolean {
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "su")
        for (suPath in suPaths) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", cmd))
                val code = process.waitFor()
                if (code == 0) return true
            } catch (_: Throwable) {}
        }
        Log.e(TAG, "Failed to run root cmd across all su candidates: $cmd")
        return false
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {}
    }
}
