package com.netops.handbook.lens.glass2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.netops.handbook.lens.glass.BloomStrokeFx
import com.netops.handbook.lens.glass.LiquidGlassShaders
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Compose 液态玻璃 dock(2026-11-12 P2,FlClash/KernelSU 同代架构)。
 *
 * 结构 = Kyant0 backdrop 同构:内容层(AndroidView 包既有 View 树)record 进
 * GraphicsLayer;玻璃节点 drawLayer 回放 + graphicsLayer.renderEffect 挂
 * blur→vibrancy→lens 链——像素全在 GPU 管线内原子提交,无跨线程位图、
 * 无 PixelCopy、无独立窗口。旧 LensBarView 的抽搐/闪白/割裂一族 bug 在此
 * 架构下不存在。
 *
 * 玻璃层级(对齐上游 z 序):
 *   栏体 = 投影 → [回放(blur+vibrancy) → 栏缘 lens(24dp)] → surface tint
 *          → BloomStroke 高光 → 灰态图标 → 药丸
 *   药丸 = [磨砂回放 + 清晰回放(α=lift) + 白底(α=150(1-lift)) + tab 镜像]
 *          → 折射 lens(2dp×lift, CA0.5) → mask(胶囊) → 按压面 + 触摸光晕
 *
 * 两态语义(2026-11-10 用户定案):平常磨砂、按住全透明。
 */
@Composable
fun GlassDockHost(
    anchor: View, modifier: Modifier = Modifier,
    pages: @Composable () -> Unit = {},
) {
    val captureLayer = rememberGraphicsLayer()
    var winSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { anchor },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { winSize = it }
                .drawWithContent {
                    /* 捕获:本节点内容(View 层=壁纸/旧 View 页)进 layer。dock 在本
                       子树外,天然无自反馈(探针实证:record 范围与玻璃必须互斥)。
                       M3 终局:Compose 页为本 Box 兄弟节点(PagesOverlay),不进
                       record 也不被嵌套——MagicOS 嵌套 ComposeView 不合成(M3 首验)。 */
                    captureLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(captureLayer)
                }
        )
        /* M3 终局:页面层 = dock 同组合兄弟节点(嵌套 ComposeView 在 MagicOS 不合成) */
        pages()
        GlassDock(captureLayer, anchor, winSize)
    }
}

