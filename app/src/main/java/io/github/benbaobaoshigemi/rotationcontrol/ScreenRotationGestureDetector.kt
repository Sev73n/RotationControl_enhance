package io.github.benbaobaoshigemi.rotationcontrol

import android.content.Context
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.Surface
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 屏幕旋转手势检测器：
 * 第一性物理原则：“手指连线应该平行于目标的显示顶部”。
 * 
 * 核心算法：
 * 1. 无论屏幕当前显示角度（0°、90°、180°、270°）为何，通过显示旋转矩阵将触控采样点
 *    逆变换还原至【平板机身绝对物理硬件坐标系】；
 * 2. 两次点击共 4 个触控点，计算物理均值 (X_phys_avg, Y_phys_avg)；
 * 3. 计算物理双指跨度 DeltaX_phys 与 DeltaY_phys：
 *    - DeltaX_phys > DeltaY_phys（手指连线平行于机身短边：顶/底边）：
 *      - 靠近物理底边（充电口端） -> 物理底边成为目标显示顶部 (ROTATION_180)
 *      - 靠近物理顶边（摄像头端） -> 物理顶边成为目标显示顶部 (ROTATION_0)
 *    - DeltaY_phys > DeltaX_phys（手指连线平行于机身长边：左/右边）：
 *      - 靠近物理右边（音量键端） -> 物理右边成为目标显示顶部 (ROTATION_90)
 *      - 靠近物理左边（磁吸端）   -> 物理左边成为目标显示顶部 (ROTATION_270)
 */
