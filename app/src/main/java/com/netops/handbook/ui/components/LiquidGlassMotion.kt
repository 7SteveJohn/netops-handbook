// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from Kyant0/AndroidLiquidGlass & compose-miuix-ui (Apache-2.0).

package com.netops.handbook.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.tanh

/**
 * Liquid Glass 阻尼水滴拖拽与弹性升起控制器：
 * - 按下立即触发凸透镜水滴膨胀（pressedScale）与折射渐入（pressProgress -> 1f）；
 * - 快速点击时卡住两个到位门控（升起 >= 0.80 且滑行误差 < 3%）后再用欠阻尼流体弹簧回落，还原真实水珠表面张力震颤；
 * - 拖拽时采用连续增量更新（无 touchSlop 半格瞬移跳变），并支持水平/垂直双轴归一化速度驱动体积守恒拉伸挤压。
 */
internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    val visibilityThreshold: Float = 0.001f,
    val initialScale: Float = 1f,
    val pressedScale: Float = 74f / 54f,
    val isVertical: Boolean = false,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit = {},
    val onDragStopped: DampedDragAnimation.() -> Unit = {},
    val onDragCancelled: DampedDragAnimation.() -> Unit = onDragStopped,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit = { _, _ -> },
) {
    private val valueAnimationSpec = spring(1f, 880f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(0.92f, 900f, 0.001f)
    private val releaseProgressAnimationSpec = spring(0.58f, 380f, 0.001f)
    private val scaleXAnimationSpec = spring(0.50f, 300f, 0.001f)
    private val scaleYAnimationSpec = spring(0.56f, 300f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private var pressJob: Job? = null
    private var releaseJob: Job? = null
    private var valueJob: Job? = null
    private val velocityTracker = VelocityTracker()
    private var latestTarget: Float = initialValue

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = latestTarget
    val pressProgress: Float get() = pressProgressAnimation.value.coerceIn(0f, 1f)
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(this) {
        inspectDockGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragCancelled()
                release()
            },
        ) { change, dragAmount ->
            if (canDrag(change.position) || canDrag(change.previousPosition)) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        releaseJob?.cancel()
        pressJob?.cancel()
        velocityTracker.resetTracking()
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            withFrameMillis { }
            // 门控 1：快速轻点时等水滴明显升起，避免手指抬起过快导致透镜未显形就憋回
            if (pressProgressAnimation.value < 0.80f) {
                withTimeoutOrNull(180L) {
                    snapshotFlow { pressProgressAnimation.value }.first { it >= 0.80f }
                }
            }
            pressJob?.cancel()
            if (pressProgressAnimation.value < 0.80f) {
                pressProgressAnimation.snapTo(0.80f)
                val minPeakScale = initialScale + (pressedScale - initialScale) * 0.80f
                if ((pressedScale >= initialScale && scaleXAnimation.value < minPeakScale) ||
                    (pressedScale < initialScale && scaleXAnimation.value > minPeakScale)
                ) {
                    scaleXAnimation.snapTo(minPeakScale)
                    scaleYAnimation.snapTo(minPeakScale)
                }
            }
            // 门控 2：跨格飞行中保持凸起水滴形态，抵达目标格附近再回落
            val threshold = ((valueRange.endInclusive - valueRange.start) * 0.03f).coerceAtLeast(0.04f)
            if (abs(value - latestTarget) >= threshold) {
                withTimeoutOrNull(420L) {
                    snapshotFlow { valueAnimation.value }.first { abs(it - latestTarget) < threshold }
                }
            }
            launch { pressProgressAnimation.animateTo(0f, releaseProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
            launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
        }
    }

    fun updateValue(newValue: Float) {
        val clamped = newValue.coerceIn(valueRange)
        latestTarget = clamped
        valueJob?.cancel()
        valueJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.animateTo(clamped, valueAnimationSpec) { updateVelocity() }
        }
    }

    fun snapToValue(newValue: Float) {
        val clamped = newValue.coerceIn(valueRange)
        latestTarget = clamped
        valueJob?.cancel()
        valueJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.snapTo(clamped)
        }
    }

    fun animateToValue(newValue: Float) {
        val clamped = newValue.coerceIn(valueRange)
        latestTarget = clamped
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                valueJob?.cancel()
                valueJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    valueAnimation.animateTo(clamped, valueAnimationSpec) { updateVelocity() }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        val samplePos = if (isVertical) Offset(0f, value) else Offset(value, 0f)
        velocityTracker.addPosition(SystemClock.uptimeMillis(), samplePos)
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
        val rawVel = velocityTracker.calculateVelocity()
        val targetVelocity = (if (isVertical) rawVel.y else rawVel.x) / span
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            velocityAnimation.snapTo(targetVelocity)
        }
    }
}

