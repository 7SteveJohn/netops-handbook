package com.netops.handbook.lens.glass2

import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * dock 独立弹窗宿主（2026-11-14 双机实证终态）。
 *
 * 为什么必须独立窗：MagicOS(HONOR 手机+平板双机探针 t1-t9)上，同一窗口内
 * 只要存在 dock 子树（record/AGSL/位图绘全部排除后依然），普通 Compose
 * 内容就不被合成器绘制；Offscreen 层包装、双 ComposeView 树均救不回。
 * dock 进专属 PopupWindow=自有 window/surface/composer 完全隔离——旧
 * LensBarView 同拓扑在手机上稳定跑过数月、M3 时代平板双树+AGSL 全绿，
 * 双重实证。
 *
 * - 弹窗尺寸 = GlassDock 自算矩形(竖排=轨条/横排=栏带)+外扩 barPad；
 * - GlassDock 内部全用窗口本地坐标，GraphicsLayer 平移 -origin 折进弹窗
 *   （DockBus.dockOrigin，Geometry 变化→dismiss+重建）；
 * - 带尺寸弹窗：带外触摸天然落主窗页面，无需任何转发；
 * - 覆盖层开合（DockBus.pushOverlaid→onOverlaid）→ dismiss/reshow；
 * - ComposeView 在弹窗内无 ViewTree 链，手动挂 Activity 的
 *   LifecycleOwner/SavedStateRegistryOwner。
 */
class DockPopupHost(private val activity: android.app.Activity) {

    private var popup: PopupWindow? = null
    private var lastRect: RectF? = null
    private var lastPad = -1f
    private var overlaid = false

    init {
        DockBus.onDockRect = { r, pad -> update(r, pad) }
        DockBus.onOverlaid = { o -> setOverlaid(o) }
    }

    /** 几何入口（computeDockGeometry 尾 + GlassDock SideEffect）。内部去重。 */
    fun update(r: RectF, padPx: Float) {
        val last = lastRect
        if (last != null && lastPad == padPx &&
            last.left == r.left && last.top == r.top &&
            last.right == r.right && last.bottom == r.bottom
        ) return
        lastRect = RectF(r)
        lastPad = padPx
        if (overlaid) return
        rebuild()
    }

    fun setOverlaid(o: Boolean) {
        overlaid = o
        if (o) {
            popup?.dismiss()
            popup = null
            return
        }
        if (lastRect != null) rebuild()
    }

    /** 释放（Activity 销毁）。 */
    fun detach() {
        popup?.dismiss()
        popup = null
    }

    private fun rebuild() {
        val r = lastRect ?: return
        val w = (r.width() + 2 * lastPad).toInt().coerceAtLeast(4)
        val h = (r.height() + 2 * lastPad).toInt().coerceAtLeast(4)
        val x = (r.left - lastPad).toInt().coerceAtLeast(0)
        val y = (r.top - lastPad).toInt().coerceAtLeast(0)
        popup?.dismiss()
        popup = null
        activity.window?.decorView?.post {
            if (overlaid) return@post
            try {
                DockBus.dockOrigin = IntOffset(x, y)   // 先于 setContent：首帧即正确坐标
                val cv = ComposeView(activity).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                    )
                    /* 弹窗内容视图无 ViewTree 链：手动挂 Activity 的
                       Lifecycle/SavedState，Compose 才能取到重组环境。 */
                    this.setViewTreeLifecycleOwner(
                        activity as? androidx.lifecycle.LifecycleOwner)
                    this.setViewTreeSavedStateRegistryOwner(
                        activity as? androidx.savedstate.SavedStateRegistryOwner)
                }
                cv.setContent { GlassDockHost() }
                val p = PopupWindow(cv, w, h, false).apply {
                    isClippingEnabled = false      // 药丸按压漫出 ≤10dp 允许越界绘制
                    setBackgroundDrawable(null)
                    elevation = 0f
                }
                p.showAtLocation(activity.window.decorView, Gravity.NO_GRAVITY, x, y)
                popup = p
                /* ❗recomposer 按 rootView(=PopupDecorView) 找 owner——decor 上
                   必须也挂一份；attach 发生在下一帧 traversal，此处补挂来得及 */
                (cv.parent as? android.view.View)?.let { decor ->
                    decor.setViewTreeLifecycleOwner(
                        activity as? androidx.lifecycle.LifecycleOwner)
                    decor.setViewTreeSavedStateRegistryOwner(
                        activity as? androidx.savedstate.SavedStateRegistryOwner)
                }
            } catch (t: Throwable) {
                Log.w("DockPopup", "弹窗创建失败", t)
            }
        }
    }
}
