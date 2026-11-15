package com.netops.handbook.lens.glass2

import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * P1 宿主(2026-11-12;2026-11-14 三轮改造)。
 *
 * 四层结构(View 层退役终态)：
 *   holder{ under(root=壁纸 backdrop+View 残页) → ComposeViewA(壁纸+页面)
 *           → ComposeViewB(dock) → over(搜索/设置/抽屉 View 覆盖层) }
 * - ❗❗内容与 dock 必须是**两棵独立 ComposeView 树**(2026-11-14 双机实证)：
 *   同一棵树内 dock 子树存在时普通页面内容不被 MagicOS 合成器绘制(手机+
 *   平板同症状；Offscreen 层包装也救不回 t8)；两棵树分立=M3 时代平板
 *   已验证的共存形态(AGSL dock+页面树全绿)。纯 View 层堆叠，零 interop。
 * - B(dock)全窗透明，带外不拦截(Compose 未命中即回落)，带内=dock 手势；
 *   A 承载壁纸底(drawWithContent 同画布直绘,勿用 Image 节点=平板 z 提升盖页)
 *   与页面本体。
 * - 返回 A 给 Java 侧：AppBarView 非按钮区触摸转发(forwardToContent)指向它。
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

    /** M4-2：抽屉收起动画走完（dock/顶栏退位收回，Java pushOverlaidAll(false)）。 */
    fun onDrawerClosed()
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
    val composeA = newComposeView()
    holder.addView(
        composeA,
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
    composeA.setContent {
        GlassContentHost {
            com.netops.handbook.ui.pages.PagesOverlay(
                data = { com.netops.handbook.ui.PageData.dataRoot },
                onGo = { r, a -> hooks.onGo(r, a) },
                onExport = { hooks.onExport() }, onImport = { hooks.onImport() },
                onState = { id, kind, on -> hooks.onState(id, kind, on) })
            /* M4-2 抽屉 Compose 版：PagesOverlay 之上的浮层（route 是页面，
               抽屉=覆盖态，不进路由 when）。 */
            com.netops.handbook.ui.pages.DrawerOverlay(
                data = { com.netops.handbook.ui.PageData.dataRoot },
                dark = com.netops.handbook.ui.AppShellState.isDark,
                onGo = { r, a -> hooks.onGo(r, a) },
                onClosed = { hooks.onDrawerClosed() })
        }
    }
    /* dock 已进独立弹窗(DockPopupHost)：与内容树完全隔离——同窗共存会在
       MagicOS 上毒杀页面内容(双机探针 t1-t9 实证)。 */
    com.netops.handbook.lens.glass2.DockPopupHost(this)
    return composeA
}

private fun ComponentActivity.newComposeView(): ComposeView {
    val v = ComposeView(this)
    v.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    return v
}

/** 内容树(树 A)：壁纸底同画布直绘 + 页面。 */
@Composable
private fun GlassContentHost(pages: @Composable () -> Unit) {
    val dark = com.netops.handbook.ui.AppShellState.isDark
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    /* 壁纸底同画布直绘(全屏 Crop 口径,深色压暗 0.36 同旧
                       ImageView ColorMatrix)——勿改 Image 节点(平板 z 提升)。 */
                    val bmp = com.netops.handbook.lens.WallpaperPrefs.wallBitmap
                    if (bmp != null) {
                        val im = bmp.asImageBitmap()
                        val s = maxOf(size.width / im.width, size.height / im.height)
                        val srcW = (size.width / s).toInt().coerceAtMost(im.width)
                        val srcH = (size.height / s).toInt().coerceAtMost(im.height)
                        drawImage(
                            image = im,
                            srcOffset = IntOffset(
                                (im.width - srcW) / 2, (im.height - srcH) / 2),
                            srcSize = IntSize(srcW, srcH),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            colorFilter = if (dark) ColorFilter.colorMatrix(
                                ColorMatrix().apply {
                                    setToScale(0.36f, 0.36f, 0.36f, 1f)
                                }) else null,
                            filterQuality = FilterQuality.Medium,
                        )
                    } else {
                        drawRect(if (dark) Color(0xFF0C0D10) else Color(0xFFF2F2F7))
                    }
                    drawContent()
                }
        ) {
            pages()
        }
    }
}
