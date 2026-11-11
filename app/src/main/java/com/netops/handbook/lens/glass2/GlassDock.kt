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
        /* ---- 栏体玻璃:帧(栏矩形定位) > mask(胶囊) > lens > blur > vibrancy > 回放 ----
           ❗玻璃链必须挂在「栏矩形大小」的节点上(2026-11-12 首验教训:挂全窗节点
           = 磨砂画到窗口原点被顶栏弹窗盖死)。回放 translate 负偏移 = 采样
           「栏后物理内容」;clipRect 把 blur 的 FBO 开销夹在栏大小。 */
        Box(Modifier
            .sizePx(bar.width(), bar.height())
            .offsetPx(bar.left, bar.top)
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                shape = barShape; clip = true
            }) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    barLens.shader.setFloatUniform("size", bar.width(), bar.height())
                    barLens.shader.setFloatUniform("offset", 0f, 0f)
                    barLens.shader.setFloatUniform("cornerRadii", bar.height() / 2f, bar.height() / 2f,
                        bar.height() / 2f, bar.height() / 2f)
                    barLens.shader.setFloatUniform("refractionHeight", 24f * density)
                    barLens.shader.setFloatUniform("refractionAmount", -24f * density)
                    barLens.shader.setFloatUniform("depthEffect", 0f)
                    renderEffect = barLens.effect(
                        floatArrayOf(bar.width(), bar.height(), 24f * density))
                }) {
                    Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = blurBarFx }) {
                        Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = vibFx }) {
                            Box(Modifier.fillMaxSize().drawBehind {
                                clipRect(0f, 0f, size.width, size.height) {
                                    /* layer 原点=窗口(0,0):负平移把「栏后窗口像素」
                                       对齐到节点局部 (0,0) —— 1:1 采样栏后内容 */
                                    translate(-bar.left, -bar.top) { drawLayer(captured) }
                                }
                            })
                        }
                    }
                }
            }
        }
        /* ---- surface tint + BloomStroke(效果链之上,z 序=上游) ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc {
                it.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom,
                    bar.height() / 2f, bar.height() / 2f, tintP)
                BloomStrokeFx.draw(bloomShader, bloomP, it, bar.left, bar.top, bar.right, bar.bottom,
                    bar.height() / 2f, 0.75f, 0f, 0f, -1f, density)
            }
        })
        /* ---- 灰态图标文字(药丸盖住的格由镜内重绘,跳过防双影) ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                val lensing = model.pressed || liftV > 0.005f
                val active = if (lensing) posV else model.selectedIndex.toFloat()
                val actIdx = active.toInt().coerceIn(0, model.count - 1)
                for (i in 0 until model.count) {
                    val cellCx = model.axisFirst + model.axisPitch * i
                    if (abs(cellCx - cx) < model.axisPitch * 0.5f + 2f * density) continue
                    DockDraw.drawTab(c, i, actIdx, false, cellCx, cy, contentH, density,
                        colors, iconPaths, iconP, textP)
                }
            }
        })
        /* ---- 药丸 ---- */
        Box(Modifier
            .sizePx(nodeW, nodeH)
            .offsetPx(nodeL, nodeT)
        ) {
            /* mask:胶囊圆角,裁折射输出。mask 必须是效果节点的**祖先**
               (后裁)——mask 在效果链内部会让折射边缘采到透明(2026-11-05
               「内外两层胶囊」的根因,探针验证过的层级约束)。 */
            Box(Modifier.fillMaxSize().graphicsLayer {
                shape = RoundedCornerShape((pillR / density).dp); clip = true
            }) {
                /* lens:磨砂底+清晰顶+白底+镜像 一起折射(shader 内容=节点输出) */
                Box(Modifier.fillMaxSize().graphicsLayer {
                    pillLens.shader.setFloatUniform("size", pw, ph)
                    /* ❗offset = halfSize - 药丸中心(节点坐标):shader 里
                       centeredCoord = coord + offset - halfSize = coord - 中心。
                       2026-11-12 首验:符号写反 = 采样区整体偏移 2×pad,
                       药丸内容整体错位、镜内图标全被 NaN 裁掉。 */
                    pillLens.shader.setFloatUniform("offset",
                        pw / 2f - (cx - nodeL), ph / 2f - (cy - nodeT))
                    pillLens.shader.setFloatUniform("cornerRadii", pillR, pillR, pillR, pillR)
                    pillLens.shader.setFloatUniform("refractionHeight", 2f * density * liftV)
                    pillLens.shader.setFloatUniform("refractionAmount", -1.5f * density * liftV)
                    pillLens.shader.setFloatUniform("depthEffect", 0f)
                    pillLens.shader.setFloatUniform("chromaticAberration", if (standard) 0f else 0.5f)
                    renderEffect = pillLens.effect(floatArrayOf(
                        pw, ph, cx - nodeL, cy - nodeT, pillR, 2f * density * liftV))
                }) {
                    /* 磨砂底(静止档) */
                    Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = blurPillFx }) {
                        Box(Modifier.fillMaxSize().drawBehind {
                            clipRect(0f, 0f, size.width, size.height) {
                                translate(-nodeL, -nodeT) { drawLayer(captured) }
                            }
                        })
                    }
                    /* 清晰顶(按住档):α=lift 淡入 */
                    Box(Modifier.fillMaxSize().drawBehind {
                        if (liftV <= 0.005f) return@drawBehind
                        val cnv = drawContext.canvas.nativeCanvas
                        val save = cnv.saveLayerAlpha(0f, 0f, size.width, size.height,
                            (255f * liftV).toInt(), Canvas.ALL_SAVE_FLAG)
                        clipRect(0f, 0f, size.width, size.height) {
                            translate(-nodeL, -nodeT) { drawLayer(captured) }
                        }
                        cnv.restoreToCount(save)
                    })
                    /* 白底:静止 α150、按住 0(2026-11-10 定案) */
                    Box(Modifier.fillMaxSize().drawBehind {
                        val a = (150f * (1f - min(1f, liftV))).toInt()
                        if (a > 2) {
                            val p = Paint().apply { color = colors.baseWhite; alpha = a }
                            drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, p)
                        }
                    })
                /* tab 镜像:选中格(+滑行跨越格)按 mag 放大重绘 */
                Box(Modifier.fillMaxSize().drawBehind {
                    ntc { c ->
                            val lensing = model.pressed || liftV > 0.005f
                            val active = if (lensing) posV else model.selectedIndex.toFloat()
                            val blue = active.toInt().coerceIn(0, model.count - 1)
                            var other = -1
                            if (active - blue != 0f) {
                                other = floor(active).toInt().coerceIn(0, model.count - 1)
                                if (other == blue) other = ceil(active).toInt().coerceIn(0, model.count - 1)
                                if (other == blue) other = -1
                            }
                            val pcxN = cx - nodeL; val pcyN = cy - nodeT
                        /* ❗镜像画在**节点坐标**:窗口坐标 - 节点原点(=旧 -tabOff,
                           2026-11-12 首验:漏乘偏移=镜像被 mask 裁掉,药丸空芯)。 */
                        c.translate(-nodeL, -nodeT)
                        c.save()
                        c.translate(pcxN, pcyN)
                        c.scale(mag, mag)
                        c.translate(-pcxN, -pcyN)
                        val cxB = model.axisFirst + model.axisPitch * blue
                        DockDraw.drawTab(c, blue, blue, false, cxB, cy, contentH, density,
                            colors, iconPaths, iconP, textP)
                        if (other >= 0 && other != blue) {
                            val cxO = model.axisFirst + model.axisPitch * other
                            DockDraw.drawTab(c, other, -2, true, cxO, cy, contentH, density,
                                colors, iconPaths, iconP, textP)
                        }
                        c.restore()
                        }
                    })
                }
            }
            /* ---- 药丸 surface:按压面 + 触摸光晕(mask 之外,z 序=上游) ---- */
            Box(Modifier.fillMaxSize().drawBehind {
                ntc { c ->
                    val pressA = (255f * 0.03f * liftV).toInt()
                    if (pressA > 2) {
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(pressA, 0, 0, 0) }
                        c.drawRoundRect(pl - nodeL, pt0 - nodeT, pr - nodeL, pb0 - nodeT, pillR, pillR, p)
                    }
                    if (glowV > 0.01f) {
                        val gr = max(1f, 28f * density * (1f + 3f * spreadV))
                        val gs = 0.06f * glowV * glowV
                        val ca = (255f * gs * 0.8f).toInt().coerceAtMost(255)
                        val ea = (255f * gs * 0.56f).toInt()
                        if (ca > 2) {
                            val gx = model.glowX - nodeL; val gy = model.glowY - nodeT
                            val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                shader = android.graphics.RadialGradient(gx, gy, gr,
                                    intArrayOf(android.graphics.Color.argb(ca, 255, 255, 255),
                                        android.graphics.Color.argb(ea, 255, 255, 255)),
                                    floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
                                xfermode = android.graphics.PorterDuffXfermode(
                                    android.graphics.PorterDuff.Mode.ADD)
                            }
                            c.drawRoundRect(pl - nodeL, pt0 - nodeT, pr - nodeL, pb0 - nodeT,
                                pillR, pillR, gp)
                        }
                    }
                }
            })
        }
        /* ---- 触摸守卫 ---- */
        TouchGuard(model, bar, anchor)
    }
}

