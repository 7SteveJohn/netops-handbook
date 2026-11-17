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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.graphics.PathParser
import com.netops.handbook.lens.glass2.InnerShadow
import com.netops.handbook.lens.glass2.innerShadow
import com.netops.handbook.lens.glass2.lens
import com.netops.handbook.lens.glass2.rememberCombinedBackdrop
import com.netops.handbook.lens.glass2.rememberIosDirectionalHighlight
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.LocalOpsColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.offscreenLayerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private const val PATH_BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
private const val PATH_MENU = "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z"
private const val PATH_SEARCH = "M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
private const val PATH_SUN = "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58c-.39-.39-1.03-.39-1.41 0s-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37c-.39-.39-1.03-.39-1.41 0s-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41l-1.06-1.06zm1.06-10.96c.39-.39.39-1.03 0-1.41s-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36c.39-.39.39-1.03 0-1.41s-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z"
private const val PATH_MOON = "M12.3 2a10 10 0 0 0-.19 14 9.92 9.92 0 0 0 7.9-2.88 10 10 0 1 1-7.71-11.12z"

private const val TOPBAR_SPOT_SHADER = """
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.45, dist);
        return color * intensity;
    }"""

private fun smoothstep01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private class TopBarFadeBrushCache {
    private var lastHeight: Float = -1f
    private var lastDark: Boolean = false
    private var lastShaderMode: Boolean = false
    private var lastUltraMode: Boolean = false
    private var cached: Brush? = null

    fun obtain(
        height: Float,
        isDark: Boolean,
        shaderMode: Boolean,
        ultraMode: Boolean,
    ): Brush {
        val h = height.coerceAtLeast(1f)
        val cur = cached
        if (cur != null &&
            lastHeight == h &&
            lastDark == isDark &&
            lastShaderMode == shaderMode &&
            lastUltraMode == ultraMode
        ) {
            return cur
        }
        val brush = if (shaderMode) {
            if (ultraMode) {
                val baseTint = if (isDark) Color(0xFF0C0E14) else Color(0xFFF6F7FB)
                Brush.verticalGradient(
                    0.0f to baseTint.copy(alpha = if (isDark) 0.72f else 0.62f),
                    0.64f to baseTint.copy(alpha = if (isDark) 0.60f else 0.50f),
                    0.84f to baseTint.copy(alpha = if (isDark) 0.24f else 0.20f),
                    1.0f to Color.Transparent,
                    startY = 0f,
                    endY = h,
                )
            } else {
                val baseTint = if (isDark) Color(0xFF12141C) else Color(0xFFF6F7FB)
                Brush.verticalGradient(
                    0.0f to baseTint.copy(alpha = if (isDark) 0.90f else 0.82f),
                    0.64f to baseTint.copy(alpha = if (isDark) 0.80f else 0.72f),
                    0.84f to baseTint.copy(alpha = if (isDark) 0.36f else 0.32f),
                    1.0f to Color.Transparent,
                    startY = 0f,
                    endY = h,
                )
            }
        } else {
            val baseOpaque = if (isDark) Color(0xFF12141B) else Color(0xFFF7F8FC)
            Brush.verticalGradient(
                0.0f to baseOpaque.copy(alpha = 0.98f),
                0.74f to baseOpaque.copy(alpha = 0.94f),
                0.88f to baseOpaque.copy(alpha = 0.42f),
                1.0f to Color.Transparent,
                startY = 0f,
                endY = h,
            )
        }
        lastHeight = h
        lastDark = isDark
        lastShaderMode = shaderMode
        lastUltraMode = ultraMode
        cached = brush
        return brush
    }
}

/**
 * 水晶凸面弯月反光（Cabochon Meniscus）缓存：
 * 顶部天光折射亮弧 + 底部微弱焦散反光，中部完全清澈通透。
 */
private class CrystalMeniscusBrushCache {
    private var lastHeight: Float = -1f
    private var lastDark: Boolean = false
    private var cached: Brush? = null

    fun obtain(height: Float, isDark: Boolean): Brush {
        val h = height.coerceAtLeast(1f)
        val cur = cached
        if (cur != null && lastHeight == h && lastDark == isDark) return cur
        return Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = if (isDark) 0.14f else 0.30f),
            0.36f to Color(0xFFEEF5FF).copy(alpha = if (isDark) 0.02f else 0.05f),
            0.68f to Color.Transparent,
            1.00f to Color.White.copy(alpha = if (isDark) 0.06f else 0.13f),
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
 * 主题图标（☀️ ↔ 🌙）180° 欠阻尼弹簧旋转与弹性脉冲状态。
 * 同时供单球主题按钮与双格水滴胶囊（含离屏放大层）共享，确保任意过渡帧零相位差。
 */
@Composable
private fun rememberThemeSpinState(isDark: Boolean): Pair<Float, Float> {
    val spinAnim = remember { Animatable(0f) }
    val pulseAnim = remember { Animatable(1f) }
    var prevDark by remember { mutableStateOf(isDark) }

    LaunchedEffect(isDark) {
        if (isDark != prevDark) {
            prevDark = isDark
            launch {
                spinAnim.snapTo(-180f)
                spinAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.52f, stiffness = 320f),
                )
            }
            launch {
                pulseAnim.snapTo(1.24f)
                pulseAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.48f, stiffness = 340f),
                )
            }
        }
    }
    return spinAnim.value to pulseAnim.value
}

/**
 * Liquid Glass 导航栏 + 活体水珠透镜顶栏：
 * 1. 底层背景保持 ProgressiveBlur (start=0.66, end=1.0, 20dp) 渐进高斯散射 + 四停渐隐遮罩防叠字；
 * 2. 左侧悬浮圆球（40dp / 紧凑横屏 36dp）采用高透水晶凸透镜 + `LiquidOrbController`
 *    （快速轻点 >=0.84f 冲顶膨胀 1.32x、tanh 阻尼牵引、atan2 体积守恒拉伸、0.46f 欠阻尼震颤回弹、
 *    ☰ ↔ ← 弹性旋转缩放编排）；
 * 3. 右侧微型双格液态水滴胶囊（88×40dp / 紧凑横屏 78×36dp）：清透水晶双键底座 + 内置
 *    `DampedDragAnimation` 可跨格滑行、轻点即鼓起 1.34x 的 3D 凸透镜水珠 + ☀️ ↔ 🌙 180° 弹性旋转。
 */
