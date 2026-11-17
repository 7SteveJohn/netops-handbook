package com.netops.handbook.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import java.util.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 液态玻璃视觉原语体系（Liquid Glass 微组件分层光学架构）：
 *
 * 1. 壳层大面积实时折射：仅顶栏胶囊 / 悬浮导轨 / 空间抽屉使用实时模糊采样；
 * 2. 滚动列表微组件（OpsCard / OpsChip / 按钮 / 搜索槽）：严格零离屏缓冲（无 SaveLayer /
 *    无 RenderEffect / 无 drawBackdrop / 无 hazeEffect），全部基于单遍 `drawWithCache`
 *    按尺寸缓存 Brush 与几何轮廓，120fps 滑动零对象分配，并实时响应 `AppShellState.glassLevel`。
 */

private class CachedGlassShape(
    val isRect: Boolean,
    val isUniformRound: Boolean,
    val cornerRadius: CornerRadius,
    val insetCornerRadius: CornerRadius,
    val strokeTopLeft: Offset,
    val strokeSize: Size,
    val strokeStyle: Stroke,
    val fillPath: Path?,
    val strokePath: Path?,
)

private fun CacheDrawScope.cacheGlassShape(shape: Shape, strokePx: Float): CachedGlassShape {
    val w = size.width
    val h = size.height
    val clampedStroke = strokePx.coerceAtMost(minOf(w, h) * 0.5f).coerceAtLeast(0f)
    val half = clampedStroke * 0.5f
    val strokeTopLeft = Offset(half, half)
    val strokeSize = Size((w - clampedStroke).coerceAtLeast(0f), (h - clampedStroke).coerceAtLeast(0f))
    val strokeStyle = Stroke(width = clampedStroke)

    return when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> CachedGlassShape(
            isRect = true,
            isUniformRound = false,
            cornerRadius = CornerRadius.Zero,
            insetCornerRadius = CornerRadius.Zero,
            strokeTopLeft = strokeTopLeft,
            strokeSize = strokeSize,
            strokeStyle = strokeStyle,
            fillPath = null,
            strokePath = null,
        )
        is Outline.Rounded -> {
            val rr = outline.roundRect
            val uniform = rr.topLeftCornerRadius == rr.topRightCornerRadius &&
                rr.topLeftCornerRadius == rr.bottomRightCornerRadius &&
                rr.topLeftCornerRadius == rr.bottomLeftCornerRadius
            if (uniform) {
                val cr = rr.topLeftCornerRadius
                val insetCr = CornerRadius(
                    (cr.x - half).coerceAtLeast(0f),
                    (cr.y - half).coerceAtLeast(0f),
                )
                CachedGlassShape(
                    isRect = false,
                    isUniformRound = true,
                    cornerRadius = cr,
                    insetCornerRadius = insetCr,
                    strokeTopLeft = strokeTopLeft,
                    strokeSize = strokeSize,
                    strokeStyle = strokeStyle,
                    fillPath = null,
                    strokePath = null,
                )
            } else {
                fun insetCr(c: CornerRadius) = CornerRadius(
                    (c.x - half).coerceAtLeast(0f),
                    (c.y - half).coerceAtLeast(0f),
                )
                val fillP = Path().apply { addRoundRect(rr) }
                val strokeP = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = half,
                            top = half,
                            right = (w - half).coerceAtLeast(half),
                            bottom = (h - half).coerceAtLeast(half),
                            topLeftCornerRadius = insetCr(rr.topLeftCornerRadius),
                            topRightCornerRadius = insetCr(rr.topRightCornerRadius),
                            bottomRightCornerRadius = insetCr(rr.bottomRightCornerRadius),
                            bottomLeftCornerRadius = insetCr(rr.bottomLeftCornerRadius),
                        )
                    )
                }
                CachedGlassShape(
                    isRect = false,
                    isUniformRound = false,
                    cornerRadius = CornerRadius.Zero,
                    insetCornerRadius = CornerRadius.Zero,
                    strokeTopLeft = strokeTopLeft,
                    strokeSize = strokeSize,
                    strokeStyle = strokeStyle,
                    fillPath = fillP,
                    strokePath = strokeP,
                )
            }
        }
        is Outline.Generic -> CachedGlassShape(
            isRect = false,
            isUniformRound = false,
            cornerRadius = CornerRadius.Zero,
            insetCornerRadius = CornerRadius.Zero,
            strokeTopLeft = strokeTopLeft,
            strokeSize = strokeSize,
            strokeStyle = strokeStyle,
            fillPath = outline.path,
            strokePath = outline.path,
        )
    }
}

private fun DrawScope.drawCachedFill(g: CachedGlassShape, color: Color) {
    when {
        g.isRect -> drawRect(color = color)
        g.isUniformRound -> drawRoundRect(color = color, cornerRadius = g.cornerRadius)
        g.fillPath != null -> drawPath(path = g.fillPath, color = color)
    }
}

private fun DrawScope.drawCachedFill(g: CachedGlassShape, brush: Brush) {
    when {
        g.isRect -> drawRect(brush = brush)
        g.isUniformRound -> drawRoundRect(brush = brush, cornerRadius = g.cornerRadius)
        g.fillPath != null -> drawPath(path = g.fillPath, brush = brush)
    }
}