@Composable
private fun GlassDock(captureLayer: GraphicsLayer, anchor: View, winSize: IntSize) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val density = context.resources.displayMetrics.density
    GlassDockEnv.set(density)

    val dark = DockBus.dark
    val glassLevel = DockBus.glassLevel
    val nativeDock = DockBus.nativeDock
    val dockedStyle = DockBus.docked
    val railTopPx = DockBus.railTopPx
    val refreshTick = DockBus.refreshTick
    val selIdx = DockBus.selectedIndex
    val busBar = DockBus.barRect

    val colors = remember(dark) { DockDraw.Colors(dark) }
    val iconPaths = remember(density) { DockDraw.buildIconPaths(density) }
    val iconP = remember(density) { DockDraw.iconPaint(density) }
    val textP = remember(density) { DockDraw.textPaint(density) }
    val model = remember(density) {
        DockModel(density,
            haptics = { hostView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) },
            commit = { i -> DockBus.onCommit?.invoke(i) })
    }

    /* 帧循环:每帧推进弹簧,值写入 State(静止不变 → 零重绘,等价旧自眠)。
       HONOR(MagicOS)(CAPTURE_PAGES=false):同帧节流喂 PageSnap——玻璃内容源
       = PixelCopy 快照(25fps,直读已合成 Surface,见 PageSnap.kt 头注)。 */
    val snapWindow = (context as? android.app.Activity)?.window
    LaunchedEffect(model, snapWindow) {
        while (true) {
            withFrameNanos { }
            model.tick()
            if (!DockBus.CAPTURE_PAGES && snapWindow != null
                && !DockBus.docked && DockBus.nativeDock
                && DockBus.glassLevel != "none") {
                val bar = DockBus.barRect
                if (bar.width() > 4 && bar.height() > 4) {
                    val pad = barPadPx(GlassDockEnv.density())
                    PageSnap.request(snapWindow, RectF(
                        bar.left - pad, bar.top - pad, bar.right + pad, bar.bottom + pad))
                }
            }
        }
    }
    LaunchedEffect(selIdx) { model.syncSelected(selIdx) }

    if (!nativeDock || winSize.width <= 0) return   // HTML 底栏在场:整体退位(旧 popup.dismiss)

    val winW = winSize.width.toFloat()
    val winH = winSize.height.toFloat()
    val vertical = winW > winH
    val glassNone = glassLevel == "none"
    val glassLive = !glassNone && !vertical && Build.VERSION.SDK_INT >= 33

    /* ---- 几何解析(照搬旧 updateOrientation/computeRailGeometry/dockRect) ---- */
    var axisFirst = 0f; var axisPitch = 0f
    var pillW = 0f; var pillH = 0f
    var pillCy = 0f
    val barRectW = RectF(busBar)     // 窗口本地(横排)
    val railRectW = RectF()          // 窗口本地(竖排)
    if (vertical) {
        val itemDp = railItemDp()
        val sc = itemDp / 40f
        val dp = density * sc
        val railW = itemDp * 1.2f * density
        val pitch = 44f * dp
        pillH = 40f * dp; pillW = 40f * dp
        val contentH = (DockDraw.TAB_LABELS.size - 1) * pitch + pillH
        val panelH = contentH + 8f * dp
        val availH = if (DockBus.viewportH > 0) min(winH - railTopPx, DockBus.viewportH)
                     else winH - railTopPx
        val cy0 = railTopPx + (availH - 14f * dp) / 2f
        railRectW.set(14f * density, cy0 - panelH / 2f, 14f * density + railW, cy0 + panelH / 2f)
        axisPitch = pitch
        axisFirst = railRectW.top + 4f * dp + pillH / 2f
    } else {
        axisPitch = if (DockBus.pitchPx > 0) DockBus.pitchPx else winW / 4f
        /* 兜底分档(2026-11-14 对齐 FlClash _minLensWidth84/_maxItemExtent86 + 设备档):
           尺寸真源 = JS 上报(CSS 侧分档);此处只夹异常上限、兜上报未就绪。
           手机 94×48(=86+8, FlClash 口径) / 平板(winW≥600dp) 96×52(11-13 验收值)。 */
        val tablet = winW >= 600f * density
        val maxPillW = (if (tablet) 96f else 94f) * density
        val maxPillH = (if (tablet) 52f else 48f) * density
        pillW = if (DockBus.pillWPx > 0) min(DockBus.pillWPx, maxPillW)
                else min(axisPitch + 8f * density, maxPillW)
        pillH = if (DockBus.pillHPx > 0) min(DockBus.pillHPx, maxPillH) else maxPillH
        pillCy = if (DockBus.pillCyPx > 0) DockBus.pillCyPx else winH - pillH
        axisFirst = if (DockBus.firstCxPx > 0) DockBus.firstCxPx else axisPitch / 2f
        if (barRectW.width() <= 4 || barRectW.height() <= 4) {
            val top = pillCy - pillH / 2f - 6f * density
            barRectW.set(0f, top, winW, top + pillH + 12f * density)
        }
    }

    model.count = DockDraw.TAB_LABELS.size
    model.dockVertical = vertical
    model.dockedStyle = dockedStyle
    model.axisFirst = axisFirst
    model.axisPitch = axisPitch
    model.pillW = pillW
    model.pillH = pillH
    model.pillCy = pillCy

    val contentH = 36f * density   // 图标22 + gap2 + 文字12(旧 contentH 同式)

    /* 贴底↔悬浮 300ms 平滑过渡(2026-11-14 平移旧路线 dockedProgress):
       DecelerateInterpolator(1.8) ≈ LinearOutSlowIn;过渡期两形态交叉淡化
       (旧路线 saveLayerAlpha 淡出同构),手势守卫归悬浮层,贴底层到位才挂。 */
    val dockP = remember { Animatable(if (dockedStyle) 1f else 0f) }
    LaunchedEffect(dockedStyle) {
        dockP.animateTo(if (dockedStyle) 1f else 0f,
            tween(300, easing = LinearOutSlowInEasing))
    }

    Box(Modifier.fillMaxSize()) {
        if (vertical) {
            RailFlat(model, railRectW, colors, iconPaths, iconP, anchor, refreshTick)
        } else {
            val p = dockP.value
            if (p > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }) {
                DockedFlat(model, barRectW, colors, iconPaths, iconP, textP,
                    contentH, anchor, refreshTick,
                    progress = p, interactive = p >= 0.99f)
            }
            if (p < 0.99f) {
                val f = 1f - p
                if (glassLive) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = f }) {
                        GlassBar(captureLayer, model, barRectW, colors, iconPaths, iconP, textP,
                            contentH, glassLevel == "standard", anchor, refreshTick)
                    }
                } else {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = f }) {
                        FloatFlat(model, barRectW, colors, iconPaths, iconP, textP,
                            contentH, anchor, refreshTick)
                    }
                }
            }
        }
    }
}

