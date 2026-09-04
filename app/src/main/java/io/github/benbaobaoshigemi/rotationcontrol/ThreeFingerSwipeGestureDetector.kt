package io.github.benbaobaoshigemi.rotationcontrol

import android.content.Context
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs

/**
 * 显示底边三指横扫手势检测器：
 * 
 * 核心逻辑与防系统手势干涉准则：
 * 1. 严格限定在当前显示底边极窄区域（屏幕底部 25%：Y >= H * 0.75）；
 * 2. 严格防止干涉系统竖向三指动作（三指下滑截屏、三指上滑分屏）：
 *    - 垂直方向设置硬性位移熔断上限 MAX_VERTICAL_DEVIATION_DP (40 dp)；
 *    - 若检测到竖向位移超过 40 dp 或垂直位移占比过高，立刻永久熔断废弃当前手势流（isDisqualified = true）；
 *    - 必须满足水平位移显著主导（|dx| >= 2.5 * |dy| 且 |dx| >= 100 dp）；
 * 3. 绝不拦截底层输入事件，仅作为被动无损观察者（PointerEventListener 不影响系统分发）；
 * 4. 底边跟随手势旋转 90 度：
 *    - 左扫：底边向左移，显示顺时针旋转 90 度 -> (currentRotation + 1) % 4
 *    - 右扫：底边向右移，显示逆时针旋转 90 度 -> (currentRotation + 3) % 4
 * 5. 包含 600ms 触发闭锁与防抖冷却机制。
 */