private fun DrawScope.drawCachedStroke(g: CachedGlassShape, color: Color) {
    if (g.strokeStyle.width <= 0f) return
    when {
        g.isRect -> drawRect(
            color = color,
            topLeft = g.strokeTopLeft,
            size = g.strokeSize,
            style = g.strokeStyle,
        )
        g.isUniformRound -> drawRoundRect(
            color = color,
            topLeft = g.strokeTopLeft,
            size = g.strokeSize,
            cornerRadius = g.insetCornerRadius,
            style = g.strokeStyle,
        )
        g.strokePath != null -> drawPath(path = g.strokePath, color = color, style = g.strokeStyle)
    }
}

private fun DrawScope.drawCachedStroke(g: CachedGlassShape, brush: Brush) {
    if (g.strokeStyle.width <= 0f) return
    when {
        g.isRect -> drawRect(
            brush = brush,
            topLeft = g.strokeTopLeft,
            size = g.strokeSize,
            style = g.strokeStyle,
        )
        g.isUniformRound -> drawRoundRect(
            brush = brush,
            topLeft = g.strokeTopLeft,
            size = g.strokeSize,
            cornerRadius = g.insetCornerRadius,
            style = g.strokeStyle,
        )
        g.strokePath != null -> drawPath(path = g.strokePath, brush = brush, style = g.strokeStyle)
    }
}

/** 四停菲涅尔镜面渐变描边 Brush（仅在 drawWithCache 构建期按尺寸生成，支持温润丝光边与钻石焦散切边双模）。 */
private fun fresnelBorderBrush(
    dark: Boolean,
    direct: Boolean,
    heightPx: Float,
    factor: Float,
    causticRim: Boolean = true,
): Brush {
    val endY = heightPx.coerceAtLeast(1f)
    val f = factor.coerceIn(0f, 1.8f)
    return if (causticRim) {
        Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = ((if (dark) 0.44f else 0.96f) * f).coerceIn(0f, 1f)),
            0.28f to Color.White.copy(alpha = ((if (dark) 0.12f else 0.30f) * f).coerceIn(0f, 1f)),
            0.68f to if (dark) {
                Color(0xFFD8ECFF).copy(alpha = (0.05f * f).coerceIn(0f, 1f))
            } else {
                Color.White.copy(alpha = (0.20f * f).coerceIn(0f, 1f))
            },
            1.00f to if (dark) {
                Color.White.copy(alpha = (0.24f * f).coerceIn(0f, 1f))
            } else {
                Color.White.copy(alpha = (0.60f * f).coerceIn(0f, 1f))
            },
            startY = 0f,
            endY = endY,
        )
    } else {
        // 温润云母丝光边：柔和顶缘受光，底边克制收敛、零焦散反光
        Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = ((if (dark) 0.34f else 0.86f) * f).coerceIn(0f, 1f)),
            0.34f to Color.White.copy(alpha = ((if (dark) 0.08f else 0.22f) * f).coerceIn(0f, 1f)),
            0.75f to if (dark) {
                Color.White.copy(alpha = (0.03f * f).coerceIn(0f, 1f))
            } else if (direct) {
                Color.White.copy(alpha = (0.14f * f).coerceIn(0f, 1f))
            } else {
                Color(0xFFD6DAE4).copy(alpha = (0.34f * f).coerceIn(0f, 1f))
            },
            1.00f to if (dark) {
                Color.White.copy(alpha = (0.05f * f).coerceIn(0f, 1f))
            } else if (direct) {
                Color.White.copy(alpha = (0.18f * f).coerceIn(0f, 1f))
            } else {
                Color(0xFFCFD4E0).copy(alpha = (0.38f * f).coerceIn(0f, 1f))
            },
            startY = 0f,
            endY = endY,
        )
    }
}

/**
 * 外层液态玻璃材质（用于特定 Haze 容器，兼容旧签名；内部 Brush 迁入 drawWithCache 消除重组分配）。
 */
fun Modifier.liquidGlassContainer(
    hazeState: HazeState,
    shape: Shape = RoundedCornerShape(26.dp),
    cornerRadius: Dp = 26.dp,
    blurRadius: Dp = 24.dp,
    borderWidth: Dp = 0.8.dp,
): Modifier = composed {
    val ops = LocalOpsColors.current
    val dark = ops.dark
    val baseTint = if (dark) {
        Color(0xFF16181D).copy(alpha = 0.56f)
    } else {
        Color.White.copy(alpha = 0.65f)
    }

    this
        .clip(shape)
        .hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = Color.Transparent,
                blurRadius = blurRadius,
                noiseFactor = 0.016f,
                tints = listOf(HazeTint(baseTint))
            )
        )
        .drawWithCache {
            val geom = cacheGlassShape(shape, borderWidth.toPx())
            val endY = size.height.coerceAtLeast(1f)
            val surfaceSheenBrush = Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = if (dark) 0.14f else 0.38f),
                0.45f to Color.White.copy(alpha = if (dark) 0.03f else 0.08f),
                1.0f to Color.White.copy(alpha = if (dark) 0.06f else 0.18f),
                startY = 0f,
                endY = endY,
            )
            val rimBrush = fresnelBorderBrush(dark, ops.direct, endY, 1.0f)
            onDrawBehind {
                drawCachedFill(geom, surfaceSheenBrush)
                drawCachedStroke(geom, rimBrush)
            }
        }
}

