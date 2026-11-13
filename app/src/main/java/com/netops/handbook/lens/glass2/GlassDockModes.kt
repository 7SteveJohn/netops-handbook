package com.netops.handbook.lens.glass2

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min

/* 贴底/无玻璃/竖排三种平面形态 + 触摸守卫(2026-11-12 P2)。
   从 GlassDock.kt 拆出,Mimosa 关卡 ≤450 行/提交。 */
/* ================= 横排 · 贴底 M3(2026-11-10 拍板形态) ================= */

@Composable
internal fun DockedFlat(
    model: DockModel,
    bar: RectF,
    colors: DockDraw.Colors,
    iconPaths: Array<Array<Path>>,
    iconP: Paint,
    textP: Paint,
    contentH: Float,
    anchor: View,
    @Suppress("UNUSED_PARAMETER") refreshTick: Int,
    /* 贴底过渡(2026-11-14 平移旧 dockedProgress):0=悬浮胶囊 1=贴底。
       圆角 half↔14dp 插值、M3 指示器淡入;interactive=false 时不挂守卫
       (过渡期手势归悬浮层,到位后才接管)。 */
    progress: Float = 1f,
    interactive: Boolean = true,
) {
    val density = GlassDockEnv.density()
    val posV = model.posV
    val half = min(bar.width(), bar.height()) / 2f
    val r = half + (14f * density - half) * progress
    val fillP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.barFill } }
    val strokeP = remember(colors, density) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.barStroke; strokeWidth = density; style = Paint.Style.STROKE } }
    val indP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (colors.dark) android.graphics.Color.argb(46, 10, 132, 255) else android.graphics.Color.argb(28, 37, 99, 235) } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                c.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, r, r, fillP)
                c.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, r, r, strokeP)
                val indA = (255f * progress).toInt()
                if (indA > 0) {
                    indP.alpha = indA
                    val indCx = model.axisFirst + model.axisPitch * posV
                    val indCy = model.pillCy - 7f * density
                    c.drawRoundRect(indCx - 32f * density, indCy - 16f * density,
                        indCx + 32f * density, indCy + 16f * density,
                        16f * density, 16f * density, indP)
                }
                val actIdx = posV.toInt().coerceIn(0, model.count - 1)
                for (i in 0 until model.count) {
                    val cellCx = model.axisFirst + model.axisPitch * i
                    DockDraw.drawTab(c, i, actIdx, i != actIdx, cellCx, model.pillCy, contentH,
                        density, colors, iconPaths, iconP, textP)
                }
            }
        })
        if (interactive) TouchGuard(model, bar, anchor)
    }
}

/* ================= 横排 · 无玻璃回退(档位=无) ================= */

@Composable
internal fun FloatFlat(
    model: DockModel,
    bar: RectF,
    colors: DockDraw.Colors,
    iconPaths: Array<Array<Path>>,
    iconP: Paint,
    textP: Paint,
    contentH: Float,
    anchor: View,
    @Suppress("UNUSED_PARAMETER") refreshTick: Int,
) {
    val density = GlassDockEnv.density()
    val posV = model.posV
    val jellyV = model.jellyV
    val r = bar.height() / 2f
    val fillP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.barFill } }
    val strokeP = remember(colors, density) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.barStroke; strokeWidth = density; style = Paint.Style.STROKE } }
    val shP = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(38, 15, 23, 42) } }
    val slP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.sliderFill } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                c.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, r, r, fillP)
                c.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, r, r, strokeP)
                /* 滑块胶囊(accent 12%,画在图标之下) */
                val cx = model.axisFirst + model.axisPitch * posV
                val cy = model.pillCy
                val g = 16f * density
                val hw = (model.pillW + g) / 2f * (1f + jellyV)
                val hh = (model.pillH + g) / 2f * (1f - jellyV * 1.4f)
                c.drawRoundRect(cx - hw, cy - hh + density, cx + hw, cy + hh + 2f * density, hh, hh, shP)
                c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, hh, hh, slP)
                val actIdx = posV.toInt().coerceIn(0, model.count - 1)
                for (i in 0 until model.count) {
                    val cellCx = model.axisFirst + model.axisPitch * i
                    DockDraw.drawTab(c, i, actIdx, i != actIdx, cellCx, cy, contentH,
                        density, colors, iconPaths, iconP, textP)
                }
            }
        })
        TouchGuard(model, bar, anchor)
    }
}

/* ================= 竖排侧栏(FlClash 平面口径,2026-10-29 定案) ================= */

