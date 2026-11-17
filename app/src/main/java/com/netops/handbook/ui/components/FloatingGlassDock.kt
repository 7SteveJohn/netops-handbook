package com.netops.handbook.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.netops.handbook.lens.glass2.DockDraw
import com.netops.handbook.lens.glass2.InnerShadow
import com.netops.handbook.lens.glass2.innerShadow
import com.netops.handbook.lens.glass2.lens
import com.netops.handbook.lens.glass2.rememberCombinedBackdrop
import com.netops.handbook.lens.glass2.rememberIosDirectionalHighlight
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.LocalOpsColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.offscreenLayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

data class DockNavItem(
    val label: String,
)

/**
 * 底座栏体顶部冰晶弯月面（0.0f..0.40f）零分配渐变缓存，避免逐帧 draw 分配 Brush。
 */
private class BarMeniscusBrushCache {
    private var lastHeight: Float = -1f
    private var lastDark: Boolean = false
    private var cached: Brush? = null

    fun obtain(height: Float, isDark: Boolean): Brush {
        val h = height.coerceAtLeast(1f)
        val cur = cached
        if (cur != null && lastHeight == h && lastDark == isDark) return cur
        val topAlpha = if (isDark) 0.07f else 0.16f
        return Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = topAlpha),
            0.40f to Color.Transparent,
            1.00f to Color.Transparent,
            startY = 0f,
            endY = h,
        ).also {
            lastHeight = h
            lastDark = isDark
            cached = it
        }
    }
}

/**
 * 水晶凸面宝石弯月反光（Cabochon Meniscus）零分配渐变缓存：
 * 顶部天光漫射 -> 冰蓝过渡 -> 中心完全透明清窗 -> 底部焦散反光，彻底取代发灰贴膜。
 */
private class PillCabochonBrushCache {
    private var lastHeight: Float = -1f
    private var lastDark: Boolean = false
    private var cached: Brush? = null

    fun obtain(height: Float, isDark: Boolean): Brush {
        val h = height.coerceAtLeast(1f)
        val cur = cached
        if (cur != null && lastHeight == h && lastDark == isDark) return cur
        return Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = if (isDark) 0.16f else 0.32f),
            0.36f to Color(0xFFEEF5FF).copy(alpha = if (isDark) 0.02f else 0.05f),
            0.68f to Color.Transparent,
            1.00f to Color.White.copy(alpha = if (isDark) 0.07f else 0.15f),
            startY = 0f,
            endY = h,
        ).also {
            lastHeight = h
            lastDark = isDark
            cached = it
        }
    }
}

/**
 * 标准档（温润云母）药丸边缘柔和丝光缓存：
 * 中部 0.32f..0.78f 保持 100% Color.Transparent，绝不蒙白冲淡底层亮蓝 `#007AFF` 图标与文字。
 */
private class PillMicaSheenBrushCache {
    private var lastHeight: Float = -1f
    private var lastDark: Boolean = false
    private var cached: Brush? = null

    fun obtain(height: Float, isDark: Boolean): Brush {
        val h = height.coerceAtLeast(1f)
        val cur = cached
        if (cur != null && lastHeight == h && lastDark == isDark) return cur
        return Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = if (isDark) 0.09f else 0.20f),
            0.32f to Color.Transparent,
            0.78f to Color.Transparent,
            1.00f to Color.White.copy(alpha = if (isDark) 0.04f else 0.09f),
            startY = 0f,
            endY = h,
        ).also {
            lastHeight = h
            lastDark = isDark
            cached = it
        }
    }
}

/**
 * Liquid Glass 同源双档分级悬浮液态玻璃导航栏（LiquidBottomTabs 光学架构 + 紧凑横屏侧边导轨）：
 * - 「极致 (`ultra`)」：7.2dp 冕牌水晶散射 + 高对比增艳（1.08 / 1.78）+ 26/34dp 弧面色散厚折光 + 刀锋高光，
 *   按压/滑行时离屏磨砂消融 94% 化为纯净清澈水滴（1.37x 跨栏界膨胀 + 8..17/14..28dp 棱镜色散凸透镜）；
 * - 「标准 (`standard`)」：对标 柔雾磨砂（Frosted）模式的 16.5dp 温润云母重磨砂 + 8/9dp 温和微倒角零色散 +
 *   柔和丝光边，按压/滑行时保留 62% 云母柔雾质感与 1.17x 内敛气垫轻弹；
 * - 手机紧凑横屏且非贴底时自动切换为左侧 `VerticalLiquidGlassRail` 竖排液态玻璃导轨（宽 58dp、高约 248dp）。
 */