/**
 * 内嵌液态玻璃药丸材质（保留签名兼容，单遍 drawWithCache 零重组分配）。
 */
fun Modifier.liquidGlassPill(
    shape: Shape = CircleShape,
    isDragging: Boolean = false,
    accentColor: Color? = null,
): Modifier = composed {
    val ops = LocalOpsColors.current
    val dark = ops.dark
    val tint = accentColor

    this
        .clip(shape)
        .drawWithCache {
            val endY = size.height.coerceAtLeast(1f)
            if (isDragging) {
                val geom = cacheGlassShape(shape, 0.65.dp.toPx())
                val clearLensBg = if (dark) {
                    Color(0xFF16181D).copy(alpha = 0.15f)
                } else {
                    Color.White.copy(alpha = 0.04f)
                }
                val specularDome = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.55f else 0.85f),
                    0.28f to Color.White.copy(alpha = if (dark) 0.12f else 0.28f),
                    0.65f to Color.White.copy(alpha = 0f),
                    startY = 0f,
                    endY = endY,
                )
                val glassRim = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.70f else 0.95f),
                    0.40f to Color.White.copy(alpha = if (dark) 0.15f else 0.30f),
                    1.0f to Color.White.copy(alpha = if (dark) 0.25f else 0.45f),
                    startY = 0f,
                    endY = endY,
                )
                onDrawBehind {
                    drawCachedFill(geom, clearLensBg)
                    drawCachedFill(geom, specularDome)
                    drawCachedStroke(geom, glassRim)
                }
            } else {
                val geom = cacheGlassShape(shape, 0.6.dp.toPx())
                val midColor = tint?.copy(alpha = if (dark) 0.32f else 0.22f)
                    ?: (if (dark) Color(0xFF1E2D45) else Color(0xFFDCE8FD)).copy(alpha = if (dark) 0.80f else 0.85f)
                val botColor = tint?.copy(alpha = if (dark) 0.40f else 0.28f)
                    ?: (if (dark) Color(0xFF142035) else Color(0xFFD2E2FC)).copy(alpha = if (dark) 0.85f else 0.90f)
                val trayGradient = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.20f else 0.55f),
                    0.45f to midColor,
                    1.0f to botColor,
                    startY = 0f,
                    endY = endY,
                )
                val trayRim = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.35f else 0.88f),
                    0.50f to Color.White.copy(alpha = 0.08f),
                    1.0f to Color.White.copy(alpha = if (dark) 0.16f else 0.42f),
                    startY = 0f,
                    endY = endY,
                )
                onDrawBehind {
                    drawCachedFill(geom, trayGradient)
                    drawCachedStroke(geom, trayRim)
                }
            }
        }
}

/**
 * 四停菲涅尔镜面渐变描边：按尺寸在 `drawWithCache` 内缓存 Brush 与轮廓，零重组分配。
 * 随 `AppShellState.glassLevel` 三档分化：`"none"` 平涂实线 / `"standard"` 丝光柔边 / `"ultra"` 钻石焦散切边。
 */
fun Modifier.glassSpecularBorder(
    shape: Shape = RoundedCornerShape(16.dp),
    width: Dp = 0.8.dp,
    intensity: Float = 1.0f,
): Modifier = composed {
    val ops = LocalOpsColors.current
    this.drawWithCache {
        val level = AppShellState.glassLevel
        if (level == "none") {
            val geom = cacheGlassShape(shape, width.toPx())
            val flatColor = ops.border
            onDrawWithContent {
                drawContent()
                drawCachedStroke(geom, flatColor)
            }
        } else {
            val ultra = level == "ultra"
            val strokeW = (width * if (ultra) 1.25f else 0.85f).toPx()
            val geom = cacheGlassShape(shape, strokeW)
            val brush = fresnelBorderBrush(
                dark = ops.dark,
                direct = ops.direct,
                heightPx = size.height,
                factor = intensity * (if (ultra) 1.38f else 0.72f),
                causticRim = ultra,
            )
            onDrawWithContent {
                drawContent()
                drawCachedStroke(geom, brush)
            }
        }
    }
}

/**
 * 卡片级液态玻璃表面（Liquid Glass 空间层板，零离屏缓冲、零 `composed`、单次 `drawWithCache`）：
 * - `"none"` 档：平涂 `colors.cardBg` + `1.dp` 清爽实线边，零反光；
 * - `"standard"` 档（温润云母）：单顶边 22% 柔雾微光（浅 `White@0.26f` / 深 `White@0.045f`）+ `0.72.dp` 柔和丝光边，无底边焦散；
 * - `"ultra"` 档（璀璨水晶）：高透层板（无壁纸微透瓷晶透出底层极光微晕）+ 双弧面 Cabochon 水晶天光（34%）
 *   + 对角冰蓝棱镜微泽 + 底边 18% 焦散反光 + `1.1.dp` 璀璨四停菲涅尔钻石高光切边（`factor = 1.42f`）。
 */