class ScreenRotationGestureDetector(
    private val contextProvider: () -> Context?,
    private val suppressionReason: () -> String? = { null },
    private val onRotateAction: (targetRotation: Int, reason: String) -> Unit
) {
    companion object {
        private const val TAG = "LSPosed-RotationGesture"

        // 阈值定义（严谨过滤长按与普通滑动，基于物理像素）
        private const val MAX_TAP_DURATION_MS = 400L    // 单次敲击时长上限，超过视为长按/按压，丢弃
        private const val MAX_DOUBLE_TAP_GAP_MS = 450L  // 两次点击之间的最大间隔时间，超过重置
        private const val MAX_SLOP_DP = 80f             // 单次点击中允许的最大位移（dp），防滑动/捏合
        private const val MAX_DRIFT_DP = 180f           // 两次点击中心点之间的最大漂移（dp），保证在同一物理区域
    }

    private enum class State {
        IDLE,
        IN_TAP_1,
        WAITING_FOR_TAP_2,
        IN_TAP_2
    }

    private data class Point(val x: Float, val y: Float)

    private data class TapRecord(
        val pA_phys: Point,
        val pB_phys: Point,
        val centroid_phys: Point,
        val deltaX_phys: Float,
        val deltaY_phys: Float,
        val timeDown: Long,
        val timeUp: Long
    )

    private var currentState = State.IDLE

    // 当前敲击过程中的状态跟踪
    private var tapDownTime: Long = 0L
    private var maxPointersInTap: Int = 0
    private var isDisqualified: Boolean = false

    // 双指在机身绝对物理坐标系下的记录
    private var p0StartPhys: Point? = null
    private var p1StartPhys: Point? = null
    private var p0CurrentPhys: Point? = null
    private var p1CurrentPhys: Point? = null

    // 第一次敲击物理记录
    private var tap1Record: TapRecord? = null

    @Synchronized
    fun onPointerEvent(event: MotionEvent) {
        val actionMasked = event.actionMasked
        val pointerCount = event.pointerCount
        val now = SystemClock.uptimeMillis()
        val density = getDisplayDensity()
        val maxSlopPx = MAX_SLOP_DP * density
        val maxDriftPx = MAX_DRIFT_DP * density

        // 获取当前屏幕显示旋转和尺寸，将事件坐标立即逆变换为机身绝对物理坐标
        val currentRotation = getCurrentRotation()
        val (wDisp, hDisp) = getScreenDimensions()

        // 更新单次敲击中出现过的最大指针数
        if (pointerCount > maxPointersInTap) {
            maxPointersInTap = pointerCount
        }

        // 若检测到 3 指及以上，立即废弃本手势（避免干扰系统三指截屏/分屏）
        if (pointerCount >= 3) {
            isDisqualified = true
            resetToIdle("Disqualified: pointerCount >= 3 ($pointerCount)")
            return
        }

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 如果处于等待第二次点击状态，检查间隔是否超时
                if (currentState == State.WAITING_FOR_TAP_2) {
                    val gap = now - (tap1Record?.timeUp ?: 0L)
                    if (gap > MAX_DOUBLE_TAP_GAP_MS) {
                        currentState = State.IDLE
                        tap1Record = null
                    }
                }

                if (currentState == State.IDLE) {
                    currentState = State.IN_TAP_1
                } else if (currentState == State.WAITING_FOR_TAP_2) {
                    currentState = State.IN_TAP_2
                }

                tapDownTime = now
                maxPointersInTap = 1
                isDisqualified = false

                val p0Phys = toPhysicalPoint(event.getX(0), event.getY(0), currentRotation, wDisp, hDisp)
                p0StartPhys = p0Phys
                p0CurrentPhys = p0Phys
                p1StartPhys = null
                p1CurrentPhys = null
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指触碰屏幕
                if (pointerCount == 2) {
                    val p0Phys = toPhysicalPoint(event.getX(0), event.getY(0), currentRotation, wDisp, hDisp)
                    val p1Phys = toPhysicalPoint(event.getX(1), event.getY(1), currentRotation, wDisp, hDisp)
                    if (p0StartPhys == null) p0StartPhys = p0Phys
                    p0CurrentPhys = p0Phys
                    p1StartPhys = p1Phys
                    p1CurrentPhys = p1Phys

                    XposedBridge.log(
                        "[$TAG] Two fingers down: p0_phys=(${p0Phys.x.toInt()}, ${p0Phys.y.toInt()}), p1_phys=(${p1Phys.x.toInt()}, ${p1Phys.y.toInt()}) [DisplayRotation=$currentRotation, State=$currentState]"
                    )
                } else if (pointerCount > 2) {
                    isDisqualified = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDisqualified) return
                if (pointerCount == 2) {
                    val p0Phys = toPhysicalPoint(event.getX(0), event.getY(0), currentRotation, wDisp, hDisp)
                    val p1Phys = toPhysicalPoint(event.getX(1), event.getY(1), currentRotation, wDisp, hDisp)
                    p0CurrentPhys = p0Phys
                    p1CurrentPhys = p1Phys

                    // 检查绝对物理位移（严格防止滑动或捏合误触）
                    p0StartPhys?.let { start0 ->
                        if (hypot(p0Phys.x - start0.x, p0Phys.y - start0.y) > maxSlopPx) {
                            isDisqualified = true
                            resetToIdle("Disqualified: p0 physical slop exceeded")
                            return
                        }
                    }
                    p1StartPhys?.let { start1 ->
                        if (hypot(p1Phys.x - start1.x, p1Phys.y - start1.y) > maxSlopPx) {
                            isDisqualified = true
                            resetToIdle("Disqualified: p1 physical slop exceeded")
                            return
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 双指中的某一根手指抬起，保留抬起瞬间的最后有效物理双指坐标
                if (pointerCount == 2 && p0CurrentPhys != null && p1CurrentPhys != null) {
                    p0CurrentPhys = toPhysicalPoint(event.getX(0), event.getY(0), currentRotation, wDisp, hDisp)
                    p1CurrentPhys = toPhysicalPoint(event.getX(1), event.getY(1), currentRotation, wDisp, hDisp)
                }
            }

            MotionEvent.ACTION_UP -> {
                // 全部手指离开屏幕，验证本单次点击是否合规
                val duration = now - tapDownTime
                val isTapValid = !isDisqualified &&
                        maxPointersInTap == 2 &&
                        duration <= MAX_TAP_DURATION_MS &&
                        p0CurrentPhys != null &&
                        p1CurrentPhys != null

                if (!isTapValid) {
                    resetToIdle("Tap invalid (duration=$duration ms, maxPointers=$maxPointersInTap, disq=$isDisqualified)")
                    return
                }

                val pA = p0CurrentPhys!!
                val pB = p1CurrentPhys!!
                val centroid = Point((pA.x + pB.x) / 2f, (pA.y + pB.y) / 2f)
                val deltaX = abs(pA.x - pB.x)
                val deltaY = abs(pA.y - pB.y)

                val tapRecord = TapRecord(
                    pA_phys = pA,
                    pB_phys = pB,
                    centroid_phys = centroid,
                    deltaX_phys = deltaX,
                    deltaY_phys = deltaY,
                    timeDown = tapDownTime,
                    timeUp = now
                )

                if (currentState == State.IN_TAP_1) {
                    // 第一次双指敲击顺利通过，进入等待第二次敲击阶段
                    tap1Record = tapRecord
                    currentState = State.WAITING_FOR_TAP_2
                    XposedBridge.log(
                        "[$TAG] Tap 1 detected: physical centroid=(${centroid.x.toInt()}, ${centroid.y.toInt()}), span=(dx=${deltaX.toInt()}, dy=${deltaY.toInt()}), duration=$duration ms"
                    )
                } else if (currentState == State.IN_TAP_2) {
                    // 第二次双指敲击完成！检查两次点击之间的中心位置物理漂移
                    val t1 = tap1Record
                    if (t1 == null) {
                        resetToIdle("Tap 2 without Tap 1 record")
                        return
                    }

                    val drift = hypot(centroid.x - t1.centroid_phys.x, centroid.y - t1.centroid_phys.y)
                    if (drift > maxDriftPx) {
                        resetToIdle("Tap 2 drift too large: $drift px > $maxDriftPx px")
                        return
                    }

                    val suppressed = suppressionReason()
                    if (suppressed != null) {
                        resetToIdle("Gesture suppressed: $suppressed")
                        return
                    }

                    // 校验全部通过：执行第一性物理指向决策！
                    XposedBridge.log("[$TAG] Tap 2 confirmed! drift=$drift px. Resolving target display top...")
                    processGestureResult(t1, tapRecord, wDisp, hDisp)
                    resetToIdle("Gesture execution complete")
                } else {
                    resetToIdle("Unexpected ACTION_UP in state $currentState")
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                resetToIdle("ACTION_CANCEL received")
            }
        }
    }

    /**
     * 第一性物理决策：
     * “手指连线应该平行于目标的显示顶部”
     */
    private fun processGestureResult(t1: TapRecord, t2: TapRecord, wDisp: Float, hDisp: Float) {
        // 1. 机身绝对物理尺寸（平板物理短边 W_phys，长边 H_phys）
        val wPhys = min(wDisp, hDisp)
        val hPhys = max(wDisp, hDisp)
        val centerPhysX = wPhys / 2.0f
        val centerPhysY = hPhys / 2.0f

        // 2. 计算两点，共四次点击在机身绝对物理坐标系下的平均坐标
        val avgPhysX = (t1.pA_phys.x + t1.pB_phys.x + t2.pA_phys.x + t2.pB_phys.x) / 4.0f
        val avgPhysY = (t1.pA_phys.y + t1.pB_phys.y + t2.pA_phys.y + t2.pB_phys.y) / 4.0f

        // 3. 计算双指在机身绝对物理坐标系下的平均跨度（连线方向向量）
        val meanDeltaXPhys = (t1.deltaX_phys + t2.deltaX_phys) / 2.0f
        val meanDeltaYPhys = (t1.deltaY_phys + t2.deltaY_phys) / 2.0f

        XposedBridge.log(
            "[$TAG] First-Principles: avgPhys=(${avgPhysX.toInt()}, ${avgPhysY.toInt()}), spanPhys=(dx=${meanDeltaXPhys.toInt()}, dy=${meanDeltaYPhys.toInt()}), panelPhys=(${wPhys.toInt()}x${hPhys.toInt()})"
        )

        // 4. 指向判定：
        if (meanDeltaXPhys > meanDeltaYPhys) {
            // 手指连线平行于机身短边（水平连线） -> 目标显示顶部在机身顶端或底端
            val isBottom = avgPhysY > centerPhysY
            val targetRotation = if (isBottom) Surface.ROTATION_180 else Surface.ROTATION_0
            val reason = if (isBottom) {
                "Fingers parallel to Bottom Edge (avgPhysY=${avgPhysY.toInt()} > ${centerPhysY.toInt()}) -> Physical Bottom becomes display TOP (ROTATION_180)"
            } else {
                "Fingers parallel to Top Edge (avgPhysY=${avgPhysY.toInt()} <= ${centerPhysY.toInt()}) -> Physical Top becomes display TOP (ROTATION_0)"
            }
            onRotateAction(targetRotation, reason)
        } else {
            // 手指连线平行于机身长边（垂直连线） -> 目标显示顶部在机身右侧边或左侧边
            val isRight = avgPhysX > centerPhysX
            val targetRotation = if (isRight) Surface.ROTATION_90 else Surface.ROTATION_270
            val reason = if (isRight) {
                "Fingers parallel to Right Edge (avgPhysX=${avgPhysX.toInt()} > ${centerPhysX.toInt()}) -> Physical Right becomes display TOP (ROTATION_90)"
            } else {
                "Fingers parallel to Left Edge (avgPhysX=${avgPhysX.toInt()} <= ${centerPhysX.toInt()}) -> Physical Left becomes display TOP (ROTATION_270)"
            }
            onRotateAction(targetRotation, reason)
        }
    }

    /**
     * 将当前显示旋转下的触控点 (x_disp, y_disp) 逆变换还原回【平板机身绝对物理硬件坐标系】 (x_phys, y_phys)
     */
    private fun toPhysicalPoint(xDisp: Float, yDisp: Float, rotation: Int, wDisp: Float, hDisp: Float): Point {
        return when (rotation) {
            Surface.ROTATION_0 -> Point(xDisp, yDisp)
            Surface.ROTATION_90 -> Point(hDisp - yDisp, xDisp)
            Surface.ROTATION_180 -> Point(wDisp - xDisp, hDisp - yDisp)
            Surface.ROTATION_270 -> Point(yDisp, wDisp - xDisp)
            else -> Point(xDisp, yDisp)
        }
    }

    private fun resetToIdle(reason: String) {
        if (currentState != State.IDLE) {
            XposedBridge.log("[$TAG] Reset to IDLE: $reason")
        }
        currentState = State.IDLE
        tap1Record = null
        p0StartPhys = null
        p1StartPhys = null
        p0CurrentPhys = null
        p1CurrentPhys = null
        isDisqualified = false
        maxPointersInTap = 0
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
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Failed to getRealMetrics from DisplayManager: ${t.message}")
            }
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