@Composable
fun LiquidGlassTopBar(
    backdrop: LayerBackdrop?,
    onNavClick: () -> Unit,
    onSearchClick: () -> Unit,
    onThemeToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ops = LocalOpsColors.current
    val isDark = ops.dark
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current

    val isCompactLandscape = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenHeightDp < 480
    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val navPadding = WindowInsets.navigationBars.asPaddingValues()
    val safeStart = maxOf(
        cutoutPadding.calculateStartPadding(layoutDirection),
        navPadding.calculateStartPadding(layoutDirection),
    )
    val safeEnd = maxOf(
        cutoutPadding.calculateEndPadding(layoutDirection),
        navPadding.calculateEndPadding(layoutDirection),
    )
    val horizontalStart = maxOf(safeStart + if (safeStart > 0.dp) 4.dp else 0.dp, 14.dp)
    val horizontalEnd = maxOf(safeEnd + if (safeEnd > 0.dp) 4.dp else 0.dp, 14.dp)

    val glassLevel = AppShellState.glassLevel
    val isShaderGlass = glassLevel != "none" &&
        backdrop != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        isRuntimeShaderSupported()

    val contentColor = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1C1D22)
    val subtitleColor = if (isDark) Color(0xB3EBEBF5) else Color(0xAD3C3C43)
    val dropletIconColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF0A66E8)

    val navBack = AppShellState.navBack
    val title = AppShellState.title
    val subtitle = AppShellState.subtitle
    val isSearchRoute = AppShellState.pageRoute == "search"

    val backPath = remember { PathParser.createPathFromPathData(PATH_BACK).asComposePath() }
    val menuPath = remember { PathParser.createPathFromPathData(PATH_MENU).asComposePath() }
    val searchPath = remember { PathParser.createPathFromPathData(PATH_SEARCH).asComposePath() }
    val sunPath = remember { PathParser.createPathFromPathData(PATH_SUN).asComposePath() }
    val moonPath = remember { PathParser.createPathFromPathData(PATH_MOON).asComposePath() }

    val (themeSpinDeg, themePulseScale) = rememberThemeSpinState(isDark)

    val topProgressiveGradient = remember {
        ProgressiveBlur(
            angle = ProgressiveBlur.Top.angle,
            startFraction = 0.66f,
            endFraction = 1.0f,
        )
    }
    val fadeBrushCache = remember { TopBarFadeBrushCache() }

    val orbHighlight = rememberIosDirectionalHighlight(
        width = 0.85.dp,
        color = Color.White.copy(alpha = if (isDark) 0.66f else 0.88f),
        baseDegrees = 45f,
        falloff = 1.08f,
        shaderKey = "IosTopBarOrbHighlight",
    )
    val dropletHighlight = rememberIosDirectionalHighlight(
        width = 0.90.dp,
        color = Color.White.copy(alpha = if (isDark) 0.72f else 0.92f),
        baseDegrees = 45f,
        falloff = 1.02f,
        shaderKey = "IosTopBarDropletHighlight",
    )

    val controlShadowPaint = remember(isDark, density.density) {
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(if (isDark) 72 else 24, 0, 0, 0)
                maskFilter = BlurMaskFilter(6.5f * density.density, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val dropletShadowPaint = remember(isDark, density.density) {
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(if (isDark) 92 else 34, 0, 0, 0)
                maskFilter = BlurMaskFilter(7.5f * density.density, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }

    val fallbackRimBrush = remember(isDark) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = if (isDark) 0.34f else 0.88f),
            0.5f to Color.White.copy(alpha = 0.08f),
            1.0f to if (isDark) {
                Color.White.copy(alpha = 0.16f)
            } else {
                Color(0xFFB8BCC8).copy(alpha = 0.38f)
            },
        )
    }

    val barModifier = if (isShaderGlass) {
        val activeBackdrop = backdrop!!
        modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = activeBackdrop,
                shape = { RectangleShape },
                progressiveGradient = topProgressiveGradient,
                effects = {
                    val darkNow = AppShellState.isDark
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    if (ultraNow) {
                        colorControls(
                            brightness = if (darkNow) -0.01f else 0.015f,
                            contrast = 1.05f,
                            saturation = 1.68f,
                        )
                        progressiveBlur(
                            radiusX = 20.dp.toPx(),
                            radiusY = 20.dp.toPx(),
                            gradient = topProgressiveGradient,
                        )
                    } else {
                        colorControls(
                            brightness = if (darkNow) -0.01f else 0.02f,
                            contrast = 1.00f,
                            saturation = 1.28f,
                        )
                        progressiveBlur(
                            radiusX = 26.dp.toPx(),
                            radiusY = 26.dp.toPx(),
                            gradient = topProgressiveGradient,
                        )
                    }
                },
                onDrawSurface = {
                    val darkNow = AppShellState.isDark
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    drawRect(
                        brush = fadeBrushCache.obtain(
                            height = size.height,
                            isDark = darkNow,
                            shaderMode = true,
                            ultraMode = ultraNow,
                        )
                    )
                },
            )
    } else {
        modifier
            .fillMaxWidth()
            .drawBehind {
                val darkNow = AppShellState.isDark
                drawRect(
                    brush = fadeBrushCache.obtain(
                        height = size.height,
                        isDark = darkNow,
                        shaderMode = false,
                        ultraMode = false,
                    )
                )
            }
    }

    val rowHeight = if (isCompactLandscape) 48.dp else 56.dp
    val controlSize = if (isCompactLandscape) 36.dp else 40.dp
    val iconSize = if (isCompactLandscape) 18.5.dp else 20.dp

    Column(modifier = barModifier) {
        Spacer(Modifier.statusBarsPadding())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(start = horizontalStart, end = horizontalEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
            ) {
                val navMorph by animateFloatAsState(
                    targetValue = if (navBack) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.54f, stiffness = 340f),
                    label = "top_nav_morph",
                )

                FloatingGlassOrbButton(
                    backdrop = backdrop,
                    isShaderGlass = isShaderGlass,
                    isDark = isDark,
                    orbSize = controlSize,
                    contentDesc = if (navBack) "返回" else "导航菜单",
                    orbHighlight = orbHighlight,
                    shadowPaint = controlShadowPaint,
                    fallbackRimBrush = fallbackRimBrush,
                    onClick = onNavClick,
                ) { pressProgress ->
                    val navIconSize = if (isCompactLandscape) 19.5.dp else 21.dp
                    Canvas(
                        modifier = Modifier
                            .size(navIconSize)
                            .graphicsLayer {
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val mag = lerp(1f, if (ultraNow) 1.16f else 1.07f, pressProgress)
                                scaleX = mag
                                scaleY = mag
                            }
                    ) {
                        val s = size.width / 24f
                        val menuAlpha = (1f - navMorph * 1.35f).coerceIn(0f, 1f)
                        val backAlpha = ((navMorph - 0.15f) / 0.85f).coerceIn(0f, 1f)
                        if (menuAlpha > 0.005f) {
                            val menuScale = lerp(1f, 0.55f, navMorph.coerceIn(0f, 1f))
                            rotate(degrees = -90f * navMorph, pivot = center) {
                                scale(menuScale, menuScale, pivot = center) {
                                    scale(s, s, pivot = Offset.Zero) {
                                        drawPath(
                                            path = menuPath,
                                            color = contentColor.copy(alpha = contentColor.alpha * menuAlpha),
                                            style = Fill,
                                        )
                                    }
                                }
                            }
                        }
                        if (backAlpha > 0.005f) {
                            val backScale = lerp(0.55f, 1f, navMorph.coerceIn(0f, 1f))
                            rotate(degrees = 90f * (1f - navMorph), pivot = center) {
                                scale(backScale, backScale, pivot = center) {
                                    scale(s, s, pivot = Offset.Zero) {
                                        drawPath(
                                            path = backPath,
                                            color = contentColor.copy(alpha = contentColor.alpha * backAlpha),
                                            style = Fill,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                if (isCompactLandscape) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = (cfg.screenWidthDp * 0.46f).dp),
                        )
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = "  ·  $subtitle",
                                fontSize = 11.5.sp,
                                color = subtitleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                fontSize = 11.sp,
                                color = subtitleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (isSearchRoute) {
                FloatingGlassOrbButton(
                    backdrop = backdrop,
                    isShaderGlass = isShaderGlass,
                    isDark = isDark,
                    orbSize = controlSize,
                    contentDesc = "切换主题",
                    orbHighlight = orbHighlight,
                    shadowPaint = controlShadowPaint,
                    fallbackRimBrush = fallbackRimBrush,
                    onClick = onThemeToggle,
                ) { pressProgress ->
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    ThemeMorphCanvas(
                        isDark = isDark,
                        sunPath = sunPath,
                        moonPath = moonPath,
                        iconSize = iconSize,
                        color = contentColor,
                        spinDeg = themeSpinDeg,
                        pulseScale = themePulseScale * lerp(1f, if (ultraNow) 1.16f else 1.07f, pressProgress),
                    )
                }
            } else {
                FloatingGlassDualCapsule(
                    backdrop = backdrop,
                    isShaderGlass = isShaderGlass,
                    isDark = isDark,
                    capsuleHeight = controlSize,
                    capsuleWidth = if (isCompactLandscape) 78.dp else 88.dp,
                    iconSize = iconSize,
                    searchPath = searchPath,
                    sunPath = sunPath,
                    moonPath = moonPath,
                    themeSpinDeg = themeSpinDeg,
                    themePulseScale = themePulseScale,
                    contentColor = contentColor,
                    dropletIconColor = dropletIconColor,
                    orbHighlight = orbHighlight,
                    dropletHighlight = dropletHighlight,
                    shadowPaint = controlShadowPaint,
                    dropletShadowPaint = dropletShadowPaint,
                    fallbackRimBrush = fallbackRimBrush,
                    onSearchClick = onSearchClick,
                    onThemeToggle = onThemeToggle,
                )
            }
        }
        // 底部柔和渐隐羽化带，确保主副标题完整处于满强度高斯保护区内，底部渐隐至清晰
        Spacer(Modifier.height(if (isCompactLandscape) 16.dp else 22.dp))
    }
}

@Composable
private fun ThemeMorphCanvas(
    isDark: Boolean,
    sunPath: Path,
    moonPath: Path,
    iconSize: Dp,
    color: Color,
    spinDeg: Float,
    pulseScale: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .size(iconSize)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
    ) {
        val s = size.width / 24f
        val progress = (spinDeg + 180f) / 180f
        val outAlpha = (1f - progress * 2.2f).coerceIn(0f, 1f)
        val inAlpha = ((progress - 0.16f) / 0.56f).coerceIn(0f, 1f)
        val currentPath = if (isDark) moonPath else sunPath
        val previousPath = if (isDark) sunPath else moonPath

        if (outAlpha > 0.005f) {
            rotate(degrees = spinDeg + 180f, pivot = center) {
                scale(s, s, pivot = Offset.Zero) {
                    drawPath(
                        path = previousPath,
                        color = color.copy(alpha = color.alpha * outAlpha),
                        style = Fill,
                    )
                }
            }
        }
        if (inAlpha > 0.005f) {
            rotate(degrees = spinDeg, pivot = center) {
                scale(s, s, pivot = Offset.Zero) {
                    drawPath(
                        path = currentPath,
                        color = color.copy(alpha = color.alpha * inAlpha),
                        style = Fill,
                    )
                }
            }
        }
    }
}

/**
 * Liquid Glass 双档单球按钮（极致 = 活体清透水珠透镜 1.32x；标准 = 温润云母磨砂微弹球 1.15x）：
 * - 极致档 (`ultraNow == true`)：`blur(3dp -> 0.5dp)` + `contrast = 1.08f, saturation = 1.76f` + 强凸镜 `lens(11..18dp, 20..34dp)` + `1.32x` 水珠鼓起；
 * - 标准档 (`ultraNow == false`)：`blur(13dp)` + 云母柔雾底色 + 温和 `lens(4dp, 6dp, depthEffect = false, CA = 0f)` + `1.15x` 柔雾微弹。
 */
@Composable
private fun FloatingGlassOrbButton(
    backdrop: LayerBackdrop?,
    isShaderGlass: Boolean,
    isDark: Boolean,
    orbSize: Dp,
    contentDesc: String,
    orbHighlight: State<Highlight>,
    shadowPaint: Paint,
    fallbackRimBrush: Brush,
    onClick: () -> Unit,
    iconContent: @Composable (pressProgress: Float) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val onClickUpdated by rememberUpdatedState(onClick)
    val maxPullPx = with(density) { 22.dp.toPx() }

    val orbController = remember(animationScope, maxPullPx) {
        LiquidOrbController(
            animationScope = animationScope,
            pressedScale = 1.32f,
            maxPullPx = { maxPullPx },
        )
    }
    orbController.onPressStart = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    orbController.onTap = {
        onClickUpdated()
    }

    val meniscusCache = remember { CrystalMeniscusBrushCache() }

    // 外层不设 clip，留足 1.32x 水珠膨胀与柔和接触阴影空间
    Box(
        modifier = Modifier.size(orbSize),
        contentAlignment = Alignment.Center,
    ) {
        // 0. 外部柔和浮空阴影（位于 clip 外侧，随档位动态匹配膨胀与牵引幅度）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    val pullMul = if (ultraNow) 1f else 0.55f
                    val pull = orbController.pullOffset
                    translationX = pull.x * pullMul
                    translationY = pull.y * pullMul
                    val rawSX = orbController.scaleX
                    val rawSY = orbController.scaleY
                    scaleX = if (ultraNow) rawSX else 1f + (rawSX - 1f) * 0.45f
                    scaleY = if (ultraNow) rawSY else 1f + (rawSY - 1f) * 0.45f
                }
                .drawBehind {
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    val p = orbController.pressProgress
                    val r = size.minDimension * 0.5f
                    val dy = if (ultraNow) {
                        lerp(2.dp.toPx(), 4.2.dp.toPx(), p)
                    } else {
                        lerp(1.6.dp.toPx(), 2.8.dp.toPx(), p)
                    }
                    drawIntoCanvas { canvas ->
                        canvas.drawRoundRect(
                            left = 1.dp.toPx(),
                            top = dy,
                            right = size.width - 1.dp.toPx(),
                            bottom = size.height + dy,
                            radiusX = r,
                            radiusY = r,
                            paint = shadowPaint,
                        )
                    }
                }
        )

        // 1. 双档光学圆球本体（实时读取 ultraNow 即时切换水晶水珠 vs 云母磨砂球）
        if (isShaderGlass && backdrop != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        val pullMul = if (ultraNow) 1f else 0.55f
                        val pull = orbController.pullOffset
                        translationX = pull.x * pullMul
                        translationY = pull.y * pullMul
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = orbController.pressProgress
                            if (ultraNow) {
                                val blurRadius = lerp(3.dp.toPx(), 0.5.dp.toPx(), p)
                                colorControls(
                                    brightness = if (darkNow) 0.01f else lerp(0.015f, -0.012f, p),
                                    contrast = 1.08f,
                                    saturation = 1.76f,
                                )
                                blur(blurRadius, blurRadius)
                                lens(
                                    refractionHeight = lerp(11.dp.toPx(), 18.dp.toPx(), p),
                                    refractionAmount = lerp(20.dp.toPx(), 34.dp.toPx(), p),
                                    depthEffect = true,
                                    chromaticAberration = lerp(0.14f, 0.28f, p),
                                )
                            } else {
                                padding = maxOf(padding, 16.dp.toPx())
                                colorControls(
                                    brightness = if (darkNow) -0.01f else 0.02f,
                                    contrast = 1.01f,
                                    saturation = 1.28f,
                                )
                                blur(13.dp.toPx(), 13.dp.toPx())
                                lens(
                                    refractionHeight = 4.dp.toPx(),
                                    refractionAmount = 6.dp.toPx(),
                                    depthEffect = false,
                                    chromaticAberration = 0f,
                                )
                            }
                        },
                        highlight = {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = orbController.pressProgress
                            val hAlpha = if (ultraNow) 0.78f + 0.22f * p else 0.44f + 0.18f * p
                            orbHighlight.value.copy(alpha = hAlpha.coerceIn(0f, 1f))
                        },
                        layerBlock = {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val rawSX = orbController.scaleX
                            val rawSY = orbController.scaleY
                            scaleX = if (ultraNow) rawSX else 1f + (rawSX - 1f) * 0.45f
                            scaleY = if (ultraNow) rawSY else 1f + (rawSY - 1f) * 0.45f
                        },
                        onDrawSurface = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = orbController.pressProgress
                            if (ultraNow) {
                                val restWeight = (1f - p).coerceIn(0f, 1f)
                                if (restWeight > 0.005f) {
                                    val crystalTint = if (darkNow) {
                                        Color(0xFF0B0E16).copy(alpha = 0.24f * restWeight)
                                    } else {
                                        Color.White.copy(alpha = 0.16f * restWeight)
                                    }
                                    drawRect(crystalTint)
                                    drawRect(
                                        brush = meniscusCache.obtain(size.height, darkNow),
                                        alpha = restWeight,
                                    )
                                }
                                if (p > 0.005f) {
                                    if (!darkNow) {
                                        // 浅色鼓起态：白色弯月面光乘 (1 - p) 完整退场，改叠极淡冰水冷调与微暗水体深度压住过曝
                                        drawRect(Color(0xFFD8E6FF).copy(alpha = 0.06f * p))
                                        drawRect(Color.Black.copy(alpha = 0.025f * p))
                                    } else {
                                        drawRect(Color.Black.copy(alpha = 0.022f * p))
                                    }
                                }
                            } else {
                                val micaTint = if (darkNow) {
                                    Color(0xFF151822).copy(alpha = lerp(0.58f, 0.52f, p))
                                } else {
                                    Color(0xFFF6F7FB).copy(alpha = lerp(0.52f, 0.46f, p))
                                }
                                drawRect(micaTint)
                                if (p > 0.005f) {
                                    drawRect(Color.White.copy(alpha = (if (darkNow) 0.05f else 0.12f) * p))
                                }
                            }
                        },
                        onDrawFront = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = orbController.pressProgress
                            val r = size.minDimension * 0.5f
                            if (!darkNow) {
                                val strokeW = 0.6.dp.toPx()
                                val half = strokeW * 0.5f
                                val strokeAlpha = if (ultraNow) lerp(0.075f, 0.11f, p) else lerp(0.058f, 0.08f, p)
                                drawRoundRect(
                                    color = Color.Black.copy(alpha = strokeAlpha),
                                    topLeft = Offset(half, half),
                                    size = Size(
                                        (size.width - strokeW).coerceAtLeast(0f),
                                        (size.height - strokeW).coerceAtLeast(0f),
                                    ),
                                    cornerRadius = CornerRadius(
                                        (r - half).coerceAtLeast(0f),
                                        (r - half).coerceAtLeast(0f),
                                    ),
                                    style = Stroke(width = strokeW),
                                )
                            } else {
                                val strokeAlpha = if (ultraNow) lerp(0.08f, 0.14f, p) else lerp(0.055f, 0.09f, p)
                                drawRoundRect(
                                    color = Color.White.copy(alpha = strokeAlpha),
                                    cornerRadius = CornerRadius(r, r),
                                    style = Stroke(width = 0.5.dp.toPx()),
                                )
                            }
                            orbController.drawSpotHighlight(
                                drawScope = this,
                                maxSpotAlpha = if (ultraNow) {
                                    if (darkNow) 0.14f else 0.04f
                                } else {
                                    if (darkNow) 0.07f else 0.025f
                                },
                                ambientAlpha = if (darkNow) (if (ultraNow) 0.045f else 0.02f) else 0f,
                            )
                        },
                    )
                    .innerShadow(shape = CircleShape) {
                        val darkNow = AppShellState.isDark
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        val p = orbController.pressProgress
                        if (ultraNow) {
                            InnerShadow(
                                radius = lerpDp(3.dp, 8.5.dp, p),
                                color = Color.Black.copy(
                                    alpha = if (darkNow) lerp(0.22f, 0.34f, p) else lerp(0.08f, 0.24f, p)
                                ),
                                alpha = 1f,
                            )
                        } else {
                            InnerShadow(
                                radius = lerpDp(2.dp, 4.dp, p),
                                color = Color.Black.copy(
                                    alpha = if (darkNow) lerp(0.12f, 0.18f, p) else lerp(0.04f, 0.09f, p)
                                ),
                                alpha = 1f,
                            )
                        }
                    }
            )
        } else {
            val fallbackBg = if (isDark) {
                Color(0xFF20232D).copy(alpha = 0.88f)
            } else {
                Color(0xFFFFFFFF).copy(alpha = 0.86f)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val pull = orbController.pullOffset
                        translationX = pull.x * 0.55f
                        translationY = pull.y * 0.55f
                        val rawSX = orbController.scaleX
                        val rawSY = orbController.scaleY
                        scaleX = 1f + (rawSX - 1f) * 0.45f
                        scaleY = 1f + (rawSY - 1f) * 0.45f
                    }
                    .clip(CircleShape)
                    .background(fallbackBg)
                    .border(width = 0.8.dp, brush = fallbackRimBrush, shape = CircleShape)
                    .then(orbController.highlightModifier)
            )
        }

        // 2. 清晰图标层（跟随牵引平移，不随椭圆拉伸扭曲字形）
        Box(
            modifier = Modifier.graphicsLayer {
                val ultraNow = AppShellState.glassLevel == "ultra"
                val pullMul = if (ultraNow) 1f else 0.55f
                val pull = orbController.pullOffset
                translationX = pull.x * pullMul
                translationY = pull.y * pullMul
            },
            contentAlignment = Alignment.Center,
        ) {
            iconContent(orbController.pressProgress)
        }

        // 3. 无障碍语义与全热区流体手势捕获层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    role = Role.Button
                    contentDescription = contentDesc
                    onClick(label = contentDesc) {
                        onClickUpdated()
                        true
                    }
                }
                .then(orbController.gestureModifier)
        )
    }
}

