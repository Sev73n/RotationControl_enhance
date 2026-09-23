package io.github.benbaobaoshigemi.rotationcontrol

import android.content.Context
import android.os.IBinder
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 挂钩 system_server：
 * 通道 1：WindowManagerService.systemReady 注册全局 PointerEventListener
 * 通道 2：InputManagerService.filterInputEvent 原生触摸事件拦截（双保险）
 */
object TwoFingerRotationHook {
    private const val TAG = "LSPosed-Rotation"
    private var isWmsListenerRegistered = false
    private var systemContext: Context? = null
    private var gestureDetector: ScreenRotationGestureDetector? = null
    private var threeFingerSwipeDetector: ThreeFingerSwipeGestureDetector? = null
    private var tripleTapDetector: ThreeFingerTripleTapDetector? = null

    private var lastEventTime: Long = 0L

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        XposedBridge.log("[$TAG] Attaching TwoFingerRotationHook to system_server")

        // 1. 初始化双指点击旋转手势检测器
        gestureDetector = ScreenRotationGestureDetector(
            contextProvider = { systemContext },
            suppressionReason = {
                if (ConfigManager.isImeGuardEnabled && ImeVisibilityTracker.isImeShown()) "IME is shown" else null
            }
        ) { targetRotation, reason ->
            if (passesAppFilter("TwoFingerDoubleTap")) RotationController.rotateScreen(targetRotation, reason)
        }

        // 2. 初始化底边三指横扫旋转手势检测器
        threeFingerSwipeDetector = ThreeFingerSwipeGestureDetector(
            contextProvider = { systemContext }
        ) { targetRotation, reason ->
            if (passesAppFilter("ThreeFingerSwipe")) RotationController.rotateScreen(targetRotation, reason)
        }

        // 3. 初始化三指三击切换锁定手势检测器
        tripleTapDetector = ThreeFingerTripleTapDetector(
            contextProvider = { systemContext }
        ) {
            if (passesAppFilter("ThreeFingerTripleTap")) RotationController.toggleRotationLock()
        }

