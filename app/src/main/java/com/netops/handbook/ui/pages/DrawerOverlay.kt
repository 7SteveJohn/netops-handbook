package com.netops.handbook.ui.pages

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.netops.handbook.lens.glass2.lens
import com.netops.handbook.lens.glass2.rememberIosDirectionalHighlight
import com.netops.handbook.ui.AppShellState
import com.netops.handbook.ui.OpsColors
import com.netops.handbook.ui.components.LiquidOrbController
import com.netops.handbook.ui.glassClickable
import com.netops.handbook.ui.opsColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import kotlin.math.abs
import kotlin.math.roundToInt

private class DrawerRimBrushCache {
    private var lastHeight: Float = -1f
    private var lastDark: Boolean = false
    private var lastUltra: Boolean = false
    private var cached: Brush? = null

    fun obtain(height: Float, isDark: Boolean, ultraMode: Boolean): Brush {
        val h = height.coerceAtLeast(1f)
        val cur = cached
        if (cur != null && lastHeight == h && lastDark == isDark && lastUltra == ultraMode) return cur
        val brush = if (ultraMode) {
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = if (isDark) 0.56f else 0.96f),
                0.35f to Color.White.copy(alpha = if (isDark) 0.20f else 0.56f),
                0.75f to if (isDark) {
                    Color.White.copy(alpha = 0.12f)
                } else {
                    Color(0xFFD8E0F0).copy(alpha = 0.48f)
                },
                1.0f to if (isDark) {
                    Color.White.copy(alpha = 0.28f)
                } else {
                    Color(0xFFC4CCE0).copy(alpha = 0.62f)
                },
                startY = 0f,
                endY = h,
            )
        } else {
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = if (isDark) 0.22f else 0.52f),
                0.38f to Color.White.copy(alpha = if (isDark) 0.07f else 0.22f),
                0.75f to if (isDark) {
                    Color.White.copy(alpha = 0.04f)
                } else {
                    Color(0xFFC8CCD8).copy(alpha = 0.20f)
                },
                1.0f to if (isDark) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color(0xFFB6BAC8).copy(alpha = 0.24f)
                },
                startY = 0f,
                endY = h,
            )
        }
        lastHeight = h
        lastDark = isDark
        lastUltra = ultraMode
        cached = brush
        return brush
    }
}

/**
 * 脉络 · NetCraft 双档空间液态玻璃抽屉（极致 = 高透空间水晶折光；标准 = 温润重磨砂云母）：
 * - 极致档 (`ultraNow == true`)：`blur(15dp)` + 边缘厚玻璃折光 `lens(16dp, 22dp, depthEffect = true, CA = 0.10f)`
 *   + 晶莹低遮蔽面色（浅 `White@0.54f` / 深 `#0D1018@0.62f`，壁纸光影清晰透射）+ `1.0dp` 亮白菲涅尔切边 + `1.32x` 水珠关闭球；
 * - 标准档 (`ultraNow == false`)：`blur(28dp)` 温润重磨砂云母（零畸变、零色散）+ 稳重雾面底色（浅 `#F6F7FB@0.84f` / 深 `#12141C@0.88f`）
 *   + `0.65dp` 柔和细边 + `1.15x` 柔雾关闭钮；
 * - 内部导航行保持 100% 零离屏缓冲（`drawWithCache`）液态玻璃胶囊。
 */