class ThreeFingerSwipeGestureDetector(
    private val contextProvider: () -> Context?,
    private val onRotateAction: (targetRotation: Int, reason: String) -> Unit
) {
    companion object {
        private const val TAG = "LSPosed-3FingerSwipe"

        // 区域与几何约束
        private const val BOTTOM_EDGE_RATIO = 0.75f               // 严格限定在屏幕底端 25% 区域
        private const val MIN_HORIZONTAL_SWIPE_DP = 100f          // 水平最小滑动位移阈值 (dp)
        private const val MAX_VERTICAL_DEVIATION_DP = 40f         // 垂直容差熔断上限 (dp)，超此位移立刻认定为竖向手势（截屏/分屏）并废弃
        private const val HORIZONTAL_DOMINANCE_RATIO = 2.5f       // 水平位移必须达到竖向位移的 2.5 倍以上（倾角 < 21.8°）
        private const val MAX_SWIPE_DURATION_MS = 1000L           // 手势最长有效时间 (ms)
        private const val DEBOUNCE_COOLDOWN_MS = 600L             // 触发后的冷却防抖窗口 (ms)
    }

    private var isTracking = false
    private var isDisqualified = false
    private var hasTriggeredInCurrentStream = false
    private var lastTriggerTime = 0L

    private var swipeStartTime = 0L
    private var startCentroidX = 0f
    private var startCentroidY = 0f

    // 记录每个 pointerId 的初始接触坐标
    private val pointerDownX = FloatArray(10)
    private val pointerDownY = FloatArray(10)

    private fun log(msg: String) {
        Log.i(TAG, msg)
        XposedBridge.log("[$TAG] $msg")
    }

    @Synchronized
    fun onPointerEvent(event: MotionEvent) {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerCount = event.pointerCount
        val now = SystemClock.uptimeMillis()

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pid = event.getPointerId(0)
                if (pid < pointerDownX.size) {
                    pointerDownX[pid] = event.getX(0)
                    pointerDownY[pid] = event.getY(0)
                }
                isTracking = false
                isDisqualified = false
                hasTriggeredInCurrentStream = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val pid = event.getPointerId(actionIndex)
                if (pid < pointerDownX.size) {
                    pointerDownX[pid] = event.getX(actionIndex)
                    pointerDownY[pid] = event.getY(actionIndex)
                }

                if (pointerCount == 3) {
                    // 冷却防抖检查
                    if (now - lastTriggerTime < DEBOUNCE_COOLDOWN_MS) {
                        return
                    }

                    val (_, hDisp) = getScreenDimensions()
                    val bottomThreshold = hDisp * BOTTOM_EDGE_RATIO

                    // 获取三指起始重心坐标
                    val id0 = event.getPointerId(0)
                    val id1 = event.getPointerId(1)
                    val id2 = event.getPointerId(2)

                    val initX = if (id0 < 10 && id1 < 10 && id2 < 10) {
                        (pointerDownX[id0] + pointerDownX[id1] + pointerDownX[id2]) / 3f
                    } else {
                        (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                    }

                    val initY = if (id0 < 10 && id1 < 10 && id2 < 10) {
                        (pointerDownY[id0] + pointerDownY[id1] + pointerDownY[id2]) / 3f
                    } else {
                        (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
                    }

                    // 必须满足在当前显示画面的底部 25% 区域内起始
                    if (initY >= bottomThreshold) {
                        isTracking = true
                        isDisqualified = false
                        hasTriggeredInCurrentStream = false
                        swipeStartTime = now
                        startCentroidX = initX
                        startCentroidY = initY

                        log("3 fingers placed in bottom edge zone: centroid=(${initX.toInt()}, ${initY.toInt()}), threshold=${bottomThreshold.toInt()}")
                    } else {
                        // 不在底边区域，彻底放弃监听，绝不干扰屏幕中上部的三指系统手势
                        isTracking = false
                        isDisqualified = true
                    }
                } else if (pointerCount > 3) {
                    // 超过 3 指，立刻废弃
                    isTracking = false
                    isDisqualified = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isTracking || isDisqualified || hasTriggeredInCurrentStream) return

                if (now - swipeStartTime > MAX_SWIPE_DURATION_MS) {
                    isTracking = false
                    isDisqualified = true
                    return
                }

                if (pointerCount >= 3) {
                    val currX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                    val currY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f

                    val deltaX = currX - startCentroidX
                    val deltaY = currY - startCentroidY
                    val density = getDisplayDensity()
                    val maxVerticalDevPx = MAX_VERTICAL_DEVIATION_DP * density
                    val minHorizontalPx = MIN_HORIZONTAL_SWIPE_DP * density

                    // 严格防干涉检查：若竖向位移超过容差，判定为截屏/分屏手势，立刻永久熔断
                    if (abs(deltaY) > maxVerticalDevPx) {
                        isTracking = false
                        isDisqualified = true
                        log("Vertical deviation exceeded limit (dy=${deltaY.toInt()} > ${maxVerticalDevPx.toInt()}px) -> Disqualified (Pass through to system vertical gestures)")
                        return
                    }

                    // 必须水平位移达到阈值，且水平主导性达到 2.5 倍以上
                    if (abs(deltaX) >= minHorizontalPx && abs(deltaX) >= HORIZONTAL_DOMINANCE_RATIO * abs(deltaY)) {
                        checkAndTrigger(deltaX, deltaY, now)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 手指抬起瞬间的末端判定
                if (isTracking && !isDisqualified && !hasTriggeredInCurrentStream && pointerCount == 3) {
                    val currX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                    val currY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f

                    val deltaX = currX - startCentroidX
                    val deltaY = currY - startCentroidY
                    val density = getDisplayDensity()
                    val maxVerticalDevPx = MAX_VERTICAL_DEVIATION_DP * density
                    val minHorizontalPx = MIN_HORIZONTAL_SWIPE_DP * density

                    if (abs(deltaY) <= maxVerticalDevPx &&
                        abs(deltaX) >= minHorizontalPx &&
                        abs(deltaX) >= HORIZONTAL_DOMINANCE_RATIO * abs(deltaY)
                    ) {
                        checkAndTrigger(deltaX, deltaY, now)
                    }
                }
                if (pointerCount <= 3) {
                    isTracking = false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTracking = false
                isDisqualified = false
                hasTriggeredInCurrentStream = false
            }
        }
    }

    private fun checkAndTrigger(deltaX: Float, deltaY: Float, now: Long) {
        hasTriggeredInCurrentStream = true
        isTracking = false
        lastTriggerTime = now

        val currentRotation = getCurrentRotation()
        val isLeftSwipe = deltaX < 0

        // 左扫：顺时针 +90° ((current + 1) % 4)
        // 右扫：逆时针 -90° ((current + 3) % 4)
        val targetRotation = if (isLeftSwipe) {
            (currentRotation + 1) % 4
        } else {
            (currentRotation + 3) % 4
        }

        val directionStr = if (isLeftSwipe) "LEFT (Clockwise +90°)" else "RIGHT (Counter-Clockwise -90°)"
        val reason = "3-finger swipe $directionStr at display bottom (dx=${deltaX.toInt()}, dy=${deltaY.toInt()}, $currentRotation -> $targetRotation)"

        log("Triggered! $reason")
        onRotateAction(targetRotation, reason)
    }

    private fun getCurrentRotation(): Int {
        val ctx = contextProvider()
        if (ctx != null) {
            try {
                val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (display != null) {
                    return display.rotation
                }
            } catch (_: Throwable) {}
        }
        return Surface.ROTATION_0
    }

    private fun getScreenDimensions(): Pair<Float, Float> {
        val ctx = contextProvider()
        if (ctx != null) {
            try {
                val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(metrics)
                    return Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                }
            } catch (_: Throwable) {}
        }
        val sysMetrics = android.content.res.Resources.getSystem().displayMetrics
        return Pair(sysMetrics.widthPixels.toFloat(), sysMetrics.heightPixels.toFloat())
    }

    private fun getDisplayDensity(): Float {
        val ctx = contextProvider()
        return ctx?.resources?.displayMetrics?.density
            ?: android.content.res.Resources.getSystem().displayMetrics.density
    }
}