@Composable
fun FloatingGlassDock(
    backdrop: LayerBackdrop?,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    val ops = LocalOpsColors.current
    val isDark = ops.dark
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val onSelectUpdated by rememberUpdatedState(onSelect)

    val items = remember {
        listOf(
            DockNavItem("学习"),
            DockNavItem("排障"),
            DockNavItem("字典"),
            DockNavItem("我的"),
        )
    }

    val iconPaths = remember(density.density) {
        val raw = DockDraw.buildIconPaths(density.density)
        raw.map { subArray -> subArray.map { it.asComposePath() } }
    }

    val isDocked = AppShellState.dockDocked
    val glassLevel = AppShellState.glassLevel
    val isShaderGlass = glassLevel != "none" &&
        backdrop != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        isRuntimeShaderSupported()

    val accentColor = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)

    // 「原生贴底」模式：还原 经典通栏 经典平直通栏 UITabBar（横屏自动收紧至 46dp 并避让左右挖孔）
    if (isDocked) {
        val navBottomDocked = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        ClassicIosDockedTabBar(
            backdrop = backdrop,
            isShaderGlass = isShaderGlass,
            isDark = isDark,
            items = items,
            iconPaths = iconPaths,
            selectedIndex = selectedIndex,
            accentColor = accentColor,
            navBottom = navBottomDocked,
            onSelect = { idx ->
                if (idx != selectedIndex) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onSelectUpdated(idx)
            },
            modifier = modifier,
        )
        return
    }

    // 手机紧凑横屏且非贴底：切换为左侧悬浮竖排液态玻璃导轨（宽 58dp，高约 248dp）
    val isCompactLandscape = configuration.screenWidthDp > configuration.screenHeightDp &&
        configuration.screenHeightDp < 480
    if (isCompactLandscape) {
        VerticalLiquidGlassRail(
            backdrop = backdrop,
            isShaderGlass = isShaderGlass,
            isDark = isDark,
            items = items,
            iconPaths = iconPaths,
            selectedIndex = selectedIndex,
            accentColor = accentColor,
            onSelect = onSelectUpdated,
            modifier = modifier,
        )
        return
    }

    val inactiveColor = if (isDark) Color(0xD6EBEBF5) else Color(0xFF1A1C20).copy(alpha = 0.82f)
    val fallbackBarColor = if (isDark) {
        Color(0xFF1A1C22).copy(alpha = 0.92f)
    } else {
        Color(0xFFF8F9FC).copy(alpha = 0.93f)
    }

    val navBottom = 0.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        val isTablet = maxWidth >= 600.dp ||
            (configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenHeightDp >= 480)

        val dockHeight = 64.dp
        val totalBarHeight = dockHeight + navBottom
        // 严格同心几何：内外间距四边统一 4.dp（R_outer - R_inner = 4.dp，栏高 64dp / 药丸 56dp 对齐 Liquid Glass）
        val innerPadding = 4.dp
        val innerPaddingPx = with(density) { innerPadding.toPx() }

        val dockShape = CircleShape
        val pillShape = CircleShape

        val totalDockWidth = if (isTablet) {
            maxWidth.coerceAtMost(420.dp)
        } else {
            maxWidth.coerceAtMost(384.dp)
        }
        val totalDockWidthPx = with(density) { totalDockWidth.toPx() }
        val contentWidth = totalDockWidth - innerPadding * 2
        val itemWidth = contentWidth / items.size
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val pillHeight = dockHeight - innerPadding * 2

        // 橡皮筋越界微移（Liquid Glass 拖到两端时整栏 ±4dp 弹性跟随）
        val offsetAnimation = remember { Animatable(0f) }
        val rubberBandPx = with(density) { 4.dp.toPx() }
        val panelOffset by remember(totalDockWidthPx, rubberBandPx) {
            derivedStateOf {
                if (totalDockWidthPx <= 0f) {
                    0f
                } else {
                    val fraction = (offsetAnimation.value / totalDockWidthPx).coerceIn(-1f, 1f)
                    rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        var currentIndex by remember { mutableIntStateOf(selectedIndex) }
        var lastHapticIdx by remember { mutableIntStateOf(selectedIndex) }

        fun indexAt(posX: Float): Int {
            if (itemWidthPx <= 0f) return currentIndex
            return ((posX - innerPaddingPx) / itemWidthPx).toInt().coerceIn(0, items.lastIndex)
        }

        // Liquid Glass 水滴膨胀比例：悬浮态 56dp → 78dp（上下各漫出栏体边缘 7dp，触发跨栏界弯月面折射）
        val pressedScaleRatio = 78f / 56f

        val dampedDrag = remember(animationScope, items.size, itemWidthPx, pressedScaleRatio) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(items.size - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = pressedScaleRatio,
                onDragStarted = { position ->
                    val hit = indexAt(position.x)
                    lastHapticIdx = hit
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (hit != targetValue.roundToInt()) {
                        updateValue(hit.toFloat())
                    }
                },
                onDragStopped = {
                    val targetIdx = targetValue.roundToInt().coerceIn(0, items.lastIndex)
                    updateValue(targetIdx.toFloat())
                    if (currentIndex != targetIdx) {
                        currentIndex = targetIdx
                    }
                    onSelectUpdated(targetIdx)
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDragCancelled = {
                    updateValue(currentIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (itemWidthPx > 0f && dragAmount.x != 0f) {
                        val next = (targetValue + dragAmount.x / itemWidthPx)
                            .coerceIn(0f, (items.size - 1).toFloat())
                        updateValue(next)
                        val hovered = next.roundToInt().coerceIn(0, items.lastIndex)
                        if (hovered != lastHapticIdx) {
                            lastHapticIdx = hovered
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                },
            )
        }

        LaunchedEffect(selectedIndex) {
            if (currentIndex != selectedIndex) {
                currentIndex = selectedIndex
                lastHapticIdx = selectedIndex
                dampedDrag.animateToValue(selectedIndex.toFloat())
            }
        }

        // derivedStateOf 隔离逐帧浮点位移，拖拽全程仅跨格中点触发最多 3 次重组，其余 100% 走 Layer/Draw 期
        val activeBoldIndex by remember(dampedDrag) {
            derivedStateOf { dampedDrag.value.roundToInt().coerceIn(0, items.lastIndex) }
        }

        val barGlassBackdrop = rememberLayerBackdrop()
        val tabsBackdrop = rememberLayerBackdrop()
        val combinedBackdrop = backdrop?.let { rememberCombinedBackdrop(it, tabsBackdrop) }

        val barMeniscusCache = remember { BarMeniscusBrushCache() }
        val pillCabochonCache = remember { PillCabochonBrushCache() }
        val pillMicaSheenCache = remember { PillMicaSheenBrushCache() }

        val barHighlightLight = rememberIosDirectionalHighlight(
            width = 0.85.dp,
            color = Color.White.copy(alpha = 0.88f),
            baseDegrees = 45f,
            falloff = 1.12f,
            shaderKey = "IosBarDirectionalHighlight",
        )
        val barHighlightDark = rememberIosDirectionalHighlight(
            width = 0.85.dp,
            color = Color.White.copy(alpha = 0.64f),
            baseDegrees = 45f,
            falloff = 1.12f,
            shaderKey = "IosBarDirectionalHighlight",
        )
        val pillHighlightLight = rememberIosDirectionalHighlight(
            width = 0.9.dp,
            color = Color.White.copy(alpha = 0.94f),
            baseDegrees = 45f,
            falloff = 1.02f,
            shaderKey = "IosPillDirectionalHighlight",
        )
        val pillHighlightDark = rememberIosDirectionalHighlight(
            width = 0.9.dp,
            color = Color.White.copy(alpha = 0.72f),
            baseDegrees = 45f,
            falloff = 1.02f,
            shaderKey = "IosPillDirectionalHighlight",
        )

        // 栏体手指聚光着色器（draw 期读取 dampedDrag，零额外手势协程）
        val spotShader = remember {
            if (isRuntimeShaderSupported()) RuntimeShader(BAR_SPOT_SHADER) else null
        }

        // 柔和双层环境浮空 + 近场接触阴影（原生 BlurMaskFilter 杜绝方形脏框，立住悬浮空间纵深）
        val barAmbientShadowPaint = remember(isDark, density.density) {
            Paint().apply {
                asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb(if (isDark) 90 else 32, 8, 14, 28)
                    maskFilter = BlurMaskFilter(14f * density.density, BlurMaskFilter.Blur.NORMAL)
                }
            }
        }
        val barContactShadowPaint = remember(isDark, density.density) {
            Paint().apply {
                asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb(if (isDark) 54 else 18, 8, 14, 28)
                    maskFilter = BlurMaskFilter(5.5f * density.density, BlurMaskFilter.Blur.NORMAL)
                }
            }
        }
        val pillShadowPaint = remember(isDark, density.density) {
            Paint().apply {
                asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb(if (isDark) 98 else 42, 6, 12, 26)
                    maskFilter = BlurMaskFilter(8.5f * density.density, BlurMaskFilter.Blur.NORMAL)
                }
            }
        }

        // 外层不加 clip，允许按压时 78dp 水滴对称漫出栏体上下边界与绘制柔和浮空投影
        Box(
            modifier = Modifier
                .width(totalDockWidth)
                .height(totalBarHeight),
            contentAlignment = Alignment.TopStart,
        ) {
            // =========================================================================
            // 第 0 层：悬浮胶囊双层环境浮空 + 接触外阴影
            // =========================================================================
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = panelOffset }
                    .drawBehind {
                        val r = size.height / 2f
                        val ambientDy = 6.dp.toPx()
                        val contactDy = 2.5.dp.toPx()
                        drawIntoCanvas { canvas ->
                            canvas.drawRoundRect(
                                left = 2.dp.toPx(),
                                top = ambientDy,
                                right = size.width - 2.dp.toPx(),
                                bottom = size.height + ambientDy,
                                radiusX = r,
                                radiusY = r,
                                paint = barAmbientShadowPaint,
                            )
                            canvas.drawRoundRect(
                                left = 3.dp.toPx(),
                                top = contactDy,
                                right = size.width - 3.dp.toPx(),
                                bottom = size.height + contactDy,
                                radiusX = r,
                                radiusY = r,
                                paint = barContactShadowPaint,
                            )
                        }
                    }
            )

            // =========================================================================
            // 第 1 层：双档光学底座（极致 = 7.2dp 冕牌水晶 + 26/34dp 色散厚折光；标准 = 16.5dp 温润云母重磨砂）
            // =========================================================================
            val fallbackRimBrush = remember(isDark) {
                Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (isDark) 0.34f else 0.78f),
                    0.5f to Color.White.copy(alpha = 0.06f),
                    1.0f to if (isDark) {
                        Color.White.copy(alpha = 0.16f)
                    } else {
                        Color(0xFFB8BCC8).copy(alpha = 0.38f)
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = panelOffset }
                    .layerBackdrop(barGlassBackdrop)
            ) {
                if (isShaderGlass) {
                    val activeBackdrop = backdrop!!
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBackdrop(
                                backdrop = activeBackdrop,
                                shape = { dockShape },
                                effects = {
                                    val darkNow = AppShellState.isDark
                                    val ultraNow = AppShellState.glassLevel == "ultra"
                                    if (ultraNow) {
                                        padding = maxOf(padding, 44.dp.toPx())
                                        colorControls(
                                            brightness = if (darkNow) 0.008f else 0.016f,
                                            contrast = 1.08f,
                                            saturation = 1.78f,
                                        )
                                        blur(7.2.dp.toPx(), 7.2.dp.toPx())
                                        lens(
                                            refractionHeight = 26.dp.toPx(),
                                            refractionAmount = 34.dp.toPx(),
                                            depthEffect = true,
                                            chromaticAberration = 0.08f,
                                        )
                                    } else {
                                        padding = maxOf(padding, 24.dp.toPx())
                                        colorControls(
                                            brightness = if (darkNow) -0.01f else 0.02f,
                                            contrast = 1.01f,
                                            saturation = 1.28f,
                                        )
                                        blur(16.5.dp.toPx(), 16.5.dp.toPx())
                                        lens(
                                            refractionHeight = 8.dp.toPx(),
                                            refractionAmount = 9.dp.toPx(),
                                            depthEffect = false,
                                            chromaticAberration = 0f,
                                        )
                                    }
                                },
                                highlight = {
                                    val darkNow = AppShellState.isDark
                                    val ultraNow = AppShellState.glassLevel == "ultra"
                                    val base = if (darkNow) barHighlightDark.value else barHighlightLight.value
                                    if (ultraNow) base else base.copy(alpha = (base.alpha * 0.52f).coerceIn(0f, 1f))
                                },
                                layerBlock = {
                                    val ultraNow = AppShellState.glassLevel == "ultra"
                                    val w = size.width.coerceAtLeast(1f)
                                    val expandPx = if (ultraNow) 8.dp.toPx() else 4.dp.toPx()
                                    val s = lerp(1f, 1f + expandPx / w, dampedDrag.pressProgress)
                                    scaleX = s
                                    scaleY = s
                                },
                                onDrawSurface = {
                                    val darkNow = AppShellState.isDark
                                    val ultraNow = AppShellState.glassLevel == "ultra"
                                    if (ultraNow) {
                                        val baseTint = if (darkNow) {
                                            Color(0xFF0A0D14).copy(alpha = 0.24f)
                                        } else {
                                            Color.White.copy(alpha = 0.15f)
                                        }
                                        drawRect(baseTint)
                                        drawRect(brush = barMeniscusCache.obtain(size.height, darkNow))
                                    } else {
                                        val micaTint = if (darkNow) {
                                            Color(0xFF151720).copy(alpha = 0.58f)
                                        } else {
                                            Color(0xFFF6F7FB).copy(alpha = 0.50f)
                                        }
                                        drawRect(micaTint)
                                    }
                                },
                                onDrawFront = {
                                    val darkNow = AppShellState.isDark
                                    val ultraNow = AppShellState.glassLevel == "ultra"
                                    // 浅色白卡场景下叠极细 0.55dp 菲涅尔光学冷灰勾线，防止白底白卡胶囊边界隐形
                                    if (!darkNow) {
                                        val strokePx = 0.55.dp.toPx()
                                        val inset = strokePx * 0.5f
                                        val w = (size.width - strokePx).coerceAtLeast(1f)
                                        val h = (size.height - strokePx).coerceAtLeast(1f)
                                        val r = minOf(w, h) / 2f
                                        drawRoundRect(
                                            color = Color(0xFF182232).copy(alpha = if (ultraNow) 0.082f else 0.068f),
                                            topLeft = Offset(inset, inset),
                                            size = Size(w, h),
                                            cornerRadius = CornerRadius(r, r),
                                            style = Stroke(width = strokePx),
                                        )
                                    }
                                    val p = dampedDrag.pressProgress
                                    if (p > 0.005f && spotShader != null) {
                                        val spotX = (innerPaddingPx + (dampedDrag.value + 0.5f) * itemWidthPx)
                                            .coerceIn(0f, size.width)
                                        val spotY = with(density) { (dockHeight / 2).toPx() }
                                        val radius = (size.minDimension * 1.4f).coerceAtLeast(1f)
                                        val spotBoost = if (ultraNow) {
                                            if (darkNow) 0.09f else 0.14f
                                        } else {
                                            if (darkNow) 0.055f else 0.075f
                                        }
                                        val spotColor = Color.White.copy(alpha = spotBoost * p)
                                        spotShader.setColorUniform("color", spotColor)
                                        spotShader.setFloatUniform("radius", radius)
                                        spotShader.setFloatUniform("position", spotX, spotY)
                                        drawRect(brush = spotShader.asBrush(), blendMode = BlendMode.Plus)
                                    }
                                },
                            )
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(dockShape)
                            .background(fallbackBarColor)
                            .border(width = 0.8.dp, brush = fallbackRimBrush, shape = dockShape)
                    )
                }
            }

            // =========================================================================
            // 第 2 层：栏体静态未激活态深灰/银白 Tab 行
            // =========================================================================
            DockTabsRow(
                items = items,
                iconPaths = iconPaths,
                color = inactiveColor,
                itemWidth = itemWidth,
                dockHeight = dockHeight,
                innerPadding = innerPadding,
                scaleProvider = { 1f },
                boldIndex = -1,
                modifier = Modifier.graphicsLayer { translationX = panelOffset },
            )

            // =========================================================================
            // 第 3 层：离屏录制层（tabsBackdrop = 分档动态消融栏体磨砂子层 + 放大亮蓝 Tab）
            // 严禁直接修改 barGlassBackdrop.graphicsLayer.alpha（避免影响第 1 层屏幕显示）：
            // - 极致档 (ultra)：(1f - 0.94f * p)，按压升起时磨砂消融至 0.06f 化作 94%+ 清澈纯水水滴；
            // - 标准档 (standard)：(1f - 0.38f * p)，按压滑行时保留 62% 温润柔雾云母质感。
            // =========================================================================
            if (isShaderGlass && combinedBackdrop != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics {}
                        .graphicsLayer { translationX = panelOffset }
                        .offscreenLayerBackdrop(tabsBackdrop)
                        .drawWithContent {
                            if (dampedDrag.pressProgress >= -1f) {
                                drawContent()
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val dissolve = if (ultraNow) 0.94f else 0f
                                alpha = (1f - dissolve * dampedDrag.pressProgress).coerceIn(0f, 1f)
                            }
                            .drawWithContent {
                                drawLayer(barGlassBackdrop.graphicsLayer)
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                if (!ultraNow) {
                                    val darkNow = AppShellState.isDark
                                    val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                                    val cushionAlpha = if (darkNow) lerp(0.11f, 0.08f, p) else lerp(0.42f, 0.34f, p)
                                    drawRect(Color.White.copy(alpha = cushionAlpha))
                                }
                            }
                    )
                    DockTabsRow(
                        items = items,
                        iconPaths = iconPaths,
                        color = accentColor,
                        itemWidth = itemWidth,
                        dockHeight = dockHeight,
                        innerPadding = innerPadding,
                        scaleProvider = {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            lerp(1f, if (ultraNow) 1.22f else 1.08f, dampedDrag.pressProgress)
                        },
                        boldIndex = activeBoldIndex,
                    )
                }
            }

            // =========================================================================
            // 第 4 层：双档药丸（极致 = 1.37x 跨栏界色散水滴；标准 = 1.14x 温润云母丝缎气垫）
            // =========================================================================
            if (isShaderGlass && combinedBackdrop != null) {
                // 4a. 药丸外部接触与浮空阴影（graphicsLayer 保持 alpha=1f 避免 RenderNode 方形裁切外溢 BlurMaskFilter）
                Box(
                    modifier = Modifier
                        .padding(horizontal = innerPadding, vertical = innerPadding)
                        .width(itemWidth)
                        .height(pillHeight)
                        .graphicsLayer {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            translationX = dampedDrag.value * itemWidthPx + panelOffset
                            val v = dampedDrag.velocity / 10f
                            val baseSX = if (ultraNow) dampedDrag.scaleX else 1f + (dampedDrag.scaleX - 1f) * 0.35f
                            val baseSY = if (ultraNow) dampedDrag.scaleY else 1f + (dampedDrag.scaleY - 1f) * 0.35f
                            val stretchMul = if (ultraNow) 1f else 0.40f
                            scaleX = baseSX / (1f - (v * 0.75f * stretchMul).coerceIn(-0.22f, 0.22f))
                            scaleY = baseSY * (1f - (v * 0.25f * stretchMul).coerceIn(-0.22f, 0.22f))
                            clip = false
                        }
                        .drawBehind {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                            val baseAlpha = if (darkNow) 98 else 42
                            val restShadowMul = if (ultraNow) 0.26f else 0.16f
                            val peakShadowMul = if (ultraNow) 1f else 0.56f
                            val alphaMul = lerp(restShadowMul, peakShadowMul, p).coerceIn(0f, 1f)
                            val targetAlpha = (baseAlpha * alphaMul).roundToInt().coerceIn(0, 255)
                            if (targetAlpha > 0) {
                                pillShadowPaint.asFrameworkPaint().alpha = targetAlpha
                                val r = size.height / 2f
                                val dy = if (ultraNow) {
                                    lerp(2.dp.toPx(), 5.dp.toPx(), p)
                                } else {
                                    lerp(1.5.dp.toPx(), 2.8.dp.toPx(), p)
                                }
                                drawIntoCanvas { canvas ->
                                    canvas.drawRoundRect(
                                        left = 0f,
                                        top = dy,
                                        right = size.width,
                                        bottom = size.height + dy,
                                        radiusX = r,
                                        radiusY = r,
                                        paint = pillShadowPaint,
                                    )
                                }
                            }
                        }
                )

                // 4b. 药丸光学本体（实时读取 ultraNow 即时切换水晶凸透镜 vs 云母丝缎气垫）
                Box(
                    modifier = Modifier
                        .padding(horizontal = innerPadding, vertical = innerPadding)
                        .width(itemWidth)
                        .height(pillHeight)
                        .graphicsLayer {
                            translationX = dampedDrag.value * itemWidthPx + panelOffset
                        }
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { pillShape },
                            effects = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                                if (ultraNow) {
                                    val restDispersion = if (darkNow) 0.14f else 0.15f
                                    lens(
                                        refractionHeight = lerp(8.dp.toPx(), 17.dp.toPx(), p),
                                        refractionAmount = lerp(14.dp.toPx(), 28.dp.toPx(), p),
                                        depthEffect = true,
                                        chromaticAberration = lerp(restDispersion, 0.30f, p),
                                    )
                                } else {
                                    lens(
                                        refractionHeight = lerp(2.dp.toPx(), 4.5.dp.toPx(), p),
                                        refractionAmount = lerp(3.dp.toPx(), 5.5.dp.toPx(), p),
                                        depthEffect = false,
                                        chromaticAberration = 0f,
                                    )
                                }
                            },
                            highlight = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                                val base = if (darkNow) pillHighlightDark.value else pillHighlightLight.value
                                val hAlpha = if (ultraNow) 0.76f + 0.24f * p else 0.42f + 0.25f * p
                                base.copy(alpha = hAlpha.coerceIn(0f, 1f))
                            },
                            layerBlock = {
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val v = dampedDrag.velocity / 10f
                                val baseSX = if (ultraNow) dampedDrag.scaleX else 1f + (dampedDrag.scaleX - 1f) * 0.35f
                                val baseSY = if (ultraNow) dampedDrag.scaleY else 1f + (dampedDrag.scaleY - 1f) * 0.35f
                                val stretchMul = if (ultraNow) 1f else 0.40f
                                scaleX = baseSX / (1f - (v * 0.75f * stretchMul).coerceIn(-0.22f, 0.22f))
                                scaleY = baseSY * (1f - (v * 0.25f * stretchMul).coerceIn(-0.22f, 0.22f))
                            },
                            onDrawSurface = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                                if (ultraNow) {
                                    // 静止态 (1 - p)：纯净水晶凸面弯月反光（Cabochon Meniscus），零脏灰贴膜
                                    if (p < 0.999f) {
                                        drawRect(
                                            brush = pillCabochonCache.obtain(size.height, darkNow),
                                            alpha = (1f - p).coerceIn(0f, 1f),
                                        )
                                    }
                                    // 升起态 (p)：平滑过渡为 98% 纯净清透水体，完整显露跨栏界折射与顶部新月暗影
                                    if (p > 0.001f) {
                                        drawRect(color = Color(0xFF08101E).copy(alpha = 0.02f * p))
                                    }
                                } else {
                                    drawRect(
                                        brush = pillMicaSheenCache.obtain(size.height, darkNow),
                                        alpha = lerp(1f, 0.72f, p),
                                    )
                                }
                            },
                            onDrawFront = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                                // 浅色白卡场景全天候保留 0.55dp 极细菲涅尔光学冷灰勾边，确保白底上晶莹分明
                                if (!darkNow) {
                                    val strokePx = 0.55.dp.toPx()
                                    val inset = strokePx * 0.5f
                                    val w = (size.width - strokePx).coerceAtLeast(1f)
                                    val h = (size.height - strokePx).coerceAtLeast(1f)
                                    val r = minOf(w, h) / 2f
                                    val strokeAlpha = if (ultraNow) lerp(0.105f, 0.135f, p) else lerp(0.065f, 0.085f, p)
                                    drawRoundRect(
                                        color = Color(0xFF1C283C).copy(alpha = strokeAlpha),
                                        topLeft = Offset(inset, inset),
                                        size = Size(w, h),
                                        cornerRadius = CornerRadius(r, r),
                                        style = Stroke(width = strokePx),
                                    )
                                }
                            },
                        )
                        .innerShadow(shape = pillShape) {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                            if (ultraNow) {
                                val radiusDp = lerpDp(3.2.dp, 9.dp, p)
                                val offsetYDp = lerpDp(1.8.dp, 8.2.dp, p)
                                val restShadowColor = if (darkNow) {
                                    Color.Black.copy(alpha = 0.20f)
                                } else {
                                    Color(0xFF16243B).copy(alpha = 0.105f)
                                }
                                val liftedShadowColor = if (darkNow) {
                                    Color.Black.copy(alpha = 0.34f)
                                } else {
                                    Color(0xFF08101E).copy(alpha = 0.26f)
                                }
                                InnerShadow(
                                    radius = radiusDp,
                                    offset = DpOffset(0.dp, offsetYDp),
                                    color = lerpColor(restShadowColor, liftedShadowColor, p),
                                    alpha = 1f,
                                )
                            } else {
                                val radiusDp = lerpDp(2.dp, 4.5.dp, p)
                                val offsetYDp = lerpDp(1.dp, 3.2.dp, p)
                                val restShadowColor = if (darkNow) {
                                    Color.Black.copy(alpha = 0.12f)
                                } else {
                                    Color(0xFF16243B).copy(alpha = 0.055f)
                                }
                                val liftedShadowColor = if (darkNow) {
                                    Color.Black.copy(alpha = 0.18f)
                                } else {
                                    Color(0xFF16243B).copy(alpha = 0.10f)
                                }
                                InnerShadow(
                                    radius = radiusDp,
                                    offset = DpOffset(0.dp, offsetYDp),
                                    color = lerpColor(restShadowColor, liftedShadowColor, p),
                                    alpha = 1f,
                                )
                            }
                        }
                )
            } else {
                // 「液态玻璃：无」或低版本系统保底分支：裁切视窗式滑动胶囊（同样零重影）
                Box(
                    modifier = Modifier
                        .padding(horizontal = innerPadding, vertical = innerPadding)
                        .width(itemWidth)
                        .height(pillHeight)
                        .graphicsLayer {
                            translationX = dampedDrag.value * itemWidthPx + panelOffset
                        }
                        .clip(pillShape)
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.13f) else accentColor.copy(alpha = 0.10f),
                            pillShape,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            .wrapContentWidth(align = Alignment.Start, unbounded = true)
                            .requiredWidth(contentWidth)
                            .height(pillHeight)
                            .graphicsLayer {
                                translationX = -dampedDrag.value * itemWidthPx
                            }
                    ) {
                        DockTabsRow(
                            items = items,
                            iconPaths = iconPaths,
                            color = accentColor,
                            itemWidth = itemWidth,
                            dockHeight = pillHeight,
                            innerPadding = 0.dp,
                            scaleProvider = { 1f },
                            boldIndex = activeBoldIndex,
                        )
                    }
                }
            }

            // =========================================================================
            // 第 5 层：全栏统一阻尼手势捕获区（覆盖交互高度 dockHeight）
            // =========================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dockHeight)
                    .then(dampedDrag.modifier)
            )
        }
    }
}