@Composable
fun DrawerOverlay(
    data: () -> JSONObject?,
    dark: Boolean,
    onGo: (String, String) -> Unit,
    onClosed: () -> Unit,
    backdrop: LayerBackdrop? = null,
) {
    if (!AppShellState.drawerOpen) return

    val view = LocalView.current
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current

    val colors = remember(dark) { opsColors(dark, false) }
    val cutoutStart = WindowInsets.displayCutout.asPaddingValues().calculateStartPadding(layoutDirection)
    val navPadding = WindowInsets.navigationBars.asPaddingValues()
    val navStart = navPadding.calculateStartPadding(layoutDirection)
    val navBottom = navPadding.calculateBottomPadding()
    val safeStart = maxOf(cutoutStart, navStart)
    val extraCutoutStart = if (safeStart > 0.dp) safeStart + 4.dp else 0.dp

    val drawerWidthDp = (304.dp + extraCutoutStart).coerceAtMost((cfg.screenWidthDp * 0.78f).dp)
    val panelW = with(density) { drawerWidthDp.toPx() }
    val touchSlop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
    val scope = rememberCoroutineScope()

    val glassLevel = AppShellState.glassLevel
    val isGlassActive = glassLevel != "none"
    val isShaderGlass = isGlassActive &&
        backdrop != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        isRuntimeShaderSupported()

    var off by remember { mutableFloatStateOf(-panelW) }
    var routeSeen by remember { mutableStateOf(AppShellState.pageRoute) }
    var closing by remember { mutableStateOf(false) }
    var animJob by remember { mutableStateOf<Job?>(null) }

    fun finishClose() {
        AppShellState.drawerOpen = false
        onClosed()
    }

    fun animateOff(from: Float, to: Float, dur: Int, after: (() -> Unit)? = null) {
        animJob?.cancel()
        animJob = scope.launch {
            androidx.compose.animation.core.animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(dur),
            ) { v, _ -> off = from + (to - from) * v }
            after?.invoke()
        }
    }

    fun animateClose(velocityPx: Float = 0f, after: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        val distance = abs(-panelW - off)
        val dur = if (abs(velocityPx) > 300f) {
            (distance / abs(velocityPx) * 1000f).toInt().coerceIn(70, 200)
        } else {
            (200f * (distance / panelW)).toInt().coerceIn(100, 200)
        }
        animateOff(off, -panelW, dur) {
            after?.invoke()
            finishClose()
        }
    }

    LaunchedEffect(Unit) {
        animateOff(-panelW, 0f, 200)
    }

    LaunchedEffect(AppShellState.pageRoute) {
        if (AppShellState.pageRoute != routeSeen) {
            routeSeen = AppShellState.pageRoute
            if (AppShellState.drawerOpen) {
                animateOff(off, -panelW, 120)
                finishClose()
            }
        }
    }
    BackHandler { animateClose() }

    val drawerShadowPaint = remember(dark, density.density) {
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(if (dark) 115 else 38, 0, 0, 0)
                maskFilter = BlurMaskFilter(16f * density.density, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val closeOrbShadowPaint = remember(dark, density.density) {
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(if (dark) 70 else 22, 0, 0, 0)
                maskFilter = BlurMaskFilter(6f * density.density, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val rimCache = remember { DrawerRimBrushCache() }
    val drawerHighlight = rememberIosDirectionalHighlight(
        width = 1.0.dp,
        color = Color.White.copy(alpha = if (dark) 0.56f else 0.90f),
        baseDegrees = 38f,
        falloff = 1.12f,
        shaderKey = "IosDrawerPanelHighlight",
    )

    val panelShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
    val fallbackPanelBg = if (dark) {
        Color(0xFF16181E).copy(alpha = 0.96f)
    } else {
        Color(0xFFFAFAFC).copy(alpha = 0.97f)
    }

    val panelSurfaceModifier = if (isShaderGlass && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { panelShape },
            effects = {
                val darkNow = AppShellState.isDark
                val ultraNow = AppShellState.glassLevel == "ultra"
                if (ultraNow) {
                    padding = maxOf(padding, 28.dp.toPx())
                    colorControls(
                        brightness = if (darkNow) -0.005f else 0.02f,
                        contrast = 1.06f,
                        saturation = 1.72f,
                    )
                    blur(15.dp.toPx(), 15.dp.toPx())
                    lens(
                        refractionHeight = 16.dp.toPx(),
                        refractionAmount = 22.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = 0.10f,
                    )
                } else {
                    colorControls(
                        brightness = if (darkNow) -0.02f else 0.02f,
                        contrast = 1.00f,
                        saturation = 1.26f,
                    )
                    blur(28.dp.toPx(), 28.dp.toPx())
                }
            },
            highlight = {
                val ultraNow = AppShellState.glassLevel == "ultra"
                val base = drawerHighlight.value
                if (ultraNow) {
                    base.copy(width = 1.0.dp, alpha = 1f)
                } else {
                    base.copy(width = 0.65.dp, alpha = 0.42f)
                }
            },
            onDrawSurface = {
                val darkNow = AppShellState.isDark
                val ultraNow = AppShellState.glassLevel == "ultra"
                val tint = if (ultraNow) {
                    if (darkNow) {
                        Color(0xFF0D1018).copy(alpha = 0.62f)
                    } else {
                        Color.White.copy(alpha = 0.54f)
                    }
                } else {
                    if (darkNow) {
                        Color(0xFF12141C).copy(alpha = 0.88f)
                    } else {
                        Color(0xFFF6F7FB).copy(alpha = 0.84f)
                    }
                }
                drawRect(tint)
            },
            onDrawFront = {
                val darkNow = AppShellState.isDark
                val ultraNow = AppShellState.glassLevel == "ultra"
                val r = 22.dp.toPx()
                val strokeW = if (ultraNow) 1.0.dp.toPx() else 0.65.dp.toPx()
                drawRoundRect(
                    brush = rimCache.obtain(size.height, darkNow, ultraNow),
                    topLeft = Offset(-r, strokeW * 0.5f),
                    size = Size(size.width + r - strokeW * 0.5f, size.height - strokeW),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = strokeW),
                )
            },
        )
    } else {
        Modifier
            .background(fallbackPanelBg, panelShape)
            .border(
                width = 0.85.dp,
                color = colors.border.copy(alpha = if (dark) 0.55f else 0.40f),
                shape = panelShape,
            )
    }

    val currentRoute = AppShellState.pageRoute
    val currentPhasePid = AppShellState.phasePid

    Box(Modifier.fillMaxSize()) {
        val scrimAlpha = (1f - abs(off) / panelW).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(Color(if (dark) 0x7A000000 else 0x59000000))
                .pointerInput(Unit) {
                    detectTapGestures { animateClose() }
                }
        )

        Box(
            Modifier
                .width(drawerWidthDp)
                .fillMaxHeight()
                .offset { IntOffset(off.roundToInt(), 0) }
                .drawBehind {
                    val r = 22.dp.toPx()
                    val dx = 5.dp.toPx()
                    drawIntoCanvas { canvas ->
                        canvas.drawRoundRect(
                            left = -r,
                            top = 2.dp.toPx(),
                            right = size.width + dx,
                            bottom = size.height - 2.dp.toPx(),
                            radiusX = r,
                            radiusY = r,
                            paint = drawerShadowPaint,
                        )
                    }
                }
                .then(panelSurfaceModifier)
                .statusBarsPadding()
                .pointerInput(panelW) {
                    var tracker: VelocityTracker? = null
                    while (true) {
                        var verdict = 0
                        var flingVel = 0f
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var draggingNow = false
                            val downX = down.position.x
                            val downY = down.position.y
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pt: androidx.compose.ui.input.pointer.PointerInputChange =
                                    ev.changes.firstOrNull { it.id == down.id }
                                        ?: ev.changes.first()
                                if (!pt.pressed) {
                                    if (draggingNow) {
                                        tracker?.addPosition(pt.uptimeMillis, pt.position)
                                        val v = tracker?.calculateVelocity()?.x ?: 0f
                                        val fling = with(density) { -500.dp.toPx() }
                                        verdict = if (off < -panelW * 0.4f || v < fling) 1 else 2
                                        flingVel = v
                                    }
                                    break
                                }
                                val dx = pt.position.x - downX
                                val dy = pt.position.y - downY
                                if (!draggingNow && dx < -touchSlop && abs(dx) > abs(dy)) {
                                    draggingNow = true
                                    animJob?.cancel()
                                    if (tracker == null) tracker = VelocityTracker()
                                    tracker.resetTracking()
                                }
                                if (draggingNow) {
                                    tracker!!.addPosition(pt.uptimeMillis, pt.position)
                                    off = (off + pt.positionChange().x).coerceIn(-panelW, 0f)
                                    pt.consume()
                                }
                            }
                        }
                        if (verdict == 1) animateClose(velocityPx = flingVel)
                        else if (verdict == 2) animateOff(off, 0f, 150)
                    }
                }
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp + extraCutoutStart,
                        top = 18.dp,
                        end = 16.dp,
                        bottom = 24.dp + navBottom,
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                    ) {
                        Text(
                            text = "脉络 · NetCraft",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "网络与运维随身手册",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Normal,
                            color = colors.textMute,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    DrawerCloseOrbButton(
                        colors = colors,
                        isGlassActive = isGlassActive,
                        shadowPaint = closeOrbShadowPaint,
                        onClose = { animateClose() },
                    )
                }

                val rootObj = data()
                val phases = rootObj?.optJSONArray("phases")
                var modTotal = 0
                if (phases != null) {
                    for (i in 0 until phases.length()) {
                        modTotal += phases.optJSONObject(i)?.optJSONArray("mods")?.length() ?: 0
                    }
                }

                DrawerSection(colors, "核心页面")
                DrawerItem(
                    colors = colors,
                    title = "学习手册",
                    meta = "${phases?.length() ?: 0} 阶段 · ${if (modTotal > 0) modTotal else 89} 模块",
                    active = currentRoute == "learn",
                    isGlassActive = isGlassActive,
                ) {
                    animateClose { onGo("learn", "") }
                }
                DrawerItem(
                    colors = colors,
                    title = "运维教材全书",
                    meta = "12 领域 · 50 章 · 全文可搜",
                    active = currentRoute == "textbook",
                    isGlassActive = isGlassActive,
                ) {
                    animateClose { onGo("textbook", "") }
                }
                DrawerItem(
                    colors = colors,
                    title = "我的",
                    meta = "进度 · 收藏 · 设置",
                    active = currentRoute == "me",
                    isGlassActive = isGlassActive,
                ) {
                    animateClose { onGo("me", "") }
                }

                DrawerSection(colors, "技能目录")
                if (phases != null) {
                    for (i in 0 until phases.length()) {
                        val p = phases.optJSONObject(i) ?: continue
                        val pid = p.optString("id")
                        val n = p.optJSONArray("mods")?.length() ?: 0
                        DrawerItem(
                            colors = colors,
                            title = p.optString("title"),
                            meta = "$n 模块",
                            active = currentRoute == "phase" && currentPhasePid == pid,
                            isGlassActive = isGlassActive,
                        ) {
                            animateClose { onGo("phase", pid) }
                        }
                    }
                }

                DrawerSection(colors, "查询工具")
                DrawerItem(
                    colors = colors,
                    title = "排障字典",
                    meta = "${count(rootObj, "faults")} 个案例",
                    active = currentRoute == "fault",
                    isGlassActive = isGlassActive,
                ) {
                    animateClose { onGo("fault", "") }
                }
                DrawerItem(
                    colors = colors,
                    title = "命令字典",
                    meta = "${dictRows(rootObj)} 条对照",
                    active = currentRoute == "dict",
                    isGlassActive = isGlassActive,
                ) {
                    animateClose { onGo("dict", "") }
                }
                DrawerItem(
                    colors = colors,
                    title = "速查表",
                    meta = "${count(rootObj, "refs")} 张",
                    active = currentRoute == "refs",
                    isGlassActive = isGlassActive,
                ) {
                    animateClose { onGo("refs", "") }
                }
            }
        }
    }
}

