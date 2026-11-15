package com.netops.handbook.lens.glass2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.netops.handbook.lens.glass.BloomStrokeFx
import com.netops.handbook.lens.glass.LiquidGlassShaders
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Compose 液态玻璃 dock(2026-11-12 P2,FlClash/KernelSU 同代架构;
 * 2026-11-14 View 层退役——record 源=Compose 壁纸 Image,页面 sibling 直绘)。
 *
 * 结构 = Kyant0 backdrop 同构;2026-11-14 本机(MagicOS)定案:record 全灭主
 * 画布 → 内容源弃 record,改壁纸 BitmapShader 直喂(见 GlassDockHost 长注);
 * 玻璃节点 graphicsLayer.renderEffect 挂 blur→vibrancy→lens 链不变——
 * 像素全在 GPU 管线内原子提交,无跨线程位图、无 PixelCopy、无独立窗口。
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
    modifier: Modifier = Modifier,
) {
    /* dock 独立窗(2026-11-14 双机实证终态)：dock 进专属 PopupWindow(自带
       window/surface/composer=完全隔离)——同窗(哪怕同树)有 dock 子树时
       普通内容不被 MagicOS 合成器绘制(手机+平板全配置探针 t1-t9)；旧
       LensBarView 的 PopupWindow 拓扑在手机上跑过数月=双重实证。
       坐标系:GlassDock 内部全用窗口本地坐标,GraphicsLayer 平移 -origin
       把窗口系绘制折进弹窗(弹窗原点=DockBus.dockOrigin,几何变化时
       DockPopupHost dismiss+重建)。 */
    val origin = DockBus.dockOrigin
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = -origin.x.toFloat()
                translationY = -origin.y.toFloat()
            }
    ) {
        GlassDock()
    }
}

/** 壁纸内容源 BitmapShader：wallBitmap 按全屏 Crop 口径铺满 (winW×winH)，
 *  再平移到节点本地坐标（节点原点=(nodeL,nodeT)）——即「bar 背后那块壁纸」。 */
