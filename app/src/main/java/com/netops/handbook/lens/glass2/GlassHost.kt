package com.netops.handbook.lens.glass2

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * P1 宿主(2026-11-12,2026-11-14 分流改造)。
 *
 * 平板/非 HONOR(CAPTURE_PAGES=true):原样——旧根包进 Compose AndroidView,
 * 页面 record 同帧折射(KernelSU 结构,平板真机全绿)。
 *
 * HONOR(MagicOS,CAPTURE_PAGES=false):**兄弟节点版**——旧根(View 树含 WebView)
 * 直接 setContentView 直挂 window,MagicOS 定制 WebView 进 Compose AndroidView
 * 连直绘都不显示(13:22 实测:record 死 + 直绘也死,独立合成层被宿主包裹破坏);
 * dock 玻璃用 ComposeView 作为同窗兄弟节点叠加在旧根之上,玻璃内容源 =
 * PageSnap(PixelCopy 直读已合成 Surface,旧路线跑过多轮已验证)。
 * 触摸 = GlassDock 内 TouchGuard 全窗自判区域,带外转发 anchor(同窗同语义)。
 */
fun ComponentActivity.installGlassHost(anchor: FrameLayout) {
    if (DockBus.CAPTURE_PAGES) {
        val holder = FrameLayout(this)
        setContentView(holder)
        val compose = ComposeView(this)
        compose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        holder.addView(
            compose,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        compose.setContent { GlassDockHost(anchor) }
    } else {
        /* HONOR(MagicOS)兄弟节点版:旧根直挂,玻璃层 addContentView 叠加 */
        setContentView(anchor)
        val overlay = FrameLayout(this)
        val compose = ComposeView(this)
        compose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        overlay.addView(
            compose,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addContentView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        compose.setContent { GlassDockHost(anchor) }
    }
}
