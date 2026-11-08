package com.netops.handbook.lens

import android.content.Context
import android.view.View
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 单窗口根容器（KernelSU 同构的最外层）。
 *
 * pages 层包进 Compose 树并 record 进 GraphicsLayer —— 后续 dock / 顶栏玻璃的
 * RuntimeShader 以该层为 uContent 输入（GPU 逐帧零回读，KernelSU 同机制）。
 * 此前 dock 走 PopupWindow + PixelCopy/anchor.draw 拷贝录制，跨 Window 拿不到
 * GPU 纹理，只能靠降频录制，这就是「停一秒」的架构根因。
 */
@Composable
fun NetOpsRoot(pages: View) {
    // 独立 GraphicsLayer（非 OwnedLayer——外部挂 RenderEffect 到 OwnedLayer 会
    // RenderThread SEGV，平板实锤）：pages 每帧 record 进来，dock/顶栏挂 shader 采样。
    // record = RenderNode 指令记录，GPU 一次执行，成本远低于回读拷贝。
    val backdrop = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize()) {
        // ❗实验：dock/顶栏声明在 pages 之前（绘制顺序取反，验证 z 序假设）
        NetOpsTopBar(backdrop, Modifier.align(Alignment.TopCenter))
        NetOpsDock(backdrop, Modifier.align(Alignment.BottomCenter))
        AndroidView(
            factory = { pages },
            modifier = Modifier.fillMaxSize().zIndex(-1f).drawWithContent {
                android.util.Log.i("NetOpsRoot", "pages draw, size=" + size.width + "x" + size.height)
                backdrop.record(
                    density = androidx.compose.ui.unit.Density(density),
                    layoutDirection = layoutDirection,
                    size = androidx.compose.ui.unit.IntSize(
                        size.width.roundToInt(), size.height.roundToInt()),
                ) {
                    this@drawWithContent.drawContent()
                }
                android.util.Log.i("NetOpsRoot", "record done, drawing content")
                drawContent()
            },
        )
    }
}

/** Java 侧入口（`ComposeView.setContent` 是 Kotlin 扩展）。 */
fun createNetOpsRoot(context: Context, pages: View): View =
    ComposeView(context).apply { setContent { NetOpsRoot(pages) } }
