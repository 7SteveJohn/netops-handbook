package com.netops.handbook.lens.glass2

import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * P1 宿主(2026-11-12;2026-11-14 两轮改造)。
 *
 * 三层结构(View 层退役)：
 *   holder{ under(root=壁纸 backdrop+View 残页,GONE 为主) → ComposeView → over(覆盖层) }
 * - Compose 树内不再有 AndroidView：MagicOS interop 三坑(嵌套 ComposeView
 *   不合成 / View 盖 Compose 兄弟 / guard 2×bar 双重定位)全因 View 层内嵌。
 *   壁纸与页面均为 Compose 本体(record 源)，覆盖层(搜索/设置/抽屉)留 View
 *   实现放在 over 层(普通 FrameLayout 兄弟层序，全设备可靠)。
 * - 返回 ComposeView 给 Java 侧：AppBarView 的非按钮区触摸转发
 *   (forwardToContent→anchor.dispatchTouchEvent)改指 Compose 树。
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
    under: View,
    over: View,
    hooks: GlassHostHooks,
): ComposeView {
    val holder = FrameLayout(this)
    setContentView(holder)
    holder.addView(
        under,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
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
    holder.addView(
        over,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    compose.setContent {
        GlassDockHost {
            com.netops.handbook.ui.pages.PagesOverlay(
                data = { com.netops.handbook.ui.PageData.dataRoot },
                onGo = { r, a -> hooks.onGo(r, a) },
                onExport = { hooks.onExport() }, onImport = { hooks.onImport() },
                onState = { id, kind, on -> hooks.onState(id, kind, on) })
        }
    }
    return compose
}
