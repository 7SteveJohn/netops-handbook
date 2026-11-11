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
fun GlassDockHost(anchor: View, modifier: Modifier = Modifier) {
    val captureLayer = rememberGraphicsLayer()
    var winSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { anchor },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { winSize = it }
                .drawWithContent {
                    /* 捕获:本节点内容(整棵 View 树)进 layer。dock 在本子树外,
                       天然无自反馈(探针实证:record 范围与玻璃必须互斥)。 */
                    captureLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(captureLayer)
                }
        )
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

    /* 帧循环:每帧推进弹簧,值写入 State(静止不变 → 零重绘,等价旧自眠)。 */
    LaunchedEffect(model) {
        while (true) {
            withFrameNanos { }
            model.tick()
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
        pillW = if (DockBus.pillWPx > 0) DockBus.pillWPx else axisPitch + 8f * density
        pillH = if (DockBus.pillHPx > 0) DockBus.pillHPx else 56f * density
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

    Box(Modifier.fillMaxSize()) {
        when {
            vertical -> RailFlat(model, railRectW, colors, iconPaths, iconP, anchor, refreshTick)
            dockedStyle -> DockedFlat(model, barRectW, colors, iconPaths, iconP, textP,
                contentH, anchor, refreshTick)
            glassLive -> GlassBar(captureLayer, model, barRectW, colors, iconPaths, iconP, textP,
                contentH, glassLevel == "standard", anchor, refreshTick)
            else -> FloatFlat(model, barRectW, colors, iconPaths, iconP, textP,
                contentH, anchor, refreshTick)
        }
    }
}

@Composable
internal fun railItemDp(): Float {
    val h = LocalConfiguration.current.screenHeightDp.toFloat()
    return max(40f, min(72f, h * 0.075f))
}

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

    /* 药丸几何(窗口本地):中心随 pos,尺寸随 lift/jelly,首末格收回栏内
       (2026-11-11「拖到两端割裂」的源码级修复,原样移植)。 */
    val g = (16f + (24f - 16f) * liftV) * density
    val s = jellyV
    var cx = model.axisFirst + model.axisPitch * posV
    val cy = model.pillCy
    val pl0 = cx - (model.pillW + g) / 2f * (1f + s)
    val pr0 = cx + (model.pillW + g) / 2f * (1f + s)
    val pt0 = cy - (model.pillH + g) / 2f * (1f - s * 1.4f)
    val pb0 = cy + (model.pillH + g) / 2f * (1f - s * 1.4f)
    val inset = 2f * density
    val pl = max(pl0, bar.left + inset)
    val pr = min(pr0, bar.right - inset)
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
        /* ---- 投影 ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { it.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, bar.height() / 2f,
                bar.height() / 2f, shadowP) }
        })
        /* ---- 栏体玻璃(prism LiquidGlassStyle.pill 雾玻璃配方):
             雾合成 = 0.72×blur15.6 + 0.28 直采;vibrancy 2.1;tint 57%白(暗 48%);
             pill 的 blur=0(不均匀模糊,靠 haze)。雾半径 15.6/2=7.8σ 近似。 ---- */
        Box(Modifier
            .sizePx(bar.width(), bar.height())
            .offsetPx(bar.left, bar.top)
            .drawWithContent {
                /* 栏体玻璃成品(雾+tint+效果)record 成独立层:抬起玻璃镜回放它
                   = 「镜弯折的是栏体」(prism: it bends only the bar) */
                barGlassLayer.record { this@drawWithContent.drawContent() }
                drawContent()
            }
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                shape = barShape; clip = true
            }) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = DockDraw.blurEffect(7.8f * density)
                    alpha = 0.72f
                }) {
                    Box(Modifier.fillMaxSize().drawBehind { drawLayer(captured) })
                }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.28f }) {
                    Box(Modifier.fillMaxSize().drawBehind { drawLayer(captured) })
                }
                Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = vibFx }) {
                    Box(Modifier.fillMaxSize().drawBehind {
                        ntc { it.drawPaint(tintP) }
                    })
                }
            }
        }
        /* ---- hairline(75% 高度发丝亮线,prism pill 亮色 0.35;暗色无)+ BloomStroke ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc {
                if (!colors.dark) {
                    val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb((255f * 0.35f).toInt(), 255, 255, 255)
                        strokeWidth = 1.2f * density
                        style = Paint.Style.STROKE
                    }
                    val hy = bar.top + bar.height() * 0.25f
                    it.drawLine(bar.left + bar.height() * 0.35f, hy,
                        bar.right - bar.height() * 0.35f, hy, hl)
                }
                BloomStrokeFx.draw(bloomShader, bloomP, it, bar.left, bar.top, bar.right, bar.bottom,
                    bar.height() / 2f, 0.75f, 0f, 0f, -1f, density)
            }
        })
        /* ---- 托盘(静止选中,prism _Lens):HCT secondary tone4/90 @85% 近似,
             随 lift 淡出(α×(1−lift));抬起后由玻璃镜接管 ---- */
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
        /* ---- 灰态图标 = prism items(outside):药丸盖住的格由镜内重绘 ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                val actIdx = model.selectedIndex
                for (i in 0 until model.count) {
                    val cellCx = model.axisFirst + model.axisPitch * i
                    if (abs(cellCx - cx) < model.axisPitch * 0.5f + 2f * density) continue
                    DockDraw.drawTab(c, i, actIdx, false, cellCx, cy, contentH, density,
                        colors, iconPaths, iconP, textP)
                }
            }
        })
        /* ---- 抬起玻璃镜(prism _GlassLens,lift>0 才在):
             lens 预设:refraction -4.3(负=向外凸,shader 取正)+ 投影 +
             暗色 brightness 1.33;节点上移 8dp×lift(投影空间) ---- */
        if (liftV > 0.01f) Box(Modifier
            .sizePx(nodeW, nodeH)
            .offsetPx(nodeL, nodeT - 8f * density * liftV)
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
                    c.drawRoundRect(0f, 8f * density, nodeW, nodeH + 8f * density, pillR, pillR, sp)
                }
            })
            /* mask:胶囊(裁镜折射输出) */
            Box(Modifier.fillMaxSize().graphicsLayer {
                shape = RoundedCornerShape((pillR / density).dp); clip = true
            }) {
                /* lens:refraction 4.3 外凸(prism lens refraction=-4.3,库内取负入 shader);
                   暗色 brightness 1.33 v1 以白 overlay 近似 */
                Box(Modifier.fillMaxSize().graphicsLayer {
                    pillLens.shader.setFloatUniform("size", pw, ph)
                    pillLens.shader.setFloatUniform("offset",
                        pw / 2f - (cx - nodeL), ph / 2f - (cy - nodeT))
                    pillLens.shader.setFloatUniform("cornerRadii", pillR, pillR, pillR, pillR)
                    pillLens.shader.setFloatUniform("refractionHeight", 4.3f * density * liftV)
                    pillLens.shader.setFloatUniform("refractionAmount", 4.3f * density * liftV)
                    pillLens.shader.setFloatUniform("depthEffect", 0f)
                    pillLens.shader.setFloatUniform("chromaticAberration", if (standard) 0f else 0.5f)
                    renderEffect = pillLens.effect(floatArrayOf(
                        pw, ph, cx - nodeL, cy - nodeT, pillR, 4.3f * density * liftV))
                }) {
                    /* ❗镜内容 = 栏体雾玻璃成品(barGlassLayer),不是 captured 壁纸:
                       prism _GlassLens 叠在 _DockSurface 上——「镜弯折的是栏体」。
                       2026-11-12 手机首验:回放壁纸原文 = 镜区发暗、与栏体断裂。 */
                    Box(Modifier.fillMaxSize().drawBehind {
                        clipRect(0f, 0f, size.width, size.height) {
                            translate(-nodeL, -nodeT) { drawLayer(barGlassLayer) }
                        }
                    })
                }
                /* 暗色提亮(prism lens brightness 1.33) */
                if (colors.dark) Box(Modifier.fillMaxSize().drawBehind {
                    ntc { c ->
                        val p = Paint().apply {
                            color = android.graphics.Color.argb((255f * 0.12f * liftV).toInt(), 255, 255, 255)
                        }
                        c.drawRect(0f, 0f, size.width, size.height, p)
                    }
                })
            }
        }
        /* ---- 镜内图标(prism items(inside)+ _LensContent):画在玻璃镜之上,
             不折射(prism 的 warp 仅 ultra 开,内容自玻璃);选中格=蓝态 ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                val actIdx = model.selectedIndex
                for (i in 0 until model.count) {
                    val cellCx = model.axisFirst + model.axisPitch * i
                    if (abs(cellCx - cx) >= model.axisPitch * 0.5f + 2f * density) continue
                    DockDraw.drawTab(c, i, actIdx, false, cellCx, cy, contentH, density,
                        colors, iconPaths, iconP, textP)
                }
            }
        })
        /* ---- 触摸光晕(prism _touchGlow:指尖发光,洗过整条栏) ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                if (glowV > 0.01f) {
                    val gr = max(1f, 28f * density * (1f + 3f * spreadV))
                    val gs = 0.06f * glowV * glowV
                    val ca = (255f * gs * 0.8f).toInt().coerceAtMost(255)
                    val ea = (255f * gs * 0.56f).toInt()
                    if (ca > 2) {
                        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = android.graphics.RadialGradient(model.glowX, model.glowY, gr,
                                intArrayOf(android.graphics.Color.argb(ca, 255, 255, 255),
                                    android.graphics.Color.argb(ea, 255, 255, 255)),
                                floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
                            xfermode = android.graphics.PorterDuffXfermode(
                                android.graphics.PorterDuff.Mode.ADD)
                        }
                        c.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom,
                            bar.height() / 2f, bar.height() / 2f, gp)
                    }
                }
            }
        })

        /* ---- 触摸守卫 ---- */
        TouchGuard(model, bar, anchor)
    }
}