internal fun wallShader(
    bmp: android.graphics.Bitmap,
    winW: Float, winH: Float,
    nodeL: Float, nodeT: Float,
): android.graphics.BitmapShader {
    val sh = android.graphics.BitmapShader(
        bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    val scale = maxOf(winW / bmp.width, winH / bmp.height)
    val m = android.graphics.Matrix()
    m.postScale(scale, scale)
    m.postTranslate((winW - bmp.width * scale) / 2f - nodeL,
        (winH - bmp.height * scale) / 2f - nodeT)
    sh.setLocalMatrix(m)
    return sh
}

@Composable
private fun GlassDock() {
    val winSize = IntSize(DockBus.winWpx, DockBus.winHpx)
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
    val snapView = DockBus.contentView
    LaunchedEffect(model, snapView) {
        while (true) {
            withFrameNanos { }
            model.tick()
            /* ❗内容源=PageSnap 软录内容树(2026-11-15 终态)：dock 独立弹窗后玻璃
               物理上看不到页面——液态玻璃(页面内容在栏体里流动折射)必须录**内容
               树**(壁纸+页面)。PixelCopy 路废弃(回调时序不可靠+alpha 不可控)，
               照抄旧 dock 终态:View.draw() 软件画布同步录(无回调丢失、alpha
               完整、无自反馈——dock 在独立弹窗=另一棵 View 树)。25fps 节流在
               PageSnap 内。 */
            if (snapView != null && !DockBus.docked && DockBus.nativeDock
                && DockBus.glassLevel != "none") {
                val bar = DockBus.barRect
                if (bar.width() > 4 && bar.height() > 4) {
                    val pad = (40f * GlassDockEnv.density() + 8f).toInt()
                    PageSnap.request(snapView, RectF(
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
    /* AGSL 恢复(2026-11-14 终态)：dock 已进独立弹窗(自有 surface),AGSL 毒杀
       只伤同窗兄弟——弹窗内无页面,全设备恢复完整玻璃(v1-v16 dock 内部
       AGSL+自身图标共存的旧证)。 */
    val agslOk = true
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
            /* catalog 全宽口径(2026-11-15 用户示图拍板,Java computeDockGeometry
               端计算推送):tabWidth=(barW−8dp)/tabs、药丸=格距+16dp cap96。
               ❗tab 行/药丸几何真源=DockBus 推送值(pitch/pillW/pillH/firstCx);
               推送缺失时兜底=旧紧凑 KSU 宽。tab 行中心对齐栏体(safeBottom
               锚底),tab 中心=首格中心+格距递推。 */
            if (barRectW.width() <= 4 || barRectW.height() <= 4) {
                val barW = DockDraw.TAB_LABELS.size * 64f * density + 8f * density
                barRectW.set((winW - barW) / 2f, winH - 64f * density - 8f * density,
                    (winW - barW) / 2f + barW, winH - 8f * density)
            }
            axisPitch = if (DockBus.pitchPx > 4f) DockBus.pitchPx else 64f * density
            axisFirst = if (DockBus.firstCxPx > 0f) DockBus.firstCxPx
            else barRectW.centerX() -
                axisPitch * (DockDraw.TAB_LABELS.size - 1) / 2f
            pillW = if (DockBus.pillWPx > 4f) DockBus.pillWPx else 64f * density
            pillH = if (DockBus.pillHPx > 4f) DockBus.pillHPx else 56f * density
            pillCy = barRectW.centerY()
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

    /* 弹窗几何上报(2026-11-14 dock 独立窗)：GlassDock 自算的最终矩形
       (竖排=轨条/横排=栏带)推给 DockPopupHost——rect 变化(旋转/样式切)
       时弹窗 dismiss+重建。 */
    androidx.compose.runtime.SideEffect {
        DockBus.pushDockRect(if (vertical) railRectW else barRectW,
            barPadPx(density))
    }

    /* 贴底↔悬浮 300ms 平滑过渡(2026-11-14 平移旧路线 dockedProgress):
       DecelerateInterpolator(1.8) ≈ LinearOutSlowIn;过渡期两形态交叉淡化
       (旧路线 saveLayerAlpha 淡出同构),手势守卫归悬浮层,贴底层到位才挂。 */
    val dockP = remember { Animatable(if (dockedStyle) 1f else 0f) }
    LaunchedEffect(dockedStyle) {
        dockP.animateTo(if (dockedStyle) 1f else 0f,
            tween(300, easing = LinearOutSlowInEasing))
    }

    /* 抽屉/设置在场 → 整个 dock(含守卫带)让位(旧 lensBar.setOverlaid 语义) */
    if (!DockBus.overlaid) Box(Modifier.fillMaxSize()) {
        if (vertical) {
            RailFlat(model, railRectW, colors, iconPaths, iconP, refreshTick)
        } else {
            val p = dockP.value
            if (p > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }) {
                DockedFlat(model, barRectW, colors, iconPaths, iconP, textP,
                    contentH, refreshTick,
                    progress = p, interactive = p >= 0.99f)
            }
            if (p < 0.99f) {
                val f = 1f - p
                if (glassLive) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = f }) {
                        GlassBar(model, barRectW, colors, iconPaths, iconP, textP,
                            contentH, glassLevel == "standard", winSize, agslOk, refreshTick)
                    }
                } else {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = f }) {
                        FloatFlat(model, barRectW, colors, iconPaths, iconP, textP,
                            contentH, refreshTick)
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
    model: DockModel,
    bar: RectF,
    colors: DockDraw.Colors,
    iconPaths: Array<Array<Path>>,
    iconP: Paint,
    textP: Paint,
    contentH: Float,
    standard: Boolean,
    winSize: IntSize,
    agslOk: Boolean,
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

    /* 药丸几何(2026-11-15 终版=KSU/catalog 真源,FloatingBottomBar.kt
       .height(56).width(tabWidth)+layerBlock scale 同构):节点=固定
       pillW×pillH 胶囊,中心随 pos 纯滑动(无跨格张力展开);按压放大=
       graphicsLayer 同比 scale lerp(1,78/56,p)+速度挤压(scaleX 横拉/
       scaleY 纵压),比例锁死=形状永不随按压突变球形(KSU 262/456 行;
       旧「各向 +20dp 胀大」把 64×56 顶成 84×76≈正圆=用户点名的球)。 */
    var cx = model.axisFirst + model.axisPitch * posV
    val cy = model.pillCy
    val nodeL = cx - model.pillW / 2f
    val nodeT = cy - model.pillH / 2f
    val nodeW = model.pillW
    val nodeH = model.pillH
    val pillR = min(nodeW / 2f, nodeH / 2f)
    /* ❗无 pad(2026-11-12):旧位图路线的 pad 是采样余量;RenderEffect 路线
       节点即药丸矩形——带 pad 的话 lens shader 对形状外像素输出 NaN(透明)。 */
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
        /* ---- 玻璃源 = miuix-blur(KernelSU 系 manager 同款,ReSuKiSU 实证同机;
             照抄 example IosLiquidGlassNavigationBar)。backdrop = PageSnap 跨窗
             快照适配(bar/药丸各一份实例,origin 组合期写入)。 ---- */
        val barBackdrop = remember { PageSnapBackdrop { winSize } }
        val pillBackdrop = remember { PageSnapBackdrop { winSize } }
        val pillHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = 90f)

        /* ---- 栏体玻璃(照抄 bar Row):节点=栏矩形;effects=padding 40dp 采样
             模型(P3 首项,PageSnap 请求 pad 同步 40dp+)+vibrancy+blur 4dp+
             lens 24/24;highlight=BloomStroke 重力旋转 α0.75;surface=栏体
             tint(定稿全量 pillTint);按压胀大 layerBlock 驱动=liftV。 ---- */
        barBackdrop.nodeL = bar.left
        barBackdrop.nodeT = bar.top
        /* ---- 栏体玻璃 = FlClash pill 实机视觉复刻(11-16 shader 逐行破案:
             sampleBehind 在 uHaze>0 只采 haze 纹理,且 haze capture 用
             Paint(黑@0.72) 调制绘制=背景压暗到 28% 的黑罩——15.6σ 模糊只是
             载体,「72% 糊+28% 清晰」是从注释误读;这就是 FlClash 栏内看不到
             文字的原因。实机视觉=均匀深灰/白玻璃微透糊内容+字不可见。
             层序:糊(15.6σ+lens 串后)+黑罩+tint+rim。 ---- */
        val navSat = if (colors.dark) 1.925f else 2.1f
        val scrimAlpha = if (colors.dark) 0.5f else 0.15f
        val rimP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.WHITE
            alpha = 90   // FlClash rim/hairline 0.35
        } }
        val scrimP = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        /* 糊层(全强度):blur 15.6σ + 栏缘折射(lens 串在 blur 后=折射采样糊后
           内容,FlClash shader 同构——折射清晰原图会把大字原样卷进栏内=乱码感) */
        Box(Modifier
            .sizePx(bar.width(), bar.height())
            .offsetPx(bar.left, bar.top)
            .drawBackdrop(
                backdrop = barBackdrop,
                shape = { barShape },
                effects = {
                    PageSnap.version   // 快照更新 → 本节点重绘(miuix observeReads 捕获此读)
                    colorControls(brightness = 0f, contrast = 1f, saturation = navSat)
                    blur(15.6f * density, 15.6f * density)
                    lens(refractionHeight = 24f * density, refractionAmount = 24f * density)
                },
                onDrawSurface = { },
            )
        ) { }
        /* 黑罩+tint+rim 层:黑罩(FlClash haze capture 的黑调制等效,深 0.5/浅
           0.15)+sfTint(pill 预设口径)+边缘白线 */
        Box(Modifier
            .sizePx(bar.width(), bar.height())
            .offsetPx(bar.left, bar.top)
            .drawBehind {
                ntc { c ->
                    val r = bar.height() / 2f
                    scrimP.color = android.graphics.Color.BLACK
                    scrimP.alpha = (scrimAlpha * 255).toInt()
                    c.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, r, r, scrimP)
                    c.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, r, r, tintP)
                    val sw = 1f * density
                    rimP.strokeWidth = sw
                    c.drawRoundRect(
                        bar.left + sw / 2f, bar.top + sw / 2f,
                        bar.right - sw / 2f, bar.bottom - sw / 2f,
                        r - sw / 2f, r - sw / 2f, rimP)
                }
            }
        ) { }

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
        /* ---- 药丸(常驻,2026-11-15 终版=catalog LiquidBottomTabs 口径):
             effects=纯 lens(10·p,14·p,CA0.5),无 blur/vibrancy;rest=清玻璃
             +黑10%·(1−p) 压暗指示透出生背景,按压折射渐入+highlight/内影。
             节点=药丸矩形无 pad(lens 自扩录制面);图标按 prism 语义由
             镜内层重绘不折射;速度挤压内建于节点尺寸(jellyV)。 ---- */
        if (Build.VERSION.SDK_INT >= 33) {
            pillBackdrop.nodeL = nodeL
            pillBackdrop.nodeT = nodeT
            Box(Modifier
                .sizePx(nodeW, nodeH)
                .offsetPx(nodeL, nodeT)
                .graphicsLayer {
                    /* KSU layerBlock 语义:按压同比放大+速度挤压,绘制期读取
                       State(不触发重组);scale 溢出绘制=凸出栏体上下。 */
                    scaleX = (1f + 0.393f * model.liftV) * (1f + model.jellyV)
                    scaleY = (1f + 0.393f * model.liftV) * (1f - model.jellyV * 0.5f)
                }
                .drawBackdrop(
                    backdrop = pillBackdrop,
                    shape = { RoundedCornerShape((pillR / density).dp) },
                    effects = {
                        /* FlClash 药丸=accent 实底胶囊(11-16 实机图):无折射无磨砂,
                           透明度即材质的历史口径全部退役 */
                    },
                    highlight = { pillHighlight.value.copy(alpha = liftV) },
                    onDrawSurface = {
                        drawRect(Color(colors.pillAccent))   // accent 实底(暗色=玻璃镜下压暗版)
                    },
                )
                .innerShadow(
                    shape = RoundedCornerShape((pillR / density).dp),
                    shadow = {
                        InnerShadow(
                            radius = 8.dp * liftV,
                            alpha = liftV,
                        )
                    },
                )
            ) { }
        }

        /* ---- 镜内图标(prism items(inside)):画在玻璃镜之上,不折射。
             覆盖格全部高亮蓝(跨格双蓝,2026-11-14 平移——对齐官方跨格效果,
             原恒单格 selectedIndex)。尺寸=KSU LocalFloatingBottomBarTabScale
             语义:rest=1(统一尺寸只变色,1.08/1.05 恒放大=选中图标偏大已废),
             按压 lerp(1,1.2,p) ---- */
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                for (i in 0 until model.count) {
                    val cellCx = model.axisFirst + model.axisPitch * i
                    if (abs(cellCx - cx) >= model.axisPitch * 0.75f) continue
                    val tabScale = 1f + 0.2f * liftV
                    DockDraw.drawTab(c, i, i, false, cellCx, cy, contentH, density,
                        colors, iconPaths, iconP, textP,
                        magIcon = tabScale, magText = tabScale, onAccentBg = true)
                }
            }
        })

        /* ---- 触摸守卫 ---- */
        TouchGuard(model, bar)
    }
}