@Composable
internal fun railItemDp(): Float {
    val h = LocalConfiguration.current.screenHeightDp.toFloat()
    return max(40f, min(72f, h * 0.075f))
}

/** prism 栏体节点外扩 pad(采样 reach)——GlassBar 与 PageSnap 共用同一矩形口径。 */
internal fun barPadPx(density: Float): Float = (15.6f * density - 0.5f) * 2.0784f + 12f

/* ================= 横排 · 液态玻璃(旗舰形态) ================= */

@Composable
private fun GlassBar(
    captured: GraphicsLayer,
    model: DockModel,
    bar: RectF,
    colors: DockDraw.Colors,
    iconPaths: Array<Array<Path>>,
    iconP: Paint,
    textP: Paint,
    contentH: Float,
    standard: Boolean,
    anchor: View,
    refreshTick: Int,
) {
    val density = GlassDockEnv.density()
    val posV = model.posV; val liftV = model.liftV
    val jellyV = model.jellyV; val glowV = model.glowV; val spreadV = model.glowSpreadV
    val barLens = remember { DockDraw.LensFx(LiquidGlassShaders.REFRACTION_DISPERSION) }
    val pillLens = remember { DockDraw.LensFx(LiquidGlassShaders.REFRACTION_DISPERSION) }
    val bloomShader = remember { android.graphics.RuntimeShader(LiquidGlassShaders.BLOOM_STROKE_DUAL) }
    val bloomP = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    } }
    val shadowP = remember { DockDraw.shadowPaint(colors, density) }
    val tintP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.sfTint } }
    val vibFx = remember { DockDraw.vibrancyEffect() }
    val barGlassLayer = rememberGraphicsLayer()
    val blurBarFx = remember(density) { DockDraw.blurEffect(4f * density) }
    val blurPillFx = remember(density) { DockDraw.blurEffect(4f * density) }
    val barShape = remember(bar.height()) {
        RoundedCornerShape((bar.height() / 2f / density).dp)
    }

    /* 药丸几何(窗口本地):中心随 pos,尺寸随 lift/jelly。
       2026-11-14 平移 Antigravity 规格并按 FlClash 原版口径修正:
       - 静止零增长:JS 上报尺寸即渲染尺寸(FlClash 药丸=格距+8、下限 84dp,
         上限由 CSS/Kotlin 兜底分档承担);
       - 按压瞬间 +20dp 胀大,高度突破栏体漫出上下边缘(水滴凸透镜通透);
       - 首末格夹取反转:由「收回栏内 2dp」改为「允许漫出 ≤10dp」——
         漫出半径远小于 lensPad 采样 reach,不触发 pad NaN 坑。 */
    val g = 20f * liftV * density
    val s = jellyV
    var cx = model.axisFirst + model.axisPitch * posV
    val cy = model.pillCy
    /* 跨格表面张力展开(2026-11-14 平移):两格正中最宽 +24dp,落格阻尼收缩。
       位置驱动(与 jelly 速度形变并存),对齐 iOS 26 水滴张力语义。 */
    val crossProgress = max(0f, min(1f, 1f - 2f * abs(posV - round(posV))))
    val crossExt = 24f * density * crossProgress
    val pl0 = cx - (model.pillW + g + crossExt) / 2f * (1f + s)
    val pr0 = cx + (model.pillW + g + crossExt) / 2f * (1f + s)
    val pt0 = cy - (model.pillH + g) / 2f * (1f - s * 1.4f)
    val pb0 = cy + (model.pillH + g) / 2f * (1f - s * 1.4f)
    val over = 10f * density
    val pl = max(pl0, bar.left - over)
    val pr = min(pr0, bar.right + over)
    val pw = pr - pl
    val ph = pb0 - pt0
    cx = (pl + pr) / 2f
    val pillR = min(pw / 2f, ph / 2f)
    /* ❗无 pad(2026-11-12):旧位图路线的 pad 是采样余量;RenderEffect 路线
       节点即药丸矩形——带 pad 的话 lens shader 对形状外像素输出 NaN(透明),
       按压时 pad 环露出硬边矩形洞(录屏 t2 实证)。 */
    val nodeL = pl; val nodeT = pt0
    val nodeW = pw; val nodeH = ph
    val mag = 1f + 0.2f * liftV

    Box(Modifier.fillMaxSize()) {
        /* ---- 投影(栏体;prism bar 无投影?有 dropShadow——保留原样) ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { it.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, bar.height() / 2f,
                bar.height() / 2f, shadowP) }
        })
        /* ---- 栏体玻璃 = prism LiquidGlassStyle.pill 单 pass 全光学:
             uShape.z bevel=20(库默认)、uOptics.x refraction=60(库默认,栏缘
             液体弯折 60dp 深度)、tint/contrast/haze/sat 按 pill 预设;
             暗线(uEdge.x=hairline)与 rim(uLight.x)由 shader 画;
             触摸光晕在 shader 内(uGlow/uGlowSpread)。 ---- */
        val barPad = barPadPx(density).toInt().coerceAtLeast(16)
        val barFx = remember { DockDraw.LensFx(PRISM_GLASS) }
        val snapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        val fillP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.barFill } }
        Box(Modifier
            .sizePx(bar.width() + 2f * barPad, bar.height() + 2f * barPad)
            .offsetPx(bar.left - barPad, bar.top - barPad)
            .graphicsLayer {
                val d = density
                /* 栏体按压胀大(KernelSU FloatingBottomBar layerBlock 口径,2026-11-14 平移):
                   只缩放绘制,不改布局与守卫命中区;transformOrigin 默认 = 节点中心
                   = bar 中心(pad 对称外扩),与旧路线 canvas.scale(dockS, 中心) 同构。 */
                val dockS = 1f + (16f * density / max(1f, bar.width())) * liftV
                scaleX = dockS
                scaleY = dockS
                DockDraw.setPrismUniforms(
                    sh = barFx.shader,
                    nodeOriginX = bar.left - barPad, nodeOriginY = bar.top - barPad,
                    glassTLX = bar.left, glassTLY = bar.top,
                    nodeW = bar.width() + 2f * barPad, nodeH = bar.height() + 2f * barPad,
                    dp = d,
                    shapeW = bar.width() / d, shapeH = bar.height() / d,
                    bevel = 20f, frost = 0f,
                    corner = bar.height() / 2f / d,
                    refraction = 60f, saturation = if (colors.dark) 1.925f else 2.1f,
                    brightness = 1f,
                    tint = colors.pillTint,
                    rim = if (colors.dark) 0.35f else 0f,
                    rimWidth = 1.3f, endShade = 0f,
                    glowCx = (model.glowX - bar.left) / d,
                    glowCy = (model.glowY - bar.top) / d,
                    glowStrength = 0.06f * glowV * glowV,
                    contrast = if (colors.dark) -0.4f else 0.4f,
                    glowReach = 28f * (1f + 3f * spreadV),
                    glowWash = 0.7f,
                    lineAlpha = if (colors.dark) 0f else 0.35f,
                    partDp = 0f, rimFrost = 0f,
                    rimTail = if (colors.dark) 0.17f else 0f,
                    rimFocus = 0f, rimAdded = if (colors.dark) 0.38f else 0f,
                    rimSide = 0f,
                    haze = 0.72f, hazeBlur = 15.6f)
                renderEffect = barFx.effect(floatArrayOf(
                    bar.width(), bar.height(), glowV, model.glowX, model.glowY))
            }
            .drawBehind {
                clipRect(0f, 0f, size.width, size.height) {
                    if (DockBus.CAPTURE_PAGES) {
                        translate(-(bar.left - barPad), -(bar.top - barPad)) {
                            drawLayer(captured)
                        }
                    } else {
                        /* HONOR(MagicOS):内容源 = PageSnap 快照(窗口坐标)对齐节点。
                           读 version 订阅绘制:快照更新 → 玻璃层重绘(draw 阶段订阅,
                           不触发重组)。快照未就绪首帧用不透明 barFill 兜底。 */
                        val snap = PageSnap.latest()
                        if (snap != null) {
                            val sh = android.graphics.BitmapShader(
                                snap, android.graphics.Shader.TileMode.CLAMP,
                                android.graphics.Shader.TileMode.CLAMP)
                            val m = android.graphics.Matrix().apply {
                                postTranslate(PageSnap.snapL - (bar.left - barPad),
                                    PageSnap.snapT - (bar.top - barPad))
                            }
                            sh.setLocalMatrix(m)
                            snapPaint.shader = sh
                            PageSnap.version
                            ntc { c -> c.drawRect(0f, 0f, size.width, size.height, snapPaint) }
                        } else {
                            ntc { c -> c.drawRect(0f, 0f, size.width, size.height, fillP) }
                        }
                    }
                }
            }
        ) { }
        /* ---- 托盘(静止选中,prism _Lens):HCT secondary tone90/4 @85%,
             α×(1−lift);抬起后由玻璃镜接管 ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                val trayA = 0.85f * (1f - liftV)
                if (trayA > 0.01f) {
                    val trayCx = model.axisFirst + model.axisPitch * model.selectedIndex
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = colors.tray
                        alpha = (255f * trayA).toInt()
                    }
                    c.drawRoundRect(trayCx - model.pillW / 2f, cy - model.pillH / 2f,
                        trayCx + model.pillW / 2f, cy + model.pillH / 2f,
                        model.pillH / 2f, model.pillH / 2f, tp)
                }
            }
        })
        /* ---- 灰态图标 = prism items(outside):药丸覆盖的格由镜内重绘。
             覆盖判定 0.75 格距(2026-11-14 平移旧 -2 口径):跨格时两格都让位,
             镜内层将两格均画高亮蓝(双蓝) ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                val actIdx = model.selectedIndex
                for (i in 0 until model.count) {
                    val cellCx = model.axisFirst + model.axisPitch * i
                    if (abs(cellCx - cx) < model.axisPitch * 0.75f) continue
                    DockDraw.drawTab(c, i, actIdx, false, cellCx, cy, contentH, density,
                        colors, iconPaths, iconP, textP)
                }
            }
        })
        /* ---- 抬起玻璃镜 = prism _GlassLens(lens 预设):
             极致档 refraction -4.3(外凸)+ dispersion 6.5 / 标准档 -2.5 + CA 0
             + 投影(0,8,5) + 暗色 brightness 1.33 + hairline 0.42;
             节点外扩 pad = 采样 reach(按最大折射档 4.3dp 预留,两档共用) ---- */
        val lensPad = ((4.3f * density + 2.5f * density * 2.0784f) + 12f)
            .toInt().coerceAtLeast(16)
        if (liftV > 0.01f) Box(Modifier
            .sizePx(nodeW + 2f * lensPad, nodeH + 2f * lensPad)
            .offsetPx(nodeL - lensPad, nodeT - lensPad)
        ) {
            /* 投影(prism _lensShadows:offset(0,8) blur5 黑 5.5%,暗 3×) */
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = liftV
            }.drawBehind {
                ntc { c ->
                    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        maskFilter = android.graphics.BlurMaskFilter(
                            5f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
                        color = if (colors.dark) android.graphics.Color.argb((255f * 0.165f * liftV).toInt(), 0, 0, 0)
                                else android.graphics.Color.argb((255f * 0.055f * liftV).toInt(), 0, 0, 0)
                    }
                    c.drawRoundRect(lensPad.toFloat(), lensPad + 8f * density,
                        lensPad + nodeW, lensPad + nodeH + 8f * density, pillR, pillR, sp)
                }
            })
            /* mask:胶囊(裁镜折射输出) */
            Box(Modifier.fillMaxSize().graphicsLayer {
                shape = RoundedCornerShape((pillR / density).dp); clip = true
            }) {
                val lensFx = remember { DockDraw.LensFx(PRISM_GLASS) }
                /* 档位差异化(2026-11-14 平移):极致档照抄 prism_ui 原版光学
                   (refraction −4.3 / bevel 5.5 / CA 6.5dp,FlClash 真机取证值);
                   标准档 = 弱化凸透镜(−2.5 / bevel 4),CA 严格归 0 防字迹彩斑。 */
                val refA = if (standard) 2.5f else 4.3f
                val bev = if (standard) 4f else 5.5f
                val caDp = if (standard) 0f else 6.5f
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val d = density
                    DockDraw.setPrismUniforms(
                        sh = lensFx.shader,
                        nodeOriginX = nodeL - lensPad, nodeOriginY = nodeT - lensPad,
                        glassTLX = nodeL, glassTLY = nodeT,
                        nodeW = nodeW + 2f * lensPad, nodeH = nodeH + 2f * lensPad,
                        dp = d,
                        shapeW = nodeW / d, shapeH = nodeH / d,
                        bevel = bev, frost = 0f,
                        corner = pillR / d,
                        refraction = -refA * liftV, saturation = 1f,
                        brightness = 1f + (if (colors.dark) 0.33f else 0f) * liftV,
                        tint = 0,
                        rim = 0.6f * liftV, rimWidth = 1.1f, endShade = 0.045f * liftV,
                        glowCx = 0f, glowCy = 0f, glowStrength = 0f, contrast = 0f,
                        glowReach = 0f, glowWash = 0f,
                        lineAlpha = 0.42f * liftV, partDp = caDp * liftV,
                        rimFrost = 2.5f * liftV,
                        rimTail = 0f, rimFocus = 1f, rimAdded = 0f, rimSide = 0f,
                        haze = 0f, hazeBlur = 0f)
                    renderEffect = lensFx.effect(floatArrayOf(
                        nodeW, nodeH, nodeL, nodeT, liftV, cx, cy))
                }) {
                    Box(Modifier.fillMaxSize().drawBehind {
                        clipRect(0f, 0f, size.width, size.height) {
                            if (DockBus.CAPTURE_PAGES) {
                                translate(-(nodeL - lensPad), -(nodeT - lensPad)) {
                                    drawLayer(captured)
                                }
                            } else {
                                /* HONOR(MagicOS):镜内容源 = 同一张 PageSnap 快照
                                   (药丸节点 ⊆ bar±barPad,快照覆盖足够)。 */
                                val snap = PageSnap.latest()
                                if (snap != null) {
                                    val sh = android.graphics.BitmapShader(
                                        snap, android.graphics.Shader.TileMode.CLAMP,
                                        android.graphics.Shader.TileMode.CLAMP)
                                    val m = android.graphics.Matrix().apply {
                                        postTranslate(PageSnap.snapL - (nodeL - lensPad),
                                            PageSnap.snapT - (nodeT - lensPad))
                                    }
                                    sh.setLocalMatrix(m)
                                    snapPaint.shader = sh
                                    PageSnap.version
                                    ntc { c -> c.drawRect(0f, 0f, size.width, size.height, snapPaint) }
                                }
                            }
                        }
                    })
                }
            }
        }
        /* ---- 镜内图标(prism items(inside)):画在玻璃镜之上,不折射。
             覆盖格全部高亮蓝(跨格双蓝,2026-11-14 平移——对齐官方跨格效果,
             原恒单格 selectedIndex)+ 选中微放大 1.08/1.05 ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                for (i in 0 until model.count) {
                    val cellCx = model.axisFirst + model.axisPitch * i
                    if (abs(cellCx - cx) >= model.axisPitch * 0.75f) continue
                    DockDraw.drawTab(c, i, i, false, cellCx, cy, contentH, density,
                        colors, iconPaths, iconP, textP,
                        magIcon = 1.08f, magText = 1.05f)
                }
            }
        })

        /* ---- 触摸守卫 ---- */
        TouchGuard(model, bar, anchor)
    }
}