/**
 * 右上角「微型双格液态水滴胶囊」（双档光学与物理分级）：
 * - 极致档 (`ultraNow == true`)：清透水晶底座（`blur(5dp)` + `lens(16dp, 28dp, CA = 0.10f)`）+
 *   可跨格滑行、轻点鼓起 `1.34x` 突破边界的 3D 凸透镜色散纯水水珠（`CA = 0.28f`）；
 * - 标准档 (`ultraNow == false`)：`blur(13dp)` 温润云母磨砂胶囊底座 + `lens(4dp, 6dp, depthEffect = false)` +
 *   `1.15x` 半透云母柔雾滑块，清晰矢量图标悬浮于柔雾气垫之上。
 */
@Composable
private fun FloatingGlassDualCapsule(
    backdrop: LayerBackdrop?,
    isShaderGlass: Boolean,
    isDark: Boolean,
    capsuleHeight: Dp,
    capsuleWidth: Dp,
    iconSize: Dp,
    searchPath: Path,
    sunPath: Path,
    moonPath: Path,
    themeSpinDeg: Float,
    themePulseScale: Float,
    contentColor: Color,
    dropletIconColor: Color,
    orbHighlight: State<Highlight>,
    dropletHighlight: State<Highlight>,
    shadowPaint: Paint,
    dropletShadowPaint: Paint,
    fallbackRimBrush: Brush,
    onSearchClick: () -> Unit,
    onThemeToggle: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val onSearchClickUpdated by rememberUpdatedState(onSearchClick)
    val onThemeToggleUpdated by rememberUpdatedState(onThemeToggle)

    val innerPadding = 2.dp
    val innerPaddingPx = with(density) { innerPadding.toPx() }
    val capsuleWidthPx = with(density) { capsuleWidth.toPx() }
    val capsuleHeightPx = with(density) { capsuleHeight.toPx() }
    val contentWidth = capsuleWidth - innerPadding * 2
    val cellWidth = contentWidth / 2
    val cellWidthPx = with(density) { cellWidth.toPx() }
    val dropletHeight = capsuleHeight - innerPadding * 2

    var lastTouchedRight by remember { mutableStateOf(false) }
    var lastHapticCell by remember { mutableIntStateOf(0) }
    var pointerPos by remember { mutableStateOf(Offset.Zero) }

    // 拖至胶囊左右尽头时的 ±2.5dp 橡皮筋弹性微移
    val rubberOffsetAnim = remember { Animatable(0f) }
    val rubberMaxPx = with(density) { 2.5.dp.toPx() }
    val panelOffset by remember(capsuleWidthPx, rubberMaxPx) {
        derivedStateOf {
            if (capsuleWidthPx <= 0f) {
                0f
            } else {
                val frac = (rubberOffsetAnim.value / capsuleWidthPx).coerceIn(-1f, 1f)
                rubberMaxPx * frac.sign * EaseOut.transform(abs(frac))
            }
        }
    }

    val dampedDrag = remember(animationScope, cellWidthPx, capsuleWidthPx, capsuleHeightPx) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = if (lastTouchedRight) 1f else 0f,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.34f,
            onDragStarted = { position ->
                pointerPos = position
                val hit = if (position.x >= capsuleWidthPx * 0.5f) 1 else 0
                lastHapticCell = hit
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (pressProgress < 0.08f) {
                    snapToValue(hit.toFloat())
                } else if (hit != targetValue.roundToInt()) {
                    updateValue(hit.toFloat())
                }
            },
            onDragStopped = {
                val slopPx = with(density) { 28.dp.toPx() }
                val withinBounds = pointerPos.x >= -slopPx &&
                    pointerPos.x <= capsuleWidthPx + slopPx &&
                    pointerPos.y >= -slopPx &&
                    pointerPos.y <= capsuleHeightPx + slopPx
                val targetIdx = targetValue.roundToInt().coerceIn(0, 1)
                lastTouchedRight = (targetIdx == 1)
                updateValue(targetIdx.toFloat())
                animationScope.launch {
                    rubberOffsetAnim.animateTo(0f, spring(0.70f, 380f, 0.5f))
                }
                if (withinBounds) {
                    if (targetIdx == 0) {
                        onSearchClickUpdated()
                    } else {
                        onThemeToggleUpdated()
                    }
                }
            },
            onDragCancelled = {
                val fallbackIdx = if (lastTouchedRight) 1 else 0
                updateValue(fallbackIdx.toFloat())
                animationScope.launch {
                    rubberOffsetAnim.animateTo(0f, spring(0.70f, 380f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                pointerPos += dragAmount
                if (cellWidthPx > 0f && dragAmount.x != 0f) {
                    val next = (targetValue + dragAmount.x / cellWidthPx).coerceIn(0f, 1f)
                    updateValue(next)
                    val hovered = next.roundToInt().coerceIn(0, 1)
                    if (hovered != lastHapticCell) {
                        lastHapticCell = hovered
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    animationScope.launch {
                        rubberOffsetAnim.snapTo(rubberOffsetAnim.value + dragAmount.x)
                    }
                }
            },
        )
    }

    val meniscusCache = remember { CrystalMeniscusBrushCache() }
    val spotShader = remember {
        if (isRuntimeShaderSupported()) RuntimeShader(TOPBAR_SPOT_SHADER) else null
    }

    val capsuleIconsBackdrop = rememberLayerBackdrop()
    val combinedDropletBackdrop = backdrop?.let { rememberCombinedBackdrop(it, capsuleIconsBackdrop) }

    // 外层不设 clip，确保按压时 1.34x 水珠上下对称漫出胶囊边界
    Box(
        modifier = Modifier
            .width(capsuleWidth)
            .height(capsuleHeight),
        contentAlignment = Alignment.TopStart,
    ) {
        // =====================================================================
        // 第 0 层：双键胶囊柔和接触外阴影
        // =====================================================================
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = panelOffset }
                .drawBehind {
                    val r = size.minDimension * 0.5f
                    val dy = 2.2.dp.toPx()
                    drawIntoCanvas { canvas ->
                        canvas.drawRoundRect(
                            left = 1.dp.toPx(),
                            top = dy,
                            right = size.width - 1.dp.toPx(),
                            bottom = size.height + dy,
                            radiusX = r,
                            radiusY = r,
                            paint = shadowPaint,
                        )
                    }
                }
        )

        // =====================================================================
        // 第 1 层：双档双键胶囊底座（极致 = 5dp 水晶折光；标准 = 13dp 云母柔雾磨砂）
        // =====================================================================
        if (isShaderGlass && backdrop != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            if (ultraNow) {
                                padding = maxOf(padding, 32.dp.toPx())
                                colorControls(
                                    brightness = if (darkNow) 0.01f else 0.02f,
                                    contrast = 1.08f,
                                    saturation = 1.76f,
                                )
                                blur(5.dp.toPx(), 5.dp.toPx())
                                lens(
                                    refractionHeight = 16.dp.toPx(),
                                    refractionAmount = 28.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = 0.10f,
                                )
                            } else {
                                padding = maxOf(padding, 18.dp.toPx())
                                colorControls(
                                    brightness = if (darkNow) -0.01f else 0.02f,
                                    contrast = 1.01f,
                                    saturation = 1.28f,
                                )
                                blur(13.dp.toPx(), 13.dp.toPx())
                                lens(
                                    refractionHeight = 4.dp.toPx(),
                                    refractionAmount = 6.dp.toPx(),
                                    depthEffect = false,
                                    chromaticAberration = 0f,
                                )
                            }
                        },
                        highlight = {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress
                            val hAlpha = if (ultraNow) 0.80f + 0.20f * p else 0.44f + 0.16f * p
                            orbHighlight.value.copy(alpha = hAlpha.coerceIn(0f, 1f))
                        },
                        layerBlock = {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress
                            val s = lerp(1f, if (ultraNow) 1.045f else 1.02f, p)
                            scaleX = s
                            scaleY = s
                        },
                        onDrawSurface = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            if (ultraNow) {
                                val baseTint = if (darkNow) {
                                    Color(0xFF0B0E16).copy(alpha = 0.24f)
                                } else {
                                    Color.White.copy(alpha = 0.16f)
                                }
                                drawRect(baseTint)
                                drawRect(brush = meniscusCache.obtain(size.height, darkNow))
                            } else {
                                val micaTint = if (darkNow) {
                                    Color(0xFF151822).copy(alpha = 0.58f)
                                } else {
                                    Color(0xFFF6F7FB).copy(alpha = 0.52f)
                                }
                                drawRect(micaTint)
                            }
                        },
                        onDrawFront = {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress
                            val r = size.minDimension * 0.5f
                            if (!darkNow) {
                                drawRoundRect(
                                    color = Color.Black.copy(alpha = if (ultraNow) 0.072f else 0.056f),
                                    cornerRadius = CornerRadius(r, r),
                                    style = Stroke(width = 0.52.dp.toPx()),
                                )
                            } else {
                                drawRoundRect(
                                    color = Color.White.copy(alpha = if (ultraNow) 0.085f else 0.055f),
                                    cornerRadius = CornerRadius(r, r),
                                    style = Stroke(width = 0.5.dp.toPx()),
                                )
                            }

                            // 中间极细光学分隔线（随 pressProgress 自动淡隐，让位于跨格滑块/水珠）
                            val divFade = (1f - p * 1.35f).coerceIn(0f, 1f)
                            if (divFade > 0.01f) {
                                val dividerH = 14.dp.toPx().coerceAtMost(size.height * 0.46f)
                                val midX = size.width * 0.5f
                                val startY = (size.height - dividerH) * 0.5f
                                drawLine(
                                    color = if (darkNow) {
                                        Color.White.copy(alpha = (if (ultraNow) 0.16f else 0.12f) * divFade)
                                    } else {
                                        Color.Black.copy(alpha = (if (ultraNow) 0.10f else 0.08f) * divFade)
                                    },
                                    start = Offset(midX, startY),
                                    end = Offset(midX, startY + dividerH),
                                    strokeWidth = 0.6.dp.toPx(),
                                )
                            }

                            // 底座跟随水滴 AGSL 聚光辉光（浅色压低至 0.04f 防过曝）
                            if (p > 0.005f && spotShader != null) {
                                val spotX = (innerPaddingPx + (dampedDrag.value + 0.5f) * cellWidthPx)
                                    .coerceIn(0f, size.width)
                                val spotY = size.height * 0.5f
                                val radius = (size.minDimension * 1.35f).coerceAtLeast(1f)
                                val spotBoost = if (ultraNow) {
                                    if (darkNow) 0.11f else 0.04f
                                } else {
                                    if (darkNow) 0.06f else 0.025f
                                }
                                val spotColor = Color.White.copy(alpha = spotBoost * p)
                                spotShader.setColorUniform("color", spotColor)
                                spotShader.setFloatUniform("radius", radius)
                                spotShader.setFloatUniform("position", spotX, spotY)
                                drawRoundRect(
                                    brush = spotShader.asBrush(),
                                    cornerRadius = CornerRadius(r, r),
                                    blendMode = BlendMode.Plus,
                                )
                            }
                        },
                    )
            )
        } else {
            val fallbackBg = if (isDark) {
                Color(0xFF20232D).copy(alpha = 0.88f)
            } else {
                Color(0xFFFFFFFF).copy(alpha = 0.86f)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = panelOffset }
                    .clip(CircleShape)
                    .background(fallbackBg)
                    .border(width = 0.75.dp, brush = fallbackRimBrush, shape = CircleShape)
                    .drawBehind {
                        val darkNow = AppShellState.isDark
                        val divFade = (1f - dampedDrag.pressProgress * 1.35f).coerceIn(0f, 1f)
                        if (divFade > 0.01f) {
                            val dividerH = 14.dp.toPx().coerceAtMost(size.height * 0.46f)
                            val midX = size.width * 0.5f
                            val startY = (size.height - dividerH) * 0.5f
                            drawLine(
                                color = if (darkNow) {
                                    Color.White.copy(alpha = 0.15f * divFade)
                                } else {
                                    Color.Black.copy(alpha = 0.095f * divFade)
                                },
                                start = Offset(midX, startY),
                                end = Offset(midX, startY + dividerH),
                                strokeWidth = 0.6.dp.toPx(),
                            )
                        }
                    }
            )
        }

        // =====================================================================
        // 第 1b 层：底座静态清晰图标行（极致档水珠升起覆盖该格时自动平滑退让；标准档交由 2c 顶层绘清晰图标）
        // =====================================================================
        DualCapsuleIconsRow(
            isDark = isDark,
            iconSize = iconSize,
            cellWidth = cellWidth,
            capsuleHeight = capsuleHeight,
            innerPadding = innerPadding,
            searchPath = searchPath,
            sunPath = sunPath,
            moonPath = moonPath,
            themeSpinDeg = themeSpinDeg,
            themePulseScale = themePulseScale,
            color = contentColor,
            scaleForCell = { cellIdx ->
                if (!isShaderGlass) {
                    val smoothP = smoothstep01(dampedDrag.pressProgress)
                    val proximity = (1f - abs(dampedDrag.value - cellIdx)).coerceIn(0f, 1f)
                    lerp(1f, 1.08f, smoothP * proximity)
                } else {
                    1f
                }
            },
            alphaForCell = { cellIdx ->
                val ultraNow = AppShellState.glassLevel == "ultra"
                if (isShaderGlass && combinedDropletBackdrop != null) {
                    if (ultraNow) {
                        val smoothP = smoothstep01(dampedDrag.pressProgress)
                        val proximity = (1f - abs(dampedDrag.value - cellIdx)).coerceIn(0f, 1f)
                        (1f - smoothP * proximity).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                } else {
                    1f
                }
            },
            modifier = Modifier.graphicsLayer { translationX = panelOffset },
        )

        // =====================================================================
        // 第 1c 层：离屏放大图标镜像层（极致档供 3D 凸透镜水珠同源折射采样；标准档隐藏防磨砂重模糊）
        // =====================================================================
        if (isShaderGlass && combinedDropletBackdrop != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .graphicsLayer { translationX = panelOffset }
                    .offscreenLayerBackdrop(capsuleIconsBackdrop)
            ) {
                DualCapsuleIconsRow(
                    isDark = isDark,
                    iconSize = iconSize,
                    cellWidth = cellWidth,
                    capsuleHeight = capsuleHeight,
                    innerPadding = innerPadding,
                    searchPath = searchPath,
                    sunPath = sunPath,
                    moonPath = moonPath,
                    themeSpinDeg = themeSpinDeg,
                    themePulseScale = themePulseScale,
                    color = dropletIconColor,
                    scaleForCell = {
                        val smoothP = smoothstep01(dampedDrag.pressProgress)
                        lerp(1f, 1.18f, smoothP)
                    },
                    alphaForCell = {
                        if (AppShellState.glassLevel == "ultra") 1f else 0f
                    },
                )
            }
        }

        // =====================================================================
        // 第 2 层：双档可跨格滑行滑块（极致 = 1.34x 透明色散大水珠；标准 = 1.15x 半透云母柔雾滑块）
        // =====================================================================
        val rawPress = dampedDrag.pressProgress
        if (rawPress > 0.001f) {
            if (isShaderGlass && combinedDropletBackdrop != null) {
                // 2a. 滑块外部浮空接触阴影（graphicsLayer 保持 alpha=1f 避免 RenderNode 方形裁切外溢 BlurMaskFilter）
                Box(
                    modifier = Modifier
                        .padding(horizontal = innerPadding, vertical = innerPadding)
                        .width(cellWidth)
                        .height(dropletHeight)
                        .graphicsLayer {
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            translationX = dampedDrag.value * cellWidthPx + panelOffset
                            val v = dampedDrag.velocity / 10f
                            val rawSX = dampedDrag.scaleX
                            val rawSY = dampedDrag.scaleY
                            val baseSX = if (ultraNow) rawSX else 1f + (rawSX - 1f) * 0.45f
                            val baseSY = if (ultraNow) rawSY else 1f + (rawSY - 1f) * 0.45f
                            val stretchMul = if (ultraNow) 1f else 0.50f
                            scaleX = baseSX / (1f - (v * 0.75f * stretchMul).coerceIn(-0.22f, 0.22f))
                            scaleY = baseSY * (1f - (v * 0.25f * stretchMul).coerceIn(-0.22f, 0.22f))
                            clip = false
                        }
                        .drawBehind {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val smoothP = smoothstep01(dampedDrag.pressProgress)
                            val baseAlpha = if (darkNow) 92 else 34
                            val alphaMul = (if (ultraNow) smoothP else smoothP * 0.58f).coerceIn(0f, 1f)
                            val targetAlpha = (baseAlpha * alphaMul).roundToInt().coerceIn(0, 255)
                            if (targetAlpha > 0) {
                                dropletShadowPaint.asFrameworkPaint().alpha = targetAlpha
                                val r = size.minDimension * 0.5f
                                val dy = if (ultraNow) 3.8.dp.toPx() else 2.4.dp.toPx()
                                drawIntoCanvas { canvas ->
                                    canvas.drawRoundRect(
                                        left = 0f,
                                        top = dy,
                                        right = size.width,
                                        bottom = size.height + dy,
                                        radiusX = r,
                                        radiusY = r,
                                        paint = dropletShadowPaint,
                                    )
                                }
                            }
                        }
                )

                // 2b. 滑块光学本体（实时读取 ultraNow 即时切换 3D 凸透镜水珠 vs 半透云母柔雾滑块）
                Box(
                    modifier = Modifier
                        .padding(horizontal = innerPadding, vertical = innerPadding)
                        .width(cellWidth)
                        .height(dropletHeight)
                        .graphicsLayer {
                            translationX = dampedDrag.value * cellWidthPx + panelOffset
                        }
                        .drawBackdrop(
                            backdrop = combinedDropletBackdrop,
                            shape = { CircleShape },
                            effects = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val p = dampedDrag.pressProgress
                                val smoothP = smoothstep01(p)
                                if (ultraNow) {
                                    colorControls(
                                        brightness = if (darkNow) 0.015f else -0.012f * p,
                                        contrast = 1.08f,
                                        saturation = 1.78f,
                                    )
                                    lens(
                                        refractionHeight = lerp(8.dp.toPx(), 13.dp.toPx(), smoothP),
                                        refractionAmount = lerp(16.dp.toPx(), 26.dp.toPx(), smoothP),
                                        depthEffect = true,
                                        chromaticAberration = 0.28f,
                                    )
                                } else {
                                    padding = maxOf(padding, 16.dp.toPx())
                                    colorControls(
                                        brightness = if (darkNow) 0.01f else 0.02f,
                                        contrast = 1.01f,
                                        saturation = 1.28f,
                                    )
                                    blur(13.dp.toPx(), 13.dp.toPx())
                                    lens(
                                        refractionHeight = 4.dp.toPx(),
                                        refractionAmount = 6.dp.toPx(),
                                        depthEffect = false,
                                        chromaticAberration = 0f,
                                    )
                                }
                            },
                            highlight = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val hAlpha = if (ultraNow) {
                                    if (darkNow) 1f else 0.68f
                                } else {
                                    if (darkNow) 0.52f else 0.46f
                                }
                                dropletHighlight.value.copy(alpha = hAlpha)
                            },
                            layerBlock = {
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val smoothP = smoothstep01(dampedDrag.pressProgress)
                                val v = dampedDrag.velocity / 10f
                                val rawSX = dampedDrag.scaleX
                                val rawSY = dampedDrag.scaleY
                                val baseSX = if (ultraNow) rawSX else 1f + (rawSX - 1f) * 0.45f
                                val baseSY = if (ultraNow) rawSY else 1f + (rawSY - 1f) * 0.45f
                                val stretchMul = if (ultraNow) 1f else 0.50f
                                scaleX = baseSX / (1f - (v * 0.75f * stretchMul).coerceIn(-0.22f, 0.22f))
                                scaleY = baseSY * (1f - (v * 0.25f * stretchMul).coerceIn(-0.22f, 0.22f))
                                alpha = smoothP
                            },
                            onDrawSurface = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val p = dampedDrag.pressProgress
                                if (ultraNow) {
                                    if (darkNow) {
                                        drawRect(Color(0xFF0A101C).copy(alpha = 0.10f))
                                        drawRect(
                                            brush = meniscusCache.obtain(size.height, true),
                                            alpha = 0.65f,
                                        )
                                    } else {
                                        // 浅色鼓起态：白色弯月面光随 (1 - p) 完整退场，叠极淡冰水冷调与微暗水体压住过曝
                                        val restMeniscus = (1f - p).coerceIn(0f, 1f)
                                        if (restMeniscus > 0.005f) {
                                            drawRect(
                                                brush = meniscusCache.obtain(size.height, false),
                                                alpha = 0.42f * restMeniscus,
                                            )
                                        }
                                        drawRect(Color(0xFFD8E6FF).copy(alpha = 0.06f * p))
                                        drawRect(Color.Black.copy(alpha = 0.025f * p))
                                    }
                                } else {
                                    val micaSliderTint = if (darkNow) {
                                        Color(0xFF202534).copy(alpha = 0.62f)
                                    } else {
                                        Color(0xFFF6F7FB).copy(alpha = 0.56f)
                                    }
                                    drawRect(micaSliderTint)
                                    drawRect(Color.White.copy(alpha = if (darkNow) 0.10f else 0.28f))
                                }
                            },
                            onDrawFront = {
                                val darkNow = AppShellState.isDark
                                val ultraNow = AppShellState.glassLevel == "ultra"
                                val p = dampedDrag.pressProgress
                                val r = size.minDimension * 0.5f
                                if (!darkNow) {
                                    val strokeW = 0.6.dp.toPx()
                                    val half = strokeW * 0.5f
                                    drawRoundRect(
                                        color = Color.Black.copy(alpha = (if (ultraNow) 0.11f else 0.075f) * p),
                                        topLeft = Offset(half, half),
                                        size = Size(
                                            (size.width - strokeW).coerceAtLeast(0f),
                                            (size.height - strokeW).coerceAtLeast(0f),
                                        ),
                                        cornerRadius = CornerRadius(
                                            (r - half).coerceAtLeast(0f),
                                            (r - half).coerceAtLeast(0f),
                                        ),
                                        style = Stroke(width = strokeW),
                                    )
                                }
                                if (spotShader != null) {
                                    val radius = (size.minDimension * 1.25f).coerceAtLeast(1f)
                                    val spotAlpha = if (ultraNow) {
                                        if (darkNow) 0.14f else 0.04f * p
                                    } else {
                                        (if (darkNow) 0.07f else 0.025f) * p
                                    }
                                    val spotColor = Color.White.copy(alpha = spotAlpha)
                                    spotShader.setColorUniform("color", spotColor)
                                    spotShader.setFloatUniform("radius", radius)
                                    spotShader.setFloatUniform("position", size.width * 0.5f, size.height * 0.32f)
                                    drawRoundRect(
                                        brush = spotShader.asBrush(),
                                        cornerRadius = CornerRadius(r, r),
                                        blendMode = BlendMode.Plus,
                                    )
                                }
                            },
                        )
                        .innerShadow(shape = CircleShape) {
                            val darkNow = AppShellState.isDark
                            val ultraNow = AppShellState.glassLevel == "ultra"
                            val p = dampedDrag.pressProgress
                            if (p > 0.01f) {
                                if (ultraNow) {
                                    InnerShadow(
                                        radius = if (darkNow) 7.5.dp * smoothstep01(p) else lerpDp(3.dp, 8.5.dp, p),
                                        color = Color.Black.copy(
                                            alpha = if (darkNow) 0.32f else lerp(0.08f, 0.24f, p)
                                        ),
                                        alpha = 1f,
                                    )
                                } else {
                                    InnerShadow(
                                        radius = lerpDp(2.dp, 4.dp, p),
                                        color = Color.Black.copy(
                                            alpha = if (darkNow) 0.16f else 0.08f
                                        ),
                                        alpha = 1f,
                                    )
                                }
                            } else {
                                null
                            }
                        }
                )
            } else {
                // 非着色器保底模式下的内敛弹性滑行气垫
                Box(
                    modifier = Modifier
                        .padding(horizontal = innerPadding, vertical = innerPadding)
                        .width(cellWidth)
                        .height(dropletHeight)
                        .graphicsLayer {
                            val smoothP = smoothstep01(dampedDrag.pressProgress)
                            translationX = dampedDrag.value * cellWidthPx + panelOffset
                            val v = dampedDrag.velocity / 10f
                            val baseSX = 1f + (dampedDrag.scaleX - 1f) * 0.45f
                            val baseSY = 1f + (dampedDrag.scaleY - 1f) * 0.45f
                            scaleX = baseSX / (1f - (v * 0.38f).coerceIn(-0.22f, 0.22f))
                            scaleY = baseSY * (1f - (v * 0.12f).coerceIn(-0.22f, 0.22f))
                            alpha = smoothP
                        }
                        .clip(CircleShape)
                        .background(
                            if (isDark) Color(0xFF0A84FF).copy(alpha = 0.24f) else Color(0xFF007AFF).copy(alpha = 0.15f),
                            CircleShape,
                        )
                        .border(width = 0.8.dp, brush = fallbackRimBrush, shape = CircleShape)
                )
            }
        }

        // =====================================================================
        // 第 2c 层：标准档（温润云母）顶层清晰矢量图标层（悬浮于柔雾磨砂滑块之上，零重模糊）
        // =====================================================================
        if (isShaderGlass && combinedDropletBackdrop != null) {
            DualCapsuleIconsRow(
                isDark = isDark,
                iconSize = iconSize,
                cellWidth = cellWidth,
                capsuleHeight = capsuleHeight,
                innerPadding = innerPadding,
                searchPath = searchPath,
                sunPath = sunPath,
                moonPath = moonPath,
                themeSpinDeg = themeSpinDeg,
                themePulseScale = themePulseScale,
                color = contentColor,
                scaleForCell = { cellIdx ->
                    val smoothP = smoothstep01(dampedDrag.pressProgress)
                    val proximity = (1f - abs(dampedDrag.value - cellIdx)).coerceIn(0f, 1f)
                    lerp(1f, 1.09f, smoothP * proximity)
                },
                alphaForCell = {
                    if (AppShellState.glassLevel == "ultra") 0f else 1f
                },
                modifier = Modifier.graphicsLayer { translationX = panelOffset },
            )
        }

        // =====================================================================
        // 第 3 层：无障碍独立语义与全胶囊统一阻尼手势捕获区
        // =====================================================================
        Row(
            modifier = Modifier
                .fillMaxSize()
                .then(dampedDrag.modifier),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics {
                        role = Role.Button
                        contentDescription = "搜索"
                        onClick(label = "搜索") {
                            onSearchClickUpdated()
                            true
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics {
                        role = Role.Button
                        contentDescription = "切换主题"
                        onClick(label = "切换主题") {
                            onThemeToggleUpdated()
                            true
                        }
                    }
            )
        }
    }
}