/**
 * 独立悬浮液态水滴按钮物理控制器（对标 Liquid Glass / Kyant0 LiquidButton）：
 * - 指针按下立即触发 `pressProgress -> 1f` 与 `scale -> pressedScale` 凸透镜水珠鼓起；
 * - 真机快速轻点门控（<45ms）：抬手时若 `pressProgress < 0.84f`，先经 `withTimeoutOrNull(130L)` 等待或推升至
 *   `>= 0.84f` 让水珠完整鼓起显形，随后以欠阻尼流体弹簧 `spring(0.46f, 340f)` 回落至 `0f` / `1f` 产生 Q 弹震颤；
 * - 手指拖拽时提供 `tanh(delta / maxPull) * maxPull` 各向同性阻尼牵引与基于 `atan2` 的流体体积守恒拉伸挤压（`0f..0.16f`）；
 * - 外扩 `24.dp` 热区容差内抬手触发 `onTap`，被父容器拦截取消时快速无过冲收敛且不触发 `onTap`；
 * - 与 `InteractiveHighlight` 共享 AGSL `SPOT_SHADER` 指尖聚光着色能力。
 */
internal class LiquidOrbController(
    private val animationScope: CoroutineScope,
    val pressedScale: Float = 1.32f,
    val maxPullPx: () -> Float = { 28f },
    var onTap: (() -> Unit)? = null,
) {
    var onPressStart: (() -> Unit)? = null

    private val pressProgressSpec = spring(dampingRatio = 0.72f, stiffness = 820f, visibilityThreshold = 0.001f)
    private val pressScaleSpec = spring(dampingRatio = 0.62f, stiffness = 720f, visibilityThreshold = 0.001f)
    private val releaseFluidSpec = spring(dampingRatio = 0.46f, stiffness = 340f, visibilityThreshold = 0.001f)
    private val releaseOffsetSpec = spring(dampingRatio = 0.46f, stiffness = 340f, visibilityThreshold = Offset.VisibilityThreshold)
    private val cancelScalarSpec = spring(dampingRatio = 0.92f, stiffness = 650f, visibilityThreshold = 0.001f)
    private val cancelOffsetSpec = spring(dampingRatio = 0.92f, stiffness = 650f, visibilityThreshold = Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleAnimation = Animatable(1f, 0.001f)
    private val pullOffsetAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private val stretchAmountAnimation = Animatable(0f, 0.001f)
    private val spotPositionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var angleRadState by mutableFloatStateOf(0f)
    private var downPosition = Offset.Zero
    private var pressJob: Job? = null
    private var settleJob: Job? = null

    private val spotShader: RuntimeShader? = createSpotShaderOrNull()

    val pressProgress: Float get() = pressProgressAnimation.value.coerceIn(0f, 1f)
    val scale: Float get() = scaleAnimation.value
    val pullOffset: Offset get() = pullOffsetAnimation.value
    val stretchAngleRad: Float get() = angleRadState
    val stretchAmount: Float get() = stretchAmountAnimation.value.coerceIn(0f, 0.16f)
    val spotPosition: Offset get() = spotPositionAnimation.value

    /** 基于 `stretchAngleRad` 与 `stretchAmount` 预计算的体积守恒水平缩放因子（已乘以 `scale`）。 */
    val scaleX: Float
        get() {
            val axial = stretchAmount * cos(2f * stretchAngleRad)
            val factor = if (axial >= 0f) 1f + axial else 1f / (1f - axial)
            return scale * factor
        }

    /** 基于 `stretchAngleRad` 与 `stretchAmount` 预计算的体积守恒垂直缩放因子（已乘以 `scale`）。 */
    val scaleY: Float
        get() {
            val axial = stretchAmount * cos(2f * stretchAngleRad)
            val factor = if (axial >= 0f) 1f / (1f + axial) else 1f - axial
            return scale * factor
        }

    /** 手势捕获修饰符：含按下立即鼓起、阻尼牵引、外扩 24dp 热区点按判定与取消收敛。 */
    val gestureModifier: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            downPosition = down.position
            press(down.position)
            onPressStart?.invoke()

            var activePointerId: PointerId = down.id
            var cancelled = false
            var upChange: PointerInputChange? = null

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.fastFirstOrNull { it.id == activePointerId }
                if (change == null || change.isConsumed) {
                    cancelled = true
                    break
                }
                if (change.changedToUpIgnoreConsumed()) {
                    val otherPressed = event.changes.fastFirstOrNull { it.pressed }
                    if (otherPressed == null) {
                        upChange = change
                        break
                    } else {
                        downPosition = otherPressed.position - (change.position - downPosition)
                        activePointerId = otherPressed.id
                    }
                } else if (change.position != change.previousPosition) {
                    onPointerMove(change.position, size)
                    change.consume()
                }
            }

            if (cancelled || upChange == null) {
                cancel()
            } else {
                upChange.consume()
                val slopPx = 24.dp.toPx()
                val pos = upChange.position
                val withinSlop = pos.x >= -slopPx &&
                    pos.x <= size.width + slopPx &&
                    pos.y >= -slopPx &&
                    pos.y <= size.height + slopPx
                release()
                if (withinSlop) {
                    onTap?.invoke()
                }
            }
        }
    }

    /** AGSL `SPOT_SHADER` 跟随指尖聚光洗亮绘制修饰符（胶囊/圆球安全圆角，零离屏缓冲）。 */
    val highlightModifier: Modifier = Modifier.drawWithContent {
        drawContent()
        drawSpotHighlight(this)
    }

    /** 便捷组合修饰符（手势控制 + AGSL 指尖聚光洗亮）。 */
    val modifier: Modifier = gestureModifier.then(highlightModifier)

    /** 便捷几何形变层修饰符（应用阻尼牵引位移 + 流体体积守恒拉伸缩放）。 */
    val motionLayerModifier: Modifier = Modifier.graphicsLayer {
        val pull = pullOffset
        translationX = pull.x
        translationY = pull.y
        scaleX = this@LiquidOrbController.scaleX
        scaleY = this@LiquidOrbController.scaleY
    }

    /** 供 `drawBackdrop(onDrawFront = ...)` 或自定义 `DrawScope` 直接调用的 AGSL 聚光洗亮辅助函数。 */
    fun drawSpotHighlight(
        drawScope: DrawScope,
        maxSpotAlpha: Float = 0.16f,
        ambientAlpha: Float = 0.045f,
    ) = with(drawScope) {
        val p = pressProgress
        if (p > 0.002f) {
            val r = size.minDimension * 0.5f
            val cr = CornerRadius(r, r)
            if (ambientAlpha > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = (ambientAlpha * p).coerceIn(0f, 1f)),
                    cornerRadius = cr,
                    blendMode = BlendMode.Plus,
                )
            }
            val rawSpot = spotPosition
            val center = Offset(
                x = rawSpot.x.coerceIn(0f, size.width),
                y = rawSpot.y.coerceIn(0f, size.height),
            )
            val radius = (size.minDimension * 1.28f).coerceAtLeast(1f)
            val spotColor = Color.White.copy(alpha = (maxSpotAlpha * p).coerceIn(0f, 1f))
            drawAgslSpot(
                spotShader = spotShader,
                center = center,
                radius = radius,
                spotColor = spotColor,
                cornerRadius = cr,
            )
        }
    }

    fun press(initialTouch: Offset = Offset.Zero) {
        settleJob?.cancel()
        pressJob?.cancel()
        pressJob = animationScope.launch {
            launch(start = CoroutineStart.UNDISPATCHED) {
                spotPositionAnimation.snapTo(initialTouch)
            }
            launch { pressProgressAnimation.animateTo(1f, pressProgressSpec) }
            launch { scaleAnimation.animateTo(pressedScale, pressScaleSpec) }
        }
    }

    private fun onPointerMove(position: Offset, containerSize: IntSize) {
        val rawDelta = position - downPosition
        val maxPull = maxPullPx().coerceAtLeast(1f)
        val dist = rawDelta.getDistance()
        val dampedOffset = if (dist > 1e-4f) {
            val dampedMag = tanh(dist / maxPull) * maxPull
            Offset(rawDelta.x / dist * dampedMag, rawDelta.y / dist * dampedMag)
        } else {
            Offset.Zero
        }
        if (dist > 0.5f) {
            angleRadState = atan2(rawDelta.y, rawDelta.x)
        }
        val pullRatio = (dampedOffset.getDistance() / maxPull).coerceIn(0f, 1f)
        val targetStretch = (0.16f * pullRatio).coerceIn(0f, 0.16f)
        val clampedSpot = Offset(
            x = position.x.coerceIn(0f, containerSize.width.toFloat().coerceAtLeast(1f)),
            y = position.y.coerceIn(0f, containerSize.height.toFloat().coerceAtLeast(1f)),
        )
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pullOffsetAnimation.snapTo(dampedOffset)
            stretchAmountAnimation.snapTo(targetStretch)
            spotPositionAnimation.snapTo(clampedSpot)
        }
    }

    fun release() {
        settleJob?.cancel()
        settleJob = animationScope.launch {
            withFrameMillis { }
            // 核心快速轻点门控：若真机快点 (<45ms) 时水珠尚未充分鼓起，先在 130ms 内等待或推升至 >= 0.84f
            if (pressProgressAnimation.value < 0.84f) {
                withTimeoutOrNull(130L) {
                    if (pressJob?.isActive == true) {
                        snapshotFlow { pressProgressAnimation.value }.first { it >= 0.84f }
                    } else {
                        pressProgressAnimation.animateTo(0.86f, pressProgressSpec)
                    }
                }
            }
            pressJob?.cancel()
            if (pressProgressAnimation.value < 0.84f) {
                pressProgressAnimation.snapTo(0.84f)
            }
            val minPeakScale = 1f + (pressedScale - 1f) * 0.84f
            if ((pressedScale >= 1f && scaleAnimation.value < minPeakScale) ||
                (pressedScale < 1f && scaleAnimation.value > minPeakScale)
            ) {
                scaleAnimation.snapTo(minPeakScale)
            }
            launch { pressProgressAnimation.animateTo(0f, releaseFluidSpec) }
            launch { scaleAnimation.animateTo(1f, releaseFluidSpec) }
            launch { pullOffsetAnimation.animateTo(Offset.Zero, releaseOffsetSpec) }
            launch { stretchAmountAnimation.animateTo(0f, releaseFluidSpec) }
        }
    }

    fun cancel() {
        pressJob?.cancel()
        settleJob?.cancel()
        settleJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, cancelScalarSpec) }
            launch { scaleAnimation.animateTo(1f, cancelScalarSpec) }
            launch { pullOffsetAnimation.animateTo(Offset.Zero, cancelOffsetSpec) }
            launch { stretchAmountAnimation.animateTo(0f, cancelScalarSpec) }
        }
    }
}