        // ==========================================
        // 通道 1：WindowManagerService (WMS)
        // ==========================================
        try {
            val wmsClass = XposedHelpers.findClass("com.android.server.wm.WindowManagerService", lpparam.classLoader)

            // 严格在 systemReady 之后注册（确保 DisplayContent(0) 及 InputMonitor 完全创建就绪）
            XposedHelpers.findAndHookMethod(
                wmsClass,
                "systemReady",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("[$TAG] WindowManagerService.systemReady hooked -> Registering PointerEventListener")
                        initWms(param.thisObject, lpparam.classLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook WindowManagerService: ${t.message}")
        }

        // ==========================================
        // 通道 2：InputManagerService (IMS) 双保险
        // ==========================================
        try {
            val imsClass = XposedHelpers.findClass("com.android.server.input.InputManagerService", lpparam.classLoader)

            // 1. 获取 Context 并确保 RotationController 初始化
            XposedHelpers.findAndHookMethod(
                imsClass,
                "systemRunning",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                        if (context != null) {
                            systemContext = context
                            XposedBridge.log("[$TAG] Obtained system Context from IMS.systemRunning")
                            if (!isWmsListenerRegistered) {
                                tryInitWithServiceManager(context, lpparam.classLoader)
                            }
                        }
                    }
                }
            )

            // 2. 挂钩 filterInputEvent 直接接收原始触摸流
            XposedHelpers.findAndHookMethod(
                imsClass,
                "filterInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args[0] as? InputEvent ?: return
                        if (event is MotionEvent) {
                            // 仅处理来自屏幕的触摸事件
                            val source = event.source
                            if ((source and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                                (source and InputDevice.SOURCE_TOUCHSCREEN) != 0) {
                                onMotionEventReceived(event, "IMS-Filter")
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] Successfully hooked filterInputEvent in InputManagerService as dual-channel listener")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook InputManagerService: ${t.message}")
        }
    }

    private fun passesAppFilter(gesture: String): Boolean {
        val blocked = ForegroundAppFilter.blockedForegroundPackage(systemContext) ?: return true
        XposedBridge.log("[$TAG] $gesture suppressed: foreground app $blocked is in filter list")
        return false
    }

    @Synchronized
    fun initWms(wmsInstance: Any, classLoader: ClassLoader, fallbackContext: Context? = null) {
        if (isWmsListenerRegistered) return
        try {
            val context = (XposedHelpers.getObjectField(wmsInstance, "mContext") as? Context) ?: fallbackContext
            if (context != null) {
                systemContext = context
                RotationController.init(context, wmsInstance)
                ConfigManager.initInSystemServer(context)
            }
            ImeVisibilityTracker.init(wmsInstance)

            val success = registerPointerEventListener(wmsInstance, classLoader)
            if (success) {
                isWmsListenerRegistered = true
                XposedBridge.log("[$TAG] TwoFingerRotationHook WMS PointerEventListener registered successfully!")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to initialize WMS: ${t.message}")
        }
    }

    private fun tryInitWithServiceManager(context: Context, classLoader: ClassLoader) {
        try {
            val smClass = XposedHelpers.findClass("android.os.ServiceManager", classLoader)
            val wmsBinder = XposedHelpers.callStaticMethod(smClass, "getService", Context.WINDOW_SERVICE)
            if (wmsBinder != null) {
                initWms(wmsBinder, classLoader, context)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] tryInitWithServiceManager failed: ${t.message}")
        }
    }

    private fun registerPointerEventListener(wmsInstance: Any, classLoader: ClassLoader): Boolean {
        try {
            val listenerClass = try {
                XposedHelpers.findClass("android.view.WindowManagerPolicyConstants\$PointerEventListener", classLoader)
            } catch (e: Throwable) {
                try {
                    Class.forName("android.view.WindowManagerPolicyConstants\$PointerEventListener")
                } catch (e2: Throwable) {
                    XposedHelpers.findClass("com.android.server.wm.WindowManagerPolicyConstants\$PointerEventListener", classLoader)
                }
            }

            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader ?: classLoader,
                arrayOf(listenerClass),
                object : InvocationHandler {
                    override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                        when (method.name) {
                            "onPointerEvent" -> {
                                val event = args?.getOrNull(0) as? MotionEvent
                                if (event != null) {
                                    onMotionEventReceived(event, "WMS-PointerListener")
                                }
                                return null
                            }
                            "equals" -> return proxy === args?.getOrNull(0)
                            "hashCode" -> return System.identityHashCode(proxy)
                            "toString" -> return "TwoFingerPointerEventListener@${Integer.toHexString(System.identityHashCode(proxy))}"
                        }
                        if (method.returnType == Boolean::class.javaPrimitiveType) return false
                        if (method.returnType == Int::class.javaPrimitiveType) return 0
                        return null
                    }
                }
            )

            val regMethod = wmsInstance.javaClass.methods.firstOrNull {
                it.name == "registerPointerEventListener" && it.parameterTypes.isNotEmpty() &&
                        it.parameterTypes[0].isAssignableFrom(listenerClass)
            } ?: wmsInstance.javaClass.methods.firstOrNull { it.name == "registerPointerEventListener" }

            if (regMethod != null) {
                if (regMethod.parameterTypes.size == 2) {
                    regMethod.invoke(wmsInstance, proxy, 0)
                } else {
                    regMethod.invoke(wmsInstance, proxy)
                }
                XposedBridge.log("[$TAG] registerPointerEventListener invoked successfully via reflection: $regMethod")
                return true
            } else {
                XposedBridge.log("[$TAG] registerPointerEventListener method not found on WMS")
                return false
            }
        } catch (t: Throwable) {
            val realCause = if (t is java.lang.reflect.InvocationTargetException) t.targetException ?: t else t
            XposedBridge.log("[$TAG] Failed to registerPointerEventListener: ${realCause.javaClass.name}: ${realCause.message}\n${Log.getStackTraceString(realCause)}")
            return false
        }
    }

    /**
     * 统一接收触摸事件流（带去重抑制）
     */
    fun onMotionEventReceived(event: MotionEvent, channel: String) {
        val eventTime = event.eventTime
        val action = event.actionMasked

        // 防止多通道对同一个 Move 事件重复处理
        if (eventTime == lastEventTime && action == MotionEvent.ACTION_MOVE) {
            return
        }
        lastEventTime = eventTime

        if (event.pointerCount >= 2 && action == MotionEvent.ACTION_POINTER_DOWN) {
            XposedBridge.log("[$TAG] Touch stream active on $channel (pointers=${event.pointerCount}, action=$action)")
        }

        if (ConfigManager.isTwoFingerEnabled) {
            gestureDetector?.onPointerEvent(event)
        }
        if (ConfigManager.isBottomSwipeEnabled) {
            threeFingerSwipeDetector?.onPointerEvent(event)
        }
        if (ConfigManager.isTripleTapEnabled) {
            tripleTapDetector?.onPointerEvent(event)
        }
    }
}
