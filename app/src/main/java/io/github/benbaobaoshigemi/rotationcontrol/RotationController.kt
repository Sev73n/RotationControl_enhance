package io.github.benbaobaoshigemi.rotationcontrol

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Surface
import de.robv.android.xposed.XposedBridge

/**
 * 屏幕旋转控制器：
 * 1. 负责反射调用 WindowManagerService 的 freezeRotation / freezeDisplayRotation；
 * 2. 负责同步更新 Settings.System 的 USER_ROTATION 与 ACCELEROMETER_ROTATION；
 * 3. 负责触发系统轻微震动反馈（Haptic feedback）；
 * 4. 支持绝对朝向模式（默认）与相对步进模式。
 */
object RotationController {
    private const val TAG = "LSPosed-Rotation"

    private var appContext: Context? = null
    private var wmsInstance: Any? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 旋转模式配置：
     * false: 绝对定向模式（默认推荐）。点击下方固定转 180° 朝下，点击右侧固定转 90°。
     * true: 相对步进模式。点击右侧在当前屏幕角度基础上再顺时针累加 +90°。
     */
    var useRelativeRotation: Boolean = false

    fun init(context: Context, wms: Any) {
        appContext = context
        wmsInstance = wms
        XposedBridge.log("[$TAG] RotationController initialized with Context and WindowManagerService")
    }

    /**
     * 执行屏幕旋转
     * @param targetRotation Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270
     * @param reason 触发原因与算法决策描述
     */
    fun rotateScreen(targetRotation: Int, reason: String) {
        mainHandler.post {
            try {
                val context = appContext
                val wms = wmsInstance

                val currentRotation = getCurrentRotation()
                val finalRotation = if (useRelativeRotation) {
                    when (targetRotation) {
                        Surface.ROTATION_90 -> (currentRotation + 1) % 4
                        Surface.ROTATION_180 -> (currentRotation + 2) % 4
                        Surface.ROTATION_270 -> (currentRotation + 3) % 4
                        else -> Surface.ROTATION_0
                    }
                } else {
                    targetRotation
                }

                XposedBridge.log(
                    "[$TAG] Intent: Pointing to $finalRotation (current=$currentRotation, reason=$reason)"
                )

                // 幂等性检查：若当前屏幕已处于目标指向，不重复执行破坏性翻转
                if (currentRotation == finalRotation) {
                    XposedBridge.log("[$TAG] Display is already at target orientation $finalRotation. Keeping orientation locked.")
                    if (context != null) {
                        try {
                            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                            Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, finalRotation)
                        } catch (_: Throwable) {}
                        triggerHapticFeedback(context)
                    }
                    return@post
                }

                // 1. 调用 WindowManagerService.freezeRotation 冻结并立即更新显示角度
                var wmsSuccess = false
                if (wms != null) {
                    wmsSuccess = invokeWmsFreeze(wms, finalRotation)
                }

                // 2. 更新 Settings.System 保证系统级持久化并唤醒 DisplayRotation 观察者
                if (context != null) {
                    try {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.ACCELEROMETER_ROTATION,
                            0
                        )
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.USER_ROTATION,
                            finalRotation
                        )
                        XposedBridge.log("[$TAG] Settings.System updated: USER_ROTATION=$finalRotation, ACCELEROMETER_ROTATION=0")
                    } catch (t: Throwable) {
                        XposedBridge.log("[$TAG] Failed to update Settings.System: ${t.message}")
                    }

                    // 3. 触觉震动反馈，给用户明确的手势触发确认
                    triggerHapticFeedback(context)
                }