/**
 * 抽屉右上角双档关闭圆球（极致 = `1.32x` 清透水珠关闭球；标准 = `1.15x` 温润柔雾关闭钮）：
 * - 接入 `LiquidOrbController`（快点 >=0.84f 冲顶膨胀 + `tanh` 阻尼牵引 + `atan2` 体积守恒拉伸 + `0.46f` 欠阻尼回弹）；
 * - 实时读取 `AppShellState.glassLevel == "ultra"` 区分 `1.32x` 晶莹水珠与 `1.15x` 柔雾微弹，零离屏缓冲。
 */
@Composable
private fun DrawerCloseOrbButton(
    colors: OpsColors,
    isGlassActive: Boolean,
    shadowPaint: Paint,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val onCloseUpdated by rememberUpdatedState(onClose)
    val maxPullPx = with(density) { 18.dp.toPx() }

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
        onCloseUpdated()
    }

    val dark = colors.dark

    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 0. 浮空接触微阴影（位于 clip 外侧）
        if (isGlassActive) {
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
                        scaleX = if (ultraNow) rawSX else 1f + (rawSX - 1f) * 0.47f
                        scaleY = if (ultraNow) rawSY else 1f + (rawSY - 1f) * 0.47f
                    }
                    .drawBehind {
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        val p = orbController.pressProgress
                        val r = size.minDimension * 0.5f
                        val dy = if (ultraNow) {
                            lerp(1.8.dp.toPx(), 4.0.dp.toPx(), p)
                        } else {
                            lerp(1.4.dp.toPx(), 2.6.dp.toPx(), p)
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
        }

        // 1. 双档圆球表面（drawWithCache 缓存水晶水珠与云母柔雾画刷，零离屏缓冲）
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
                    scaleX = if (ultraNow) rawSX else 1f + (rawSX - 1f) * 0.47f
                    scaleY = if (ultraNow) rawSY else 1f + (rawSY - 1f) * 0.47f
                }
                .clip(CircleShape)
                .drawWithCache {
                    val h = size.height.coerceAtLeast(1f)
                    val r = size.minDimension * 0.5f
                    val corner = CornerRadius(r, r)

                    val ultraStrokeW = 0.92.dp.toPx()
                    val ultraInset = ultraStrokeW * 0.5f
                    val ultraStrokeTopLeft = Offset(ultraInset, ultraInset)
                    val ultraStrokeSize = Size(
                        (size.width - ultraStrokeW).coerceAtLeast(0f),
                        (size.height - ultraStrokeW).coerceAtLeast(0f),
                    )
                    val ultraStrokeCorner = CornerRadius(
                        (r - ultraInset).coerceAtLeast(0f),
                        (r - ultraInset).coerceAtLeast(0f),
                    )

                    val stdStrokeW = 0.62.dp.toPx()
                    val stdInset = stdStrokeW * 0.5f
                    val stdStrokeTopLeft = Offset(stdInset, stdInset)
                    val stdStrokeSize = Size(
                        (size.width - stdStrokeW).coerceAtLeast(0f),
                        (size.height - stdStrokeW).coerceAtLeast(0f),
                    )
                    val stdStrokeCorner = CornerRadius(
                        (r - stdInset).coerceAtLeast(0f),
                        (r - stdInset).coerceAtLeast(0f),
                    )

                    val crystalBodyBrush = Brush.verticalGradient(
                        0.00f to if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.68f),
                        0.42f to if (dark) Color(0xFF1A2030).copy(alpha = 0.28f) else Color(0xFFEEF4FF).copy(alpha = 0.24f),
                        1.00f to if (dark) Color(0xFF101522).copy(alpha = 0.36f) else Color.White.copy(alpha = 0.38f),
                        startY = 0f,
                        endY = h,
                    )

                    val micaBodyBrush = Brush.verticalGradient(
                        0.00f to if (dark) Color(0xFF252936).copy(alpha = 0.80f) else Color(0xFFECEFF6).copy(alpha = 0.84f),
                        1.00f to if (dark) Color(0xFF1E222D).copy(alpha = 0.84f) else Color(0xFFE4E8F2).copy(alpha = 0.86f),
                        startY = 0f,
                        endY = h,
                    )

                    val flatBodyBrush = Brush.verticalGradient(
                        0.0f to colors.chipBgOff,
                        1.0f to colors.chipBgOff,
                    )

                    val meniscusBrush = Brush.verticalGradient(
                        0.00f to Color.White.copy(alpha = if (dark) 0.26f else 0.72f),
                        0.36f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1.00f to Color.White.copy(alpha = if (dark) 0.10f else 0.28f),
                        startY = 0f,
                        endY = h,
                    )

                    val ultraRimBrush = Brush.verticalGradient(
                        0.00f to Color.White.copy(alpha = if (dark) 0.60f else 0.98f),
                        0.35f to Color.White.copy(alpha = if (dark) 0.18f else 0.44f),
                        0.72f to if (dark) Color.White.copy(alpha = 0.07f) else Color(0xFFC2C8D6).copy(alpha = 0.40f),
                        1.00f to if (dark) Color.White.copy(alpha = 0.28f) else Color(0xFFAEB5C6).copy(alpha = 0.54f),
                        startY = 0f,
                        endY = h,
                    )

                    val stdRimBrush = Brush.verticalGradient(
                        0.00f to Color.White.copy(alpha = if (dark) 0.22f else 0.68f),
                        1.00f to if (dark) Color.White.copy(alpha = 0.08f) else Color(0xFFC4C8D4).copy(alpha = 0.34f),
                        startY = 0f,
                        endY = h,
                    )

                    val flatRimBrush = Brush.verticalGradient(
                        0.0f to colors.border.copy(alpha = 0.65f),
                        1.0f to colors.border.copy(alpha = 0.65f),
                    )

                    onDrawBehind {
                        val ultraNow = AppShellState.glassLevel == "ultra"
                        val p = orbController.pressProgress
                        if (!isGlassActive) {
                            drawRoundRect(brush = flatBodyBrush, cornerRadius = corner)
                            drawRoundRect(
                                brush = flatRimBrush,
                                topLeft = stdStrokeTopLeft,
                                size = stdStrokeSize,
                                cornerRadius = stdStrokeCorner,
                                style = Stroke(width = stdStrokeW),
                            )
                        } else if (ultraNow) {
                            val restAlpha = lerp(1f, 0.48f, p)
                            drawRoundRect(brush = crystalBodyBrush, cornerRadius = corner, alpha = restAlpha)
                            drawRoundRect(
                                brush = meniscusBrush,
                                cornerRadius = corner,
                                alpha = lerp(0.88f, 1f, p),
                            )
                            drawRoundRect(
                                brush = ultraRimBrush,
                                topLeft = ultraStrokeTopLeft,
                                size = ultraStrokeSize,
                                cornerRadius = ultraStrokeCorner,
                                style = Stroke(width = ultraStrokeW),
                            )
                        } else {
                            drawRoundRect(
                                brush = micaBodyBrush,
                                cornerRadius = corner,
                                alpha = lerp(1f, 0.88f, p),
                            )
                            drawRoundRect(
                                brush = stdRimBrush,
                                topLeft = stdStrokeTopLeft,
                                size = stdStrokeSize,
                                cornerRadius = stdStrokeCorner,
                                style = Stroke(width = stdStrokeW),
                            )
                        }
                    }
                }
                .then(orbController.highlightModifier)
        )

        // 2. 90° 弹性旋转关闭图标（极致档跟随水珠放大 1.15x，标准档柔和放大 1.07x）
        Canvas(
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    val pullMul = if (ultraNow) 1f else 0.55f
                    val pull = orbController.pullOffset
                    val p = orbController.pressProgress
                    val mag = lerp(1f, if (ultraNow) 1.15f else 1.07f, p)
                    translationX = pull.x * pullMul
                    translationY = pull.y * pullMul
                    scaleX = mag
                    scaleY = mag
                }
        ) {
            val p = orbController.pressProgress
            val arm = size.minDimension * 0.27f
            val strokePx = 1.9.dp.toPx()
            val iconTint = lerpColor(colors.textMain, colors.accent, p * 0.65f)
            rotate(degrees = 90f * p, pivot = center) {
                drawLine(
                    color = iconTint,
                    start = Offset(center.x - arm, center.y - arm),
                    end = Offset(center.x + arm, center.y + arm),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = iconTint,
                    start = Offset(center.x + arm, center.y - arm),
                    end = Offset(center.x - arm, center.y + arm),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
            }
        }

        // 3. 无障碍语义与全热区流体手势捕获
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    role = Role.Button
                    contentDescription = "关闭侧边栏"
                    onClick(label = "关闭侧边栏") {
                        onCloseUpdated()
                        true
                    }
                }
                .then(orbController.gestureModifier)
        )
    }
}