/**
 * 手机紧凑横屏专属：Liquid Glass 左侧悬浮竖排液态玻璃导轨（宽 58dp，高约 248dp）：
 * 复用同源 5 层 LayerBackdrop + offscreenLayerBackdrop + 垂直 DampedDragAnimation 水滴凸透镜折射滑块。
 */
@Composable
private fun VerticalLiquidGlassRail(
    backdrop: LayerBackdrop?,
    isShaderGlass: Boolean,
    isDark: Boolean,
    items: List<DockNavItem>,
    iconPaths: List<List<androidx.compose.ui.graphics.Path>>,
    selectedIndex: Int,
    accentColor: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val onSelectUpdated by rememberUpdatedState(onSelect)

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val maxAvailHeight = (configuration.screenHeightDp.dp - statusTop - navBottomInset - 56.dp)
        .coerceAtLeast(196.dp)

    val railWidth = 58.dp
    val railHeight = 248.dp.coerceAtMost(maxAvailHeight)
    val innerPadding = 4.dp
    val innerPaddingPx = with(density) { innerPadding.toPx() }
    val railHeightPx = with(density) { railHeight.toPx() }

    val contentHeight = railHeight - innerPadding * 2
    val itemHeight = contentHeight / items.size
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val pillWidth = railWidth - innerPadding * 2
    val pillHeight = itemHeight

    val railShape = CircleShape
    val pillShape = CircleShape

    val inactiveColor = if (isDark) Color(0xD6EBEBF5) else Color(0xFF1A1C20).copy(alpha = 0.82f)
    val fallbackRailColor = if (isDark) {
        Color(0xFF1A1C22).copy(alpha = 0.92f)
    } else {
        Color(0xFFF8F9FC).copy(alpha = 0.93f)
    }

    // 纵向橡皮筋越界微移（拖到上下两端时整轨 ±4dp 弹性跟随）
    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffsetY by remember(railHeightPx, rubberBandPx) {
        derivedStateOf {
            if (railHeightPx <= 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / railHeightPx).coerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    var lastHapticIdx by remember { mutableIntStateOf(selectedIndex) }

    fun indexAtY(posY: Float): Int {
        if (itemHeightPx <= 0f) return currentIndex
        return ((posY - innerPaddingPx) / itemHeightPx).toInt().coerceIn(0, items.lastIndex)
    }

    // 竖排导轨水滴膨胀比例：50dp → 68dp（左右各对称漫出导轨边缘 5dp，触发弯月面折射）
    val pressedScaleRatio = 68f / 50f

    val dampedDrag = remember(animationScope, items.size, itemHeightPx, pressedScaleRatio) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(items.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = pressedScaleRatio,
            isVertical = true,
            onDragStarted = { position ->
                val hit = indexAtY(position.y)
                lastHapticIdx = hit
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (hit != targetValue.roundToInt()) {
                    updateValue(hit.toFloat())
                }
            },
            onDragStopped = {
                val targetIdx = targetValue.roundToInt().coerceIn(0, items.lastIndex)
                updateValue(targetIdx.toFloat())
                if (currentIndex != targetIdx) {
                    currentIndex = targetIdx
                }
                onSelectUpdated(targetIdx)
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (itemHeightPx > 0f && dragAmount.y != 0f) {
                    val next = (targetValue + dragAmount.y / itemHeightPx)
                        .coerceIn(0f, (items.size - 1).toFloat())
                    updateValue(next)
                    val hovered = next.roundToInt().coerceIn(0, items.lastIndex)
                    if (hovered != lastHapticIdx) {
                        lastHapticIdx = hovered
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.y)
                    }
                }
            },
        )
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) {
            currentIndex = selectedIndex
            lastHapticIdx = selectedIndex
            dampedDrag.animateToValue(selectedIndex.toFloat())
        }
    }

    val activeBoldIndex by remember(dampedDrag) {
        derivedStateOf { dampedDrag.value.roundToInt().coerceIn(0, items.lastIndex) }
    }

    val barGlassBackdrop = rememberLayerBackdrop()
    val railTabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = backdrop?.let { rememberCombinedBackdrop(it, railTabsBackdrop) }

    val barMeniscusCache = remember { BarMeniscusBrushCache() }
    val pillCabochonCache = remember { PillCabochonBrushCache() }
    val pillMicaSheenCache = remember { PillMicaSheenBrushCache() }

    val barHighlightLight = rememberIosDirectionalHighlight(
        width = 0.85.dp,
        color = Color.White.copy(alpha = 0.88f),
        baseDegrees = 45f,
        falloff = 1.12f,
        shaderKey = "IosRailDirectionalHighlight",
    )
    val barHighlightDark = rememberIosDirectionalHighlight(
        width = 0.85.dp,
        color = Color.White.copy(alpha = 0.64f),
        baseDegrees = 45f,
        falloff = 1.12f,
        shaderKey = "IosRailDirectionalHighlight",
    )
    val pillHighlightLight = rememberIosDirectionalHighlight(
        width = 0.9.dp,
        color = Color.White.copy(alpha = 0.94f),
        baseDegrees = 45f,
        falloff = 1.02f,
        shaderKey = "IosRailPillDirectionalHighlight",
    )
    val pillHighlightDark = rememberIosDirectionalHighlight(
        width = 0.9.dp,
        color = Color.White.copy(alpha = 0.72f),
        baseDegrees = 45f,
        falloff = 1.02f,
        shaderKey = "IosRailPillDirectionalHighlight",
    )

    val spotShader = remember {
        if (isRuntimeShaderSupported()) RuntimeShader(BAR_SPOT_SHADER) else null
    }

    val railAmbientShadowPaint = remember(isDark, density.density) {
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(if (isDark) 90 else 32, 8, 14, 28)
                maskFilter = BlurMaskFilter(13f * density.density, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val railContactShadowPaint = remember(isDark, density.density) {
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(if (isDark) 54 else 18, 8, 14, 28)
                maskFilter = BlurMaskFilter(5f * density.density, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val pillShadowPaint = remember(isDark, density.density) {
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(if (isDark) 98 else 42, 6, 12, 26)
                maskFilter = BlurMaskFilter(8f * density.density, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }

    val fallbackRimBrush = remember(isDark) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = if (isDark) 0.34f else 0.78f),
            0.5f to Color.White.copy(alpha = 0.06f),
            1.0f to if (isDark) {
                Color.White.copy(alpha = 0.16f)
            } else {
                Color(0xFFB8BCC8).copy(alpha = 0.38f)
            },
        )
    }

    Box(
        modifier = modifier
            .width(railWidth)
            .height(railHeight),
        contentAlignment = Alignment.TopStart,
    ) {
        // 第 0 层：竖排导轨双层环境浮空 + 接触阴影
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = panelOffsetY }
                .drawBehind {
                    val r = size.width / 2f
                    val dx = 2.5.dp.toPx()
                    val ambientDy = 5.5.dp.toPx()
                    val contactDy = 2.dp.toPx()
                    drawIntoCanvas { canvas ->
                        canvas.drawRoundRect(
                            left = dx,
                            top = 2.dp.toPx() + ambientDy,
                            right = size.width + dx,
                            bottom = size.height - 2.dp.toPx() + ambientDy,
                            radiusX = r,
                            radiusY = r,
                            paint = railAmbientShadowPaint,
                        )
                        canvas.drawRoundRect(
                            left = 1.dp.toPx(),
                            top = 2.dp.toPx() + contactDy,
                            right = size.width + 1.dp.toPx(),
                            bottom = size.height - 2.dp.toPx() + contactDy,
                            radiusX = r,
                            radiusY = r,
                            paint = railContactShadowPaint,
                        )
                    }
                }
        )

        // 第 1 层：双档竖排光学底座（极致 = 7.2dp 冕牌水晶 + 26/34dp 色散厚折光；标准 = 16.5dp 温润云母重磨砂）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = panelOffsetY }
                .layerBackdrop(barGlassBackdrop)
        ) {
            if (isShaderGlass && backdrop != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { railShape },
                            effects = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                if (ultraNow) {
                                    padding = maxOf(padding, 44.dp.toPx())
                                    colorControls(
                                        brightness = if (darkNow) 0.008f else 0.016f,
                                        contrast = 1.08f,
                                        saturation = 1.78f,
                                    )
                                    blur(7.2.dp.toPx(), 7.2.dp.toPx())
                                    lens(
                                        refractionHeight = 26.dp.toPx(),
                                        refractionAmount = 34.dp.toPx(),
                                        depthEffect = true,
                                        chromaticAberration = 0.08f,
                                    )
                                } else {
                                    padding = maxOf(padding, 24.dp.toPx())
                                    colorControls(
                                        brightness = if (darkNow) -0.01f else 0.02f,
                                        contrast = 1.01f,
                                        saturation = 1.28f,
                                    )
                                    blur(16.5.dp.toPx(), 16.5.dp.toPx())
                                    lens(
                                        refractionHeight = 8.dp.toPx(),
                                        refractionAmount = 9.dp.toPx(),
                                        depthEffect = false,
                                        chromaticAberration = 0f,
                                    )
                                }
                            },
                            highlight = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val base = if (darkNow) barHighlightDark.value else barHighlightLight.value
                                if (ultraNow) base else base.copy(alpha = (base.alpha * 0.52f).coerceIn(0f, 1f))
                            },
                            layerBlock = {
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val h = size.height.coerceAtLeast(1f)
                                val expandPx = if (ultraNow) 6.dp.toPx() else 3.dp.toPx()
                                val s = lerp(1f, 1f + expandPx / h, dampedDrag.pressProgress)
                                scaleX = s
                                scaleY = s
                            },
                            onDrawSurface = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                if (ultraNow) {
                                    val baseTint = if (darkNow) {
                                        Color(0xFF0A0D14).copy(alpha = 0.24f)
                                    } else {
                                        Color.White.copy(alpha = 0.15f)
                                    }
                                    drawRect(baseTint)
                                    drawRect(brush = barMeniscusCache.obtain(size.height, darkNow))
                                } else {
                                    val micaTint = if (darkNow) {
                                        Color(0xFF151720).copy(alpha = 0.58f)
                                    } else {
                                        Color(0xFFF6F7FB).copy(alpha = 0.50f)
                                    }
                                    drawRect(micaTint)
                                }
                            },
                            onDrawFront = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                if (!darkNow) {
                                    val strokePx = 0.55.dp.toPx()
                                    val inset = strokePx * 0.5f
                                    val w = (size.width - strokePx).coerceAtLeast(1f)
                                    val h = (size.height - strokePx).coerceAtLeast(1f)
                                    val r = minOf(w, h) / 2f
                                    drawRoundRect(
                                        color = Color(0xFF182232).copy(alpha = if (ultraNow) 0.082f else 0.068f),
                                        topLeft = Offset(inset, inset),
                                        size = Size(w, h),
                                        cornerRadius = CornerRadius(r, r),
                                        style = Stroke(width = strokePx),
                                    )
                                }
                                val p = dampedDrag.pressProgress
                                if (p > 0.005f && spotShader != null) {
                                    val spotX = size.width * 0.5f
                                    val spotY = (innerPaddingPx + (dampedDrag.value + 0.5f) * itemHeightPx)
                                        .coerceIn(0f, size.height)
                                    val radius = (size.minDimension * 1.45f).coerceAtLeast(1f)
                                    val spotBoost = if (ultraNow) {
                                        if (darkNow) 0.09f else 0.14f
                                    } else {
                                        if (darkNow) 0.055f else 0.075f
                                    }
                                    val spotColor = Color.White.copy(alpha = spotBoost * p)
                                    spotShader.setColorUniform("color", spotColor)
                                    spotShader.setFloatUniform("radius", radius)
                                    spotShader.setFloatUniform("position", spotX, spotY)
                                    drawRect(brush = spotShader.asBrush(), blendMode = BlendMode.Plus)
                                }
                            },
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(railShape)
                        .background(fallbackRailColor)
                        .border(width = 0.8.dp, brush = fallbackRimBrush, shape = railShape)
                )
            }
        }

        // 第 2 层：静态未激活竖排 Tab 列
        RailTabsColumn(
            items = items,
            iconPaths = iconPaths,
            color = inactiveColor,
            railWidth = railWidth,
            itemHeight = itemHeight,
            innerPadding = innerPadding,
            scaleProvider = { 1f },
            boldIndex = -1,
            modifier = Modifier.graphicsLayer { translationY = panelOffsetY },
        )

        // 第 3 层：离屏亮蓝放大镜像与分档动态消融层（railTabsBackdrop，绝不触碰 barGlassBackdrop.graphicsLayer.alpha）
        if (isShaderGlass && combinedBackdrop != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .graphicsLayer { translationY = panelOffsetY }
                    .offscreenLayerBackdrop(railTabsBackdrop)
                    .drawWithContent {
                        if (dampedDrag.pressProgress >= -1f) {
                            drawContent()
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val dissolve = if (ultraNow) 0.94f else 0f
                            alpha = (1f - dissolve * dampedDrag.pressProgress).coerceIn(0f, 1f)
                        }
                        .drawWithContent {
                            drawLayer(barGlassBackdrop.graphicsLayer)
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            if (!ultraNow) {
                                val darkNow = AppShellState.isDark
                                val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                                val cushionAlpha = if (darkNow) lerp(0.11f, 0.08f, p) else lerp(0.42f, 0.34f, p)
                                drawRect(Color.White.copy(alpha = cushionAlpha))
                            }
                        }
                )
                RailTabsColumn(
                    items = items,
                    iconPaths = iconPaths,
                    color = accentColor,
                    railWidth = railWidth,
                    itemHeight = itemHeight,
                    innerPadding = innerPadding,
                    scaleProvider = {
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        lerp(1f, if (ultraNow) 1.22f else 1.08f, dampedDrag.pressProgress)
                    },
                    boldIndex = activeBoldIndex,
                )
            }
        }

        // 第 4 层：双档竖向滑块（极致 = 跨栏界色散水滴；标准 = 1.13x 温润云母丝缎气垫）
        if (isShaderGlass && combinedBackdrop != null) {
            Box(
                modifier = Modifier
                    .padding(horizontal = innerPadding, vertical = innerPadding)
                    .width(pillWidth)
                    .height(pillHeight)
                    .graphicsLayer {
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        translationY = dampedDrag.value * itemHeightPx + panelOffsetY
                        val speed = abs(dampedDrag.velocity) / 10f
                        val baseSY = if (ultraNow) dampedDrag.scaleY else 1f + (dampedDrag.scaleY - 1f) * 0.35f
                        val baseSX = if (ultraNow) dampedDrag.scaleX else 1f + (dampedDrag.scaleX - 1f) * 0.35f
                        val stretchMul = if (ultraNow) 1f else 0.40f
                        scaleY = baseSY / (1f - (speed * 0.65f * stretchMul).coerceIn(0f, 0.20f))
                        scaleX = baseSX * (1f - (speed * 0.25f * stretchMul).coerceIn(0f, 0.16f))
                        clip = false
                    }
                    .drawBehind {
                        val darkNow = AppShellState.isDark
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                        val baseAlpha = if (darkNow) 98 else 42
                        val restShadowMul = if (ultraNow) 0.26f else 0.16f
                        val peakShadowMul = if (ultraNow) 1f else 0.56f
                        val alphaMul = lerp(restShadowMul, peakShadowMul, p).coerceIn(0f, 1f)
                        val targetAlpha = (baseAlpha * alphaMul).roundToInt().coerceIn(0, 255)
                        if (targetAlpha > 0) {
                            pillShadowPaint.asFrameworkPaint().alpha = targetAlpha
                            val r = size.minDimension / 2f
                            val dy = if (ultraNow) {
                                lerp(2.dp.toPx(), 4.5.dp.toPx(), p)
                            } else {
                                lerp(1.5.dp.toPx(), 2.6.dp.toPx(), p)
                            }
                            drawIntoCanvas { canvas ->
                                canvas.drawRoundRect(
                                    left = 0f,
                                    top = dy,
                                    right = size.width,
                                    bottom = size.height + dy,
                                    radiusX = r,
                                    radiusY = r,
                                    paint = pillShadowPaint,
                                )
                            }
                        }
                    }
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = innerPadding, vertical = innerPadding)
                    .width(pillWidth)
                    .height(pillHeight)
                    .graphicsLayer {
                        translationY = dampedDrag.value * itemHeightPx + panelOffsetY
                    }
                    .drawBackdrop(
                        backdrop = combinedBackdrop,
                        shape = { pillShape },
                        effects = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                            if (ultraNow) {
                                val restDispersion = if (darkNow) 0.14f else 0.15f
                                lens(
                                    refractionHeight = lerp(8.dp.toPx(), 17.dp.toPx(), p),
                                    refractionAmount = lerp(14.dp.toPx(), 28.dp.toPx(), p),
                                    depthEffect = true,
                                    chromaticAberration = lerp(restDispersion, 0.30f, p),
                                )
                            } else {
                                lens(
                                    refractionHeight = lerp(2.dp.toPx(), 4.5.dp.toPx(), p),
                                    refractionAmount = lerp(3.dp.toPx(), 5.5.dp.toPx(), p),
                                    depthEffect = false,
                                    chromaticAberration = 0f,
                                )
                            }
                        },
                        highlight = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                            val base = if (darkNow) pillHighlightDark.value else pillHighlightLight.value
                            val hAlpha = if (ultraNow) 0.76f + 0.24f * p else 0.42f + 0.25f * p
                            base.copy(alpha = hAlpha.coerceIn(0f, 1f))
                        },
                        layerBlock = {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val speed = abs(dampedDrag.velocity) / 10f
                            val baseSY = if (ultraNow) dampedDrag.scaleY else 1f + (dampedDrag.scaleY - 1f) * 0.35f
                            val baseSX = if (ultraNow) dampedDrag.scaleX else 1f + (dampedDrag.scaleX - 1f) * 0.35f
                            val stretchMul = if (ultraNow) 1f else 0.40f
                            scaleY = baseSY / (1f - (speed * 0.65f * stretchMul).coerceIn(0f, 0.20f))
                            scaleX = baseSX * (1f - (speed * 0.25f * stretchMul).coerceIn(0f, 0.16f))
                        },
                        onDrawSurface = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                            if (ultraNow) {
                                if (p < 0.999f) {
                                    drawRect(
                                        brush = pillCabochonCache.obtain(size.height, darkNow),
                                        alpha = (1f - p).coerceIn(0f, 1f),
                                    )
                                }
                                if (p > 0.001f) {
                                    drawRect(color = Color(0xFF08101E).copy(alpha = 0.02f * p))
                                }
                            } else {
                                drawRect(
                                    brush = pillMicaSheenCache.obtain(size.height, darkNow),
                                    alpha = lerp(1f, 0.72f, p),
                                )
                            }
                        },
                        onDrawFront = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                            if (!darkNow) {
                                val strokePx = 0.55.dp.toPx()
                                val inset = strokePx * 0.5f
                                val w = (size.width - strokePx).coerceAtLeast(1f)
                                val h = (size.height - strokePx).coerceAtLeast(1f)
                                val r = minOf(w, h) / 2f
                                val strokeAlpha = if (ultraNow) lerp(0.105f, 0.135f, p) else lerp(0.065f, 0.085f, p)
                                drawRoundRect(
                                    color = Color(0xFF1C283C).copy(alpha = strokeAlpha),
                                    topLeft = Offset(inset, inset),
                                    size = Size(w, h),
                                    cornerRadius = CornerRadius(r, r),
                                    style = Stroke(width = strokePx),
                                )
                            }
                        },
                    )
                    .innerShadow(shape = pillShape) {
                        val darkNow = AppShellState.isDark
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        val p = dampedDrag.pressProgress.coerceIn(0f, 1f)
                        if (ultraNow) {
                            val radiusDp = lerpDp(3.2.dp, 9.dp, p)
                            val offsetYDp = lerpDp(1.8.dp, 8.2.dp, p)
                            val restShadowColor = if (darkNow) {
                                Color.Black.copy(alpha = 0.20f)
                            } else {
                                Color(0xFF16243B).copy(alpha = 0.105f)
                            }
                            val liftedShadowColor = if (darkNow) {
                                Color.Black.copy(alpha = 0.34f)
                            } else {
                                Color(0xFF08101E).copy(alpha = 0.26f)
                            }
                            InnerShadow(
                                radius = radiusDp,
                                offset = DpOffset(0.dp, offsetYDp),
                                color = lerpColor(restShadowColor, liftedShadowColor, p),
                                alpha = 1f,
                            )
                        } else {
                            val radiusDp = lerpDp(2.dp, 4.5.dp, p)
                            val offsetYDp = lerpDp(1.dp, 3.2.dp, p)
                            val restShadowColor = if (darkNow) {
                                Color.Black.copy(alpha = 0.12f)
                            } else {
                                Color(0xFF16243B).copy(alpha = 0.055f)
                            }
                            val liftedShadowColor = if (darkNow) {
                                Color.Black.copy(alpha = 0.18f)
                            } else {
                                Color(0xFF16243B).copy(alpha = 0.10f)
                            }
                            InnerShadow(
                                radius = radiusDp,
                                offset = DpOffset(0.dp, offsetYDp),
                                color = lerpColor(restShadowColor, liftedShadowColor, p),
                                alpha = 1f,
                            )
                        }
                    }
            )
        } else {
            // 「液态玻璃：无」或低版本系统保底分支：竖向裁切视窗滑动胶囊
            Box(
                modifier = Modifier
                    .padding(horizontal = innerPadding, vertical = innerPadding)
                    .width(pillWidth)
                    .height(pillHeight)
                    .graphicsLayer {
                        translationY = dampedDrag.value * itemHeightPx + panelOffsetY
                    }
                    .clip(pillShape)
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.13f) else accentColor.copy(alpha = 0.10f),
                        pillShape,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .width(pillWidth)
                        .requiredHeight(contentHeight)
                        .graphicsLayer {
                            translationY = -dampedDrag.value * itemHeightPx
                        }
                ) {
                    RailTabsColumn(
                        items = items,
                        iconPaths = iconPaths,
                        color = accentColor,
                        railWidth = pillWidth,
                        itemHeight = itemHeight,
                        innerPadding = 0.dp,
                        scaleProvider = { 1f },
                        boldIndex = activeBoldIndex,
                    )
                }
            }
        }

        // 第 5 层：全轨统一阻尼点按与竖向滑动手势捕获区
        Box(
            modifier = Modifier
                .width(railWidth)
                .height(railHeight)
                .then(dampedDrag.modifier)
        )
    }
}