                XposedBridge.log("[$TAG] Rotation execution finished: finalRotation=$finalRotation, wmsSuccess=$wmsSuccess")
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Error during rotateScreen execution: ${t.message}")
            }
        }
    }

    /**
     * 切换系统原生屏幕旋转锁定状态
     */
    fun toggleRotationLock() {
        mainHandler.post {
            try {
                val context = appContext ?: return@post

                // 优先通过系统原生内部类 RotationPolicy 切换（与下拉通知栏快捷磁贴 100% 同源）
                var nativeSuccess = false
                try {
                    val policyClass = Class.forName("com.android.internal.view.RotationPolicy")
                    val isLockedMethod = policyClass.getMethod("isRotationLocked", Context::class.java)
                    val setLockedMethod = policyClass.getMethod("setRotationLock", Context::class.java, Boolean::class.javaPrimitiveType)

                    val isLocked = isLockedMethod.invoke(null, context) as Boolean
                    val targetLock = !isLocked
                    setLockedMethod.invoke(null, context, targetLock)
                    triggerHapticFeedback(context, isLock = targetLock)
                    XposedBridge.log("[$TAG] Native RotationPolicy toggled: wasLocked=$isLocked -> targetLock=$targetLock")
                    nativeSuccess = true
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] Native RotationPolicy reflection failed: ${t.message}, using fallback")
                }

                if (!nativeSuccess) {
                    val cr = context.contentResolver
                    val autoRotate = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

                    if (autoRotate) {
                        // 当前为自动旋转 -> 锁定为当前朝向
                        val curRot = getCurrentRotation()
                        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                        Settings.System.putInt(cr, Settings.System.USER_ROTATION, curRot)
                        wmsInstance?.let { invokeWmsFreeze(it, curRot) }
                        triggerHapticFeedback(context, isLock = true)
                        XposedBridge.log("[$TAG] Toggle: Auto-rotate DISABLED -> Rotation LOCKED at $curRot")
                    } else {
                        // 当前为锁定 -> 解锁开启自动旋转
                        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
                        wmsInstance?.let { invokeWmsThaw(it) }
                        triggerHapticFeedback(context, isLock = false)
                        XposedBridge.log("[$TAG] Toggle: Rotation UNLOCKED -> Auto-rotate ENABLED")
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] toggleRotationLock error: ${t.message}")
            }
        }
    }

    private fun getCurrentRotation(): Int {
        val context = appContext
        if (context != null) {
            try {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (display != null) {
                    return display.rotation
                }
            } catch (_: Throwable) {}

            try {
                return Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.USER_ROTATION,
                    Surface.ROTATION_0
                )
            } catch (_: Throwable) {}
        }
        return Surface.ROTATION_0
    }

    private fun invokeWmsFreeze(wms: Any, rotation: Int): Boolean {
        var success = false

        // 尝试 freezeRotation(int rotation, String caller)
        try {
            val method2 = wms.javaClass.methods.firstOrNull {
                it.name == "freezeRotation" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == String::class.java
            }
            if (method2 != null) {
                method2.invoke(wms, rotation, "TwoFingerRotationHook")
                success = true
                XposedBridge.log("[$TAG] Invoked WMS.freezeRotation(rotation, caller)")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] WMS.freezeRotation(int, String) failed: ${t.message}")
        }

        // 尝试 freezeRotation(int rotation)
        if (!success) {
            try {
                val method1 = wms.javaClass.methods.firstOrNull {
                    it.name == "freezeRotation" && it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                if (method1 != null) {
                    method1.invoke(wms, rotation)
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.freezeRotation(rotation)")
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] WMS.freezeRotation(int) failed: ${t.message}")
            }
        }

        // 尝试 freezeDisplayRotation(int displayId, int rotation, String caller)
        try {
            val methodDisplay = wms.javaClass.methods.firstOrNull {
                it.name == "freezeDisplayRotation"
            }
            if (methodDisplay != null) {
                if (methodDisplay.parameterTypes.size == 3) {
                    methodDisplay.invoke(wms, 0, rotation, "TwoFingerRotationHook")
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.freezeDisplayRotation(0, rotation, caller)")
                } else if (methodDisplay.parameterTypes.size == 2) {
                    methodDisplay.invoke(wms, 0, rotation)
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.freezeDisplayRotation(0, rotation)")
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] WMS.freezeDisplayRotation failed: ${t.message}")
        }

        return success
    }

    private fun invokeWmsThaw(wms: Any): Boolean {
        var success = false
        try {
            val method = wms.javaClass.methods.firstOrNull {
                it.name == "thawRotation" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            }
            if (method != null) {
                method.invoke(wms, "TwoFingerRotationHook")
                success = true
                XposedBridge.log("[$TAG] Invoked WMS.thawRotation(caller)")
            }
        } catch (_: Throwable) {}

        if (!success) {
            try {
                val method = wms.javaClass.methods.firstOrNull { it.name == "thawRotation" && it.parameterTypes.isEmpty() }
                if (method != null) {
                    method.invoke(wms)
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.thawRotation()")
                }
            } catch (_: Throwable) {}
        }

        try {
            val methodDisplay = wms.javaClass.methods.firstOrNull { it.name == "thawDisplayRotation" }
            if (methodDisplay != null) {
                if (methodDisplay.parameterTypes.size == 2) {
                    methodDisplay.invoke(wms, 0, "TwoFingerRotationHook")
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.thawDisplayRotation(0, caller)")
                } else if (methodDisplay.parameterTypes.size == 1) {
                    methodDisplay.invoke(wms, 0)
                    success = true
                    XposedBridge.log("[$TAG] Invoked WMS.thawDisplayRotation(0)")
                }
            }
        } catch (_: Throwable) {}

        return success
    }

    private fun triggerHapticFeedback(context: Context, isLock: Boolean = true) {
        try {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (isLock) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (isLock) 40L else 70L)
                }
            }
        } catch (_: Throwable) {}
    }
}
