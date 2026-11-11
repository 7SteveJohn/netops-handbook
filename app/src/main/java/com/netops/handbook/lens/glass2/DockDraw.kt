package com.netops.handbook.lens.glass2

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Compose dock 的绘制工具层(2026-11-12 P2)。
 *
 * 图标 Path / 配色 / 折射 shader 组全部 1:1 移植自 LensBarView(参数逐值对齐,
 * 真源注释保留)。玻璃效果 = 平台 RenderEffect(见 GlassProbeActivity P0 探针,
 * 2026-11-12 真机判活):blur→vibrancy→lens 三级嵌套 graphicsLayer。
 */
object DockDraw {
    private const val TAG = "GlassDock"

    /* ================= 图标(sprite.svg d 原文,与旧 LensBarView 逐字节一致) ================= */

    val ICON_PATHS: Array<Array<String>> = arrayOf(
        arrayOf("m12 3 9 5-9 5-9-5 9-5Z", "m3 13 9 5 9-5", "m3 17.5 9 5 9-5"),          // 学习 i-layers
        arrayOf(
            "M14.6 6.4a4.5 4.5 0 0 0 5.9 5.9l-8.3 8.3a2.4 2.4 0 0 1-3.4 0l-2.5-2.5a2.4 2.4 0 0 1 0-3.4Z",
            "M14.6 6.4 18 3l3 3-3.4 3.4"                                                  // 排障 i-wrench
        ),
        arrayOf("R2.5,4,19,16,2.2", "m6.5 9.5 3 2.7-3 2.7", "M12.5 15.4h5"),              // 字典 i-terminal
        arrayOf("C12,8,4", "M4.5 20.5a7.5 7.5 0 0 1 15 0")                                // 我的 i-user
    )

    val TAB_LABELS = arrayOf("学习", "排障", "字典", "我的")