@Composable
private fun RailTabsColumn(
    items: List<DockNavItem>,
    iconPaths: List<List<androidx.compose.ui.graphics.Path>>,
    color: Color,
    railWidth: Dp,
    itemHeight: Dp,
    innerPadding: Dp,
    scaleProvider: () -> Float,
    boldIndex: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(railWidth)
            .padding(horizontal = innerPadding, vertical = innerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEachIndexed { index, item ->
            val subPaths = iconPaths.getOrNull(index) ?: emptyList()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .graphicsLayer {
                        val s = scaleProvider()
                        scaleX = s
                        scaleY = s
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Canvas(modifier = Modifier.size(19.5.dp)) {
                    val pathScale = size.width / 22.dp.toPx()
                    val strokeWidthPx = 1.9f * (size.width / 24f) / pathScale
                    scale(pathScale, pathScale, pivot = Offset.Zero) {
                        subPaths.forEachIndexed { k, path ->
                            val pathAlpha = if (index == 0 && k == subPaths.size - 1) 0.55f else 1.0f
                            drawPath(
                                path = path,
                                color = color.copy(alpha = color.alpha * pathAlpha),
                                style = Stroke(
                                    width = strokeWidthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.label,
                    fontSize = 10.sp,
                    lineHeight = 11.5.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = if (index == boldIndex || boldIndex < 0) FontWeight.SemiBold else FontWeight.Medium,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun DockTabsRow(
    items: List<DockNavItem>,
    iconPaths: List<List<androidx.compose.ui.graphics.Path>>,
    color: Color,
    itemWidth: Dp,
    dockHeight: Dp,
    innerPadding: Dp,
    scaleProvider: () -> Float,
    boldIndex: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dockHeight)
            .padding(horizontal = innerPadding, vertical = innerPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val subPaths = iconPaths.getOrNull(index) ?: emptyList()
            Column(
                modifier = Modifier
                    .width(itemWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val s = scaleProvider()
                        scaleX = s
                        scaleY = s
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Canvas(modifier = Modifier.size(21.dp)) {
                    val strokeWidthPx = 1.9f * (size.width / 24f)
                    subPaths.forEachIndexed { k, path ->
                        val pathAlpha = if (index == 0 && k == subPaths.size - 1) 0.55f else 1.0f
                        drawPath(
                            path = path,
                            color = color.copy(alpha = color.alpha * pathAlpha),
                            style = Stroke(
                                width = strokeWidthPx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.5.dp))
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = if (index == boldIndex || boldIndex < 0) FontWeight.SemiBold else FontWeight.Medium,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun ClassicIosDockedTabBar(
    backdrop: LayerBackdrop?,
    isShaderGlass: Boolean,
    isDark: Boolean,
    items: List<DockNavItem>,
    iconPaths: List<List<androidx.compose.ui.graphics.Path>>,
    selectedIndex: Int,
    accentColor: Color,
    navBottom: Dp,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val navPadding = WindowInsets.navigationBars.asPaddingValues()
    val cutoutStart = maxOf(
        cutoutPadding.calculateStartPadding(layoutDirection),
        navPadding.calculateStartPadding(layoutDirection),
    )
    val cutoutEnd = maxOf(
        cutoutPadding.calculateEndPadding(layoutDirection),
        navPadding.calculateEndPadding(layoutDirection),
    )

    // 经典 经典通栏 UITabBar Chrome 材质底色（细腻高遮盖磨砂，无透镜畸变、无药丸）
    val barSurfaceColor = when {
        !isShaderGlass && isDark -> Color(0xFF161618).copy(alpha = 0.96f)
        !isShaderGlass && !isDark -> Color(0xFFF9F9FB).copy(alpha = 0.96f)
        isDark -> Color(0xFF161618).copy(alpha = 0.80f)
        else -> Color(0xFFF9F9FB).copy(alpha = 0.78f)
    }
    val topHairlineColor = if (isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.Black.copy(alpha = 0.12f)
    }
    val unselectedColor = if (isDark) {
        Color(0xFF8E8E93)
    } else {
        Color(0xFF76767B)
    }

    val barModifier = if (isShaderGlass && backdrop != null) {
        modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    val darkNow = AppShellState.isDark
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    colorControls(
                        brightness = if (darkNow) -0.01f else 0.02f,
                        contrast = if (ultraNow) 1.04f else 1f,
                        saturation = if (ultraNow) 1.68f else 1.32f,
                    )
                    val blurPx = if (ultraNow) 15.dp.toPx() else 24.dp.toPx()
                    blur(blurPx, blurPx)
                },
                onDrawSurface = {
                    val darkNow = AppShellState.isDark
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    val surfaceAlpha = if (ultraNow) 0.72f else 0.86f
                    drawRect(
                        if (darkNow) Color(0xFF161618).copy(alpha = surfaceAlpha)
                        else Color(0xFFF9F9FB).copy(alpha = surfaceAlpha)
                    )
                },
                onDrawFront = {
                    val darkNow = AppShellState.isDark
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    val lineAlpha = if (ultraNow) {
                        if (darkNow) 0.22f else 0.16f
                    } else {
                        if (darkNow) 0.12f else 0.10f
                    }
                    drawLine(
                        color = if (darkNow) Color.White.copy(alpha = lineAlpha) else Color.Black.copy(alpha = lineAlpha),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 0.55.dp.toPx(),
                    )
                },
            )
    } else {
        modifier
            .fillMaxWidth()
            .background(barSurfaceColor)
            .drawBehind {
                drawLine(
                    color = topHairlineColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 0.55.dp.toPx(),
                )
            }
    }

    val barRowHeight = if (isLandscape) 46.dp else 50.dp
    val iconSize = if (isLandscape) 20.dp else 22.dp

    Column(
        modifier = barModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barRowHeight)
                .padding(start = cutoutStart, end = cutoutEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                val tint = if (isSelected) accentColor else unselectedColor
                val subPaths = iconPaths.getOrNull(index) ?: emptyList()
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val pressScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.91f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.76f, stiffness = 520f),
                    label = "classic_tab_press",
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(index) },
                        )
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Canvas(modifier = Modifier.size(iconSize)) {
                        val pathScale = size.width / 22.dp.toPx()
                        val strokeWidthPx = ((if (isSelected) 2.05f else 1.85f) * (size.width / 24f)) / pathScale
                        scale(pathScale, pathScale, pivot = Offset.Zero) {
                            subPaths.forEachIndexed { k, path ->
                                val pathAlpha = if (index == 0 && k == subPaths.size - 1) 0.58f else 1.0f
                                drawPath(
                                    path = path,
                                    color = tint.copy(alpha = tint.alpha * pathAlpha),
                                    style = Stroke(
                                        width = strokeWidthPx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(if (isLandscape) 1.5.dp else 2.dp))
                    Text(
                        text = item.label,
                        fontSize = if (isLandscape) 10.sp else 10.5.sp,
                        lineHeight = if (isLandscape) 11.sp else 12.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (navBottom > 0.dp) {
            Spacer(modifier = Modifier.height(navBottom))
        }
    }
}

private const val BAR_SPOT_SHADER = """
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.45, dist);
        return color * intensity;
    }"""