@Composable
internal fun RailFlat(
    model: DockModel,
    rail: RectF,
    colors: DockDraw.Colors,
    iconPaths: Array<Array<Path>>,
    iconP: Paint,
    anchor: View,
    @Suppress("UNUSED_PARAMETER") refreshTick: Int,
) {
    val density = GlassDockEnv.density()
    val posV = model.posV
    val sc = railItemDp() / 40f
    val r = min(10f * density, rail.width() / 2f)
    val fillP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.railFill } }
    val selP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (colors.dark) 0x1FFFFFFF.toInt() else 0x14000000 } }
    val barP = remember(colors) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.active } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawBehind {
            ntc { c ->
                c.drawRoundRect(rail.left, rail.top, rail.right, rail.bottom, r, r, fillP)
                val item = 40f * density
                val idx = posV.toInt().coerceIn(0, model.count - 1)
                val crossC = rail.left + rail.width() / 2f
                val selCy = model.axisFirst + model.axisPitch * idx
                c.drawRoundRect(crossC - item / 2f, selCy - item / 2f,
                    crossC + item / 2f, selCy + item / 2f, 10f * density, 10f * density, selP)
                val iy = model.axisFirst + model.axisPitch * posV
                val bw = 3f * density; val bh = 16f * density; val x = rail.left + 4f * density
                c.drawRoundRect(x, iy - bh / 2f, x + bw, iy + bh / 2f, bw / 2f, bw / 2f, barP)
                for (i in 0 until model.count) {
                    DockDraw.drawRailIcon(c, i, i == idx, model.axisFirst + model.axisPitch * i,
                        crossC, sc, density, colors, iconPaths, iconP)
                }
            }
        })
        TouchGuard(model, rail, anchor)
    }
}

/* ================= 触摸守卫(旧 onTouchEvent 1:1 语义) ================= */

/**
 * 药丸带内 = dock 手势;带外 = 转发内容层(旧 forwardToPage:改派 anchor 根
 * 分发,命中测试自动找当前可见页,原生页与 WebView 通用,2026-11-10)。
 */
internal class TouchGuardView(
    context: Context,
    private val model: DockModel,
    private val anchor: View,
) : View(context) {
    private val area = RectF()
    private val scr = IntArray(2)
    private val ascrr = IntArray(2)
    private var pillMode = false

    fun setGeometry(r: RectF) { area.set(r) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        getLocationOnScreen(scr)
        anchor.getLocationOnScreen(ascrr)
        val offX = (scr[0] - ascrr[0]).toFloat()
        val offY = (scr[1] - ascrr[1]).toFloat()
        val wx = e.x + offX
        val wy = e.y + offY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pillMode = model.inPillBand(wy)
                if (pillMode) {
                    model.onDown(wx, wy, e.getPointerId(0), e.eventTime)
                } else {
                    forward(e, offX, offY)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pillMode) { forward(e, offX, offY); return true }
                if (!model.pressed) return true
                if (e.findPointerIndex(model.activePointer()) < 0) {
                    model.onPointerLost(); return true
                }
                val pi = e.findPointerIndex(model.activePointer())
                model.onMove(e.getX(pi) + offX, e.getY(pi) + offY,
                    model.activePointer(), e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!pillMode) { forward(e, offX, offY); return true }
                model.onUp(e.actionMasked == MotionEvent.ACTION_CANCEL)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (pillMode) {
                /* 多指:只跟起手那根(旧 findPointerIndex 守卫同语义) */
            }
            MotionEvent.ACTION_POINTER_UP -> if (pillMode) {
                /* 主指在多指中抬起:留给下一个 MOVE 的 onPointerLost 收场(旧同) */
            }
        }
        return true
    }

    private fun forward(e: MotionEvent, offX: Float, offY: Float) {
        val c = MotionEvent.obtain(e)
        c.offsetLocation(offX, offY)
        try {
            anchor.dispatchTouchEvent(c)
        } catch (t: Throwable) {
            android.util.Log.w("GlassDock", "转发触摸到主窗口失败", t)
        } finally {
            c.recycle()
        }
    }
}

@Composable
internal fun TouchGuard(model: DockModel, area: RectF, anchor: View) {
    /* ❗全窗守卫(2026-11-12):AndroidView+Modifier.offset 在本机 interop 下被
       双重定位(实测 guard 落在 2×bar 处,点按全穿透)。旧 PopupWindow 本就是
       「收到→带内接管/带外转发」模型——守卫铺满窗口、事件里按窗口坐标判区,
       语义与旧实现 1:1,定位问题不存在。 */
    AndroidView(
        factory = { ctx -> TouchGuardView(ctx, model, anchor).apply { isClickable = true } },
        update = { it.setGeometry(area) },
        modifier = Modifier.fillMaxSize()
    )
}

/* ================= 小工具 ================= */

internal fun DrawScope.ntc(block: (Canvas) -> Unit) {
    block(drawContext.canvas.nativeCanvas)
}

/** density 快照(非 Compose 绘制工具读取;GlassDock 每次重组刷新)。 */
object GlassDockEnv {
    @Volatile private var d: Float = 1f
    fun set(v: Float) { d = v }
    fun density(): Float = d
}

/** px 定尺寸(内容固定大小,几何来自 JS 物理像素)。 */
internal fun Modifier.sizePx(w: Float, h: Float): Modifier = this.then(
    layout { measurable, _ ->
        val wi = w.toInt().coerceAtLeast(0)
        val hi = h.toInt().coerceAtLeast(0)
        val placeable = measurable.measure(
            androidx.compose.ui.unit.Constraints.fixed(wi, hi)
        )
        layout(wi, hi) { placeable.place(0, 0) }
    }
)

internal fun Modifier.offsetPx(x: Float, y: Float): Modifier = this.then(
    offset { IntOffset(x.toInt(), y.toInt()) }
)
