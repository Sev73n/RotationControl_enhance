package io.github.benbaobaoshigemi.rotationcontrol

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "LSPosedModule"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("[$TAG] Loaded package: ${lpparam.packageName}, process: ${lpparam.processName}")

        // Hook system_server (package: "android")
        if (lpparam.packageName == "android" && lpparam.processName == "android") {
            TwoFingerRotationHook.hook(lpparam)
        }
    }
}
