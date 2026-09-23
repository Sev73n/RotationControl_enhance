package io.github.benbaobaoshigemi.rotationcontrol

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 输入法可见性探测器（运行于 system_server）：
 * 探针 A：DisplayContent.getInsetsStateController().getRawInsetsState() 中 ID_IME 源的可见性；
 * 探针 B：DisplayContent.mInputMethodWindow (WindowState) 的 isVisible()。
 * 任何探针失败时视为“输入法未显示”，保证手势功能不因反射失败而整体失效。
 */
object ImeVisibilityTracker {
    private const val TAG = "LSPosed-RotationIme"

    @Volatile
    private var wmsInstance: Any? = null
    private var defaultDisplayContent: Any? = null
    private var imeSourceId: Int? = null
    private var probeAFailed = false
    private var probeBFailed = false
    private var lastLoggedProbe: String? = null

    fun init(wms: Any) {
        wmsInstance = wms
    }

    @Synchronized
    fun isImeShown(): Boolean {
        val dc = getDefaultDisplayContent() ?: return false

        if (!probeAFailed) {
            try {
                return queryInsetsState(dc).also { logProbeOnce("InsetsState(ID_IME)") }
            } catch (t: Throwable) {
                probeAFailed = true
                log("Probe A (InsetsState) unavailable: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        if (!probeBFailed) {
            try {
                return queryImeWindow(dc).also { logProbeOnce("mInputMethodWindow.isVisible") }
            } catch (t: Throwable) {
                probeBFailed = true
                log("Probe B (mInputMethodWindow) unavailable: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        return false
    }

    private fun queryInsetsState(dc: Any): Boolean {
        val controller = XposedHelpers.callMethod(dc, "getInsetsStateController")
        val state = XposedHelpers.callMethod(controller, "getRawInsetsState")
        val id = imeSourceId ?: (XposedHelpers.getStaticIntField(
            XposedHelpers.findClass("android.view.InsetsSource", null), "ID_IME"
        )).also { imeSourceId = it }
        val source = XposedHelpers.callMethod(state, "peekSource", id) ?: return false
        return XposedHelpers.callMethod(source, "isVisible") as Boolean
    }

    private fun queryImeWindow(dc: Any): Boolean {
        val imeWindow = XposedHelpers.getObjectField(dc, "mInputMethodWindow") ?: return false
        return XposedHelpers.callMethod(imeWindow, "isVisible") as Boolean
    }

    private fun getDefaultDisplayContent(): Any? {
        defaultDisplayContent?.let { return it }
        val wms = wmsInstance ?: return null
        val dc = try {
            XposedHelpers.callMethod(wms, "getDefaultDisplayContentLocked")
        } catch (_: Throwable) {
            try {
                val root = XposedHelpers.getObjectField(wms, "mRoot")
                XposedHelpers.callMethod(root, "getDefaultDisplay")
            } catch (t: Throwable) {
                log("Failed to resolve default DisplayContent: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
        defaultDisplayContent = dc
        return dc
    }

    private fun logProbeOnce(probe: String) {
        if (lastLoggedProbe != probe) {
            lastLoggedProbe = probe
            log("IME visibility resolved via $probe")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }
}