fun Modifier.glassCardSurface(
    colors: OpsColors,
    shape: Shape = RoundedCornerShape(16.dp),
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val level = AppShellState.glassLevel
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        when (level) {
            "none" -> {
                val geom = cacheGlassShape(shape, 1.dp.toPx())
                onDrawBehind {
                    drawCachedFill(geom, colors.cardBg)
                    drawCachedStroke(geom, colors.border)
                }
            }
            "standard" -> {
                val geom = cacheGlassShape(shape, 0.72.dp.toPx())
                val baseBg = if (!colors.dark && !colors.direct) {
                    Color(0xF5FFFFFF)
                } else {
                    colors.cardBg
                }
                val topBandEndY = (h * 0.22f).coerceAtLeast(1f)
                val micaSheenBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (colors.dark) 0.045f else 0.26f),
                    1.0f to Color.White.copy(alpha = 0f),
                    startY = 0f,
                    endY = topBandEndY,
                )
                val rimBrush = fresnelBorderBrush(
                    dark = colors.dark,
                    direct = colors.direct,
                    heightPx = h,
                    factor = 0.72f,
                    causticRim = false,
                )
                onDrawBehind {
                    drawCachedFill(geom, baseBg)
                    drawCachedFill(geom, micaSheenBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
            else -> {
                val geom = cacheGlassShape(shape, 1.1.dp.toPx())
                val baseBg = if (!colors.direct) {
                    // 无壁纸默认态：微透瓷晶层板以透出底层空间极光环境微晕
                    if (colors.dark) Color(0xD8161922) else Color(0xD6FFFFFF)
                } else {
                    colors.cardBg
                }
                val prismAlpha = if (colors.dark) 0.05f else 0.12f
                val prismDiagonalBrush = Brush.linearGradient(
                    0.00f to Color(0xFFD8ECFF).copy(alpha = prismAlpha),
                    0.38f to Color.White.copy(alpha = prismAlpha * 0.45f),
                    0.62f to Color.Transparent,
                    0.86f to Color(0xFFE8D8FF).copy(alpha = prismAlpha * 0.42f),
                    1.00f to Color(0xFFD8ECFF).copy(alpha = prismAlpha * 0.85f),
                    start = Offset.Zero,
                    end = Offset(w, h),
                )
                // 单遍融合顶部 34% 双弧面 Cabochon 水晶天光 + 底部 18% 焦散反光（腰部 34%..82% 纯净透窗保障高对比度阅读）
                val cabochonVerticalBrush = Brush.verticalGradient(
                    0.00f to Color.White.copy(alpha = if (colors.dark) 0.16f else 0.62f),
                    0.15f to Color.White.copy(alpha = if (colors.dark) 0.065f else 0.24f),
                    0.34f to Color.White.copy(alpha = 0f),
                    0.82f to Color.White.copy(alpha = 0f),
                    0.93f to Color.White.copy(alpha = if (colors.dark) 0.038f else 0.12f),
                    1.00f to Color.White.copy(alpha = if (colors.dark) 0.08f else 0.26f),
                    startY = 0f,
                    endY = h,
                )
                val rimBrush = fresnelBorderBrush(
                    dark = colors.dark,
                    direct = colors.direct,
                    heightPx = h,
                    factor = 1.42f,
                    causticRim = true,
                )
                onDrawBehind {
                    drawCachedFill(geom, baseBg)
                    drawCachedFill(geom, prismDiagonalBrush)
                    drawCachedFill(geom, cabochonVerticalBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
        }
    }

/**
 * 胶囊筛选 / 操作按钮液态玻璃表面（零离屏缓冲、单次 `drawWithCache`）：
 * - `"standard"`：克制柔雾边（`0.65.dp`）与柔和顶光（`0.18f`）；
 * - `"ultra"`：宝石通透渐变 + 饱满凸面弧光（`0.42f`）+ 锐利亮白菲涅尔钻石切边（`0.9.dp`，`factor = 1.35f`）。
 */
fun Modifier.glassChipSurface(
    colors: OpsColors,
    active: Boolean = false,
    shape: Shape = RoundedCornerShape(10.dp),
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val level = AppShellState.glassLevel
        val h = size.height.coerceAtLeast(1f)
        when {
            level == "none" -> {
                val geom = cacheGlassShape(shape, if (active) 0.7.dp.toPx() else 0.65.dp.toPx())
                val fill = if (active) colors.accent else colors.chipBgOff
                val rim = if (active) colors.accent else colors.border
                onDrawBehind {
                    drawCachedFill(geom, fill)
                    drawCachedStroke(geom, rim)
                }
            }
            active && level == "standard" -> {
                val geom = cacheGlassShape(shape, 0.65.dp.toPx())
                val deepTone = lerp(colors.accent, Color(0xFF153E9E), 0.16f)
                val bodyBrush = Brush.verticalGradient(
                    0.0f to colors.accent.copy(alpha = 0.94f),
                    1.0f to deepTone.copy(alpha = 0.90f),
                    startY = 0f,
                    endY = h,
                )
                val domeEndY = (h * 0.30f).coerceAtLeast(1f)
                val domeBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = 0.18f),
                    1.0f to Color.White.copy(alpha = 0f),
                    startY = 0f,
                    endY = domeEndY,
                )
                val rimBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (colors.dark) 0.42f else 0.62f),
                    0.50f to Color.White.copy(alpha = 0.14f),
                    1.0f to Color.White.copy(alpha = 0.22f),
                    startY = 0f,
                    endY = h,
                )
                onDrawBehind {
                    drawCachedFill(geom, bodyBrush)
                    drawCachedFill(geom, domeBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
            active -> {
                // "ultra" 激活态：宝石蓝通透液态水珠 + 0.42f 饱满凸面弧光 + 0.9.dp 亮白菲涅尔切边
                val geom = cacheGlassShape(shape, 0.9.dp.toPx())
                val iceCyan = lerp(colors.accent, Color(0xFF38BDF8), if (colors.dark) 0.24f else 0.18f)
                val deepSapphire = lerp(colors.accent, Color(0xFF123894), if (colors.dark) 0.30f else 0.24f)
                val bodyBrush = Brush.verticalGradient(
                    0.0f to iceCyan.copy(alpha = 0.86f),
                    0.54f to deepSapphire.copy(alpha = if (colors.dark) 0.78f else 0.83f),
                    1.0f to colors.accent.copy(alpha = if (colors.dark) 0.70f else 0.76f),
                    startY = 0f,
                    endY = h,
                )
                val domeEndY = (h * 0.44f).coerceAtLeast(1f)
                val domeBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = 0.42f),
                    0.46f to Color.White.copy(alpha = 0.14f),
                    1.0f to Color.White.copy(alpha = 0f),
                    startY = 0f,
                    endY = domeEndY,
                )
                val rimBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (colors.dark) 0.86f else 0.98f),
                    0.42f to Color.White.copy(alpha = 0.28f),
                    1.0f to Color.White.copy(alpha = if (colors.dark) 0.48f else 0.68f),
                    startY = 0f,
                    endY = h,
                )
                onDrawBehind {
                    drawCachedFill(geom, bodyBrush)
                    drawCachedFill(geom, domeBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
            level == "standard" -> {
                val geom = cacheGlassShape(shape, 0.65.dp.toPx())
                val baseBg = colors.chipBgOff
                val topSheenEndY = (h * 0.28f).coerceAtLeast(1f)
                val topSheenBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (colors.dark) 0.05f else 0.18f),
                    1.0f to Color.White.copy(alpha = 0f),
                    startY = 0f,
                    endY = topSheenEndY,
                )
                val rimBrush = fresnelBorderBrush(
                    dark = colors.dark,
                    direct = colors.direct,
                    heightPx = h,
                    factor = 0.72f,
                    causticRim = false,
                )
                onDrawBehind {
                    drawCachedFill(geom, baseBg)
                    drawCachedFill(geom, topSheenBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
            else -> {
                // "ultra" 未激活态：通透冰晶胶囊 + 0.42f 凸面弧光 + 0.9.dp 璀璨菲涅尔切边 (factor = 1.35f)
                val geom = cacheGlassShape(shape, 0.9.dp.toPx())
                val baseBg = if (colors.direct) {
                    colors.chipBgOff
                } else {
                    if (colors.dark) Color(0xB8222634) else Color(0xBAEEF2FA)
                }
                val topSheenEndY = (h * 0.44f).coerceAtLeast(1f)
                val topSheenBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (colors.dark) 0.16f else 0.42f),
                    0.50f to Color.White.copy(alpha = if (colors.dark) 0.05f else 0.14f),
                    1.0f to Color.White.copy(alpha = 0f),
                    startY = 0f,
                    endY = topSheenEndY,
                )
                val rimBrush = fresnelBorderBrush(
                    dark = colors.dark,
                    direct = colors.direct,
                    heightPx = h,
                    factor = 1.35f,
                    causticRim = true,
                )
                onDrawBehind {
                    drawCachedFill(geom, baseBg)
                    drawCachedFill(geom, topSheenBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
        }
    }

/**
 * 编号徽章 / 小标签液态玻璃表面（按 `standard` 柔雾边 vs `ultra` 宝石通透弧光切边分层）。
 */
fun Modifier.glassBadgeSurface(
    colors: OpsColors,
    shape: Shape = RoundedCornerShape(9.dp),
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val level = AppShellState.glassLevel
        val h = size.height.coerceAtLeast(1f)
        when (level) {
            "none" -> {
                val geom = cacheGlassShape(shape, 0.6.dp.toPx())
                val flatRim = colors.accent.copy(alpha = 0.24f)
                onDrawBehind {
                    drawCachedFill(geom, colors.chipBgOn)
                    drawCachedStroke(geom, flatRim)
                }
            }
            "standard" -> {
                val geom = cacheGlassShape(shape, 0.65.dp.toPx())
                val arcEndY = (h * 0.34f).coerceAtLeast(1f)
                val arcBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (colors.dark) 0.07f else 0.18f),
                    1.0f to Color.White.copy(alpha = 0f),
                    startY = 0f,
                    endY = arcEndY,
                )
                val rimBrush = Brush.verticalGradient(
                    0.0f to colors.accent.copy(alpha = 0.32f),
                    0.55f to colors.accent.copy(alpha = 0.14f),
                    1.0f to colors.accent.copy(alpha = 0.20f),
                    startY = 0f,
                    endY = h,
                )
                onDrawBehind {
                    drawCachedFill(geom, colors.chipBgOn)
                    drawCachedFill(geom, arcBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
            else -> {
                val geom = cacheGlassShape(shape, 0.9.dp.toPx())
                val jewelBody = Brush.verticalGradient(
                    0.0f to colors.accent.copy(alpha = if (colors.dark) 0.28f else 0.20f),
                    0.55f to colors.chipBgOn,
                    1.0f to Color(0xFF38BDF8).copy(alpha = if (colors.dark) 0.22f else 0.16f),
                    startY = 0f,
                    endY = h,
                )
                val arcEndY = (h * 0.46f).coerceAtLeast(1f)
                val arcBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (colors.dark) 0.22f else 0.42f),
                    0.50f to Color.White.copy(alpha = if (colors.dark) 0.07f else 0.14f),
                    1.0f to Color.White.copy(alpha = 0f),
                    startY = 0f,
                    endY = arcEndY,
                )
                val topRim = lerp(
                    colors.accent.copy(alpha = 0.55f),
                    Color.White.copy(alpha = if (colors.dark) 0.64f else 0.95f),
                    0.62f,
                )
                val botRim = lerp(
                    colors.accent.copy(alpha = 0.42f),
                    Color.White.copy(alpha = if (colors.dark) 0.36f else 0.72f),
                    0.45f,
                )
                val rimBrush = Brush.verticalGradient(
                    0.0f to topRim,
                    0.50f to colors.accent.copy(alpha = 0.26f),
                    1.0f to botRim,
                    startY = 0f,
                    endY = h,
                )
                onDrawBehind {
                    drawCachedFill(geom, jewelBody)
                    drawCachedFill(geom, arcBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
        }
    }

/**
 * 冰晶搜索槽表面（按 `standard` 柔雾槽 vs `ultra` 璀璨通透晶体槽分层）。
 */
fun Modifier.glassSearchFieldSurface(
    colors: OpsColors,
    shape: Shape = RoundedCornerShape(13.dp),
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val level = AppShellState.glassLevel
        val h = size.height.coerceAtLeast(1f)
        when (level) {
            "none" -> {
                val geom = cacheGlassShape(shape, 0.75.dp.toPx())
                onDrawBehind {
                    drawCachedFill(geom, colors.chipBgOff)
                    drawCachedStroke(geom, colors.border)
                }
            }
            "standard" -> {
                val geom = cacheGlassShape(shape, 0.65.dp.toPx())
                val baseBg = colors.chipBgOff
                val grooveBrush = Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = if (colors.dark) 0.14f else 0.03f),
                    0.24f to Color.Transparent,
                    0.76f to Color.Transparent,
                    1.00f to Color.White.copy(alpha = if (colors.dark) 0.03f else 0.18f),
                    startY = 0f,
                    endY = h,
                )
                val rimBrush = fresnelBorderBrush(
                    dark = colors.dark,
                    direct = colors.direct,
                    heightPx = h,
                    factor = 0.72f,
                    causticRim = false,
                )
                onDrawBehind {
                    drawCachedFill(geom, baseBg)
                    drawCachedFill(geom, grooveBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
            else -> {
                val geom = cacheGlassShape(shape, 0.9.dp.toPx())
                val baseBg = if (colors.direct) {
                    colors.chipBgOff
                } else {
                    if (colors.dark) Color(0xB81E2230) else Color(0xC4EEF2FA)
                }
                val grooveBrush = Brush.verticalGradient(
                    0.00f to Color.White.copy(alpha = if (colors.dark) 0.15f else 0.42f),
                    0.34f to Color.Transparent,
                    0.70f to Color.Transparent,
                    1.00f to Color(0xFFD8ECFF).copy(alpha = if (colors.dark) 0.08f else 0.28f),
                    startY = 0f,
                    endY = h,
                )
                val rimBrush = fresnelBorderBrush(
                    dark = colors.dark,
                    direct = colors.direct,
                    heightPx = h,
                    factor = 1.35f,
                    causticRim = true,
                )
                onDrawBehind {
                    drawCachedFill(geom, baseBg)
                    drawCachedFill(geom, grooveBrush)
                    drawCachedStroke(geom, rimBrush)
                }
            }
        }
    }

/**
 * 64×64 程序微噪点平铺（仅用于大面积栏体消除色带）。
 */
fun Modifier.glassMicroNoise(
    alpha: Float = 0.016f,
    tileSizePx: Int = 64,
): Modifier = this.drawWithCache {
    val shaderBrush = ShaderBrush(
        ImageShader(noiseBitmap(tileSizePx).asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
    )
    onDrawWithContent {
        drawContent()
        drawRect(brush = shaderBrush, alpha = alpha)
    }
}

private val noiseBitmapCache = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()

private fun noiseBitmap(size: Int): Bitmap {
    return noiseBitmapCache.getOrPut(size) {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rnd = Random(2026L)
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            val v = rnd.nextInt(256)
            pixels[i] = android.graphics.Color.argb(255, v, v, v)
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        bmp
    }
}

private class GlassPressOrigin(var offset: Offset = Offset.Unspecified)

/**
 * 纯视觉灵动流体弹性反馈 + 指尖径向聚光波纹（零离屏缓冲、零重组，实时按 `AppShellState.glassLevel` 三档响应）：
 * - `"none"`：清爽无过冲 `0.988f` 轻按，关闭 `BlendMode.Plus` 波纹聚光；
 * - `"standard"`：温和克制轻弹（`dampingRatio = 0.72f`，聚光强度乘 `0.45f`，形变幅度收敛一半）；
 * - `"ultra"`：100% 释放欠阻尼 `0.46f` 活体果冻回弹与饱满指尖径向液态聚光波纹！
 */
fun Modifier.glassPressFeedback(
    interactionSource: InteractionSource? = null,
    pressedScale: Float = 0.972f,
    glowBoost: Float = 0.08f,
    swellOnPress: Boolean = false,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pulseAnim = remember { Animatable(0f, 0.001f) }
    val pressOrigin = remember { GlassPressOrigin() }
    val baseTargetScale = if (swellOnPress && pressedScale < 1f) 1.07f else pressedScale

    LaunchedEffect(source) {
        var pressJob: Job? = null
        var settleJob: Job? = null
        source.interactions.collect { interaction ->
            val level = AppShellState.glassLevel
            when (interaction) {
                is PressInteraction.Press -> {
                    settleJob?.cancel()
                    pressJob?.cancel()
                    pressOrigin.offset = interaction.pressPosition
                    pressJob = launch {
                        val spec = when (level) {
                            "none" -> spring<Float>(dampingRatio = 1.0f, stiffness = 900f)
                            "standard" -> spring(dampingRatio = 0.82f, stiffness = 780f)
                            else -> spring(dampingRatio = 0.68f, stiffness = 760f)
                        }
                        pulseAnim.animateTo(targetValue = 1f, animationSpec = spec)
                    }
                }
                is PressInteraction.Release -> {
                    settleJob?.cancel()
                    settleJob = launch {
                        when (level) {
                            "none" -> {
                                pressJob?.cancel()
                                pulseAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = 1.0f, stiffness = 760f),
                                )
                            }
                            "standard" -> {
                                if (pulseAnim.value < 0.72f) {
                                    withTimeoutOrNull(70L) {
                                        if (pressJob?.isActive == true) {
                                            snapshotFlow { pulseAnim.value }.first { it >= 0.72f }
                                        } else {
                                            pulseAnim.animateTo(
                                                targetValue = 0.75f,
                                                animationSpec = spring(dampingRatio = 0.82f, stiffness = 860f),
                                            )
                                        }
                                    }
                                }
                                pressJob?.cancel()
                                if (pulseAnim.value < 0.72f) {
                                    pulseAnim.snapTo(0.72f)
                                }
                                pulseAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 460f),
                                )
                            }
                            else -> {
                                if (pulseAnim.value < 0.82f) {
                                    withTimeoutOrNull(95L) {
                                        if (pressJob?.isActive == true) {
                                            snapshotFlow { pulseAnim.value }.first { it >= 0.82f }
                                        } else {
                                            pulseAnim.animateTo(
                                                targetValue = 0.85f,
                                                animationSpec = spring(dampingRatio = 0.68f, stiffness = 860f),
                                            )
                                        }
                                    }
                                }
                                pressJob?.cancel()
                                if (pulseAnim.value < 0.82f) {
                                    pulseAnim.snapTo(0.82f)
                                }
                                pulseAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = 0.46f, stiffness = 360f),
                                )
                            }
                        }
                    }
                }
                is PressInteraction.Cancel -> {
                    pressJob?.cancel()
                    settleJob?.cancel()
                    settleJob = launch {
                        pulseAnim.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.95f, stiffness = 680f),
                        )
                    }
                }
            }
        }
    }

    this
        .graphicsLayer {
            val rawPulse = pulseAnim.value
            if (abs(rawPulse) > 0.0005f) {
                val level = AppShellState.glassLevel
                val s = when (level) {
                    "none" -> {
                        val p = rawPulse.coerceIn(0f, 1f)
                        1f + (0.988f - 1f) * p
                    }
                    "standard" -> {
                        val stdScale = 1f + (baseTargetScale - 1f) * 0.5f
                        (1f + (stdScale - 1f) * rawPulse).coerceIn(0.90f, 1.18f)
                    }
                    else -> {
                        (1f + (baseTargetScale - 1f) * rawPulse).coerceIn(0.80f, 1.35f)
                    }
                }
                scaleX = s
                scaleY = s
            }
        }
        .drawWithContent {
            drawContent()
            val level = AppShellState.glassLevel
            if (level == "none") return@drawWithContent
            val pulse = pulseAnim.value.coerceIn(0f, 1f)
            if (pulse > 0.004f) {
                val glowMul = if (level == "ultra") 1.0f else 0.45f
                val effGlow = glowBoost * glowMul
                val cornerR = minOf(size.minDimension * 0.5f, 16.dp.toPx())
                val cr = CornerRadius(cornerR, cornerR)
                val rawCenter = pressOrigin.offset
                val center = if (rawCenter != Offset.Unspecified) {
                    Offset(
                        x = rawCenter.x.coerceIn(0f, size.width),
                        y = rawCenter.y.coerceIn(0f, size.height),
                    )
                } else {
                    Offset(size.width * 0.5f, size.height * 0.5f)
                }
                val maxDim = maxOf(size.width, size.height).coerceAtLeast(1f)
                val rippleRadius = (maxDim * (0.26f + 0.80f * pulse)).coerceAtLeast(8f)

                val liftAlpha = (effGlow * 0.52f * pulse).coerceIn(0f, 1f)
                if (liftAlpha > 0.002f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = liftAlpha),
                        cornerRadius = cr,
                    )
                }
                if (!AppShellState.isDark) {
                    val iceAlpha = (effGlow * 0.45f * pulse).coerceIn(0f, 0.055f)
                    if (iceAlpha > 0.002f) {
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF2563EB).copy(alpha = iceAlpha),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = rippleRadius,
                            ),
                            cornerRadius = cr,
                        )
                    }
                }

                val peakSpotAlpha = (effGlow * 1.48f * pulse).coerceIn(0f, 0.34f)
                if (peakSpotAlpha > 0.002f) {
                    val midSpotAlpha = (peakSpotAlpha * 0.42f).coerceIn(0f, 0.34f)
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = peakSpotAlpha),
                                0.48f to Color.White.copy(alpha = midSpotAlpha),
                                1.0f to Color.Transparent,
                            ),
                            center = center,
                            radius = rippleRadius,
                        ),
                        cornerRadius = cr,
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
}