    /** 图标 Path(24 viewBox → 22dp,stroke 1.7/24 随缩放)。 */
    fun buildIconPaths(density: Float): Array<Array<Path>> {
        val s = 22f * density / 24f
        val rect = android.graphics.RectF()
        return Array(ICON_PATHS.size) { i ->
            Array(ICON_PATHS[i].size) { k ->
                val seg = ICON_PATHS[i][k]
                val p = Path()
                try {
                    if (seg.startsWith("R")) {
                        val v = seg.substring(1).split(",")
                        val x = v[0].toFloat(); val y = v[1].toFloat()
                        rect.set(x, y, x + v[2].toFloat(), y + v[3].toFloat())
                        p.addRoundRect(rect, v[4].toFloat(), v[4].toFloat(), Path.Direction.CW)
                    } else if (seg.startsWith("C")) {
                        val v = seg.substring(1).split(",")
                        p.addCircle(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), Path.Direction.CW)
                    } else {
                        androidx.core.graphics.PathParser.createPathFromPathData(seg)?.let { p.set(it) }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "图标 path 解析失败 $i", t)
                }
                val m = android.graphics.Matrix()
                m.setScale(s, s)
                p.transform(m)
                p
            }
        }
    }

    /** 图标描边画笔(SVG stroke-width 1.7 @24 系,随 22/24 缩放)。 */
    fun iconPaint(density: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.7f * (22f * density / 24f)
    }

    fun textPaint(density: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 10f * density
    }

    /* ================= 配色(逐值对齐 01-tokens.css / KernelSU / FlClash) ================= */

    class Colors(val dark: Boolean) {
        var railFill = 0; private set
        var barFill = 0; private set
        var sfTint = 0; private set
        var barStroke = 0; private set
        var sliderFill = 0; private set
        var sliderStroke = 0; private set
        var icon = 0; private set
        var text = 0; private set
        var barShadow = 0; private set
        var active = 0; private set
        var baseWhite = 0; private set   // 药丸白底(dark 74,74,80 / light 252,252,252)

        init {
            if (dark) {
                railFill = Color.argb(204, 24, 24, 26)
                barFill = Color.argb(255, 28, 28, 30)
                sfTint = Color.argb(160, 33, 31, 38)     // KernelSU surfaceContainer(dark) @55%(用户 11-07 定)
                barStroke = Color.argb(70, 255, 255, 255)
                sliderFill = Color.argb(255, 44, 44, 46)
                sliderStroke = Color.argb(26, 255, 255, 255)
                icon = Color.argb(153, 235, 235, 245)
                text = Color.argb(153, 235, 235, 245)
                barShadow = Color.argb(51, 0, 0, 0)
                active = Color.rgb(10, 132, 255)          // --accent 暗 #0a84ff
                baseWhite = Color.rgb(74, 74, 80)
            } else {
                railFill = Color.argb(178, 255, 255, 255)
                barFill = Color.WHITE
                sfTint = Color.argb(160, 243, 237, 247)   // M3 surfaceContainer #F3EDF7 @55%
                barStroke = Color.argb(120, 255, 255, 255)
                sliderFill = Color.argb(31, 37, 99, 235)  // #2563EB @12%(FlClash 实测)
                sliderStroke = Color.argb(18, 37, 99, 235)
                icon = Color.rgb(66, 71, 84)              // #424754(FlClash 实测)
                text = Color.rgb(66, 71, 84)
                barShadow = Color.argb(26, 0, 0, 0)
                active = Color.rgb(37, 99, 235)           // --accent 浅 #2563eb
                baseWhite = Color.rgb(252, 252, 252)
            }
        }
    }

    /* ================= 玻璃效果(平台 RenderEffect,P0 探针同构) ================= */

    /** 饱和度 1.5 色彩矩阵(KernelSU colorControls:contrast 1.0=不调,sat 1.5)。 */
    fun vibrancyEffect(): ComposeRenderEffect {
        val s = 1.5f
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0f, 0f,
            0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0f, 0f,
            0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        return RenderEffect.createColorFilterEffect(
            android.graphics.ColorMatrixColorFilter(cm)).asComposeRenderEffect()
    }

    fun blurEffect(sigmaPx: Float): ComposeRenderEffect =
        RenderEffect.createBlurEffect(sigmaPx, sigmaPx, Shader.TileMode.CLAMP)
            .asComposeRenderEffect()

    /**
     * 动态 uniform 的折射 shader 载体:uniform 变化时重建 RenderEffect
     * (平台 RenderEffect 无 then 链、uniform 快照语义,见 P0 探针注释)。
     * 静止期 key 不变 → 零重建;拖拽/按压期每帧一次小对象分配(Skia 缓存
     * SkRuntimeEffect 程序,仅 uniform buffer 新建)。
     */
    class LensFx(shaderSource: String) {
        val shader = RuntimeShader(shaderSource)
        private var cached: ComposeRenderEffect? = null
        private var lastKey: FloatArray? = null

        fun effect(key: FloatArray): ComposeRenderEffect {
            val last = lastKey
            val cached0 = cached
            if (cached0 != null && last != null && last.size == key.size) {
                var same = true
                for (i in key.indices) if (last[i] != key[i]) { same = false; break }
                if (same) return cached0
            }
            lastKey = key.copyOf()
            return RenderEffect.createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect().also { cached = it }
        }
    }

    /** 栏体投影画笔(dropShadow radius 10dp:浅 0.1 / 暗 0.2)。 */
    fun shadowPaint(colors: Colors, density: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.barShadow
        maskFilter = BlurMaskFilter(10f * density, BlurMaskFilter.Blur.NORMAL)
    }

    /* ================= 单格绘制(横排:图标+文字;真源 drawTabsOn) ================= */

    /** @param activeIdx 主色格;@param forceOff 强制灰(mirror 的跨越格) */
    fun drawTab(
        c: Canvas, i: Int, activeIdx: Int, forceOff: Boolean,
        cellCx: Float, pillCy: Float, contentH: Float, density: Float,
        colors: Colors, iconPaths: Array<Array<Path>>, iconP: Paint, textP: Paint,
    ) {
        val on = !forceOff && i == activeIdx
        val color = if (on) colors.active else colors.icon
        val subs = iconPaths[i]
        val iconTop = pillCy - contentH / 2f
        c.save()
        c.translate(cellCx - 11f * density, iconTop)
        iconP.color = color
        for (k in subs.indices) {
            /* layers(第 0 图标)第三笔在 SVG 里 opacity=".5",与 CSS 渲染对齐 */
            iconP.alpha = if (i == 0 && k == subs.size - 1) 128 else 255
            c.drawPath(subs[k], iconP)
        }
        c.restore()
        val fm = textP.fontMetrics
        val baseline = iconTop + 22f * density + 2f * density - fm.ascent
        textP.color = color
        c.drawText(TAB_LABELS[i], cellCx, baseline, textP)
    }

    /** 竖排单格(仅图标,compact;真源 drawTabsOn 竖排分支)。 */
    fun drawRailIcon(
        c: Canvas, i: Int, selected: Boolean, cellCy: Float, crossC: Float, sc: Float,
        density: Float, colors: Colors, iconPaths: Array<Array<Path>>, iconP: Paint,
    ) {
        val subs = iconPaths[i]
        iconP.color = if (selected) colors.active else colors.icon
        c.save()
        c.translate(crossC, cellCy - 11f * density * sc)
        c.scale(sc, sc)
        c.translate(-11f * density, -11f * density)
        for (k in subs.indices) {
            iconP.alpha = if (i == 0 && k == subs.size - 1) 128 else 255
            c.drawPath(subs[k], iconP)
        }
        c.restore()
    }
}