private fun count(root: JSONObject?, key: String): Int =
    root?.optJSONArray(key)?.length() ?: 0

private fun dictRows(root: JSONObject?): Int =
    root?.optJSONObject("dict")?.optJSONArray("rows")?.length() ?: 0

@Composable
private fun DrawerSection(colors: OpsColors, title: String) {
    Text(
        text = title,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = colors.textMute,
        modifier = Modifier.padding(top = 11.dp, bottom = 6.dp, start = 2.dp),
    )
}

@Composable
private fun DrawerItem(
    colors: OpsColors,
    title: String,
    meta: String,
    active: Boolean,
    isGlassActive: Boolean,
    onClick: () -> Unit,
) {
    // 跨项切换欠阻尼弹性激活进度：赋予当前激活胶囊灵动宝石高光与侧标弹射感
    val activeAnim by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 340f),
        label = "drawer_item_active",
    )

    val itemShape = remember { RoundedCornerShape(14.dp) }
    val dark = colors.dark

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .glassClickable(
                pressedScale = 0.972f,
                glowBoost = if (dark) 0.09f else 0.12f,
                onClick = onClick,
            )
            .clip(itemShape)
            .drawWithCache {
                val r = 14.dp.toPx()
                val corner = CornerRadius(r, r)
                val ultraStrokeW = 0.86.dp.toPx()
                val ultraInset = ultraStrokeW * 0.5f
                val ultraBorderTopLeft = Offset(ultraInset, ultraInset)
                val ultraBorderSize = Size(
                    (size.width - ultraStrokeW).coerceAtLeast(0f),
                    (size.height - ultraStrokeW).coerceAtLeast(0f),
                )
                val stdStrokeW = 0.65.dp.toPx()
                val stdInset = stdStrokeW * 0.5f
                val stdBorderTopLeft = Offset(stdInset, stdInset)
                val stdBorderSize = Size(
                    (size.width - stdStrokeW).coerceAtLeast(0f),
                    (size.height - stdStrokeW).coerceAtLeast(0f),
                )
                val h = size.height.coerceAtLeast(1f)

                val ultraInactiveFillBrush = if (dark) {
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.11f),
                        1.0f to Color.White.copy(alpha = 0.055f),
                        startY = 0f,
                        endY = h,
                    )
                } else {
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.62f),
                        1.0f to Color(0xFFEEF3FC).copy(alpha = 0.44f),
                        startY = 0f,
                        endY = h,
                    )
                }

                val stdInactiveFillBrush = if (dark) {
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.065f),
                        1.0f to Color.White.copy(alpha = 0.04f),
                        startY = 0f,
                        endY = h,
                    )
                } else {
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.80f),
                        1.0f to Color(0xFFF3F5FA).copy(alpha = 0.68f),
                        startY = 0f,
                        endY = h,
                    )
                }

                val flatInactiveFillBrush = Brush.verticalGradient(
                    0.0f to colors.chipBgOff,
                    1.0f to colors.chipBgOff,
                    startY = 0f,
                    endY = h,
                )

                val activeFillBrush = if (isGlassActive) {
                    Brush.verticalGradient(
                        0.0f to colors.accent.copy(alpha = if (dark) 0.32f else 0.20f),
                        0.55f to colors.accent.copy(alpha = if (dark) 0.22f else 0.13f),
                        1.0f to colors.accent.copy(alpha = if (dark) 0.16f else 0.09f),
                        startY = 0f,
                        endY = h,
                    )
                } else {
                    Brush.verticalGradient(
                        0.0f to colors.chipBgOn,
                        1.0f to colors.chipBgOn,
                        startY = 0f,
                        endY = h,
                    )
                }

                val domeBrush = if (isGlassActive) {
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = if (dark) 0.18f else 0.56f),
                        0.44f to Color.Transparent,
                        startY = 0f,
                        endY = h,
                    )
                } else {
                    null
                }

                val ultraInactiveRimBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.36f else 0.96f),
                    0.36f to Color.White.copy(alpha = if (dark) 0.10f else 0.40f),
                    0.72f to Color.White.copy(alpha = 0.03f),
                    1.0f to if (dark) {
                        Color.White.copy(alpha = 0.16f)
                    } else {
                        Color(0xFFBCC4D6).copy(alpha = 0.44f)
                    },
                    startY = 0f,
                    endY = h,
                )

                val stdInactiveRimBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.14f else 0.62f),
                    1.0f to if (dark) {
                        Color.White.copy(alpha = 0.05f)
                    } else {
                        Color(0xFFC8CCD8).copy(alpha = 0.28f)
                    },
                    startY = 0f,
                    endY = h,
                )

                val flatInactiveRimBrush = Brush.verticalGradient(
                    0.0f to colors.border.copy(alpha = 0.55f),
                    1.0f to colors.border.copy(alpha = 0.55f),
                    startY = 0f,
                    endY = h,
                )

                val activeRimBrush = Brush.verticalGradient(
                    0.0f to lerpColor(
                        colors.accent.copy(alpha = if (dark) 0.68f else 0.54f),
                        Color.White.copy(alpha = if (dark) 0.60f else 0.90f),
                        0.38f,
                    ),
                    0.50f to colors.accent.copy(alpha = if (dark) 0.26f else 0.22f),
                    1.0f to colors.accent.copy(alpha = if (dark) 0.40f else 0.32f),
                    startY = 0f,
                    endY = h,
                )

                onDrawBehind {
                    val ultraNow = AppShellState.glassLevel == "ultra"
                    val a = activeAnim.coerceIn(0f, 1f)
                    val overshootGleam = (activeAnim - 1f).coerceIn(0f, 0.25f)
                    val strokeW = if (isGlassActive && ultraNow) ultraStrokeW else stdStrokeW
                    val borderTopLeft = if (isGlassActive && ultraNow) ultraBorderTopLeft else stdBorderTopLeft
                    val borderSize = if (isGlassActive && ultraNow) ultraBorderSize else stdBorderSize
                    val inactiveFill = when {
                        !isGlassActive -> flatInactiveFillBrush
                        ultraNow -> ultraInactiveFillBrush
                        else -> stdInactiveFillBrush
                    }
                    val inactiveRim = when {
                        !isGlassActive -> flatInactiveRimBrush
                        ultraNow -> ultraInactiveRimBrush
                        else -> stdInactiveRimBrush
                    }

                    if (a < 0.999f) {
                        drawRoundRect(
                            brush = inactiveFill,
                            cornerRadius = corner,
                            alpha = (1f - a).coerceIn(0f, 1f),
                        )
                    }
                    if (a > 0.001f) {
                        drawRoundRect(
                            brush = activeFillBrush,
                            cornerRadius = corner,
                            alpha = a,
                        )
                    }
                    if (domeBrush != null && (ultraNow || a > 0.01f)) {
                        val restDome = if (ultraNow) {
                            if (dark) 0.52f else 0.85f
                        } else {
                            0f
                        }
                        val baseDomeAlpha = lerp(restDome, if (ultraNow) 1f else 0.55f, a)
                        drawRoundRect(
                            brush = domeBrush,
                            cornerRadius = corner,
                            alpha = (baseDomeAlpha + overshootGleam * 0.8f).coerceIn(0f, 1f),
                        )
                    }
                    if (overshootGleam > 0.005f) {
                        drawRoundRect(
                            color = colors.accent.copy(alpha = overshootGleam * (if (dark) 0.32f else 0.20f)),
                            cornerRadius = corner,
                        )
                    }
                    if (a < 0.999f) {
                        drawRoundRect(
                            brush = inactiveRim,
                            topLeft = borderTopLeft,
                            size = borderSize,
                            cornerRadius = corner,
                            style = Stroke(width = strokeW),
                            alpha = (1f - a).coerceIn(0f, 1f),
                        )
                    }
                    if (a > 0.001f) {
                        drawRoundRect(
                            brush = activeRimBrush,
                            topLeft = borderTopLeft,
                            size = borderSize,
                            cornerRadius = corner,
                            style = Stroke(width = strokeW),
                            alpha = a,
                        )
                    }
                }
            }
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            val pillAlpha = activeAnim.coerceIn(0f, 1f)
            if (pillAlpha > 0.01f) {
                Box(
                    Modifier
                        .width(3.5.dp)
                        .height(22.dp)
                        .graphicsLayer {
                            scaleY = activeAnim.coerceIn(0.2f, 1.22f)
                            alpha = pillAlpha
                        }
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent)
                )
                Spacer(Modifier.width(9.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) colors.accent else colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    color = colors.textMute,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Text(
            text = "›",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) colors.accent else colors.textMute.copy(alpha = 0.70f),
            modifier = Modifier
                .padding(start = 6.dp)
                .graphicsLayer {
                    translationX = activeAnim * 1.5.dp.toPx()
                },
        )
    }
}
