package com.netops.handbook.lens.glass2

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
        if (interactive) TouchGuard(model, bar)
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
        TouchGuard(model, bar)
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
        TouchGuard(model, rail)
    }
}

/* ================= 触摸守卫(2026-11-14 纯 Compose 手势带) ================= */

/**
 * 旧实现 = 全窗 AndroidView 守卫(带内接管/带外转发 anchor View)。
 * View 层退役后 anchor 不在树上=转发死路;AndroidView interop 定位双坑
 * (2×bar 双重定位 / View 盖 Compose 兄弟)也不再可忍——守卫改纯 Compose:
 * 面积收口到 area 矩形(offsetPx+sizePx 定位,与 drawBehind 同基准),
 * 带外不拦截=触摸自然落到 Compose 页。
 *
 * 带内判定仍走 model.inPillBand(窗口本地 y):横排=栏带内(栏带外的横向
 * 余量自然落页);竖排=轨条全域(旧 rail 命中口径)。跟拍语义 1:1:
 * 多指只跟起手那根;跟拍指先抬而余指在场=onPointerLost;守卫协程被中断
 * (页面销毁/父级拦截)=onUp(cancelled) CANCEL 收场。
 */
@Composable
internal fun TouchGuard(model: DockModel, area: RectF) {
    if (area.width() <= 0f || area.height() <= 0f) return
    Box(
        Modifier
            /* ❗sizePx 先于 offsetPx(2026-11-14 实测)：offset-first 时节点不渲染
               也不命中(全窗可视化对照实证)；GlassBar 栏体节点同序(sizePx→
               offsetPx)定位正常。 */
            .sizePx(area.width(), area.height())
            .offsetPx(area.left, area.top)
            .pointerInput(model) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id.value.toInt()
                    if (!model.inPillBand(area.top + down.position.y)) {
                        return@awaitEachGesture   // 带外:放行给页面(不消费)
                    }
                    model.onDown(area.left + down.position.x,
                        area.top + down.position.y, id, down.uptimeMillis)
                    down.consume()
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val t = ev.changes.firstOrNull { it.id == down.id }
                            if (t == null || !t.pressed) {
                                /* 跟拍指收场:余指在场=onPointerLost(旧 MOVE
                                   findPointerIndex<0 分支);全抬=onUp 提交 */
                                if (ev.changes.any { it.pressed && it.id != down.id })
                                    model.onPointerLost()
                                else model.onUp(false)
                                break
                            }
                            model.onMove(area.left + t.position.x,
                                area.top + t.position.y, id, t.uptimeMillis)
                            ev.changes.forEach { it.consume() }
                        }
                    } finally {
                        if (model.pressed) model.onUp(true)   // 守卫中断=CANCEL
                    }
                }
            }
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
