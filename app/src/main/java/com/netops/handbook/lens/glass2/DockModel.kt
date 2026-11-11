package com.netops.handbook.lens.glass2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.netops.handbook.lens.Spring
import com.netops.handbook.lens.SpringSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Compose dock 的运动模型 + 手势状态机(2026-11-12 P2)。
 *
 * 弹簧与手势逻辑 1:1 移植自 LensBarView(真源 navigation_dock.dart,一个月
 * 真机打磨的参数一笔不动):pos/lift/glow/glowSpread/jelly 五根弹簧、
 * 100ms 速度投影落格、TOUCH_SLOP、多指保护、dropLift 双条件、wobble 果冻。
 *
 * 与旧实现的差异:**无捕获冷却**——新架构捕获走 GPU 同帧,不存在
 * 「按住抓帧节拍/抽搐」一族问题;Choreographer 循环由 Compose 侧驱动。
 */
class DockModel(
    val density: Float,
    private val haptics: () -> Unit,
    private val commit: (Int) -> Unit,
) {
    /* ---- 弹簧(FlClash navigation_dock.dart 同名同参) ---- */
    val pos = Spring(0.0)
    val lift = Spring(0.0)
    val glow = Spring(0.0)
    val glowSpread = Spring(0.0)
    val jelly = Spring(0.0)

    companion object {
        private const val LIFT_SNAP = 0.975
        private const val LIFT_RELEASE = 0.04
        private const val TOUCH_SLOP_DP = 8f
        private const val FLING_MS = 100f
        private val FOLLOW = SpringSpec.ratio(10000.0, 1.0)
        private val TRAVEL = SpringSpec.durationBounce(320.0, 0.0)
        private val FAST_TRAVEL = SpringSpec.durationBounce(140.0, 0.02)
        private val LIFT_S = SpringSpec.durationBounce(250.0, 0.0)
        private val PRESS_S = SpringSpec.durationBounce(180.0, 0.0)
        private val JELLY_S = SpringSpec.ratio(100.0, 0.4)
        private val GLOW_IN = SpringSpec.durationBounce(100.0, 0.0)
        private val GLOW_OUT = SpringSpec.durationBounce(500.0, 0.0)
    }

    /* ---- 手势状态 ---- */
    var pressed = false; private set
    var dragging = false; private set
    private var dropping = false
    private var downId = 0
    private var downX = 0f
    private var lastX = 0f
    private var lastMoveMs = 0L
    private var velIndexPerMs = 0f
    var pressedIndex = 0; private set
    var selectedIndex = 0

    /* ---- 弹簧值快照(Compose State,tick 写入;静止不变 → 零重绘) ---- */
    var posV by mutableFloatStateOf(0f); private set
    var liftV by mutableFloatStateOf(0f); private set
    var glowV by mutableFloatStateOf(0f); private set
    var glowSpreadV by mutableFloatStateOf(0f); private set
    var jellyV by mutableFloatStateOf(0f); private set

    /* ---- 几何(composable 每次几何状态变化时回填) ---- */
    var count = 4
    var dockVertical = false
    var dockedStyle = false
    var axisFirst = 0f
    var axisPitch = 0f
    var pillW = 0f
    var pillH = 0f
    var pillCy = 0f

    /* ---- 触点(玻璃高光位置,dock 本地) ---- */
    var glowX = 0f; private set
    var glowY = 0f; private set

    /** 是否需要下一帧(Compose 帧循环据此自眠)。 */
    val animating: Boolean
        get() = pos.active() || lift.active() || glow.active()
                || glowSpread.active() || jelly.active() || pressed

    fun nowSec(): Double = System.nanoTime() / 1e9

    /* ================= 手势入口(Compose pointerInput 调用) ================= */

    /** 是否接管该手势:药丸带内(竖排=整条轨,与旧 rail 命中口径一致)。
     *  坐标 = 窗口本地(与 axisFirst 同基准)。 */
    fun inPillBand(y: Float): Boolean {
        if (dockVertical) return true
        val hh = pillH / 2f
        return y >= pillCy - hh && y <= pillCy + hh
    }

    /** 触摸守卫查询:当前跟拍的手指 id。 */
    fun activePointer(): Int = downId

    /** 跟拍手指已抬起而其他指还在(旧 findPointerIndex<0 分支):按取消收场。 */
    fun onPointerLost() {
        if (!pressed) return
        pressed = false
        dragging = false
        pos.springTo(selectedIndex.toDouble(),
            if (dockVertical) FAST_TRAVEL else TRAVEL, nowSec())
    }

    fun onDown(x: Float, y: Float, pointerId: Int, eventTimeMs: Long) {
        pressed = true
        dragging = false
        dropping = false
        downId = pointerId
        glowX = x; glowY = y
        val now = nowSec()
        glowSpread.springTo(0.0, FAST_TRAVEL, now)
        glow.springTo(1.0, GLOW_IN, now)
        downX = hitAxis(x, y)
        lastX = downX
        lastMoveMs = eventTimeMs
        velIndexPerMs = 0f
        pressedIndex = max(0, min(count - 1, round(positionAtAxis(hitAxis(x, y))).toInt()))
        pos.springTo(pressedIndex.toDouble(),
            if (dockVertical) FAST_TRAVEL else TRAVEL, now)
        /* 贴底不做按压胀大(2026-11-11 拍板),悬浮胶囊保留抬起 */
        if (!dockVertical && !dockedStyle) lift.springTo(1.0, PRESS_S, now)
    }

    fun onMove(x: Float, y: Float, pointerId: Int, eventTimeMs: Long): Boolean {
        if (!pressed) return false
        if (pointerId != downId) return true   // 多指:只跟起手那根
        val now = nowSec()
        glowX = x; glowY = y
        val a = hitAxis(x, y)
        val t = eventTimeMs
        val dt = t - lastMoveMs
        if (dt > 0) {
            val v = (a - lastX) / axisPitch / dt
            velIndexPerMs = (velIndexPerMs * 0.6f + v * 0.4f)
            lastMoveMs = t
            lastX = a
        }
        if (!dragging && abs(a - downX) < TOUCH_SLOP_DP * density) return true
        dragging = true
        pos.springTo(positionAtAxis(a).toDouble(), FOLLOW, now)
        val idx = max(0, min(count - 1, round(positionAtAxis(a)).toInt()))
        if (idx != pressedIndex) {
            pressedIndex = idx
            haptics()
        }
        return true
    }

    /** @return true=事件已消费(不再转发) */
    fun onUp(cancelled: Boolean): Boolean {
        if (!pressed) return false
        pressed = false
        val now = nowSec()
        glow.springTo(0.0, GLOW_OUT, now)
        glowSpread.springTo(1.0, GLOW_OUT, now)
        if (!cancelled) {
            val lead = velIndexPerMs * FLING_MS
            var target = round(pos.target() + lead).toInt()
            target = max(pressedIndex - 1, min(pressedIndex + 1, target))
            target = max(0, min(count - 1, target))
            pressedIndex = target
            /* ❗selectedIndex 立即跟走(旧 commit 同语义):否则灰态图标循环
               仍把旧格画成主色,松手后旧格残留蓝色(2026-11-12 首验)。 */
            selectedIndex = target
            pos.springTo(target.toDouble(), if (dockVertical) FAST_TRAVEL else TRAVEL, now)
            commit(target)
        } else {
            pos.springTo(selectedIndex.toDouble(), if (dockVertical) FAST_TRAVEL else TRAVEL, now)
        }
        dragging = false
        dropping = !dockVertical
        if (dockVertical) lift.springTo(0.0, FAST_TRAVEL, now)
        return true
    }

    fun onCancel() { onUp(cancelled = true) }

    /** 页面侧选择变化(桥 setSelectedIndex):非按压期弹簧跟进。 */
    fun syncSelected(index: Int) {
        selectedIndex = index
        if (!pressed && round(pos.target()).toInt() != selectedIndex) {
            pos.springTo(selectedIndex.toDouble(),
                if (dockVertical) FAST_TRAVEL else TRAVEL, nowSec())
        }
    }

    /* ================= 每帧推进 ================= */

    fun tick() {
        val now = nowSec()
        pos.advance(now)
        wobble(now)
        jelly.advance(now)
        lift.advance(now)
        glow.advance(now)
        glowSpread.advance(now)
        dropLift(now)
        posV = pos.value().toFloat()
        liftV = lift.value().toFloat()
        glowV = glow.value().toFloat()
        glowSpreadV = glowSpread.value().toFloat()
        jellyV = jelly.value().toFloat()
    }

    /** 果冻:拖拽期由速度+刹车耦合(真源 navigation_dock.dart,移植自旧 wobble)。 */
    private fun wobble(now: Double) {
        var target = 0.0
        if (dragging && !dockedStyle) {
            val v = pos.velocity()
            val acc = pos.acceleration()
            val braking = max(0.0, -acc * sign(v))
            target = 0.20 * tanh(abs(v) / 3.0) - 0.15 * tanh(braking / 25.0)
        }
        if (abs(target - jelly.target()) > 1e-4) jelly.springTo(target, JELLY_S, now)
    }

    /** UIKit 行为:升到位 + 移动到格,两个条件都满足才允许回落。 */
    private fun dropLift(now: Double) {
        if (!dropping || pressed) return
        if (lift.value() < LIFT_SNAP) return
        if (abs(pos.target() - pos.value()) > LIFT_RELEASE) return
        dropping = false
        lift.springTo(0.0, LIFT_S, now)
    }

    /* ---- 轴向工具 ---- */
    private fun hitAxis(x: Float, y: Float) = if (dockVertical) y else x

    /** 硬夹首末格,不做橡皮筋(与网页 indexAt 同口径)。 */
    fun positionAtAxis(axisCoord: Float): Float {
        val raw = (axisCoord - axisFirst) / axisPitch
        return max(0f, min((count - 1).toFloat(), raw))
    }

    fun cellAxis(i: Int) = axisFirst + axisPitch * i
}