/**
 * 栏体交互聚光光晕（跟随手指/药丸位置的加色柔光洗亮）。
 */
internal class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressSpec = spring(0.55f, 320f, 0.001f)
    private val posSpec = spring(0.55f, 320f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero

    private val spotShader: RuntimeShader? = createSpotShaderOrNull()

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value.coerceIn(0f, 1f)
        if (progress > 0.002f) {
            drawRect(
                color = Color.White.copy(alpha = 0.05f * progress),
                blendMode = BlendMode.Plus,
            )
            val pos = position(size, positionAnimation.value)
            val radius = (size.minDimension * 1.35f).coerceAtLeast(1f)
            val center = Offset(
                x = pos.x.coerceIn(0f, size.width),
                y = pos.y.coerceIn(0f, size.height),
            )
            val spotColor = Color.White.copy(alpha = 0.13f * progress)
            drawAgslSpot(
                spotShader = spotShader,
                center = center,
                radius = radius,
                spotColor = spotColor,
            )
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDockGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = { release() },
            onDragCancel = { release() },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }

    private fun release() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressSpec) }
            launch { positionAnimation.animateTo(startPosition, posSpec) }
        }
    }
}

private fun createSpotShaderOrNull(): RuntimeShader? =
    if (isRuntimeShaderSupported()) RuntimeShader(SPOT_SHADER) else null

