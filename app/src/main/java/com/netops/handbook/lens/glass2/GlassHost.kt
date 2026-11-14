package com.netops.handbook.lens.glass2

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * P1 宿主(2026-11-12,2026-11-14 M3 终局改造)。
 *
 * 单一路径：旧根(View 层=壁纸 backdrop)包进 Compose AndroidView，
 * Compose 页(PagesOverlay)为 dock 同组合兄弟节点——M3 首验实锤 MagicOS
 * 「AndroidView 内嵌套 ComposeView 内容不合成」，页面必须与 dock 同组合；
 * WebView 已摘除(M1d-3)，树里只剩 View/Compose，record 全绿(探针实证)。
 *
 * 回调用 GlassHostHooks 接口（Java 匿名类友好——Kotlin 函数类型的 Unit
 * 返回在 Java lambda 里会因 void↔Unit 推断失败）。
 */
interface GlassHostHooks {
    fun onGo(route: String, arg: String?)
    fun onExport()
    fun onImport()
    fun onSettings()
    fun onState(id: String, kind: String, on: Boolean)
}

fun ComponentActivity.installGlassHost(
    anchor: FrameLayout,
    hooks: GlassHostHooks,
) {
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
    compose.setContent {
        GlassDockHost(anchor) {
            com.netops.handbook.ui.pages.PagesOverlay(
                data = { com.netops.handbook.ui.PageData.dataRoot },
                onGo = { r, a -> hooks.onGo(r, a) },
                onExport = { hooks.onExport() }, onImport = { hooks.onImport() },
                onState = { id, kind, on -> hooks.onState(id, kind, on) })
        }
    }
}
