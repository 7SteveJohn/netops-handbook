package com.netops.handbook.lens.glass2

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * P1 宿主(2026-11-12):把既有 View 树(anchor=原 setContentView 的根)包进
 * Compose 的 AndroidView,玻璃 dock 叠加在同树顶层。
 *
 * View 树内部零改动:触摸/insets/生命周期/转场全部原样工作;唯一的变化是
 * 根节点从 window 直挂变成「ComposeView > AndroidView > 旧根」。
 */
fun ComponentActivity.installGlassHost(anchor: FrameLayout) {
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
}