/**
 * 液态玻璃点击控件：快速轻点冲顶门控 + 欠阻尼流体回弹 + 指尖径向聚光波纹 + 列表滚动零误触。
 */
fun Modifier.glassClickable(
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    pressedScale: Float = 0.972f,
    glowBoost: Float = 0.08f,
    swellOnPress: Boolean = false,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    this
        .glassPressFeedback(
            interactionSource = source,
            pressedScale = pressedScale,
            glowBoost = glowBoost,
            swellOnPress = swellOnPress,
        )
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/** 是否处于横屏视口（宽 > 高）。 */
@Composable
fun isOpsLandscape(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.screenWidthDp > cfg.screenHeightDp
}

/** 是否处于手机紧凑横屏视口（宽 > 高 且 高 < 480dp）。 */
@Composable
fun isOpsCompactLandscape(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenHeightDp < 480
}

/**
 * 全站统一横竖屏自适应安全区内边距辅助函数：
 * - 自动适配紧凑横屏 48dp 顶栏 / 常规 56dp 顶栏；
 * - 手机紧凑横屏且悬浮模式下自动预留左侧 74dp 竖排液态玻璃导轨避让；
 * - 全向叠加挖孔屏 `displayCutout` 与系统导航栏安全区，横屏绝不遮挡文字。
 * - `hasDock` / `hasTopBar` 默认按 `AppShellState.pageRoute` 自动识别（search/settings/cli 无底栏导轨，cli 无顶栏），
 *   调用方也可显式覆写。
 */
@Composable
fun rememberOpsPagePadding(
    extraTop: Dp = 12.dp,
    extraBottom: Dp = 96.dp,
    horizontal: Dp = 14.dp,
    hasDock: Boolean = AppShellState.pageRoute != "cli" &&
        AppShellState.pageRoute != "search" &&
        AppShellState.pageRoute != "settings",
    hasTopBar: Boolean = AppShellState.pageRoute != "cli",
): PaddingValues {
    val cfg = LocalConfiguration.current
    val layoutDir = LocalLayoutDirection.current
    val isRtl = layoutDir == LayoutDirection.Rtl

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navVals = WindowInsets.navigationBars.asPaddingValues()
    val navBottom = navVals.calculateBottomPadding()
    val navStart = if (isRtl) navVals.calculateRightPadding(layoutDir) else navVals.calculateLeftPadding(layoutDir)
    val navEnd = if (isRtl) navVals.calculateLeftPadding(layoutDir) else navVals.calculateRightPadding(layoutDir)

    val cutoutVals = WindowInsets.displayCutout.asPaddingValues()
    val cutoutStart = if (isRtl) cutoutVals.calculateRightPadding(layoutDir) else cutoutVals.calculateLeftPadding(layoutDir)
    val cutoutEnd = if (isRtl) cutoutVals.calculateLeftPadding(layoutDir) else cutoutVals.calculateRightPadding(layoutDir)

    val isCompactLandscape = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenHeightDp < 480
    val docked = AppShellState.dockDocked

    val topBarH = when {
        !hasTopBar -> 0.dp
        isCompactLandscape -> 48.dp
        else -> 56.dp
    }
    val railStart = if (hasDock && isCompactLandscape && !docked) 74.dp else 0.dp
    val bottomPad = when {
        !hasDock -> if (extraBottom == 96.dp) 20.dp else extraBottom
        isCompactLandscape && !docked -> 16.dp
        docked -> 62.dp
        else -> extraBottom
    }

    val topPad = statusTop + topBarH + extraTop
    val bottomTotal = navBottom + bottomPad
    val startPad = maxOf(cutoutStart, navStart, horizontal) + railStart
    val endPad = maxOf(cutoutEnd, navEnd, horizontal)

    return remember(startPad, topPad, endPad, bottomTotal) {
        PaddingValues(
            start = startPad,
            top = topPad,
            end = endPad,
            bottom = bottomTotal,
        )
    }
}