private fun DrawScope.drawAgslSpot(
    spotShader: RuntimeShader?,
    center: Offset,
    radius: Float,
    spotColor: Color,
    cornerRadius: CornerRadius = CornerRadius.Zero,
) {
    val safeRadius = radius.coerceAtLeast(1f)
    if (spotShader != null) {
        spotShader.setColorUniform("color", spotColor)
        spotShader.setFloatUniform("radius", safeRadius)
        spotShader.setFloatUniform("position", center.x, center.y)
        val brush = spotShader.asBrush()
        if (cornerRadius == CornerRadius.Zero) {
            drawRect(brush = brush, blendMode = BlendMode.Plus)
        } else {
            drawRoundRect(brush = brush, cornerRadius = cornerRadius, blendMode = BlendMode.Plus)
        }
    } else {
        val brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to spotColor,
                0.5f to spotColor,
                1.0f to Color.White.copy(alpha = 0f),
            ),
            center = center,
            radius = safeRadius,
        )
        if (cornerRadius == CornerRadius.Zero) {
            drawRect(brush = brush, blendMode = BlendMode.Plus)
        } else {
            drawRoundRect(brush = brush, cornerRadius = cornerRadius, blendMode = BlendMode.Plus)
        }
    }
}

internal suspend fun PointerInputScope.inspectDockGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down)
        val upEvent = trackPointerDrag(
            pointerId = down.id,
            onDrag = { change ->
                val delta = change.positionChange()
                if (delta != Offset.Zero) {
                    onDrag(change, delta)
                    change.consume()
                }
            },
        )
        if (upEvent == null) {
            onDragCancel()
        } else {
            upEvent.consume()
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.trackPointerDrag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else if (dragEvent.previousPosition != dragEvent.position) {
            onDrag(dragEvent)
        }
    }
}

private const val SPOT_SHADER = """
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }"""
