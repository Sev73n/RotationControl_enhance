package io.github.benbaobaoshigemi.rotationcontrol

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import de.robv.android.xposed.XposedBridge
import kotlin.math.hypot

/**
 * 屏幕任意位置三指三击（Triple Tap）检测器：
 * 
 * 核心逻辑：
 * 1. 屏幕任意位置检测 3 指快速连续轻击 3 次；
 * 2. 严格的敲击时限（单次 <= 320ms）与位移门限（<= 45dp），与任何滑动截屏动作物理互斥；
 * 3. 触发后切换屏幕旋转锁定状态：
 *    - 若当前已锁定 (ACCELEROMETER_ROTATION=0)：解锁并开启自动旋转 (ACCELEROMETER_ROTATION=1)，双击震动反馈；
 *    - 若当前为自动旋转 (ACCELEROMETER_ROTATION=1)：锁定为当前屏幕物理朝向 (ACCELEROMETER_ROTATION=0)，单次利落震动反馈。
 */
class ThreeFingerTripleTapDetector(
    private val contextProvider: () -> Context?,
    private val onToggleLockAction: () -> Unit
) {
    companion object {
        private const val TAG = "LSPosed-TripleTap"

        private const val MAX_TAP_DURATION_MS = 320L    // 单次敲击最大允许时长 (ms)
        private const val MAX_TAP_GAP_MS = 380L         // 敲击之间的最大间隔时长 (ms)
        private const val MAX_SLOP_DP = 45f             // 单次点击中允许的最大移动 (dp)，严格滤除滑动手势
        private const val MAX_CLUSTER_DRIFT_DP = 90f    // 三次点击重心之间的最大允许漂移 (dp)
    }

    private enum class State {
        IDLE,
        TAP_1_DOWN,
        WAITING_TAP_2,
        TAP_2_DOWN,
        WAITING_TAP_3,
        TAP_3_DOWN
    }

    private data class Point(val x: Float, val y: Float)

    private var currentState = State.IDLE
    private var tapDownTime = 0L
    private var isTapDisqualified = false
    private var maxPointersInTap = 0

    private var tap1Centroid: Point? = null
    private var tap1UpTime = 0L

    private var tap2Centroid: Point? = null
    private var tap2UpTime = 0L

    private var tapStartCentroid: Point? = null
    private var lastCurrentCentroid: Point? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun onPointerEvent(event: MotionEvent) {
        val actionMasked = event.actionMasked
        val pointerCount = event.pointerCount
        val now = SystemClock.uptimeMillis()
        val density = getDisplayDensity()
        val maxSlopPx = MAX_SLOP_DP * density
        val maxDriftPx = MAX_CLUSTER_DRIFT_DP * density

        if (pointerCount > maxPointersInTap) {
            maxPointersInTap = pointerCount
        }

        // 超过 3 指，立刻取消三击检测
        if (pointerCount > 3) {
            isTapDisqualified = true
            resetToIdle("Pointers > 3 ($pointerCount)")
            return
        }

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 检查前序点击等待超时
                if (currentState == State.WAITING_TAP_2 && now - tap1UpTime > MAX_TAP_GAP_MS) {
                    resetToIdle("Tap 1 -> Tap 2 timeout")
                } else if (currentState == State.WAITING_TAP_3 && now - tap2UpTime > MAX_TAP_GAP_MS) {
                    resetToIdle("Tap 2 -> Tap 3 timeout")
                }

                currentState = when (currentState) {
                    State.WAITING_TAP_2 -> State.TAP_2_DOWN
                    State.WAITING_TAP_3 -> State.TAP_3_DOWN
                    else -> State.TAP_1_DOWN
                }

                tapDownTime = now
                isTapDisqualified = false
                maxPointersInTap = 1
                tapStartCentroid = null
                lastCurrentCentroid = null
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 3) {
                    val c = getCentroid(event, 3)
                    if (tapStartCentroid == null) {
                        tapStartCentroid = c
                    }
                    lastCurrentCentroid = c
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTapDisqualified) return
                if (pointerCount == 3) {
                    val c = getCentroid(event, 3)
                    lastCurrentCentroid = c
                    tapStartCentroid?.let { start ->
                        val moveDist = hypot(c.x - start.x, c.y - start.y)
                        if (moveDist > maxSlopPx) {
                            // 移动过大，视为滑动或截屏动作，立即废弃本点击
                            isTapDisqualified = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount == 3 && lastCurrentCentroid == null) {
                    lastCurrentCentroid = getCentroid(event, 3)
                }
            }

            MotionEvent.ACTION_UP -> {
                val duration = now - tapDownTime
                val isValidTap = !isTapDisqualified &&
                        maxPointersInTap == 3 &&
                        duration <= MAX_TAP_DURATION_MS &&
                        lastCurrentCentroid != null

                if (!isValidTap) {
                    resetToIdle("Tap invalid (duration=$duration, pointers=$maxPointersInTap, disq=$isTapDisqualified)")
                    return
                }

                val currentTapCentroid = lastCurrentCentroid!!

                when (currentState) {
                    State.TAP_1_DOWN -> {
                        tap1Centroid = currentTapCentroid
                        tap1UpTime = now
                        currentState = State.WAITING_TAP_2
                        log("Tap 1 valid at (${currentTapCentroid.x.toInt()}, ${currentTapCentroid.y.toInt()}) in ${duration}ms")
                    }

                    State.TAP_2_DOWN -> {
                        val t1 = tap1Centroid
                        if (t1 == null || hypot(currentTapCentroid.x - t1.x, currentTapCentroid.y - t1.y) > maxDriftPx) {
                            resetToIdle("Tap 2 drift too large")
                            return
                        }
                        tap2Centroid = currentTapCentroid
                        tap2UpTime = now
                        currentState = State.WAITING_TAP_3
                        log("Tap 2 valid in ${duration}ms")
                    }

                    State.TAP_3_DOWN -> {
                        val t1 = tap1Centroid
                        val t2 = tap2Centroid
                        if (t1 == null || t2 == null ||
                            hypot(currentTapCentroid.x - t1.x, currentTapCentroid.y - t1.y) > maxDriftPx ||
                            hypot(currentTapCentroid.x - t2.x, currentTapCentroid.y - t2.y) > maxDriftPx
                        ) {
                            resetToIdle("Tap 3 drift too large")
                            return
                        }

                        // 三指三击判定成功！
                        log("Three-finger triple tap SUCCESS! Toggling rotation lock state...")
                        onToggleLockAction()
                        resetToIdle("Triple tap execution complete")
                    }

                    else -> {
                        resetToIdle("Unexpected state $currentState on UP")
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                resetToIdle("ACTION_CANCEL")
            }
        }
    }

    private fun getCentroid(event: MotionEvent, count: Int): Point {
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until count) {
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        return Point(sumX / count, sumY / count)
    }

    private fun resetToIdle(reason: String) {
        if (currentState != State.IDLE) {
            // log("Reset: $reason")
        }
        currentState = State.IDLE
        tap1Centroid = null
        tap2Centroid = null
        tapStartCentroid = null
        lastCurrentCentroid = null
        isTapDisqualified = false
        maxPointersInTap = 0
    }

    private fun getDisplayDensity(): Float {
        val ctx = contextProvider()
        return ctx?.resources?.displayMetrics?.density
            ?: android.content.res.Resources.getSystem().displayMetrics.density
    }
}