@Composable
private fun DualCapsuleIconsRow(
    isDark: Boolean,
    iconSize: Dp,
    cellWidth: Dp,
    capsuleHeight: Dp,
    innerPadding: Dp,
    searchPath: Path,
    sunPath: Path,
    moonPath: Path,
    themeSpinDeg: Float,
    themePulseScale: Float,
    color: Color,
    scaleForCell: (Int) -> Float,
    alphaForCell: (Int) -> Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(capsuleHeight)
            .padding(horizontal = innerPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(cellWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer {
                        val s = scaleForCell(0)
                        scaleX = s
                        scaleY = s
                        alpha = alphaForCell(0)
                    }
            ) {
                val sc = size.width / 24f
                scale(sc, sc, pivot = Offset.Zero) {
                    drawPath(searchPath, color = color, style = Fill)
                }
            }
        }

        Box(
            modifier = Modifier
                .width(cellWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            ThemeMorphCanvas(
                isDark = isDark,
                sunPath = sunPath,
                moonPath = moonPath,
                iconSize = iconSize,
                color = color,
                spinDeg = themeSpinDeg,
                pulseScale = themePulseScale * scaleForCell(1),
                modifier = Modifier.graphicsLayer {
                    alpha = alphaForCell(1)
                },
            )
        }
    }
}
