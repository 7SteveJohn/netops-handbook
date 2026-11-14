package com.netops.handbook.lens.glass2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.netops.handbook.lens.glass2.PageSnap
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * PageSnap 跨窗快照 → miuix [Backdrop] 适配。
 *
 * 主窗内容源(壁纸+页面)=独立弹窗 dock 的玻璃输入；drawBackdrop 在节点本地
 * 坐标系里绘制，快照按窗口坐标对齐(节点原点=nodeL/nodeT,组合期每帧写入)。
 * downscale 缩放契约照抄库方 LayerBackdrop：downscaleFactor>1 时内容按
 * 1/sf 缩放进降采样录制面。
 */
internal class PageSnapBackdrop(
    private val winSize: () -> IntSize,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    /** 节点左上角(窗口坐标)。 */
    var nodeL: Float = 0f
    var nodeT: Float = 0f

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        withTransform({
            if (downscaleFactor > 1) {
                val inv = 1f / downscaleFactor
                scale(inv, inv, Offset.Zero)
            }
            translate(-nodeL, -nodeT)
        }) {
            val snap = PageSnap.latest()
            if (snap != null) {
                PageSnap.version
                drawImage(
                    image = snap.asImageBitmap(),
                    dstOffset = IntOffset(PageSnap.snapL, PageSnap.snapT),
                    dstSize = IntSize(snap.width, snap.height),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                )
            } else {
                val (w, h) = winSize()
                val bmp = com.netops.handbook.lens.WallpaperPrefs.wallBitmap
                if (bmp != null) {
                    drawRect(
                        brush = ShaderBrush(wallShader(bmp, w.toFloat(), h.toFloat(), 0f, 0f)),
                        size = androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
                    )
                }
            }
        }
    }
}
